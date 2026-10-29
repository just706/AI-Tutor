import assert from 'node:assert/strict';
import { createHash } from 'node:crypto';
import { existsSync, mkdirSync, readFileSync, realpathSync, writeFileSync } from 'node:fs';
import path from 'node:path';
import { fileURLToPath, pathToFileURL } from 'node:url';
import { parseArgs } from 'node:util';

const projectRoot = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '../..');
const materialPath = path.join(projectRoot, 'backend/evaluation/stage4-pilot.json');
const phases = ['pre', 'immediate', 'delayed'];
const hash = value => createHash('sha256').update(value).digest('hex');
const hasText = value => typeof value === 'string' && value.trim().length > 0;
const mean = values => values.length ? Math.round(values.reduce((a, b) => a + b, 0) / values.length * 100) / 100 : null;

function timestamp(value, name) {
  assert(value === null || typeof value === 'string' &&
    /^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(?:\.\d+)?(?:Z|[+-]\d{2}:\d{2})$/.test(value)
    && Number.isFinite(Date.parse(value)), `${name}: use an ISO timestamp with timezone or null`);
  return value === null ? null : Date.parse(value);
}
function count(value, name) {
  assert(value === null || Number.isSafeInteger(value) && value >= 0, `${name}: use a nonnegative integer or null`);
}
function exactIds(items, wanted, key, name) {
  assert(Array.isArray(items), `${name}: array required`);
  const actual = items.map(item => item?.[key]);
  assert.equal(new Set(actual).size, actual.length, `${name}: duplicate IDs`);
  assert.deepEqual([...actual].sort(), [...wanted].sort(), `${name}: missing or unknown IDs`);
}

function learningSummary(rows) {
  const summary = Object.fromEntries(phases.map(phase => {
    const values = rows.map(item => item[phase]).filter(value => value !== null);
    return [phase, { n: values.length, meanPercent: mean(values) }];
  }));
  for (const phase of ['immediate', 'delayed']) {
    const pairs = rows.filter(item => item.pre !== null && item[phase] !== null);
    summary[phase + 'Gain'] = { n: pairs.length, meanPercentagePoints: mean(pairs.map(item => item[phase] - item.pre)) };
  }
  return summary;
}

export function createParticipant(material, id) {
  assert(/^P\d{2,4}$/.test(id), 'Use a pseudonym such as P01');
  return { id, consentedAt: null, priorJava: null, topics: material.topics.map(topic => ({
    topicId: topic.id, startedAt: null, endedAt: null, conversationId: null,
    taskCompleted: null, helpCount: null, citationsChecked: null, citationErrors: null,
    feedbackChecked: null, feedbackErrors: null, interveningStudy: null, evidenceRefs: [], issues: [],
    phases: Object.fromEntries(phases.map(phase => [phase, { completedAt: null,
      externalHelp: null, scoredBy: null, answers: topic.questions[phase].map(question => ({
        questionId: question.id, response: null, score: null, reason: null })) }]))
  })) };
}

