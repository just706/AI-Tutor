// Offline acceptance only. Semantic judgments must be supplied by a reviewer.
import assert from 'node:assert/strict';
import { createHash } from 'node:crypto';
import { readFileSync, mkdirSync, existsSync, writeFileSync, realpathSync } from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { parseArgs } from 'node:util';

const projectRoot = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '../..');
const hash = value => createHash('sha256').update(value).digest('hex');
const pending = id => ({id, status:'pending', note:''});

function exactIds(items, expected, key, name) {
  assert(Array.isArray(items), `${name} must be an array`);
  const ids = items.map(item => item?.[key]);
  assert.equal(new Set(ids).size, ids.length, `${name} contains duplicates`);
  assert.deepEqual([...ids].sort(), [...expected].sort(), `${name} is incomplete or contains unknown entries`);
}

function exactKeys(item, keys, name) {
  assert(item && typeof item === 'object' && !Array.isArray(item), `${name} must be an object`);
  assert.deepEqual(Object.keys(item).sort(), [...keys].sort(), `${name} contains missing or unknown fields`);
}

function text(value, name) {
  assert(typeof value === 'string' && value.trim(), `${name} is required`);
}

function questionAssessable(question) {
  if (typeof question?.prompt !== 'string' || !Array.isArray(question.options)
    || question.options.length < 2 || question.options.length > 4
    || !question.options.every(option => typeof option === 'string')
    || !Array.isArray(question.wrongOptionFeedback)) return false;
  const letters = question.options.map((_, index) => String.fromCharCode(65 + index));
  const expected = letters.filter(letter => letter !== question.correctOption);
  const actual = question.wrongOptionFeedback.map(feedback => feedback?.option);
  return letters.includes(question.correctOption) && actual.length === expected.length
    && new Set(actual).size === actual.length && actual.every(letter => expected.includes(letter));
}

function verifyPreparedQuestions(candidate, prepared, source) {
  assert(prepared && Array.isArray(prepared.checks) && Array.isArray(prepared.practices), 'Prepared lesson is missing');
  assert.equal(prepared.checks.length,2);
  assert.equal(prepared.practices.length,2);
  assert(Array.isArray(source) && Array.isArray(candidate.distinctions),'Prepared feedback requires numbered source and distinctions');
  const questions = [prepared.diagnosis,...prepared.checks,...prepared.practices];
  assert.equal(questions.length,candidate.questions.length,'Prepared question count differs');
  for (const [index, raw] of candidate.questions.entries()) {
    const saved = questions[index];
    assert.equal(saved?.prompt,raw.prompt,'Prepared question differs');
    assert.equal(saved.explanation,raw.explanation,'Prepared explanation differs');
    assert.deepEqual([...saved.options].sort(),[...raw.options].sort(),'Prepared options differ');
    assert.equal(saved.options[saved.correctOption?.charCodeAt(0)-65],
      raw.options[raw.correctOption.charCodeAt(0)-65],'Prepared answer differs');
    const wrongLetters = saved.options.map((_, i) => String.fromCharCode(65 + i)).filter(letter => letter !== saved.correctOption);
    exactIds(saved.wrongOptionFeedback,wrongLetters,'option','Prepared feedback');
    for (const feedback of saved.wrongOptionFeedback) {
      const optionText = saved.options[feedback.option.charCodeAt(0)-65];
      const original = raw.wrongOptionFeedback.find(value => raw.options[value.option.charCodeAt(0)-65] === optionText);
      assert(original,'Prepared feedback option differs');
      const label = candidate.distinctions.find(value => value.id === original.distinctionId)?.label;
      text(label,'Feedback distinction label');
      // Match Java String.trim(); non-ASCII spaces remain part of the label.
      assert.equal(feedback.focus,label.replace(/^[\x00-\x20]+|[\x00-\x20]+$/g,''),'Prepared feedback label differs');
      assert.equal(feedback.explanation,original.explanation,'Prepared feedback explanation differs');
      assert(Array.isArray(original.evidenceIds),'Feedback evidence IDs must be an array');
      const evidence = original.evidenceIds.map(id => {
        const unit = source.find(value => value.id === id);
        assert(unit,'Feedback evidence is absent from source');
        return unit;
      });
      assert.deepEqual(feedback.evidence,evidence,'Prepared feedback evidence differs');
    }
  }
  assert.equal(prepared.explanation,candidate.explanation,'Prepared lesson explanation differs');
  assert.equal(prepared.remedialExplanation,candidate.remedialExplanation,'Prepared remedial explanation differs');
  return questions;
}

