<template>
  <main class="app-shell">
    <aside class="sidebar">
      <div class="sidebar-head">
        <div>
          <div class="product-name">AI Tutor</div>
          <div class="user-line">{{ authStore.user?.nickname || authStore.user?.username }}</div>
        </div>
        <el-button :icon="SwitchButton" circle @click="logout" />
      </div>

      <div class="tool-switch">
        <button
          v-for="tool in tools"
          :key="tool.value"
          class="tool-button"
          :class="{ active: activeTool === tool.value }"
          @click="activeTool = tool.value"
        >
          {{ tool.label }}
        </button>
      </div>

      <el-button class="new-conversation" type="primary" :icon="Plus" @click="createNewConversation">
        新会话
      </el-button>

      <div v-loading="workspaceStore.loadingConversations" class="conversation-list">
        <button
          v-for="conversation in workspaceStore.conversations"
          :key="conversation.id"
          class="conversation-item"
          :class="{ active: workspaceStore.currentConversationId === conversation.id }"
          @click="selectConversation(conversation)"
        >
          <span>{{ conversation.title || '未命名会话' }}</span>
          <small>{{ modeLabel(conversation.mode) }} · {{ formatTime(conversation.updateTime) }}</small>
        </button>

        <el-empty
          v-if="!workspaceStore.loadingConversations && workspaceStore.conversations.length === 0"
          description="暂无会话"
          :image-size="80"
        />
      </div>
    </aside>

    <section class="chat-panel">
      <header class="chat-head">
        <div>
          <h2>{{ workspaceStore.currentConversation?.title || 'AI 学习问答' }}</h2>
          <p>{{ headerSubtitle }}</p>
        </div>
        <el-tag v-if="workspaceStore.currentConversation" size="large">
          {{ modeLabel(workspaceStore.currentConversation.mode) }}
        </el-tag>
      </header>

      <div ref="messageScroller" v-loading="workspaceStore.loadingMessages" class="message-list">
        <div v-if="workspaceStore.messages.length === 0" class="empty-chat">
          <h3>{{ emptyTitle }}</h3>
          <p>{{ emptySubtitle }}</p>
        </div>

        <article
          v-for="(message, index) in workspaceStore.messages"
          :key="`${message.createTime}-${index}`"
          class="message"
          :class="message.role"
        >
          <div class="message-meta">{{ message.role === 'user' ? '我' : 'AI Tutor' }}</div>
          <div
            v-if="message.role === 'assistant'"
            class="message-bubble markdown-body"
            v-html="renderMarkdown(message.messageContent)"
          />
          <div v-else class="message-bubble">{{ message.messageContent }}</div>
        </article>
      </div>

      <footer class="composer">
        <el-input
          v-model="draft"
          type="textarea"
          resize="none"
          :autosize="{ minRows: 2, maxRows: 5 }"
          :placeholder="composerPlaceholder"
          @keydown.enter.exact.prevent="send"
        />
        <el-button type="primary" :icon="Promotion" :loading="workspaceStore.sending || ragSending" @click="send">
          发送
        </el-button>
      </footer>
    </section>

    <aside class="workspace-panel">
      <el-tabs v-model="activeTool" stretch>
        <el-tab-pane label="档案" name="profile">
          <div class="panel-head">
            <h2>学习档案</h2>
          </div>

          <el-form v-loading="workspaceStore.loadingProfile" label-position="top" class="profile-form">
            <el-form-item label="学习方向">
              <el-input v-model="workspaceStore.profile.learningDirection" placeholder="Java / Python / 数学" />
            </el-form-item>
            <el-form-item label="学习目标">
              <el-input v-model="workspaceStore.profile.learningGoal" placeholder="就业 / 考试 / 项目实践" />
            </el-form-item>
            <el-form-item label="当前水平">
              <el-input v-model="workspaceStore.profile.currentLevel" placeholder="基础 / 进阶 / 熟练" />
            </el-form-item>
            <el-form-item label="学习偏好">
              <el-input
                v-model="workspaceStore.profile.learningPreference"
                type="textarea"
                resize="none"
                :rows="4"
                placeholder="例如：案例讲解、循序渐进"
              />
            </el-form-item>
            <el-button class="full-action" type="primary" :icon="Check" @click="saveProfile">
              保存档案
            </el-button>
          </el-form>
        </el-tab-pane>

        <el-tab-pane label="教学" name="teaching">
          <section class="feature-block">
            <div class="block-title">知识点</div>
            <el-input v-model.trim="knowledgeSubject" class="subject-input" placeholder="学科" @change="loadKnowledgeTree" />
            <el-tree
              v-loading="loadingKnowledge"
              class="knowledge-tree"
              :data="knowledgeTree"
              node-key="id"
              default-expand-all
              highlight-current
              :props="{ label: 'name', children: 'children' }"
              @node-click="selectKnowledgePoint"
            />
          </section>

          <section class="feature-block">
            <div class="block-title">教学模式</div>
            <div class="selected-line">{{ selectedKnowledgePoint?.name || '未选择知识点' }}</div>
            <el-button class="full-action" type="primary" :loading="teachingLoading" @click="startTeachingFlow">
              开始教学
            </el-button>
            <el-input
              v-model="teachingAnswer"
              class="stacked-input"
              type="textarea"
              resize="none"
              :rows="4"
              placeholder="提交理解检查回答"
            />
            <el-button class="full-action" :loading="teachingLoading" @click="evaluateTeachingFlow">
              提交回答
            </el-button>
          </section>

          <section class="feature-block">
            <div class="block-title">学习记录</div>
            <div v-if="learningRecords.length === 0" class="muted-line">暂无记录</div>
            <div v-for="record in learningRecords" :key="record.knowledgePointId" class="record-row">
              <span>{{ record.knowledgePointName || record.knowledgePointId }}</span>
              <el-tag size="small">{{ record.masteryLevel }}%</el-tag>
            </div>
          </section>
        </el-tab-pane>

        <el-tab-pane label="练习" name="practice">
          <section class="feature-block">
            <div class="block-title">生成题目</div>
            <div class="selected-line">{{ selectedKnowledgePoint?.name || '未选择知识点' }}</div>
            <el-form label-position="top">
              <el-form-item label="题型">
                <el-select v-model="practiceForm.questionType">
                  <el-option label="单选题" value="single_choice" />
                  <el-option label="判断题" value="true_false" />
                  <el-option label="简答题" value="short_answer" />
                </el-select>
              </el-form-item>
              <el-form-item label="难度">
                <el-select v-model="practiceForm.difficulty">
                  <el-option label="简单" value="easy" />
                  <el-option label="中等" value="medium" />
                  <el-option label="困难" value="hard" />
                </el-select>
              </el-form-item>
              <el-form-item label="数量">
                <el-input-number v-model="practiceForm.count" :min="1" :max="5" />
              </el-form-item>
            </el-form>
            <el-button class="full-action" type="primary" :loading="questionLoading" @click="generateQuestionFlow">
              生成题目
            </el-button>
          </section>

          <section class="question-list">
            <article v-for="question in questions" :key="question.id" class="question-item">
              <div class="question-head">
                <el-tag size="small">{{ questionTypeLabel(question.questionType) }}</el-tag>
                <span>{{ difficultyLabel(question.difficulty) }}</span>
              </div>
              <p class="question-content">{{ question.content }}</p>

              <el-radio-group
                v-if="question.questionType === 'single_choice'"
                v-model="answerDrafts[question.id]"
                class="option-group"
              >
                <el-radio v-for="option in question.options" :key="option" :label="choiceValue(option)">
                  {{ option }}
                </el-radio>
              </el-radio-group>

              <el-radio-group
                v-else-if="question.questionType === 'true_false'"
                v-model="answerDrafts[question.id]"
                class="option-group"
              >
                <el-radio label="true">true</el-radio>
                <el-radio label="false">false</el-radio>
              </el-radio-group>

              <el-input
                v-else
                v-model="answerDrafts[question.id]"
                type="textarea"
                resize="none"
                :rows="3"
                placeholder="输入你的答案"
              />

              <el-button class="full-action stacked-input" :loading="answerLoadingId === question.id" @click="submitAnswerFlow(question)">
                提交答案
              </el-button>

              <div v-if="answerResults[question.id]" class="feedback-box">
                <strong>{{ answerResults[question.id].score }} 分</strong>
                <div class="markdown-body" v-html="renderMarkdown(answerResults[question.id].feedback)" />
              </div>
            </article>
            <el-empty v-if="questions.length === 0" description="暂无题目" :image-size="80" />
          </section>
        </el-tab-pane>

        <el-tab-pane label="资料" name="rag">
          <section class="feature-block">
            <div class="block-title">资料库</div>
            <el-upload
              accept=".txt,.md,.markdown"
              :show-file-list="false"
              :http-request="uploadLearningDocument"
            >
              <el-button class="full-action" :icon="Upload" :loading="uploadingDocument">
                上传资料
              </el-button>
            </el-upload>
            <el-checkbox-group v-model="selectedDocumentIds" class="document-list">
              <el-checkbox v-for="document in documents" :key="document.id" :label="document.id">
                {{ document.fileName }}
                <small>{{ document.chunkCount }} chunks</small>
              </el-checkbox>
            </el-checkbox-group>
            <el-empty v-if="documents.length === 0" description="暂无资料" :image-size="80" />
          </section>

          <section class="feature-block">
            <div class="block-title">资料问答</div>
            <el-button class="full-action" type="primary" :loading="ragSending" @click="createRagConversation">
              新建资料会话
            </el-button>
            <el-input
              v-model="ragQuestion"
              class="stacked-input"
              type="textarea"
              resize="none"
              :rows="4"
              placeholder="基于资料提问"
            />
            <el-button class="full-action" :loading="ragSending" @click="sendRagQuestion">
              资料问答
            </el-button>
          </section>

          <section v-if="ragSources.length > 0" class="feature-block">
            <div class="block-title">来源</div>
            <div v-for="source in ragSources" :key="`${source.documentId}-${source.chunkIndex}`" class="source-item">
              <strong>{{ source.fileName }} · #{{ source.chunkIndex }}</strong>
              <p>{{ source.snippet }}</p>
            </div>
          </section>
        </el-tab-pane>
      </el-tabs>
    </aside>
  </main>
