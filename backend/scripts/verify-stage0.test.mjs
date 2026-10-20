import assert from 'node:assert/strict';
import { spawnSync } from 'node:child_process';
import { createHash } from 'node:crypto';
import { mkdtempSync, mkdirSync, readFileSync, writeFileSync, rmSync, existsSync } from 'node:fs';
import { tmpdir } from 'node:os';
import path from 'node:path';
import { fileURLToPath, pathToFileURL } from 'node:url';
import test from 'node:test';

const projectRoot = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '../..');

function verifyFailedPreflight(t, extraOptions = [], expectedCount = 5, expectedMaxCalls = 5, manualMode = 'real') {
  const directory = mkdtempSync(path.join(tmpdir(), 'ai-tutor-eval-test-'));
  t.after(() => {
    assert.equal(path.dirname(path.resolve(directory)), path.resolve(tmpdir()));
    assert(path.basename(directory).startsWith('ai-tutor-eval-test-'));
    rmSync(directory, { recursive: true, force: true });
  });
  const requestsPath = path.join(directory, 'requests.jsonl');
  const fixtureRoot = path.join(directory, 'project');
  mkdirSync(path.join(fixtureRoot, 'backend/target'), {recursive:true});
  mkdirSync(path.join(fixtureRoot, 'backend/evaluation'), {recursive:true});
  // 预检必须在服务启动前失败，测试无需已构建的后端或可运行的 JAR。
  writeFileSync(path.join(fixtureRoot, 'backend/target/ai-tutor-backend-0.0.1-SNAPSHOT.jar'), '');
  writeFileSync(path.join(fixtureRoot, 'backend/evaluation/stage2-flow-cases.json'),
    readFileSync(path.join(projectRoot, 'backend/evaluation/stage2-flow-cases.json')));
  writeFileSync(path.join(fixtureRoot, 'backend/evaluation/stage2-textbook-flow-cases.json'), JSON.stringify({
    version:'stage2-textbook-flow-v1',cases:[
      {id:'TJ01',topic:'ArrayList 索引访问',filename:'guided-java.txt',source:'ArrayList 基于动态数组，按索引读取元素。',supported:true,path:'remediate'},
      {id:'TM01',topic:'导数与瞬时变化率',filename:'guided-math.txt',source:'导数表示瞬时变化率，g(x)=x² 的导数为2x。',supported:true,path:'remediate'},
    ],
  }));
  const injectPath = path.join(directory, 'offline-fetch.mjs');
  writeFileSync(injectPath, `
    import { appendFileSync } from 'node:fs';
    globalThis.fetch = async (url, options = {}) => {
      appendFileSync(${JSON.stringify(requestsPath)}, JSON.stringify({
        url: String(url), method: options.method, hasBody: options.body !== undefined,
        authorization: new Headers(options.headers).has('Authorization')
      }) + '\\n');
      throw new TypeError('synthetic top-level secret', {
        cause: Object.assign(new Error('synthetic nested secret'), {code:'EACCES', syscall:'connect'})
      });
    };
  `);
  const outputDir = path.join(directory, 'result');
  const child = spawnSync(process.execPath, [
    '--import', pathToFileURL(injectPath).href,
    path.join(projectRoot, 'backend/scripts/verify-stage0.mjs'),
    '--project-root', fixtureRoot,
    '--output-dir', outputDir, '--flow-evaluation', manualMode,
    ...extraOptions,
    // 即使预检错误地漏过，也只能触发不存在的客户端，不能访问真实数据库。
    '--mysql-bin', path.join(directory, 'must-not-run-mysql.exe'),
  ], {
    cwd: projectRoot, encoding: 'utf8', windowsHide: true, timeout: 15000,
    env: { ...process.env, DEEPSEEK_API_KEY: 'synthetic-test-key',
      DEEPSEEK_BASE_URL: 'https://api.deepseek.com', DEEPSEEK_MODEL_NAME: 'synthetic-test-model',
      SPRING_DATASOURCE_URL: 'jdbc:mysql://127.0.0.1:3306/unused',
      SPRING_DATASOURCE_USERNAME: 'unused', SPRING_DATASOURCE_PASSWORD: '' },
  });
  assert.ifError(child.error);
  assert.equal(child.status, 1);
  assert(existsSync(requestsPath), 'the real-mode CLI must perform an anonymous preflight before MySQL');
  const requests = readFileSync(requestsPath, 'utf8').trim().split('\n').map(line => JSON.parse(line));
  assert.deepEqual(requests, [{url:'https://api.deepseek.com/models',method:'GET',hasBody:false,authorization:false}]);
  const resultText = readFileSync(path.join(outputDir, 'result.json'), 'utf8');
  const result = JSON.parse(resultText);
  assert.equal(result.passed, false);
  assert.equal(result.realFlowCalls, 0);
  assert.equal(result.realTeacherCalls, 0);
  assert.equal(result.realModelCallLimit, expectedMaxCalls);
  assert.equal(result.realModelCalls,0);
  assert.equal(result.cleanup, 'not needed');
  assert.equal(result.providerPreflight.passed, false);
  assert.equal(result.providerPreflight.error.diagnostic.causes[0].code, 'EACCES');
  assert.deepEqual(result.checks, []);
  assert(!resultText.includes('secret'));
  assert(!resultText.includes('synthetic-test-key'));
  assert(!child.stderr.includes('secret'));
  const cases = JSON.parse(readFileSync(path.join(outputDir, 'flow-case-results.json'), 'utf8'));
  const expectedManualCount=manualMode==='off' ? 0 : 5;
  assert.equal(cases.length, expectedManualCount);
  assert(cases.every(item => item.skipped && item.status === 'blocked' && item.passed === false));
  assert.deepEqual(JSON.parse(readFileSync(path.join(outputDir, 'flow-requests.json'), 'utf8')), []);
  if(expectedCount > expectedManualCount) {
    const textbookCases=JSON.parse(readFileSync(path.join(outputDir,'textbook-flow-case-results.json'),'utf8'));
    assert.equal(textbookCases.length,expectedCount-expectedManualCount);
    assert(textbookCases.every(item=>item.skipped && item.status==='blocked' && item.passed===false));
    assert.deepEqual(JSON.parse(readFileSync(path.join(outputDir,'textbook-flow-requests.json'),'utf8')),[]);
    assert.equal(result.realTextbookFlowCalls,0);
  }
}

