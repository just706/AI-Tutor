import { request } from './http'
import type {
  AgentEventLog,
  AgentSuggestion,
  AiGeneratedPath,
  AiPractice,
  AiChatResult,
  AnswerResult,
  ChatMessage,
  Conversation,
  ConversationCreateResult,
  DocumentChunk,
  DocumentUploadResult,
  EvaluationOverview,
  KnowledgeGraph,
  KnowledgePoint,
  KnowledgePointProgress,
  LearningSession,
  LearnerMemory,
  LearningDocument,
  LearningAnalysisOverview,
  LearningDocumentDetail,
  LearningRecord,
  LoginResult,
  OrchestratorChatResult,
  PersonalGraph,
  PersonalGraphExtraction,
  Question,
  RagChatResult,
  RecentAnswerAnalysis,
  RegisterResult,
  StudentProfile,
  StudyPlan,
  TeachingEvaluationResult,
  TeachingStartResult,
  TutorAgentChatResult,
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

export function sendOrchestratorChat(conversationId: number, message: string) {
  return request<OrchestratorChatResult>({
    url: '/ai/orchestrator/chat',
    method: 'POST',
    data: { conversationId, message }
  })
}

export function sendTutorAgentChat(conversationId: number, message: string, learningSessionId?: number) {
  return request<TutorAgentChatResult>({
    url: '/tutor-agent/chat',
    method: 'POST',
    data: { conversationId, message, learningSessionId }
  })
}

export function getActiveLearningSession(conversationId: number) {
  return request<LearningSession | null>({
    url: '/learning-sessions/active',
    method: 'GET',
    params: { conversationId }
  })
}

export function closeLearningSession(learningSessionId: number) {
  return request<LearningSession>({
    url: `/learning-sessions/${learningSessionId}/close`,
    method: 'POST'
  })
}

export function listLearnerMemories() {
  return request<LearnerMemory[]>({
    url: '/learner-memories',
    method: 'GET'
  })
}

export function suppressLearnerMemory(memoryId: number) {
  return request<boolean>({
    url: `/learner-memories/${memoryId}/suppress`,
    method: 'PATCH'
  })
}

export function deleteLearnerMemory(memoryId: number) {
  return request<boolean>({
    url: `/learner-memories/${memoryId}`,
    method: 'DELETE'
  })
}

export function listKnowledgeTree(subject = 'Java') {
  return request<KnowledgePoint[]>({
    url: '/knowledge-points/tree',
    method: 'GET',
    params: { subject }
  })
}

export function getKnowledgeGraph(subject = 'Java') {
  return request<KnowledgeGraph>({
    url: '/knowledge-map/graph',
    method: 'GET',
    params: { subject }
  })
}

export function createPersonalGraphExtraction(documentId: number) {
  return request<PersonalGraphExtraction>({
    url: '/personal-graph/extractions',
    method: 'POST',
    data: { documentId }
  })
}

export function getPersonalGraphExtraction(extractionId: number) {
  return request<PersonalGraphExtraction>({
    url: `/personal-graph/extractions/${extractionId}`,
    method: 'GET'
  })
}

export function getLatestPersonalGraphExtraction(documentId: number) {
  return request<PersonalGraphExtraction | null>({
    url: '/personal-graph/extractions',
    method: 'GET',
    params: { documentId }
  })
}

export function publishPersonalGraphExtraction(extractionId: number) {
  return request<PersonalGraphExtraction>({
    url: `/personal-graph/extractions/${extractionId}/publish`,
    method: 'POST'
  })
}

export function getPersonalGraph(documentId?: number) {
  return request<PersonalGraph>({
    url: '/personal-graph',
    method: 'GET',
    params: { documentId }
  })
}

export function startTeaching(knowledgePointId: number) {
  return request<TeachingStartResult>({
    url: '/teaching/start',
    method: 'POST',
    data: { knowledgePointId }
  })
}

export function evaluateTeaching(conversationId: number, knowledgePointId: number, studentAnswer: string) {
  return request<TeachingEvaluationResult>({
    url: '/teaching/evaluate',
    method: 'POST',
    data: { conversationId, knowledgePointId, studentAnswer }
  })
}

export function listLearningRecords() {
  return request<LearningRecord[]>({
    url: '/teaching/records',
    method: 'GET'
  })
}

export function generateQuestions(knowledgePointId: number, questionType: string, difficulty: string, count = 1) {
  return request<Question[]>({
    url: '/questions/generate',
    method: 'POST',
    data: { knowledgePointId, questionType, difficulty, count }
  })
}

export function listQuestions(knowledgePointId?: number, questionType?: string, difficulty?: string) {
  return request<Question[]>({
    url: '/questions',
    method: 'GET',
    params: { knowledgePointId, questionType, difficulty }
  })
}

export function submitAnswer(questionId: number, answer: string) {
  return request<AnswerResult>({
    url: `/questions/${questionId}/answer`,
    method: 'POST',
    data: { answer }
  })
}

export function uploadDocument(file: File) {
  const formData = new FormData()
  formData.append('file', file)
  return request<DocumentUploadResult>({
    url: '/documents/upload',
    method: 'POST',
    data: formData
  })
}

export function listDocuments() {
  return request<LearningDocument[]>({
    url: '/documents',
    method: 'GET'
  })
}

export function getDocument(documentId: number) {
  return request<LearningDocumentDetail>({
    url: `/documents/${documentId}`,
    method: 'GET'
  })
}

export function listDocumentChunks(documentId: number) {
  return request<DocumentChunk[]>({
    url: `/documents/${documentId}/chunks`,
    method: 'GET'
  })
}

export function reprocessDocument(documentId: number) {
  return request<DocumentUploadResult>({
    url: `/documents/${documentId}/reprocess`,
    method: 'POST'
  })
}

export function deleteDocument(documentId: number) {
  return request<boolean | null>({
    url: `/documents/${documentId}`,
    method: 'DELETE'
  })
}

export function sendRagChat(conversationId: number, question: string, documentIds?: number[]) {
  return request<RagChatResult>({
    url: '/ai/rag/chat',
    method: 'POST',
    data: { conversationId, question, documentIds }
  })
}

export function getLearningAnalysisOverview() {
  return request<LearningAnalysisOverview>({
    url: '/analysis/overview',
    method: 'GET'
  })
}

export function getEvaluationOverview() {
  return request<EvaluationOverview>({
    url: '/evaluation/overview',
    method: 'GET'
  })
}

export function listKnowledgePointProgress() {
  return request<KnowledgePointProgress[]>({
    url: '/analysis/knowledge-points',
    method: 'GET'
  })
}

export function listRecentAnswerAnalysis(limit = 10) {
  return request<RecentAnswerAnalysis[]>({
    url: '/analysis/recent-answers',
    method: 'GET',
    params: { limit }
  })
}

export function generateStudyPlan(period: string, goal?: string) {
  return request<StudyPlan>({
    url: '/study-plans/generate',
    method: 'POST',
    data: { period, goal }
  })
}

export function generateAiPath(topic: string, level?: string, goal?: string, preference?: string) {
  return request<AiGeneratedPath>({
    url: '/ai/path/generate',
    method: 'POST',
    data: { topic, level, goal, preference }
  })
}

export function generateAiPractice(topic: string, questionType: string, difficulty: string, count = 1) {
  return request<AiPractice>({
    url: '/ai/practice/generate',
    method: 'POST',
    data: { topic, questionType, difficulty, count }
  })
}

export function generateAgentSuggestions(agentType = 'all') {
  return request<AgentSuggestion[]>({
    url: '/agents/suggestions/generate',
    method: 'POST',
    data: { agentType }
  })
}

export function listAgentSuggestions(status?: string, agentType?: string) {
  return request<AgentSuggestion[]>({
    url: '/agents/suggestions',
    method: 'GET',
    params: { status, agentType }
  })
}

export function listAgentEvents(suggestionId: number) {
  return request<AgentEventLog[]>({
    url: `/agents/suggestions/${suggestionId}/events`,
    method: 'GET'
  })
}

export function confirmAgentSuggestion(suggestionId: number, note?: string) {
  return request<AgentSuggestion>({
    url: `/agents/suggestions/${suggestionId}/confirm`,
    method: 'POST',
    data: { note }
  })
}

export function completeAgentSuggestion(suggestionId: number, note?: string) {
  return request<AgentSuggestion>({
    url: `/agents/suggestions/${suggestionId}/complete`,
    method: 'POST',
    data: { note }
  })
}

export function dismissAgentSuggestion(suggestionId: number, note?: string) {
  return request<AgentSuggestion>({
    url: `/agents/suggestions/${suggestionId}/dismiss`,
    method: 'POST',
    data: { note }
  })
}
