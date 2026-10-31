// 隔离MySQL、真实Java/Python进程和本地模型替身的接入验收；不使用外部模型。
import assert from 'node:assert/strict';
import { spawn, spawnSync } from 'node:child_process';
import { randomBytes, randomUUID } from 'node:crypto';
import { createServer } from 'node:http';
import { once } from 'node:events';
import { createWriteStream, existsSync, readFileSync, mkdirSync, writeFileSync } from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { setTimeout as delay } from 'node:timers/promises';
import { parseArgs } from 'node:util';

const { values: options } = parseArgs({ options: {
  'output-dir': { type: 'string' }, 'env-file': { type: 'string' },
  'mysql-bin': { type: 'string', default: 'mysql' }, 'java-bin': { type: 'string', default: 'java' },
  'python-bin': { type: 'string', default: 'python' }, 'browser': { type: 'boolean', default: false },
} });
assert(options['output-dir'], 'Specify a new output directory');
const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '../..');
const output = path.resolve(options['output-dir']);
assert(!existsSync(output), 'Use a new output directory');
const backend = path.join(root, 'backend');
const jar = path.join(backend, 'target/ai-tutor-backend-0.0.1-SNAPSHOT.jar');
assert(existsSync(jar), 'Build backend first');
mkdirSync(output, { recursive: true });
const config = {};
if (options['env-file']) for (const line of readFileSync(options['env-file'], 'utf8').split(/\r?\n/)) {
  const match = line.match(/^\s*([A-Za-z_][A-Za-z0-9_]*)\s*=(.*)$/);
  if (match) config[match[1]] = match[2].trim().replace(/^(['"])(.*)\1$/, '$2');
}
const setting = (key, fallback) => process.env[key] ?? config[key] ?? fallback;
const dbUrl = new URL(setting('SPRING_DATASOURCE_URL', 'jdbc:mysql://localhost:3306/ai_tutor').replace(/^jdbc:/, '').replace(/^mysql:/, 'http:'));
assert(['localhost', '127.0.0.1'].includes(dbUrl.hostname));
const schema = `ai_tutor_agent_verify_${Date.now()}_${randomBytes(4).toString('hex')}`;
assert(/^ai_tutor_agent_verify_\d+_[a-f0-9]{8}$/.test(schema));
const databaseUser = setting('SPRING_DATASOURCE_USERNAME', 'root');
const databasePassword = setting('SPRING_DATASOURCE_PASSWORD', '');
function sql(statement, selected = true) {
  const result = spawnSync(options['mysql-bin'], ['--protocol=TCP', '--host=127.0.0.1', `--port=${dbUrl.port || 3306}`,
    `--user=${databaseUser}`, '--default-character-set=utf8mb4', '--batch', '--skip-column-names', ...(selected ? [schema] : [])],
  { input: statement, encoding: 'utf8', env: { ...process.env, MYSQL_PWD: databasePassword }, windowsHide: true, timeout: 30000 });
  assert(!result.error, `MySQL invocation failed: ${result.error?.code}`);
  assert.equal(result.status, 0, 'Isolated SQL operation failed');
  return result.stdout.trim();
}
async function freePort() {
  const server = createServer(); server.listen(0, '127.0.0.1'); await once(server, 'listening');
  const port = server.address().port; await new Promise(resolve => server.close(resolve)); return port;
}
const children = [], logs = [], checks = [], events = [];
const pass = name => { checks.push(name); console.log(`PASS ${name}`); };
function launch(command, args, cwd, environment, name) {
  const log = createWriteStream(path.join(output, name + '.log')); logs.push(log);
  const child = spawn(command, args, { cwd, env: { ...process.env, ...environment }, windowsHide: true,
    stdio: ['ignore', 'pipe', 'pipe'] });
  child.stdout.pipe(log, { end: false }); child.stderr.pipe(log, { end: false });
  child.startError = null; child.once('error', error => { child.startError = error.code; });
  children.push(child); return child;
}
async function ready(url, child) {
  for (let attempt = 0; attempt < 120; attempt++) {
    assert(!child.startError && child.exitCode === null, 'Service exited before readiness; inspect service log');
    try { const response = await fetch(url, { signal: AbortSignal.timeout(1000) }); if (response.ok) return; } catch {}
    await delay(500);
  }
  assert.fail('Service startup timeout');
}
let apiBase, agentBase, failure, created = false, failNext = false, holdReview = false, releaseReview, reviewSeen;
let browserInvocation;
const model = createServer(async (request, response) => {
  try {
    const pieces = []; for await (const piece of request) pieces.push(piece);
    const body = JSON.parse(Buffer.concat(pieces).toString('utf8'));
    let data; try { data = JSON.parse(body.messages.at(-1).content); } catch { data = {}; }
    let decision;
    if (!data.stage) decision = { nodes: [], edges: [] }; // 上传后的可选图谱提取夹具。
    else {
      events.push({ stage: data.stage, question: data.question });
      if (failNext) { failNext = false; response.writeHead(503); response.end('local failure'); return; }
      if (data.stage === 'plan') {
        if (data.question === '它是什么？') decision = { action: 'clarify', questions: [], clarification: '你指的是哪个概念？' };
        else decision = { action: 'search', questions: [{ id: 1, question: data.question }],
          query: data.question.includes('蓝桥') ? '蓝桥编码' : data.question.includes('局部变化快慢') ? '完全不匹配的初次查询'
            : data.question.includes('方差') ? '总体方差' : '导数' };
      } else if (data.stage === 'review') {
        if (holdReview) {
          holdReview = false; reviewSeen?.();
          await new Promise(resolve => { releaseReview = resolve; });
        }
        decision = { claims: data.draft.claims.map(claim => ({ claim_id: claim.id, verdict: 'supported', reason: '固定联调判决' })), coverage: 'complete' };
      } else if (data.question.includes('方差')) decision = { action: 'refuse', missing: '没有调查数据。' };
      else if (data.question.includes('蓝桥')) {
        const source = data.evidence.find(chunk => chunk.text.includes('陈雨'));
        decision = source ? { action: 'draft', claims: [{ id: 'c1', text: '合成材料中的蓝桥编码由陈雨发明。', covers: [1],
          citations: [{ chunk_id: source.id, quote: '在这个合成材料中，该规则由陈雨发明。' }] }] }
          : { action: 'read', anchor_ids: [data.evidence.find(chunk => chunk.text.includes('蓝桥编码')).id] };
      } else if (!data.evidence.length) decision = { action: 'search', query: '导数' };
      else {
        const source = data.evidence.find(chunk => chunk.text.includes('导数表示函数的局部变化率。'));
        decision = { action: 'draft', claims: [{ id: 'c1', text: '导数表示函数的局部变化率。', covers: [1],
          citations: [{ chunk_id: source.id, quote: '导数表示函数的局部变化率。' }] }] };
      }
    }
    response.writeHead(200, { 'Content-Type': 'application/json' });
    response.end(JSON.stringify({ choices: [{ message: { content: JSON.stringify(decision) }, finish_reason: 'stop' }],
      usage: { prompt_tokens: 100, completion_tokens: 30 } }));
  } catch {
    response.writeHead(500); response.end('Invalid local fixture');
  }
});
async function api(endpoint, { token, body, method, code = 200 } = {}) {
  const response = await fetch(apiBase + endpoint, { method: method ?? (body === undefined ? 'GET' : 'POST'),
    headers: { ...(token ? { Authorization: `Bearer ${token}` } : {}), ...(body === undefined ? {} : { 'Content-Type': 'application/json' }) },
    body: body === undefined ? undefined : JSON.stringify(body), signal: AbortSignal.timeout(70000) });
  const result = await response.json(); assert.equal(result.code, code, `${endpoint}: ${result.message}`); return result.data;
}
async function upload(text, token) {
  const form = new FormData(); form.append('file', new Blob([text]), 'agent-material.txt');
  const response = await fetch(apiBase + '/documents/upload', { method: 'POST', headers: { Authorization: `Bearer ${token}` }, body: form });
  const result = await response.json(); assert.equal(result.code, 200); return result.data.documentId;
}
function savedContext(key) {
  assert(/^[a-f0-9-]{36}$/.test(key));
  return JSON.parse(Buffer.from(sql(`SELECT HEX(rag_context) FROM chat_history WHERE rag_request_id='${key}' AND role='user';`), 'hex').toString('utf8'));
}
try {
  sql(`CREATE DATABASE ${schema} CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;`, false); created = true;
  for (const migration of ['stage1-user','stage2-student-profile','stage3-conversation-chat','stage4-ai-chat','stage6-teaching',
    'stage7-question-answer','stage8-rag','stage9-analysis','stage10-agent','stage12-learning-session','stage13-knowledge-map',
    'stage14-learner-memory','stage15-evaluation-governance','stage16-personal-graph','stage17-async-personal-graph',
    'stage18-conversation-documents','stage19-chat-rag-sources','stage20-rag-retry','stage21-tutor-flow','stage22-learning-evidence',
    'stage23-review-tasks','stage24-teaching-binding']) sql(readFileSync(path.join(backend, 'src/main/resources/db', migration + '.sql'), 'utf8'));
  pass('临时数据库初始化，无新增迁移');
  model.listen(0, '127.0.0.1'); await once(model, 'listening');
  const modelBase = `http://127.0.0.1:${model.address().port}`;
  const javaPort = await freePort(), pythonPort = await freePort();
  apiBase = `http://127.0.0.1:${javaPort}/api`; agentBase = `http://127.0.0.1:${pythonPort}`;
  const serviceToken = randomBytes(32).toString('hex'), callbackToken = randomBytes(32).toString('hex');
  const agent = launch(options['python-bin'], ['-m','uvicorn','tutor_agent.app:build_app','--factory','--host','127.0.0.1',
    '--port',String(pythonPort),'--workers','1','--no-access-log'], path.join(root,'agent-service'), {
    AGENT_MODE:'live', AGENT_ENABLE_REAL_MODEL:'true', AGENT_SERVICE_TOKEN:serviceToken, AGENT_JAVA_SERVICE_TOKEN:callbackToken,
    AGENT_MODEL_API_KEY:'local-fixture-only', AGENT_MODEL_BASE_URL:modelBase, AGENT_MODEL_NAME:'local-fixture',
    AGENT_JAVA_BASE_URL:`http://127.0.0.1:${javaPort}`, PYTHONUTF8:'1',
  }, 'python');
  const java = launch(options['java-bin'], ['-Dfile.encoding=UTF-8','-jar',jar], backend, {
    SERVER_ADDRESS:'127.0.0.1', SERVER_PORT:String(javaPort),
    SPRING_DATASOURCE_URL:`jdbc:mysql://127.0.0.1:${dbUrl.port || 3306}/${schema}?useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai&useSSL=false&allowPublicKeyRetrieval=true`,
    SPRING_DATASOURCE_USERNAME:databaseUser, SPRING_DATASOURCE_PASSWORD:databasePassword, JWT_SECRET:randomBytes(48).toString('hex'),
    RAG_STORAGE_DIR:path.join(output,'uploads'), RAG_CHUNK_SIZE:'800', RAG_CHUNK_OVERLAP:'120', RAG_TOP_K:'4',
    DEEPSEEK_BASE_URL:modelBase, DEEPSEEK_API_KEY:'local-fixture-only', DEEPSEEK_MODEL_NAME:'local-fixture', DEEPSEEK_TIMEOUT_MS:'5000',
    PYTHON_AGENT_ENABLED:'true', PYTHON_AGENT_BASE_URL:agentBase, PYTHON_AGENT_TIMEOUT_MS:'55000',
    AGENT_SERVICE_TOKEN:serviceToken, AGENT_JAVA_SERVICE_TOKEN:callbackToken,
  }, 'java');
  await Promise.all([ready(apiBase+'/health',java),ready(agentBase+'/health',agent)]);
  await api('/health/db'); pass('真实 Java 与 Python 服务启动');
  const password=randomBytes(16).toString('hex'), tokens=[];
  for (const username of ['agent_owner','agent_other']) {
    await api('/auth/register',{body:{username,password}});
    tokens.push((await api('/auth/login',{body:{username,password}})).token);
  }
  const [token,otherToken]=tokens;
  const documentId=await upload('导数表示函数的局部变化率。\n总体方差衡量数据离散程度，本文未给出具体调查数据。',token);
  const conversationId=(await api('/conversations',{token,body:{title:'Python Agent 接入验证',mode:'rag',documentIds:[documentId]}})).conversationId;
  const request=question=>({conversationId,question,requestId:randomUUID(),attempt:1});
  const initial=request('导数是什么？');
  const answer=await api('/ai/rag/chat',{token,body:initial});
  assert.equal(answer.answer,'导数表示函数的局部变化率。[1]'); assert.equal(answer.sources[0].documentId,documentId);
  assert.equal(savedContext(initial.requestId).agent.audit.model_calls,3);
  pass('原教材聊天接口经 Python 调用 Java 工具，保存回答与原文引用');
  const before=events.length;
  assert.deepEqual(await api('/ai/rag/chat',{token,body:initial}),answer);
  await api('/ai/rag/chat',{token,body:{...initial,question:'不同问题'},code:409});
  assert.equal(events.length,before); pass('已完成请求持久化去重及内容冲突');
  const history=await api(`/conversations/${conversationId}/messages`,{token});
  assert.equal(history.length,2); assert.equal(history[0].ragStatus,'completed'); assert.equal(history[1].sources[0].snippet,answer.sources[0].snippet);
  pass('历史恢复一问一答及引用');
  const rewritten=request('函数局部变化快慢叫什么？');
  await api('/ai/rag/chat',{token,body:rewritten});
  assert.equal(savedContext(rewritten.requestId).agent.searches,2); assert.equal(savedContext(rewritten.requestId).agent.audit.model_calls,4);
  pass('模型改写后再次检索');
  const refused=await api('/ai/rag/chat',{token,body:request('这份调查的总体方差是多少？')});
  assert.equal(refused.sources.length,0); assert.match(refused.answer,/证据不足/);
  const clarified=await api('/ai/rag/chat',{token,body:request('它是什么？')});
  assert.equal(clarified.answer,'你指的是哪个概念？'); pass('拒答和澄清保存为正常完成');
  const adjacentId=await upload('蓝桥编码是本合成材料中的颜色规则。',token);
  sql(`INSERT INTO document_chunk(document_id,chunk_index,chunk_text,create_time) VALUES(${adjacentId},1,'在这个合成材料中，该规则由陈雨发明。',NOW());`);
  const adjacent=request('蓝桥编码的发明者是谁？'); adjacent.documentIds=[adjacentId];
  const adjacentAnswer=await api('/ai/rag/chat',{token,body:adjacent});
  assert.equal(adjacentAnswer.sources[0].chunkIndex,1); assert.equal(savedContext(adjacent.requestId).agent.reads,1);
  pass('仅凭已检索锚点补读相邻片段');
  await api('/ai/rag/chat',{token:otherToken,body:request('导数是什么？'),code:404});
  const otherConversation=(await api('/conversations',{token:otherToken,body:{title:'隔离',mode:'rag'}})).conversationId;
  await api('/ai/rag/chat',{token:otherToken,body:{...request('导数是什么？'),conversationId:otherConversation,documentIds:[documentId]},code:404});
  pass('用户与教材所有权隔离');
  const callback=async(headers,body)=>{
    const result=await fetch(apiBase.replace(/\/api$/,'')+'/internal/agent/v1/search',{method:'POST',headers:{'Content-Type':'application/json',...headers},body:JSON.stringify(body)});
    return result.json();
  };
  assert.equal((await callback({},{})).code,401);
  const scope=savedContext(initial.requestId).agent;
  assert.equal((await callback({Authorization:'Bearer '+callbackToken,'X-Tutor-Scope':'forged'}, {
    request_id:initial.requestId,attempt:1,user_id:history[0].userId ?? Number(sql("SELECT id FROM `user` WHERE username='agent_owner';")),
    conversation_id:conversationId,document_ids:[documentId],scope_version:scope.scopeVersion,query:'导数',limit:4,
  })).code,403);
  pass('内部工具服务认证与伪造范围拒绝');
  const retry=request('导数是什么？'); retry.documentIds=[documentId]; failNext=true;
  await api('/ai/rag/chat',{token,body:retry,code:600}); const afterFailure=events.length;
  await api('/ai/rag/chat',{token,body:retry,code:600}); assert.equal(events.length,afterFailure);
  await api('/ai/rag/chat',{token,body:{...retry,attempt:2}});
  assert.equal(savedContext(retry.requestId).agent.reservedCalls,8); pass('失败不自动重发，显式重试使用原请求与新尝试');
  const exhausted=request('导数是什么？'); exhausted.documentIds=[documentId];
  failNext=true; await api('/ai/rag/chat',{token,body:exhausted,code:600});
  failNext=true; await api('/ai/rag/chat',{token,body:{...exhausted,attempt:2},code:600});
  const beforeThird=events.length;
  await api('/ai/rag/chat',{token,body:{...exhausted,attempt:3},code:400}); assert.equal(events.length,beforeThird);
  pass('持久化预占每次4次、同问题最多8次调用额度');
  const pending=request('导数是什么？'); pending.documentIds=[documentId];
  holdReview=true; const observed=new Promise(resolve=>{reviewSeen=resolve;});
  const inflight=api('/ai/rag/chat',{token,body:pending});
  await Promise.race([observed,delay(15000).then(()=>assert.fail('Review not reached'))]);
  await api('/ai/rag/chat',{token,body:pending,code:409});
  releaseReview(); await inflight; pass('并发重复请求只执行一次');
  const changed=request('导数是什么？'); changed.documentIds=[documentId];
  holdReview=true; const sawChange=new Promise(resolve=>{reviewSeen=resolve;});
  const changing=api('/ai/rag/chat',{token,body:changed,code:409});
  await Promise.race([sawChange,delay(15000).then(()=>assert.fail('Review not reached'))]);
  await api(`/documents/${documentId}/reprocess`,{token,method:'POST'}); releaseReview(); await changing;
  assert.equal((await api(`/conversations/${conversationId}/messages`,{token})).filter(item=>item.ragRequestId===changed.requestId).length,1);
  pass('模型返回期间教材重处理，提交前拒绝旧引用');
  if (options.browser) {
    writeFileSync(path.join(output,'browser-fixture.json'),JSON.stringify({apiBase,username:'agent_owner',password,documentId}));
    const browser=launch(process.execPath,[path.join(root,'frontend/tests/python-agent.browser.cjs'),output],root,{},'browser');
    browserInvocation=await new Promise(resolve=>{browser.once('exit',resolve); browser.once('error',()=>resolve(-1));});
    assert.equal(browserInvocation,0,'Browser verification failed; inspect browser log'); pass('网页经真实 Java/Python 链路回答、引用与刷新恢复');
  }
  agent.kill(); await Promise.race([once(agent,'exit'),delay(5000)]);
  const unavailable=request('导数是什么？'); unavailable.documentIds=[documentId];
  const beforeOutage=events.length;
  await api('/ai/rag/chat',{token,body:unavailable,code:600}); assert.equal(events.length,beforeOutage);
  await api('/ai/rag/chat',{token,body:initial}); pass('Python停止后记录失败，无静默回退；已有回答仍可恢复');
} catch(error) {
  failure={name:error.name,message:error.message}; console.error(error.stack);
} finally {
  releaseReview?.();
  for (const child of children.reverse()) if (child.exitCode===null && !child.startError) {
    child.kill(); await Promise.race([once(child,'exit'),delay(5000)]);
    if (child.exitCode===null && child.signalCode===null) child.kill('SIGKILL');
  }
  if (model.listening) { model.closeAllConnections(); await new Promise(resolve=>model.close(resolve)); }
  let databaseDropped=false;
  if (created) try { sql(`DROP DATABASE ${schema};`,false); databaseDropped=true; } catch { failure??={message:'Temporary database cleanup failed'}; }
  for (const log of logs) log.end();
  writeFileSync(path.join(output,'result.json'),JSON.stringify({passed:!failure,checks,external_model_calls:0,
    scripted_model_calls:events.length,events,browserInvocation,databaseDropped,
    processesStopped:children.every(child=>child.exitCode!==null || child.signalCode!==null || child.startError),failure},null,2));
  process.exitCode=failure?1:0;
}
