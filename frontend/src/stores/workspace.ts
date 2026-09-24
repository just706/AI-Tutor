import { defineStore } from 'pinia'
import { computed, ref } from 'vue'
import {
  closeLearningSession,
  createConversation,
  getActiveLearningSession,
  getConversation,
  getProfile,
  listConversations,
  listMessages,
  saveProfile as saveProfileApi,
  sendTutorAgentChat,
  sendRagChat,
  updateConversationDocuments
} from '../api'
import type { ChatMessage, Conversation, LearningSession, StudentProfile, TutorAction } from '../types/domain'

export const useWorkspaceStore = defineStore('workspace', () => {
  const profile = ref<StudentProfile>({
    learningDirection: '',
    learningGoal: '',
    currentLevel: '',
    learningPreference: ''
  })
  const conversations = ref<Conversation[]>([])
  const currentConversationId = ref<number | null>(null)
  const messages = ref<ChatMessage[]>([])
  const activeLearningSession = ref<LearningSession | null>(null)
  const draftConversation = ref(false)
  const loadingProfile = ref(false)
  const loadingConversations = ref(false)
  const loadingMessages = ref(false)
  const sending = ref(false)
  const savingDocuments = ref(false)
  let selectionVersion = 0
  let lifecycleVersion = 0

  const currentConversation = computed(() =>
    conversations.value.find((item) => item.id === currentConversationId.value) || null
  )

  async function loadProfile() {
    loadingProfile.value = true
    try {
      const result = await getProfile()
      profile.value = {
        learningDirection: result?.learningDirection || '',
        learningGoal: result?.learningGoal || '',
        currentLevel: result?.currentLevel || '',
        learningPreference: result?.learningPreference || ''
      }
    } finally {
      loadingProfile.value = false
    }
  }

  async function saveProfile() {
    await saveProfileApi(profile.value)
  }

  async function loadConversations(preferredId?: number) {
    const lifecycle = lifecycleVersion
    loadingConversations.value = true
    try {
      const result = await listConversations()
      if (lifecycle !== lifecycleVersion) return
      conversations.value = result
      if (preferredId && preferredId !== currentConversationId.value) {
        await selectConversation(preferredId)
      } else if (!currentConversationId.value && !draftConversation.value && conversations.value.length > 0) {
        await selectConversation(conversations.value[0].id)
      }
    } finally {
      loadingConversations.value = false
    }
  }

  async function addConversation(title = '新的学习会话', mode = 'chat', documentIds?: number[]) {
    const result = await createConversation(title, mode, documentIds)
    await loadConversations()
    await selectConversation(result.conversationId)
    return result.conversationId
  }

  async function selectConversation(conversationId: number) {
    const version = ++selectionVersion
    currentConversationId.value = conversationId
    draftConversation.value = false
    messages.value = []
    activeLearningSession.value = null
    loadingMessages.value = true
    try {
      const conversation = await getConversation(conversationId)
      const [messageResult, sessionResult] = await Promise.all([
        listMessages(conversationId),
        conversation.mode === 'rag' ? Promise.resolve(null) : getActiveLearningSession(conversationId)
      ])
      if (version !== selectionVersion) return
      replaceConversation(conversation)
      messages.value = messageResult
      activeLearningSession.value = sessionResult
    } catch (error) {
      if (version === selectionVersion) currentConversationId.value = null
      throw error
    } finally {
      if (version === selectionVersion) loadingMessages.value = false
    }
  }

  async function sendMessage(content: string) {
    if (sending.value || loadingMessages.value || savingDocuments.value) throw new Error('请等待当前操作完成')
    const lifecycle = lifecycleVersion
    sending.value = true
    try {
      if (!currentConversationId.value) await addConversation(createConversationTitle(content))
      const conversationId = currentConversationId.value
      if (!conversationId || lifecycle !== lifecycleVersion) return
      const isRag = currentConversation.value?.mode === 'rag'
      if (isRag && !currentConversation.value?.documentIds?.length) throw new Error('请先为当前会话选择教材')
      appendMessage('user', content)
      if (isRag) {
        const result = await sendRagChat(conversationId, content)
        if (currentConversationId.value === conversationId && lifecycle === lifecycleVersion) {
          activeLearningSession.value = null
          messages.value.push({ role: 'assistant', messageContent: result.answer,
            createTime: new Date().toISOString(), sources: result.sources || [] })
        }
      } else {
        const result = await sendTutorAgentChat(conversationId, content, activeLearningSession.value?.id)
        if (currentConversationId.value === conversationId && lifecycle === lifecycleVersion) {
          activeLearningSession.value = result.learningSession || null
          appendMessage('assistant', result.answer, result.actions, result.intent, result.memoryUpdates)
        }
      }
      if (lifecycle !== lifecycleVersion) return
      await loadConversations()
    } finally {
      sending.value = false
    }
  }

  function replaceConversation(conversation: Conversation) {
    const index = conversations.value.findIndex(item => item.id === conversation.id)
    if (index === -1) conversations.value.unshift(conversation)
    else conversations.value[index] = conversation
  }

  async function setConversationDocuments(documentIds: number[]) {
    const conversationId = currentConversationId.value
    if (!conversationId || currentConversation.value?.mode !== 'rag') throw new Error('请先创建教材会话')
    if (sending.value || savingDocuments.value || loadingMessages.value) throw new Error('请等待当前操作完成')
    const lifecycle = lifecycleVersion
    savingDocuments.value = true
    try {
      const conversation = await updateConversationDocuments(conversationId, documentIds)
      if (lifecycle === lifecycleVersion) replaceConversation(conversation)
    } finally {
      savingDocuments.value = false
    }
  }

  async function completeActiveLearningSession() {
    if (!activeLearningSession.value) {
      return
    }
    await closeLearningSession(activeLearningSession.value.id)
    activeLearningSession.value = null
  }

  function appendMessage(
    role: ChatMessage['role'],
    messageContent: string,
    actions: TutorAction[] = [],
    intent?: string,
    memoryUpdates: string[] = []
  ) {
    messages.value.push({
      role,
      messageContent,
      createTime: new Date().toISOString(),
      actions,
      intent,
      memoryUpdates
    })
  }

  function startDraftConversation() {
    selectionVersion++
    currentConversationId.value = null
    messages.value = []
    activeLearningSession.value = null
    draftConversation.value = true
    loadingMessages.value = false
  }

  function createConversationTitle(content: string) {
    const cleaned = content
      .replace(/[`*_>#()[\]{}]/g, ' ')
      .replace(/\s+/g, ' ')
      .trim()

    if (!cleaned) {
      return '学习问答'
    }

    const firstSentence = cleaned.split(/[。！？!?]/)[0]?.trim() || cleaned
    return firstSentence.length > 24 ? `${firstSentence.slice(0, 24)}...` : firstSentence
  }

  function reset() {
    lifecycleVersion++
    selectionVersion++
    profile.value = {
      learningDirection: '',
      learningGoal: '',
      currentLevel: '',
      learningPreference: ''
    }
    conversations.value = []
    currentConversationId.value = null
    messages.value = []
    activeLearningSession.value = null
    draftConversation.value = false
    loadingMessages.value = false
  }

  return {
    profile,
    conversations,
    currentConversationId,
    currentConversation,
    messages,
    activeLearningSession,
    draftConversation,
    loadingProfile,
    loadingConversations,
    loadingMessages,
    sending,
    savingDocuments,
    loadProfile,
    saveProfile,
    loadConversations,
    addConversation,
    selectConversation,
    sendMessage,
    setConversationDocuments,
    completeActiveLearningSession,
    appendMessage,
    startDraftConversation,
    reset
  }
})
