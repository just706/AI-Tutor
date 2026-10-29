import assert from 'node:assert/strict';

export function planEvaluation(suite, { only, mode = 'retrieval', maxRealCalls = '64' } = {}) {
  assert(['retrieval', 'real', 'replay'].includes(mode), 'mode must be retrieval, real or replay');
  const limit = Number(maxRealCalls);
  assert(Number.isSafeInteger(limit) && limit >= 1 && limit <= 64,
    'max-real-calls must be an integer between 1 and 64');
  const requested = only === undefined ? null : only.split(',');
  assert(!requested || requested.every(id => suite.cases.some(item => item.id === id)), 'Unknown case ID');
  assert(!requested || new Set(requested).size === requested.length, 'Duplicate case ID');
  const cases = requested ? suite.cases.filter(item => requested.includes(item.id)) : suite.cases;
  assert(cases.length > 0, 'No matching cases');
  const materials = [...new Set(cases.flatMap(item => item.documents))];
  assert(materials.every(name => name === 'java' || Object.hasOwn(suite.materials, name)), 'Unknown material');
  return { cases, materials, maxRealCalls: limit, requiresTextbook: materials.includes('java') };
}

export function claimRealCall(current, limit) {
  assert(Number.isSafeInteger(current) && current >= 0, 'Invalid call count');
  assert(Number.isSafeInteger(limit) && limit >= 1 && limit <= 64, 'Invalid call limit');
  assert(current < limit, `Real model call budget exhausted (${limit})`);
  return current + 1;
}

export function takeReplayAnswer(requests, used, promptHash) {
  const index = requests.findIndex((request, candidate) => !used.has(candidate) && request.promptSha256 === promptHash);
  assert(index >= 0, 'No unused recorded response matches the exact request');
  const request = requests[index];
  assert(typeof request.answer === 'string' && request.answer.trim(), 'Recorded answer is missing');
  used.add(index);
  return { index, answer: request.answer, model: request.responseModel, usage: request.usage };
}
