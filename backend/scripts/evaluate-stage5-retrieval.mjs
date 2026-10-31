import assert from 'node:assert/strict';
import { createHash } from 'node:crypto';
import { spawnSync } from 'node:child_process';
import { existsSync, mkdirSync, readFileSync, readdirSync, statSync, writeFileSync } from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { parseArgs } from 'node:util';

const sha = bytes => createHash('sha256').update(bytes).digest('hex');

export function validateRetrievalSuite(suite) {
  assert(typeof suite.version === 'string' && suite.version.trim(), 'Missing suite version');
  assert(typeof suite.scope === 'string' && suite.scope.trim(), 'Missing suite scope');
  assert(Number.isSafeInteger(suite.topK) && suite.topK >= 1 && suite.topK <= 8, 'Invalid topK');
  assert(Array.isArray(suite.documents) && suite.documents.length > 0, 'Missing documents');
  assert.equal(new Set(suite.documents.map(item => item.id)).size, suite.documents.length, 'Duplicate document');
  for (const document of suite.documents) {
    assert(typeof document.id === 'string' && document.id.trim(), 'Invalid document ID');
    assert(Array.isArray(document.chunks) && document.chunks.length > 0, 'Missing chunks');
    document.chunks.forEach((chunk, index) => {
      assert.equal(chunk.id, `${document.id}:${index}`, 'Chunk ID must match document and index');
      assert(typeof chunk.text === 'string' && chunk.text.trim(), 'Empty chunk');
    });
  }
  assert(Array.isArray(suite.cases) && suite.cases.length > 0, 'Missing cases');
  assert.equal(new Set(suite.cases.map(item => item.id)).size, suite.cases.length, 'Duplicate case');
  for (const item of suite.cases) {
    assert(typeof item.id === 'string' && item.id.trim() && typeof item.group === 'string' && item.group.trim(), 'Invalid case identity');
    assert(typeof item.question === 'string' && item.question.trim(), 'Missing case question');
    assert(Array.isArray(item.documents) && item.documents.length > 0 && new Set(item.documents).size === item.documents.length, 'Invalid selected documents');
    assert(item.documents.every(id => suite.documents.some(document => document.id === id)), 'Unknown selected document');
    assert((item.setup ?? []).every(question => typeof question === 'string' && question.trim()), 'Invalid setup question');
    assert(['refusal', 'clarification'].includes(item.expect) || (!item.expect && Array.isArray(item.evidence) && item.evidence.length), 'Invalid expected outcome');
    for (const alternatives of item.evidence ?? []) {
      assert(Array.isArray(alternatives) && alternatives.length && alternatives.every(text => typeof text === 'string' && text.trim()), 'Empty evidence');
      assert(alternatives.some(text => suite.documents.filter(document => item.documents.includes(document.id))
        .some(document => document.chunks.some(chunk => chunk.text.includes(text)))), `Expected evidence absent from selected chunks: ${item.id}`);
    }
  }
  return suite;
}

export function scoreRetrievalRun(suite, rows) {
  const caseIds = suite.cases.map(item => item.id);
  assert(caseIds.length > 0, 'No evaluation cases');
  assert.equal(new Set(caseIds).size, caseIds.length, 'Duplicate suite case');
  assert.equal(rows.length, caseIds.length, 'Missing or extra result case');
  assert.equal(new Set(rows.map(row => row.caseId)).size, rows.length, 'Duplicate result case');
  assert(rows.every(row => caseIds.includes(row.caseId)), 'Unknown result case');
  const chunks = new Map();
  for (const document of suite.documents) for (const chunk of document.chunks) {
    assert(!chunks.has(chunk.id), 'Duplicate fixture chunk');
    chunks.set(chunk.id, { ...chunk, documentId: document.id });
  }
  const result = { totalCases: caseIds.length, passedCases: 0,
    answerable: { passed: 0, total: 0 }, evidenceGroups: { found: 0, total: 0 },
    refusal: { passed: 0, total: 0 }, clarification: { passed: 0, total: 0 },
    groups: {}, cases: [] };
  for (const item of suite.cases) {
    const row = rows.find(row => row.caseId === item.id);
    assert(Number.isFinite(row.elapsedMs) && row.elapsedMs >= 0, 'Invalid latency');
    assert(typeof row.resolvedQuestion === 'string' && row.resolvedQuestion.trim(), 'Missing resolved question');
    assert(row.clarification === null || (typeof row.clarification === 'string' && row.clarification.trim()), 'Invalid clarification');
    assert(Array.isArray(row.retrieved), 'Invalid chunk list');
    assert(row.retrieved.length <= suite.topK, 'Chunk limit exceeded');
    assert.equal(new Set(row.retrieved).size, row.retrieved.length, 'Repeated chunk');
    for (const id of row.retrieved) {
      assert(chunks.has(id), 'Unknown chunk');
      assert(item.documents.includes(chunks.get(id).documentId), 'Unselected document chunk');
    }
    const missingEvidenceGroups = [], evidenceChunks = new Set();
    const evidence = item.evidence ?? [];
    assert(['refusal', 'clarification'].includes(item.expect) || (!item.expect && evidence.length > 0), 'Case lacks an expected outcome');
    evidence.forEach((alternatives, index) => {
      const matches = row.retrieved.filter(id => alternatives.some(text => chunks.get(id).text.includes(text)));
      if (matches.length === 0) missingEvidenceGroups.push(index);
      for (const id of matches) evidenceChunks.add(id);
    });
    const distinctChunksSatisfied = !item.distinctChunks || evidenceChunks.size >= 2;
    const resolvedSubjectSatisfied = (item.resolvedIncludes ?? []).every(name => row.resolvedQuestion.includes(name));
    let passed;
    const metric = item.expect ?? 'answerable';
    if (metric === 'clarification') passed = !!row.clarification && row.retrieved.length === 0;
    else if (metric === 'refusal') passed = row.clarification === null && row.retrieved.length === 0;
    else {
      passed = row.clarification === null && missingEvidenceGroups.length === 0 && distinctChunksSatisfied && resolvedSubjectSatisfied;
      result.evidenceGroups.found += evidence.length - missingEvidenceGroups.length;
      result.evidenceGroups.total += evidence.length;
    }
    result[metric].total++;
    result[metric].passed += Number(passed);
    result.passedCases += Number(passed);
    const group = result.groups[item.group] ??= { passed: 0, total: 0 };
    group.total++; group.passed += Number(passed);
    result.cases.push({ ...row, group: item.group, category: item.category, expected: metric, passed,
      missingEvidenceGroups, distinctChunksSatisfied, resolvedSubjectSatisfied });
  }
  const timings = rows.map(row => row.elapsedMs).sort((a, b) => a - b);
  result.latencyMs = { median: (timings[Math.floor((timings.length - 1) / 2)] + timings[Math.floor(timings.length / 2)]) / 2,
    p95: timings[Math.ceil(timings.length * 0.95) - 1] };
  return result;
}

