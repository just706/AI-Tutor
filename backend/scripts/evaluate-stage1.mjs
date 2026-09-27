// 阶段 1 固定题集验收：retrieval 模式只诊断检索；real 模式调用已配置的真实模型。
// 示例见 docs/Development.md。需要先构建后端 jar，并安装 MySQL 客户端。
import assert from 'node:assert/strict';
import { spawn, spawnSync } from 'node:child_process';
import { randomBytes, randomUUID, createHash } from 'node:crypto';
import { createServer } from 'node:http';
import { createServer as createTcpServer } from 'node:net';
import { once } from 'node:events';
import { createWriteStream, existsSync, readFileSync, mkdirSync, writeFileSync, unlinkSync } from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { setTimeout as delay } from 'node:timers/promises';
import { parseArgs } from 'node:util';

const { values: options } = parseArgs({ options: {
  'project-root': { type: 'string', default: path.resolve(path.dirname(fileURLToPath(import.meta.url)), '../..') },
  'env-file': { type: 'string' },
  'output-dir': { type: 'string' },
  'mysql-bin': { type: 'string', default: 'mysql' },
  'java-bin': { type: 'string', default: 'java' },
  mode: { type: 'string', default: 'retrieval' },
  textbook: { type: 'string' },
  only: { type: 'string' },
  'hold-for-browser': { type: 'boolean', default: false },
} });
assert(options['output-dir'], '必须指定 --output-dir，日志和临时上传文件应保存在源码仓库外。');
const projectRoot = path.resolve(options['project-root']);
const outputDir = path.resolve(options['output-dir']);
const outputRelative = path.relative(projectRoot, outputDir);
assert(outputRelative.startsWith('..' + path.sep) || path.isAbsolute(outputRelative),
  '输出目录必须位于源码仓库之外。');
assert(!['cases.json', 'result.json', 'provenance.json'].some(file => existsSync(path.join(outputDir, file))),
  '输出目录已有评测记录，请换用新目录以保留先前结果。');
const backendRoot = path.join(projectRoot, 'backend');
const jarPath = path.join(backendRoot, 'target/ai-tutor-backend-0.0.1-SNAPSHOT.jar');
assert(existsSync(jarPath), '请先执行后端 verify，生成可运行 jar。');
mkdirSync(outputDir, { recursive: true });

