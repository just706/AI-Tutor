import assert from 'node:assert/strict';
import { performance } from 'node:perf_hooks';

const normalize = value => value.normalize('NFKC').toLowerCase().replace(/\s+/g, '');
const escape = value => value.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');
const personEvents = { 发明者: '发明', 提出者: '提出', 发现者: '发现', 创建者: '创建', 创始人: '创立', 作者: '创作' };

function requirementsFor(question) {
  const requirements = [];
  for (const part of question.split(/[？?；;]+/).map(text => text.trim().replace(/^请问\s*/, '')).filter(Boolean)) {
    let match = part.match(/^(.{1,60}?)的(发明者|提出者|发现者|创建者|创始人|作者)(?:是)?谁$/u);
    if (match) {
      requirements.push({ kind: 'person', subject: match[1], attribute: match[2], event: personEvents[match[2]] });
      continue;
    }
    match = part.match(/^(.{1,60}?)的(?:(发明|提出|发现|创建|成立|出版|发表))?(年份|时间)(?:是)?(?:多少|哪一年|何年)$/u);
    if (match) {
      requirements.push({ kind: 'year', subject: match[1], attribute: match[3], event: match[2] ?? null });
      continue;
    }
    match = part.match(/^(.{1,60}?)(?:的|在.+?中的)(?:实际)?([\p{Script=Han}]{0,12}?(?:数值|结果|阈值|容量上限|容量|倍率|数量|个数|人数|温度|质量|频率|次数|分数|大小|值))(?:是)?(?:多少|几)$/u);
    if (match) requirements.push({ kind: 'quantity', subject: match[1], attribute: match[2], event: null });
  }
  return requirements;
}

// These patterns detect surface cues only. Negation, provenance, anaphora, calculations and
// missing relations can invalidate them; callers must not rename "unverified" to "supported".
function hasCue(requirement, sentence) {
  const text = normalize(sentence);
  if (!text.includes(normalize(requirement.subject))) return false;
  if (requirement.kind === 'person') {
    const attribute = escape(requirement.attribute), event = escape(requirement.event);
    return new RegExp(`${attribute}(?:是|为|:)[\\p{L}·]{2,24}|由[\\p{L}·]{2,24}?${event}`, 'u').test(text);
  }
  if (requirement.kind === 'year') {
    return /(?:\d{3,4}|[零〇一二三四五六七八九]{4})年/u.test(text)
      && (requirement.event === null || text.includes(requirement.event));
  }
  const generic = ['数值', '值', '结果'].includes(requirement.attribute);
  return (generic || text.includes(normalize(requirement.attribute)))
    && /(?:是|为|等于|=|测得)[^\d零〇一二两三四五六七八九十百千万负]{0,8}(?:-?\d+(?:\.\d+)?|[零〇一二两三四五六七八九十百千万负]+)(?![0-9a-z_])/u.test(text);
}

export function inspectEvidence(question, snippets) {
  assert(typeof question === 'string' && question.trim(), 'Missing question');
  assert(Array.isArray(snippets) && snippets.every(text => typeof text === 'string' && text.trim()), 'Invalid evidence snippets');
  const sentences = snippets.flatMap(text => text.split(/[。！？!?；;\r\n]+/).map(sentence => sentence.trim()).filter(Boolean));
  const requirements = requirementsFor(question).map(requirement => ({ ...requirement,
    cues: sentences.filter(sentence => hasCue(requirement, sentence)) }));
  const missing = requirements.filter(requirement => requirement.cues.length === 0)
    .map(({ kind, subject, attribute }) => ({ kind, subject, attribute }));
  return { status: missing.length > 0 ? 'blocked' : 'unverified', requirements, missing };
}

export function applyEvidenceGate(input, rows) {
  assert.equal(new Set(input.cases.map(item => item.id)).size, input.cases.length, 'Duplicate input case');
  assert.equal(rows.length, input.cases.length, 'Missing result case');
  assert.equal(new Set(rows.map(row => row.caseId)).size, rows.length, 'Duplicate result case');
  assert(rows.every(row => input.cases.some(item => item.id === row.caseId)), 'Unknown result case');
  const chunks = new Map();
  for (const document of input.documents) for (const chunk of document.chunks) {
    assert(!chunks.has(chunk.id), 'Duplicate input chunk');
    chunks.set(chunk.id, { ...chunk, documentId: document.id });
  }
  return rows.map(row => {
    const started = performance.now();
    const item = input.cases.find(item => item.id === row.caseId);
    assert(Array.isArray(row.retrieved) && new Set(row.retrieved).size === row.retrieved.length, 'Invalid retrieved chunks');
    assert(row.retrieved.every(id => chunks.has(id) && item.documents.includes(chunks.get(id).documentId)), 'Retrieved chunk out of scope');
    assert(Number.isFinite(row.elapsedMs) && row.elapsedMs >= 0, 'Invalid result timing');
    assert(row.clarification === null || (typeof row.clarification === 'string' && row.clarification.trim()), 'Invalid clarification');
    if (row.clarification !== null) {
      assert.equal(row.retrieved.length, 0, 'Clarification case has retrieved evidence');
      return { ...row, evidenceGate: { status: 'unverified', bypassReason: 'shared_clarification', originalRetrieved: [] } };
    }
    const judgment = inspectEvidence(row.resolvedQuestion, row.retrieved.map(id => chunks.get(id).text));
    const elapsedMs = performance.now() - started;
    return { ...row, retrieved: judgment.status === 'blocked' ? [] : [...row.retrieved], elapsedMs: row.elapsedMs + elapsedMs,
      evidenceGate: { ...judgment, originalRetrieved: [...row.retrieved], elapsedMs } };
  });
}