function loadSuite(root) {
  const baseline = JSON.parse(readFileSync(path.join(root, 'backend/evaluation/stage1-cases.json'), 'utf8'));
  const challenge = JSON.parse(readFileSync(path.join(root, 'backend/evaluation/stage5-retrieval-cases.json'), 'utf8'));
  assert.equal(baseline.version, 'stage1-v1');
  assert.equal(new Set(challenge.baselineCaseIds).size, challenge.baselineCaseIds.length, 'Duplicate baseline case');
  assert(Number.isSafeInteger(challenge.topK) && challenge.topK >= 1 && challenge.topK <= 8);
  const materials = { ...Object.fromEntries(Object.entries(baseline.materials).map(([id, text]) => [id, [text]])) };
  for (const [id, texts] of Object.entries(challenge.additionalMaterials)) {
    assert(!Object.hasOwn(materials, id), 'Cannot replace baseline material');
    materials[id] = texts;
  }
  const documents = Object.entries(materials).map(([id, texts]) => ({ id,
    chunks: texts.map((text, index) => ({ id: `${id}:${index}`, text })) }));
  const cases = challenge.baselineCaseIds.map(id => {
    const item = baseline.cases.find(item => item.id === id);
    assert(item && !item.documents.includes('java'), 'Unknown or unavailable baseline case');
    return { ...item, group: 'stage1_supplement' };
  }).concat(challenge.cases.map(item => ({ ...item, group: 'challenge' })));
  assert.equal(new Set(cases.map(item => item.id)).size, cases.length, 'Duplicate case ID');
  for (const item of cases) {
    assert(item.documents.length && item.documents.every(id => Object.hasOwn(materials, id)), 'Unknown selected material');
    for (const alternatives of item.evidence ?? []) {
      assert(alternatives.length > 0 && alternatives.every(text => typeof text === 'string' && text.trim()), 'Empty evidence');
      assert(alternatives.some(text => item.documents.some(id => materials[id].some(chunk => chunk.includes(text)))),
        `Expected evidence absent from selected fixture: ${item.id}`);
    }
  }
  return { version: challenge.version, scope: challenge.scope, topK: challenge.topK, documents, cases };
}

function treeFingerprint(directory, extension) {
  const entries = [];
  function visit(current) {
    for (const name of readdirSync(current).sort()) {
      const filename = path.join(current, name);
      if (statSync(filename).isDirectory()) visit(filename);
      else if (name.endsWith(extension)) entries.push([path.relative(directory, filename).replaceAll('\\', '/'), sha(readFileSync(filename))]);
    }
  }
  visit(directory);
  assert(entries.length > 0, `Missing compiled classes or dependencies: ${directory}`);
  return { files: entries.length, sha256: sha(JSON.stringify(entries)) };
}

