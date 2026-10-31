import test from 'node:test';
import assert from 'node:assert/strict';
import { mkdtempSync, readFileSync, rmSync } from 'node:fs';
import { tmpdir } from 'node:os';
import path from 'node:path';
import { openBudget, openContinuationBudget, checkSources } from './stage5-agent-evaluation.mjs';
import { createHash } from 'node:crypto';

function fixture(t) {
  const directory = mkdtempSync(path.join(tmpdir(), 'agent-budget-'));
  t.after(() => rmSync(directory, { recursive: true, force: true }));
  return { file: path.join(directory, 'budget.json'), identity: 'frozen-suite-and-code',
    cases: Array.from({ length: 8 }, (_, i) => 'A0' + (i + 1)) };
}

test('durable authorization caps each arm/case and total, including failed attempts', async t => {
  const options = fixture(t), budget = openBudget(options);
  for (const caseId of options.cases) for (const arm of ['java', 'agent']) {
    budget.beginPair(caseId, arm);
    const attempts = await Promise.allSettled(Array.from({ length: 6 }, async () => budget.reserve(caseId, arm)));
    assert.equal(attempts.filter(item => item.status === 'fulfilled').length, arm === 'java' ? 1 : 4);
    const saved = JSON.parse(readFileSync(options.file, 'utf8'));
    assert.equal(saved.attempts.length, budget.state.attempts.length, 'saved before dispatch');
    budget.endPair(caseId, arm);
  }
  assert.equal(budget.state.attempts.length, 40);
  assert.equal(budget.state.attempts.filter(item => item.arm === 'agent').length, 32);
  budget.close();
});

test('reopening preserves charges and forbids replay of an already started pair', t => {
  const options = fixture(t);
  let budget = openBudget(options);
  budget.beginPair('A01', 'agent');
  budget.reserve('A01', 'agent'); // unknown response, still consumed
  budget.close();
  budget = openBudget(options);
  assert.equal(budget.state.attempts.length, 1);
  assert.throws(() => budget.beginPair('A01', 'agent'), /already/);
  assert.throws(() => budget.reserve('A02', 'agent'), /active/);
  assert.throws(() => budget.beginPair('A09', 'agent'), /case/);
  budget.close();
});

test('exclusive lock and frozen identity prevent a second process or reset', t => {
  const options = fixture(t), budget = openBudget(options);
  assert.throws(() => openBudget(options), /EEXIST/);
  budget.close();
  assert.throws(() => openBudget({ ...options, identity: 'changed' }), /identity/);
  const reopened = openBudget(options);
  reopened.close();
});

test('source checks reject wrong document, missing cross-chunk context, and answer injection', () => {
  const item = { expect: 'answer', evidence: [['subject'], ['inventor']], distinctChunks: true, forbidden: ['malicious'] };
  const allowed = [{ documentId: 7, chunkIndex: 0, text: 'subject' }, { documentId: 7, chunkIndex: 1, text: 'inventor malicious' }];
  const response = { code: 200, data: { answer: 'fact[1][2]', sources: allowed.map(c => ({ ...c, snippet: c.text })) } };
  assert.equal(checkSources(item, response, allowed).passed, true);
  assert.equal(checkSources(item, { ...response, data: { ...response.data, answer: 'malicious[1][2]' } }, allowed).passed, false);
  assert.equal(checkSources(item, { ...response, data: { ...response.data, sources: response.data.sources.slice(1) } }, allowed).passed, false);
  assert.equal(checkSources(item, response, allowed.map(c => ({ ...c, documentId: 8 }))).passed, false);
  assert.equal(checkSources({ expect: 'refusal', evidence: [] }, { code: 600 }, []).passed, false);
});

test('a frozen repair round shares the original lock and can only spend its unused authorization', t => {
  const original=fixture(t), first=openBudget(original);
  for (const caseId of original.cases.slice(0,5)) {
    first.beginPair(caseId,'agent');
    for(let n=0;n<4;n++) first.reserve(caseId,'agent');
    first.endPair(caseId,'agent');
  }
  first.beginPair('A06','java'); first.reserve('A06','java'); first.endPair('A06','java'); first.close();
  const bytes=readFileSync(original.file);
  const parentHash=createHash('sha256').update(bytes).digest('hex');
  const options={parentFile:original.file,parentHash,identity:'repair-v2',cases:original.cases};
  const next=openContinuationBudget(options);
  assert.equal(next.priorAttempts,21);
  assert.equal(next.state.limit,19);
  assert.throws(()=>openBudget(original),/EEXIST/);
  let charged=0;
  for(const caseId of original.cases) {
    next.beginPair(caseId,'agent');
    for(let n=0;n<4;n++) { try { next.reserve(caseId,'agent'); charged++; } catch {} }
    next.endPair(caseId,'agent');
  }
  assert.equal(charged,19);
  next.close();
  assert.deepEqual(readFileSync(original.file),bytes,'original evidence remains immutable');
  const reopened=openContinuationBudget(options);
  assert.equal(reopened.state.attempts.length,19);
  assert.throws(()=>reopened.beginPair('A01','agent'),/already/);
  reopened.close();
  assert.throws(()=>openContinuationBudget({...options,parentHash:'changed'}),/fingerprint/);
});
