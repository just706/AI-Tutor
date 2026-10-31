import assert from 'node:assert/strict';
import { createHash } from 'node:crypto';
import { spawnSync } from 'node:child_process';
import { existsSync, mkdirSync, readFileSync, writeFileSync } from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { parseArgs } from 'node:util';
import { scoreRetrievalRun } from './evaluate-stage5-retrieval.mjs';
import { retrieveCandidate, compareCases } from './stage5-vector.mjs';

const sha = bytes => createHash('sha256').update(bytes).digest('hex');
const readJson = filename => JSON.parse(readFileSync(filename, 'utf8'));

function main() {
  const { values } = parseArgs({ options: {
    'output-dir': { type: 'string' }, 'model-dir': { type: 'string' },
    'python-bin': { type: 'string', default: 'python' }, 'java-bin': { type: 'string', default: 'java' },
    'suite-file': { type: 'string' },
  } });
  assert(values['output-dir'] && values['model-dir'], 'Use --output-dir <new directory> and --model-dir <pinned local model>');
  const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '../..');
  const output = path.resolve(values['output-dir']), modelDir = path.resolve(values['model-dir']);
  assert(!existsSync(output), 'Output already exists; preserve all runs');
  assert(existsSync(path.join(modelDir, 'manifest.json')), 'Prepare the pinned local model first');
  const configPath = path.join(root, 'backend/evaluation/stage5-vector-config.json');
  const config = readJson(configPath);
  assert.equal(config.version, 'stage5-vector-v1');
  assert.deepEqual(config.rrfWeights, { lexical: 1, vector: 1 });
  assert(config.cosineThresholds.length > 0 && config.cosineThresholds.every(value => Number.isFinite(value) && value >= -1 && value <= 1));
  assert.equal(new Set(config.cosineThresholds).size, config.cosineThresholds.length);
  mkdirSync(output);
  const save = (name, data) => writeFileSync(path.join(output, name), JSON.stringify(data, null, 2) + '\n', { flag: 'wx' });
  const frozenSources = [
    'backend/evaluation/stage5-vector-config.json', 'backend/scripts/encode-stage5-local.py',
    'backend/scripts/stage5-vector.mjs', 'backend/scripts/evaluate-stage5-vector.mjs',
    'backend/scripts/evaluate-stage5-retrieval.mjs', 'backend/scripts/prepare-stage5-model.py',
  ];
  const sourceHashes = {};
  for (const relative of frozenSources) {
    const bytes = readFileSync(path.join(root, relative));
    sourceHashes[relative] = sha(bytes);
    writeFileSync(path.join(output, path.basename(relative)), bytes, { flag: 'wx' });
  }
  const preparedAt = new Date().toISOString();
  save('prepared.json', { preparedAt, sourceHashes, externalModelCalls: 0 });
  const suiteArgs = values['suite-file'] ? ['--suite-file', path.resolve(values['suite-file'])] : [];
  if (values['suite-file']) writeFileSync(path.join(output, 'custom-suite.json'), readFileSync(values['suite-file']), { flag: 'wx' });
  const baselineDir = path.join(output, 'baseline');
  const baselineRun = spawnSync(process.execPath, [path.join(root, 'backend/scripts/evaluate-stage5-retrieval.mjs'),
    '--output-dir', baselineDir, '--java-bin', values['java-bin'], ...suiteArgs], { cwd: root, encoding: 'utf8', windowsHide: true, timeout: 90000 });
  assert.ifError(baselineRun.error);
  assert([0, 1].includes(baselineRun.status) && existsSync(path.join(baselineDir, 'report.json')), baselineRun.stderr || 'Baseline did not complete');
  const suite = readJson(path.join(baselineDir, 'suite.json'));
  const input = readJson(path.join(baselineDir, 'input.json'));
  const baselineReport = readJson(path.join(baselineDir, 'report.json'));
  if (!values['suite-file']) {
    const originalBaseline = readJson(path.join(root, 'backend/evaluation/stage5-retrieval-results.json'));
    assert.equal(baselineReport.provenance.suiteSha256, originalBaseline.provenance.suiteSha256, 'Frozen suite has changed');
    assert.equal(baselineReport.provenance.inputSha256, originalBaseline.provenance.inputSha256, 'Frozen retrieval input has changed');
  } else {
    assert.deepEqual(suite, readJson(path.join(output, 'custom-suite.json')), 'Custom suite changed during preparation');
  }
  const lexical = readJson(path.join(baselineDir, 'retrieval.json'));
  const encodingInput = { documents: input.documents.flatMap(document => document.chunks.map(({ id, text }) => ({ id, text }))),
    queries: lexical.filter(row => row.clarification === null).map(row => ({ id: row.caseId, text: row.resolvedQuestion })) };
  save('embedding-input.json', encodingInput);
  const embeddingPath = path.join(output, 'embeddings.json');
  const encoderRun = spawnSync(values['python-bin'], [path.join(output, 'encode-stage5-local.py'),
    '--input-file', path.join(output, 'embedding-input.json'), '--config-file', path.join(output, 'stage5-vector-config.json'),
    '--model-dir', modelDir, '--output-file', embeddingPath], { cwd: root, encoding: 'utf8', windowsHide: true, timeout: 180000,
    env: { ...process.env, PYTHONUTF8: '1', HF_HUB_OFFLINE: '1', TRANSFORMERS_OFFLINE: '1', HF_HUB_DISABLE_TELEMETRY: '1' } });
  writeFileSync(path.join(output, 'encoder.stdout.log'), encoderRun.stdout ?? '', { flag: 'wx' });
  writeFileSync(path.join(output, 'encoder.stderr.log'), encoderRun.stderr ?? '', { flag: 'wx' });
  assert.ifError(encoderRun.error);
  assert.equal(encoderRun.status, 0, `Local encoder failed; inspect ${path.join(output, 'encoder.stderr.log')}`);
  const embeddings = readJson(embeddingPath);
  assert.equal(embeddings.provenance.inputSha256, sha(readFileSync(path.join(output, 'embedding-input.json'))));
  assert.equal(embeddings.provenance.configSha256, sourceHashes['backend/evaluation/stage5-vector-config.json']);
  assert.equal(embeddings.provenance.encoderSha256, sourceHashes['backend/scripts/encode-stage5-local.py']);
  const comparisons = [];
  for (const threshold of config.cosineThresholds) for (const mode of ['vector', 'hybrid']) {
    const rows = retrieveCandidate(input, lexical, embeddings, { mode, threshold, rrfK: config.rrfK });
    const metrics = scoreRetrievalRun(suite, rows);
    const changes = compareCases(baselineReport.cases, metrics.cases);
    const result = { mode, threshold, ...metrics, changes };
    save(`${mode}-${threshold}.json`, result);
    comparisons.push({ mode, threshold, totalCases: metrics.totalCases, passedCases: metrics.passedCases,
      answerable: metrics.answerable, evidenceGroups: metrics.evidenceGroups, refusal: metrics.refusal,
      clarification: metrics.clarification, groups: metrics.groups, latencyMs: metrics.latencyMs, changes,
      // This is a development screen, never an acceptance or statistical significance claim.
      improvesWithoutRegression: changes.improved.length > 0 && changes.regressed.length === 0,
      cases: metrics.cases.map(row => ({ caseId: row.caseId, passed: row.passed, retrieved: row.retrieved,
        missingEvidenceGroups: row.missingEvidenceGroups, topCosine: row.vectorScores[0]?.score ?? null })) });
  }
  const report = { version: config.version, suiteVersion: suite.version, preparedAt, finishedAt: new Date().toISOString(),
    scope: `Offline comparison on ${suite.version} (${suite.cases.length} cases); no generated answers or application integration.`,
    config, externalModelCalls: 0, localForwardPasses: embeddings.provenance.localForwardPasses,
    candidateAcceptance: 'not_accepted',
    nextDecision: comparisons.some(item => item.improvesWithoutRegression) ? 'requires_independent_holdout_and_system_validation' : 'do_not_integrate',
    exclusions: ['SQL/ownership', 'real parsing/chunking', 'answer and citation correctness', 'held-out generalization',
      'production latency/cost', 'service timeout/fallback', 'learning outcomes'],
    timingScope: 'Candidate latency is a sum of the measured Java lexical/preprocessing median, local per-query encoding median (3 samples), and in-process selection time. Includes the lexical prepass even for vector-only to share reference resolution. Model loading and corpus encoding are separate; not an end-to-end SLA.',
    provenance: { sourceHashes, suiteSha256: baselineReport.provenance.suiteSha256,
      inputSha256: baselineReport.provenance.inputSha256, baselineReportSha256: sha(readFileSync(path.join(baselineDir, 'report.json'))),
      embeddingsSha256: sha(readFileSync(embeddingPath)), nodeVersion: process.version, encoder: embeddings.provenance },
    baseline: { passedCases: baselineReport.passedCases, totalCases: baselineReport.totalCases, answerable: baselineReport.answerable,
      evidenceGroups: baselineReport.evidenceGroups, refusal: baselineReport.refusal, clarification: baselineReport.clarification,
      groups: baselineReport.groups, latencyMs: baselineReport.latencyMs }, comparisons };
  save('report.json', report);
  console.log(JSON.stringify({ output, nextDecision: report.nextDecision, candidateAcceptance: report.candidateAcceptance,
    baseline: report.baseline, comparisons: comparisons.map(({ mode, threshold, passedCases, answerable, refusal, changes }) =>
      ({ mode, threshold, passedCases, answerable, refusal, changes })), externalModelCalls: 0, localForwardPasses: report.localForwardPasses }));
  if (!comparisons.some(item => item.improvesWithoutRegression)) process.exitCode = 1;
}

if (process.argv[1] && path.resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  try { main(); } catch (error) { console.error(error.message); process.exitCode = 2; }
}