// 读取数据库与模型配置；凭据不进入命令行或验收报告。
const config = {};
if (options['env-file']) {
  for (const line of readFileSync(options['env-file'], 'utf8').split(/\r?\n/)) {
    const match = line.match(/^\s*([A-Za-z_][A-Za-z0-9_]*)\s*=(.*)$/);
    if (match) config[match[1]] = match[2].trim().replace(/^(['"])(.*)\1$/, '$2');
  }
}
const setting = (name, fallback) => process.env[name] ?? config[name] ?? fallback;
const configuredUrl = setting('SPRING_DATASOURCE_URL', 'jdbc:mysql://localhost:3306/ai_tutor');
const dbUrl = new URL(configuredUrl.replace(/^jdbc:/, '').replace(/^mysql:/, 'http:'));
assert(['localhost', '127.0.0.1'].includes(dbUrl.hostname), '验收脚本仅允许连接本机 MySQL。');
const dbPort = dbUrl.port || '3306';
const dbUser = setting('SPRING_DATASOURCE_USERNAME', 'root');
const dbPassword = setting('SPRING_DATASOURCE_PASSWORD', '');
const schema = `ai_tutor_verify_${Date.now()}_${randomBytes(4).toString('hex')}`;
assert(/^ai_tutor_verify_[0-9]+_[a-f0-9]{8}$/.test(schema));
const mysqlEnv = { ...process.env, MYSQL_PWD: dbPassword };
function sql(statement, selectSchema = true) {
  const result = spawnSync(options['mysql-bin'], [
    '--protocol=TCP', '--host=127.0.0.1', `--port=${dbPort}`, `--user=${dbUser}`,
    '--default-character-set=utf8mb4', '--batch', '--skip-column-names',
    ...(selectSchema ? [schema] : []),
  ], { input: statement, encoding: 'utf8', env: mysqlEnv, windowsHide: true, timeout: 30000 });
  assert(!result.error, `MySQL 客户端无法运行：${result.error?.code}`);
  assert.equal(result.status, 0, `临时库 SQL 执行失败：${result.stderr}`);
  return result.stdout.trim();
}

const suitePath = path.join(backendRoot, 'evaluation/stage1-cases.json');
const suite = JSON.parse(readFileSync(suitePath, 'utf8'));
assert(['retrieval', 'real'].includes(options.mode), 'mode 必须是 retrieval 或 real');
assert(options.textbook && existsSync(options.textbook), '必须指定原 Java 教材 DOCX');
const sha = value => createHash('sha256').update(value).digest('hex');
const checks = [];
const pass = name => { checks.push(name); console.log('PASS ' + name); };
const cases = options.only ? suite.cases.filter(item => options.only.split(',').includes(item.id)) : suite.cases;
assert(cases.length > 0, '没有匹配的用例');
assert(!options.only || options.only.split(',').every(id => suite.cases.some(item => item.id === id)), '存在未知用例 ID');
const provider = new URL(setting('DEEPSEEK_BASE_URL', 'https://api.deepseek.com'));
assert.equal(provider.protocol, 'https:');
assert.equal(provider.hostname, 'api.deepseek.com', '此评测仅允许当前配置的官方 DeepSeek 目标');
assert(!provider.username && !provider.password && !provider.search && !provider.hash);
const model = setting('DEEPSEEK_MODEL_NAME', 'deepseek-v4-flash');
const modelKey = setting('DEEPSEEK_API_KEY', '');
if (options.mode === 'real') assert(modelKey, '真实模型密钥未配置');
const modelRequests = [];
const caseResults = [];
let graphStubCalls = 0;
let realCalls = 0;
const modelServer = createServer(async (request, response) => {
  try {
    let body = ''; for await (const chunk of request) body += chunk;
    const payload = JSON.parse(body);
    const prompt = payload.messages.map(message => message.content).join('\n');
    if (prompt.includes('个人知识图谱节点抽取助手') || prompt.includes('个人知识图谱关系抽取助手')) {
      graphStubCalls++;
      response.writeHead(200, { 'Content-Type': 'application/json' });
      const emptyGraph = prompt.includes('个人知识图谱节点抽取助手') ? { nodes: [] } : { edges: [] };
      response.end(JSON.stringify({ choices: [{ message: { content: JSON.stringify(emptyGraph) } }] }));
      return;
    }
    const record = { model, messages: payload.messages, promptSha256: sha(JSON.stringify(payload)), startedAt: new Date().toISOString() };
    modelRequests.push(record);
    let result;
    if (options.mode === 'real') {
      assert(realCalls < 64, '单次运行最多调用 64 次真实模型，超出即停止'); realCalls++;
      const upstream = await fetch(provider.href.replace(/\/$/, '') + '/chat/completions', {
        method: 'POST', headers: { 'Content-Type': 'application/json', Authorization: 'Bearer ' + modelKey },
        body: JSON.stringify(payload), signal: AbortSignal.timeout(120000), redirect: 'error'
      });
      record.httpStatus = upstream.status;
      if (!upstream.ok) {
        response.writeHead(upstream.status); response.end('{}'); return;
      }
      result = await upstream.json();
      record.responseModel = result.model; record.usage = result.usage;
      record.answer = result.choices?.[0]?.message?.content;
    } else {
      result = { choices: [{ message: { content: '仅用于检索诊断，不是模型质量评测。参考来源：片段1。' } }] };
    }
    record.finishedAt = new Date().toISOString();
    response.writeHead(200, { 'Content-Type': 'application/json' }); response.end(JSON.stringify(result));
  } catch (error) {
    // 不记录上游原始异常、地址、请求头或响应正文，避免泄漏凭据。
    response.writeHead(502); response.end('{}');
  }
});
let javaProcess;
let javaExited;
let databaseCreated = false;
let databaseRemoved = false;
let javaStartupError;
let apiBase;
let backendLog;
const api = async (route, { token, body, method = body === undefined ? 'GET' : 'POST', code = 200 } = {}) => {
  const form = body instanceof FormData;
  const response = await fetch(`${apiBase}${route}`, {
    method, headers: { ...(token ? { Authorization: `Bearer ${token}` } : {}),
      ...(!form && body !== undefined ? { 'Content-Type': 'application/json' } : {}) },
    body: body === undefined ? undefined : form ? body : JSON.stringify(body),
    signal: AbortSignal.timeout(150000),
  });
  const result = await response.json();
  assert.equal(result.code, code, `${method} ${route}: ${result.message}`);
  return result.data;
};
async function waitForExtraction(id, token) {
  for (let attempt = 0; attempt < 60; attempt++) {
    const extraction = await api(`/personal-graph/extractions/${id}`, { token });
    assert.notEqual(extraction.status, 'failed', extraction.errorMessage);
    if (extraction.status === 'completed') return extraction;
    await delay(500);
  }
  assert.fail('异步图谱提取超时。');
}
let failure;
try {
  // 不使用 IF NOT EXISTS；只有确认由本次创建的库才能在 finally 中删除。
  sql(`CREATE DATABASE ${schema} CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;`, false);
  databaseCreated = true;
  const migrations = [
    'stage1-user.sql', 'stage2-student-profile.sql', 'stage3-conversation-chat.sql', 'stage4-ai-chat.sql',
    'stage6-teaching.sql', 'stage7-question-answer.sql', 'stage8-rag.sql', 'stage9-analysis.sql',
    'stage10-agent.sql', 'stage12-learning-session.sql', 'stage13-knowledge-map.sql',
    'stage14-learner-memory.sql', 'stage15-evaluation-governance.sql',
    'stage16-personal-graph.sql', 'stage17-async-personal-graph.sql', 'stage18-conversation-documents.sql',
    'stage19-chat-rag-sources.sql', 'stage20-rag-retry.sql',
  ];
  for (const migration of migrations) sql(readFileSync(path.join(backendRoot, 'src/main/resources/db', migration), 'utf8'));
  assert.equal(Number(sql('SELECT COUNT(*) FROM information_schema.tables WHERE table_schema = DATABASE();')), 20);
  for (const migration of migrations.slice(-5)) sql(readFileSync(path.join(backendRoot, 'src/main/resources/db', migration), 'utf8'));
  pass('18 个数据库脚本初始化；stage16 至 stage20 重复执行；20 张表');

  modelServer.listen(0, '127.0.0.1');
  await once(modelServer, 'listening');
  const modelPort = modelServer.address().port;
  const reservation = createTcpServer();
  reservation.listen(0, '127.0.0.1');
  await once(reservation, 'listening');
  const serverPort = reservation.address().port;
  await new Promise(resolve => reservation.close(resolve));
  apiBase = `http://127.0.0.1:${serverPort}/api`;
  backendLog = createWriteStream(path.join(outputDir, 'backend.log'));
  javaProcess = spawn(options['java-bin'], ['-Dfile.encoding=UTF-8', '-jar', jarPath], {
    cwd: backendRoot, windowsHide: true, stdio: ['ignore', 'pipe', 'pipe'],
    env: { ...process.env, SERVER_ADDRESS: '127.0.0.1', SERVER_PORT: String(serverPort),
      SPRING_DATASOURCE_URL: `jdbc:mysql://127.0.0.1:${dbPort}/${schema}?useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai&useSSL=false&allowPublicKeyRetrieval=true`,
      SPRING_DATASOURCE_USERNAME: dbUser, SPRING_DATASOURCE_PASSWORD: dbPassword,
      JWT_SECRET: randomBytes(48).toString('hex'), RAG_STORAGE_DIR: path.join(outputDir, 'uploads'),
      RAG_CHUNK_SIZE: '800', RAG_CHUNK_OVERLAP: '120', RAG_TOP_K: '4',
      DEEPSEEK_BASE_URL: `http://127.0.0.1:${modelPort}`, DEEPSEEK_API_KEY: 'local-verification-only',
      DEEPSEEK_MODEL_NAME: model, DEEPSEEK_TIMEOUT_MS: '120000' },
  });
  javaProcess.stdout.pipe(backendLog, { end: false });
  javaProcess.stderr.pipe(backendLog, { end: false });
  javaExited = new Promise(resolve => {
    javaProcess.once('exit', resolve);
    javaProcess.once('error', error => { javaStartupError = error; resolve(); });
  });
  let ready = false;
  for (let attempt = 0; attempt < 60; attempt++) {
    assert(!javaStartupError, `Java 无法运行：${javaStartupError?.code}`);
    assert.equal(javaProcess.exitCode, null, '后端进程启动失败，请查看 backend.log。');
    try { await api('/health'); ready = true; break; } catch { await delay(500); }
  }
  assert(ready, '后端启动超时，请查看 backend.log。');
  await api('/health/db');
  pass('可运行 jar 启动及数据库连接');

  const password = randomBytes(15).toString('hex');
  const tokens = [];
  for (const username of ['stage0_owner', 'stage0_other']) {
    await api('/auth/register', { body: { username, password } });
    const login = await api('/auth/login', { body: { username, password } });
    assert(login.token);
    tokens.push(login.token);
  }
  const [token, otherToken] = tokens;

  const documents = {};
  const materialHashes = {};
  const chunksById = new Map();
  for (const name of ['java', ...Object.keys(suite.materials)]) {
    const bytes = name === 'java' ? readFileSync(options.textbook) : Buffer.from(suite.materials[name]);
    materialHashes[name] = sha(bytes);
    const form = new FormData();
    form.append('file', new Blob([bytes]), name === 'java' ? 'java-stage1.docx' : name + '-stage1.txt');
    const document = await api('/documents/upload', { token, body: form });
    documents[name] = document.documentId;
    if (document.personalGraphExtractionId) await waitForExtraction(document.personalGraphExtractionId, token);
    const chunks = await api('/documents/' + document.documentId + '/chunks', { token });
    chunksById.set(document.documentId, chunks);
  }
  pass('原教材 DOCX 及五份自编跨学科材料通过真实解析上传；图谱提取使用空替身以限定问答评测调用范围');
  writeFileSync(path.join(outputDir, 'materials.json'), JSON.stringify({ hashes: materialHashes, documents, chunks: Object.fromEntries(chunksById) }, null, 2));
  const normalize = value => value.normalize('NFKC').replace(/\s+/g, '');
  const matches = (snippet, alternatives) => alternatives.some(value => normalize(snippet).includes(normalize(value)));
  for (const item of cases) {
    const started = Date.now();
    const selected = item.documents.map(name => documents[name]);
    const conversation = await api('/conversations', { token, body: { title: '阶段一评测 ' + item.id, mode: 'rag', documentIds: selected } });
    const ask = question => api('/ai/rag/chat', { token, body: { conversationId: conversation.conversationId, question, requestId: randomUUID(), attempt: 1 } });
    const setup = [];
    for (const question of item.setup || []) setup.push({ question, result: await ask(question) });
    const beforeCalls = modelRequests.length;
    let result;
    try { result = await ask(item.question); }
    catch (error) {
      caseResults.push({ id: item.id, category: item.category, question: item.question, error: error.message, passed: false });
      console.log('FAIL ' + item.id + ' API error'); continue;
    }
    const modelRequest = modelRequests.length > beforeCalls ? modelRequests.at(-1) : null;
    const sources = result.sources;
    const sourcesValid = sources.every(source => selected.includes(source.documentId)
      && chunksById.get(source.documentId).some(chunk => chunk.chunkIndex === source.chunkIndex && chunk.chunkText === source.snippet));
    const evidence = (item.evidence || []).map(group => sources.some(source => matches(source.snippet, group)));
    const combinedEvidenceNeeded = !item.distinctChunks || (sources.length > 1
      && !sources.some(source => item.evidence.every(group => matches(source.snippet, group))));
    const resolved = !item.resolvedIncludes || item.resolvedIncludes.every(name =>
      result.answer.startsWith('本次按“') && result.answer.split('”理解你的追问。')[0].includes(name));
    const expectedKind = item.expect === 'refusal' ? result.answer.includes('资料中没有找到足够依据')
      : item.expect === 'clarification' ? result.answer.includes('请明确') && sources.length === 0
      : !result.answer.includes('资料中没有找到足够依据') && sources.length > 0;
    const forbiddenAbsent = !(item.forbidden || []).some(text => result.answer.includes(text));
    const restored = await api('/conversations/' + conversation.conversationId + '/messages', { token });
    assert.equal(restored.at(-1).messageContent, result.answer);
    assert.deepEqual(restored.at(-1).sources, sources);
    const automaticChecks = { sourcesValid, evidence, combinedEvidenceNeeded, resolved, expectedKind, forbiddenAbsent };
    const passed = sourcesValid && evidence.every(Boolean) && combinedEvidenceNeeded && resolved && expectedKind && forbiddenAbsent;
    caseResults.push({ id: item.id, category: item.category, conversationId: conversation.conversationId, question: item.question, setup, result, automaticChecks, passed,
      modelInvoked: Boolean(modelRequest), modelRequest, elapsedMs: Date.now() - started, rubric: item.rubric });
    writeFileSync(path.join(outputDir, 'cases.json'), JSON.stringify(caseResults, null, 2));
    console.log((passed ? 'PASS ' : 'FAIL ') + item.id + ' evidence=' + JSON.stringify(evidence) + ' ' + (Date.now() - started) + 'ms');
  }
  const provenance = { suiteVersion: suite.version, suiteSha256: sha(readFileSync(suitePath)),
    jarSha256: sha(readFileSync(jarPath)), promptSourceSha256: sha(readFileSync(path.join(backendRoot, 'src/main/java/com/aitutor/ai/AiPromptBuilder.java'))),
    materials: materialHashes, mode: options.mode, model, provider: provider.origin, realCalls, graphStubCalls,
    modelRequests, topK: 4, chunkSize: 800, chunkOverlap: 120, timeoutMs: 120000 };
  writeFileSync(path.join(outputDir, 'provenance.json'), JSON.stringify(provenance, null, 2));
  pass('已保存逐题问题、预期依据检查、回答、检索片段、模型请求、版本校验值及耗时；真实质量仍需逐题审阅');
  if (options['hold-for-browser']) {
    const finishPath = path.join(outputDir, schema + '.browser-finished');
    writeFileSync(path.join(outputDir, 'browser-fixture.json'), JSON.stringify({ apiBase, username: 'stage0_owner', password,
      cases: caseResults.map(item => ({ id: item.id, conversationId: item.conversationId })), finishPath }, null, 2));
    console.log('浏览器复核环境已就绪；账号与地址仅写入输出目录的 browser-fixture.json，30 分钟后自动清理。');
    const deadline = Date.now() + 30 * 60 * 1000;
    while (!existsSync(finishPath) && Date.now() < deadline) await delay(500);
  }
} catch (error) {
  failure = error;
} finally {
  writeFileSync(path.join(outputDir, 'cases.json'), JSON.stringify(caseResults, null, 2));
  if (failure) writeFileSync(path.join(outputDir, 'failed-model-requests.json'), JSON.stringify(modelRequests, null, 2));
  if (javaProcess?.pid && javaProcess.exitCode === null) {
    javaProcess.kill();
    await javaExited;
  }
  backendLog?.end();
  if (modelServer.listening) {
    modelServer.closeAllConnections();
    await new Promise(resolve => modelServer.close(resolve));
  }
  if (databaseCreated) {
    try { sql(`DROP DATABASE ${schema};`, false); databaseRemoved = true; }
    catch (error) { failure ??= error; }
  }
  writeFileSync(path.join(outputDir, 'result.json'), JSON.stringify({
    passed: !failure && caseResults.length === cases.length && caseResults.every(item => item.passed), checks,
    mode: options.mode, model, realCalls, caseCount: caseResults.length, automaticPassCount: caseResults.filter(item => item.passed).length,
    requiresAnswerReview: options.mode === 'real', fullSuite: !options.only,
    temporaryDatabase: schema, cleanup: databaseCreated ? (databaseRemoved ? 'removed' : 'failed') : 'not needed',
    error: failure?.message, time: new Date().toISOString(),
  }, null, 2));
}
if (failure) { console.error(failure.message); process.exitCode = 1; }
else {
  console.log('自动检查 ' + caseResults.filter(item => item.passed).length + '/' + cases.length + '；临时数据库和测试服务已清理。');
  if (!caseResults.every(item => item.passed)) process.exitCode = 2;
}