</template>

<script setup lang="ts">
import { Check, Plus, Promotion, SwitchButton, Upload } from '@element-plus/icons-vue'
import { ElMessage } from 'element-plus'
import type { UploadRequestOptions } from 'element-plus'
import MarkdownIt from 'markdown-it'
import { computed, nextTick, onMounted, reactive, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import {
  evaluateTeaching,
  generateQuestions,
  listDocuments,
  listKnowledgeTree,
  listLearningRecords,
  listQuestions,
  sendRagChat,
  startTeaching,
  submitAnswer,
  uploadDocument
} from '../api'
import { useAuthStore } from '../stores/auth'
import { useWorkspaceStore } from '../stores/workspace'
import type {
  AnswerResult,
  Conversation,
  KnowledgePoint,
  LearningDocument,
  LearningRecord,
  Question,
  RagSource
} from '../types/domain'

type ToolMode = 'profile' | 'chat' | 'teaching' | 'practice' | 'rag'

const router = useRouter()
const authStore = useAuthStore()
const workspaceStore = useWorkspaceStore()
const activeTool = ref<ToolMode>('profile')
const draft = ref('')
const messageScroller = ref<HTMLElement | null>(null)
const markdown = new MarkdownIt({
  html: false,
  linkify: true,
  breaks: true
})

const tools: Array<{ label: string; value: ToolMode }> = [
  { label: '档案', value: 'profile' },
  { label: '问答', value: 'chat' },
  { label: '教学', value: 'teaching' },
  { label: '练习', value: 'practice' },
  { label: '资料', value: 'rag' }
]

const knowledgeSubject = ref('Java')
const knowledgeTree = ref<KnowledgePoint[]>([])
const selectedKnowledgePointId = ref<number | null>(null)
const loadingKnowledge = ref(false)
const learningRecords = ref<LearningRecord[]>([])
const teachingAnswer = ref('')
const teachingLoading = ref(false)

const practiceForm = reactive({
  questionType: 'single_choice',
  difficulty: 'medium',
  count: 1
})
const questions = ref<Question[]>([])
const answerDrafts = reactive<Record<number, string>>({})
const answerResults = reactive<Record<number, AnswerResult>>({})
const questionLoading = ref(false)
const answerLoadingId = ref<number | null>(null)

const documents = ref<LearningDocument[]>([])
const selectedDocumentIds = ref<number[]>([])
const uploadingDocument = ref(false)
const ragQuestion = ref('')
const ragSources = ref<RagSource[]>([])
const ragSending = ref(false)

const selectedKnowledgePoint = computed(() =>
  flattenKnowledgePoints(knowledgeTree.value).find((item) => item.id === selectedKnowledgePointId.value) || null
)

const headerSubtitle = computed(() => {
  const mode = workspaceStore.currentConversation?.mode || 'chat'
  if (mode === 'teaching') {
    return '围绕知识点进行讲解、检查和反馈'
  }
  if (mode === 'rag') {
    return '基于上传资料检索来源并回答'
  }
  return '结合学习档案和当前会话上下文回答'
})

const emptyTitle = computed(() => {
  if (activeTool.value === 'teaching') {
    return '选择知识点开始教学'
  }
  if (activeTool.value === 'practice') {
    return '生成题目后开始练习'
  }
  if (activeTool.value === 'rag') {
    return '上传资料后开始问答'
  }
  return '开始一次学习问答'
})

const emptySubtitle = computed(() => {
  if (activeTool.value === 'profile') {
    return '可以先在右侧补充学习档案'
  }
  return '消息会出现在这里'
})

const composerPlaceholder = computed(() => {
  if (workspaceStore.currentConversation?.mode === 'rag') {
    return '输入资料问答问题'
  }
  return '输入学习问题'
})

onMounted(async () => {
  try {
    await Promise.all([
      workspaceStore.loadProfile(),
      workspaceStore.loadConversations(),
      loadKnowledgeTree(),
      loadLearningRecordsFlow(),
      loadDocumentsFlow()
    ])
  } catch (error) {
    showError(error, '加载失败')
  }
})

watch(
  () => workspaceStore.messages.length,
  async () => {
    await nextTick()
    if (messageScroller.value) {
      messageScroller.value.scrollTop = messageScroller.value.scrollHeight
    }
  }
)

watch(selectedKnowledgePointId, async (value) => {
  if (value) {
    await loadQuestionsFlow()
  }
})

function renderMarkdown(content: string) {
  return markdown.render(content || '')
}

function formatTime(value: string) {
  if (!value) {
    return ''
  }
  return value.replace('T', ' ').slice(0, 16)
}

function modeLabel(mode: string) {
  if (mode === 'teaching') {
    return '教学'
  }
  if (mode === 'rag') {
    return '资料'
  }
  return '问答'
}

function questionTypeLabel(type: string) {
  if (type === 'true_false') {
    return '判断题'
  }
  if (type === 'short_answer') {
    return '简答题'
  }
  return '单选题'
}

function difficultyLabel(difficulty: string) {
  if (difficulty === 'easy') {
    return '简单'
  }
  if (difficulty === 'hard') {
    return '困难'
  }
  return '中等'
}

function choiceValue(option: string) {
  const first = option.trim().charAt(0).toUpperCase()
  return first >= 'A' && first <= 'D' ? first : option
}

async function selectConversation(conversation: Conversation) {
  await workspaceStore.selectConversation(conversation.id)
  if (conversation.mode === 'teaching') {
    activeTool.value = 'teaching'
  } else if (conversation.mode === 'rag') {
    activeTool.value = 'rag'
  } else {
    activeTool.value = 'chat'
  }
}

async function createNewConversation() {
  try {
    await workspaceStore.addConversation('新的学习会话', 'chat')
    activeTool.value = 'chat'
  } catch (error) {
    showError(error, '创建会话失败')
  }
}

async function saveProfile() {
  try {
    await workspaceStore.saveProfile()
    ElMessage.success('学习档案已保存')
  } catch (error) {
    showError(error, '保存失败')
  }
}

async function send() {
  const content = draft.value.trim()
  if (!content) {
    return
  }

  if (workspaceStore.currentConversation?.mode === 'rag') {
    ragQuestion.value = content
    draft.value = ''
    await sendRagQuestion()
    return
  }

  draft.value = ''
  try {
    await workspaceStore.sendMessage(content)
  } catch (error) {
    showError(error, '发送失败')
  }
}

async function loadKnowledgeTree() {
  loadingKnowledge.value = true
  try {
    knowledgeTree.value = await listKnowledgeTree(knowledgeSubject.value || 'Java')
    if (!selectedKnowledgePointId.value) {
      selectedKnowledgePointId.value = firstSelectableKnowledgePoint(knowledgeTree.value)?.id || null
    }
  } finally {
    loadingKnowledge.value = false
  }
}

function selectKnowledgePoint(point: KnowledgePoint) {
  selectedKnowledgePointId.value = point.id
}

function flattenKnowledgePoints(points: KnowledgePoint[]) {
  const result: KnowledgePoint[] = []
  for (const point of points) {
    result.push(point)
    result.push(...flattenKnowledgePoints(point.children || []))
  }
  return result
}

function firstSelectableKnowledgePoint(points: KnowledgePoint[]) {
  return flattenKnowledgePoints(points).find((point) => !point.children || point.children.length === 0)
    || flattenKnowledgePoints(points)[0]
}

async function startTeachingFlow() {
  if (!selectedKnowledgePoint.value) {
    ElMessage.warning('请选择知识点')
    return
  }

  teachingLoading.value = true
  try {
    const result = await startTeaching(selectedKnowledgePoint.value.id)
    await workspaceStore.loadConversations()
    await workspaceStore.selectConversation(result.conversationId)
    await loadLearningRecordsFlow()
    activeTool.value = 'teaching'
    ElMessage.success('教学已开始')
  } catch (error) {
    showError(error, '开始教学失败')
  } finally {
    teachingLoading.value = false
  }
}

async function evaluateTeachingFlow() {
  if (!selectedKnowledgePoint.value) {
    ElMessage.warning('请选择知识点')
    return
  }
  if (!workspaceStore.currentConversationId || workspaceStore.currentConversation?.mode !== 'teaching') {
    ElMessage.warning('请先开始教学')
    return
  }
  const answer = teachingAnswer.value.trim()
  if (!answer) {
    ElMessage.warning('请输入回答')
    return
  }

  teachingLoading.value = true
  try {
    const result = await evaluateTeaching(workspaceStore.currentConversationId, selectedKnowledgePoint.value.id, answer)
    teachingAnswer.value = ''
    await workspaceStore.selectConversation(result.conversationId)
    await loadLearningRecordsFlow()
  } catch (error) {
    showError(error, '提交回答失败')
  } finally {
    teachingLoading.value = false
  }
}

async function loadLearningRecordsFlow() {
  learningRecords.value = await listLearningRecords()
}

async function generateQuestionFlow() {
  if (!selectedKnowledgePoint.value) {
    ElMessage.warning('请选择知识点')
    return
  }

  questionLoading.value = true
  try {
    const generated = await generateQuestions(
      selectedKnowledgePoint.value.id,
      practiceForm.questionType,
      practiceForm.difficulty,
      practiceForm.count
    )
    questions.value = generated
    ElMessage.success('题目已生成')
  } catch (error) {
    showError(error, '生成题目失败')
  } finally {
    questionLoading.value = false
  }
}

async function loadQuestionsFlow() {
  if (!selectedKnowledgePoint.value) {
    questions.value = []
    return
  }
  questions.value = await listQuestions(selectedKnowledgePoint.value.id)
}

async function submitAnswerFlow(question: Question) {
  const answer = answerDrafts[question.id]?.trim()
  if (!answer) {
    ElMessage.warning('请输入答案')
    return
  }

  answerLoadingId.value = question.id
  try {
    answerResults[question.id] = await submitAnswer(question.id, answer)
    await loadLearningRecordsFlow()
  } catch (error) {
    showError(error, '提交答案失败')
  } finally {
    answerLoadingId.value = null
  }
}

async function uploadLearningDocument(options: UploadRequestOptions) {
  uploadingDocument.value = true
  try {
    const result = await uploadDocument(options.file as File)
    await loadDocumentsFlow()
    selectedDocumentIds.value = [result.documentId]
    ElMessage.success('资料已上传')
    options.onSuccess?.(result)
  } catch (error) {
    // Element Plus 的自定义上传需要主动通知组件失败状态。
    const uploadError = error instanceof Error ? error : new Error(String(error))
    options.onError?.(uploadError as Parameters<NonNullable<UploadRequestOptions['onError']>>[0])
    showError(error, '上传失败')
  } finally {
    uploadingDocument.value = false
  }
}

async function loadDocumentsFlow() {
  documents.value = await listDocuments()
  if (selectedDocumentIds.value.length === 0 && documents.value.length > 0) {
    selectedDocumentIds.value = documents.value.map((item) => item.id)
  }
}

async function createRagConversation() {
  ragSending.value = true
  try {
    await workspaceStore.addConversation('资料问答', 'rag')
    activeTool.value = 'rag'
  } catch (error) {
    showError(error, '创建资料会话失败')
  } finally {
    ragSending.value = false
  }
}

async function sendRagQuestion() {
  const question = ragQuestion.value.trim()
  if (!question) {
    ElMessage.warning('请输入问题')
    return
  }

  ragSending.value = true
  try {
    if (!workspaceStore.currentConversationId || workspaceStore.currentConversation?.mode !== 'rag') {
      await workspaceStore.addConversation('资料问答', 'rag')
    }
    if (!workspaceStore.currentConversationId) {
      return
    }
    const result = await sendRagChat(
      workspaceStore.currentConversationId,
      question,
      selectedDocumentIds.value.length > 0 ? selectedDocumentIds.value : undefined
    )
    ragQuestion.value = ''
    ragSources.value = result.sources || []
    await workspaceStore.loadConversations()
    await workspaceStore.selectConversation(result.conversationId)
  } catch (error) {
    showError(error, '资料问答失败')
  } finally {
    ragSending.value = false
  }
}

async function logout() {
  workspaceStore.reset()
  authStore.logout()
  await router.push('/login')
}

function showError(error: unknown, fallback: string) {
  ElMessage.error(error instanceof Error ? error.message : fallback)
}
</script>
