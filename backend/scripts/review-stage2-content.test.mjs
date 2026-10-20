import assert from 'node:assert/strict';
import test from 'node:test';
import { mkdtempSync, mkdirSync, readFileSync, writeFileSync, rmSync } from 'node:fs';
import { tmpdir } from 'node:os';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { createHash } from 'node:crypto';
import { spawnSync } from 'node:child_process';
import { readRecording, prepareReview, evaluateReview } from './review-stage2-content.mjs';

const projectRoot = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '../..');
const hash = bytes => createHash('sha256').update(bytes).digest('hex');
const files = ['flow', 'textbook-flow'];

function fixture(t) {
  const directory = mkdtempSync(path.join(tmpdir(), 'ai-tutor-content-review-'));
  t.after(() => {
    assert.equal(path.dirname(directory), path.resolve(tmpdir()));
    assert(path.basename(directory).startsWith('ai-tutor-content-review-'));
    rmSync(directory, { recursive: true, force: true });
  });
  const recordingDir = path.join(directory, 'recording');
  mkdirSync(recordingDir);
  for (const prefix of files) {
    const suiteBytes = readFileSync(path.join(projectRoot, `backend/evaluation/stage2-${prefix === 'flow' ? 'flow' : 'textbook-flow'}-cases.json`));
    const suite = JSON.parse(suiteBytes);
    writeFileSync(path.join(recordingDir, `${prefix}-cases.json`), suiteBytes);
    writeFileSync(path.join(recordingDir, `${prefix}-provenance.json`), JSON.stringify({ suiteSha256: hash(suiteBytes), mode: 'synthetic' }));
    const requests = suite.cases.map(item => ({
      caseId: item.id,
      messages: [{role: 'system', content: 'Synthetic contract fixture'}, {role: 'user', content: JSON.stringify({
        topic: item.topic,
        source: [{id: 'E1', text: prefix === 'flow' ? item.source
          : `片段1｜文档：${item.filename}｜chunkIndex：0\n${item.source}`}],
      })}],
      answer: JSON.stringify(item.supported ? {
        schemaVersion: 4, supported: true, explanation: 'Synthetic explanation', remedialExplanation: 'Synthetic review',
        distinctions: [{id:'D1',label:'First contrast'},{id:'D2',label:'Second contrast'}],
        questions: Array.from({length:5}, (_, index) => ({
          prompt: `Synthetic ${item.id} question ${index + 1}`, options: ['First', 'Second', 'Third'],
          correctOption: 'A', explanation: 'Synthetic reason', wrongOptionFeedback: [
            {option:'B',distinctionId:'D1',explanation:'Second differs',evidenceIds:['E1']},
            {option:'C',distinctionId:'D2',explanation:'Third differs',evidenceIds:['E1']},
          ],
        })),
      } : {schemaVersion:4,supported:false}),
    }));
    const results = suite.cases.map(item => {
      const raw = JSON.parse(requests.find(request => request.caseId === item.id).answer);
      const input = JSON.parse(requests.find(request => request.caseId === item.id).messages.at(-1).content);
      const saved = raw.questions?.map(question => ({...question,wrongOptionFeedback:question.wrongOptionFeedback.map(feedback => ({
        option:feedback.option,focus:raw.distinctions.find(d => d.id === feedback.distinctionId).label,
        explanation:feedback.explanation,evidence:feedback.evidenceIds.map(id => input.source.find(unit => unit.id === id)),
      }))}));
      const remediate = item.path === 'remediate';
      const questionIndices = remediate ? [0,1,2,3] : [0,1,3];
      const evidence = !item.supported ? [] : questionIndices.map((index, position) => ({
        stage:position === 0 ? 'DIAGNOSING' : index === 3 ? 'PRACTICE' : 'CHECKING',
        correct:!remediate || position > 1,question:saved[index].prompt,
        selectedOption:remediate && position <= 1 ? 'B' : 'A',
      }));
      const selection = {history:evidence.slice(1).map((answer,index) => ({stage:answer.stage,
        reason:remediate ? 'DISTINCTION_MATCH' : 'ORIGINAL_ORDER',question:answer.question,
        basedOnQuestion:remediate ? evidence[index === 0 ? 0 : 1].question : null,
        selectedOption:remediate ? 'B' : null,sharedEvidenceIds:remediate ? ['E1'] : [],
      }))};
      return {id:item.id,passed:true,states:[{topic:item.topic}],
        ...(item.supported ? {preparedLesson:{diagnosis:saved[0],checks:saved.slice(1,3),
          practices:saved.slice(3),explanation:raw.explanation,remedialExplanation:raw.remedialExplanation}} : {}),
        ...(prefix === 'flow' ? {selection,evidence} : {savedFlow:{selection,evidence}})};
    });
    writeFileSync(path.join(recordingDir, `${prefix}-requests.json`), JSON.stringify(requests));
    writeFileSync(path.join(recordingDir, `${prefix}-case-results.json`), JSON.stringify(results));
  }
  writeFileSync(path.join(recordingDir, 'result.json'), JSON.stringify({passed:true,flowEvaluationMode:'synthetic',textbookFlowEvaluationMode:'synthetic'}));
  return {directory, recordingDir};
}

