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
  intent?: string
  actions?: TutorAction[]
}

export interface AiChatResult {
  answer: string
}

export interface TutorAction {
  actionType: string
  title: string
  description: string
  label: string
  routeName: string
  impactLevel: 'low' | 'medium' | 'high'
  payload?: Record<string, unknown>
}

export interface OrchestratorChatResult extends AiChatResult {
  intent: string
  matchedKnowledgePoint?: KnowledgePoint | null
  actions: TutorAction[]
}

export type LearningSessionStatus =
  | 'CREATED'
  | 'DIAGNOSING'
  | 'PLANNING'
  | 'TEACHING'
  | 'PRACTICE'
  | 'REFLECTION'
  | 'COMPLETED'

export interface LearningSession {
  id: number
  conversationId: number
  goal: string
  topic?: string
  intent?: string
  status: LearningSessionStatus
  currentStepType?: string
  teachingStrategy?: string
  strategySource?: string[]
  nextAction?: string
  createTime?: string
  updateTime?: string
  completeTime?: string
}

export interface TutorAgentChatResult extends AiChatResult {
  intent: string
  learningSession?: LearningSession | null
  teachingStrategy?: string
  strategySource: string[]
  toolTraces: string[]
  sources: RagSource[]
  memoryUpdates: string[]
  actions: TutorAction[]
}

export interface KnowledgePoint {
  id: number
  subject: string
  name: string
  parentId: number
  sortOrder: number
  children: KnowledgePoint[]
}

export interface LearningRecord {
  knowledgePointId: number
  knowledgePointName?: string
  subject?: string
  learningStatus: string
  masteryLevel: number
  studyTime: number
  updateTime: string
}

export interface TeachingStartResult {
  conversationId: number
  knowledgePointId: number
  knowledgePointName: string
  teachingContent: string
  learningStatus: string
  masteryLevel: number
}

export interface TeachingEvaluationResult {
  conversationId: number
  knowledgePointId: number
  feedback: string
  learningStatus: string
  masteryLevel: number
}

export interface Question {
  id: number
  knowledgePointId: number
  knowledgePointName?: string
  questionType: 'single_choice' | 'true_false' | 'short_answer'
  content: string
  options: string[]
  answer: string
  analysis: string
  difficulty: 'easy' | 'medium' | 'hard'
  source: string
  createTime: string
}

export interface AnswerResult {
  answerRecordId: number
  questionId: number
  correct: boolean
  score: number
  correctAnswer: string
  analysis: string
  feedback: string
  learningStatus: string
  masteryLevel: number
}

export interface LearningDocument {
  id: number
  fileName: string
  fileType: string
  processStatus: string
  chunkCount: number
  uploadTime: string
}

export interface DocumentChunk {
  id: number
  documentId: number
  chunkIndex: number
  chunkText: string
  createTime: string
}

export interface LearningDocumentDetail extends LearningDocument {
  preview: string
}

export interface DocumentUploadResult {
  documentId: number
  processStatus: string
  chunkCount: number
}

export interface RagSource {
  documentId: number
  fileName: string
  chunkIndex: number
  snippet: string
}

export interface RagChatResult {
  conversationId: number
  answer: string
  sources: RagSource[]
}

export interface WeakKnowledgePoint {
  knowledgePointId: number
  knowledgePointName: string
  subject?: string
  masteryLevel?: number
  answerAccuracy?: number
  reason: string
}

export interface LearningAnalysisOverview {
  learnedCount: number
  masteredCount: number
  inProgressCount: number
  averageMasteryLevel: number
  totalStudyTime: number
  answeredQuestionCount: number
  correctAnswerCount: number
  answerAccuracy: number
  chatMessageCount: number
  weakKnowledgePoints: WeakKnowledgePoint[]
  suggestions: string[]
  nextActions: string[]
}

export interface KnowledgePointProgress {
  knowledgePointId: number
  knowledgePointName: string
  subject?: string
  learningStatus?: string
  masteryLevel: number
  studyTime: number
  answeredQuestionCount: number
  correctAnswerCount: number
  answerAccuracy: number
  averageScore: number
  updateTime?: string
}

export interface RecentAnswerAnalysis {
  answerRecordId: number
  questionId: number
  knowledgePointId?: number
  knowledgePointName?: string
  questionType?: string
  difficulty?: string
  questionContent: string
  userAnswer?: string
  correct: boolean
  score: number
  feedbackPreview: string
  createTime?: string
}

export interface StudyPlan {
  title: string
  period: 'week' | 'month'
  goal: string
  estimatedDays: number
  focusKnowledgePoints: string[]
  steps: string[]
  planContent: string
}

export interface AiGeneratedPathStep {
  orderIndex: number
  title: string
  goal: string
  explanation?: string
  estimatedTime?: string
  keyPoints: string[]
  actions: string[]
}

export interface AiGeneratedPath {
  topic: string
  source: 'ai_generated'
  level?: string
  goal?: string
  summary?: string
  steps: AiGeneratedPathStep[]
}

export interface AiPracticeQuestion {
  temporaryId: number
  topic: string
  questionType: 'single_choice' | 'true_false' | 'short_answer'
  difficulty: 'easy' | 'medium' | 'hard'
  content: string
  options: string[]
  answer: string
  analysis: string
}

export interface AiPractice {
  topic: string
  source: 'ai_generated'
  questionType: 'single_choice' | 'true_false' | 'short_answer'
  difficulty: 'easy' | 'medium' | 'hard'
  questions: AiPracticeQuestion[]
}

export interface AgentSuggestion {
  id: number
  agentType: 'planning' | 'teaching' | 'practice' | 'analysis'
  title: string
  suggestion: string
  reason?: string
  actionType: string
  actionPayload?: string
  impactLevel: 'low' | 'medium' | 'high'
  requiresConfirmation: boolean
  status: 'pending' | 'confirmed' | 'completed' | 'dismissed'
  createTime?: string
  confirmTime?: string
  completeTime?: string
  updateTime?: string
}

export interface AgentEventLog {
  id: number
  suggestionId: number
  eventType: string
  note?: string
  createTime?: string
}
