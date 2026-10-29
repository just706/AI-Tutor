import assert from 'node:assert/strict';
import { existsSync, mkdtempSync, readFileSync, rmSync } from 'node:fs';
import { spawnSync } from 'node:child_process';
import { tmpdir } from 'node:os';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import test from 'node:test';
import { createHash } from 'node:crypto';
import { createParticipant, summarizePilot } from './evaluate-stage4.mjs';

const raw = readFileSync(new URL('../evaluation/stage4-pilot.json', import.meta.url));
const material = JSON.parse(raw);
const materialHash = createHash('sha256').update(raw).digest('hex');
const blank = () => ({ version: material.version, materialHash, mode: 'real',
  review: { status: 'pending', reviewer: null, reviewedAt: null, note: null },
  environment: { appCommit: null, modelRequested: null, modelReported: null, promptVersion: null }, participants: [] });
function participant(id, scores = [1, 3, 2]) {
  return { id, consentedAt: '2026-10-20T08:00:00Z', priorJava: 'basic',
    topics: material.topics.map(topic => ({ topicId: topic.id,
      startedAt: '2026-10-21T08:00:00Z', endedAt: '2026-10-21T08:15:00Z',
      conversationId: 1, taskCompleted: true, helpCount: 2,
      citationsChecked: 2, citationErrors: 0, feedbackChecked: 2, feedbackErrors: 1,
      evidenceRefs: ['local-evidence-01'], issues: [], interveningStudy: 'none',
      phases: Object.fromEntries(['pre', 'immediate', 'delayed'].map((phase, index) => [phase, {
        completedAt: ['2026-10-21T07:55:00Z', '2026-10-21T08:20:00Z', '2026-10-23T09:00:00Z'][index],
        externalHelp: false, scoredBy: 'reviewer-01',
        answers: topic.questions[phase].map((question, item) => ({ questionId: question.id,
          response: 'Observed answer', score: item === 0 ? Math.min(2, scores[index]) : Math.max(0, scores[index] - 2),
          reason: 'Manual rubric assessment' }))
      }])) })) };
}

test('empty records do not invent learners, zero scores, or learning effects', () => {
  const result = summarizePilot(material, blank(), materialHash);
  assert.equal(result.status, 'not_collected');
  assert.equal(result.observedParticipants, 0);
  assert.equal(result.learning.pre.meanPercent, null);
  assert.equal(result.learningEffectEstablished, false);
});

test('scores aggregate at learner level and paired gains use complete pairs only', () => {
  const records = blank(); records.participants = [participant('P01'), participant('P02', [2, 4, 4])];
  const result = summarizePilot(material, records, materialHash);
  assert.equal(result.observedParticipants, 2);
  assert.deepEqual(result.learning.pre, { n: 2, meanPercent: 37.5 });
  assert.deepEqual(result.learning.immediateGain, { n: 2, meanPercentagePoints: 50 });
  assert.deepEqual(result.learning.delayedGain, { n: 2, meanPercentagePoints: 37.5 });
  assert.equal(result.operations.feedbackErrorRate, 0.5);
});

test('missing delayed answers are excluded rather than scored as zero', () => {
  const records = blank(); records.participants = [participant('P01')];
  records.participants[0].topics[0].phases.delayed.answers[0].score = null;
  const result = summarizePilot(material, records, materialHash);
  assert.deepEqual(result.learning.delayed, { n: 0, meanPercent: null });
  assert.equal(result.learning.immediate.n, 1);
  assert.equal(result.topicLearning.arraylist.delayed.n, 0);
  assert.equal(result.topicLearning.hashmap.delayed.n, 1);
  assert.equal(result.status, 'in_progress');
});

test('a fully scored second learner is the only pair when the first learner has missing followup', () => {
  const records = blank(); records.participants = [participant('P01'), participant('P02', [2, 4, 4])];
  records.participants[0].topics[1].phases.delayed.answers[0].score = null;
  assert.deepEqual(summarizePilot(material, records, materialHash).learning.delayedGain,
    { n: 1, meanPercentagePoints: 50 });
});

test('synthetic observations never count as real learners or establish effects', () => {
  const records = blank(); records.mode = 'dry_run'; records.participants = [participant('P01')];
  const result = summarizePilot(material, records, materialHash);
  assert.equal(result.status, 'dry_run'); assert.equal(result.realParticipants, 0);
  assert.equal(result.learningEffectEstablished, false);
});

