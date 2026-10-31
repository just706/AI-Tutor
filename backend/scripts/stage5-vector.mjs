import assert from 'node:assert/strict';
import { performance } from 'node:perf_hooks';

function sameIds(expected, actual, label) {
  assert.equal(new Set(expected).size, expected.length, `Duplicate expected ${label}`);
  assert.equal(new Set(actual).size, actual.length, `Duplicate ${label}`);
  assert.deepEqual([...actual].sort(), [...expected].sort(), `Missing or extra ${label}`);
}

function normalizedVector(vector, dimensions) {
  assert(Array.isArray(vector) && vector.length === dimensions && vector.every(Number.isFinite), 'Invalid vector dimensions or values');
  const normSquared = vector.reduce((sum, value) => sum + value * value, 0);
  assert(Math.abs(normSquared - 1) <= 1e-5, 'Vector must be normalized');
}

export function retrieveCandidate(input, lexicalRows, embeddings, { mode, threshold, rrfK }) {
  assert(['vector', 'hybrid'].includes(mode), 'Unknown retrieval mode');
  assert(Number.isFinite(threshold) && threshold >= -1 && threshold <= 1, 'Invalid cosine threshold');
  assert(Number.isSafeInteger(rrfK) && rrfK > 0, 'Invalid RRF constant');
  assert(Number.isSafeInteger(input.topK) && input.topK > 0 && input.topK <= 8, 'Invalid topK');
  sameIds(input.cases.map(item => item.id), lexicalRows.map(row => row.caseId), 'case');
  const chunks = input.documents.flatMap(document => document.chunks.map(chunk => ({ ...chunk, documentId: document.id })));
  sameIds(chunks.map(chunk => chunk.id), embeddings.documents.map(item => item.id), 'document embedding');
  sameIds(lexicalRows.filter(row => row.clarification === null).map(row => row.caseId), embeddings.queries.map(item => item.id), 'query embedding');
  const dimensions = embeddings.documents[0]?.vector?.length;
  assert(Number.isSafeInteger(dimensions) && dimensions > 0, 'Empty vector dimensions');
  for (const item of [...embeddings.documents, ...embeddings.queries]) normalizedVector(item.vector, dimensions);
  const documentVectors = new Map(embeddings.documents.map(item => [item.id, item.vector]));
  const queries = new Map(embeddings.queries.map(item => [item.id, item]));
  return input.cases.map(item => {
    const started = performance.now();
    const lexical = lexicalRows.find(row => row.caseId === item.id);
    assert(Number.isFinite(lexical.elapsedMs) && lexical.elapsedMs >= 0, 'Invalid lexical latency');
    assert(lexical.clarification === null || (typeof lexical.clarification === 'string' && lexical.clarification.trim()), 'Invalid clarification');
    assert(typeof lexical.resolvedQuestion === 'string' && lexical.resolvedQuestion.trim(), 'Missing resolved query');
    const scoped = chunks.filter(chunk => item.documents.includes(chunk.documentId));
    assert(lexical.retrieved.length <= input.topK && new Set(lexical.retrieved).size === lexical.retrieved.length, 'Invalid lexical case');
    assert(lexical.retrieved.every(id => scoped.some(chunk => chunk.id === id)), 'Lexical chunk out of scope');
    if (lexical.clarification !== null) {
      assert.equal(lexical.retrieved.length, 0, 'Clarification case contains chunks');
      return { caseId: item.id, resolvedQuestion: lexical.resolvedQuestion, clarification: lexical.clarification,
        retrieved: [], elapsedMs: lexical.elapsedMs, vectorScores: [], vectorRanking: [], fusionRanking: [] };
    }
    const query = queries.get(item.id);
    assert.equal(query.text, lexical.resolvedQuestion, 'Embedding query differs from Java-resolved query');
    assert(Number.isFinite(query.elapsedMs) && query.elapsedMs >= 0, 'Invalid query latency');
    // Only selected documents enter similarity ranking; embedding the corpus is not permission to retrieve it.
    const vectorScores = scoped.map(chunk => ({ id: chunk.id,
      score: Math.max(-1, Math.min(1, query.vector.reduce((sum, value, index) => sum + value * documentVectors.get(chunk.id)[index], 0))) }))
      .sort((a, b) => b.score - a.score);
    const vectorRanking = vectorScores.filter(item => item.score >= threshold).slice(0, input.topK);
    const fusionScores = new Map();
    if (mode === 'hybrid') {
      for (const ranking of [lexical.retrieved, vectorRanking.map(item => item.id)]) ranking.forEach((id, index) => {
        fusionScores.set(id, (fusionScores.get(id) ?? 0) + 1 / (rrfK + index + 1));
      });
    }
    const fusionRanking = [...fusionScores].map(([id, score]) => ({ id, score }))
      .sort((a, b) => b.score - a.score).slice(0, input.topK);
    const selectionMs = performance.now() - started;
    return { caseId: item.id, resolvedQuestion: lexical.resolvedQuestion, clarification: null,
      retrieved: (mode === 'vector' ? vectorRanking : fusionRanking).map(item => item.id),
      elapsedMs: lexical.elapsedMs + query.elapsedMs + selectionMs,
      timingComponentsMs: { javaPrepass: lexical.elapsedMs, queryEncoding: query.elapsedMs, selection: selectionMs },
      vectorScores, vectorRanking, fusionRanking };
  });
}

export function compareCases(baseline, candidate) {
  sameIds(baseline.map(item => item.caseId), candidate.map(item => item.caseId), 'comparison case');
  const result = { improved: [], regressed: [], unchanged: [] };
  for (const item of baseline) {
    const other = candidate.find(row => row.caseId === item.caseId);
    assert(typeof item.passed === 'boolean' && typeof other.passed === 'boolean', 'Invalid case outcome');
    result[item.passed === other.passed ? 'unchanged' : other.passed ? 'improved' : 'regressed'].push(item.caseId);
  }
  return result;
}
