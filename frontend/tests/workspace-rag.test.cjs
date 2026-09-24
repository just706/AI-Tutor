const assert = require('node:assert/strict')
const { test } = require('node:test')
const fs = require('node:fs')
const path = require('node:path')
const vm = require('node:vm')
const ts = require('typescript')
const { createPinia, setActivePinia } = require('pinia')

function workspace() {
  const conversations = [
    { id: 1, title: '教材会话', mode: 'rag', documentIds: [21], updateTime: '2026-09-23' },
    { id: 2, title: '普通会话', mode: 'chat', documentIds: [], updateTime: '2026-09-23' }
  ]
  const calls = []
  const api = {
    listConversations: async () => conversations.map(item => ({ ...item })),
    getConversation: async id => ({ ...conversations.find(item => item.id === id) }),
    listMessages: async () => [],
    getActiveLearningSession: async () => null,
    sendRagChat: async (id, question) => {
      calls.push(['rag', id, question])
      return { conversationId: id, answer: '选择 ArrayList。', sources: [{ documentId: 21, fileName: 'Java.txt', chunkIndex: 0, snippet: '索引访问快' }] }
    },
    sendTutorAgentChat: async (id, question) => {
      calls.push(['tutor', id, question])
      return { answer: '普通讲解', actions: [], memoryUpdates: [], learningSession: null }
    },
    updateConversationDocuments: async (id, ids) => {
      const conversation = conversations.find(item => item.id === id)
      conversation.documentIds = [...ids]
      return { ...conversation }
    }
  }
  const source = fs.readFileSync(path.join(__dirname, '../src/stores/workspace.ts'), 'utf8')
  const code = ts.transpileModule(source, { compilerOptions: { module: ts.ModuleKind.CommonJS, target: ts.ScriptTarget.ES2022 } }).outputText
  const context = { exports: {}, require: name => name === '../api' ? api : require(name) }
  vm.runInNewContext(code, context)
  setActivePinia(createPinia())
  return { store: context.exports.useWorkspaceStore(), api, calls, conversations }
}

test('a textbook conversation uses RAG and keeps source evidence on its answer', async () => {
  const { store, calls } = workspace()
  await store.loadConversations()
  await store.selectConversation(1)
  await store.sendMessage('经常按下标读取元素，应该选哪一种？')
  assert.deepEqual(calls, [['rag', 1, '经常按下标读取元素，应该选哪一种？']])
  assert.equal(store.messages.at(-1).sources[0].documentId, 21)
  assert.equal(store.activeLearningSession, null)
})

test('ordinary conversations still use the tutor endpoint', async () => {
  const { store, calls } = workspace()
  await store.loadConversations()
  await store.selectConversation(2)
  await store.sendMessage('解释一下 Java')
  assert.deepEqual(calls, [['tutor', 2, '解释一下 Java']])
  assert.equal(store.messages.at(-1).messageContent, '普通讲解')
})

test('a refreshed page restores the requested conversation rather than the first one', async () => {
  const { store } = workspace()
  await store.loadConversations(2)
  assert.equal(store.currentConversationId, 2)
  assert.equal(store.currentConversation.mode, 'chat')
})

test('a late RAG reply cannot appear in another selected conversation', async () => {
  const { store, api } = workspace()
  let resolveReply
  api.sendRagChat = () => new Promise(resolve => { resolveReply = resolve })
  api.sendTutorAgentChat = api.sendRagChat
  await store.loadConversations()
  const pending = store.sendMessage('ArrayList')
  await new Promise(resolve => setImmediate(resolve))
  await store.selectConversation(2)
  resolveReply({ conversationId: 1, answer: '这是教材会话的回答', sources: [] })
  await pending
  assert.equal(store.currentConversationId, 2)
  assert.equal(store.messages.length, 0)
})

test('changing textbooks saves the selection and reload restores it', async () => {
  const { store } = workspace()
  await store.loadConversations()
  await store.setConversationDocuments([22, 23])
  await store.selectConversation(2)
  await store.selectConversation(1)
  assert.deepEqual(Array.from(store.currentConversation.documentIds), [22, 23])
})

test('an empty saved selection never sends an ungrounded answer request', async () => {
  const { store, conversations, calls } = workspace()
  conversations[0].documentIds = []
  await store.loadConversations()
  await assert.rejects(store.sendMessage('ArrayList'), /选择教材/)
  assert.equal(calls.length, 0)
  assert.equal(store.messages.length, 0)
})
