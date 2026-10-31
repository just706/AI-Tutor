// 固定题集的真实Java/Python对照；默认stub，不重试、不调整生产提示或限制。
import assert from 'node:assert/strict';
import { spawn, spawnSync } from 'node:child_process';
import { createHash, randomBytes, randomUUID } from 'node:crypto';
import { createServer } from 'node:http';
import { once } from 'node:events';
import { createWriteStream, existsSync, readFileSync, readdirSync, mkdirSync, writeFileSync } from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { setTimeout as delay } from 'node:timers/promises';
import { parseArgs } from 'node:util';
import { createEvaluationProvider } from './evaluation-provider.mjs';
import { openBudget, openContinuationBudget, checkSources } from './stage5-agent-evaluation.mjs';

const { values: options } = parseArgs({ options: {
  mode: { type: 'string', default: 'stub' }, 'output-dir': { type: 'string' }, 'env-file': { type: 'string' },
  round: { type: 'string', default: 'initial' },
  'mysql-bin': { type: 'string', default: 'mysql' }, 'java-bin': { type: 'string', default: 'java' },
  'python-bin': { type: 'string', default: 'python' },
} });
assert(['stub', 'real'].includes(options.mode));
assert(['initial','fix-v2'].includes(options.round));
assert(options['output-dir'], 'Specify a new output directory');
const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '../..');
const backend = path.join(root, 'backend'), output = path.resolve(options['output-dir']);
assert(!existsSync(output), 'Use a new output directory');
const jar = path.join(backend, 'target/ai-tutor-backend-0.0.1-SNAPSHOT.jar');
assert(existsSync(jar), 'Build backend first');
mkdirSync(output, { recursive: true });
const save = (name, value) => writeFileSync(path.join(output, name), JSON.stringify(value, null, 2));
const config = {};
if (options['env-file']) for (const line of readFileSync(options['env-file'], 'utf8').split(/\r?\n/)) {
  const match = line.match(/^\s*([A-Za-z_][A-Za-z0-9_]*)\s*=(.*)$/);
  if (match) config[match[1]] = match[2].trim().replace(/^(['"])(.*)\1$/, '$2');
}
const setting = (key, fallback) => process.env[key] ?? config[key] ?? fallback;
const modelName = setting('DEEPSEEK_MODEL_NAME', 'deepseek-v4-flash');
const baseUrl = setting('DEEPSEEK_BASE_URL', 'https://api.deepseek.com');
const apiKey = setting('DEEPSEEK_API_KEY', '');
if (options.mode === 'real') assert(apiKey, 'Missing model key');
const suite = JSON.parse(readFileSync(path.join(backend, 'evaluation/stage5-agent-cases.json'), 'utf8'));
assert.equal(suite.cases.length, 8);
assert.deepEqual(suite.budget, { total: 40, javaPerCase: 1, agentPerCase: 4 });
const repair=options.round==='fix-v2';
// 运行前固定修复轮顺序；优先安全边界与明确历史，不根据模型表现动态挑选。
const selectedCases=repair ? ['A07','A06','A08','A03','A04','A05'].map(id=>suite.cases.find(c=>c.id===id)) : suite.cases;
const digest = value => createHash('sha256').update(value).digest('hex');
const hashes = {};
function fingerprint(file) { hashes[path.relative(root, file).replaceAll('\\', '/')] = digest(readFileSync(file)); }
function fingerprintTree(directory, accept) {
  for (const entry of readdirSync(directory, { withFileTypes: true })) {
    const file = path.join(directory, entry.name);
    if (entry.isDirectory() && entry.name !== '__pycache__') fingerprintTree(file, accept);
    else if (entry.isFile() && accept(file)) fingerprint(file);
  }
}
for (const file of [jar, 'evaluation/stage5-agent-cases.json', 'scripts/evaluate-stage5-agent.mjs',
  'scripts/stage5-agent-evaluation.mjs', 'scripts/evaluation-provider.mjs', 'src/main/resources/application.yml']) {
  fingerprint(path.isAbsolute(file) ? file : path.join(backend, file));
}
fingerprintTree(path.join(backend, 'src/main/java'), file => file.endsWith('.java'));
fingerprintTree(path.join(root, 'agent-service/tutor_agent'), file => file.endsWith('.py'));
const identity = digest(JSON.stringify({ suite: suite.version, round:options.round, selectedCases:selectedCases.map(c=>c.id), modelName, baseUrl, hashes }));
const budgetFile = options.mode === 'real'
  ? path.join(backend, 'target/stage5-agent-real-budget-20261031.json') : path.join(output, 'stub-budget.json');
const priorReport=repair ? JSON.parse(readFileSync(path.join(backend,'evaluation/stage5-agent-results.json'),'utf8')) : null;
if(repair) assert.equal(modelName,priorReport.model.requested,'Repair comparison must keep the configured model');
const budget = repair && options.mode==='real'
  ? openContinuationBudget({parentFile:budgetFile,parentHash:priorReport.provenance.ledgerSha256,identity,cases:selectedCases.map(c=>c.id)})
  : openBudget({ file: budgetFile, identity, cases: selectedCases.map(item => item.id),limit:repair?19:40 });
const priorAttempts=repair ? (options.mode==='real'?budget.priorAttempts:21) : 0;
if (budget.state.pairs.length) { budget.close(); throw new Error('Authorization already started; inspect existing results, do not replay'); }
const provider = options.mode === 'real' ? createEvaluationProvider({ baseUrl, apiKey, maxCalls: budget.state.limit }) : null;
const frozen = { startedAt: new Date().toISOString(), mode: options.mode, round:options.round, selectedCases:selectedCases.map(c=>c.id), priorAttempts, identity, hashes, suite,
  requestedModel: modelName, budgetFile, limits: { javaTimeoutMs: 60000, agentModelMs: 15000, agentTotalMs: 45000,
    agentJavaClientMs: 55000, relayMs: 60000, topK: 4, maxOutputTokens: 2048 },
  note: repair
    ? 'Six preselected Agent-only repair cases, compared with saved initial results; same model, materials and rubric. No new Java baseline. Updated implementation frozen before inference, no in-run tuning or retries.'
    : 'Same configured model; production prompts unchanged. Agent JSON stages differ from Java single prose completion. Exact chunks and user-only history seeded; alternating arm order; no retries.' };
save('frozen.json', frozen);

const dbUrl = new URL(setting('SPRING_DATASOURCE_URL', 'jdbc:mysql://localhost:3306/ai_tutor').replace(/^jdbc:/, '').replace(/^mysql:/, 'http:'));
assert(['localhost', '127.0.0.1'].includes(dbUrl.hostname));
const schema = `ai_tutor_agent_eval_${Date.now()}_${randomBytes(4).toString('hex')}`;
assert(/^ai_tutor_agent_eval_\d+_[a-f0-9]{8}$/.test(schema));
const databaseUser = setting('SPRING_DATASOURCE_USERNAME', 'root');
const databasePassword = setting('SPRING_DATASOURCE_PASSWORD', '');
const literal = value => `CONVERT(0x${Buffer.from(value, 'utf8').toString('hex')} USING utf8mb4)`;
function sql(statement, selected = true) {
  const result = spawnSync(options['mysql-bin'], ['--protocol=TCP', '--host=127.0.0.1', `--port=${dbUrl.port || 3306}`,
    `--user=${databaseUser}`, '--default-character-set=utf8mb4', '--batch', '--raw', '--skip-column-names', ...(selected ? [schema] : [])],
  { input: statement, encoding: 'utf8', env: { ...process.env, MYSQL_PWD: databasePassword }, windowsHide: true, timeout: 30000 });
  assert(!result.error, `MySQL invocation failed: ${result.error?.code}`);
  assert.equal(result.status, 0, 'Isolated SQL operation failed');
  return result.stdout.trim();
}
async function freePort() {
  const server = createServer(); server.listen(0, '127.0.0.1'); await once(server, 'listening');
  const port = server.address().port; await new Promise(resolve => server.close(resolve)); return port;
}
const children = [], logs = [], events = [], results = [], inflight = new Set();
let active = null, created = false, failure = null, pythonBase;
const credentials = { java: randomBytes(32).toString('hex'), agent: randomBytes(32).toString('hex') };
const serviceToken = randomBytes(32).toString('hex'), callbackToken = randomBytes(32).toString('hex');
function launch(command, args, cwd, environment, name) {
  const log = createWriteStream(path.join(output, name + '.log')); logs.push(log);
  const child = spawn(command, args, { cwd, env: { ...process.env, ...environment }, windowsHide: true, stdio: ['ignore', 'pipe', 'pipe'] });
  child.stdout.pipe(log, { end: false }); child.stderr.pipe(log, { end: false });
  child.startError = null; child.once('error', error => { child.startError = error.code; });
  children.push(child); return child;
}
async function ready(url, child) {
  for (let i = 0; i < 120; i++) {
    assert(!child.startError && child.exitCode === null, 'Service exited; inspect service log');
    try { const response = await fetch(url, { signal: AbortSignal.timeout(1000) }); if (response.ok) return; } catch {}
    await delay(500);
  }
  assert.fail('Service startup timeout');
}
async function api(base, endpoint, { token, body, raw = false } = {}) {
  const started = performance.now();
  const response = await fetch(base + '/api' + endpoint, { method: body === undefined ? 'GET' : 'POST',
    headers: { ...(token ? { Authorization: `Bearer ${token}` } : {}), ...(body === undefined ? {} : { 'Content-Type': 'application/json' }) },
    body: body === undefined ? undefined : JSON.stringify(body), signal: AbortSignal.timeout(70000) });
  const result = await response.json();
  if (raw) return { httpStatus: response.status, elapsedMs: performance.now() - started, response: result };
  assert.equal(result.code, 200, `Setup API failed: ${endpoint}`); return result.data;
}

// 仅用于传输与记录流程演练，不能据此判断真实语义质量。
function stubCompletion(payload, item, arm) {
  let decision;
  const facts = { A01: ['导数表示函数的局部变化率。'], A02: ['加速度描述速度随时间的变化快慢。'],
    A03: ['导数的用途是研究函数的变化趋势。'], A04: ['合成案例中的青栈标记由研究者顾宁提出。'],
    A05: ['HashMap 对已有 key 写入新值会覆盖旧值。', 'HashSet 不保留重复元素。'], A08: ['星云投影表示将星点编号映射为颜色。'] };
  if (arm === 'java') {
    const context = payload.messages[0].content;
    const chunks = [...context.matchAll(/片段(\d+)｜文档：[^\n]+\n([^]*?)(?=\n\n|$)/g)].map(m => ({ number: m[1], text: m[2] }));
    if (item.expect === 'refusal' || item.evidence.some(group => !chunks.some(c => group.some(t => c.text.includes(t))))) decision = '资料中没有找到足够依据。';
    else decision = facts[item.id].map((text, i) => text + item.evidence.filter((_, j) => item.id !== 'A05' || i === j)
      .map(group => '（片段' + chunks.find(c => group.some(t => c.text.includes(t))).number + '）').join('')).join('\n');
  } else {
    const data = JSON.parse(payload.messages.at(-1).content);
    if (data.stage === 'plan') decision = item.expect === 'clarification'
      ? { action: 'clarify', questions: [], clarification: '你指的是导数还是积分？' }
      : { action: 'search', questions: [{ id: 1, question: item.question }],
        query: { A01: '导数', A02: '加速度', A03: '导数', A04: '青栈标记', A05: 'HashMap', A06: '总体方差', A08: '星云投影' }[item.id] };
    else if (data.stage === 'review') decision = { claims: data.draft.claims.map(c => ({ claim_id: c.id, verdict: 'supported', reason: '本地演练判决' })), coverage: 'complete' };
    else if (item.expect === 'refusal') decision = { action: 'refuse', missing: '未提供实际调查数据。' };
    else {
      const matches = item.evidence.map(group => data.evidence.find(c => group.some(t => c.text.includes(t))));
      if (!data.evidence.length) decision = { action: 'search', query: item.question };
      else if (matches.some(c => !c)) decision = { action: 'read', anchor_ids: [data.evidence[0].id] };
      else decision = { action: 'draft', claims: facts[item.id].map((text, i) => ({ id: 'c' + i, text, covers: [1],
        citations: matches.filter((_, j) => item.id !== 'A05' || i === j).map(c => ({ chunk_id: c.id, quote: c.text.split('。')[0] + '。' })) })) };
    }
    decision = JSON.stringify(decision);
  }
  return { id: 'local-fixture', model: 'local-fixture', choices: [{ message: { role: 'assistant', content: decision }, finish_reason: 'stop' }],
    usage: { prompt_tokens: 100, completion_tokens: 30, total_tokens: 130 } };
}
async function handleRelay(request, response) {
  let record;
  try {
    if (request.method === 'GET' && request.url === '/python/health') {
      const health = await fetch(pythonBase + '/health', { signal: AbortSignal.timeout(4000) });
      response.writeHead(health.status, { 'Content-Type': 'application/json' }); response.end(await health.text()); return;
    }
    assert(request.method === 'POST' && active, 'Unexpected relay request');
    const pair = active;
    const pieces = []; let bytes = 0;
    for await (const piece of request) { bytes += piece.length; assert(bytes <= 262144); pieces.push(piece); }
    const payload = JSON.parse(Buffer.concat(pieces).toString('utf8'));
    if (request.url === '/python/v1/runs') {
      assert(pair.arm === 'agent' && request.headers.authorization === 'Bearer ' + serviceToken);
      const upstream = await fetch(pythonBase + '/v1/runs', { method: 'POST',
        headers: { Authorization: 'Bearer ' + serviceToken, 'Content-Type': 'application/json' },
        body: JSON.stringify(payload), signal: AbortSignal.timeout(60000) });
      pair.agentResult = await upstream.json();
      save(`${pair.item.id}-agent-run.json`, pair.agentResult);
      response.writeHead(upstream.status, { 'Content-Type': 'application/json' }); response.end(JSON.stringify(pair.agentResult)); return;
    }
    assert(request.url === `/${pair.arm}/chat/completions` && request.headers.authorization === 'Bearer ' + credentials[pair.arm], 'Unexpected model route');
    assert.equal(payload.model, modelName);
    if (provider?.blocked) throw new Error('Provider blocked; no further inference');
    let stage = 'answer';
    if (pair.arm === 'agent') stage = JSON.parse(payload.messages.at(-1).content).stage;
    const reservation = budget.reserve(pair.item.id, pair.arm);
    record = { ...reservation, stage, payload, startedAt: new Date().toISOString() };
    events.push(record);
    const name = `model-${String(record.number).padStart(2, '0')}.json`;
    save(name, record); // 请求、归属和占用额度均在外发前写入。
    const started = performance.now();
    try { record.completion = provider ? await provider.complete(payload, record) : stubCompletion(payload, pair.item, pair.arm); }
    finally { record.elapsedMs = performance.now() - started; record.lateUnused = response.destroyed; record.finishedAt = new Date().toISOString(); save(name, record); }
    response.writeHead(200, { 'Content-Type': 'application/json' }); response.end(JSON.stringify(record.completion));
  } catch (error) {
    if (record) { record.error = { name: error.name, diagnostic: error.diagnostic }; save(`model-${String(record.number).padStart(2, '0')}.json`, record); }
    if (!response.destroyed) { response.writeHead(503, { 'Content-Type': 'application/json' }); response.end('{"error":"evaluation relay stopped this request"}'); }
  }
}
const relay = createServer((request, response) => {
  const task = handleRelay(request, response); inflight.add(task); task.finally(() => inflight.delete(task));
});

try {
  if (provider) {
    const preflight = await provider.preflight(); save('preflight.json', preflight);
    assert(preflight.passed, 'Anonymous network preflight failed; no inference sent');
    console.log(`Anonymous network preflight passed; prior calls ${priorAttempts}/40`);
  }
  sql(`CREATE DATABASE ${schema} CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;`, false); created = true;
  for (const migration of ['stage1-user','stage2-student-profile','stage3-conversation-chat','stage4-ai-chat','stage6-teaching',
    'stage7-question-answer','stage8-rag','stage9-analysis','stage10-agent','stage12-learning-session','stage13-knowledge-map',
    'stage14-learner-memory','stage15-evaluation-governance','stage16-personal-graph','stage17-async-personal-graph',
    'stage18-conversation-documents','stage19-chat-rag-sources','stage20-rag-retry','stage21-tutor-flow','stage22-learning-evidence',
    'stage23-review-tasks','stage24-teaching-binding']) sql(readFileSync(path.join(backend, 'src/main/resources/db', migration + '.sql'), 'utf8'));
  relay.listen(0, '127.0.0.1'); await once(relay, 'listening');
  const relayBase = `http://127.0.0.1:${relay.address().port}`;
  const javaPort = await freePort(), candidatePort = await freePort(), pythonPort = await freePort();
  const bases = { java: `http://127.0.0.1:${javaPort}`, agent: `http://127.0.0.1:${candidatePort}` };
  pythonBase = `http://127.0.0.1:${pythonPort}`;
  const python = launch(options['python-bin'], ['-m','uvicorn','tutor_agent.app:build_app','--factory','--host','127.0.0.1',
    '--port',String(pythonPort),'--workers','1','--no-access-log'], path.join(root, 'agent-service'), {
    AGENT_MODE:'live', AGENT_ENABLE_REAL_MODEL:'true', AGENT_SERVICE_TOKEN:serviceToken, AGENT_JAVA_SERVICE_TOKEN:callbackToken,
    AGENT_MODEL_API_KEY:credentials.agent, AGENT_MODEL_BASE_URL:relayBase+'/agent', AGENT_MODEL_NAME:modelName,
    AGENT_JAVA_BASE_URL:bases.agent, PYTHONUTF8:'1',
  }, 'python');
  const jwtSecret = randomBytes(48).toString('hex'), servers = {};
  for (const arm of ['java', 'agent']) servers[arm] = launch(options['java-bin'], ['-Dfile.encoding=UTF-8','-jar',jar], backend, {
    SERVER_ADDRESS:'127.0.0.1', SERVER_PORT:String(arm === 'java' ? javaPort : candidatePort),
    SPRING_DATASOURCE_URL:`jdbc:mysql://127.0.0.1:${dbUrl.port || 3306}/${schema}?useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai&useSSL=false&allowPublicKeyRetrieval=true`,
    SPRING_DATASOURCE_USERNAME:databaseUser, SPRING_DATASOURCE_PASSWORD:databasePassword, JWT_SECRET:jwtSecret,
    RAG_STORAGE_DIR:path.join(output,'uploads-'+arm), RAG_CHUNK_SIZE:'800', RAG_CHUNK_OVERLAP:'120', RAG_TOP_K:'4',
    DEEPSEEK_BASE_URL:relayBase+'/'+arm, DEEPSEEK_API_KEY:credentials[arm], DEEPSEEK_MODEL_NAME:modelName, DEEPSEEK_TIMEOUT_MS:'60000',
    PYTHON_AGENT_ENABLED:String(arm === 'agent'), PYTHON_AGENT_BASE_URL:relayBase+'/python', PYTHON_AGENT_TIMEOUT_MS:'55000',
    AGENT_SERVICE_TOKEN:serviceToken, AGENT_JAVA_SERVICE_TOKEN:callbackToken,
  }, arm);
  await Promise.all([ready(bases.java+'/api/health',servers.java),ready(bases.agent+'/api/health',servers.agent),ready(pythonBase+'/health',python)]);
  await api(bases.java,'/health/db'); await api(bases.agent,'/health/db');
  const username = 'agent_eval_owner', password = randomBytes(16).toString('hex');
  await api(bases.java, '/auth/register', { body: { username, password } });
  const token = (await api(bases.java, '/auth/login', { body: { username, password } })).token;
  const userId = Number(sql("SELECT id FROM `user` WHERE username='agent_eval_owner';"));
  const documents = new Map(), seeded = [];
  for (const document of suite.documents) {
    const documentId = Number(sql(`INSERT INTO document(user_id,file_name,file_type,storage_path,process_status) VALUES(${userId},${literal(document.filename)},'txt','synthetic-evaluation-only','completed'); SELECT LAST_INSERT_ID();`));
    documents.set(document.id, documentId);
    document.chunks.forEach((text, chunkIndex) => {
      sql(`INSERT INTO document_chunk(document_id,chunk_index,chunk_text) VALUES(${documentId},${chunkIndex},${literal(text)});`);
      seeded.push({ documentId, chunkIndex, text });
    });
  }
  save('seeded-chunks.json', seeded);
  console.log('Isolated Java baseline + Java/Python candidate ready; exact synthetic chunks seeded');
  for (let index = 0; index < selectedCases.length; index++) {
    const item = selectedCases[index];
    for (const arm of repair ? ['agent'] : index % 2 === 0 ? ['java', 'agent'] : ['agent', 'java']) {
      if (provider?.blocked) { results.push({ caseId: item.id, arm, blocked: provider.blocked }); continue; }
      if(budget.state.attempts.length>=budget.state.limit) {results.push({caseId:item.id,arm,blocked:'authorization_exhausted'});continue;}
      const documentIds = item.documents.map(id => documents.get(id));
      const conversationId = (await api(bases[arm], '/conversations', { token, body: { title: item.id+' '+arm, mode:'rag', documentIds } })).conversationId;
      for (const question of item.history) sql(`INSERT INTO chat_history(user_id,conversation_id,role,message_content) VALUES(${userId},${conversationId},'user',${literal(question)});`);
      const requestId = randomUUID();
      budget.beginPair(item.id, arm); active = { item, arm };
      let result = { caseId: item.id, arm, conversationId, requestId, question: item.question };
      try { Object.assign(result, await api(bases[arm], '/ai/rag/chat', { token, body: { conversationId, question:item.question, requestId, attempt:1 }, raw:true })); }
      catch (error) { result.transportError = { name:error.name }; }
      // 客户端可能先超时；等外部请求结算并保存迟到用量，再开始下一题。
      while (inflight.size) await Promise.allSettled([...inflight]);
      if (active.agentResult) result.agentResult = active.agentResult;
      const contextHex = sql(`SELECT HEX(rag_context) FROM chat_history WHERE rag_request_id='${requestId}' AND role='user';`);
      if (contextHex) result.savedContext = JSON.parse(Buffer.from(contextHex,'hex').toString('utf8'));
      result.checks = checkSources(item, result.response, seeded.filter(c => documentIds.includes(c.documentId)));
      result.calls = events.filter(e => e.caseId === item.id && e.arm === arm).length;
      results.push(result); save(`${item.id}-${arm}.json`, result); save('progress.json', { results, calls:budget.state.attempts.length });
      budget.endPair(item.id, arm); active = null;
      console.log(`${item.id} ${arm}: API ${result.response?.code ?? 'transport failure'}, ${result.calls} calls, cumulative ${priorAttempts+budget.state.attempts.length}/40; mechanical=${result.checks.passed}`);
    }
  }
  assert.equal(results.length, selectedCases.length*(repair?1:2));
  if (options.mode === 'stub') assert(results.every(r => r.response?.code === 200), 'Stub transport rehearsal incomplete');
} catch (error) {
  failure = { name:error.name, message:error.message }; console.error(JSON.stringify(failure));
} finally {
  while (inflight.size) await Promise.allSettled([...inflight]);
  for (const child of children.reverse()) if (child.exitCode === null && !child.startError) {
    child.kill(); await Promise.race([once(child,'exit'),delay(5000)]);
    if (child.exitCode === null && child.signalCode === null) child.kill('SIGKILL');
  }
  if (relay.listening) { relay.closeAllConnections(); await new Promise(resolve => relay.close(resolve)); }
  let databaseDropped = false;
  if (created) try { sql(`DROP DATABASE ${schema};`,false); databaseDropped=true; } catch { failure ??= { message:'Temporary database cleanup failed' }; }
  for (const log of logs) log.end();
  const result = { mode:options.mode, round:options.round, priorAttempts, completed:!failure, semanticAcceptance:'pending_manual_review', frozenIdentity:identity,
    externalCalls:provider?.calls ?? 0, ledgerAttempts:budget.state.attempts.length, results, events, databaseDropped,
    processesStopped:children.every(child => child.exitCode !== null || child.signalCode !== null || child.startError), failure };
  save('result.json',result); budget.close();
  console.log(JSON.stringify({ completed:result.completed, externalCalls:result.externalCalls, databaseDropped, processesStopped:result.processesStopped }));
  process.exitCode = failure ? 1 : 0;
}