function reviewed(bundle) {
  const review = prepareReview(bundle);
  review.reviewer = 'Synthetic fixture author';
  review.reviewedAt = '2026-10-20T00:00:00.000Z';
  for (const item of review.cases) {
    for (const check of item.checks) { check.status = 'pass'; check.note = 'Hand-authored contract fixture only'; }
    for (const answer of item.answers) { answer.validOptions = ['A']; answer.note = 'Synthetic designated answer'; }
  }
  return review;
}

test('preparation covers every recorded question, wrong feedback, rubric and actual route without accepting content', t => {
  const {recordingDir} = fixture(t);
  const bundle = readRecording(recordingDir);
  const review = prepareReview(bundle);
  assert.equal(review.cases.length, 7);
  assert.deepEqual(review.cases.map(item => item.answers.length), [5,5,5,5,0,5,5]);
  const first = review.cases[0];
  assert(first.checks.some(item => item.id === 'Q5.feedback.2.distinction_matches'));
  assert(first.checks.some(item => item.id === 'R1.selection_matches_reason'));
  assert(review.cases.at(-1).checks.some(item => item.id === 'R3.selection_matches_reason'));
  assert(first.checks.some(item => item.id === 'rubric.1'));
  assert(first.checks.every(item => item.status === 'pending'));
  assert.equal(bundle.cases.at(-1).evidence.length,4);
  assert.equal(bundle.cases[0].states[0].topic,'ArrayList 索引访问');
  review.reviewer = 'Pending review fixture';
  review.reviewedAt = '2026-10-20T00:00:00.000Z';
  assert.equal(evaluateReview(bundle, review).accepted, false);
});

test('complete explicit judgments can accept a synthetic contract recording only', t => {
  const {recordingDir} = fixture(t);
  const bundle = readRecording(recordingDir);
  const report = evaluateReview(bundle, reviewed(bundle));
  assert.equal(report.accepted, true);
  assert.equal(report.contentPassed, true);
  assert.equal(report.interfacePassed, true);
  assert.equal(report.externalCallsThisCommand, 0);
  assert.equal(report.cases.length, 7);
  assert.equal(report.interfaceValidationSource, 'recorded_artifacts_not_reexecuted');
  assert.equal(report.currentBuildVerified, false);
  assert.equal(report.semanticValidationAutomated, false);
});

test('two valid answers or an answer key outside the reviewed valid set rejects an interface-successful case', t => {
  const {recordingDir} = fixture(t);
  const bundle = readRecording(recordingDir);
  for (const choices of [['A','C'], ['B'], []]) {
    const review = reviewed(bundle);
    review.cases[0].answers[0].validOptions = choices;
    const report = evaluateReview(bundle, review);
    assert.equal(report.accepted, false);
    assert.equal(report.cases[0].contentStatus, 'fail');
    assert(report.cases[0].findings.some(item => item.id === 'Q1.unique_answer'));
  }
});

test('pending judgments, absent answer reviews and explicit semantic failures cannot become overall passes', t => {
  const {recordingDir} = fixture(t);
  const bundle = readRecording(recordingDir);
  for (const status of ['pending','fail']) {
    const review = reviewed(bundle);
    review.cases[1].checks.find(item => item.id === 'Q3.feedback.1.distinction_matches').status = status;
    const report = evaluateReview(bundle, review);
    assert.equal(report.accepted, false);
    assert.equal(report.cases[1].contentStatus, status);
  }
  const review = reviewed(bundle);
  review.cases[0].answers[0].validOptions = null;
  assert.equal(evaluateReview(bundle, review).accepted, false);
});

test('review omissions, duplicates, unknown checks, invalid states and empty rationale fail closed', t => {
  const {recordingDir} = fixture(t);
  const bundle = readRecording(recordingDir);
  const mutations = [
    r => r.cases.pop(), r => r.cases.push(r.cases[0]),
    r => r.cases[0].checks.pop(), r => r.cases[0].checks.push(r.cases[0].checks[0]),
    r => r.cases[0].checks[0].id = 'unrequested.pass',
    r => r.cases[0].checks[0].status = true,
    r => r.cases[0].checks[0].note = '',
    r => r.cases[0].answers.pop(), r => r.reviewer = '',
    r => r.reviewedAt = 'not-a-date', r => r.cases[0].accepted = true,
  ];
  for (const mutate of mutations) {
    const review = reviewed(bundle); mutate(review);
    assert.throws(() => evaluateReview(bundle, review));
  }
});

