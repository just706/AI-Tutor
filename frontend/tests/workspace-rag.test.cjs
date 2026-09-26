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
  context.crypto = require('node:crypto').webcrypto
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

test('a failed textbook answer retains one recoverable question', async () => {
  const { store, api } = workspace()
  api.sendRagChat = async () => { throw new Error('connection lost') }
  await store.loadConversations(1)
  await assert.rejects(store.sendMessage('什么是导数？'), /connection lost/)
  assert.equal(store.messages.length, 1)
  assert.equal(store.messages[0].ragStatus, 'uncertain')
  assert.match(store.messages[0].ragRequestId, /^[0-9a-f-]{36}$/)
  assert.equal(store.messages[0].ragAttempt, 1)
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

test('reopening a conversation restores server citations without leaking the previous conversation', async () => {
  const { store, api } = workspace()
  api.listMessages = async id => id === 1 ? [{ role: 'assistant', messageContent: '旧回答', createTime: '2026-09-24', sources: [
    { documentId: 21, fileName: '原教材.txt', chunkIndex: 7, snippet: '回答时的证据', available: true }
  ] }] : []
  await store.loadConversations(1)
  await store.selectConversation(2)
  assert.equal(store.messages.length, 0)
  store.reset()
  await store.loadConversations(1)
  assert.equal(store.messages[0].sources[0].snippet, '回答时的证据')
  assert.equal(store.messages[0].sources[0].chunkIndex, 7)
  assert.equal(store.messages[0].sources[0].available, true)
})

test('refresh restores a failed question and retry reuses its key without adding another question', async () => {
  const { store, api } = workspace()
  const saved = { role: 'user', messageContent: '什么是导数？', createTime: '2026-09-26',
    ragRequestId: '11111111-1111-4111-8111-111111111111', ragStatus: 'failed', ragAttempt: 1 }
  api.listMessages = async () => [{ ...saved }]
  let request
  api.sendRagChat = async (...args) => { request = args; return { answer: '导数表示变化率。', sources: [], attempt: 2 } }
  await store.loadConversations(1)
  await store.retryRagMessage(store.messages[0])
  assert.equal(request[3], saved.ragRequestId)
  assert.equal(request[4], 2)
  assert.equal(store.messages.length, 2)
  assert.equal(store.messages[0].ragStatus, 'completed')
  assert.equal(store.messages[1].messageContent, '导数表示变化率。')
})

test('an answer saved during a lost response is recovered without another model request', async () => {
  const { store, api } = workspace()
  await store.loadConversations(1)
  api.sendRagChat = async (...args) => {
    api.listMessages = async () => [
      { role: 'user', messageContent: args[1], ragRequestId: args[3], ragStatus: 'completed', ragAttempt: 1 },
      { role: 'assistant', messageContent: '已保存的回答', ragRequestId: args[3], sources: [] }
    ]
    throw new Error('connection lost after commit')
  }
  await store.sendMessage('什么是导数？')
  assert.equal(store.messages.length, 2)
  assert.equal(store.messages[1].messageContent, '已保存的回答')
})

test('checking an active request never submits another attempt', async () => {
  const { store, api, calls } = workspace()
  api.listMessages = async () => [{ role: 'user', messageContent: '导数', ragRequestId: 'active', ragStatus: 'processing', ragAttempt: 1 }]
  await store.loadConversations(1)
  await store.retryRagMessage(store.messages[0])
  assert.equal(calls.length, 0)
  assert.equal(store.messages[0].ragStatus, 'processing')
})

test('repeated retry clicks cannot submit concurrently', async () => {
  const { store, api } = workspace()
  api.listMessages = async () => [{ role: 'user', messageContent: '导数', ragRequestId: 'failed', ragStatus: 'failed', ragAttempt: 1 }]
  await store.loadConversations(1)
  let finish
  api.sendRagChat = () => new Promise(resolve => { finish = resolve })
  const message = store.messages[0]
  const retry = store.retryRagMessage(message)
  await new Promise(resolve => setImmediate(resolve))
  await assert.rejects(store.retryRagMessage(message), /等待/)
  finish({ answer: '回答', sources: [] })
  await retry
  assert.equal(store.messages.length, 2)
})

test('a retry result cannot leak into a different account after reset', async () => {
  const { store, api } = workspace()
  api.listMessages = async () => [{ role: 'user', messageContent: '导数', ragRequestId: 'failed', ragStatus: 'failed', ragAttempt: 1 }]
  await store.loadConversations(1)
  let finish
  api.sendRagChat = () => new Promise(resolve => { finish = resolve })
  const pending = store.retryRagMessage(store.messages[0])
  await new Promise(resolve => setImmediate(resolve))
  store.reset()
  finish({ answer: '旧账号的回答', sources: [] })
  await pending
  assert.equal(store.messages.length, 0)
  assert.equal(store.currentConversationId, null)
})