test('failed anonymous preflight blocks all real cases before database creation and records zero model attempts', t => {
  verifyFailedPreflight(t);
});

test('combined manual and textbook real evaluation blocks all seven cases before database creation on failed preflight', t => {
  verifyFailedPreflight(t,['--textbook-flow-evaluation','real'],7,7);
});

test('textbook-only real evaluation limits the plan to two cases without activating manual evaluation', t => {
  verifyFailedPreflight(t,['--textbook-flow-evaluation','real'],2,2,'off');
});

test('textbook stub evaluation does not increase the real manual-model allowance', t => {
  verifyFailedPreflight(t,['--textbook-flow-evaluation','stub'],7,5);
});

test('teacher real and textbook real modes are rejected before either network or database access', t => {
  const directory=mkdtempSync(path.join(tmpdir(),'ai-tutor-eval-test-'));
  t.after(()=>{
    assert.equal(path.dirname(path.resolve(directory)),path.resolve(tmpdir()));
    assert(path.basename(directory).startsWith('ai-tutor-eval-test-'));
    rmSync(directory,{recursive:true,force:true});
  });
  const fixtureRoot=path.join(directory,'project');
  mkdirSync(path.join(fixtureRoot,'backend/target'),{recursive:true});
  writeFileSync(path.join(fixtureRoot,'backend/target/ai-tutor-backend-0.0.1-SNAPSHOT.jar'),'');
  const requestsPath=path.join(directory,'requests.jsonl');
  const injectPath=path.join(directory,'offline-fetch.mjs');
  writeFileSync(injectPath,`
    import { appendFileSync } from 'node:fs';
    globalThis.fetch=async()=>{
      appendFileSync(${JSON.stringify(requestsPath)},'unexpected request');
      throw new Error('Network access must not occur');
    };
  `);
  const child=spawnSync(process.execPath,[
    '--import',pathToFileURL(injectPath).href,path.join(projectRoot,'backend/scripts/verify-stage0.mjs'),
    '--project-root',fixtureRoot,'--output-dir',path.join(directory,'result'),
    '--teacher-model','real','--textbook-flow-evaluation','real',
    '--mysql-bin',path.join(directory,'must-not-run-mysql.exe'),
  ],{cwd:projectRoot,encoding:'utf8',windowsHide:true,timeout:15000});
  assert.ifError(child.error);
  assert.equal(child.status,1);
  assert.match(child.stderr,/两类真实模型验收请分别运行/);
  assert.equal(existsSync(requestsPath),false);
});