function verifyRoutes(routes, evidence, questions) {
  assert(Array.isArray(evidence) && evidence.length === routes.length + 1,'Successful case requires complete answer history');
  assert.equal(new Set(evidence.map(answer => answer.question)).size,evidence.length,'Answer history repeats a question');
  for (const [index, answer] of evidence.entries()) {
    const pool = index === 0 ? questions.slice(0,1)
      : answer.stage === 'CHECKING' ? questions.slice(1,3) : questions.slice(3);
    const question = pool.find(value => value.prompt === answer.question);
    assert(question,'Answered question is outside its stage pool');
    assert.equal(answer.stage,index === 0 ? 'DIAGNOSING' : routes[index-1].stage,'Answer stage differs from route');
    const letters = question.options.map((_, i) => String.fromCharCode(65+i));
    assert(letters.includes(answer.selectedOption),'Recorded answer option is invalid');
    assert.equal(answer.correct,answer.selectedOption === question.correctOption,'Recorded correctness differs from saved answer key');
    if (index === 0) continue;
    const route = routes[index-1];
    assert.equal(route.question,answer.question,'Route differs from the answered question');
    const lastWrong = evidence.slice(0,index).findLast(value => !value.correct);
    assert.equal(route.basedOnQuestion,lastWrong?.question ?? null,'Route does not reference the latest wrong answer');
    assert.equal(route.selectedOption,lastWrong?.selectedOption ?? null,'Route does not reference the selected wrong option');
  }
}

export function readRecording(directory) {
  const artifacts = [];
  function read(name) {
    const bytes = readFileSync(path.join(directory, name));
    artifacts.push({name, sha256:hash(bytes)});
    return JSON.parse(bytes.toString('utf8'));
  }
  const result = read('result.json');
  assert.equal(typeof result.passed, 'boolean');
  const cases = [];
  for (const prefix of ['flow', 'textbook-flow']) {
    const suiteBytes = readFileSync(path.join(projectRoot, `backend/evaluation/stage2-${prefix}-cases.json`));
    const suite = JSON.parse(suiteBytes.toString('utf8'));
    assert.deepEqual(read(`${prefix}-cases.json`), suite, 'Recorded cases differ from the frozen suite');
    const provenance = read(`${prefix}-provenance.json`);
    assert.equal(provenance.suiteSha256, hash(suiteBytes), 'Frozen suite fingerprint differs');
    const requests = read(`${prefix}-requests.json`);
    const results = read(`${prefix}-case-results.json`);
    const ids = suite.cases.map(item => item.id);
    exactIds(requests, ids, 'caseId', 'Recorded requests');
    exactIds(results, ids, 'id', 'Recorded interface cases');
    for (const item of suite.cases) {
      const request = requests.find(value => value.caseId === item.id);
      const recorded = results.find(value => value.id === item.id);
      assert.equal(typeof recorded.passed, 'boolean');
      assert(Array.isArray(request.messages) && request.messages.at(-1)?.role === 'user');
      const input = JSON.parse(request.messages.at(-1).content);
      text(input.topic, 'Recorded topic');
      const recordedTopic = prefix === 'flow' ? recorded.states?.[0]?.topic : item.topic;
      text(recordedTopic,'Recorded resolved topic');
      assert.equal(input.topic,recordedTopic,'Request topic differs from the recorded flow');
      if (Array.isArray(input.source)) {
        assert(input.source.length > 0);
        input.source.forEach((unit, index) => {
          assert.equal(unit.id, `E${index + 1}`); text(unit.text, 'Source passage');
        });
      } else text(input.source, 'Source');
      const source = Array.isArray(input.source) ? input.source.map(unit => unit.text).join('') : input.source;
      assert.equal(source, prefix === 'flow' ? item.source
        : `片段1｜文档：${item.filename}｜chunkIndex：0\n${item.source}`, 'Source differs from the frozen case');
      text(request.answer, 'Raw response');
      let candidate;
      try { candidate = JSON.parse(request.answer); } catch { candidate = null; }
      const assessable = candidate?.schemaVersion === 4 && candidate?.supported === item.supported
        && (!item.supported || Array.isArray(candidate.questions) && candidate.questions.length === 5
          && candidate.questions.every(questionAssessable));
      const selection = prefix === 'flow' ? recorded.selection : recorded.savedFlow?.selection;
      const routes = selection?.history ?? [];
      const evidence = prefix === 'flow' ? recorded.evidence : recorded.savedFlow?.evidence;
      assert(Array.isArray(routes), 'Recorded routes must be an array');
      if (recorded.passed && item.supported) {
        const stages = item.path === 'remediate' ? ['CHECKING','CHECKING','PRACTICE'] : ['CHECKING','PRACTICE'];
        assert.deepEqual(routes.map(route => route.stage),stages,'Successful case requires complete route history');
        for (const route of routes) {
          text(route.reason,'Selection reason');
          assert(candidate?.questions?.some(question => question.prompt === route.question),'Route question is absent from response');
        }
        if (assessable) {
          const questions = verifyPreparedQuestions(candidate,recorded.preparedLesson,input.source);
          verifyRoutes(routes,evidence,questions);
        }
      }
      cases.push({id:item.id, expectedSupported:item.supported, rubric:item.rubric,
        requestSha256:hash(JSON.stringify(request.messages)), responseSha256:hash(request.answer),
        interfacePassed:recorded.passed, assessable:Boolean(assessable), candidate,
        questions:assessable && item.supported ? candidate.questions : [], routes,
        input, preparedLesson:recorded.preparedLesson ?? null,states:recorded.states ?? [],evidence:evidence ?? []});
    }
  }
  return {recordingSha256:hash(JSON.stringify(artifacts)), artifacts, cases,
    interfacePassed:result.passed && cases.every(item => item.interfacePassed),
    modes:{manual:result.flowEvaluationMode, textbook:result.textbookFlowEvaluationMode}};
}