export function summarizePilot(material, records, expectedHash) {
  assert.equal(records.version, material.version, 'Material version mismatch');
  assert(hasText(expectedHash) && records.materialHash === expectedHash, 'Material hash mismatch');
  assert(['real', 'dry_run', 'self_test'].includes(records.mode), 'Invalid data mode');
  assert(['pending', 'approved', 'rejected'].includes(records.review?.status), 'Content review status required');
  const reviewedAt = timestamp(records.review.reviewedAt, 'reviewedAt');
  if (records.review.status !== 'pending') assert(hasText(records.review.reviewer) &&
    hasText(records.review.note) && reviewedAt !== null, 'Content review requires reviewer, timestamp and note');
  for (const field of ['appCommit', 'modelRequested', 'modelReported', 'promptVersion']) {
    assert(records.environment && (records.environment[field] === null || hasText(records.environment[field])), `environment.${field} required`);
  }
  assert(Array.isArray(records.participants), 'participants required');
  const ids = records.participants.map(item => item?.id);
  assert(ids.every(id => /^P\d{2,4}$/.test(id)) && new Set(ids).size === ids.length, 'Duplicate or invalid participant ID');
  const exclusions = [], perParticipant = [], issues = [];
  const topicRows = Object.fromEntries(material.topics.map(topic => [topic.id, []]));
  const metrics = { tasksObserved: 0, tasksCompleted: 0, helpCount: 0, helpSamples: 0,
    citationsChecked: 0, citationErrors: 0, citationSamples: 0, feedbackChecked: 0, feedbackErrors: 0, feedbackSamples: 0 };
  let observedParticipants = 0;
  for (const person of records.participants) {
    const consent = timestamp(person.consentedAt, person.id + '.consentedAt');
    assert([null, 'none', 'basic', 'experienced'].includes(person.priorJava), 'Invalid priorJava');
    exactIds(person.topics, material.topics.map(topic => topic.id), 'topicId', person.id + '.topics');
    const topicScores = [];
    let observed = false;
    for (const topic of material.topics) {
      const row = person.topics.find(item => item.topicId === topic.id);
      const name = person.id + '.' + topic.id;
      const start = timestamp(row.startedAt, name + '.startedAt'), end = timestamp(row.endedAt, name + '.endedAt');
      assert(end === null || start !== null && end >= start, name + ': invalid session order');
      if (start !== null) { observed = true; assert(consent !== null && consent <= start, name + ': consent must precede collection'); }
      assert([null, true, false].includes(row.taskCompleted), name + ': taskCompleted required');
      assert(row.taskCompleted !== true || end !== null, name + ': completion requires session end');
      assert([null, 'none', 'yes', 'unknown'].includes(row.interveningStudy), name + ': interveningStudy required');
      assert(Array.isArray(row.evidenceRefs) && row.evidenceRefs.every(hasText), name + ': evidenceRefs required');
      assert(row.conversationId === null || Number.isSafeInteger(row.conversationId) && row.conversationId > 0, name + ': invalid conversationId');
      if (row.taskCompleted !== null) { metrics.tasksObserved++; metrics.tasksCompleted += Number(row.taskCompleted); }
      count(row.helpCount, name + '.helpCount');
      if (row.helpCount !== null) { metrics.helpSamples++; metrics.helpCount += row.helpCount; }
      for (const prefix of ['citation', 'feedback']) {
        const checkedKey = prefix === 'citation' ? 'citationsChecked' : 'feedbackChecked';
        const checked = row[checkedKey], errors = row[prefix + 'Errors'];
        count(checked, name + '.' + checkedKey); count(errors, name + '.' + prefix + 'Errors');
        assert((checked === null) === (errors === null), name + ': error count and denominator must both be recorded');
        assert(errors === null || errors <= checked, name + ': errors exceed checked items');
        if (checked !== null) {
          assert(checked === 0 || row.evidenceRefs.length > 0, name + ': reviewed items require evidenceRefs');
          metrics[checkedKey] += checked; metrics[prefix + 'Errors'] += errors; metrics[prefix + 'Samples']++;
        }
      }
      assert(Array.isArray(row.issues), name + ': issues required');
      for (const issue of row.issues) {
        assert(['high', 'medium', 'low'].includes(issue.severity) &&
          ['citation', 'feedback', 'blocking', 'other'].includes(issue.kind) &&
          hasText(issue.description) && hasText(issue.evidenceRef), name + ': incomplete issue evidence');
        issues.push({ participantId: person.id, topicId: topic.id, ...issue });
      }
      if (row.taskCompleted !== null || row.helpCount !== null || row.citationsChecked !== null || row.feedbackChecked !== null || row.issues.length) {
        assert(start !== null, name + ': operational observations require a started session');
      }
      const scores = {};
      const times = {};
      for (const phase of phases) {
        const trial = row.phases?.[phase];
        assert(trial && [null, true, false].includes(trial.externalHelp), name + '.' + phase + ': help status required');
        times[phase] = timestamp(trial.completedAt, name + '.' + phase);
        exactIds(trial.answers, topic.questions[phase].map(item => item.id), 'questionId', name + '.' + phase);
        for (const answer of trial.answers) {
          assert(answer.response === null || hasText(answer.response), 'Invalid response');
          assert(answer.score === null || Number.isInteger(answer.score) && answer.score >= 0 && answer.score <= 2, 'Score must be 0, 1, 2 or null');
          if (answer.score !== null) assert(hasText(answer.response) && hasText(answer.reason) && hasText(trial.scoredBy)
            && times[phase] !== null, 'Scoring requires response, rubric reason, scorer and completion time');
        }
        if (times[phase] !== null || trial.answers.some(answer => answer.response !== null)) {
          observed = true;
          assert(consent !== null && (times[phase] === null || consent <= times[phase]), name + ': consent must precede collection');
        }
        scores[phase] = times[phase] !== null && trial.externalHelp === false && trial.answers.every(answer => answer.score !== null)
          ? trial.answers.reduce((sum, answer) => sum + answer.score, 0) / (trial.answers.length * 2) * 100 : null;
        if (trial.externalHelp !== false && times[phase] !== null)
          exclusions.push({ participantId: person.id, topicId: topic.id, phase, reason: 'external_help_or_unknown' });
      }
      assert(times.pre === null || start === null || times.pre <= start, name + ': pretest must precede learning');
      assert(times.immediate === null || end !== null && times.immediate >= end, name + ': immediate test must follow learning');
      assert(times.delayed === null || times.immediate !== null && times.delayed >= times.immediate, name + ': delayed test must follow immediate test');
      if (times.immediate !== null && times.immediate - end > material.immediateWindowMinutes * 60000) {
        scores.immediate = null;
        exclusions.push({ participantId: person.id, topicId: topic.id, phase: 'immediate', reason: 'outside_time_window' });
      }
      if (times.delayed !== null) {
        const elapsed = (times.delayed - end) / 3600000;
        if (elapsed < material.delayedWindowHours.min || elapsed > material.delayedWindowHours.max || row.interveningStudy !== 'none') {
          scores.delayed = null;
          exclusions.push({ participantId: person.id, topicId: topic.id, phase: 'delayed',
            reason: elapsed < material.delayedWindowHours.min || elapsed > material.delayedWindowHours.max ? 'outside_time_window' : 'intervening_study_or_unknown' });
        }
      }
      topicScores.push(scores);
      topicRows[topic.id].push({ id: person.id, ...scores });
    }
    if (observed) observedParticipants++;
    // 两主题和多轮题目都是同一学习者的重复测量，不增加独立样本量。
    perParticipant.push({ id: person.id, ...Object.fromEntries(phases.map(phase => [phase,
      topicScores.every(score => score[phase] !== null) ? mean(topicScores.map(score => score[phase])) : null])) });
  }
  const learning = learningSummary(perParticipant);
  const ready = observedParticipants >= material.targetParticipants.min && records.review.status === 'approved'
    && Object.values(records.environment).every(hasText) && records.participants.every(item => item.priorJava !== null)
    && perParticipant.every(item => phases.every(phase => item[phase] !== null));
  return { version: material.version, materialHash: expectedHash, mode: records.mode,
    status: records.mode === 'dry_run' ? 'dry_run' : records.mode === 'self_test' ? 'self_test'
      : observedParticipants === 0 ? 'not_collected' : ready ? 'ready_for_review' : 'in_progress',
    contentReviewStatus: records.review.status, registeredParticipants: records.participants.length, observedParticipants,
    realParticipants: records.mode === 'dry_run' ? 0 : observedParticipants,
    pilotParticipants: records.mode === 'real' ? observedParticipants : 0,
    learningEffectEstablished: false, inference: records.mode === 'self_test' ? 'personal_walkthrough_only'
      : 'descriptive_pilot_without_control', learning, perParticipant, exclusions,
    topicLearning: Object.fromEntries(material.topics.map(topic => [topic.id, learningSummary(topicRows[topic.id])])),
    operations: { ...metrics, taskCompletionRate: metrics.tasksObserved ? metrics.tasksCompleted / metrics.tasksObserved : null,
      citationErrorRate: metrics.citationsChecked ? metrics.citationErrors / metrics.citationsChecked : null,
      feedbackErrorRate: metrics.feedbackChecked ? metrics.feedbackErrors / metrics.feedbackChecked : null },
    issues: issues.sort((a, b) => ['high', 'medium', 'low'].indexOf(a.severity) - ['high', 'medium', 'low'].indexOf(b.severity)) };
}

