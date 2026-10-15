const { test } = require('node:test')
const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')
const vm = require('node:vm')
const ts = require('typescript')
const vue = require('vue')
const compiler = require('@vue/compiler-sfc')

const point = (id, subject, name) => ({ id, subject, name, parentId: 0, sortOrder: 0, children: [] })
const catalog = [point(1, 'Java', 'ArrayList'), point(201, '数学', '导数'), point(301, '物理', '速度')]

// 执行真实页面的 setup 和响应式逻辑；仅替换 HTTP、路由及消息提示，不模拟业务方法。
function page(name, query = {}, overrides = {}) {
  const route = vue.reactive({ query: { ...query } })
  const navigation = [], calls = [], mounted = [], disposed = [], notices = []
  const router = {
    push: async target => { navigation.push(target) },
    replace: async target => { route.query = { ...target.query }; await vue.nextTick() }
  }
  const api = {
    listKnowledgeTree: async subject => { calls.push(['tree', subject]); return catalog.filter(p => !subject || p.subject === subject) },
    listQuestions: async id => { calls.push(['questions', id]); return [{ id: id + 10, knowledgePointId: id, content: `题目${id}`, options: [] }] },
    listLearningRecords: async () => [],
    getKnowledgeGraph: async subject => { calls.push(['graph', subject]); return { subject, nodes: [], edges: [] } },
    getPersonalGraph: async () => ({ nodes: [], edges: [] }),
    ...overrides
  }
  const workspace = vue.reactive({ messages: [], activeLearningSession: null, currentConversation: null, currentConversationId: null })
  const cache = new Map()
  function load(file) {
    if (cache.has(file)) return cache.get(file)
    const raw = fs.readFileSync(file, 'utf8')
    const source = file.endsWith('.vue') ? compiler.compileScript(compiler.parse(raw).descriptor, { id: 'test' }).content : raw
    const code = ts.transpileModule(source, { compilerOptions: { module: ts.ModuleKind.CommonJS, target: ts.ScriptTarget.ES2022 } }).outputText
    const exports = {}
    const localRequire = spec => {
      if (spec === 'vue') return { ...vue, onMounted: fn => mounted.push(fn), onUnmounted: fn => disposed.push(fn) }
      if (spec === 'vue-router') return { useRoute: () => route, useRouter: () => router }
      if (spec.endsWith('/api') || spec === '../api') return api
      if (spec.endsWith('/stores/workspace')) return { useWorkspaceStore: () => workspace }
      if (spec === 'element-plus') return { ElMessage: Object.fromEntries(['error', 'warning', 'success'].map(k => [k, m => notices.push(m)])) }
      if (spec === '@element-plus/icons-vue') return {}
      if (spec.endsWith('/markdown')) return { renderMarkdown: text => text }
      if (spec.endsWith('.vue')) return {}
      if (spec.startsWith('.')) return load(path.resolve(path.dirname(file), spec + '.ts'))
      return require(spec)
    }
    vm.runInNewContext(code, { exports, require: localRequire, console, setTimeout, clearTimeout })
    cache.set(file, exports)
    return exports
  }
  const scope = vue.effectScope()
  const view = scope.run(() => load(path.join(__dirname, '../src/views', name + '.vue')).default.setup({}, { expose() {} }))
  const settle = async () => { for (let i = 0; i < 5; i++) { await vue.nextTick(); await Promise.resolve() } }
  return { view, route, navigation, calls, api, workspace, notices, settle,
    mount: async () => { for (const fn of mounted) await fn(); await settle() },
    close: () => { disposed.forEach(fn => fn()); scope.stop() } }
}

test('practice opens a math knowledge point without loading or selecting Java', async t => {
  const p = page('PracticeView', { subject: '数学', knowledgePointId: '201' }); t.after(p.close)
  await p.mount()
  assert.equal(p.view.selectedKnowledgePoint.value?.name, '导数')
  assert.equal(p.view.questions.value[0]?.knowledgePointId, 201)
  assert(!p.calls.some(([type, value]) => type === 'tree' && value === 'Java'))
})

test('graph to learning path keeps the selected subject', async t => {
  const p = page('KnowledgeGraphView', { subject: '数学' }); t.after(p.close)
  await p.mount(); p.view.goLearningPath(201)
  assert.equal(p.navigation.at(-1).query.subject, '数学')
})