function replayFixture(t) {
  const directory=mkdtempSync(path.join(tmpdir(),'ai-tutor-replay-test-'));
  t.after(()=>{
    assert.equal(path.dirname(path.resolve(directory)),path.resolve(tmpdir()));
    assert(path.basename(directory).startsWith('ai-tutor-replay-test-'));
    rmSync(directory,{recursive:true,force:true});
  });
  const fixtureRoot=path.join(directory,'project');
  mkdirSync(path.join(fixtureRoot,'backend/target'),{recursive:true});
  mkdirSync(path.join(fixtureRoot,'backend/evaluation'),{recursive:true});
  writeFileSync(path.join(fixtureRoot,'backend/target/ai-tutor-backend-0.0.1-SNAPSHOT.jar'),'');
  const replayPaths={};
  for(const [kind,name] of [['manual','stage2-flow-cases.json'],['textbook','stage2-textbook-flow-cases.json']]) {
    const bytes=readFileSync(path.join(projectRoot,'backend/evaluation',name));
    writeFileSync(path.join(fixtureRoot,'backend/evaluation',name),bytes);
    const suite=JSON.parse(bytes);
    replayPaths[kind]=path.join(directory,kind+'-requests.json');
    writeFileSync(replayPaths[kind],JSON.stringify(suite.cases.map(item=>({
      caseId:item.id,messages:[{role:'system',content:'Historical prompt remains data.'},
        {role:'user',content:JSON.stringify({topic:item.topic,source:[{id:'E1',text:kind==='textbook'
          ? `片段1｜文档：${item.filename}｜chunkIndex：0\n${item.source}` : item.source}]})}],
      // Invalid JSON must still load, because the real parser must receive the original response.
      answer:'{"supported":true,',
    }))));
  }
  const requestsPath=path.join(directory,'unexpected-network.jsonl');
  const injectPath=path.join(directory,'offline-fetch.mjs');
  writeFileSync(injectPath,`
    import { appendFileSync } from 'node:fs';
    globalThis.fetch=async()=>{
      appendFileSync(${JSON.stringify(requestsPath)},'unexpected request');
      throw new Error('Network access must not occur');
    };
  `);
  const outputDir=path.join(directory,'result');
  const args=['--flow-evaluation','replay','--flow-replay-file',replayPaths.manual,
    '--textbook-flow-evaluation','replay','--textbook-flow-replay-file',replayPaths.textbook];
  const run=(options=args)=>{
    const child=spawnSync(process.execPath,[
      '--import',pathToFileURL(injectPath).href,path.join(projectRoot,'backend/scripts/verify-stage0.mjs'),
      '--project-root',fixtureRoot,'--output-dir',outputDir,...options,
      '--mysql-bin',path.join(directory,'must-not-run-mysql.exe'),
    ],{cwd:projectRoot,encoding:'utf8',windowsHide:true,timeout:15000,env:{...process.env,
      DEEPSEEK_API_KEY:'synthetic-test-key',DEEPSEEK_BASE_URL:'https://api.deepseek.com',
      SPRING_DATASOURCE_URL:'jdbc:mysql://127.0.0.1:3306/unused'}});
    assert.ifError(child.error);
    assert.equal(child.status,1);
    assert.equal(existsSync(requestsPath),false,'replay configuration must never reach external fetch');
    return child;
  };
  return {replayPaths,outputDir,args,run};
}

