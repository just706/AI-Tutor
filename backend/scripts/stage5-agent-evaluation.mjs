// 评测账本先持久化再发请求；未知结果和失败都不退款，重跑不能重置授权。
import assert from 'node:assert/strict';
import { createHash } from 'node:crypto';
import { closeSync, existsSync, fsyncSync, openSync, readFileSync, renameSync, unlinkSync, writeFileSync } from 'node:fs';

export function openBudget({ file, identity, cases, limit=40 }) {
  assert(Number.isSafeInteger(limit) && limit>0 && limit<=40, 'Invalid authorization limit');
  const lockFile = file + '.lock';
  const lock = openSync(lockFile, 'wx');
  let closed = false, active = null;
  const close = () => { if (!closed) { closed = true; closeSync(lock); unlinkSync(lockFile); } };
  const persist = state => {
    const temporary = file + '.tmp';
    const handle = openSync(temporary, 'w');
    try { writeFileSync(handle, JSON.stringify(state, null, 2)); fsyncSync(handle); }
    finally { closeSync(handle); }
    renameSync(temporary, file);
  };
  try {
    const state = existsSync(file) ? JSON.parse(readFileSync(file, 'utf8')) : {
      version: 1, identity, cases, limit, pairs: [], attempts: [], createdAt: new Date().toISOString(),
    };
    assert.equal(state.identity, identity, 'Budget identity changed');
    assert.deepEqual(state.cases, cases);
    assert.equal(state.limit, limit);
    assert.equal(state.version, 1);
    assert(state.attempts.length <= limit);
    for (const arm of ['java', 'agent']) {
      assert(state.attempts.filter(a => a.arm === arm).length <= (arm === 'java' ? 8 : 32));
      for (const caseId of cases) assert(state.attempts.filter(a => a.arm === arm && a.caseId === caseId).length <= (arm === 'java' ? 1 : 4));
    }
    for (const attempt of state.attempts) assert(cases.includes(attempt.caseId) && ['java', 'agent'].includes(attempt.arm));
    persist(state);
    return {
      get state() { return structuredClone(state); }, close,
      beginPair(caseId, arm) {
        assert(!closed && !active, 'Another pair is active');
        assert(cases.includes(caseId) && ['java', 'agent'].includes(arm), 'Unknown case or arm');
        assert(!state.pairs.some(p => p.caseId === caseId && p.arm === arm), 'Pair already started; no replay');
        active = { caseId, arm, startedAt: new Date().toISOString() };
        state.pairs.push(active); persist(state);
      },
      reserve(caseId, arm) {
        assert(!closed && active?.caseId === caseId && active.arm === arm, 'No matching active pair');
        assert(state.attempts.length < limit, 'Total budget exhausted');
        assert(state.attempts.filter(a => a.arm === arm).length < (arm === 'java' ? 8 : 32), 'Arm budget exhausted');
        assert(state.attempts.filter(a => a.arm === arm && a.caseId === caseId).length < (arm === 'java' ? 1 : 4), 'Pair budget exhausted');
        const attempt = { number: state.attempts.length + 1, caseId, arm, reservedAt: new Date().toISOString() };
        state.attempts.push(attempt); persist(state);
        return structuredClone(attempt);
      },
      endPair(caseId, arm) {
        assert(active?.caseId === caseId && active.arm === arm, 'No matching active pair');
        active.finishedAt = new Date().toISOString(); persist(state); active = null;
      },
    };
  } catch (error) { close(); throw error; }
}

// 修复轮只能有一个固定子账本，同时占用原授权锁；不重写首轮的计费证据。
export function openContinuationBudget({parentFile,parentHash,identity,cases}) {
  const lockFile=parentFile+'.lock', lock=openSync(lockFile,'wx');
  let closed=false;
  const release=()=>{ if(!closed) { closed=true; closeSync(lock); unlinkSync(lockFile); } };
  try {
    const bytes=readFileSync(parentFile);
    assert.equal(createHash('sha256').update(bytes).digest('hex'),parentHash,'Original budget fingerprint changed');
    const parent=JSON.parse(bytes);
    assert.equal(parent.limit,40); assert.equal(parent.version,1);
    assert(parent.pairs.every(pair=>pair.finishedAt),'Original run is not settled');
    const priorAttempts=parent.attempts.length;
    assert(priorAttempts>=0 && priorAttempts<40,'Original authorization exhausted');
    const budget=openBudget({file:parentFile+'.fix-v2.json',identity:parentHash+':'+identity,cases,limit:40-priorAttempts});
    return { ...budget, get state(){return budget.state;}, priorAttempts,
      close(){try{budget.close();}finally{release();}} };
  } catch(error) { release(); throw error; }
}

// 仅检查可机械核对的引用条件；语义是否完整、正确仍由逐题阅读判定。
export function checkSources(item, response, allowed) {
  const issues = [], data = response?.data, sources = data?.sources ?? [];
  if (response?.code !== 200 || typeof data?.answer !== 'string' || !data.answer.trim()) issues.push('api_or_answer');
  for (const source of sources) {
    if (!allowed.some(c => c.documentId === source.documentId && c.chunkIndex === source.chunkIndex && c.text === source.snippet)) issues.push('source_mismatch');
  }
  if (item.expect === 'answer') {
    for (const group of item.evidence) if (!group.some(text => sources.some(s => s.snippet?.includes(text)))) issues.push('missing_evidence');
    if (item.distinctChunks && new Set(sources.map(s => `${s.documentId}:${s.chunkIndex}`)).size < item.evidence.length) issues.push('missing_distinct_chunks');
    const references = [...(data?.answer ?? '').matchAll(/(?:\[(\d+)\]|片段\s*(\d+))/g)];
    if (!references.length) issues.push('missing_inline_citation');
    for (const match of references) if (Number(match[1] ?? match[2]) < 1 || Number(match[1] ?? match[2]) > sources.length) issues.push('invalid_citation_number');
  } else if (sources.length) issues.push('unexpected_sources');
  for (const forbidden of item.forbidden ?? []) if (data?.answer?.includes(forbidden)) issues.push('injection_in_answer');
  return { passed: issues.length === 0, issues, semanticReview: 'pending' };
}