test('personal walkthrough records stay separate from the novice pilot even with complete scores', () => {
  const records = blank(); records.mode = 'self_test'; records.participants = [participant('P01')];
  const result = summarizePilot(material, records, materialHash);
  assert.equal(result.status, 'self_test');
  assert.equal(result.realParticipants, 1);
  assert.equal(result.pilotParticipants, 0);
  assert.equal(result.inference, 'personal_walkthrough_only');
  assert.equal(result.learningEffectEstablished, false);
});

test('a prepared participant has unknown scores and metrics, never implied zeros', () => {
  const records = blank(); records.participants = [createParticipant(material, 'P01')];
  const result = summarizePilot(material, records, materialHash);
  assert.equal(result.status, 'not_collected');
  assert.equal(result.operations.taskCompletionRate, null);
  assert.equal(result.operations.feedbackErrorRate, null);
  assert.equal(result.learning.pre.n, 0);
});

for (const [name, change, message] of [
  ['changed materials', records => { records.materialHash = 'old-hash'; }, /hash/],
  ['duplicate learners', records => { records.participants.push(participant('P01')); }, /participant ID/],
  ['missing topic', records => { records.participants[0].topics.pop(); }, /missing or unknown/],
  ['duplicate answer', records => { const a = records.participants[0].topics[0].phases.pre.answers; a[1].questionId = a[0].questionId; }, /duplicate IDs/],
  ['out of range score', records => { records.participants[0].topics[0].phases.pre.answers[0].score = 3; }, /Score/],
  ['score without answer', records => { records.participants[0].topics[0].phases.pre.answers[0].response = null; }, /Scoring requires/],
  ['score without reviewer', records => { records.participants[0].topics[0].phases.pre.scoredBy = null; }, /Scoring requires/],
  ['errors greater than inspected feedback', records => { records.participants[0].topics[0].feedbackErrors = 3; }, /exceed/],
  ['unknown count masquerading as zero', records => { records.participants[0].topics[0].helpCount = ''; }, /nonnegative/],
  ['missing error denominator', records => { records.participants[0].topics[0].feedbackChecked = null; }, /denominator/],
  ['timestamps without timezone', records => { records.participants[0].topics[0].startedAt = '2026-10-21T08:00:00'; }, /timezone/],
  ['operational observations without a session', records => { const row = records.participants[0].topics[0]; row.startedAt = null; row.endedAt = null; row.taskCompleted = false; }, /started session/],
  ['pretest after learning', records => { records.participants[0].topics[0].phases.pre.completedAt = '2026-10-21T09:00:00Z'; }, /pretest/],
  ['observations without consent', records => { records.participants[0].consentedAt = null; }, /consent/],
  ['unverified review approval', records => { records.review.status = 'approved'; }, /Content review requires/]
]) test('rejects ' + name, () => {
  const records = blank(); records.participants = [participant('P01')]; change(records);
  assert.throws(() => summarizePilot(material, records, materialHash), message);
});

test('early followup retains the observation but excludes it from the planned delayed endpoint', () => {
  const records = blank(); records.participants = [participant('P01')];
  records.participants[0].topics[0].phases.delayed.completedAt = '2026-10-22T09:00:00Z';
  const result = summarizePilot(material, records, materialHash);
  assert.equal(result.learning.delayed.n, 0);
  assert.equal(result.exclusions[0].reason, 'outside_time_window');
  assert.equal(records.participants[0].topics[0].phases.delayed.answers[0].score, 2);
});

test('external help and intervening study are recorded as endpoint exclusions', () => {
  const records = blank(); records.participants = [participant('P01')];
  records.participants[0].topics[0].phases.immediate.externalHelp = true;
  records.participants[0].topics[1].interveningStudy = 'yes';
  const result = summarizePilot(material, records, materialHash);
  assert.equal(result.learning.immediate.n, 0); assert.equal(result.learning.delayed.n, 0);
  assert.deepEqual(result.exclusions.map(item => item.reason), ['external_help_or_unknown', 'intervening_study_or_unknown']);
});

for (const [name, time, expected] of [
  ['48-hour boundary', '2026-10-23T08:15:00Z', 1],
  ['72-hour boundary', '2026-10-24T08:15:00Z', 1],
  ['after 72 hours', '2026-10-24T08:15:01Z', 0]
]) test('delayed endpoint handles ' + name, () => {
  const records = blank(); records.participants = [participant('P01')];
  for (const topic of records.participants[0].topics) topic.phases.delayed.completedAt = time;
  assert.equal(summarizePilot(material, records, materialHash).learning.delayed.n, expected);
});