export function prepareReview(bundle) {
  return {schemaVersion:1, recordingSha256:bundle.recordingSha256, reviewer:'', reviewedAt:'',
    cases:bundle.cases.map(item => {
      const checks = item.rubric.map((_, index) => pending(`rubric.${index + 1}`));
      if (!item.expectedSupported) checks.push(pending('refusal.justified'), pending('refusal.no_invented_content'));
      else {
        checks.push(pending('lesson.explanation_supported'), pending('lesson.remedial_supported'));
        item.questions.forEach((question, index) => {
          const id = `Q${index + 1}`;
          for (const suffix of ['self_contained','answer_not_disclosed','explanation_supported']) checks.push(pending(`${id}.${suffix}`));
          question.wrongOptionFeedback.forEach((_, feedbackIndex) => {
            for (const suffix of ['matches_option','supported_by_evidence','distinction_matches','no_hidden_inference'])
              checks.push(pending(`${id}.feedback.${feedbackIndex + 1}.${suffix}`));
          });
        });
        item.routes.forEach((_, index) => checks.push(pending(`R${index + 1}.selection_matches_reason`)));
      }
      return {id:item.id, requestSha256:item.requestSha256, responseSha256:item.responseSha256, checks,
        answers:item.questions.map((_, index) => ({question:`Q${index + 1}`,validOptions:null,note:''}))};
    })};
}

