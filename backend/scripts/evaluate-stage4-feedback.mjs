import assert from 'node:assert/strict';
import { createHash } from 'node:crypto';
import { spawnSync } from 'node:child_process';
import { existsSync, mkdirSync, readFileSync, realpathSync, statSync, writeFileSync } from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { parseArgs } from 'node:util';
import { createEvaluationProvider } from './evaluation-provider.mjs';

const sha = value => createHash('sha256').update(value).digest('hex');

export async function runFeedbackCases({ requests, provider, model, save }) {
  assert(Array.isArray(requests) && requests.length > 0 && requests.length <= 8, 'At most eight cases per authorized run');
  assert.equal(new Set(requests.map(item => item.caseId)).size, requests.length, 'Duplicate case IDs');
  for (const item of requests) {
    assert(typeof item.caseId === 'string' && item.caseId.trim());
    assert(Array.isArray(item.messages) && item.messages.length > 0 && item.messages.at(-1).role === 'user');
    assert(item.messages.every(message => ['system', 'user', 'assistant'].includes(message.role)
      && typeof message.content === 'string' && message.content.trim()));
  }
  const result = { evaluationScope: 'production prompts with synthetic history; no database or UI',
    maxRealCalls: 8, realModelCalls: 0, requestedModel: model, contentAcceptance: 'pending',
    interfacePassed: false, responses: [], startedAt: new Date().toISOString() };
  result.preflight = await provider.preflight();
  save(result);
  if (result.preflight.passed) for (const request of requests) {
    const payload = { model, messages: request.messages, stream: false, thinking: { type: 'disabled' }, max_tokens: 2048 };
    const record = { caseId: request.caseId, payload, requestSha256: sha(JSON.stringify(payload)),
      status: 'attempt_reserved', startedAt: new Date().toISOString() };
    result.responses.push(record);
    // Persist the reservation before the network request; an interrupted run must not be resumed implicitly.
    result.realModelCalls = provider.calls + 1;
    save(result);
    try {
      const response = await provider.complete(payload, record);
      record.response = response;
      record.responseSha256 = sha(JSON.stringify(response));
      record.answer = response.choices?.[0]?.message?.content;
      record.reportedModel = response.model;
      record.usage = response.usage;
      assert(typeof record.answer === 'string' && record.answer.trim(), 'Empty model answer');
      assert.equal(response.choices[0].finish_reason, 'stop', 'Incomplete model answer');
      record.status = 'received';
    } catch (error) {
      record.status = 'failed';
      record.error = error.name === 'EvaluationProviderError' ? error.message : 'Invalid or incomplete completion';
    }
    record.finishedAt = new Date().toISOString();
    record.elapsedMs = Date.parse(record.finishedAt) - Date.parse(record.startedAt);
    result.realModelCalls = provider.calls;
    save(result);
    if (provider.blocked) break;
  }
  result.skippedCaseIds = requests.filter(item => !result.responses.some(record => record.caseId === item.caseId)).map(item => item.caseId);
  result.interfacePassed = result.responses.length === requests.length && result.responses.every(item => item.status === 'received');
  result.finishedAt = new Date().toISOString();
  save(result);
  return result;
}

