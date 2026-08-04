import { defineStore } from 'pinia'
import { computed, ref } from 'vue'
import {
  createConversation,
  getProfile,
  listConversations,
  listMessages,
  saveProfile as saveProfileApi,
  sendAiChat
} from '../api'
import type { ChatMessage, Conversation, StudentProfile } from '../types/domain'

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
      if (!currentConversationId.value && conversations.value.length > 0) {
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
    loadingMessages.value = true
    try {
      messages.value = await listMessages(conversationId)
    } finally {
      loadingMessages.value = false
    }
  }

  async function sendMessage(content: string) {
    if (!currentConversationId.value) {
      await addConversation('AI 学习问答')
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
      const result = await sendAiChat(conversationId, content)
      appendMessage('assistant', result.answer)
      await loadConversations()
      currentConversationId.value = conversationId
    } finally {
      sending.value = false
    }
  }

  function appendMessage(role: ChatMessage['role'], messageContent: string) {
    messages.value.push({
      role,
      messageContent,
      createTime: new Date().toISOString()
    })
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
  }

  return {
    profile,
    conversations,
    currentConversationId,
    currentConversation,
    messages,
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
    reset
  }
})