export function evaluateReview(bundle, review) {
  exactKeys(review, ['schemaVersion','recordingSha256','reviewer','reviewedAt','cases'], 'Review');
  assert.equal(review.schemaVersion, 1);
  assert.equal(review.recordingSha256, bundle.recordingSha256, 'Review belongs to different recording bytes');
  text(review.reviewer, 'Reviewer');
  assert(typeof review.reviewedAt === 'string' && /^\d{4}-\d{2}-\d{2}T/.test(review.reviewedAt)
    && Number.isFinite(Date.parse(review.reviewedAt)), 'Reviewed date must be an ISO timestamp');
  const template = prepareReview(bundle);
  exactIds(review.cases, template.cases.map(item => item.id), 'id', 'Reviewed cases');
  const cases = bundle.cases.map(item => {
    const provided = review.cases.find(value => value.id === item.id);
    const expected = template.cases.find(value => value.id === item.id);
    exactKeys(provided, Object.keys(expected), `Case ${item.id}`);
    assert.equal(provided.requestSha256, item.requestSha256, 'Request fingerprint differs');
    assert.equal(provided.responseSha256, item.responseSha256, 'Response fingerprint differs');
    exactIds(provided.checks, expected.checks.map(check => check.id), 'id', `Checks for ${item.id}`);
    exactIds(provided.answers, expected.answers.map(answer => answer.question), 'question', `Answers for ${item.id}`);
    const findings = [];
    if (!item.assessable) findings.push({id:'candidate.assessable',status:'fail',note:'Response cannot be assessed under the expected schema and support status'});
    for (const check of provided.checks) {
      exactKeys(check, ['id','status','note'], 'Check');
      assert(['pass','fail','pending'].includes(check.status), 'Unknown judgment status');
      assert.equal(typeof check.note, 'string');
      if (check.status !== 'pending') text(check.note, 'Judgment rationale');
      if (check.status !== 'pass') findings.push(check);
    }
    for (const [index, question] of item.questions.entries()) {
      const answer = provided.answers.find(value => value.question === `Q${index + 1}`);
      exactKeys(answer, ['question','validOptions','note'], 'Answer review');
      assert.equal(typeof answer.note, 'string');
      if (answer.validOptions === null) {
        findings.push({id:`Q${index + 1}.unique_answer`,status:'pending',note:'Valid options have not been independently identified'});
        continue;
      }
      assert(Array.isArray(answer.validOptions), 'Valid options must be an array or null');
      const letters = question.options.map((_, optionIndex) => String.fromCharCode(65 + optionIndex));
      assert(new Set(answer.validOptions).size === answer.validOptions.length
        && answer.validOptions.every(option => letters.includes(option)), 'Unknown or duplicate reviewed answer');
      text(answer.note, 'Answer rationale');
      if (answer.validOptions.length !== 1 || answer.validOptions[0] !== question.correctOption)
        findings.push({id:`Q${index + 1}.unique_answer`,status:'fail',note:answer.note});
    }
    const contentStatus = findings.some(finding => finding.status === 'fail') ? 'fail'
      : findings.length ? 'pending' : 'pass';
    return {id:item.id,interfacePassed:item.interfacePassed,contentStatus,
      accepted:item.interfacePassed && contentStatus === 'pass', findings};
  });
  const contentPassed = cases.every(item => item.contentStatus === 'pass');
  return {schemaVersion:1,recordingSha256:bundle.recordingSha256,reviewSha256:hash(JSON.stringify(review)),
    reviewer:review.reviewer,reviewedAt:review.reviewedAt,modes:bundle.modes,
    interfacePassed:bundle.interfacePassed,contentPassed,accepted:bundle.interfacePassed && contentPassed,
    interfaceValidationSource:'recorded_artifacts_not_reexecuted',currentBuildVerified:false,
    judgmentSource:'explicit_reviewer_decisions',semanticValidationAutomated:false,externalCallsThisCommand:0,cases};
}

function outside(parent, target) {
  const relative = path.relative(parent, target);
  return relative.startsWith('..' + path.sep) || path.isAbsolute(relative);
}

function resolvedOutput(directory) {
  const absolute = path.resolve(directory);
  let ancestor = absolute;
  while (!existsSync(ancestor)) {
    const parent = path.dirname(ancestor);
    assert.notEqual(parent,ancestor,'Output filesystem root is unavailable');
    ancestor = parent;
  }
  return path.resolve(realpathSync(ancestor), path.relative(ancestor, absolute));
}

function main() {
  const {values} = parseArgs({options:{
    mode:{type:'string'},'recordings-dir':{type:'string'},'output-dir':{type:'string'},'review-file':{type:'string'},
  }});
  assert(['prepare','check'].includes(values.mode), 'Use --mode prepare or check');
  text(values['recordings-dir'], '--recordings-dir'); text(values['output-dir'], '--output-dir');
  const recordingDir = realpathSync(values['recordings-dir']);
  const outputDir = resolvedOutput(values['output-dir']);
  assert(outside(realpathSync(projectRoot), outputDir) && outside(recordingDir, outputDir), 'Output must be outside the repository and original recording');
  assert(!existsSync(outputDir), 'Use a new output directory');
  assert.equal(Boolean(values['review-file']), values.mode === 'check', 'Only check mode requires --review-file');
  const bundle = readRecording(recordingDir);
  if (values.mode === 'prepare') {
    mkdirSync(outputDir,{recursive:true});
    writeFileSync(path.join(outputDir,'review-template.json'),JSON.stringify(prepareReview(bundle),null,2));
    writeFileSync(path.join(outputDir,'review-context.json'),JSON.stringify(bundle,null,2));
    console.log(`Review template prepared for ${bundle.cases.length} cases. Content acceptance is pending; external calls: 0.`);
  } else {
    const review = JSON.parse(readFileSync(values['review-file'],'utf8'));
    const report = evaluateReview(bundle,review);
    mkdirSync(outputDir,{recursive:true});
    writeFileSync(path.join(outputDir,'result.json'),JSON.stringify(report,null,2));
    console.log(JSON.stringify({accepted:report.accepted,interfacePassed:report.interfacePassed,contentPassed:report.contentPassed,
      cases:report.cases.map(({id,contentStatus})=>({id,contentStatus})),externalCallsThisCommand:0}));
    process.exitCode = report.accepted ? 0 : 1;
  }
}

if (process.argv[1] && path.resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  try { main(); } catch (error) {
    console.error(`Content review input rejected: ${error.message}`);
    process.exitCode = 2;
  }
}