function main() {
  const { values } = parseArgs({ options: { 'output-dir': { type: 'string' }, 'java-bin': { type: 'string', default: 'java' },
    'suite-file': { type: 'string' } } });
  assert(values['output-dir'], 'Use --output-dir with a new directory');
  const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '../..');
  const output = path.resolve(values['output-dir']);
  assert(!existsSync(output), 'Output already exists; preserve previous results');
  const suite = validateRetrievalSuite(values['suite-file'] ? JSON.parse(readFileSync(values['suite-file'], 'utf8')) : loadSuite(root));
  const sourceNames = ['service/impl/RagServiceImpl', 'ai/RagKeywordScorer', 'ai/RagQuestionParser', 'ai/RagFollowUpResolver', 'config/RagProperties'];
  const sources = {};
  for (const name of sourceNames) {
    const relative = `backend/src/main/java/com/aitutor/${name}.java`;
    const source = path.join(root, relative), compiled = path.join(root, `backend/target/classes/com/aitutor/${name}.class`);
    assert(statSync(compiled).mtimeMs >= statSync(source).mtimeMs, 'Build the current backend with mvnw.cmd -o verify first');
    sources[relative] = sha(readFileSync(source));
  }
  const classes = treeFingerprint(path.join(root, 'backend/target/classes'), '.class');
  const dependencies = treeFingerprint(path.join(root, 'backend/target/dependency'), '.jar');
  const helper = path.join(root, 'backend/scripts/Stage5RetrievalProbe.java');
  const input = { topK: suite.topK, documents: suite.documents,
    // The retrieval process never receives expected outcomes, evidence annotations or rubrics.
    cases: suite.cases.map(({ id, documents, question, setup = [] }) => ({ id, documents, question, setup })) };
  mkdirSync(output);
  const save = (name, value) => writeFileSync(path.join(output, name), JSON.stringify(value, null, 2) + '\n', { flag: 'wx' });
  save('suite.json', suite);
  save('input.json', input);
  if (values['suite-file']) writeFileSync(path.join(output, 'custom-suite.json'), readFileSync(values['suite-file']), { flag: 'wx' });
  for (const [name, relative] of [
    ['stage1-cases.json', 'backend/evaluation/stage1-cases.json'],
    ['stage5-retrieval-cases.json', 'backend/evaluation/stage5-retrieval-cases.json'],
    ['Stage5RetrievalProbe.java', 'backend/scripts/Stage5RetrievalProbe.java'],
    ['evaluate-stage5-retrieval.mjs', 'backend/scripts/evaluate-stage5-retrieval.mjs'],
    ...sourceNames.map(name => [name.split('/').at(-1) + '.java', `backend/src/main/java/com/aitutor/${name}.java`]),
  ]) writeFileSync(path.join(output, name), readFileSync(path.join(root, relative)), { flag: 'wx' });
  const provenance = { createdAt: new Date().toISOString(), suiteSha256: sha(readFileSync(path.join(output, 'suite.json'))),
    inputSha256: sha(readFileSync(path.join(output, 'input.json'))), sources, classes, dependencies,
    runnerSha256: sha(readFileSync(fileURLToPath(import.meta.url))), helperSha256: sha(readFileSync(helper)),
    nodeVersion: process.version, javaVersion: spawnSync(values['java-bin'], ['-version'], { encoding: 'utf8', windowsHide: true }).stderr?.trim() };
  save('provenance.json', provenance);
  const classpath = [path.join(root, 'backend/target/classes'), path.join(root, 'backend/target/dependency/*')].join(path.delimiter);
  const run = spawnSync(values['java-bin'], ['-Dfile.encoding=UTF-8', '--class-path', classpath, helper,
    path.join(output, 'input.json'), path.join(output, 'retrieval.json')], { encoding: 'utf8', windowsHide: true, timeout: 60000 });
  assert.ifError(run.error);
  assert.equal(run.status, 0, run.stderr || run.stdout);
  const rows = JSON.parse(readFileSync(path.join(output, 'retrieval.json'), 'utf8'));
  const report = { version: suite.version, implementation: 'current-java-lexical', scope: suite.scope,
    exclusions: ['document parsing/chunking', 'SQL and ownership enforcement', 'HTTP and transactions', 'answer generation/citation quality', 'learning outcomes', 'candidate vector retrieval'],
    realModelCalls: 0, candidateAcceptance: 'not_evaluated',
    timingScope: 'Seven in-process samples per case after three warmups; per-case median; excludes JVM startup, SQL, network and model calls. Tiny synthetic corpus, not production latency.',
    provenance: { ...provenance, retrievalSha256: sha(readFileSync(path.join(output, 'retrieval.json'))) },
    ...scoreRetrievalRun(suite, rows) };
  save('report.json', report);
  console.log(JSON.stringify({ output, passed: report.passedCases, total: report.totalCases,
    groups: report.groups, answerable: report.answerable, evidenceGroups: report.evidenceGroups,
    refusal: report.refusal, clarification: report.clarification, realModelCalls: 0 }));
  // A completed diagnostic may have failures; preserve them and return a failing benchmark status.
  if (report.passedCases !== report.totalCases) process.exitCode = 1;
}

if (process.argv[1] && path.resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  try { main(); } catch (error) { console.error(error.message); process.exitCode = 2; }
}