async function main() {
  const { values } = parseArgs({ options: {
    mode: { type: 'string', default: 'prepare' }, 'output-dir': { type: 'string' }, 'env-file': { type: 'string' },
    'java-bin': { type: 'string', default: 'java' },
  } });
  assert(['prepare', 'real'].includes(values.mode), 'Mode must be prepare or real');
  assert(values['output-dir'], 'Output directory required');
  const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '../..');
  const output = path.resolve(values['output-dir']);
  const resolvedOutput = path.join(realpathSync(path.dirname(output)), path.basename(output));
  const relative = path.relative(realpathSync(root), resolvedOutput);
  assert(relative.startsWith('..' + path.sep) || path.isAbsolute(relative), 'Raw evidence must stay outside repository');
  assert(!existsSync(output), 'Choose a new output directory; never resume or overwrite real calls');
  if (process.platform === 'win32') assert.equal(path.parse(resolvedOutput).root.toLowerCase(), 'd:\\', 'Keep raw evidence on D drive');
  const suitePath = path.join(root, 'backend/evaluation/stage4-feedback-cases.json');
  const suiteBytes = readFileSync(suitePath), suite = JSON.parse(suiteBytes);
  assert.equal(suite.cases.length, 8);
  assert.equal(suite.maxRealCalls, 8);
  const promptSource = path.join(root, 'backend/src/main/java/com/aitutor/ai/AiPromptBuilder.java');
  const promptClass = path.join(root, 'backend/target/classes/com/aitutor/ai/AiPromptBuilder.class');
  assert(statSync(promptClass).mtimeMs >= statSync(promptSource).mtimeMs, 'Build current backend prompts first');
  const helper = path.join(root, 'backend/scripts/TutorFeedbackPrompts.java');
  mkdirSync(output);
  const requestFile = path.join(output, 'requests.json');
  const classpath = [path.join(root, 'backend/target/classes'), path.join(root, 'backend/target/dependency/*')].join(path.delimiter);
  const exported = spawnSync(values['java-bin'], ['-Dfile.encoding=UTF-8', '--class-path', classpath, helper, suitePath, requestFile],
    { encoding: 'utf8', windowsHide: true, timeout: 30000 });
  assert.ifError(exported.error);
  assert.equal(exported.status, 0, exported.stderr);
  const requests = JSON.parse(readFileSync(requestFile, 'utf8'));
  assert.deepEqual(requests.map(item => item.caseId), suite.cases.map(item => item.id));
  for (const [index, request] of requests.entries()) {
    assert.deepEqual(request.messages.filter(item => item.role !== 'system'), suite.cases[index].history);
    assert(request.messages[0].content.includes('未提供理由不等于答案错误'));
  }
  const provenance = { suiteVersion: suite.version, suiteSha256: sha(suiteBytes), requestsSha256: sha(readFileSync(requestFile)),
    promptSourceSha256: sha(readFileSync(promptSource)), promptClassSha256: sha(readFileSync(promptClass)),
    jarSha256: sha(readFileSync(path.join(root, 'backend/target/ai-tutor-backend-0.0.1-SNAPSHOT.jar'))),
    appCommit: spawnSync('git', ['rev-parse', 'HEAD'], { cwd: root, encoding: 'utf8', windowsHide: true }).stdout.trim(),
    syntheticHistory: true, profile: null, learnerMemories: [], automaticRetries: 0,
    maxRealCalls: 8, preparedAt: new Date().toISOString() };
  writeFileSync(path.join(output, 'provenance.json'), JSON.stringify(provenance, null, 2));
  writeFileSync(path.join(output, 'suite.json'), suiteBytes);
  for (const filename of ['evaluate-stage4-feedback.mjs', 'evaluation-provider.mjs', 'TutorFeedbackPrompts.java'])
    writeFileSync(path.join(output, filename), readFileSync(path.join(root, 'backend/scripts', filename)));
  if (values.mode === 'prepare') {
    console.log(JSON.stringify({ prepared: true, cases: requests.length, realModelCalls: 0, output }));
    return;
  }
  const config = {};
  if (values['env-file']) for (const line of readFileSync(values['env-file'], 'utf8').split(/\r?\n/)) {
    const match = line.match(/^\s*([A-Za-z_][A-Za-z0-9_]*)\s*=(.*)$/);
    if (match) config[match[1]] = match[2].trim().replace(/^(['"])(.*)\1$/, '$2');
  }
  const setting = (name, fallback) => process.env[name] ?? config[name] ?? fallback;
  const model = setting('DEEPSEEK_MODEL_NAME', 'deepseek-v4-flash');
  const apiKey = setting('DEEPSEEK_API_KEY', '');
  assert(apiKey, 'Model key is not configured');
  const provider = createEvaluationProvider({ baseUrl: setting('DEEPSEEK_BASE_URL', 'https://api.deepseek.com'), apiKey, maxCalls: 8 });
  const result = await runFeedbackCases({ requests, provider, model,
    save: data => writeFileSync(path.join(output, 'result.json'), JSON.stringify(data, null, 2)) });
  console.log(JSON.stringify({ interfacePassed: result.interfacePassed, contentAcceptance: result.contentAcceptance,
    realModelCalls: result.realModelCalls, received: result.responses.filter(item => item.status === 'received').length, output }));
  if (!result.interfacePassed) process.exitCode = 1;
}

if (process.argv[1] && path.resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  main().catch(error => { console.error(error.message); process.exitCode = 1; });
}
