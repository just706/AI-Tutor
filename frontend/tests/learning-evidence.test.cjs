const assert = require('node:assert/strict')
const { test } = require('node:test')
const fs = require('node:fs')
const path = require('node:path')
const vm = require('node:vm')
const ts = require('typescript')
const vue = require('vue')
const { parse, compileScript } = require('@vue/compiler-sfc')
const { renderToString } = require('@vue/server-renderer')

async function renderEvidence(rows, error) {
  const filename = path.join(__dirname, '../src/components/LearningEvidenceList.vue')
  const script = compileScript(parse(fs.readFileSync(filename, 'utf8'), { filename }).descriptor,
    { id: 'evidence-test', inlineTemplate: true })
  const code = ts.transpileModule(script.content, {
    compilerOptions: { module: ts.ModuleKind.CommonJS, target: ts.ScriptTarget.ES2022 }
  }).outputText
  const mounted = [], calls = [], context = { exports: {}, Error, require: spec => {
    if (spec === 'vue') return { ...vue, onMounted: fn => mounted.push(fn) }
    if (spec === '../api') return { listLearningEvidence: async (...args) => {
      calls.push(args)
      if (error) throw new Error(error)
      return rows
    } }
    if (spec === '@element-plus/icons-vue') return { Refresh: { render: () => null }, ArrowRight: { render: () => null } }
    return require(spec)
  } }
  vm.runInNewContext(code, context)
  let render
  const app = vue.createSSRApp({ setup() {
    render = context.exports.default.setup({ points: [] }, { expose() {} })
    vue.onServerPrefetch(async () => { for (const fn of mounted) await fn() })
    return render
  } })
  const wrapper = { setup: (_, { slots }) => () => vue.h('span', slots.default?.()) }
  for (const name of ['el-select', 'el-option', 'el-tooltip', 'el-button', 'el-tag', 'el-icon']) app.component(name, wrapper)
  app.component('el-alert', { props: ['title'], setup: props => () => vue.h('p', props.title) })
  app.directive('loading', { getSSRProps: () => ({}) })
  return { html: await renderToString(app), calls }
}

const evidence = overrides => ({
  id: 12, knowledgePointId: 2, sourceType: 'question', assessmentType: 'objective',
  snapshot: JSON.stringify({ knowledgePointName: 'Parity', question: 'Which value is even?', options: ['2', '3'], referenceAnswer: 'A' }),
  studentAnswer: 'B', feedback: '2 is even.', score: 0, masteryBefore: 100, masteryAfter: 40,
  priorEvidenceId: 11, reason: 'Recent incorrect answer', ruleVersion: 'recent-evidence-v1',
  createTime: '2026-10-21T07:00:00', ...overrides
})

test('evidence renders the saved question, grading method and state transition', async () => {
  const { html, calls } = await renderEvidence([evidence()])
  assert.match(html, /Which value is even\?/)
  assert.match(html, /答案键比对/)
  assert.match(html, /100 → 40/)
  assert.match(html, /上次评分 #11/)
  assert.match(html, /Recent incorrect answer/)
  assert.equal(calls[0][1], 100)
})

test('unscored model evidence stays unscored and snapshot markup is escaped', async () => {
  const { html } = await renderEvidence([evidence({ assessmentType: 'model', score: null,
    masteryAfter: 100, feedback: '<script>unsafe()</script>', studentAnswer: '<img src=x onerror=unsafe()>' })])
  assert.match(html, /模型评价/)
  assert.match(html, /未评分/)
  assert.match(html, /100 → 100/)
  assert(!html.includes('<script>'))
  assert(!html.includes('<img'))
  assert(html.includes('&lt;script&gt;'))
})

test('evidence load failure displays an error instead of invented empty results', async () => {
  const { html } = await renderEvidence([], 'Evidence unavailable')
  assert.match(html, /Evidence unavailable/)
  assert(!html.includes('尚无可追溯评价'))
})
