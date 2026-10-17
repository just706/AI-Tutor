// 真实验收的传输边界；调用方负责题目和内容审查。不在此处重试。
const errorTypes = new Set(['Error', 'TypeError', 'SyntaxError', 'AggregateError', 'AbortError', 'TimeoutError']);
const errorCodes = new Set(['EACCES', 'EPERM', 'ENOTFOUND', 'EAI_AGAIN', 'ECONNREFUSED', 'ECONNRESET',
  'ETIMEDOUT', 'ENETUNREACH', 'EHOSTUNREACH', 'UND_ERR_CONNECT_TIMEOUT', 'UND_ERR_HEADERS_TIMEOUT',
  'UND_ERR_BODY_TIMEOUT', 'UND_ERR_SOCKET', 'CERT_HAS_EXPIRED', 'DEPTH_ZERO_SELF_SIGNED_CERT',
  'SELF_SIGNED_CERT_IN_CHAIN', 'UNABLE_TO_VERIFY_LEAF_SIGNATURE', 'ERR_TLS_CERT_ALTNAME_INVALID']);
const syscalls = new Set(['connect', 'getaddrinfo', 'read', 'write']);
const blockingStatuses = new Set([401, 403, 404, 407, 408, 429]);

// 不保存原始 message、stack、URL、地址或 headers；保留可定位权限/DNS/TLS问题的白名单字段。
function safeDiagnostic(error, seen = new Set(), depth = 0) {
  const result = { type: errorTypes.has(error?.name) ? error.name : 'Error' };
  if (errorCodes.has(error?.code)) result.code = error.code;
  if (syscalls.has(error?.syscall)) result.syscall = error.syscall;
  if (!error || seen.has(error) || depth >= 3) return result;
  seen.add(error);
  const causes = [error.cause, ...(Array.isArray(error.errors) ? error.errors : [])]
    .filter(cause => cause && !seen.has(cause)).slice(0, 4);
  if (causes.length) result.causes = causes.map(cause => safeDiagnostic(cause, seen, depth + 1));
  return result;
}

export class EvaluationProviderError extends Error {
  constructor(message, diagnostic) {
    super(message);
    this.name = 'EvaluationProviderError';
    this.diagnostic = diagnostic;
  }
}

export function createEvaluationProvider({ baseUrl, apiKey, maxCalls, fetchImpl = fetch, timeoutMs = 60000 }) {
  const provider = new URL(baseUrl);
  if (provider.origin !== 'https://api.deepseek.com' || provider.username || provider.password || provider.search || provider.hash) {
    throw new Error('真实验收仅允许无凭据参数的 DeepSeek 官方 HTTPS 地址。');
  }
  if (!Number.isSafeInteger(maxCalls) || maxCalls < 1 || maxCalls > 16) throw new Error('真实验收调用上限必须为1至16的整数。');
  const endpoint = provider.href.replace(/\/$/, '');
  let calls = 0, blocked = null, preflightResult;
  const fail = (record, diagnostic, message, stop) => {
    record.providerError = diagnostic;
    if (stop) blocked = diagnostic;
    return new EvaluationProviderError(message, diagnostic);
  };
  const cancelBody = async response => { try { await response.body?.cancel(); } catch { /* 已收到状态，不读取错误正文。 */ } };

  return {
    get calls() { return calls; },
    get blocked() { return blocked; },
    async preflight() {
      if (preflightResult) return preflightResult;
      const record = { passed: false, method: 'GET', endpoint: endpoint + '/models',
        authenticated: false, inference: false, startedAt: new Date().toISOString() };
      try {
        const response = await fetchImpl(record.endpoint, {
          method: 'GET', redirect: 'error', signal: AbortSignal.timeout(Math.min(timeoutMs, 10000)),
        });
        record.httpStatus = response.status;
        await cancelBody(response);
        // 匿名401只能证明可达，不能证明密钥、余额或生成服务可用。
        record.passed = response.status === 200 || response.status === 401;
        if (!record.passed) record.error = { kind: 'http', httpStatus: response.status };
      } catch (error) {
        record.error = { kind: 'transport', diagnostic: safeDiagnostic(error) };
      }
      if (!record.passed) blocked = record.error;
      record.finishedAt = new Date().toISOString();
      preflightResult = record;
      return record;
    },
    async complete(payload, record = {}) {
      if (blocked) throw fail(record, { kind: 'blocked', reason: blocked }, '本轮真实请求已中止，未追加调用。', false);
      if (!preflightResult?.passed) throw fail(record, { kind: 'preflight_required' }, '须先通过匿名连接预检。', false);
      if (!apiKey) throw fail(record, { kind: 'missing_key' }, '真实模型密钥未配置。', true);
      if (calls >= maxCalls) throw fail(record, { kind: 'budget' }, '本轮真实调用额度已用完。', false);
      // 在发送前占用额度，失败和并发请求也不能超出上限。
      const body = JSON.stringify(payload);
      calls++;
      let response;
      try {
        response = await fetchImpl(endpoint + '/chat/completions', {
          method: 'POST', headers: { 'Content-Type': 'application/json', Authorization: 'Bearer ' + apiKey },
          body, redirect: 'error', signal: AbortSignal.timeout(timeoutMs),
        });
      } catch (error) {
        throw fail(record, { kind: 'transport', diagnostic: safeDiagnostic(error) }, '真实模型请求传输失败，已中止后续调用。', true);
      }
      record.httpStatus = response.status;
      if (!response.ok) {
        await cancelBody(response);
        throw fail(record, { kind: 'http', httpStatus: response.status }, `真实模型请求返回 HTTP ${response.status}。`,
          blockingStatuses.has(response.status) || response.status >= 500);
      }
      try {
        return await response.json();
      } catch (error) {
        const invalidJson = error.name === 'SyntaxError';
        throw fail(record, { kind: invalidJson ? 'invalid_response' : 'transport', diagnostic: safeDiagnostic(error) },
          invalidJson ? '真实模型响应不是有效 JSON。' : '读取真实模型响应失败，已中止后续调用。', !invalidJson);
      }
    },
  };
}
