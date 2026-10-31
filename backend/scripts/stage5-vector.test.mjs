import test from 'node:test';
import assert from 'node:assert/strict';
import { retrieveCandidate, compareCases } from './stage5-vector.mjs';

function fixture() {
  return {
    input: { topK: 3, documents: [
      { id: 'selected', chunks: [{ id: 'A', text: 'alpha' }, { id: 'B', text: 'beta' }, { id: 'C', text: 'gamma' }] },
      { id: 'hidden', chunks: [{ id: 'H', text: 'hidden' }] },
    ], cases: [{ id: 'Q', documents: ['selected'], question: 'query' }] },
    lexical: [{ caseId: 'Q', resolvedQuestion: 'resolved query', clarification: null, retrieved: ['B', 'A'], elapsedMs: 1 }],
    embeddings: { documents: [
      { id: 'A', vector: [1, 0] }, { id: 'B', vector: [0, 1] },
      { id: 'C', vector: [0.8, 0.6] }, { id: 'H', vector: [1, 0] },
    ], queries: [{ id: 'Q', text: 'resolved query', vector: [1, 0], elapsedMs: 2 }] },
  };
}
const run = (f, options = {}) => retrieveCandidate(f.input, f.lexical, f.embeddings,
  { mode: 'vector', threshold: 0.8, rrfK: 60, ...options });

test('cosine threshold includes its boundary, ranks matches, and excludes an unselected perfect match', () => {
  const result = run(fixture());
  assert.deepEqual(result[0].retrieved, ['A', 'C']);
  assert.deepEqual(result[0].vectorRanking.map(({id, score}) => ({id, score})), [{ id: 'A', score: 1 }, { id: 'C', score: 0.8 }]);
  assert.deepEqual(run(fixture(), { threshold: 0.81 })[0].retrieved, ['A']);
});

test('equal-weight reciprocal rank fusion combines ranks without comparing lexical and cosine scores', () => {
  assert.deepEqual(run(fixture(), { mode: 'hybrid' })[0].retrieved, ['A', 'B', 'C']);
  const f = fixture(); f.input.topK = 2;
  assert.deepEqual(run(f, { mode: 'hybrid' })[0].retrieved, ['A', 'B']);
});

test('no vector match stays empty for dense retrieval and preserves lexical results for hybrid', () => {
  const f = fixture(); f.embeddings.queries[0].vector = [-1, 0];
  assert.deepEqual(run(f)[0].retrieved, []);
  assert.deepEqual(run(f, { mode: 'hybrid' })[0].retrieved, ['B', 'A']);
});

test('shared clarification bypasses vectors and never guesses an ambiguous referent', () => {
  const f = fixture(); f.lexical[0].clarification = '请明确对象'; f.lexical[0].retrieved = [];
  f.embeddings.queries = [];
  assert.deepEqual(run(f)[0].retrieved, []);
  assert.equal(run(f)[0].clarification, '请明确对象');
});

test('rejects invalid, unnormalized, dimensionally inconsistent or mismatched embeddings', () => {
  for (const vector of [[NaN, 0], [0, 0], [2, 0], [1, 0, 0]]) {
    const f = fixture(); f.embeddings.queries[0].vector = vector;
    assert.throws(() => run(f), /vector|dimension/i);
  }
  const f = fixture(); f.embeddings.queries[0].text = 'different query';
  assert.throws(() => run(f), /query/i);
});

test('rejects missing, duplicated or out-of-scope records instead of silently changing the cohort', () => {
  for (const modify of [
    f => { f.lexical = []; }, f => { f.lexical.push(f.lexical[0]); },
    f => { f.embeddings.documents.pop(); }, f => { f.embeddings.documents.push(f.embeddings.documents[0]); },
    f => { f.embeddings.queries = []; }, f => { f.lexical[0].retrieved = ['H']; },
  ]) {
    const f = fixture(); modify(f);
    assert.throws(() => run(f), /case|document|query|scope|duplicate/i);
  }
});

test('paired comparisons report both improvements and regressions and reject changed cohorts', () => {
  const baseline = [{ caseId: 'A', passed: false }, { caseId: 'B', passed: true }, { caseId: 'C', passed: false }];
  const candidate = [{ caseId: 'C', passed: false }, { caseId: 'A', passed: true }, { caseId: 'B', passed: false }];
  assert.deepEqual(compareCases(baseline, candidate), { improved: ['A'], regressed: ['B'], unchanged: ['C'] });
  assert.throws(() => compareCases(baseline, candidate.slice(1)), /case/i);
  assert.throws(() => compareCases(baseline, [candidate[0], candidate[0], candidate[0]]), /case/i);
});
