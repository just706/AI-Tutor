import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import test from 'node:test';
import { planEvaluation, claimRealCall, takeReplayAnswer } from './stage1-plan.mjs';

const suite = JSON.parse(readFileSync(new URL('../evaluation/stage1-cases.json', import.meta.url), 'utf8'));

test('math and physics selection requires only their frozen sources and preserves all six cases', () => {
  const plan = planEvaluation(suite, { only: 'M01,M02,M03,P01,P02,P03', mode: 'real', maxRealCalls: '8' });
  assert.deepEqual(plan.cases.map(item => item.id), ['M01', 'M02', 'M03', 'P01', 'P02', 'P03']);
  assert.deepEqual(plan.materials, ['math', 'physics']);
  assert.equal(plan.requiresTextbook, false);
  assert.equal(plan.maxRealCalls, 8);
});

test('full suite still requires the original Java textbook', () => {
  const plan = planEvaluation(suite);
  assert.equal(plan.cases.length, 32);
  assert.equal(plan.requiresTextbook, true);
  assert.deepEqual(plan.materials, ['java', 'math', 'physics', 'opportunity', 'sunk', 'custom']);
});

test('mixed selection keeps Java textbook mandatory', () => {
  assert.equal(planEvaluation(suite, { only: 'J01,M01' }).requiresTextbook, true);
});

test('invalid case selections and budgets fail before any provider request', () => {
  for (const only of ['', 'missing', 'M01,M01']) assert.throws(() => planEvaluation(suite, { only }));
  for (const maxRealCalls of ['0', '-1', '1.5', '65', 'NaN', 'Infinity'])
    assert.throws(() => planEvaluation(suite, { maxRealCalls }));
  assert.throws(() => planEvaluation(suite, { mode: 'unknown' }));
});

test('eight attempted calls exhaust the budget even when upstream calls fail', () => {
  let attempts = 0;
  for (let index = 0; index < 8; index++) attempts = claimRealCall(attempts, 8);
  assert.equal(attempts, 8);
  assert.throws(() => claimRealCall(attempts, 8), /budget exhausted/);
  assert.equal(attempts, 8);
  assert.throws(() => claimRealCall(-1, 8));
  assert.throws(() => claimRealCall(0, 65));
});

test('replay consumes identical requests in their original order and rejects changed or exhausted requests', () => {
  assert.equal(planEvaluation(suite, { mode: 'replay', only: 'M01' }).requiresTextbook, false);
  const requests = [
    { promptSha256: 'same-request', answer: 'first answer' },
    { promptSha256: 'same-request', answer: 'second answer' },
  ];
  const used = new Set();
  assert.equal(takeReplayAnswer(requests, used, 'same-request').answer, 'first answer');
  assert.throws(() => takeReplayAnswer(requests, used, 'changed-request'));
  assert.equal(used.size, 1);
  assert.equal(takeReplayAnswer(requests, used, 'same-request').answer, 'second answer');
  assert.throws(() => takeReplayAnswer(requests, used, 'same-request'));
  assert.throws(() => takeReplayAnswer([{ promptSha256: 'empty', answer: '' }], new Set(), 'empty'));
});
