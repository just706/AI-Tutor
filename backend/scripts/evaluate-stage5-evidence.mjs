import assert from 'node:assert/strict';
import { createHash } from 'node:crypto';
import { existsSync, mkdirSync, readFileSync, writeFileSync } from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { parseArgs } from 'node:util';
import { scoreRetrievalRun, validateRetrievalSuite } from './evaluate-stage5-retrieval.mjs';
import { retrieveCandidate, compareCases } from './stage5-vector.mjs';
import { inspectEvidence, applyEvidenceGate } from './stage5-evidence.mjs';

const sha = bytes => createHash('sha256').update(bytes).digest('hex');
const read = filename => JSON.parse(readFileSync(filename, 'utf8'));
const digest = filename => sha(readFileSync(filename));

function loadRecording(directory, expectedReport, config) {
  const report = read(path.join(directory, 'report.json'));
  assert.deepEqual(report, expectedReport, 'Archived report differs from the reviewed recording');
  const baseline = read(path.join(directory, 'baseline/report.json'));
  const suite = validateRetrievalSuite(read(path.join(directory, 'baseline/suite.json')));
  const input = read(path.join(directory, 'baseline/input.json'));
  const lexical = read(path.join(directory, 'baseline/retrieval.json'));
  const embeddings = read(path.join(directory, 'embeddings.json'));
  assert.deepEqual(report.config, config, 'Archived candidate configuration changed');
  for (const [filename, hash] of [
    ['baseline/report.json', report.provenance.baselineReportSha256],
    ['baseline/suite.json', report.provenance.suiteSha256],
    ['baseline/input.json', report.provenance.inputSha256],
    ['baseline/retrieval.json', baseline.provenance.retrievalSha256],
    ['embeddings.json', report.provenance.embeddingsSha256],
    ['embedding-input.json', embeddings.provenance.inputSha256],
    ['stage5-vector-config.json', embeddings.provenance.configSha256],
    ['encode-stage5-local.py', embeddings.provenance.encoderSha256],
  ]) assert.equal(digest(path.join(directory, filename)), hash, `Recording fingerprint mismatch: ${filename}`);
  assert.equal(report.provenance.suiteSha256, expectedReport.provenance.suiteSha256, 'Frozen suite changed');
  assert.equal(report.provenance.inputSha256, expectedReport.provenance.inputSha256, 'Frozen query input changed');
  assert.deepEqual(input, { topK: suite.topK, documents: suite.documents,
    cases: suite.cases.map(({ id, documents, question, setup = [] }) => ({ id, documents, question, setup })) }, 'Input differs from suite');
  const scoredBaseline = scoreRetrievalRun(suite, lexical);
  assert.equal(scoredBaseline.passedCases, baseline.passedCases, 'Archived baseline count changed');
  assert.equal(report.comparisons.length, 8, 'Missing candidate configurations');
  assert.equal(new Set(report.comparisons.map(item => `${item.mode}:${item.threshold}`)).size, 8, 'Duplicate configuration');
  assert.equal(report.suiteVersion, suite.version);
  return { report, suite, input, lexical, embeddings, scoredBaseline,
    provenance: { reportSha256: digest(path.join(directory, 'report.json')), suiteSha256: report.provenance.suiteSha256,
      inputSha256: report.provenance.inputSha256, embeddingsSha256: report.provenance.embeddingsSha256 } };
}