function externalDestination(file) {
  const target = path.resolve(file);
  const resolved = path.join(realpathSync(path.dirname(target)), path.basename(target));
  const relative = path.relative(realpathSync(projectRoot), resolved);
  assert(relative.startsWith('..' + path.sep) || path.isAbsolute(relative), 'Output must be outside the repository');
  assert(!existsSync(target), 'Output already exists; choose a new destination');
  return target;
}

function questionText(question) {
  return `### ${question.id}\n\n${question.prompt}\n\n\`\`\`java\n${question.setup.map(line => line + ';').join('\n')}\nSystem.out.println(${question.expression});\n\`\`\`\n\n回答：\n\n理由：\n`;
}

function main() {
  const { values, positionals } = parseArgs({ allowPositionals: true, options: {
    'output-dir': { type: 'string' }, input: { type: 'string' }, 'output-file': { type: 'string' },
    mode: { type: 'string', default: 'real' }
  } });
  const raw = readFileSync(materialPath), material = JSON.parse(raw), materialHash = hash(raw);
  if (positionals[0] === 'init') {
    assert(values['output-dir'], '--output-dir required');
    assert(['real', 'dry_run', 'self_test'].includes(values.mode), 'Invalid data mode');
    const output = externalDestination(values['output-dir']);
    mkdirSync(output);
    const records = { version: material.version, materialHash, mode: values.mode,
      review: { status: 'pending', reviewer: null, reviewedAt: null, note: null },
      environment: { appCommit: null, modelRequested: null, modelReported: null, promptVersion: null },
      participants: values.mode === 'self_test' ? [createParticipant(material, 'P01')] : [] };
    writeFileSync(path.join(output, 'records.json'), JSON.stringify(records, null, 2));
    writeFileSync(path.join(output, 'participant-template.json'), JSON.stringify(createParticipant(material, 'P01'), null, 2));
    const facilitator = [];
    for (const topic of material.topics) {
      writeFileSync(path.join(output, topic.id + '-source.txt'), topic.source + '\n');
      writeFileSync(path.join(output, topic.id + '-task.md'), `# ${topic.name}\n\n${topic.learningTask}\n`);
      for (const phase of phases) {
        const questions = topic.questions[phase];
        writeFileSync(path.join(output, `${topic.id}-${phase}.md`), `# ${topic.name} · ${phase}\n\n` +
          '限时5分钟，独立作答，不运行代码、不查看资料或聊天。假设已导入 java.util.*，代码在 main 方法中执行。\n\n' +
          questions.map(questionText).join('\n'));
        facilitator.push(...questions.map(question => `## ${question.id}\n\n输出：${question.expectedOutput}\n\n${question.rubric.join('\n\n')}\n`));
      }
    }
    writeFileSync(path.join(output, 'facilitator-only.md'), '# 仅供评分者使用\n\n' + facilitator.join('\n'));
    writeFileSync(path.join(output, 'findings-template.md'), `# ${values.mode === 'self_test' ? '个人走查，不纳入初学者试点样本' : '阶段四试点人工结论'}\n\n状态：待采集，未形成学习效果结论。\n\n` +
      `材料版本：${material.version}\n\n材料SHA256：${materialHash}\n\n` +
      '## 样本和执行偏差\n\n待实际记录；独立学习者数、失访、缺测、时间窗、额外学习、模型与版本。\n\n' +
      '## 分任务观察\n\n待实际记录；逐主题引用report.json、原回答和人工评分依据，区分流程成功和学习表现。\n\n' +
      '## 失败与改进优先级\n\n待实际记录；每项保留影响、涉及学习者数、证据引用和需回填的阶段。\n\n' +
      '## 结论边界\n\n单组可行性试点；不支持优于普通聊天或教育效果的因果结论。\n');
    writeFileSync(path.join(output, 'report.json'), JSON.stringify(summarizePilot(material, records, materialHash), null, 2));
    console.log('Pilot kit initialized outside repository; no participant observations or model calls.');
  } else if (positionals[0] === 'summarize') {
    assert(values.input && values['output-file'], '--input and --output-file required');
    const records = JSON.parse(readFileSync(values.input, 'utf8'));
    const report = summarizePilot(material, records, materialHash);
    const output = externalDestination(values['output-file']);
    writeFileSync(output, JSON.stringify(report, null, 2));
    console.log(JSON.stringify({ status: report.status, realParticipants: report.realParticipants, learningEffectEstablished: false }));
  } else throw new Error('Use init --output-dir DIR [--mode real|dry_run|self_test] or summarize --input FILE --output-file FILE');
}

if (process.argv[1] && import.meta.url === pathToFileURL(path.resolve(process.argv[1])).href) {
  try { main(); } catch (error) { console.error(error.message); process.exitCode = 1; }
}
