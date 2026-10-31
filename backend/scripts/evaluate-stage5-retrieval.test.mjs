import test from 'node:test';
import assert from 'node:assert/strict';
import { scoreRetrievalRun, validateRetrievalSuite } from './evaluate-stage5-retrieval.mjs';

const suite = {
  topK: 4,
  documents: [
    { id: 'one', chunks: [{ id: 'one:0', text: '甲表示局部变化率。' }, { id: 'one:1', text: '乙描述运动状态。' }] },
    { id: 'unselected', chunks: [{ id: 'unselected:0', text: '禁止借用的依据。' }] },
  ],
  cases: [
    { id: 'A', group: 'challenge', category: 'cross_chunk', documents: ['one'], question: '甲和乙', evidence: [['局部变化率'], ['运动状态']], distinctChunks: true },
    { id: 'B', group: 'baseline', category: 'direct', documents: ['one'], question: '甲', evidence: [['局部变化率']], resolvedIncludes: ['甲'] },
    { id: 'N', group: 'challenge', category: 'unanswerable', documents: ['one'], question: '未知', expect: 'refusal' },
    { id: 'C', group: 'baseline', category: 'clarification', documents: ['one'], question: '它', expect: 'clarification' },
  ],
};
const row = (caseId, retrieved = [], clarification = null) => ({ caseId, resolvedQuestion: '甲和乙', retrieved, clarification, elapsedMs: 1 });
const passing = () => [row('A', ['one:0', 'one:1']), row('B', ['one:0']), row('N'), row('C', [], '请明确对象')];

test('reports evidence groups, refusal and clarification with separate denominators', () => {
  const result = scoreRetrievalRun(suite, passing());
  assert.equal(result.passedCases, 4);
  assert.deepEqual(result.evidenceGroups, { found: 3, total: 3 });
  assert.deepEqual(result.answerable, { passed: 2, total: 2 });
  assert.deepEqual(result.refusal, { passed: 1, total: 1 });
  assert.deepEqual(result.clarification, { passed: 1, total: 1 });
});

test('one retrieved evidence group cannot pass a two-part question or disappear from the denominator', () => {
  const rows = passing(); rows[0].retrieved = ['one:0']; rows[1].retrieved = [];
  const result = scoreRetrievalRun(suite, rows);
  assert.equal(result.passedCases, 2);
  assert.deepEqual(result.evidenceGroups, { found: 1, total: 3 });
  assert.deepEqual(result.answerable, { passed: 0, total: 2 });
  assert.deepEqual(result.cases[0].missingEvidenceGroups, [1]);
  assert.equal(result.groups.challenge.passed, 1);
});

test('empty retrieval is not clarification, and clarification is not a correct refusal', () => {
  const rows = passing(); rows[2].clarification = '请选择'; rows[3].clarification = null;
  assert.equal(scoreRetrievalRun(suite, rows).passedCases, 2);
  rows[2] = row('N', ['one:0']);
  assert.equal(scoreRetrievalRun(suite, rows).refusal.passed, 0);
});

test('rejects missing, duplicate and unknown result rows instead of inflating the pass rate', () => {
  assert.throws(() => scoreRetrievalRun(suite, passing().slice(1)), /case/i);
  assert.throws(() => scoreRetrievalRun(suite, [passing()[0], ...passing().slice(0, 3)]), /case/i);
  const rows = passing(); rows[0].caseId = 'unknown';
  assert.throws(() => scoreRetrievalRun(suite, rows), /case/i);
});

test('rejects unknown, unselected, repeated and over-limit chunks', () => {
  for (const retrieved of [['unknown:0'], ['unselected:0'], ['one:0', 'one:0']]) {
    const rows = passing(); rows[0].retrieved = retrieved;
    assert.throws(() => scoreRetrievalRun(suite, rows), /chunk/i);
  }
  assert.throws(() => scoreRetrievalRun({ ...suite, topK: 1 }, passing()), /limit/i);
});

test('requires distinct chunks and the resolved subject even when text matches the evidence', () => {
  const local = structuredClone(suite);
  local.documents[0].chunks[0].text += '乙描述运动状态。';
  const rows = passing(); rows[0].retrieved = ['one:0']; rows[1].resolvedQuestion = '乙';
  const result = scoreRetrievalRun(local, rows);
  assert.deepEqual(result.evidenceGroups, { found: 3, total: 3 });
  assert.equal(result.answerable.passed, 0);
});

test('rejects non-finite or negative timings instead of presenting bogus latency', () => {
  for (const elapsedMs of [NaN, Infinity, -1]) {
    const rows = passing(); rows[0].elapsedMs = elapsedMs;
    assert.throws(() => scoreRetrievalRun(suite, rows), /latency/i);
  }
});

test('latency median averages the two middle values for an even number of cases', () => {
  const rows = passing();
  rows.forEach((row, index) => { row.elapsedMs = index + 1; });
  assert.deepEqual(scoreRetrievalRun(suite, rows).latencyMs, { median: 2.5, p95: 4 });
});

test('custom suites validate annotations against selected chunks before invoking retrieval', () => {
  const valid = { ...structuredClone(suite), version: 'custom-v1', scope: 'test fixture' };
  assert.equal(validateRetrievalSuite(valid), valid);
  valid.cases[0].evidence = [['禁止借用的依据']];
  assert.throws(() => validateRetrievalSuite(valid), /evidence/i);
});

test('custom suites reject unstable chunk IDs, duplicate cases and invalid expected outcomes', () => {
  for (const modify of [
    value => { value.documents[0].chunks[0].id = 'wrong'; },
    value => { value.cases.push(value.cases[0]); },
    value => { value.cases[0].expect = 'anything'; },
  ]) {
    const invalid = { ...structuredClone(suite), version: 'custom-v1', scope: 'test fixture' };
    modify(invalid);
    assert.throws(() => validateRetrievalSuite(invalid), /chunk|case|outcome/i);
  }
});
