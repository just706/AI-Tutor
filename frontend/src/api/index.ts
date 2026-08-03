import { request } from './http'
import type {
  AiChatResult,
  ChatMessage,
  Conversation,
  ConversationCreateResult,
  LoginResult,
  RegisterResult,
  StudentProfile,
  User
} from '../types/domain'

export function register(username: string, password: string) {
  return request<RegisterResult>({
    url: '/auth/register',
    method: 'POST',
    data: { username, password }
  })
}

export function login(username: string, password: string) {
  return request<LoginResult>({
    url: '/auth/login',
    method: 'POST',
    data: { username, password }
  })
}

export function getCurrentUser() {
  return request<User>({
    url: '/users/me',
    method: 'GET'
  })
}

export function getProfile() {
  return request<StudentProfile | null>({
    url: '/profile',
    method: 'GET'
  })
}

export function saveProfile(profile: StudentProfile) {
  return request<boolean>({
    url: '/profile',
    method: 'PUT',
    data: profile
  })
}

export function createConversation(title: string, mode = 'chat') {
  return request<ConversationCreateResult>({
    url: '/conversations',
    method: 'POST',
    data: { title, mode }
  })
}

export function listConversations() {
  return request<Conversation[]>({
    url: '/conversations',
    method: 'GET'
  })
}

export function listMessages(conversationId: number) {
  return request<ChatMessage[]>({
    url: `/conversations/${conversationId}/messages`,
    method: 'GET'
  })
}

export function sendAiChat(conversationId: number, message: string) {
  return request<AiChatResult>({
    url: '/ai/chat',
    method: 'POST',
    data: { conversationId, message }
  })
}