for (const name of ['PracticeView', 'LearningPathView']) {
  test(`${name} leaves a direct visit unselected and keeps unknown subjects empty`, async t => {
    const p = page(name); t.after(p.close); await p.mount()
    assert.equal(p.view.selectedKnowledgePoint.value, null)
    assert.equal(p.view.knowledgeSubject.value, '')
    assert.equal(p.view.knowledgeTree.value.length, 0)
    assert.match(p.view.selectionNotice.value, /选择学科/)
    await p.view.selectSubject('天文学'); await p.settle()
    assert.equal(p.route.query.subject, '天文学')
    assert.equal(p.view.knowledgeTree.value.length, 0)
    assert.match(p.view.selectionNotice.value, /暂无/)
    // Element Plus 的单选清除事件默认传入 undefined。
    await p.view.selectSubject(undefined); await p.settle()
    assert.equal(p.view.selectedKnowledgePoint.value, null)
    assert.equal(p.view.knowledgeSubject.value, '')
  })

  test(`${name} resolves legacy ID links from the actual point and rejects conflicting subjects`, async t => {
    const p = page(name, { knowledgePointId: '201' }); t.after(p.close); await p.mount()
    assert.equal(p.view.knowledgeSubject.value, '数学')
    assert.equal(p.view.selectedKnowledgePoint.value?.name, '导数')
    p.route.query = { subject: '物理', knowledgePointId: '201' }; await p.settle()
    assert.equal(p.view.selectedKnowledgePoint.value, null)
    assert.match(p.view.selectionNotice.value, /匹配/)
    p.route.query = { knowledgePointId: '999', topic: '未知规则' }; await p.settle()
    assert.equal(p.view.selectedKnowledgePoint.value, null)
    assert.equal(p.view.knowledgeSubject.value, '')
  })
}

test('math learning and practice links survive a reload and a round trip', async t => {
  const learn = page('LearningPathView', { subject: '数学', knowledgePointId: '201' }); t.after(learn.close)
  await learn.mount(); await learn.view.goPractice()
  const target = learn.navigation.at(-1)
  assert.equal(target.query.subject, '数学'); assert.equal(target.query.knowledgePointId, '201')
  const practice = page('PracticeView', target.query); t.after(practice.close)
  await practice.mount(); practice.view.goLearningPath()
  assert.equal(practice.navigation.at(-1).query.subject, '数学')
  assert.equal(practice.view.selectedKnowledgePoint.value.name, '导数')
})

test('switching subjects clears answers and ignores questions arriving from the old subject', async t => {
  let finishMath
  const p = page('PracticeView', { subject: '数学', knowledgePointId: '201' }, {
    listQuestions: id => id === 201 ? new Promise(resolve => { finishMath = resolve }) : Promise.resolve([{ id: 311, knowledgePointId: 301, content: '速度题', options: [] }])
  }); t.after(p.close); await p.mount()
  p.view.answerDrafts[211] = 'A'; p.view.answerResults[211] = { feedback: '旧反馈' }
  await p.view.selectSubject('物理'); await p.settle()
  assert.equal(p.view.questions.value.length, 0)
  assert.equal(Object.keys(p.view.answerDrafts).length, 0)
  assert.equal(Object.keys(p.view.answerResults).length, 0)
  await p.view.selectKnowledgePoint(catalog[2]); await p.settle()
  finishMath([{ id: 211, knowledgePointId: 201, content: '旧数学题', options: [] }]); await p.settle()
  assert.equal(p.view.questions.value[0].knowledgePointId, 301)
  assert.equal(p.view.selectedKnowledgePoint.value.name, '速度')
})

test('free topic practice retains the subject in navigation and ignores stale generation', async t => {
  let finish
  const p = page('PracticeView', { subject: '数学', topic: '极限' }, {
    generateAiPractice: () => new Promise(resolve => { finish = resolve })
  }); t.after(p.close); await p.mount()
  p.view.goLearningPath()
  assert.equal(p.navigation.at(-1).query.subject, '数学')
  assert.equal(p.navigation.at(-1).query.topic, '极限')
  const generation = p.view.generateQuestionFlow()
  await p.view.selectSubject('物理'); await p.settle()
  finish({ questions: [{ temporaryId: 1, topic: '极限' }] }); await generation
  assert.equal(p.view.aiQuestions.value.length, 0)
  assert.equal(p.view.activeTopic.value, '')
})

test('switching learning points removes old teaching and cannot grade into the previous lesson', async t => {
  let graded = false
  const p = page('LearningPathView', { subject: '数学', knowledgePointId: '201' }, {
    evaluateTeaching: async () => { graded = true }
  }); t.after(p.close); await p.mount()
  p.view.teachingResult.value = { conversationId: 9, knowledgePointId: 201, teachingContent: '导数' }
  p.view.evaluationResult.value = { feedback: '旧反馈' }; p.view.teachingAnswer.value = '旧答案'
  await p.view.selectKnowledgePoint(catalog[2]); await p.settle()
  assert.equal(p.view.teachingResult.value, null); assert.equal(p.view.evaluationResult.value, null)
  assert.equal(p.view.teachingAnswer.value, '')
  p.view.teachingAnswer.value = '新的回答'
  await p.view.evaluateTeachingFlow(); assert.equal(graded, false)
})

