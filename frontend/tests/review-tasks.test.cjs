const { test } = require('node:test')
const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')
const vm = require('node:vm')
const ts = require('typescript')
const vue = require('vue')
const { parse, compileScript } = require('@vue/compiler-sfc')

function setup(api) {
  const suppliedPage = api.listReviewPage
  api = { ...api, listReviewPage: suppliedPage || (async (_status, page, pageSize) => {
    const items = await (api.listReviews ? api.listReviews() : [])
    return { items, total: items.length, page, pageSize }
  }) }
  const filename = path.join(__dirname, '../src/components/ReviewTasks.vue')
  const script = compileScript(parse(fs.readFileSync(filename, 'utf8')).descriptor, { id: 'review-test' })
  const code = ts.transpileModule(script.content, { compilerOptions: { module: ts.ModuleKind.CommonJS } }).outputText
  const context = { exports: {}, Date, Error, setInterval: () => 1, clearInterval() {}, require: name => {
    if (name === '../api') return api
    if (name === 'vue') return { ...vue, onMounted() {}, onUnmounted() {} }
    if (name === '@element-plus/icons-vue') return {}
    return require(name)
  } }
  vm.runInNewContext(code, context)
  const events = []
  const view = context.exports.default.setup({}, { expose() {}, emit: event => events.push(event) })
  return { view, events }
}
const task = () => ({ id: 1, status: 'pending', dueTime: '2020-01-01T00:00:00' })
const result = score => ({ taskId: 1, evidenceId: 10, score, masteryBefore: 40,
  masteryAfter: score === null ? 40 : 76, feedback: 'Saved feedback', completed: score !== null })

test('completed history can request a page beyond the first 100 tasks', async () => {
  const calls = []
  const { view } = setup({ listReviews: async () => [], listReviewPage: async (...args) => {
    calls.push(args)
    return { items: [{ id: 101 }], total: 121, page: 6, pageSize: 20 }
  } })
  await view.load(false, 6, 'completed')
  assert.deepEqual(calls, [['completed', 6, 20]])
  assert.equal(view.items.value[0].id, 101)
  assert.equal(view.total.value, 121)
  assert.equal(view.page.value, 6)
})

test('review completion reloads persisted task and requests fresh knowledge state', async () => {
  const row = task()
  const { view, events } = setup({ answerReview: async () => result(100), listReviews: async () => [row] })
  view.answers[1] = ' A '
  await view.submit(row)
  assert.equal(row.status, 'completed')
  assert.equal(row.resultEvidenceId, 10)
  assert.equal(view.results[1].masteryAfter, 76)
  assert.equal(view.status.value, 'completed')
  assert.equal(view.page.value, 1)
  assert.equal(view.activeId.value, 1)
  assert.deepEqual(events, ['changed'])
})

test('unscored review retains answer and pending task', async () => {
  const row = task()
  const { view } = setup({ answerReview: async () => result(null) })
  view.answers[1] = 'Need more context'
  await view.submit(row)
  assert.equal(row.status, 'pending')
  assert.equal(view.results[1].score, null)
  assert.equal(view.answers[1], 'Need more context')
})

test('refreshed completed tasks display their saved result', () => {
  const { view } = setup({})
  const saved = result(100)
  assert.equal(view.resultFor({ ...task(), status: 'completed', result: saved }), saved)
  view.results[1] = result(null)
  assert.equal(view.resultFor({ ...task(), status: 'completed', result: saved }), saved)
})

test('failed review stays pending with an explicit error and no invented result', async () => {
  const row = task()
  const { view, events } = setup({ answerReview: async () => { throw new Error('Model unavailable') } })
  view.answers[1] = 'A'
  await view.submit(row)
  assert.equal(row.status, 'pending')
  assert.equal(view.submitErrors[1], 'Model unavailable')
  assert.equal(view.results[1], undefined)
  assert.equal(view.submitting.value, null)
  assert.deepEqual(events, [])
})

test('rapid repeated submissions only send one request', async () => {
  let resolve, calls = 0
  const pending = new Promise(done => { resolve = done })
  const { view } = setup({ answerReview: () => { calls++; return pending }, listReviews: async () => [] })
  view.answers[1] = 'A'
  const first = view.submit(task())
  await view.submit(task())
  assert.equal(calls, 1)
  resolve(result(100))
  await first
})

test('a stale list response cannot replace newer generated tasks', async () => {
  let resolve
  const old = new Promise(done => { resolve = done })
  let calls = 0
  const { view } = setup({ listReviews: () => ++calls === 1 ? old : [{ id: 2 }], generateReviews: async () => [] })
  const first = view.load()
  await view.load(true)
  resolve([{ id: 1 }])
  await first
  assert.equal(view.items.value[0].id, 2)
})

test('newly generated immediate tasks use the response time rather than page opening time', async () => {
  const row = { ...task(), dueTime: new Date(Date.now() - 1000).toISOString() }
  const { view } = setup({ generateReviews: async () => [], listReviews: async () => [row] })
  view.now.value = Date.now() - 60000
  await view.load(true)
  assert.equal(view.isDue(row), true)
})

test('history load failure retains the previous page and can be retried', async () => {
  let failed = true
  const { view } = setup({ listReviewPage: async () => {
    if (failed) throw new Error('Network unavailable')
    return { items: [{ id: 21 }], total: 42, page: 2, pageSize: 20 }
  } })
  view.items.value = [{ id: 1 }]
  await view.load(false, 2, 'completed')
  assert.equal(view.page.value, 1)
  assert.equal(view.items.value[0].id, 1)
  assert.equal(view.error.value, 'Network unavailable')
  failed = false
  await view.load(false, 2, 'completed')
  assert.equal(view.page.value, 2)
  assert.equal(view.items.value[0].id, 21)
  assert.equal(view.error.value, '')
})