test('a previous review cannot be reused after a response, source, request or interface result changes', t => {
  for (const target of ['answer','source','system','interface']) {
    const {recordingDir} = fixture(t);
    const before = readRecording(recordingDir);
    const review = reviewed(before);
    const file = path.join(recordingDir, target === 'interface' ? 'flow-case-results.json' : 'flow-requests.json');
    const data = JSON.parse(readFileSync(file,'utf8'));
    if (target === 'answer') data[0].answer += ' ';
    if (target === 'system') data[0].messages[0].content += ' changed';
    if (target === 'source') {
      const input = JSON.parse(data[0].messages[1].content); input.source[0].text += ' changed';
      data[0].messages[1].content = JSON.stringify(input);
    }
    if (target === 'interface') data[0].passed = false;
    writeFileSync(file, JSON.stringify(data));
    assert.throws(() => evaluateReview(readRecording(recordingDir), review));
  }
});

test('failed interface checks remain failures even when every content judgment is positive', t => {
  const {recordingDir} = fixture(t);
  const file = path.join(recordingDir,'textbook-flow-case-results.json');
  const data = JSON.parse(readFileSync(file,'utf8')); data[0].passed = false;
  writeFileSync(file,JSON.stringify(data));
  const bundle = readRecording(recordingDir);
  const report = evaluateReview(bundle,reviewed(bundle));
  assert.equal(report.contentPassed,true);
  assert.equal(report.interfacePassed,false);
  assert.equal(report.accepted,false);
});

test('missing or duplicate cases and modified frozen suite provenance are rejected', t => {
  for (const mutation of ['missing','duplicate','provenance']) {
    const {recordingDir} = fixture(t);
    const file = path.join(recordingDir, mutation === 'provenance' ? 'flow-provenance.json' : 'flow-requests.json');
    const data = JSON.parse(readFileSync(file,'utf8'));
    if (mutation === 'missing') data.pop();
    if (mutation === 'duplicate') data[1] = data[0];
    if (mutation === 'provenance') data.suiteSha256 = '0'.repeat(64);
    writeFileSync(file,JSON.stringify(data));
    assert.throws(() => readRecording(recordingDir));
  }
});

test('successful supported cases require complete routes and a matching prepared lesson', t => {
  for (const mutation of ['missing','short','stage','question','lesson','lesson-content','topic']) {
    const {recordingDir} = fixture(t);
    const file = path.join(recordingDir, 'flow-case-results.json');
    const data = JSON.parse(readFileSync(file,'utf8'));
    if (mutation === 'missing') delete data[0].selection;
    if (mutation === 'short') data[0].selection.history.pop();
    if (mutation === 'stage') data[0].selection.history[0].stage = 'PRACTICE';
    if (mutation === 'question') data[0].selection.history[0].question = 'Unrecorded question';
    if (mutation === 'lesson') delete data[0].preparedLesson;
    if (mutation === 'lesson-content') data[0].preparedLesson.diagnosis.prompt = 'Different question';
    if (mutation === 'topic') data[0].states[0].topic = 'Different topic';
    writeFileSync(file,JSON.stringify(data));
    assert.throws(() => readRecording(recordingDir), mutation);
  }
});

test('missing, duplicated or correct-option feedback cannot shrink review coverage into a pass', t => {
  for (const mutation of ['missing','duplicate','correct']) {
    const {recordingDir} = fixture(t);
    const file = path.join(recordingDir,'flow-requests.json');
    const data = JSON.parse(readFileSync(file,'utf8'));
    const candidate = JSON.parse(data[0].answer);
    if (mutation === 'missing') candidate.questions[0].wrongOptionFeedback = [];
    if (mutation === 'duplicate') candidate.questions[0].wrongOptionFeedback[1].option = 'B';
    if (mutation === 'correct') candidate.questions[0].wrongOptionFeedback[0].option = 'A';
    data[0].answer = JSON.stringify(candidate);
    writeFileSync(file,JSON.stringify(data));
    const bundle = readRecording(recordingDir);
    assert.equal(bundle.cases[0].assessable,false,mutation);
    assert.equal(evaluateReview(bundle,reviewed(bundle)).accepted,false,mutation);
  }
});

