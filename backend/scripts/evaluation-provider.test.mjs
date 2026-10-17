import test from 'node:test';
import assert from 'node:assert/strict';
import { createEvaluationProvider, EvaluationProviderError } from './evaluation-provider.mjs';

const apiKey = 'test-only-api-key';
const payload = {
  model: 'deepseek-v4-flash',
  messages: [{ role: 'user', content: 'Only return supported:false for this test fixture.' }],
  stream: false,
};

// 保留实际 Chat Completions 响应的外层、消息、结束原因和用量结构。
// 这里只替换外部 fetch 边界，提供方自己的预检、限额和错误处理均实际执行。
const completion = {
  id: 'chatcmpl-local-fixture',
  object: 'chat.completion',
  created: 1792211099,
  model: 'deepseek-flash',
  choices: [{
    index: 0,
    message: { role: 'assistant', content: '{"supported":false}' },
    finish_reason: 'stop',
  }],
  usage: { prompt_tokens: 12, completion_tokens: 5, total_tokens: 17 },
};

function completionResponse() {
  return new Response(JSON.stringify(completion), {
    status: 200,
    headers: { 'content-type': 'application/json' },
  });
}

test('200 and 401 model-list responses pass anonymous preflight without consuming inference calls', async () => {
  for (const scenario of [
    { baseUrl: 'https://api.deepseek.com', status: 200, url: 'https://api.deepseek.com/models' },
    { baseUrl: 'https://api.deepseek.com/v1', status: 401, url: 'https://api.deepseek.com/v1/models' },
  ]) {
    const requests = [];
    const provider = createEvaluationProvider({
      baseUrl: scenario.baseUrl, apiKey: scenario.status === 401 ? apiKey : undefined, maxCalls: 2,
      fetchImpl: async (url, options) => {
        requests.push({ url: String(url), options });
        return new Response(scenario.status === 200
          ? '{"object":"list","data":[]}'
          : '{"error":{"message":"Authentication required","type":"authentication_error"}}',
        { status: scenario.status, headers: { 'content-type': 'application/json' } });
      },
    });

    assert.equal(provider.calls, 0);
    assert.equal(provider.blocked, null);
    const result = await provider.preflight();

    assert.equal(result.passed, true, `HTTP ${scenario.status} is reachable`);
    assert.equal(result.httpStatus, scenario.status);
    assert.equal(provider.calls, 0);
    assert.equal(provider.blocked, null);
    assert.equal(requests.length, 1);
    assert.equal(requests[0].url, scenario.url);
    assert.equal(requests[0].options.method, 'GET');
    assert.equal(requests[0].options.redirect, 'error');
    assert.equal(new Headers(requests[0].options.headers).get('authorization'), null);
    assert.equal(requests[0].options.body, undefined);
    assert.ok(requests[0].options.signal instanceof AbortSignal);
  }
});

test('completion before a successful preflight is rejected without sending a request or consuming budget', async () => {
  let requests = 0;
  const provider = createEvaluationProvider({
    baseUrl: 'https://api.deepseek.com', apiKey, maxCalls: 1,
    fetchImpl: async () => { requests++; return completionResponse(); },
  });

  await assert.rejects(provider.complete(payload), EvaluationProviderError);
  assert.equal(provider.calls, 0);
  assert.equal(requests, 0);
});

test('failed preflight blocks inference for both unexpected HTTP status and transport failure', async () => {
  for (const failure of [
    () => new Response('Unavailable', { status: 503 }),
    () => { throw new TypeError('fetch failed'); },
  ]) {
    const requests = [];
    const provider = createEvaluationProvider({
      baseUrl: 'https://api.deepseek.com', apiKey, maxCalls: 2,
      fetchImpl: async (url, options) => { requests.push(options.method); return failure(); },
    });

    const result = await provider.preflight();
    assert.equal(result.passed, false);
    assert.notEqual(provider.blocked, null);
    await assert.rejects(provider.complete(payload), EvaluationProviderError);
    assert.equal(provider.calls, 0);
    assert.deepEqual(requests, ['GET']);
  }
});