function main() {
  const { values } = parseArgs({ options: {
    'output-dir': { type: 'string' }, 'development-dir': { type: 'string' }, 'holdout-dir': { type: 'string' },
  } });
  const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '../..');
  assert(values['output-dir'], 'Use --output-dir with a new directory');
  const output = path.resolve(values['output-dir']);
  assert(!existsSync(output), 'Output already exists; preserve failed experiments');
  const config = read(path.join(root, 'backend/evaluation/stage5-vector-config.json'));
  const policy = read(path.join(root, 'backend/evaluation/stage5-evidence-policy.json'));
  assert.equal(policy.version, 'stage5-slot-veto-v1');
  assert.equal(policy.unverifiedAllowedInExperiment, true);
  const contrastSuite = read(path.join(root, 'backend/evaluation/stage5-evidence-cases.json'));
  assert(contrastSuite.cases.length > 0, 'Missing contrast cases');
  assert.equal(new Set(contrastSuite.cases.map(item => item.id)).size, contrastSuite.cases.length, 'Duplicate contrast case');
  for (const item of contrastSuite.cases) {
    assert(typeof item.id === 'string' && item.id.trim());
    assert(typeof item.expectedSupport === 'boolean' && typeof item.reason === 'string' && item.reason.trim(), 'Missing independent annotation');
    assert(!item.expectedSupport || (typeof item.expectedAnswer === 'string' && item.expectedAnswer.trim()), 'Missing positive control answer');
  }
  const recordings = [
    loadRecording(path.resolve(values['development-dir'] ?? path.join(root, 'backend/target/stage5-vector-comparison-b')),
      read(path.join(root, 'backend/evaluation/stage5-vector-development-results.json')), config),
    loadRecording(path.resolve(values['holdout-dir'] ?? path.join(root, 'backend/target/stage5-vector-holdout-a')),
      read(path.join(root, 'backend/evaluation/stage5-vector-holdout-results.json')), config),
  ];
  mkdirSync(output);
  const save = (name, data) => writeFileSync(path.join(output, name), JSON.stringify(data, null, 2) + '\n', { flag: 'wx' });
  const sourceHashes = {};
  for (const relative of [
    'backend/scripts/stage5-evidence.mjs', 'backend/scripts/evaluate-stage5-evidence.mjs',
    'backend/scripts/stage5-vector.mjs', 'backend/scripts/evaluate-stage5-retrieval.mjs',
    'backend/evaluation/stage5-evidence-policy.json', 'backend/evaluation/stage5-evidence-cases.json',
  ]) {
    const bytes = readFileSync(path.join(root, relative));
    sourceHashes[relative] = sha(bytes);
    writeFileSync(path.join(output, path.basename(relative)), bytes, { flag: 'wx' });
  }
  save('prepared.json', { preparedAt: new Date().toISOString(), sourceHashes, recordings: recordings.map(item => item.provenance) });
  // Expected labels, answers and annotations never enter inspectEvidence or the gate input artifact.
  save('contrast-input.json', contrastSuite.cases.map(({ question, snippets }) => ({ question, snippets })));
  const contrast = { version: contrastSuite.version, total: contrastSuite.cases.length, correct: 0,
    supported: { allowed: 0, total: 0 }, unsupported: { blocked: 0, total: 0 }, falseAllows: [], falseRejects: [], cases: [] };
  for (const item of contrastSuite.cases) {
    const result = inspectEvidence(item.question, item.snippets);
    assert(policy.outcomes.includes(result.status), 'Unexpected evidence gate outcome');
    const allowed = result.status === 'unverified';
    if (item.expectedSupport) {
      contrast.supported.total++;
      contrast.supported.allowed += Number(allowed);
      if (!allowed) contrast.falseRejects.push(item.id);
    } else {
      contrast.unsupported.total++;
      contrast.unsupported.blocked += Number(!allowed);
      if (allowed) contrast.falseAllows.push(item.id);
    }
    const correct = allowed === item.expectedSupport;
    contrast.correct += Number(correct);
    contrast.cases.push({ ...item, ...result, allowedInExperiment: allowed, correct });
  }
  const comparisons = [];
  for (const recording of recordings) {
    const { report, suite, input, lexical, embeddings, scoredBaseline } = recording;
    save(`${suite.version}-gate-input.json`, { documents: input.documents,
      cases: input.cases.map(({ id, documents }) => ({ id, documents })),
      resolved: lexical.map(({ caseId, resolvedQuestion, clarification }) => ({ caseId, resolvedQuestion, clarification })) });
    const variants = [];
    for (const threshold of config.cosineThresholds) for (const mode of ['vector', 'hybrid']) {
      const beforeRows = retrieveCandidate(input, lexical, embeddings, { mode, threshold, rrfK: config.rrfK });
      const before = scoreRetrievalRun(suite, beforeRows);
      const archived = report.comparisons.find(item => item.mode === mode && item.threshold === threshold);
      assert(archived, 'Missing archived candidate');
      assert.deepEqual(before.cases.map(({ caseId, passed, retrieved }) => ({ caseId, passed, retrieved })),
        archived.cases.map(({ caseId, passed, retrieved }) => ({ caseId, passed, retrieved })), 'Reconstructed candidate differs from archived outcome');
      const afterRows = applyEvidenceGate(input, beforeRows);
      const after = scoreRetrievalRun(suite, afterRows);
      const changes = compareCases(before.cases, after.cases);
      const versusLexical = compareCases(scoredBaseline.cases, after.cases);
      const result = { mode, threshold, before, after, changes, versusLexical };
      save(`${suite.version}-${mode}-${threshold}.json`, result);
      variants.push({ mode, threshold, beforePassed: before.passedCases, afterPassed: after.passedCases, total: after.totalCases,
        beforeAnswerable: before.answerable, afterAnswerable: after.answerable, beforeRefusal: before.refusal, afterRefusal: after.refusal,
        afterClarification: after.clarification, changes, versusLexical,
        cases: after.cases.map(row => ({ caseId: row.caseId, passed: row.passed, retrieved: row.retrieved, evidenceGate: row.evidenceGate })) });
    }
    comparisons.push({ suiteVersion: suite.version, sourceRecording: recording.provenance,
      lexicalPassed: scoredBaseline.passedCases, total: suite.cases.length, variants });
  }
  const sufficientForFurtherValidation = contrast.correct === contrast.total
    && comparisons.every(item => item.variants.every(variant => variant.changes.regressed.length === 0));
  const report = { version: policy.version, finishedAt: new Date().toISOString(), policy,
    executionMode: 'Verified archived embeddings and current deterministic ranking/gate; no Java, encoder or generation run.',
    localModelForwardPasses: 0, externalModelCalls: 0, candidateAcceptance: 'not_accepted',
    decision: sufficientForFurtherValidation ? 'requires_independent_validation' : 'rule_is_not_an_answerability_verifier',
    provenance: { sourceHashes, nodeVersion: process.version, contrastInputSha256: digest(path.join(output, 'contrast-input.json')) },
    contrast, comparisons,
    limitations: ['Surface cue recognition is not entailment', 'Known failures influenced prototype design',
      'Same-author synthetic controls; no population accuracy estimate', 'No generated answers or real learner outcomes',
      'No application integration, service failure or ownership validation', 'Archived query timing is not fresh model performance'] };
  save('report.json', report);
  console.log(JSON.stringify({ output, decision: report.decision, contrast: { correct: contrast.correct, total: contrast.total,
    supported: contrast.supported, unsupported: contrast.unsupported, falseAllows: contrast.falseAllows, falseRejects: contrast.falseRejects },
    comparisons: comparisons.map(item => ({ suite: item.suiteVersion, lexical: item.lexicalPassed,
      variants: item.variants.map(({ mode, threshold, beforePassed, afterPassed, changes }) => ({ mode, threshold, beforePassed, afterPassed, changes })) })),
    externalModelCalls: 0, localModelForwardPasses: 0 }));
  if (!sufficientForFurtherValidation) process.exitCode = 1;
}

if (process.argv[1] && path.resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  try { main(); } catch (error) { console.error(error.message); process.exitCode = 2; }
}
