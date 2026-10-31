import test from 'node:test';
import assert from 'node:assert/strict';
import { createEvaluationProvider } from './evaluation-provider.mjs';
import { runFeedbackCases } from './evaluate-stage4-feedback.mjs';

const request = id => ({ caseId: id, messages: [{ role: 'user', content: '7' }] });
const completion = content => new Response(JSON.stringify({ model: 'local-fixture',
  choices: [{ message: { content }, finish_reason: 'stop' }], usage: { total_tokens: 3 } }), { status: 200 });

test('feedback runner rejects a ninth case before any network call', async () => {
  let calls = 0;
  const provider = createEvaluationProvider({ baseUrl: 'https://api.deepseek.com', apiKey: 'fixture',
    maxCalls: 8, fetchImpl: async () => { calls++; return completion('fixture'); } });
  await assert.rejects(runFeedbackCases({ requests: Array.from({ length: 9 }, (_, i) => request('C' + i)),
    provider, model: 'fixture', save: () => {} }), /eight/);
  assert.equal(calls, 0);
});

test('feedback runner stops after outage and never retries or spends remaining budget', async () => {
  const methods = [], snapshots = [];
  const provider = createEvaluationProvider({ baseUrl: 'https://api.deepseek.com', apiKey: 'fixture', maxCalls: 8,
    fetchImpl: async (_, options) => { methods.push(options.method); return new Response('', { status: options.method === 'GET' ? 401 : 503 }); } });
  const result = await runFeedbackCases({ requests: [request('A'), request('B')], provider,
    model: 'fixture', save: data => snapshots.push(structuredClone(data)) });
  assert.equal(result.realModelCalls, 1);
  assert.equal(result.responses.length, 1);
  assert.equal(result.responses[0].httpStatus, 503);
  assert.equal(result.responses[0].status, 'failed');
  assert.deepEqual(methods, ['GET', 'POST']);
  assert.equal(snapshots.at(-1).contentAcceptance, 'pending');
});

test('feedback runner preserves raw answers and finish reason without treating transport success as content acceptance', async () => {
  let posts = 0;
  const provider = createEvaluationProvider({ baseUrl: 'https://api.deepseek.com', apiKey: 'fixture', maxCalls: 8,
    fetchImpl: async (_, options) => {
      if (options.method === 'GET') return new Response('', { status: 401 });
      posts++;
      const payload = JSON.parse(options.body);
      assert.equal(payload.max_tokens, 2048);
      assert.deepEqual(payload.thinking, { type: 'disabled' });
      return completion('  正确，理由尚未提供。  ');
    } });
  const result = await runFeedbackCases({ requests: [request('A'), request('B')], provider, model: 'fixture', save: () => {} });
  assert.equal(posts, 2);
  assert.equal(result.interfacePassed, true);
  assert.equal(result.contentAcceptance, 'pending');
  assert.equal(result.responses[0].answer, '  正确，理由尚未提供。  ');
  assert.equal(result.responses[0].response.choices[0].finish_reason, 'stop');
  assert.match(result.responses[0].requestSha256, /^[a-f0-9]{64}$/);
  assert.match(result.responses[0].responseSha256, /^[a-f0-9]{64}$/);
});
