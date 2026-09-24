// 阶段 0 接口验收：使用临时 MySQL 库和本地模型替身，不调用真实模型。
// 示例见 docs/Development.md。需要先构建后端 jar，并安装 MySQL 客户端。
import assert from 'node:assert/strict';
import { spawn, spawnSync } from 'node:child_process';
import { randomBytes } from 'node:crypto';
import { createServer } from 'node:http';
import { createServer as createTcpServer } from 'node:net';
import { once } from 'node:events';
import { createWriteStream, existsSync, readFileSync, mkdirSync, writeFileSync } from 'node:fs';
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
  'hold-for-browser': { type: 'boolean', default: false },
} });
assert(options['output-dir'], '必须指定 --output-dir，日志和临时上传文件应保存在源码仓库外。');
const projectRoot = path.resolve(options['project-root']);
const outputDir = path.resolve(options['output-dir']);
const backendRoot = path.join(projectRoot, 'backend');
const jarPath = path.join(backendRoot, 'target/ai-tutor-backend-0.0.1-SNAPSHOT.jar');
assert(existsSync(jarPath), '请先执行后端 verify，生成可运行 jar。');
mkdirSync(outputDir, { recursive: true });

// 只读取数据库连接参数；凭据不进入命令行或验收报告。
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

const checks = [];
const pass = name => { checks.push(name); console.log(`PASS ${name}`); };
const modelRequests = [];
const textbook = 'ArrayList 基于动态数组，实现 List 接口，随机访问速度快，适合经常通过索引访问元素。LinkedList 基于双向链表，也实现 List 接口。ArrayList 和 LinkedList 都是 List 的常见实现类。';
const modelServer = createServer(async (request, response) => {
  try {
    let body = '';
    for await (const chunk of request) body += chunk;
    const payload = JSON.parse(body);
    const prompt = payload.messages.map(message => message.content).join('\n');
    modelRequests.push(prompt);
    const indexes = [...prompt.matchAll(/\[chunkIndex=(\d+)\]/g)].map(match => Number(match[1]));
    const evidence = [indexes[0] ?? 0];
    let content;
    if (prompt.includes('个人知识图谱节点抽取助手')) {
      assert.equal(payload.response_format?.type, 'json_object');
      content = JSON.stringify({ nodes: [
        { name: 'ArrayList', description: '动态数组，索引访问快。', confidence: 95, evidenceChunkIndexes: evidence },
        { name: 'LinkedList', description: '双向链表。', confidence: 90, evidenceChunkIndexes: evidence },
      ] });
    } else if (prompt.includes('个人知识图谱关系抽取助手')) {
      assert.equal(payload.response_format?.type, 'json_object');
      content = JSON.stringify({ edges: [{ sourceName: 'ArrayList', targetName: 'LinkedList',
        relationType: 'related', relationReason: '都是 List 的实现。', confidence: 90, evidenceChunkIndexes: evidence }] });
    } else {
      assert(prompt.includes('ArrayList'), '教材证据没有进入模型请求。');
      content = '经常按下标读取元素，应该选 ArrayList。它基于动态数组，随机访问较快。参考来源：片段1。';
    }
    response.writeHead(200, { 'Content-Type': 'application/json; charset=utf-8' });
    response.end(JSON.stringify({ choices: [{ message: { content } }], usage: { prompt_tokens: 20, completion_tokens: 10 } }));
  } catch (error) {
    response.writeHead(400, { 'Content-Type': 'application/json' });
    response.end(JSON.stringify({ error: { message: error.message } }));
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
    signal: AbortSignal.timeout(30000),
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
async function upload(text, filename, token) {
  const form = new FormData();
  form.append('file', new Blob([text], { type: 'text/plain' }), filename);
  return api('/documents/upload', { token, body: form });
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
  ];
  for (const migration of migrations) sql(readFileSync(path.join(backendRoot, 'src/main/resources/db', migration), 'utf8'));
  assert.equal(Number(sql('SELECT COUNT(*) FROM information_schema.tables WHERE table_schema = DATABASE();')), 20);
  for (const migration of migrations.slice(-3)) sql(readFileSync(path.join(backendRoot, 'src/main/resources/db', migration), 'utf8'));
  pass('16 个数据库脚本初始化；stage16 至 stage18 重复执行；20 张表');

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
      DEEPSEEK_MODEL_NAME: 'local-verification', DEEPSEEK_TIMEOUT_MS: '5000' },
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
  const conversation = await api('/conversations', { token, body: { title: '集合框架验收', mode: 'rag' } });
  assert(conversation.conversationId);
  const publicGraph = await api('/knowledge-map/graph', { token });
  assert(publicGraph.nodes.length > 0);
  pass('双用户注册登录、创建教材会话、公共图谱');

  const document = await upload(textbook, 'collections.txt', token);
  assert.equal(document.processStatus, 'completed');
  assert(document.personalGraphExtractionId);
  const extraction = await waitForExtraction(document.personalGraphExtractionId, token);
  assert.equal(extraction.candidates.nodes.length, 2);
  assert.equal(extraction.candidates.edges.length, 1);
  const graphPath = `/personal-graph?documentId=${document.documentId}`;
  assert.equal((await api(graphPath, { token })).nodes.length, 0);
  await api(`/personal-graph/extractions/${extraction.id}/publish`, { token, method: 'POST' });
  await api(`/personal-graph/extractions/${extraction.id}/publish`, { token, method: 'POST' });
  const graph = await api(graphPath, { token });
  assert.equal(graph.nodes.length, 2);
  assert.equal(graph.edges.length, 1);
  assert(graph.nodes.every(node => node.source.length > 0 && node.source[0].chunkIndex === 0));
  pass('上传、异步节点和关系提取、显式发布、证据与重复发布');

  const question = { conversationId: conversation.conversationId, question: '经常按下标读取元素，应该选哪一种？', documentIds: [document.documentId] };
  const answer = await api('/ai/rag/chat', { token, body: question });
  assert(answer.answer.includes('ArrayList'));
  assert(answer.sources.some(source => source.documentId === document.documentId && source.snippet.includes('ArrayList')));
  const requestCount = modelRequests.length;
  const refusal = await api('/ai/rag/chat', { token, body: { ...question, question: '量子纠缠如何测量？' } });
  assert.equal(refusal.sources.length, 0);
  assert(refusal.answer.includes('没有找到足够依据'));
  assert.equal(modelRequests.length, requestCount);
  pass('中文下标读取问题召回证据；无依据问题拒答且不调用模型');

  const bindingPath = `/conversations/${conversation.conversationId}/documents`;
  assert.deepEqual((await api(`/conversations/${conversation.conversationId}`, { token })).documentIds, [document.documentId]);
  const savedAnswer = await api('/ai/rag/chat', { token, body: { conversationId: conversation.conversationId, question: question.question } });
  assert(savedAnswer.sources.every(source => source.documentId === document.documentId));
  assert(savedAnswer.sources.length > 0);
  await api(bindingPath, { token: otherToken, method: 'PUT', body: { documentIds: [] }, code: 404 });
  await api(bindingPath, { token, method: 'PUT', body: { documentIds: [document.documentId, 999999] }, code: 404 });
  assert.deepEqual((await api(`/conversations/${conversation.conversationId}`, { token })).documentIds, [document.documentId]);
  await api(bindingPath, { token, method: 'PUT', body: { documentIds: [] } });
  const beforeEmptySelection = modelRequests.length;
  await api('/ai/rag/chat', { token, body: { conversationId: conversation.conversationId, question: question.question }, code: 400 });
  assert.equal(modelRequests.length, beforeEmptySelection);
  await api(bindingPath, { token, method: 'PUT', body: { documentIds: [document.documentId] } });
  const createdBound = await api('/conversations', { token, body: { title: '创建时绑定', mode: 'rag', documentIds: [document.documentId] } });
  assert.deepEqual((await api(`/conversations/${createdBound.conversationId}`, { token })).documentIds, [document.documentId]);
  const ordinary = await api('/conversations', { token, body: { title: '普通会话', mode: 'chat' } });
  await api(`/conversations/${ordinary.conversationId}/documents`, { token, method: 'PUT', body: { documentIds: [document.documentId] }, code: 400 });
  pass('教材绑定保存和恢复；省略教材参数使用已保存选择；空选、混入无权教材和普通会话绑定被拒绝');

  for (const route of [`/documents/${document.documentId}`, `/documents/${document.documentId}/chunks`,
    `/personal-graph/extractions/${extraction.id}`, graphPath]) {
    await api(route, { token: otherToken, code: 404 });
  }
  await api(`/personal-graph/extractions/${extraction.id}/publish`, { token: otherToken, method: 'POST', code: 404 });
  await api('/ai/rag/chat', { token: otherToken, body: question, code: 404 });
  const otherConversation = await api('/conversations', { token: otherToken, body: { title: '权限验收', mode: 'rag' } });
  await api('/ai/rag/chat', { token: otherToken, body: { ...question, conversationId: otherConversation.conversationId }, code: 404 });
  pass('他人教材、片段、会话和图谱均不能读取或发布');

  const largeDocument = await upload(textbook.repeat(1400), 'long-collections.txt', token);
  assert.equal(largeDocument.processStatus, 'completed');
  assert(largeDocument.chunkCount > 100);
  assert.equal(largeDocument.personalGraphExtractionId, null);
  await api('/personal-graph/extractions', { token, body: { documentId: largeDocument.documentId }, code: 400 });
  const reprocessedLarge = await api(`/documents/${largeDocument.documentId}/reprocess`, { token, method: 'POST' });
  assert.equal(reprocessedLarge.processStatus, 'completed');
  assert.equal(reprocessedLarge.personalGraphExtractionId, null);
  const largeAnswer = await api('/ai/rag/chat', { token, body: { ...question, documentIds: [largeDocument.documentId] } });
  assert(largeAnswer.sources.length > 0);
  pass('超过 100 片段的教材仍可上传、重处理和问答；图谱提取保留限制');

  const reprocessed = await api(`/documents/${document.documentId}/reprocess`, { token, method: 'POST' });
  await waitForExtraction(reprocessed.personalGraphExtractionId, token);
  assert.equal((await api(graphPath, { token })).nodes.length, 0);
  await api(`/documents/${document.documentId}`, { token, method: 'DELETE' });
  await api(`/documents/${largeDocument.documentId}`, { token, method: 'DELETE' });
  await api('/ai/rag/chat', { token, body: { conversationId: createdBound.conversationId, question: question.question }, code: 404 });
  assert.equal((await api('/documents', { token })).length, 0);
  assert.equal((await api('/personal-graph', { token })).nodes.length, 0);
  pass('重处理清理旧图谱；删除教材清理片段和图谱');

  if (options['hold-for-browser']) {
    const browserDocument = await upload(textbook, 'browser-collections.txt', token);
    await waitForExtraction(browserDocument.personalGraphExtractionId, token);
    const browserConversation = await api('/conversations', { token, body: { title: '浏览器教材会话', mode: 'rag', documentIds: [browserDocument.documentId] } });
    const finishPath = path.join(outputDir, `${schema}.browser-finished`);
    writeFileSync(path.join(outputDir, 'browser-fixture.json'), JSON.stringify({ apiBase, username: 'stage0_owner', password,
      conversationId: browserConversation.conversationId, documentId: browserDocument.documentId, finishPath }, null, 2));
    console.log(`浏览器验收环境已就绪，临时账号与地址见 ${path.join(outputDir, 'browser-fixture.json')}。完成后创建该文件中 finishPath 指定的标记；30 分钟后自动清理。`);
    const deadline = Date.now() + 30 * 60 * 1000;
    while (!existsSync(finishPath) && Date.now() < deadline) await delay(500);
  }
} catch (error) {
  failure = error;
} finally {
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
    passed: !failure, checks, model: 'local deterministic stub; not a model-quality evaluation',
    temporaryDatabase: schema, cleanup: databaseCreated ? (databaseRemoved ? 'removed' : 'failed') : 'not needed',
    error: failure?.message, time: new Date().toISOString(),
  }, null, 2));
}
if (failure) { console.error(failure.message); process.exitCode = 1; }
else console.log(`全部 ${checks.length} 组验收通过。临时数据库和测试服务已清理。`);
