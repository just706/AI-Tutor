// 接口验收默认使用临时 MySQL 库和本地模型替身；teacher-model=real 仅评测自编教师问题。
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
  'hold-for-browser': { type: 'boolean', default: false },
  'teacher-model': { type: 'string', default: 'stub' },
  'flow-evaluation': { type: 'string', default: 'off' },
  'flow-replay-file': { type: 'string' },
} });
assert(options['output-dir'], '必须指定 --output-dir，日志和临时上传文件应保存在源码仓库外。');
const projectRoot = path.resolve(options['project-root']);
const outputDir = path.resolve(options['output-dir']);
const outputRelative = path.relative(projectRoot, outputDir);
assert(outputRelative.startsWith('..' + path.sep) || path.isAbsolute(outputRelative), '输出目录必须在源码仓库外。');
assert(!existsSync(path.join(outputDir,'result.json')), '输出目录已有验收结果，请使用新目录。');
const backendRoot = path.join(projectRoot, 'backend');
const jarPath = path.join(backendRoot, 'target/ai-tutor-backend-0.0.1-SNAPSHOT.jar');
assert(existsSync(jarPath), '请先执行后端 verify，生成可运行 jar。');
mkdirSync(outputDir, { recursive: true });

// 读取运行配置；凭据不进入命令行或验收报告。
const config = {};
if (options['env-file']) {
  for (const line of readFileSync(options['env-file'], 'utf8').split(/\r?\n/)) {
    const match = line.match(/^\s*([A-Za-z_][A-Za-z0-9_]*)\s*=(.*)$/);
    if (match) config[match[1]] = match[2].trim().replace(/^(['"])(.*)\1$/, '$2');
  }
}
const setting = (name, fallback) => process.env[name] ?? config[name] ?? fallback;
assert(['stub','real'].includes(options['teacher-model']));
assert(['off','stub','real','replay'].includes(options['flow-evaluation']));
const realTeacher = options['teacher-model'] === 'real';
const realFlow = options['flow-evaluation'] === 'real';
const replayFlow = options['flow-evaluation'] === 'replay';
assert(!(realTeacher && realFlow), '两类真实模型验收请分别运行，以明确数据和调用预算。');
assert(!(realTeacher && replayFlow), '本地回放不能同时启用真实教师模型。');
assert.equal(Boolean(options['flow-replay-file']), replayFlow, 'replay 模式必须提供 --flow-replay-file，其他模式不得提供。');
const flowSuitePath = path.join(backendRoot, 'evaluation/stage2-flow-cases.json');
const flowSuite = options['flow-evaluation'] === 'off' ? null : JSON.parse(readFileSync(flowSuitePath, 'utf8'));
if (flowSuite) {
  assert.equal(flowSuite.cases.length, 5);
  assert.equal(new Set(flowSuite.cases.map(item => item.id)).size, 5);
  assert(flowSuite.cases.every(item => ['correct','remediate','insufficient'].includes(item.path)));
}
const replayBytes = replayFlow ? readFileSync(options['flow-replay-file']) : null;
const replaySha256 = replayBytes ? createHash('sha256').update(replayBytes).digest('hex') : undefined;
const replayRequests = replayBytes ? JSON.parse(replayBytes.toString('utf8')) : [];
function lessonSourceText(source) {
  if (typeof source === 'string') return source; // 仅为历史响应的本地回放保留旧输入格式。
  assert(Array.isArray(source) && source.length>0 && source.length<=64);
  source.forEach((unit,index)=> {
    assert.equal(unit.id,'E'+(index+1));
    assert(typeof unit.text==='string' && unit.text.trim());
  });
  return source.map(unit=>unit.text).join('');
}
if (replayFlow) {
  assert(Array.isArray(replayRequests) && replayRequests.length === flowSuite.cases.length, '回放必须包含完整固定题集。');
  assert.equal(new Set(replayRequests.map(item => item.caseId)).size, flowSuite.cases.length);
  for (const item of flowSuite.cases) {
    const saved = replayRequests.find(request => request.caseId === item.id);
    assert(saved && typeof saved.answer === 'string' && saved.answer.trim(), '回放案例缺少原始响应。');
    assert.equal(lessonSourceText(JSON.parse(saved.messages.at(-1).content).source), item.source, '回放资料必须与冻结题集一致。');
  }
}
const teacherModel = setting('DEEPSEEK_MODEL_NAME','deepseek-v4-flash');
const teacherKey = setting('DEEPSEEK_API_KEY','');
const teacherProvider = new URL(setting('DEEPSEEK_BASE_URL','https://api.deepseek.com'));
if(realTeacher || realFlow) {
  assert(teacherKey, '真实模型密钥未配置');
  assert.equal(teacherProvider.origin,'https://api.deepseek.com');
  assert(!teacherProvider.username && !teacherProvider.password && !teacherProvider.search && !teacherProvider.hash);
}
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

function readSavedFlow(sessionId) {
  assert(Number.isSafeInteger(sessionId) && sessionId > 0);
  // MySQL batch 输出会二次转义 JSON 中的反斜杠；HEX 保留原始 UTF-8 字节。
  const hex = sql(`SELECT HEX(flow_state) FROM learning_session WHERE id=${sessionId};`);
  return JSON.parse(Buffer.from(hex, 'hex').toString('utf8'));
}

const checks = [];
const pass = name => { checks.push(name); console.log(`PASS ${name}`); };
const modelRequests = [];
const modelErrors = [];
const teacherRequests = [];
const flowRequests = [];
const lessonInputs = [];
const flowCaseResults = [];
let realTeacherCalls = 0;
let realFlowCalls = 0;
let activeFlowCase;
let malformedLessonNext = false;
let unsupportedLessonNext = false;
let invalidOptionFeedbackNext;
let invalidQuestionContextNext;
let routingLessonNext = false;
let failNextRag = false;
let modelGate;
const browserFailureControl = path.join(outputDir, 'fail-next-rag');
const textbook = 'ArrayList 基于动态数组，实现 List 接口，随机访问速度快，适合经常通过索引访问元素。LinkedList 基于双向链表，也实现 List 接口。ArrayList 和 LinkedList 都是 List 的常见实现类。';
const mathTextbook = '导数表示函数的局部变化率。导数的用途是研究函数的变化趋势。积分表示累积量。积分的用途是计算曲线下方的面积。';
const physicsTextbook = '牛顿第二定律描述力和加速度的关系。牛顿第二定律的适用条件包括惯性参考系。平均速度表示总位移与总时间的比值。瞬时速度描述某一时刻的运动状态。';
const routingTextbook = '亮纹代表红色。暗纹代表蓝色。无纹代表绿色。';
const modelServer = createServer(async (request, response) => {
  try {
    // TCP 分段可能切开中文 UTF-8 字符，先合并字节再解码，避免固定资料被替换字符破坏。
    const chunks = [];
    for await (const chunk of request) chunks.push(chunk);
    const payload = JSON.parse(Buffer.concat(chunks).toString('utf8'));
    const prompt = payload.messages.map(message => message.content).join('\n');
    modelRequests.push(prompt);
    const isRag = !prompt.includes('个人知识图谱节点抽取助手') && !prompt.includes('个人知识图谱关系抽取助手');
    if (isRag && modelGate) {
      const gate = modelGate; modelGate = undefined; gate.started(); await gate.release;
    }
    if (isRag && (failNextRag || existsSync(browserFailureControl))) {
      failNextRag = false;
      if (existsSync(browserFailureControl)) unlinkSync(browserFailureControl);
      response.writeHead(503, { 'Content-Type': 'application/json' });
      response.end(JSON.stringify({ error: { message: 'synthetic model outage' } })); return;
    }
    const indexes = [...prompt.matchAll(/\[chunkIndex=(\d+)\]/g)].map(match => Number(match[1]));
    const evidence = [indexes[0] ?? 0];
    let content;
    if (prompt.includes('循序学习备课助手')) {
      assert.equal(payload.response_format?.type, 'json_object');
      const data = JSON.parse(payload.messages.at(-1).content);
      assert(data.topic && Array.isArray(data.source));
      const sourceText=lessonSourceText(data.source);
      lessonInputs.push(data);
      const evidenceIds=data.source.slice(0,2).map(unit=>unit.id);
      const optionTexts=['与所给定义一致','与所给定义不一致','忽略定义中的条件'];
      const question = (label, correctOption) => ({prompt:`${data.topic}：${label}`,
        options:optionTexts,correctOption,explanation:'请对照定义核对当前判断。',
        wrongOptionFeedback:['A','B','C'].filter(option=>option!==correctOption).map(option=>({option,focus:optionTexts[option.charCodeAt(0)-65],
          explanation:`仅针对${label}中“${optionTexts[option.charCodeAt(0)-65]}”的说法补充说明，请依据下面原文重新判断。`,evidenceIds}))});
      content = JSON.stringify({schemaVersion:3,supported:true,explanation:`先理解本次资料：${sourceText}`,
        remedialExplanation:`先回顾定义，再比较容易混淆的含义：${sourceText}`,
        questions:[question('诊断题','A'),question('检查题一','B'),question('检查题二','A'),
        question('应用题一','A'),question('应用题二','B')]});
      if (routingLessonNext) {
        routingLessonNext=false;
        const ref=text=>{
          // 教材首段含文件名和片段标记；模拟模型仅选择包含该句的已有编号。
          const units=data.source.filter(unit=>unit.text.includes(text));
          assert.equal(units.length,1,'选题测试资料必须有唯一对应原文'); return units[0].id;
        };
        const red=ref('亮纹代表红色。'),blue=ref('暗纹代表蓝色。'),green=ref('无纹代表绿色。');
        const feedback=(option,focus,evidenceIds)=>({option,focus,explanation:'请对照原文中的纹路与颜色对应关系。',evidenceIds});
        const q=(prompt,color,id,focus)=>({prompt,options:[color,'无法确定'],correctOption:'A',
          explanation:`根据给定规则，答案是${color}。`,wrongOptionFeedback:[feedback('B',focus,[id])]});
        content=JSON.stringify({schemaVersion:3,supported:true,explanation:routingTextbook,remedialExplanation:routingTextbook,
          questions:[{prompt:'亮纹代表什么颜色？',options:['红色','蓝色','绿色'],correctOption:'A',explanation:'亮纹代表红色。',
            wrongOptionFeedback:[feedback('B','亮纹与暗纹的颜色对应',[red,blue]),feedback('C','亮纹与无纹的颜色对应',[red,green])]},
          q('暗纹代表什么颜色？','蓝色',blue,'亮纹与暗纹的颜色对应'),q('无纹代表什么颜色？','绿色',green,'亮纹与无纹的颜色对应'),
          q('暗纹卡片应涂什么颜色？','蓝色',blue,'亮纹与暗纹的颜色对应'),q('无纹卡片应涂什么颜色？','绿色',green,'亮纹与无纹的颜色对应')]});
      }
      if (invalidOptionFeedbackNext) {
        const prepared=JSON.parse(content);
        const diagnosis=prepared.questions[0];
        if(invalidOptionFeedbackNext==='missing') diagnosis.wrongOptionFeedback.pop();
        if(invalidOptionFeedbackNext==='quote') diagnosis.wrongOptionFeedback[0].sourceQuote='原资料中完全没有这一句话。';
        if(invalidOptionFeedbackNext==='unknown-evidence') diagnosis.wrongOptionFeedback[0].evidenceIds=['E999'];
        if(invalidOptionFeedbackNext==='duplicate-evidence') diagnosis.wrongOptionFeedback[0].evidenceIds=['E1','E1'];
        if(invalidOptionFeedbackNext==='empty-evidence') diagnosis.wrongOptionFeedback[0].evidenceIds=[];
        if(invalidOptionFeedbackNext==='correct') diagnosis.wrongOptionFeedback[0].option=diagnosis.correctOption;
        if(invalidOptionFeedbackNext==='misnested') {
          prepared.lesson={questions:prepared.questions}; delete prepared.questions;
        }
        content=JSON.stringify(prepared);
        if(invalidOptionFeedbackNext==='syntax') content=content.slice(0,-1);
        if(invalidOptionFeedbackNext==='trailing') content+='{}';
        invalidOptionFeedbackNext=undefined;
      }
      if (malformedLessonNext) { malformedLessonNext=false; content='{"supported":true,"lesson":{}}'; }
      if (invalidQuestionContextNext) {
        const prepared=JSON.parse(content),q=prepared.questions[2];
        if(invalidQuestionContextNext==='prompt') q.prompt='上述序列执行后，结果是什么？';
        if(invalidQuestionContextNext==='option') q.options[1]='与上一题答案相同';
        if(invalidQuestionContextNext==='explanation') q.explanation='条件见第1题，沿用该题结论。';
        if(invalidQuestionContextNext==='feedback') q.wrongOptionFeedback[0].explanation='Use the previous question to understand this option.';
        content=JSON.stringify(prepared); invalidQuestionContextNext=undefined;
      }
      if (unsupportedLessonNext) { unsupportedLessonNext=false; content='{"supported":false}'; }
      if (activeFlowCase) {
        assert.equal(sourceText, activeFlowCase.source, '只允许发送固定题集内的自编资料');
        assert.equal(data.topic, activeFlowCase.resolvedTopic);
        const record = {caseId:activeFlowCase.id, messages:payload.messages, requestedModel:payload.model,
          mode:options['flow-evaluation'], startedAt:new Date().toISOString()};
        flowRequests.push(record);
        try {
          if (realFlow) {
            assert(realFlowCalls < 5, '此固定题集单次最多5次真实调用，不自动重试');
            realFlowCalls++;
            const upstream = await fetch(teacherProvider.href.replace(/\/$/,'')+'/chat/completions', {
              method:'POST', headers:{'Content-Type':'application/json', Authorization:'Bearer '+teacherKey},
              body:JSON.stringify(payload), signal:AbortSignal.timeout(60000), redirect:'error'
            });
            record.httpStatus = upstream.status;
            assert(upstream.ok, '备课模型返回非成功状态');
            const result = await upstream.json();
            content = result.choices?.[0]?.message?.content;
            assert(typeof content === 'string' && content.trim(), '备课模型未返回内容');
            record.responseModel = result.model; record.usage = result.usage;
          } else if (replayFlow) {
            const saved = replayRequests.find(request => request.caseId === activeFlowCase.id);
            const savedData=JSON.parse(saved.messages.at(-1).content);
            assert.equal(savedData.topic,data.topic,'回放主题必须一致');
            assert.equal(lessonSourceText(savedData.source),sourceText,'回放资料必须一致');
            if(Array.isArray(savedData.source)) assert.deepEqual(savedData,data,'新格式回放还须保持证据编号一致');
            record.replayInputContractChanged=!Array.isArray(savedData.source);
            record.replaySystemPromptChanged=JSON.stringify(saved.messages.filter(m=>m.role==='system'))
              !==JSON.stringify(payload.messages.filter(m=>m.role==='system'));
            // 原样回放，包括不合法的 JSON；不修补模型响应，不产生外部调用。
            content = saved.answer;
            record.replaySha256 = replaySha256;
          } else if (!activeFlowCase.supported) content='{"supported":false}';
          record.answer = content;
        } catch (error) { record.errorType = error.name; throw error; }
        finally { record.finishedAt = new Date().toISOString(); }
      }
    } else if (prompt.includes('个人知识图谱节点抽取助手')) {
      assert.equal(payload.response_format?.type, 'json_object');
      content = JSON.stringify({ nodes: prompt.includes('ArrayList') ? [
        { name: 'ArrayList', description: '动态数组，索引访问快。', confidence: 95, evidenceChunkIndexes: evidence },
        { name: 'LinkedList', description: '双向链表。', confidence: 90, evidenceChunkIndexes: evidence },
      ] : [] });
    } else if (prompt.includes('个人知识图谱关系抽取助手')) {
      assert.equal(payload.response_format?.type, 'json_object');
      content = JSON.stringify({ edges: [{ sourceName: 'ArrayList', targetName: 'LinkedList',
        relationType: 'related', relationReason: '都是 List 的实现。', confidence: 90, evidenceChunkIndexes: evidence }] });
    } else if (payload.messages.some(message => message.role === 'system' && message.content.startsWith('{'))) {
      const context = JSON.parse(payload.messages.find(message => message.role === 'system' && message.content.startsWith('{')).content);
      assert(context.teachingStrategy && context.intent && Array.isArray(context.strategySource));
      const record = { context, messages: payload.messages, requestedModel:payload.model, startedAt:new Date().toISOString() };
      teacherRequests.push(record);
      const replies = {
        concept_first: '先理解导数表示局部变化率，再用一个问题检查理解。',
        example_first: '先看一个例子：观察某一时刻的速度，再理解局部变化率。',
        source_code_first: '先检查给出的实现流程；没有具体源码时不猜测版本细节。',
        prerequisite_first: '先回顾函数这一前置知识，再回到导数的变化率。',
        practice_first: '请先完成一道练习：用自己的话说明局部变化率，回答后再反馈。',
        debug_misconception: '先定位困难：你卡在函数取值，还是变化率的含义？',
        summary_review: '回顾核心要点：导数描述局部变化率，接下来检查理解。'
      };
      assert(replies[context.teachingStrategy]);
      content = replies[context.teachingStrategy];
      if(realTeacher) {
        assert(realTeacherCalls < 16, '教师真实模型验收单次最多16次调用'); realTeacherCalls++;
        const upstream = await fetch(teacherProvider.href.replace(/\/$/,'')+'/chat/completions', {
          method:'POST',headers:{'Content-Type':'application/json',Authorization:'Bearer '+teacherKey},
          body:JSON.stringify(payload),signal:AbortSignal.timeout(60000),redirect:'error'
        });
        assert(upstream.ok, '教师模型返回非成功状态：'+upstream.status);
        const result = await upstream.json();
        content = result.choices?.[0]?.message?.content;
        assert(typeof content === 'string' && content.trim(), '教师模型未返回回答');
        record.responseModel = result.model; record.usage = result.usage;
      }
      record.answer = content; record.finishedAt = new Date().toISOString();
    } else if (prompt.includes(mathTextbook) || prompt.includes(physicsTextbook)) {
      // 只验证证据和明确后的问题能到达模型，不冒充真实回答质量评测。
      content = `${prompt.includes(mathTextbook) ? mathTextbook : physicsTextbook}参考来源：片段1。`;
    } else {
      assert(prompt.includes('ArrayList'), '教材证据没有进入模型请求。');
      content = '经常按下标读取元素，应该选 ArrayList。它基于动态数组，随机访问较快。参考来源：片段1。';
    }
    response.writeHead(200, { 'Content-Type': 'application/json; charset=utf-8' });
    response.end(JSON.stringify({ choices: [{ message: { content } }], usage: { prompt_tokens: 20, completion_tokens: 10 } }));
  } catch (error) {
    modelErrors.push({caseId:activeFlowCase?.id, type:error.name, message:error.message});
    response.writeHead(400, { 'Content-Type': 'application/json' });
    response.end(JSON.stringify({ error: { message: 'verification model failure' } }));
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
    signal: AbortSignal.timeout(realTeacher || realFlow ? 90000 : 30000),
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
    'stage19-chat-rag-sources.sql', 'stage20-rag-retry.sql', 'stage21-tutor-flow.sql',
  ];
  for (const migration of migrations) sql(readFileSync(path.join(backendRoot, 'src/main/resources/db', migration), 'utf8'));
  assert.equal(Number(sql('SELECT COUNT(*) FROM information_schema.tables WHERE table_schema = DATABASE();')), 20);
  for (const migration of migrations.slice(-6)) sql(readFileSync(path.join(backendRoot, 'src/main/resources/db', migration), 'utf8'));
  pass('19 个数据库脚本初始化；stage16 至 stage21 重复执行；20 张表');

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
      DEEPSEEK_MODEL_NAME: realTeacher || realFlow ? teacherModel : 'local-verification',
      DEEPSEEK_TIMEOUT_MS: realTeacher || realFlow ? '60000' : '5000' },
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
  await api('/knowledge-map/graph', { token, code: 400 });
  await api('/knowledge-map/graph?subject=%20%20', { token, code: 400 });
  const publicGraph = await api('/knowledge-map/graph?subject=Java', { token });
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

  const historyPath = `/conversations/${conversation.conversationId}/messages`;
  const restored = await api(historyPath, { token });
  const savedSources = restored.filter(message => message.role === 'assistant')[0].sources;
  assert.deepEqual(savedSources, answer.sources);
  assert.equal(savedSources[0].snippet, textbook);
  assert(restored.filter(message => message.role === 'user').every(message => message.sources.length === 0));
  assert.deepEqual(restored.filter(message => message.role === 'assistant')[1].sources, []);
  await api(historyPath, { token: otherToken, code: 404 });
  pass('历史回答恢复原引用顺序、文件名及原文；用户消息与拒答不伪造引用；他人不可读历史');

  const followConversation = await api('/conversations', { token, body: {
    title: '连续追问验收', mode: 'rag', documentIds: [document.documentId] } });
  const follow = question => api('/ai/rag/chat', { token, body: {
    conversationId: followConversation.conversationId, question } });
  const comparison = await follow('ArrayList 和 LinkedList 有什么区别？');
  assert(comparison.sources.length > 0, '教材没有“区别”字样也应召回两种结构的描述。');
  const pluralQuestion = '那两种集合有什么区别？';
  const plural = await follow(pluralQuestion);
  assert(plural.answer.startsWith('本次按“ArrayList 和 LinkedList有什么区别？”理解你的追问。'));
  assert.deepEqual(plural.sources, comparison.sources);
  assert(modelRequests.at(-1).endsWith('ArrayList 和 LinkedList有什么区别？'));
  const beforeAmbiguity = modelRequests.length;
  const ambiguous = await follow('那它有什么特点？');
  assert(ambiguous.answer.includes('请明确') && ambiguous.answer.includes('ArrayList') && ambiguous.answer.includes('LinkedList'));
  assert.deepEqual(ambiguous.sources, []);
  assert.equal(modelRequests.length, beforeAmbiguity);
  const clarified = await follow('我指的是 ArrayList');
  assert(clarified.sources.length > 0);
  const singularQuestion = '它的随机访问速度如何？';
  const singular = await follow(singularQuestion);
  assert(singular.answer.startsWith('本次按“ArrayList的随机访问速度如何？”理解你的追问。'));
  assert(singular.sources.length > 0);
  assert(modelRequests.at(-1).endsWith('ArrayList的随机访问速度如何？'));
  const followHistory = await api(`/conversations/${followConversation.conversationId}/messages`, { token });
  assert.equal(followHistory[2].messageContent, pluralQuestion);
  assert.equal(followHistory.at(-2).messageContent, singularQuestion);
  assert.equal(followHistory.at(-1).messageContent, singular.answer);
  assert.deepEqual(followHistory.at(-1).sources, singular.sources);
  pass('比较结构描述召回；复数追问、歧义澄清、明确对象后连续追问；原问题和引用恢复');

  const isolatedConversation = await api('/conversations', { token, body: {
    title: '无上下文验收', mode: 'rag', documentIds: [document.documentId] } });
  const beforeNoContext = modelRequests.length;
  const noContext = await api('/ai/rag/chat', { token, body: {
    conversationId: isolatedConversation.conversationId, question: '它是什么？' } });
  assert(noContext.answer.includes('请明确') && !noContext.answer.includes('ArrayList'));
  assert.deepEqual(noContext.sources, []);
  const unsupported = await follow('它的默认扩容倍率是多少？');
  assert(unsupported.answer.startsWith('本次按“ArrayList的默认扩容倍率是多少？”理解你的追问。'));
  assert(unsupported.answer.includes('没有找到足够依据'));
  assert.deepEqual(unsupported.sources, []);
  assert.equal(modelRequests.length, beforeNoContext);
  pass('会话间不借用追问对象；历史回答不能替代当前教材证据；澄清和无依据拒答不调用模型');

  for (const route of [`/documents/${document.documentId}`, `/documents/${document.documentId}/chunks`,
    `/personal-graph/extractions/${extraction.id}`, graphPath]) {
    await api(route, { token: otherToken, code: 404 });
  }
  await api(`/personal-graph/extractions/${extraction.id}/publish`, { token: otherToken, method: 'POST', code: 404 });
  await api('/ai/rag/chat', { token: otherToken, body: question, code: 404 });
  const otherConversation = await api('/conversations', { token: otherToken, body: { title: '权限验收', mode: 'rag' } });
  await api('/ai/rag/chat', { token: otherToken, body: { ...question, conversationId: otherConversation.conversationId }, code: 404 });
  pass('他人教材、片段、会话和图谱均不能读取或发布');

  // 仅在本次临时库构造异常旧记录，验证读取时不会信任快照中的归属。
  assert(Number.isSafeInteger(otherConversation.conversationId) && Number.isSafeInteger(document.documentId));
  sql(`INSERT INTO chat_history(user_id,conversation_id,role,message_content,rag_sources)
       SELECT user_id,id,'assistant','合成权限检查',JSON_ARRAY(JSON_OBJECT('documentId',${document.documentId},
         'fileName','不应返回的文件名','chunkIndex',0,'snippet','不应返回的原文'))
       FROM conversation WHERE id=${otherConversation.conversationId};`);
  const foreignHistory = await api(`/conversations/${otherConversation.conversationId}/messages`, { token: otherToken });
  assert.equal(foreignHistory[0].sources[0].available, false);
  assert.equal(foreignHistory[0].sources[0].snippet, null);
  assert.equal(foreignHistory[0].sources[0].fileName, null);
  pass('历史引用再次检查教材归属，异常快照不能泄露他人教材内容');

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
  assert(largeAnswer.sources[0].snippet.length > 180);
  const changedBindingHistory = await api(historyPath, { token });
  assert.deepEqual(changedBindingHistory.filter(message => message.role === 'assistant')[0].sources, savedSources);
  assert.deepEqual(changedBindingHistory.at(-1).sources, largeAnswer.sources);
  pass('超过 100 片段的教材仍可上传、重处理和问答；图谱提取保留限制');

  const reprocessed = await api(`/documents/${document.documentId}/reprocess`, { token, method: 'POST' });
  await waitForExtraction(reprocessed.personalGraphExtractionId, token);
  assert.equal((await api(graphPath, { token })).nodes.length, 0);
  sql(`UPDATE document_chunk SET chunk_text='重新处理后的不同原文' WHERE document_id=${document.documentId};`);
  assert.deepEqual((await api(historyPath, { token })).filter(message => message.role === 'assistant')[0].sources, savedSources);
  await api(`/documents/${document.documentId}`, { token, method: 'DELETE' });
  await api(`/documents/${largeDocument.documentId}`, { token, method: 'DELETE' });
  await api('/ai/rag/chat', { token, body: { conversationId: createdBound.conversationId, question: question.question }, code: 404 });
  assert.equal((await api('/documents', { token })).length, 0);
  assert.equal((await api('/personal-graph', { token })).nodes.length, 0);
  pass('重处理清理旧图谱；删除教材清理片段和图谱');
  const deletedSources = (await api(historyPath, { token })).filter(message => message.role === 'assistant')[0].sources;
  assert.equal(deletedSources.length, savedSources.length);
  assert(deletedSources.every(source => source.available === false && source.fileName === null && source.snippet === null));
  pass('重处理不改写旧引用；切换教材不改写旧引用；删除后返回失效引用且隐藏原文');

  const mathDocument = await upload(mathTextbook, 'calculus.txt', token);
  const physicsDocument = await upload(physicsTextbook, 'mechanics.txt', token);
  await waitForExtraction(mathDocument.personalGraphExtractionId, token);
  await waitForExtraction(physicsDocument.personalGraphExtractionId, token);
  const mathConversation = await api('/conversations', { token, body: {
    title: '数学教材会话', mode: 'rag', documentIds: [mathDocument.documentId] } });
  const physicsConversation = await api('/conversations', { token, body: {
    title: '物理教材会话', mode: 'rag', documentIds: [physicsDocument.documentId] } });
  const askSubject = (conversationId, question) => api('/ai/rag/chat', { token, body: { conversationId, question } });
  assert((await askSubject(mathConversation.conversationId, '什么是导数？')).sources.length > 0);
  const mathFollow = await askSubject(mathConversation.conversationId, '它有什么用途？');
  assert(mathFollow.answer.startsWith('本次按“导数有什么用途？”理解你的追问。'));
  assert(mathFollow.sources.every(source => source.documentId === mathDocument.documentId));
  assert(mathFollow.sources.length > 0);
  assert(modelRequests.at(-1).endsWith('导数有什么用途？'));
  assert((await askSubject(mathConversation.conversationId, '导数和积分有什么区别？')).sources.length > 0);
  const mathPair = await askSubject(mathConversation.conversationId, '那两个概念有什么区别？');
  assert(mathPair.sources.length > 0);
  assert(mathPair.answer.startsWith('本次按“导数 和 积分有什么区别？”理解你的追问。'));
  const beforeMathAmbiguity = modelRequests.length;
  assert((await askSubject(mathConversation.conversationId, '它有什么用途？')).answer.includes('请明确'));
  assert.equal(modelRequests.length, beforeMathAmbiguity);
  const mathHistory = await api(`/conversations/${mathConversation.conversationId}/messages`, { token });
  assert.equal(mathHistory[2].messageContent, '它有什么用途？');
  assert.deepEqual(mathHistory[3].sources, mathFollow.sources);
  pass('数学教材：中文概念追问、两概念比较、歧义澄清及原问题和引用恢复');

  const beforePhysicsContext = modelRequests.length;
  const noPhysicsContext = await askSubject(physicsConversation.conversationId, '它是什么？');
  assert(noPhysicsContext.answer.includes('请明确') && !noPhysicsContext.answer.includes('导数'));
  assert.equal(modelRequests.length, beforePhysicsContext);
  assert((await askSubject(physicsConversation.conversationId, '牛顿第二定律是什么？')).sources.length > 0);
  const physicsFollow = await askSubject(physicsConversation.conversationId, '它的适用条件是什么？');
  assert(physicsFollow.answer.startsWith('本次按“牛顿第二定律的适用条件是什么？”理解你的追问。'));
  assert(physicsFollow.sources.length > 0);
  assert(physicsFollow.sources.every(source => source.documentId === physicsDocument.documentId));
  assert(modelRequests.at(-1).endsWith('牛顿第二定律的适用条件是什么？'));
  const beforePhysicsRefusal = modelRequests.length;
  const physicsRefusal = await askSubject(physicsConversation.conversationId, '它的发现年份是多少？');
  assert(physicsRefusal.answer.includes('没有找到足够依据'));
  assert.deepEqual(physicsRefusal.sources, []);
  assert.equal(modelRequests.length, beforePhysicsRefusal);
  await api(`/conversations/${mathConversation.conversationId}/documents`, { token, method: 'PUT', body: { documentIds: [physicsDocument.documentId] } });
  await askSubject(mathConversation.conversationId, '什么是牛顿第二定律？');
  const changedSubject = await askSubject(mathConversation.conversationId, '它的适用条件是什么？');
  assert(changedSubject.sources.length > 0);
  assert(changedSubject.sources.every(source => source.documentId === physicsDocument.documentId));
  assert(changedSubject.answer.startsWith('本次按“牛顿第二定律的适用条件是什么？”理解你的追问。'));
  await api(`/conversations/${mathConversation.conversationId}/documents`, { token, method: 'PUT', body: { documentIds: [mathDocument.documentId] } });
  pass('物理教材：中文长名称与属性分别匹配、无依据拒答、跨会话隔离和同会话切换学科');


  const retryConversation = await api('/conversations', { token, body: {
    title: '重试验收', mode: 'rag', documentIds: [mathDocument.documentId] } });
  const retryId = retryConversation.conversationId;
  const retryHistory = () => api(`/conversations/${retryId}/messages`, { token });
  await askSubject(retryId, '什么是导数？');
  const retryQuestion = { conversationId: retryId, question: '它有什么用途？', requestId: randomUUID(), attempt: 1 };
  failNextRag = true;
  await api('/ai/rag/chat', { token, body: retryQuestion, code: 600 });
  let failedHistory = await retryHistory();
  assert.equal(failedHistory.at(-1).ragStatus, 'failed');
  assert.equal(failedHistory.at(-1).ragAttempt, 1);
  assert.equal(failedHistory.at(-1).ragRequestId, retryQuestion.requestId);
  const afterFailure = modelRequests.length;
  await api('/ai/rag/chat', { token, body: retryQuestion, code: 600 });
  assert.equal(modelRequests.length, afterFailure);
  assert.equal((await retryHistory()).length, failedHistory.length);
  pass('模型失败后问题和状态持久化；重复失败提交不重复记录、不再次调用模型');

  await api(`/conversations/${retryId}/documents`, { token, method: 'PUT', body: { documentIds: [physicsDocument.documentId] } });
  await askSubject(retryId, '牛顿第二定律是什么？');
  const recovered = await api('/ai/rag/chat', { token, body: { ...retryQuestion, attempt: 2 } });
  assert(recovered.answer.startsWith('本次按“导数有什么用途？”'));
  assert(recovered.sources.length > 0 && recovered.sources.every(source => source.documentId === mathDocument.documentId));
  assert(modelRequests.at(-1).endsWith('导数有什么用途？'));
  const recoveredHistory = await retryHistory();
  const pair = recoveredHistory.filter(message => message.ragRequestId === retryQuestion.requestId);
  assert.equal(pair.length, 2); assert.equal(pair[0].ragStatus, 'completed'); assert.equal(pair[0].ragAttempt, 2);
  assert.equal(recoveredHistory[3].messageContent, recovered.answer);
  assert.equal(recoveredHistory[4].messageContent, '牛顿第二定律是什么？');
  const afterRecovery = modelRequests.length;
  const replay = await api('/ai/rag/chat', { token, body: retryQuestion });
  assert.deepEqual(replay, recovered); assert.equal(modelRequests.length, afterRecovery);
  await api('/ai/rag/chat', { token, body: { ...retryQuestion, question: '积分是什么？' }, code: 409 });
  await api('/ai/rag/chat', { token, body: { ...retryQuestion, documentIds: [physicsDocument.documentId] }, code: 409 });
  await api('/ai/rag/chat', { token: otherToken, body: retryQuestion, code: 404 });
  await api('/ai/rag/chat', { token, body: { ...retryQuestion, requestId: 'invalid-key' }, code: 400 });
  pass('重试固定原教材及追问对象；迟到回答归位；成功重放、参数冲突与用户隔离');

  const concurrentQuestion = { conversationId: retryId, question: '牛顿第二定律是什么？', requestId: randomUUID(), attempt: 1 };
  let release;
  const started = new Promise(resolve => { modelGate = { started: resolve, release: new Promise(r => { release = r; }) }; });
  const inFlight = api('/ai/rag/chat', { token, body: concurrentQuestion });
  try {
    await Promise.race([started, delay(10000).then(() => { throw new Error('model gate was not reached'); })]);
    const processing = (await retryHistory()).find(message => message.ragRequestId === concurrentQuestion.requestId);
    assert.equal(processing.ragStatus, 'processing');
    await api('/ai/rag/chat', { token, body: concurrentQuestion, code: 409 });
    await api('/ai/rag/chat', { token, body: { ...concurrentQuestion, attempt: 2 }, code: 409 });
  } finally { release(); }
  await inFlight;
  const concurrentReplayCount = modelRequests.length;
  await Promise.all([api('/ai/rag/chat', { token, body: concurrentQuestion }), api('/ai/rag/chat', { token, body: concurrentQuestion })]);
  assert.equal(modelRequests.length, concurrentReplayCount);
  assert.equal((await retryHistory()).filter(message => message.ragRequestId === concurrentQuestion.requestId).length, 2);
  pass('模型调用期间问题已可刷新读取；并发重复提交不增加模型调用；每个请求只有一问一答');

  const interrupted = { conversationId: retryId, question: '牛顿第二定律是什么？', requestId: randomUUID(), attempt: 1 };
  failNextRag = true;
  await api('/ai/rag/chat', { token, body: interrupted, code: 600 });
  // 仅在临时库模拟进程中断后的过期记录。
  sql(`UPDATE chat_history SET rag_status='processing', rag_retry_after=DATE_SUB(NOW(), INTERVAL 1 SECOND)
       WHERE conversation_id=${retryId} AND rag_request_id='${interrupted.requestId}' AND role='user';`);
  assert.equal((await retryHistory()).find(message => message.ragRequestId === interrupted.requestId).ragStatus, 'interrupted');
  await api('/ai/rag/chat', { token, body: { ...interrupted, attempt: 2 } });
  assert.equal((await retryHistory()).filter(message => message.ragRequestId === interrupted.requestId).length, 2);
  pass('过期处理中状态可恢复，重试完成后仍只保存一问一答');

  const removedDocument = await upload(mathTextbook, 'retry-removed.txt', token);
  await waitForExtraction(removedDocument.personalGraphExtractionId, token);
  const removedQuestion = { conversationId: retryId, question: '什么是导数？', documentIds: [removedDocument.documentId], requestId: randomUUID(), attempt: 1 };
  failNextRag = true;
  await api('/ai/rag/chat', { token, body: removedQuestion, code: 600 });
  await api(`/documents/${removedDocument.documentId}`, { token, method: 'DELETE' });
  const beforeRemovedRetry = modelRequests.length;
  await api('/ai/rag/chat', { token, body: { ...removedQuestion, attempt: 2 }, code: 404 });
  assert.equal(modelRequests.length, beforeRemovedRetry);
  assert.equal((await retryHistory()).find(message => message.ragRequestId === removedQuestion.requestId).ragAttempt, 1);
  // NULL 旧消息保持可读，不冒充有状态的新请求。
  sql(`INSERT INTO chat_history(user_id, conversation_id, role, message_content)
       SELECT user_id, id, 'user', 'legacy question' FROM conversation WHERE id=${retryId};`);
  assert.equal((await retryHistory()).at(-1).ragStatus, null);
  pass('教材删除后重试拒绝越权或无依据调用；历史无状态消息兼容');

  // 合成数学知识点只存在于本次临时库，用于验证策略不依赖 Java 名单。
  sql("INSERT INTO knowledge_point(subject,name,sort_order) VALUES ('数学','函数',1),('数学','导数',2);");
  sql("INSERT INTO knowledge_map_dependency(prerequisite_point_id,dependent_point_id,relation_reason) SELECT a.id,b.id,'先理解函数再研究变化率' FROM knowledge_point a JOIN knowledge_point b ON b.subject=a.subject WHERE a.subject='数学' AND a.name='函数' AND b.name='导数';");
  const mathTree = await api('/knowledge-points/tree?subject=' + encodeURIComponent('数学'), { token });
  assert.equal(mathTree.length, 2);
  assert(mathTree.every(point => point.subject === '数学'));
  const allSubjects = await api('/knowledge-points/tree', { token });
  assert(allSubjects.some(point => point.subject === 'Java'));
  assert(allSubjects.some(point => point.subject === '数学'));
  const mathGraph = await api('/knowledge-map/graph?subject=' + encodeURIComponent('数学'), { token });
  assert.equal(mathGraph.subject, '数学');
  assert.deepEqual(mathGraph.nodes.map(point => point.id).sort(), mathTree.map(point => point.id).sort());
  assert.equal(mathGraph.edges.length, 1);
  const unknownGraph = await api('/knowledge-map/graph?subject=' + encodeURIComponent('天文学'), { token });
  assert.equal(unknownGraph.subject, '天文学');
  assert.equal(unknownGraph.nodes.length, 0);
  assert.equal((await api('/knowledge-points/tree?subject=' + encodeURIComponent('天文学'), { token })).length, 0);
  pass('学科目录、数学图谱及空学科校验；未知学科不回退 Java');
  const strategyCases = [
    ['解释导数的含义','concept_first','learn','核心概念'],
    ['举个例子解释导数','example_first','learn','例子'],
    ['导数在这段源码里如何计算','source_code_first','learn','源码'],
    ['补基础，导数的前置知识是什么','prerequisite_first','learn','前置知识'],
    ['给我一道导数练习','practice_first','practice','不要提前给出答案'],
    ['导数我还是不懂','debug_misconception','concept_difficulty','误区'],
    ['复盘导数','summary_review','analysis','核心要点']
  ];
  const strategyConversations = {};
  for (const [message,strategy,intent,instruction] of strategyCases) {
    const conversation = await api('/conversations', {token,body:{title:'策略验收 '+strategy,mode:'chat'}});
    const before = modelRequests.length;
    const response = await api('/tutor-agent/chat', {token,body:{conversationId:conversation.conversationId,message,
      teachingStrategy:'client_must_not_control_strategy'}});
    assert.equal(modelRequests.length,before+1);
    const sent = teacherRequests.at(-1);
    assert.equal(sent.context.teachingStrategy,strategy);
    assert.equal(sent.context.intent,intent);
    assert.equal(sent.context.topic,'导数');
    assert(sent.messages[0].content.includes(instruction));
    assert.equal(sent.messages.filter(item=>item.role==='user' && item.content===message).length,1);
    assert(sent.context.knowledgeMap.unmetPrerequisites.some(item=>item.knowledgePointName==='函数'));
    assert.equal(response.teachingStrategy,strategy);
    assert.equal(response.learningSession.teachingStrategy,strategy);
    assert.equal(response.intent,intent);
    assert.equal(response.learningSession.nextAction,sent.context.nextAction);
    assert.deepEqual(response.strategySource,sent.context.strategySource);
    const active = await api('/learning-sessions/active?conversationId='+conversation.conversationId,{token});
    assert.equal(active.teachingStrategy,strategy);
    assert.deepEqual(active.strategySource,response.strategySource);
    const history = await api('/conversations/'+conversation.conversationId+'/messages',{token});
    assert.equal(history.length,2); assert.equal(history[1].messageContent,response.answer);
    assert.equal(sql(`SELECT teaching_strategy FROM learning_session_step WHERE session_id=${active.id} ORDER BY id DESC LIMIT 1;`),strategy);
    assert.equal(Number(sql(`SELECT COUNT(*) FROM learning_session_step s JOIN chat_history h ON h.conversation_id=s.conversation_id AND h.role='assistant' AND h.message_content=s.agent_response WHERE s.session_id=${active.id};`)),1);
    strategyConversations[strategy] = {conversationId:conversation.conversationId,learningSessionId:active.id};
  }
  pass('七种教师策略及数学前置知识进入同一次模型请求；页面响应、学习步骤、聊天历史与刷新读取一致；客户端不能指定策略');

  const difficulty = strategyConversations.debug_misconception;
  const repeated = await api('/tutor-agent/chat',{token,body:{...difficulty,message:'我还是不懂'}});
  assert.equal(repeated.teachingStrategy,'prerequisite_first');
  assert.equal(teacherRequests.at(-1).context.topic,'导数');
  assert(teacherRequests.at(-1).context.nextAction.includes('函数'));
  const customConversation = await api('/conversations',{token,body:{title:'自定义主题策略',mode:'chat'}});
  const customResponse = await api('/tutor-agent/chat',{token,body:{conversationId:customConversation.conversationId,message:'举个例子解释星云投影'}});
  assert.equal(customResponse.teachingStrategy,'example_first');
  assert(teacherRequests.at(-1).context.topic.includes('星云投影'));
  assert.equal(teacherRequests.at(-1).context.knowledgeMap.knowledgePointId,null);
  assert.deepEqual(teacherRequests.at(-1).context.knowledgeMap.unmetPrerequisites,[]);
  pass('连续困难反馈沿用当前主题并先补前置知识；未收录的自定义主题仍可选择策略，不虚构知识地图');

  const beforeInvalid = modelRequests.length;
  await api('/tutor-agent/chat',{token,body:{conversationId:customConversation.conversationId,learningSessionId:difficulty.learningSessionId,message:'解释导数'},code:404});
  await api('/tutor-agent/chat',{token:otherToken,body:{...difficulty,message:'解释导数'},code:404});
  assert.equal(modelRequests.length,beforeInvalid);
  const failedConversation = await api('/conversations',{token,body:{title:'教师失败回滚',mode:'chat'}});
  failNextRag = true;
  await api('/tutor-agent/chat',{token,body:{conversationId:failedConversation.conversationId,message:'举个例子解释导数'},code:600});
  assert.equal(Number(sql(`SELECT COUNT(*) FROM learning_session WHERE conversation_id=${failedConversation.conversationId};`)),0);
  assert.equal(Number(sql(`SELECT COUNT(*) FROM learning_session_step WHERE conversation_id=${failedConversation.conversationId};`)),0);
  assert.deepEqual(await api('/conversations/'+failedConversation.conversationId+'/messages',{token}),[]);
  pass('无效学习会话与他人会话在模型调用前拒绝；教师生成失败时事务回滚，不保留成功策略或步骤');

  for(const route of ['/ai/chat','/ai/orchestrator/chat']) {
    const conversation = await api('/conversations',{token,body:{title:'普通聊天兼容',mode:'chat'}});
    const before = teacherRequests.length;
    const response = await api(route,{token,body:{conversationId:conversation.conversationId,message:'解释 ArrayList'}});
    assert(response.answer.includes('ArrayList')); assert.equal(teacherRequests.length,before);
    assert.equal((await api('/conversations/'+conversation.conversationId+'/messages',{token})).length,2);
  }
  writeFileSync(path.join(outputDir,'teacher-requests.json'),JSON.stringify(teacherRequests,null,2));
  pass('普通聊天与原编排接口保持兼容；教材问答继续使用独立证据流程');

  const flowConversations = {};
  const flowStart = async topic => {
    const created=await api('/conversations',{token,body:{title:'循序学习 '+topic,mode:'chat'}});
    const before=modelRequests.length;
    const result=await api('/tutor-agent/chat',{token,body:{conversationId:created.conversationId,message:topic,flowAction:'start'}});
    assert.equal(result.learningSession.flow.stage,'CLARIFYING'); assert.equal(modelRequests.length,before);
    assert(!JSON.stringify(result).includes('correctOption'));
    return {conversationId:created.conversationId,result};
  };
  const flowSend = async (turn,flowAction,message,code=200) => api('/tutor-agent/chat',{token,code,
    body:{conversationId:turn.conversationId,learningSessionId:turn.result.learningSession.id,
      flowVersion:turn.result.learningSession.flow.version,flowAction,message}});
  const flowStep = async (turn,action,message,stage) => {
    turn.result=await flowSend(turn,action,message);
    assert.equal(turn.result.learningSession.flow.stage,stage);
    const active=await api('/learning-sessions/active?conversationId='+turn.conversationId,{token});
    assert.deepEqual(active.flow,turn.result.learningSession.flow);
    assert(!JSON.stringify(active).includes('correctOption')); assert(!JSON.stringify(active).includes('"lesson"'));
  };
  const flowAnswer = async (turn,correct,stage) => {
    // 通过服务端保存的参考答案驱动状态；选项语义和补讲映射在下方独立比较原始备课。
    const lesson=readSavedFlow(turn.result.learningSession.id).lesson;
    const current=[lesson.diagnosis,...lesson.checks,...lesson.practices]
      .find(q=>q.prompt===turn.result.learningSession.flow.prompt);
    assert(current,'当前页面题目必须对应已保存的题目');
    assert.deepEqual(turn.result.learningSession.flow.options,current.options);
    await flowStep(turn,'answer',correct?current.correctOption:current.wrongOptionFeedback[0].option,stage);
  };
  for(const [topic,source] of [['ArrayList','Java：ArrayList 使用动态数组，按下标访问通常较快。'],
    ['HashMap','Java：HashMap 保存键值对，相同键再次放入会替换原值。'],['导数','数学：导数表示函数在某点的瞬时变化率，可用切线斜率理解。']]) {
    const turn=await flowStart(topic); const before=modelRequests.length;
    await flowStep(turn,'clarify',source,'DIAGNOSING'); assert.equal(modelRequests.length,before+1);
    await flowSend(turn,'answer','懂了',400); await flowSend(turn,'continue','跳过诊断',400);
    await flowAnswer(turn,true,'TEACHING'); await flowStep(turn,'continue','开始理解检查','CHECKING');
    await flowAnswer(turn,true,'PRACTICE'); await flowAnswer(turn,true,'FEEDBACK');
    assert(turn.result.learningSession.flow.passed); assert.equal(modelRequests.length,before+1);
    assert.equal((await api('/conversations/'+turn.conversationId+'/messages',{token})).length,12);
    flowConversations[topic]=turn.conversationId;
  }
  pass('两个 Java 知识点及数学走通确认、诊断、讲解、检查、练习和反馈；每步刷新一致，答案键不返回页面，不以“懂了”推进');

  const remediation=await flowStart('导数');
  await flowStep(remediation,'clarify','数学：导数表示瞬时变化率，需要先区分平均变化率。','DIAGNOSING');
  await flowAnswer(remediation,false,'TEACHING'); await flowStep(remediation,'continue','开始理解检查','CHECKING');
  const firstCheck=remediation.result.learningSession.flow.prompt;
  await flowAnswer(remediation,false,'RETEACHING'); await flowStep(remediation,'continue','再次检查','CHECKING');
  assert.notEqual(remediation.result.learningSession.flow.prompt,firstCheck);
  await flowAnswer(remediation,false,'REVIEW'); assert(!remediation.result.learningSession.flow.passed);
  await flowSend(remediation,'continue','直接通过',400);
  const savedFlow=readSavedFlow(remediation.result.learningSession.id);
  assert.equal(savedFlow.evidence.length,3); assert.equal(savedFlow.evidence[2].correct,false);
  pass('答错后补讲并改用不同检查题；题目用尽停在复习，保存错误证据且不标记通过');

  const pending=await flowStart('星云投影');
  await flowSend(pending,'clarify','就是这个',400);
  const beforeFailedHistory=await api('/conversations/'+pending.conversationId+'/messages',{token});
  for(const failureMode of ['outage','malformed','unsupported']) {
    if(failureMode==='outage') failNextRag=true;
    if(failureMode==='malformed') malformedLessonNext=true;
    if(failureMode==='unsupported') unsupportedLessonNext=true;
    await flowSend(pending,'clarify','测试领域：星云投影是把编号映射为颜色的自定义规则。',failureMode==='unsupported'?400:600);
    assert.deepEqual(await api('/conversations/'+pending.conversationId+'/messages',{token}),beforeFailedHistory);
    assert.equal((await api('/learning-sessions/active?conversationId='+pending.conversationId,{token})).flow.version,1);
  }
  pass('未定义术语先要求领域和资料；模型调用失败、格式错误或资料不足不跳步、不覆盖进度，原步骤仍可继续');

  await flowStep(pending,'clarify','测试领域：星云投影是把编号映射为颜色的自定义规则。','DIAGNOSING');
  const beforeConcurrent=modelRequests.length;
  const concurrent=await Promise.allSettled([flowSend(pending,'answer','A'),flowSend(pending,'answer','A')]);
  assert.equal(concurrent.filter(result=>result.status==='fulfilled').length,1);
  pending.result=concurrent.find(result=>result.status==='fulfilled').value;
  assert.equal(pending.result.learningSession.flow.stage,'TEACHING'); assert.equal(modelRequests.length,beforeConcurrent);
  assert.equal((await api('/conversations/'+pending.conversationId+'/messages',{token})).length,6);
  await api('/tutor-agent/chat',{token:otherToken,body:{conversationId:pending.conversationId,message:'A',flowAction:'answer',flowVersion:3},code:404});
  await flowStep(pending,'continue','开始理解检查','CHECKING');
  flowConversations['浏览器检查']=pending.conversationId;
  pass('同一版本并发提交只保存一轮；他人不能推进，切换主题使用各自会话进度');

  await api('/learning-sessions/'+remediation.result.learningSession.id+'/close',{token,body:{}});
  await flowSend(remediation,'answer','A',409);
  assert.equal(await api('/learning-sessions/active?conversationId='+remediation.conversationId,{token}),null);
  assert.equal(Number(sql(`SELECT COUNT(*) FROM learning_record WHERE user_id=(SELECT user_id FROM conversation WHERE id=${pending.conversationId});`)),0);
  pass('结束会话后旧请求不能重新开启流程；循序学习不写入掌握度或学习记录分数');

  // 教材循序学习复用真实检索、数据库和鉴权；备课仍由本地替身生成，不新增真实模型发送。
  const lessonDocument=await upload(mathTextbook,'guided-math.txt',token);
  await waitForExtraction(lessonDocument.personalGraphExtractionId,token);
  const makeTextbookFlow=async (ids=[lessonDocument.documentId]) => {
    const created=await api('/conversations',{token,body:{title:'依据教材学习',mode:'rag',documentIds:ids}});
    return {conversationId:created.conversationId};
  };
  const startTextbookFlow=async (turn,topic='导数的用途',code=200) => api('/tutor-agent/chat',{
    token,code,body:{conversationId:turn.conversationId,message:topic,flowAction:'start'}});
  const textbookTurn=await makeTextbookFlow();
  let lessonCalls=modelRequests.length;
  textbookTurn.result=await startTextbookFlow(textbookTurn);
  const lessonSources=textbookTurn.result.sources;
  assert.equal(modelRequests.length,lessonCalls+1);
  assert.equal(textbookTurn.result.learningSession.flow.stage,'DIAGNOSING');
  assert.equal(textbookTurn.result.learningSession.flow.topic,'导数的用途','不能将带属性的主题截成图谱概念名');
  assert.equal(textbookTurn.result.learningSession.flow.textbookBased,true);
  assert.equal(lessonSources.length,1);
  assert.equal(lessonSources[0].documentId,lessonDocument.documentId);
  assert.equal(lessonSources[0].snippet,mathTextbook);
  const lessonSource=lessonSourceText(lessonInputs.at(-1).source);
  assert(lessonSource.includes(mathTextbook)); assert(!lessonSource.includes(textbook));
  const lessonSnapshot=readSavedFlow(textbookTurn.result.learningSession.id);
  assert.deepEqual(lessonSnapshot.materials.documentIds,[lessonDocument.documentId]);
  assert.deepEqual(lessonSnapshot.materials.sources,lessonSources);
  const publicState=await api('/learning-sessions/active?conversationId='+textbookTurn.conversationId,{token});
  assert.deepEqual(publicState.flow,textbookTurn.result.learningSession.flow);
  assert(!JSON.stringify(publicState).includes('correctOption'));
  assert(!JSON.stringify(publicState).includes('materials'));
  assert(!JSON.stringify(publicState).includes(mathTextbook));
  await flowAnswer(textbookTurn,true,'TEACHING');
  await flowStep(textbookTurn,'continue','开始理解检查','CHECKING');
  await flowAnswer(textbookTurn,false,'RETEACHING');
  await flowStep(textbookTurn,'continue','再次检查','CHECKING');
  await flowAnswer(textbookTurn,true,'PRACTICE');
  await flowAnswer(textbookTurn,true,'FEEDBACK');
  const textbookHistory=await api('/conversations/'+textbookTurn.conversationId+'/messages',{token});
  assert.equal(textbookHistory.length,14);
  assert(textbookHistory.filter(m=>m.role==='assistant').every(m=>JSON.stringify(m.sources)===JSON.stringify(lessonSources)));
  assert.equal(modelRequests.length,lessonCalls+1,'只有备课调用模型，作答推进不再调用');
  pass('教材主题按所选片段备课；诊断、补讲、检查、练习和刷新始终保留同一引用；不暴露答案键');

  await api('/conversations/'+textbookTurn.conversationId+'/documents',{token,method:'PUT',body:{documentIds:[]},code:409});
  await api('/ai/rag/chat',{token,body:{conversationId:textbookTurn.conversationId,question:'什么是导数？',documentIds:[]},code:409});
  await api('/tutor-agent/chat',{token:otherToken,body:{conversationId:textbookTurn.conversationId,message:'导数',flowAction:'start'},code:404});
  await api('/conversations/'+textbookTurn.conversationId+'/messages',{token:otherToken,code:404});
  assert.equal(modelRequests.length,lessonCalls+1);
  await api('/learning-sessions/'+textbookTurn.result.learningSession.id+'/close',{token,body:{}});
  await api('/conversations/'+textbookTurn.conversationId+'/documents',{token,method:'PUT',body:{documentIds:[]}});
  pass('活动学习固定教材，选择接口与教材问答入口均无法绕过；他人无法读取或推进，结束后可更换');

  const emptyTurn=await makeTextbookFlow([]);
  lessonCalls=modelRequests.length;
  await startTextbookFlow(emptyTurn,'导数',400);
  const failedTextbookTurn=await makeTextbookFlow();
  await startTextbookFlow(failedTextbookTurn,'光合作用',400);
  assert.equal(modelRequests.length,lessonCalls,'无选择或未检索到依据时不能调用模型');
  for (const mode of ['outage','malformed','unsupported']) {
    if(mode==='outage') failNextRag=true;
    if(mode==='malformed') malformedLessonNext=true;
    if(mode==='unsupported') unsupportedLessonNext=true;
    await startTextbookFlow(failedTextbookTurn,'导数',mode==='unsupported'?400:600);
    assert.equal(await api('/learning-sessions/active?conversationId='+failedTextbookTurn.conversationId,{token}),null);
    assert.deepEqual(await api('/conversations/'+failedTextbookTurn.conversationId+'/messages',{token}),[]);
    assert.equal(Number(sql(`SELECT COUNT(*) FROM learning_session_step WHERE conversation_id=${failedTextbookTurn.conversationId};`)),0);
  }
  pass('空教材、无关主题、资料不足和备课失败均回滚，不生成空学习进度，不回退到普通聊天');

  const dependentTurn=await flowStart('导数');
  const dependentHistory=await api('/conversations/'+dependentTurn.conversationId+'/messages',{token});
  const dependentState=await api('/learning-sessions/active?conversationId='+dependentTurn.conversationId,{token});
  for(const mode of ['prompt','option','explanation','feedback']) {
    let calls=modelRequests.length;
    invalidQuestionContextNext=mode;
    await flowSend(dependentTurn,'clarify',mathTextbook,600);
    assert.equal(modelRequests.length,calls+1,'上下文校验失败不得自动追加模型请求');
    assert.deepEqual(await api('/conversations/'+dependentTurn.conversationId+'/messages',{token}),dependentHistory);
    assert.deepEqual(await api('/learning-sessions/active?conversationId='+dependentTurn.conversationId,{token}),dependentState);
    calls=modelRequests.length;
    invalidQuestionContextNext=mode;
    await startTextbookFlow(failedTextbookTurn,'导数',600);
    assert.equal(modelRequests.length,calls+1);
    assert.equal(await api('/learning-sessions/active?conversationId='+failedTextbookTurn.conversationId,{token}),null);
    assert.deepEqual(await api('/conversations/'+failedTextbookTurn.conversationId+'/messages',{token}),[]);
    assert.equal(Number(sql(`SELECT COUNT(*) FROM learning_session_step WHERE conversation_id=${failedTextbookTurn.conversationId};`)),0);
  }
  await flowStep(dependentTurn,'clarify',mathTextbook,'DIAGNOSING');
  failedTextbookTurn.result=await startTextbookFlow(failedTextbookTurn,'导数');
  assert.equal(failedTextbookTurn.result.learningSession.flow.stage,'DIAGNOSING');
  pass('题干、选项、解析或补讲依赖前题时两种入口均回滚，不追加模型调用；改为完整条件后可重试');

  const invalidatedTurn=await makeTextbookFlow();
  invalidatedTurn.result=await startTextbookFlow(invalidatedTurn,'导数');
  const invalidatedVersion=invalidatedTurn.result.learningSession.flow.version;
  const beforeInvalidated=modelRequests.length;
  // 仅在临时库模拟处理中的资料；不修改真实教材或生产状态。
  sql(`UPDATE document SET process_status='pending' WHERE id=${lessonDocument.documentId};`);
  await flowSend(invalidatedTurn,'answer','A',409);
  let invalidHistory=await api('/conversations/'+invalidatedTurn.conversationId+'/messages',{token});
  assert.equal(invalidHistory.length,2);
  assert.equal(invalidHistory[1].sources[0].available,false);
  assert.equal(invalidHistory[1].sources[0].snippet,null);
  sql(`UPDATE document SET process_status='completed' WHERE id=${lessonDocument.documentId};`);
  sql(`UPDATE conversation SET document_ids='[]' WHERE id=${invalidatedTurn.conversationId};`);
  await flowSend(invalidatedTurn,'answer','A',409);
  sql(`UPDATE conversation SET document_ids='[${lessonDocument.documentId}]' WHERE id=${invalidatedTurn.conversationId};`);
  await api(`/documents/${lessonDocument.documentId}`,{token,method:'DELETE'});
  await flowSend(invalidatedTurn,'answer','A',404);
  assert.equal((await api('/learning-sessions/active?conversationId='+invalidatedTurn.conversationId,{token})).flow.version,invalidatedVersion);
  invalidHistory=await api('/conversations/'+invalidatedTurn.conversationId+'/messages',{token});
  assert.equal(invalidHistory[1].sources[0].available,false);
  assert.equal(invalidHistory[1].sources[0].fileName,null);
  assert.equal(invalidHistory[1].sources[0].snippet,null);
  assert.equal(modelRequests.length,beforeInvalidated);
  await api('/learning-sessions/'+invalidatedTurn.result.learningSession.id+'/close',{token,body:{}});
  pass('教材处理中、绑定被改或教材删除均停止推进；失效引用隐藏原文，原版本保留且仍可结束学习');

  for(const [topic,source] of [['ArrayList',textbook],['星云投影','星云投影是把编号映射为颜色的自定义规则。偶数编号对应蓝色，奇数编号对应红色。']]) {
    const doc=await upload(source,'guided-domain.txt',token); await waitForExtraction(doc.personalGraphExtractionId,token);
    const turn=await makeTextbookFlow([doc.documentId]);
    turn.result=await startTextbookFlow(turn,topic);
    assert.equal(turn.result.learningSession.flow.topic,topic);
    assert.equal(turn.result.sources[0].snippet,source);
    assert.equal(turn.result.sources[0].documentId,doc.documentId);
    await flowAnswer(turn,true,'TEACHING');
  }
  pass('同一教材学习入口支持 Java 与自定义规则，不依赖某个学科的硬编码');

  // 两个不同错误选项必须映射到各自的补讲；比较当前响应、持久化历史及重新读取的公开阶段。
  for(const wrongIndex of [0,1]) {
    const turn=await flowStart('导数'); const calls=modelRequests.length;
    await flowStep(turn,'clarify',mathTextbook,'DIAGNOSING');
    const prepared=readSavedFlow(turn.result.learningSession.id).lesson;
    const current=prepared.checks[0];
    const support=current.wrongOptionFeedback[wrongIndex];
    const selected=support.option;
    const other=current.wrongOptionFeedback.find(item=>item.option!==selected);
    assert(!JSON.stringify(turn.result).includes(support.explanation));
    await flowAnswer(turn,true,'TEACHING');
    await flowStep(turn,'continue','开始检查','CHECKING');
    await flowStep(turn,'answer',selected,'RETEACHING');
    assert.equal(turn.result.teachingStrategy,'debug_misconception');
    assert.equal(turn.result.learningSession.teachingStrategy,'debug_misconception');
    assert(turn.result.answer.includes(support.explanation));
    assert(support.evidence.length>0);
    assert(support.evidence.every(unit=>turn.result.answer.includes(unit.text.trim())));
    assert(support.evidence.every(unit=>lessonInputs.at(-1).source.some(input=>input.id===unit.id && input.text===unit.text)));
    assert(turn.result.answer.includes(`${selected}. ${current.options[selected.charCodeAt(0)-65]}`));
    assert(!turn.result.answer.includes(other.explanation));
    assert(!JSON.stringify(turn.result).includes('wrongOptionFeedback'));
    const history=await api('/conversations/'+turn.conversationId+'/messages',{token});
    assert.equal(history.at(-1).messageContent,turn.result.answer);
    assert.equal(modelRequests.length,calls+1,'选项反馈在备课时保存，作答时不增加模型调用');
    const staleVersion=turn.result.learningSession.flow.version;
    await flowStep(turn,'continue','再次检查','CHECKING');
    assert.notEqual(turn.result.learningSession.flow.prompt,current.prompt);
    await api('/tutor-agent/chat',{token,code:409,body:{conversationId:turn.conversationId,message:selected,flowAction:'answer',flowVersion:staleVersion}});
  }
  pass('不同错误选项展示各自补讲与原文；未作答时隐藏反馈，刷新与历史一致，不增加模型调用');

  const selectByText=async (turn,text,stage)=>{
    const index=turn.result.learningSession.flow.options.indexOf(text);
    assert(index>=0,'选项重排后仍应保留原语义');
    await flowStep(turn,'answer',String.fromCharCode(65+index),stage);
  };
  const assertSelection=async (turn,prompt,reason)=>{
    const flow=turn.result.learningSession.flow;
    assert.equal(flow.prompt,prompt);
    const saved=readSavedFlow(turn.result.learningSession.id),trace=saved.selection.history.at(-1);
    assert.equal(saved.selection.policy,'distinction_then_evidence_v2');
    assert.equal(trace.question,prompt); assert.equal(trace.reason,reason);
    const lastWrong=saved.evidence.findLast(answer=>!answer.correct);
    assert.equal(trace.basedOnQuestion,lastWrong.question); assert.equal(trace.selectedOption,lastWrong.selectedOption);
    assert.equal(trace.sharedEvidenceIds.length>0,reason==='DISTINCTION_MATCH');
    assert(flow.selectionReason && turn.result.answer.includes(flow.selectionReason));
    const history=await api('/conversations/'+turn.conversationId+'/messages',{token});
    assert.equal(history.at(-1).messageContent,turn.result.answer);
    for(const hidden of ['sharedEvidenceIds','basedOnQuestion','wrongOptionFeedback','distinction_then_evidence_v2',
      '亮纹与暗纹的颜色对应','亮纹与无纹的颜色对应'])
      assert(!JSON.stringify(turn.result).includes(hidden));
    const active=await api('/learning-sessions/active?conversationId='+turn.conversationId,{token});
    assert.deepEqual(active.flow,flow);
    assert.deepEqual(readSavedFlow(turn.result.learningSession.id),saved,'重新读取不改变选题记录');
  };
  for(const [wrongColor,pattern] of [['蓝色','暗纹'],['绿色','无纹']]) {
    const turn=await flowStart('纹路颜色'),calls=modelRequests.length;
    routingLessonNext=true; await flowStep(turn,'clarify',routingTextbook,'DIAGNOSING');
    await selectByText(turn,wrongColor,'TEACHING'); await flowStep(turn,'continue','检查理解','CHECKING');
    await assertSelection(turn,`${pattern}代表什么颜色？`,'DISTINCTION_MATCH');
    await flowAnswer(turn,true,'PRACTICE');
    await assertSelection(turn,`${pattern}卡片应涂什么颜色？`,'DISTINCTION_MATCH');
    if(pattern==='无纹') {
      await flowAnswer(turn,false,'RETEACHING'); await flowStep(turn,'continue','继续检查','CHECKING');
      await assertSelection(turn,'暗纹代表什么颜色？','NO_DISTINCTION_MATCH');
      await flowAnswer(turn,true,'PRACTICE');
      await assertSelection(turn,'暗纹卡片应涂什么颜色？','NO_DISTINCTION_MATCH');
    }
    await flowAnswer(turn,true,'FEEDBACK');
    const saved=readSavedFlow(turn.result.learningSession.id);
    assert.equal(new Set(saved.evidence.map(answer=>answer.question)).size,saved.evidence.length);
    assert.equal(modelRequests.length,calls+1,'选题只读取已保存资料，不增加模型调用');
  }
  pass('不同错误选项按可观察区分点选择检查题和练习；原文只用于同类题排序，无匹配时回退，选题理由与历史和刷新一致');

  const routingDocument=await upload(routingTextbook,'guided-routing.txt',token);
  await waitForExtraction(routingDocument.personalGraphExtractionId,token);
  const routingTurn=await makeTextbookFlow([routingDocument.documentId]),routingCalls=modelRequests.length;
  routingLessonNext=true; routingTurn.result=await startTextbookFlow(routingTurn,'无纹');
  await selectByText(routingTurn,'绿色','TEACHING'); await flowStep(routingTurn,'continue','检查理解','CHECKING');
  await assertSelection(routingTurn,'无纹代表什么颜色？','DISTINCTION_MATCH');
  assert(routingTurn.result.learningSession.flow.textbookBased);
  assert.equal(routingTurn.result.sources[0].documentId,routingDocument.documentId);
  await flowAnswer(routingTurn,true,'PRACTICE');
  await assertSelection(routingTurn,'无纹卡片应涂什么颜色？','DISTINCTION_MATCH');
  await flowAnswer(routingTurn,true,'FEEDBACK'); assert.equal(modelRequests.length,routingCalls+1);
  pass('教材入口使用相同选题规则并保留教材引用，备课后不追加模型调用');

  const legacyTurn=await flowStart('旧版纹路颜色');
  routingLessonNext=true; await flowStep(legacyTurn,'clarify',routingTextbook,'DIAGNOSING');
  // 仅在临时库还原没有选题策略字段的旧版快照。
  sql(`UPDATE learning_session SET flow_state=JSON_REMOVE(flow_state,'$.selection') WHERE id=${legacyTurn.result.learningSession.id};`);
  await selectByText(legacyTurn,'绿色','TEACHING'); await flowStep(legacyTurn,'continue','检查理解','CHECKING');
  assert.equal(legacyTurn.result.learningSession.flow.prompt,'暗纹代表什么颜色？');
  assert.equal(legacyTurn.result.learningSession.flow.selectionReason,null);
  await flowAnswer(legacyTurn,false,'RETEACHING'); await flowStep(legacyTurn,'continue','再次检查','CHECKING');
  assert.equal(legacyTurn.result.learningSession.flow.prompt,'无纹代表什么颜色？');
  assert.equal(readSavedFlow(legacyTurn.result.learningSession.id).selection,null);
  pass('旧版保存进度继续使用原题目顺序，不在升级或刷新时重新选题');

  const feedbackFailure=await flowStart('导数');
  const feedbackHistory=await api('/conversations/'+feedbackFailure.conversationId+'/messages',{token});
  for(const mode of ['missing','quote','correct','misnested','unknown-evidence','duplicate-evidence','empty-evidence','syntax','trailing']) {
    invalidOptionFeedbackNext=mode;
    await flowSend(feedbackFailure,'clarify',mathTextbook,600);
    assert.deepEqual(await api('/conversations/'+feedbackFailure.conversationId+'/messages',{token}),feedbackHistory);
    assert.equal((await api('/learning-sessions/active?conversationId='+feedbackFailure.conversationId,{token})).flow.version,1);
  }
  await flowStep(feedbackFailure,'clarify',mathTextbook,'DIAGNOSING');
  const diagnosisFeedback=readSavedFlow(feedbackFailure.result.learningSession.id).lesson.diagnosis.wrongOptionFeedback[0];
  await flowStep(feedbackFailure,'answer',diagnosisFeedback.option,'TEACHING');
  assert(feedbackFailure.result.answer.includes(diagnosisFeedback.explanation));
  pass('反馈缺失、模型附加引文、证据编号无效、反馈对应正确选项、结构或语法错误时整轮回滚；修复后可重试');

  // 这些路径只验证 Java 按参考选项推进；事实正确性另按固定 rubric 审阅原始备课。
  if (flowSuite) {
    writeFileSync(path.join(outputDir,'flow-cases.json'), JSON.stringify(flowSuite,null,2));
    writeFileSync(path.join(outputDir,'flow-provenance.json'), JSON.stringify({version:flowSuite.version,
      suiteSha256:createHash('sha256').update(readFileSync(flowSuitePath)).digest('hex'),
      scriptSha256:createHash('sha256').update(readFileSync(fileURLToPath(import.meta.url))).digest('hex'),
      jarSha256:createHash('sha256').update(readFileSync(jarPath)).digest('hex'),
      requestedModel:realFlow ? teacherModel : (replayFlow ? 'saved-response-replay' : 'local-verification'),
      mode:options['flow-evaluation'], replaySha256},null,2));
    for (const item of flowSuite.cases) {
      const record = {id:item.id, passed:false, expectedSupported:item.supported, states:[]};
      flowCaseResults.push(record);
      let turn, initialHistory;
      try {
        turn = await flowStart(item.topic);
        record.conversationId = turn.conversationId;
        const before = modelRequests.length;
        initialHistory = await api('/conversations/'+turn.conversationId+'/messages',{token});
        record.states.push(turn.result.learningSession.flow);
        activeFlowCase = {...item, resolvedTopic:turn.result.learningSession.flow.topic};
        try {
          if (item.supported) await flowStep(turn,'clarify',item.source,'DIAGNOSING');
          else await flowSend(turn,'clarify',item.source,400);
        } finally { activeFlowCase = undefined; }
        assert.equal(modelRequests.length, before+1);
        const prepared = JSON.parse(flowRequests.find(request => request.caseId === item.id).answer);
        assert.equal(prepared.supported, item.supported);
        if (!item.supported) {
          assert.deepEqual(await api('/conversations/'+turn.conversationId+'/messages',{token}), initialHistory);
          const active = await api('/learning-sessions/active?conversationId='+turn.conversationId,{token});
          assert.deepEqual(active.flow, turn.result.learningSession.flow);
          record.states.push(active.flow);
        } else {
          const lesson = readSavedFlow(turn.result.learningSession.id).lesson;
          record.preparedLesson=lesson;
          const savedQuestions=[lesson.diagnosis,...lesson.checks,...lesson.practices];
          assert.equal(prepared.questions.length,savedQuestions.length);
          for(let i=0;i<savedQuestions.length;i++) {
            const raw=prepared.questions[i],saved=savedQuestions[i];
            assert.equal(saved.prompt,raw.prompt); assert.equal(saved.explanation,raw.explanation);
            assert.deepEqual([...saved.options].sort(),[...raw.options].sort());
            assert.equal(saved.options[saved.correctOption.charCodeAt(0)-65],raw.options[raw.correctOption.charCodeAt(0)-65]);
            assert.equal(saved.wrongOptionFeedback.length,raw.wrongOptionFeedback.length);
            for(const feedback of saved.wrongOptionFeedback) {
              const text=saved.options[feedback.option.charCodeAt(0)-65];
              const original=raw.wrongOptionFeedback.find(f=>raw.options[f.option.charCodeAt(0)-65]===text);
              assert(original,'重排不得丢失选项对应的补讲');
              assert.equal(feedback.focus,original.focus);
              assert.equal(feedback.explanation,original.explanation);
              assert.deepEqual(feedback.evidence.map(e=>e.id),original.evidenceIds);
              assert(feedback.evidence.every(e=>lessonInputs.at(-1).source.some(unit=>unit.id===e.id && unit.text===e.text)));
            }
          }
          record.optionOrder={original:prepared.questions.map(q=>q.correctOption),saved:savedQuestions.map(q=>q.correctOption),
            contentAndFeedbackPreserved:true};
          const step = async (action, message, stage) => {
            await flowStep(turn,action,message,stage); record.states.push(turn.result.learningSession.flow);
          };
          record.states.push(turn.result.learningSession.flow);
          await flowSend(turn,'answer','懂了',400);
          const chooseWrong = key => key === 'A' ? 'B' : 'A';
          await step('answer',item.path === 'remediate' ? chooseWrong(lesson.diagnosis.correctOption) : lesson.diagnosis.correctOption,'TEACHING');
          await step('continue','开始理解检查','CHECKING');
          const answerCurrent = async (correct,stage) => {
            await flowAnswer(turn,correct,stage); record.states.push(turn.result.learningSession.flow);
          };
          if (item.path === 'remediate') {
            const firstPrompt = turn.result.learningSession.flow.prompt;
            await answerCurrent(false,'RETEACHING');
            await step('continue','再次检查','CHECKING');
            assert.notEqual(turn.result.learningSession.flow.prompt,firstPrompt);
          }
          await answerCurrent(true,'PRACTICE');
          await answerCurrent(true,'FEEDBACK');
          assert(turn.result.learningSession.flow.passed);
          assert.equal(modelRequests.length,before+1, '判分和推进不得再次调用模型');
          record.history = await api('/conversations/'+turn.conversationId+'/messages',{token});
          const saved = readSavedFlow(turn.result.learningSession.id);
          assert.deepEqual(saved.lesson,lesson,'学习推进和重新读取不能再次改变选项顺序');
          record.evidence = saved.evidence;
          record.selection = saved.selection;
          assert.equal(new Set(saved.evidence.map(answer=>answer.question)).size,saved.evidence.length);
        }
        record.passed = true;
        pass('固定备课案例 '+item.id+'：'+item.topic+'，接口与进度验证通过，内容仍须独立审阅');
      } catch (error) {
        record.error = error.message;
        if (turn && initialHistory && record.states.length === 1) {
          const active = await api('/learning-sessions/active?conversationId='+turn.conversationId,{token});
          record.stateAfterFailure = active.flow;
          record.historyPreservedAfterFailure = JSON.stringify(await api('/conversations/'+turn.conversationId+'/messages',{token})) === JSON.stringify(initialHistory);
          record.statePreservedAfterFailure = JSON.stringify(active.flow) === JSON.stringify(record.states[0]);
        }
      }
    }
    assert(flowCaseResults.every(item => item.passed), '循序学习固定案例未全部通过，详见 flow-case-results.json');
  }

  if (options['hold-for-browser']) {
    // 独立账号只包含本次页面验收资料，便于核对教材选择、会话隔离和重新登录。
    const flowBrowserUsername = 'stage0_flow_browser';
    await api('/auth/register', { body: { username: flowBrowserUsername, password } });
    const flowBrowserLogin = await api('/auth/login', { body: { username: flowBrowserUsername, password } });
    const browserCases = [];
    const browserSuite = flowSuite || JSON.parse(readFileSync(flowSuitePath, 'utf8'));
    for (const [id, topic, fileName] of [['J01','ArrayList','browser-arraylist.txt'],
      ['J02','HashMap','browser-hashmap.txt'],['M01','导数','browser-math.txt']]) {
      const source = browserSuite.cases.find(item => item.id === id).source;
      const doc = await upload(source, fileName, flowBrowserLogin.token);
      await waitForExtraction(doc.personalGraphExtractionId, flowBrowserLogin.token);
      const convo = await api('/conversations', { token: flowBrowserLogin.token,
        body: { title: `${topic}教材页面验收`, mode: 'rag', documentIds: [] } });
      browserCases.push({ id, topic, fileName, documentId: doc.documentId, conversationId: convo.conversationId });
    }
    const browserDocument = await upload(textbook, 'browser-collections.txt', token);
    await waitForExtraction(browserDocument.personalGraphExtractionId, token);
    const browserConversation = await api('/conversations', { token, body: { title: '浏览器教材会话', mode: 'rag', documentIds: [browserDocument.documentId] } });
    const finishPath = path.join(outputDir, `${schema}.browser-finished`);
    writeFileSync(path.join(outputDir, 'browser-fixture.json'), JSON.stringify({ apiBase, username: 'stage0_owner', password,
      conversationId: browserConversation.conversationId, documentId: browserDocument.documentId,
      mathConversationId: mathConversation.conversationId, physicsConversationId: physicsConversation.conversationId,
      strategyConversations, flowConversations, browserFailureControl, finishPath,
      flowBrowser: { username: flowBrowserUsername, password, cases: browserCases } }, null, 2));
    console.log(`浏览器验收环境已就绪，临时账号与地址见 ${path.join(outputDir, 'browser-fixture.json')}。完成后创建该文件中 finishPath 指定的标记；30 分钟后自动清理。`);
    const deadline = Date.now() + 30 * 60 * 1000;
    while (!existsSync(finishPath) && Date.now() < deadline) await delay(500);
  }
} catch (error) {
  failure = error;
} finally {
  writeFileSync(path.join(outputDir,'model-errors.json'),JSON.stringify(modelErrors,null,2));
  writeFileSync(path.join(outputDir,'teacher-requests.json'),JSON.stringify(teacherRequests,null,2));
  writeFileSync(path.join(outputDir,'flow-requests.json'),JSON.stringify(flowRequests,null,2));
  writeFileSync(path.join(outputDir,'flow-case-results.json'),JSON.stringify(flowCaseResults,null,2));
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
    passed: !failure, checks, model: realTeacher || realFlow ? teacherModel : (replayFlow ? 'saved responses; not a new model-quality evaluation' : 'local deterministic stub; not a model-quality evaluation'),
    teacherModelMode:options['teacher-model'],realTeacherCalls,requiresTeacherAnswerReview:realTeacher,
    flowEvaluationMode:options['flow-evaluation'],realFlowCalls,requiresFlowAnswerReview:realFlow,
    flowSuiteVersion:flowSuite?.version,flowCaseCount:flowCaseResults.length,replaySha256,
    temporaryDatabase: schema, cleanup: databaseCreated ? (databaseRemoved ? 'removed' : 'failed') : 'not needed',
    error: failure?.message, time: new Date().toISOString(),
  }, null, 2));
}
if (failure) { console.error(failure.message); process.exitCode = 1; }
else console.log(`全部 ${checks.length} 组验收通过。临时数据库和测试服务已清理。`);