test('successful completion sends one authenticated JSON request and returns the provider response unchanged', async () => {
  const requests = [];
  const provider = createEvaluationProvider({
    baseUrl: 'https://api.deepseek.com/v1', apiKey, maxCalls: 2,
    fetchImpl: async (url, options) => {
      requests.push({ url: String(url), options });
      return options.method === 'GET' ? new Response(null, { status: 401 }) : completionResponse();
    },
  });
  await provider.preflight();
  const record = {};

  assert.deepEqual(await provider.complete(payload, record), completion);
  assert.equal(record.httpStatus, 200);
  assert.equal(provider.calls, 1);
  assert.equal(provider.blocked, null);
  assert.equal(requests.length, 2);
  const request = requests[1];
  assert.equal(request.url, 'https://api.deepseek.com/v1/chat/completions');
  assert.equal(request.options.method, 'POST');
  assert.equal(request.options.redirect, 'error');
  assert.equal(new Headers(request.options.headers).get('authorization'), 'Bearer test-only-api-key');
  assert.equal(new Headers(request.options.headers).get('content-type'), 'application/json');
  assert.deepEqual(JSON.parse(request.options.body), payload);
  assert.ok(request.options.signal instanceof AbortSignal);
});

test('transport failure records only diagnostic allowlisted fields and blocks further calls without retrying', async () => {
  const sensitive = 'secret-token-and-private-request-content';
  const cause = Object.assign(new Error(sensitive), {
    code: 'EACCES', syscall: 'connect', address: sensitive,
    url: `https://example.invalid/${sensitive}`, headers: { authorization: sensitive },
  });
  const failure = new TypeError(sensitive, { cause });
  let requests = 0;
  const provider = createEvaluationProvider({
    baseUrl: 'https://api.deepseek.com', apiKey, maxCalls: 5,
    fetchImpl: async (url, options) => {
      requests++;
      if (options.method === 'GET') return new Response(null, { status: 401 });
      throw failure;
    },
  });
  await provider.preflight();
  const record = {};

  await assert.rejects(provider.complete(payload, record), error => {
    assert.ok(error instanceof EvaluationProviderError);
    assert.equal(String(error).includes(sensitive), false);
    assert.equal(JSON.stringify(error).includes(sensitive), false);
    return true;
  });
  assert.deepEqual(record.providerError, {
    kind: 'transport',
    diagnostic: { type: 'TypeError', causes: [{ type: 'Error', code: 'EACCES', syscall: 'connect' }] },
  });
  assert.equal(record.httpStatus, undefined);
  assert.notEqual(provider.blocked, null);
  assert.equal(JSON.stringify(provider.blocked).includes(sensitive), false);
  assert.equal(provider.calls, 1);
  await assert.rejects(provider.complete(payload), EvaluationProviderError);
  assert.equal(provider.calls, 1);
  assert.equal(requests, 2, 'one preflight and one inference attempt, without retry');
});

test('authentication, endpoint, proxy, timeout, rate-limit and server HTTP failures block remaining cases', async () => {
  for (const status of [401, 403, 404, 407, 408, 429, 500, 502, 503, 599]) {
    let requests = 0;
    const provider = createEvaluationProvider({
      baseUrl: 'https://api.deepseek.com', apiKey, maxCalls: 5,
      fetchImpl: async (url, options) => {
        requests++;
        return options.method === 'GET' ? new Response(null, { status: 401 })
          : new Response('{"error":{"message":"Provider rejected the request","type":"api_error"}}',
            { status, headers: { 'content-type': 'application/json' } });
      },
    });
    await provider.preflight();
    const record = {};

    await assert.rejects(provider.complete(payload, record), EvaluationProviderError);
    assert.equal(record.httpStatus, status);
    assert.ok(record.providerError, `HTTP ${status} must be recorded`);
    assert.notEqual(provider.blocked, null, `HTTP ${status} must block later cases`);
    await assert.rejects(provider.complete(payload), EvaluationProviderError);
    assert.equal(provider.calls, 1);
    assert.equal(requests, 2, `HTTP ${status} must not trigger retry or a second inference`);
  }
});