test('all seven replay responses load without parsing or repairing answers and record their file checksums', t=>{
  const fixture=replayFixture(t);
  const child=fixture.run();
  assert.match(child.stderr,/MySQL 客户端无法运行/);
  for(const [kind,name] of [['manual','flow-provenance.json'],['textbook','textbook-flow-provenance.json']]) {
    const provenance=JSON.parse(readFileSync(path.join(fixture.outputDir,name),'utf8'));
    assert.equal(provenance.mode,'replay');
    assert.equal(provenance.requestedModel,'saved-response-replay');
    assert.equal(provenance.replaySha256,createHash('sha256').update(readFileSync(fixture.replayPaths[kind])).digest('hex'));
  }
  const result=JSON.parse(readFileSync(path.join(fixture.outputDir,'result.json'),'utf8'));
  assert.equal(result.realModelCalls,0);
  assert.equal(result.realModelCallLimit,0);
  assert.match(result.model,/saved responses/);
});

for(const [name,change,expected] of [
  ['missing textbook case',rows=>rows.pop(),/回放必须包含完整固定题集/],
  ['duplicate textbook case',rows=>{rows[1]=rows[0];},/回放案例编号不得重复/],
  ['modified textbook source',rows=>{const data=JSON.parse(rows[0].messages.at(-1).content);data.source[0].text+=' changed';rows[0].messages.at(-1).content=JSON.stringify(data);},/回放资料必须与冻结题集一致/],
  ['modified textbook topic',rows=>{const data=JSON.parse(rows[0].messages.at(-1).content);data.topic='different topic';rows[0].messages.at(-1).content=JSON.stringify(data);},/教材回放主题必须与冻结题集一致/],
  ['modified textbook evidence identifier',rows=>{const data=JSON.parse(rows[0].messages.at(-1).content);data.source[0].id='E2';rows[0].messages.at(-1).content=JSON.stringify(data);},/E2[\s\S]*E1/],
]) {
  test(`textbook replay rejects ${name} before database and network access`,t=>{
    const fixture=replayFixture(t);
    const rows=JSON.parse(readFileSync(fixture.replayPaths.textbook,'utf8'));
    change(rows);writeFileSync(fixture.replayPaths.textbook,JSON.stringify(rows));
    const child=fixture.run();
    assert.match(child.stderr,expected);
    assert.equal(existsSync(path.join(fixture.outputDir,'result.json')),false);
  });
}

for(const options of [
  ['--teacher-model','real','--textbook-flow-evaluation','replay'],
  ['--flow-evaluation','real','--textbook-flow-evaluation','replay'],
  ['--flow-evaluation','replay','--textbook-flow-evaluation','real'],
]) {
  test(`real and replay modes cannot mix: ${options.join(' ')}`,t=>{
    const fixture=replayFixture(t);
    const child=fixture.run(options);
    assert.match(child.stderr,/真实模型验收不能与本地回放混用/);
    assert.equal(existsSync(path.join(fixture.outputDir,'result.json')),false);
  });
}

test('textbook replay requires its file and refuses a replay file outside replay mode',t=>{
  const fixture=replayFixture(t);
  assert.match(fixture.run(['--textbook-flow-evaluation','replay']).stderr,/replay 模式必须提供 --textbook-flow-replay-file/);
  assert.match(fixture.run(['--textbook-flow-replay-file',fixture.replayPaths.textbook]).stderr,/其他模式不得提供/);
});
