// 用 Java 17 实际执行题面代码；只验证操作结果，不代替人工教学质量审阅。
import assert from 'node:assert/strict';
import { spawnSync } from 'node:child_process';
import { createHash } from 'node:crypto';
import { existsSync, mkdirSync, readFileSync, realpathSync, writeFileSync } from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { parseArgs } from 'node:util';

const projectRoot = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '../..');
const { values } = parseArgs({ options: { 'output-dir': { type: 'string' }, 'java-bin': { type: 'string', default: 'java' } } });
try {
  assert(values['output-dir'], '--output-dir required');
  const output = path.resolve(values['output-dir']);
  const resolved = path.join(realpathSync(path.dirname(output)), path.basename(output));
  const relative = path.relative(realpathSync(projectRoot), resolved);
  assert(relative.startsWith('..' + path.sep) || path.isAbsolute(relative), 'Output must be outside repository');
  assert(!existsSync(output), 'Output exists; choose a new directory');
  const raw = readFileSync(path.join(projectRoot, 'backend/evaluation/stage4-pilot.json'));
  const material = JSON.parse(raw);
  const questions = material.topics.flatMap(topic => ['pre', 'immediate', 'delayed'].flatMap(phase => topic.questions[phase]));
  const code = 'import java.util.*;\nclass Stage4Materials { public static void main(String[] args) {\n' +
    questions.map(question => `{\n${question.setup.map(line => line + ';').join('\n')}\n` +
      `System.out.println("${question.id}\\t" + String.valueOf(${question.expression}));\n}`).join('\n') + '\n}}\n';
  mkdirSync(output);
  const source = path.join(output, 'Stage4Materials.java');
  writeFileSync(source, code);
  const version = spawnSync(values['java-bin'], ['-version'], { encoding: 'utf8', windowsHide: true });
  assert.ifError(version.error); assert.equal(version.status, 0, 'Java version check failed');
  assert(/version "17\./.test(version.stderr + version.stdout), 'Use Java 17 for the frozen material baseline');
  const run = spawnSync(values['java-bin'], [source], { encoding: 'utf8', windowsHide: true, timeout: 30000 });
  assert.ifError(run.error); assert.equal(run.status, 0, run.stderr);
  const actual = run.stdout.trim().split(/\r?\n/).map(line => line.split('\t'));
  assert.deepEqual(actual, questions.map(question => [question.id, question.expectedOutput]), 'A question answer differs from Java 17');
  const report = { passed: true, materialVersion: material.version,
    materialHash: createHash('sha256').update(raw).digest('hex'), javaVersion: (version.stderr + version.stdout).trim(),
    checkedQuestions: actual.length, observations: actual.map(([questionId, output]) => ({ questionId, output })),
    contentReview: 'pending', learningEffectEvaluated: false, realModelCalls: 0 };
  writeFileSync(path.join(output, 'material-verification.json'), JSON.stringify(report, null, 2));
  console.log(JSON.stringify({ passed: true, checkedQuestions: actual.length, contentReview: 'pending', realModelCalls: 0 }));
} catch (error) { console.error(error.message); process.exitCode = 1; }
