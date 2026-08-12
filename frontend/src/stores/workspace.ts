import { defineStore } from 'pinia'
import { computed, ref } from 'vue'
import {
  createConversation,
  getActiveLearningSession,
  getProfile,
  listConversations,
  listMessages,
  saveProfile as saveProfileApi,
  sendTutorAgentChat
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

  async function loadConversations() {
    loadingConversations.value = true
    try {
      conversations.value = await listConversations()
      if (!currentConversationId.value && !draftConversation.value && conversations.value.length > 0) {
        await selectConversation(conversations.value[0].id)
      }
    } finally {
      loadingConversations.value = false
    }
  }

  async function addConversation(title = '新的学习会话', mode = 'chat') {
    const result = await createConversation(title, mode)
    await loadConversations()
    await selectConversation(result.conversationId)
    return result.conversationId
  }

  async function selectConversation(conversationId: number) {
    currentConversationId.value = conversationId
    draftConversation.value = false
    loadingMessages.value = true
    try {
      const [messageResult, sessionResult] = await Promise.all([
        listMessages(conversationId),
        getActiveLearningSession(conversationId)
      ])
      messages.value = messageResult
      activeLearningSession.value = sessionResult
    } finally {
      loadingMessages.value = false
    }
  }

  async function sendMessage(content: string) {
    if (!currentConversationId.value) {
      await addConversation(createConversationTitle(content))
    }
    if (!currentConversationId.value) {
      return
    }

    const conversationId = currentConversationId.value
    messages.value.push({
      role: 'user',
      messageContent: content,
      createTime: new Date().toISOString()
    })
    sending.value = true
    try {
      const result = await sendTutorAgentChat(conversationId, content, activeLearningSession.value?.id)
      activeLearningSession.value = result.learningSession || null
      appendMessage('assistant', result.answer, result.actions, result.intent, result.memoryUpdates)
      await loadConversations()
      currentConversationId.value = conversationId
    } finally {
      sending.value = false
    }
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
    currentConversationId.value = null
    messages.value = []
    activeLearningSession.value = null
    draftConversation.value = true
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
    loadProfile,
    saveProfile,
    loadConversations,
    addConversation,
    selectConversation,
    sendMessage,
    appendMessage,
    startDraftConversation,
    reset
  }
})
