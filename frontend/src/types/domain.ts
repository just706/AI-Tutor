export interface ApiResult<T> {
  code: number
  message: string
  data: T
}

export interface User {
  id: number
  username: string
  nickname?: string
  role: string
}

export interface RegisterResult {
  userId: number
  username: string
}

export interface LoginResult {
  token: string
  user: User
}

export interface StudentProfile {
  learningDirection?: string
  learningGoal?: string
  currentLevel?: string
  learningPreference?: string
}

export interface Conversation {
  id: number
  title: string
  mode: string
  updateTime: string
}

export interface ConversationCreateResult {
  conversationId: number
}

export interface ChatMessage {
  role: 'user' | 'assistant' | 'system'
  messageContent: string
  createTime: string
}

export interface AiChatResult {
  answer: string
}