test('saved feedback must preserve option mapping, explanation, label and exact evidence', t => {
  for (const mutation of ['option','explanation','focus','evidence','evidence-text','missing']) {
    const {recordingDir} = fixture(t);
    const file = path.join(recordingDir,'flow-case-results.json');
    const data = JSON.parse(readFileSync(file,'utf8'));
    const feedback = data[0].preparedLesson.diagnosis.wrongOptionFeedback;
    if (mutation === 'option') feedback[0].option = 'C';
    if (mutation === 'explanation') feedback[0].explanation = 'Different explanation';
    if (mutation === 'focus') feedback[0].focus = 'Different contrast';
    if (mutation === 'evidence') feedback[0].evidence = [];
    if (mutation === 'evidence-text') feedback[0].evidence[0].text = 'Changed evidence';
    if (mutation === 'missing') feedback.pop();
    writeFileSync(file,JSON.stringify(data));
    assert.throws(() => readRecording(recordingDir),mutation);
  }
});

test('review accepts the same surrounding label whitespace normalization as the Java parser', t => {
  const {recordingDir} = fixture(t);
  const file = path.join(recordingDir,'flow-requests.json');
  const data = JSON.parse(readFileSync(file,'utf8'));
  const candidate = JSON.parse(data[0].answer);
  candidate.distinctions[0].label = '  First contrast  ';
  data[0].answer = JSON.stringify(candidate);
  writeFileSync(file,JSON.stringify(data));
  const bundle = readRecording(recordingDir);
  assert.equal(evaluateReview(bundle,reviewed(bundle)).accepted,true);
});

test('routes require actual answer history, stage membership and the latest wrong answer', t => {
  for (const mutation of ['missing','stage','selected','source','stale','repeat']) {
    const {recordingDir} = fixture(t);
    const file = path.join(recordingDir,'textbook-flow-case-results.json');
    const data = JSON.parse(readFileSync(file,'utf8'));
    const flow = data[0].savedFlow;
    if (mutation === 'missing') delete flow.evidence;
    if (mutation === 'stage') flow.selection.history.at(-1).question = data[0].preparedLesson.checks[1].prompt;
    if (mutation === 'selected') flow.evidence[0].selectedOption = 'D';
    if (mutation === 'source') flow.selection.history[0].basedOnQuestion = 'Unrecorded prior question';
    if (mutation === 'stale') flow.selection.history[1].basedOnQuestion = flow.evidence[0].question;
    if (mutation === 'repeat') flow.evidence[2].question = flow.evidence[1].question;
    writeFileSync(file,JSON.stringify(data));
    assert.throws(() => readRecording(recordingDir),mutation);
  }
});

const cli = (args, options = {}) => spawnSync(process.execPath,
  [path.join(projectRoot,'backend/scripts/review-stage2-content.mjs'),...args],
  {encoding:'utf8',timeout:10000,...options});

test('CLI prepares pending review, rejects multiple answers and protects existing output', t => {
  const {recordingDir,directory} = fixture(t);
  const preparedDir = path.join(directory,'prepared');
  const args = ['--recordings-dir',recordingDir,'--output-dir',preparedDir,'--mode','prepare'];
  const prepared = cli(args);
  assert.equal(prepared.status,0,prepared.stderr);
  const review = JSON.parse(readFileSync(path.join(preparedDir,'review-template.json'),'utf8'));
  assert(review.cases.every(item => item.checks.every(check => check.status === 'pending')));
  const duplicate = cli(args);
  assert.equal(duplicate.status,2);
  const bundle = readRecording(recordingDir);
  const judged = reviewed(bundle);
  judged.cases[0].answers[0].validOptions = ['A','C'];
  const reviewFile = path.join(directory,'judged.json');
  writeFileSync(reviewFile,JSON.stringify(judged));
  const resultDir = path.join(directory,'checked');
  const checked = cli(['--mode','check','--recordings-dir',recordingDir,'--review-file',reviewFile,'--output-dir',resultDir]);
  assert.equal(checked.status,1,checked.stderr);
  const report = JSON.parse(readFileSync(path.join(resultDir,'result.json'),'utf8'));
  assert.equal(report.accepted,false);
  assert.equal(report.externalCallsThisCommand,0);
});

test('CLI rejects output inside the repository or original recording', t => {
  const {recordingDir} = fixture(t);
  for (const output of [path.join(projectRoot,'content-review-should-not-exist'),path.join(recordingDir,'review')]) {
    const result = cli(['--mode','prepare','--recordings-dir',recordingDir,'--output-dir',output]);
    assert.equal(result.status,2,result.stderr);
    assert.match(result.stderr,/outside the repository and original recording/);
  }
});

test('CLI rejects an unavailable Windows root without hanging', {skip:process.platform !== 'win32'}, t => {
  const {recordingDir} = fixture(t);
  const result = cli(['--mode','prepare','--recordings-dir',recordingDir,
    '--output-dir','\\\\localhost\\ai-tutor-nonexistent-review-share\\output'],{timeout:3000});
  assert.equal(result.error,undefined,'Output resolution must terminate');
  assert.equal(result.status,2,result.stderr);
});