test('only reviewed complete real data can become ready for manual interpretation', () => {
  const records = blank(); records.participants = ['P01', 'P02', 'P03', 'P04'].map(id => participant(id));
  assert.equal(summarizePilot(material, records, materialHash).status, 'in_progress');
  records.review = { status: 'approved', reviewer: 'reviewer-01', reviewedAt: '2026-10-19T08:00:00Z', note: 'Reviewed material and rubric' };
  records.environment = { appCommit: 'f86595a', modelRequested: 'recorded-model', modelReported: 'reported-model', promptVersion: 'v1' };
  const result = summarizePilot(material, records, materialHash);
  assert.equal(result.status, 'ready_for_review'); assert.equal(result.learningEffectEstablished, false);
});

test('high impact issues appear first and retain their source reference', () => {
  const records = blank(); records.participants = [participant('P01')];
  records.participants[0].topics[0].issues = [
    { severity: 'low', kind: 'other', description: 'Minor friction', evidenceRef: 'local-01' },
    { severity: 'high', kind: 'feedback', description: 'Wrong feedback', evidenceRef: 'local-02' }
  ];
  const result = summarizePilot(material, records, materialHash);
  assert.equal(result.issues[0].severity, 'high'); assert.equal(result.issues[0].evidenceRef, 'local-02');
});

test('CLI generates separate participant papers, refuses overwrite, and reports empty real data honestly', t => {
  const root = mkdtempSync(path.join(tmpdir(), 'ai-tutor-stage4-test-'));
  t.after(() => {
    assert.equal(path.dirname(path.resolve(root)), path.resolve(tmpdir()));
    assert(path.basename(root).startsWith('ai-tutor-stage4-test-'));
    rmSync(root, { recursive: true, force: true });
  });
  const script = fileURLToPath(new URL('./evaluate-stage4.mjs', import.meta.url));
  const output = path.join(root, 'kit');
  const run = args => spawnSync(process.execPath, [script, ...args], { encoding: 'utf8', windowsHide: true });
  const init = run(['init', '--output-dir', output]);
  assert.equal(init.status, 0, init.stderr);
  const records = JSON.parse(readFileSync(path.join(output, 'records.json')));
  assert.deepEqual(records.participants, []); assert.equal(records.review.status, 'pending');
  for (const topic of material.topics) for (const phase of ['pre', 'immediate', 'delayed']) {
    const paper = readFileSync(path.join(output, `${topic.id}-${phase}.md`), 'utf8');
    assert(paper.includes('System.out.println'));
    assert(!paper.includes('得1分')); assert(!paper.includes('输出：'));
  }
  assert(readFileSync(path.join(output, 'facilitator-only.md'), 'utf8').includes('输出：blue'));
  const report = path.join(output, 'fresh-report.json');
  assert.equal(run(['summarize', '--input', path.join(output, 'records.json'), '--output-file', report]).status, 0);
  assert.equal(JSON.parse(readFileSync(report)).status, 'not_collected');
  const selfOutput = path.join(root, 'self-test');
  assert.equal(run(['init', '--mode', 'self_test', '--output-dir', selfOutput]).status, 0);
  const selfRecords = JSON.parse(readFileSync(path.join(selfOutput, 'records.json')));
  const selfReport = JSON.parse(readFileSync(path.join(selfOutput, 'report.json')));
  assert.equal(selfRecords.mode, 'self_test'); assert.equal(selfRecords.participants[0].id, 'P01');
  assert.equal(selfReport.status, 'self_test'); assert.equal(selfReport.observedParticipants, 0);
  assert.equal(selfReport.pilotParticipants, 0); assert.equal(selfReport.learning.pre.meanPercent, null);
  assert.notEqual(run(['init', '--output-dir', output]).status, 0);
  assert.notEqual(run(['summarize', '--input', path.join(output, 'records.json'), '--output-file', report]).status, 0);
  const forbidden = fileURLToPath(new URL('../evaluation/must-not-write-stage4', import.meta.url));
  assert.notEqual(run(['init', '--output-dir', forbidden]).status, 0);
  assert(!existsSync(forbidden));
});
