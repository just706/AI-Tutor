import assert from 'node:assert/strict';
import { spawnSync } from 'node:child_process';
import { mkdtempSync, mkdirSync, readFileSync, writeFileSync, rmSync, existsSync } from 'node:fs';
import { tmpdir } from 'node:os';
import path from 'node:path';
import { fileURLToPath, pathToFileURL } from 'node:url';
import test from 'node:test';

const projectRoot = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '../..');

test('failed anonymous preflight blocks all real cases before database creation and records zero model attempts', t => {
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
    '--output-dir', outputDir, '--flow-evaluation', 'real',
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
  assert.equal(result.cleanup, 'not needed');
  assert.equal(result.providerPreflight.passed, false);
  assert.equal(result.providerPreflight.error.diagnostic.causes[0].code, 'EACCES');
  assert.deepEqual(result.checks, []);
  assert(!resultText.includes('secret'));
  assert(!resultText.includes('synthetic-test-key'));
  assert(!child.stderr.includes('secret'));
  const cases = JSON.parse(readFileSync(path.join(outputDir, 'flow-case-results.json'), 'utf8'));
  assert.equal(cases.length, 5);
  assert(cases.every(item => item.skipped && item.status === 'blocked' && item.passed === false));
  assert.deepEqual(JSON.parse(readFileSync(path.join(outputDir, 'flow-requests.json'), 'utf8')), []);
});