test('HTTP 400 records a case failure but permits a different subsequent case', async () => {
  const requests = [];
  const provider = createEvaluationProvider({
    baseUrl: 'https://api.deepseek.com', apiKey, maxCalls: 2,
    fetchImpl: async (url, options) => {
      if (options.method === 'GET') return new Response(null, { status: 401 });
      requests.push(JSON.parse(options.body));
      return requests.length === 1
        ? new Response('{"error":{"message":"Invalid request","type":"invalid_request_error"}}',
          { status: 400, headers: { 'content-type': 'application/json' } })
        : completionResponse();
    },
  });
  await provider.preflight();
  const record = {};

  await assert.rejects(provider.complete(payload, record), EvaluationProviderError);
  assert.equal(record.httpStatus, 400);
  assert.ok(record.providerError);
  assert.equal(provider.blocked, null);
  const nextCase = { ...payload, messages: [{ role: 'user', content: 'A different fixed case.' }] };
  assert.deepEqual(await provider.complete(nextCase), completion);
  assert.deepEqual(requests, [payload, nextCase]);
  assert.equal(provider.calls, 2);
});

test('invalid JSON in an HTTP 200 response fails only its case and permits the next one', async () => {
  let inferenceRequests = 0;
  const provider = createEvaluationProvider({
    baseUrl: 'https://api.deepseek.com', apiKey, maxCalls: 2,
    fetchImpl: async (url, options) => {
      if (options.method === 'GET') return new Response(null, { status: 401 });
      inferenceRequests++;
      return inferenceRequests === 1
        ? new Response('{"choices":', { status: 200, headers: { 'content-type': 'application/json' } })
        : completionResponse();
    },
  });
  await provider.preflight();
  const record = {};

  await assert.rejects(provider.complete(payload, record), EvaluationProviderError);
  assert.equal(record.httpStatus, 200);
  assert.ok(record.providerError);
  assert.equal(provider.blocked, null);
  assert.deepEqual(await provider.complete({ ...payload, messages: [{ role: 'user', content: 'Next case.' }] }), completion);
  assert.equal(provider.calls, 2);
  assert.equal(inferenceRequests, 2);
});

test('the call limit is reserved before fetch completes so an in-flight request cannot permit overspending', async () => {
  let inferenceRequests = 0;
  let finishRequest;
  const provider = createEvaluationProvider({
    baseUrl: 'https://api.deepseek.com', apiKey, maxCalls: 1,
    fetchImpl: async (url, options) => {
      if (options.method === 'GET') return new Response(null, { status: 401 });
      inferenceRequests++;
      if (inferenceRequests > 1) throw new Error('An extra inference request crossed the boundary');
      return new Promise(resolve => { finishRequest = resolve; });
    },
  });
  await provider.preflight();
  const pending = provider.complete(payload);
  try {
    assert.equal(provider.calls, 1, 'budget is reserved before the response arrives');
    await assert.rejects(provider.complete(payload), EvaluationProviderError);
    assert.equal(provider.calls, 1);
    assert.equal(inferenceRequests, 1);
  } finally {
    finishRequest?.(completionResponse());
  }
  assert.deepEqual(await pending, completion);
});

test('unsafe endpoints and invalid call budgets are rejected before any network access', () => {
  let requests = 0;
  const options = {
    baseUrl: 'https://api.deepseek.com', apiKey, maxCalls: 5,
    fetchImpl: async () => { requests++; return completionResponse(); },
  };
  for (const baseUrl of [
    'http://api.deepseek.com',
    'https://example.invalid',
    'https://api.deepseek.com.example.invalid',
    'https://user:password@api.deepseek.com',
    'https://api.deepseek.com?token=private',
    'https://api.deepseek.com#private',
  ]) {
    assert.throws(() => createEvaluationProvider({ ...options, baseUrl }), `reject ${baseUrl}`);
  }
  for (const maxCalls of [0, -1, 17, 1.5, NaN, Infinity]) {
    assert.throws(() => createEvaluationProvider({ ...options, maxCalls }), `reject budget ${maxCalls}`);
  }
  assert.equal(requests, 0);
});