test('late AI learning paths cannot overwrite a newly selected subject', async t => {
  let finish
  const p = page('LearningPathView', { subject: '数学', topic: '极限' }, {
    generateAiPath: () => new Promise(resolve => { finish = resolve })
  }); t.after(p.close); await p.mount()
  const pending = p.view.generateAiPathFlow()
  await p.view.selectSubject('物理'); await p.settle()
  finish({ topic: '极限', steps: [] }); await pending
  assert.equal(p.view.aiGeneratedPath.value, null)
})

test('graph does not request a default subject, and a delayed graph cannot replace a newer one', async t => {
  let finish
  const p = page('KnowledgeGraphView', {}, {
    getKnowledgeGraph: subject => subject === '数学' ? new Promise(resolve => { finish = resolve }) : Promise.resolve({ subject, nodes: [], edges: [] })
  }); t.after(p.close); await p.mount()
  assert.equal(p.view.graph.title, '请选择学科'); assert.equal(finish, undefined)
  p.view.subjectInput.value = '数学'; await p.view.applyGraphFilter(); await p.settle()
  assert.equal(p.route.query.subject, '数学')
  p.view.subjectInput.value = '物理'; await p.view.applyGraphFilter(); await p.settle()
  finish({ subject: '数学', nodes: [], edges: [] }); await p.settle()
  assert.equal(p.view.graph.title, '物理')
  p.view.subjectInput.value = ''; await p.view.applyGraphFilter(); await p.settle()
  assert.equal(p.view.graph.title, '请选择学科'); assert.equal(p.view.graph.nodes.length, 0)
})

test('chat keeps a known subject and does not invent Java for an unknown topic', async t => {
  const p = page('ChatView', { topic: '天体运动' }); t.after(p.close)
  p.view.goKnowledgeGraph()
  assert.equal(p.navigation.at(-1).query.subject, undefined)
  p.workspace.activeLearningSession = { topic: '导数', knowledgeMap: { knowledgePointId: 201, subject: '数学', unmetPrerequisites: [] } }
  p.view.goPractice()
  assert.equal(p.navigation.at(-1).query.subject, '数学')
  p.view.goLearningPath(201)
  assert.equal(p.navigation.at(-1).query.subject, '数学')
  p.view.goKnowledgeGraph()
  assert.equal(p.navigation.at(-1).query.subject, '数学')
})

test('knowledge catalog API omits the subject filter when loading available subjects', async () => {
  const source = fs.readFileSync(path.join(__dirname, '../src/api/index.ts'), 'utf8')
  const code = ts.transpileModule(source, { compilerOptions: { module: ts.ModuleKind.CommonJS, target: ts.ScriptTarget.ES2022 } }).outputText
  const sent = [], exports = {}
  vm.runInNewContext(code, { exports, require: () => ({ request: async request => { sent.push(request); return [] } }) })
  await exports.listKnowledgeTree(); await exports.listKnowledgeTree('数学')
  assert.equal(sent[0].params.subject, undefined)
  assert.equal(sent[1].params.subject, '数学')
})

test('free topic generation includes the selected subject in both learning and practice', async t => {
  const requests = []
  const learn = page('LearningPathView', { subject: '物理', topic: '功' }, {
    generateAiPath: async topic => { requests.push(topic); return { topic, steps: [] } }
  }); t.after(learn.close); await learn.mount(); await learn.view.generateAiPathFlow()
  const practice = page('PracticeView', { subject: '物理', topic: '功' }, {
    generateAiPractice: async topic => { requests.push(topic); return { questions: [] } }
  }); t.after(practice.close); await practice.mount(); await practice.view.generateQuestionFlow()
  assert.deepEqual(requests, ['物理：功', '物理：功'])
})

test('an empty learning catalog still allows entering a topic and opening matching practice', async t => {
  const p = page('LearningPathView', { subject: '天文学' }); t.after(p.close); await p.mount()
  p.view.topicInput.value = '恒星演化'
  p.view.useTopicInput(); await p.settle()
  assert.equal(p.view.chatTopic.value, '恒星演化')
  assert.equal(p.view.selectedKnowledgePoint.value, null)
  await p.view.goPractice()
  assert.equal(p.navigation.at(-1).query.subject, '天文学')
  assert.equal(p.navigation.at(-1).query.topic, '恒星演化')
})

test('a late answer cannot add feedback after switching subjects', async t => {
  let finish
  const p = page('PracticeView', { subject: '数学', knowledgePointId: '201' }, {
    submitAnswer: () => new Promise(resolve => { finish = resolve })
  }); t.after(p.close); await p.mount()
  p.view.answerDrafts[211] = 'A'
  const pending = p.view.submitAnswerFlow(p.view.questions.value[0])
  await p.view.selectKnowledgePoint(catalog[2]); await p.settle()
  finish({ feedback: '导数题反馈', score: 100 }); await pending
  assert.equal(Object.keys(p.view.answerResults).length, 0)
  assert.equal(p.view.questions.value[0].knowledgePointId, 301)
})
