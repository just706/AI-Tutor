import test from 'node:test';
import assert from 'node:assert/strict';
import { inspectEvidence, applyEvidenceGate } from './stage5-evidence.mjs';

test('definition-only text cannot satisfy an explicit inventor question', () => {
  const result = inspectEvidence('晨星协议的发明者是谁？', ['晨星协议是一种消息排序方式。']);
  assert.equal(result.status, 'blocked');
  assert.equal(result.missing[0].kind, 'person');
});

test('a matching person relation leaves an inspectable cue but never claims semantic support', () => {
  const result = inspectEvidence('晨星协议的发明者是谁？', ['晨星协议由林晓发明。']);
  assert.equal(result.status, 'unverified');
  assert.deepEqual(result.requirements[0].cues, ['晨星协议由林晓发明']);
});

test('an actual numeric question rejects a bare definition and retains a reported value', () => {
  assert.equal(inspectEvidence('温度在本次实验中的实际数值是多少？', ['温度是描述冷热程度的物理量。']).status, 'blocked');
  assert.equal(inspectEvidence('温度在本次实验中的实际数值是多少？', ['本次实验测得温度为23摄氏度。']).status, 'unverified');
  assert.equal(inspectEvidence('温度在本次实验中的实际数值是多少？', ['本次实验测得温度为0摄氏度。']).status, 'unverified');
});

test('unrelated names and numbers in the same snippet cannot fill another subject slot', () => {
  const result = inspectEvidence('晨星协议的发明年份是多少？', ['晨星协议用于消息排序。远航协议于2012年发明。']);
  assert.equal(result.status, 'blocked');
});

test('each recognized subquestion needs a cue and cues may come from separate selected snippets', () => {
  const question = '晨星协议的发明者是谁？晨星协议的发明年份是多少？';
  assert.equal(inspectEvidence(question, ['晨星协议由林晓发明。']).status, 'blocked');
  assert.equal(inspectEvidence(question, ['晨星协议由林晓发明。', '晨星协议于2012年发明。']).status, 'unverified');
});

test('definition questions remain unverified instead of being certified answerable', () => {
  const result = inspectEvidence('晨星协议是什么？', ['远航协议是一种消息排序方式。']);
  assert.equal(result.status, 'unverified');
  assert.deepEqual(result.requirements, []);
});

function fixture() {
  return { input: { documents: [
    { id: 'chosen', chunks: [{ id: 'chosen:0', text: '晨星协议是一种消息排序方式。' }] },
    { id: 'other', chunks: [{ id: 'other:0', text: '晨星协议由林晓发明。' }] },
  ], cases: [{ id: 'A', documents: ['chosen'], question: '晨星协议的发明者是谁？' }] },
  rows: [{ caseId: 'A', resolvedQuestion: '晨星协议的发明者是谁？', clarification: null, retrieved: ['chosen:0'], elapsedMs: 1 }] };
}

test('the gate removes unsupported retrieval without borrowing unselected evidence or mutating input', () => {
  const f = fixture();
  const rows = applyEvidenceGate(f.input, f.rows);
  assert.deepEqual(rows[0].retrieved, []);
  assert.deepEqual(rows[0].evidenceGate.originalRetrieved, ['chosen:0']);
  assert.deepEqual(f.rows[0].retrieved, ['chosen:0']);
  assert.equal(rows[0].evidenceGate.status, 'blocked');
});

test('shared clarification stays intact and malformed or out-of-scope rows fail closed', () => {
  const f = fixture(); f.rows[0].clarification = '请明确对象'; f.rows[0].retrieved = [];
  assert.equal(applyEvidenceGate(f.input, f.rows)[0].clarification, '请明确对象');
  assert.throws(() => applyEvidenceGate(f.input, []), /case/i);
  const other = fixture(); other.rows[0].retrieved = ['other:0'];
  assert.throws(() => applyEvidenceGate(other.input, other.rows), /scope/i);
  assert.throws(() => inspectEvidence('', ['text']), /question/i);
});
