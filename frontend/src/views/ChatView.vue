<template>
  <div class="chat-workbench">
    <section class="chat-main panel">
      <header class="chat-main-head">
        <div>
          <p class="eyebrow">{{ currentModeLabel }}</p>
          <h2>{{ currentChatTitle }}</h2>
          <div class="chat-topic-strip">
            <el-tag v-if="activeTopicLabel" effect="plain">{{ activeTopicLabel }}</el-tag>
            <el-tag v-if="workspaceStore.activeLearningSession?.teachingStrategy" effect="plain">
              {{ strategyLabel(workspaceStore.activeLearningSession.teachingStrategy) }}
            </el-tag>
            <el-tag v-if="workspaceStore.activeLearningSession" :type="learningSessionStatusTag(workspaceStore.activeLearningSession.status)" effect="dark">
              {{ learningSessionStatusLabel(workspaceStore.activeLearningSession.status) }}
            </el-tag>
          </div>
        </div>
        <div class="chat-head-actions">
          <el-tooltip :content="historySearchOpen ? '关闭记录搜索' : '查找聊天记录'" placement="bottom">
            <el-button
              :aria-label="historySearchOpen ? '关闭记录搜索' : '查找聊天记录'"
              :icon="Search"
              circle
              @click="toggleHistorySearch"
            />
          </el-tooltip>
          <el-tooltip content="新学习会话" placement="bottom">
            <el-button aria-label="新学习会话" :icon="Plus" circle @click="createNewConversation" />
          </el-tooltip>
        </div>
      </header>

      <section v-if="historySearchOpen" class="chat-history-search">
        <div class="history-search-row">
          <el-input
            v-model.trim="historyQuery"
            :prefix-icon="Search"
            clearable
            placeholder="搜索当前对话里的关键词..."
            @keydown.enter.prevent="goToNextSearchResult"
          />
          <span class="history-result-count">{{ historySearchLabel }}</span>
          <el-button :icon="ArrowUp" circle :disabled="historySearchResults.length === 0" @click="goToPreviousSearchResult" />
          <el-button :icon="ArrowDown" circle :disabled="historySearchResults.length === 0" @click="goToNextSearchResult" />
          <el-button :icon="Close" circle @click="closeHistorySearch" />
        </div>

        <div v-if="historyQuery && historySearchResults.length > 0" class="history-result-strip">
          <button
            v-for="result in historySearchResults"
            :key="`${result.messageIndex}-${result.resultIndex}`"
            class="history-result-item"
            :class="{ active: activeResultIndex === result.resultIndex }"
            type="button"
            @click="goToSearchResult(result.resultIndex)"
          >
            <strong>{{ result.roleLabel }} · 第 {{ result.messageIndex + 1 }} 条</strong>
            <span>{{ result.snippet }}</span>
          </button>
        </div>
      </section>

      <div ref="messageScroller" v-loading="workspaceStore.loadingMessages" class="message-list wide">
        <div v-if="workspaceStore.messages.length === 0" class="empty-chat">
          <h3>把问题交给 AI Tutor</h3>
          <p>可以直接输入“我想学习 HashMap”，学习会话、图谱、练习和进度会在右侧自动承接。</p>
        </div>

        <article
          v-for="(message, index) in workspaceStore.messages"
          :key="`${message.createTime}-${index}`"
          class="message"
          :class="[
            message.role,
            {
              'search-match': matchedMessageIndexes.has(index),
              'active-search-match': activeMessageIndex === index
            }
          ]"
          :data-message-index="index"
        >
          <div class="message-meta">{{ message.role === 'user' ? '你' : 'AI Tutor' }}</div>
          <div
            v-if="message.role === 'assistant'"
            class="message-bubble markdown-body"
            v-html="renderMarkdown(message.messageContent)"
          />
          <div v-else class="message-bubble">{{ message.messageContent }}</div>
          <div v-if="message.role === 'assistant' && message.memoryUpdates?.length" class="message-memory-updates">
            <span v-for="update in message.memoryUpdates" :key="update">{{ update }}</span>
          </div>
          <div v-if="message.role === 'assistant'" class="message-quick-actions">
            <el-button
              v-for="action in quickPromptActions"
              :key="action.prompt"
              size="small"
              plain
              @click="sendPrompt(action.prompt)"
            >
              {{ action.label }}
            </el-button>
          </div>
          <div v-if="message.role === 'assistant' && message.actions?.length" class="message-actions">
            <button
              v-for="action in message.actions"
              :key="`${message.createTime}-${action.actionType}-${action.routeName}`"
              class="message-action-card"
              :data-impact="action.impactLevel"
              type="button"
              @click="runTutorAction(action)"
            >
              <span>
                <strong>{{ action.title }}</strong>
                <small>{{ action.description }}</small>
              </span>
              <em>{{ action.label }}</em>
            </button>
          </div>
        </article>
      </div>

      <footer class="composer">
        <el-input
          v-model="draft"
          type="textarea"
          resize="none"
          :autosize="{ minRows: 2, maxRows: 5 }"
          placeholder="问一个问题，或让 AI Tutor 继续解释当前知识点..."
          @keydown.enter.exact.prevent="send"
        />
        <el-button type="primary" :icon="Promotion" :loading="workspaceStore.sending" @click="send">
          发送
        </el-button>
      </footer>
    </section>

    <aside class="learning-inspector panel">
      <header class="inspector-head">
        <div class="inspector-icon">
          <el-icon><Compass /></el-icon>
        </div>
        <div>
          <p class="eyebrow">Inspector</p>
          <h2>学习上下文</h2>
        </div>
      </header>

      <nav class="inspector-tabs" aria-label="学习上下文">
        <button
          v-for="tab in inspectorTabs"
          :key="tab.key"
          class="inspector-tab"
          :class="{ active: activePanel === tab.key }"
          type="button"
          @click="setActivePanel(tab.key)"
        >
          <el-icon><component :is="tab.icon" /></el-icon>
          <span>{{ tab.label }}</span>
        </button>
      </nav>

      <div class="inspector-body">
        <section v-if="activePanel === 'session'" class="inspector-section">
          <template v-if="workspaceStore.activeLearningSession">
            <div class="inspector-block">
              <span>Current Topic</span>
              <strong>{{ workspaceStore.activeLearningSession.topic || activeTopicLabel || '当前主题' }}</strong>
            </div>
            <div class="inspector-block">
              <span>Strategy</span>
              <el-tag effect="plain">{{ strategyLabel(workspaceStore.activeLearningSession.teachingStrategy || 'concept_first') }}</el-tag>
            </div>
            <div class="inspector-block">
              <span>Reasoning</span>
              <ul v-if="workspaceStore.activeLearningSession.strategySource?.length" class="inspector-list">
                <li v-for="source in workspaceStore.activeLearningSession.strategySource" :key="source">{{ source }}</li>
              </ul>
              <p v-else>等待 Tutor Agent 形成可解释策略。</p>
            </div>
            <div class="inspector-next">
              <span>Next Up</span>
              <strong>{{ workspaceStore.activeLearningSession.nextAction || '继续围绕当前目标学习' }}</strong>
            </div>
            <el-button type="primary" plain :icon="CircleCheck" @click="completeLearningSession">
              完成本次会话
            </el-button>
          </template>
          <el-empty v-else description="开始聊天后会自动创建学习会话" :image-size="92" />
        </section>

        <section v-else-if="activePanel === 'map'" class="inspector-section">
          <div class="inspector-block">
            <span>Knowledge Map</span>
            <strong>{{ knowledgeMapTargetLabel }}</strong>
            <p>{{ knowledgeMapPanelHint }}</p>
          </div>
          <div class="knowledge-map-canvas">
            <div v-if="knowledgeMapPrerequisites.length" class="knowledge-map-prerequisites">
              <button
                v-for="prerequisite in knowledgeMapPrerequisites"
                :key="prerequisite.knowledgePointId"
                class="knowledge-map-node prerequisite-node"
                type="button"
                @click="goLearningPath(prerequisite.knowledgePointId)"
              >
                <strong>{{ prerequisite.knowledgePointName }}</strong>
                <small>掌握度 {{ masteryLabel(prerequisite.masteryLevel) }}</small>
              </button>
            </div>
            <div v-else class="knowledge-map-node prerequisite-node muted">
              <strong>{{ knowledgeMapEmptyTitle }}</strong>
              <small>{{ knowledgeMapEmptyDescription }}</small>
            </div>
            <span class="knowledge-map-arrow">→</span>
            <button class="knowledge-map-node target-node" type="button" @click="goLearningPath(activeKnowledgeMap?.knowledgePointId)">
              <strong>{{ knowledgeMapTargetLabel }}</strong>
              <small>{{ knowledgeMapSubjectLabel }}</small>
            </button>
          </div>
          <ul v-if="knowledgeMapReasons.length" class="knowledge-map-reasons">
            <li
              v-for="prerequisite in knowledgeMapReasons"
              :key="`${prerequisite.knowledgePointId}-${prerequisite.relationReason}`"
            >
              {{ prerequisite.relationReason }}
            </li>
          </ul>
          <div class="inspector-action-row">
            <el-button type="primary" plain :icon="Compass" @click="goKnowledgeGraph">打开知识图谱</el-button>
            <el-button plain @click="goLearningPath(activeKnowledgeMap?.knowledgePointId)">查看学习路径详情</el-button>
          </div>
        </section>

        <section v-else-if="activePanel === 'practice'" class="inspector-section">
          <div class="inspector-block">
            <span>Practice</span>
            <strong>{{ activeTopicLabel || '当前主题练习' }}</strong>
            <p>围绕当前主题做练习，检查自己的理解。</p>
          </div>
          <div class="inspector-card">
            <strong>生成当前主题练习</strong>
            <p>进入练习详情后，可以选择题型、难度和题目数量。</p>
            <el-button type="primary" plain :icon="EditPen" @click="goPractice">进入练习</el-button>
          </div>
        </section>

        <section v-else-if="activePanel === 'sources'" class="inspector-section" v-loading="sourcesLoading">
          <div class="inspector-block">
            <span>Sources</span>
            <strong>{{ documents.length }} 个资料来源</strong>
            <p>查看已上传的学习资料，进入资料库可以管理教材并依据教材提问。</p>
          </div>
          <div v-if="documents.length" class="inspector-list-card">
            <article v-for="document in documents.slice(0, 5)" :key="document.id">
              <strong>{{ document.fileName }}</strong>
              <small>{{ document.fileType }} · {{ document.chunkCount }} 个片段 · {{ trustLabel(document) }}</small>
            </article>
          </div>
          <el-empty v-else description="还没有资料来源" :image-size="86" />
          <el-button plain :icon="Collection" @click="router.push({ name: 'library' })">打开资料详情</el-button>
        </section>

        <section v-else class="inspector-section" v-loading="progressLoading">
          <div class="inspector-block">
            <span>Progress</span>
            <strong>{{ analysisOverview?.averageMasteryLevel ?? 0 }}% 平均掌握</strong>
            <p>查看学习记录和薄弱知识点，进入学习分析可了解详细情况。</p>
          </div>
          <div class="inspector-metrics">
            <div>
              <span>正确率</span>
              <strong>{{ analysisOverview?.answerAccuracy ?? 0 }}%</strong>
            </div>
            <div>
              <span>完成率</span>
              <strong>{{ evaluationOverview?.sessionCompletionRate ?? 0 }}%</strong>
            </div>
          </div>
          <div v-if="analysisOverview?.weakKnowledgePoints.length" class="inspector-list-card">
            <article v-for="point in analysisOverview.weakKnowledgePoints.slice(0, 4)" :key="point.knowledgePointId">
              <strong>{{ point.knowledgePointName }}</strong>
              <small>{{ point.reason }}</small>
            </article>
          </div>
          <el-empty v-else description="暂无明显薄弱点" :image-size="86" />
          <el-button plain :icon="DataAnalysis" @click="router.push({ name: 'analysis', query: activeTopicLabel ? { topic: activeTopicLabel } : {} })">
            查看完整分析
          </el-button>
        </section>
      </div>
    </aside>
  </div>
</template>

<script setup lang="ts">
import type { Component } from 'vue'
import { computed, nextTick, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import {
  ArrowDown,
  ArrowUp,
  CircleCheck,
  Close,
  Collection,
  Compass,
  DataAnalysis,
  EditPen,
  InfoFilled,
  Plus,
  Promotion,
  Reading,
  Search
} from '@element-plus/icons-vue'
import { ElMessage } from 'element-plus'
import {
  getEvaluationOverview,
  getLearningAnalysisOverview,
  listDocuments
} from '../api'
import { useWorkspaceStore } from '../stores/workspace'
import type {
  EvaluationOverview,
  KnowledgeMapPrerequisite,
  LearningAnalysisOverview,
  LearningDocument,
  LearningSessionStatus,
  TutorAction
} from '../types/domain'
import { modeLabel } from '../utils/format'
import { renderMarkdown } from '../utils/markdown'

type InspectorPanel = 'session' | 'map' | 'practice' | 'sources' | 'progress'

interface HistorySearchResult {
  resultIndex: number
  messageIndex: number
  roleLabel: string
  snippet: string
}

interface InspectorTab {
  key: InspectorPanel
  label: string
  icon: Component
}

const workspaceStore = useWorkspaceStore()
const route = useRoute()
const router = useRouter()
const draft = ref('')
const historySearchOpen = ref(false)
const historyQuery = ref('')
const activeResultIndex = ref(0)
const messageScroller = ref<HTMLElement | null>(null)
const activePanel = ref<InspectorPanel>(normalizePanel(route.query.panel))
const documents = ref<LearningDocument[]>([])
const analysisOverview = ref<LearningAnalysisOverview | null>(null)
const evaluationOverview = ref<EvaluationOverview | null>(null)
const sourcesLoading = ref(false)
const progressLoading = ref(false)

const inspectorTabs: InspectorTab[] = [
  { key: 'session', label: 'Session', icon: InfoFilled },
  { key: 'map', label: 'Map', icon: Compass },
  { key: 'practice', label: 'Practice', icon: EditPen },
  { key: 'sources', label: 'Sources', icon: Collection },
  { key: 'progress', label: 'Progress', icon: DataAnalysis }
]

const quickPromptActions = [
  { label: '举例解释', prompt: '请用一个具体例子解释一下。' },
  { label: '补前置知识', prompt: '请先帮我补齐理解这个问题需要的前置知识。' },
  { label: '生成练习', prompt: '请基于当前知识点生成一道练习题。' },
  { label: '总结一下', prompt: '请总结一下当前知识点和下一步。' }
]

const currentModeLabel = computed(() => modeLabel(workspaceStore.currentConversation?.mode || 'chat'))
const currentChatTitle = computed(() => {
  if (workspaceStore.currentConversation?.title) {
    return workspaceStore.currentConversation.title
  }
  return workspaceStore.draftConversation ? '输入第一句话后自动命名' : 'AI 学习问答'
})
const activeTopicLabel = computed(() =>
  workspaceStore.activeLearningSession?.topic
    || workspaceStore.currentConversation?.title
    || String(route.query.topic || '').trim()
)
const normalizedHistoryQuery = computed(() => historyQuery.value.trim().toLowerCase())
const historySearchResults = computed<HistorySearchResult[]>(() => {
  const keyword = normalizedHistoryQuery.value
  if (!keyword) {
    return []
  }

  return workspaceStore.messages
    .map((message, messageIndex) => ({
      messageIndex,
      roleLabel: message.role === 'user' ? '你' : 'AI Tutor',
      snippet: buildSearchSnippet(message.messageContent, keyword),
      matched: message.messageContent.toLowerCase().includes(keyword)
    }))
    .filter((result) => result.matched)
    .map((result, resultIndex) => ({
      resultIndex,
      messageIndex: result.messageIndex,
      roleLabel: result.roleLabel,
      snippet: result.snippet
    }))
})
const matchedMessageIndexes = computed(() =>
  new Set(historySearchResults.value.map((result) => result.messageIndex))
)
const activeHistoryResult = computed(() => historySearchResults.value[activeResultIndex.value] || null)
const activeMessageIndex = computed(() => activeHistoryResult.value?.messageIndex ?? -1)
const activeKnowledgeMap = computed(() => workspaceStore.activeLearningSession?.knowledgeMap || null)
const knowledgeMapPrerequisites = computed(() => activeKnowledgeMap.value?.unmetPrerequisites || [])
const knowledgeMapReasons = computed(() =>
  knowledgeMapPrerequisites.value.filter((prerequisite) => Boolean(prerequisite.relationReason))
)
const knowledgeMapMatched = computed(() => Boolean(activeKnowledgeMap.value?.topic))
const knowledgeMapTargetLabel = computed(() =>
  activeKnowledgeMap.value?.topic || workspaceStore.activeLearningSession?.topic || activeTopicLabel.value || '当前主题'
)
const knowledgeMapSubjectLabel = computed(() => activeKnowledgeMap.value?.subject || '当前对话主题')
const knowledgeMapPanelHint = computed(() =>
  knowledgeMapMatched.value ? '当前主题的局部前置关系' : '知识图谱入口已预留，当前主题暂未进入标准知识地图'
)
const knowledgeMapEmptyTitle = computed(() =>
  knowledgeMapMatched.value ? '暂无需补齐项' : '未匹配知识点'
)
const knowledgeMapEmptyDescription = computed(() =>
  knowledgeMapMatched.value
    ? '当前没有识别到未掌握前置知识'
    : '试试输入“我想学习 HashMap”这类已建图主题'
)
const historySearchLabel = computed(() => {
  if (!normalizedHistoryQuery.value) {
    return '输入关键词'
  }
  if (historySearchResults.value.length === 0) {
    return '无匹配'
  }
  return `${activeResultIndex.value + 1} / ${historySearchResults.value.length}`
})

onMounted(async () => {
  try {
    await Promise.all([workspaceStore.loadConversations(), loadSourcesSummary(), loadProgressSummary()])
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '加载聊天失败')
  }
})

watch(
  () => workspaceStore.messages.length,
  async (newLength, oldLength) => {
    await nextTick()
    if (!messageScroller.value || newLength === 0) {
      return
    }

    const lastMessage = workspaceStore.messages[newLength - 1]
    const isSingleNewMessage = newLength === oldLength + 1
    if (isSingleNewMessage && workspaceStore.sending && lastMessage?.role === 'assistant') {
      scrollToMessageStart(newLength - 1)
      return
    }

    messageScroller.value.scrollTop = messageScroller.value.scrollHeight
  }
)

watch(normalizedHistoryQuery, async (keyword) => {
  activeResultIndex.value = 0
  if (keyword && historySearchResults.value.length > 0) {
    await nextTick()
    scrollToActiveSearchResult()
  }
})

watch(
  () => workspaceStore.currentConversationId,
  () => {
    historyQuery.value = ''
    activeResultIndex.value = 0
  }
)

watch(
  () => route.query.panel,
  (panel) => {
    activePanel.value = normalizePanel(panel)
  }
)

watch(activePanel, async (panel) => {
  if (panel === 'sources' && documents.value.length === 0) {
    await loadSourcesSummary()
  }
  if (panel === 'progress' && !analysisOverview.value) {
    await loadProgressSummary()
  }
})

function normalizePanel(panel: unknown): InspectorPanel {
  return ['session', 'map', 'practice', 'sources', 'progress'].includes(String(panel))
    ? String(panel) as InspectorPanel
    : 'session'
}

async function setActivePanel(panel: InspectorPanel) {
  activePanel.value = panel
  await router.replace({ name: 'chat', query: panel === 'session' ? {} : { panel } })
}

async function loadSourcesSummary() {
  sourcesLoading.value = true
  try {
    documents.value = await listDocuments()
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '加载资料来源失败')
  } finally {
    sourcesLoading.value = false
  }
}

async function loadProgressSummary() {
  progressLoading.value = true
  try {
    const [analysis, evaluation] = await Promise.all([
      getLearningAnalysisOverview(),
      getEvaluationOverview()
    ])
    analysisOverview.value = analysis
    evaluationOverview.value = evaluation
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '加载学习进度失败')
  } finally {
    progressLoading.value = false
  }
}

async function createNewConversation() {
  workspaceStore.startDraftConversation()
  await setActivePanel('session')
}

function toggleHistorySearch() {
  historySearchOpen.value = !historySearchOpen.value
  if (!historySearchOpen.value) {
    historyQuery.value = ''
    activeResultIndex.value = 0
  }
}

function closeHistorySearch() {
  historySearchOpen.value = false
  historyQuery.value = ''
  activeResultIndex.value = 0
}

async function goToPreviousSearchResult() {
  if (historySearchResults.value.length === 0) {
    return
  }
  activeResultIndex.value =
    (activeResultIndex.value - 1 + historySearchResults.value.length) % historySearchResults.value.length
  await nextTick()
  scrollToActiveSearchResult()
}

async function goToNextSearchResult() {
  if (historySearchResults.value.length === 0) {
    return
  }
  activeResultIndex.value = (activeResultIndex.value + 1) % historySearchResults.value.length
  await nextTick()
  scrollToActiveSearchResult()
}

async function goToSearchResult(resultIndex: number) {
  activeResultIndex.value = resultIndex
  await nextTick()
  scrollToActiveSearchResult()
}

function scrollToActiveSearchResult() {
  if (!activeHistoryResult.value || !messageScroller.value) {
    return
  }
  const target = messageScroller.value.querySelector<HTMLElement>(
    `[data-message-index="${activeHistoryResult.value.messageIndex}"]`
  )
  target?.scrollIntoView({ block: 'center', behavior: 'smooth' })
}

function scrollToMessageStart(messageIndex: number) {
  if (!messageScroller.value) {
    return
  }
  const target = messageScroller.value.querySelector<HTMLElement>(`[data-message-index="${messageIndex}"]`)
  target?.scrollIntoView({ block: 'start', behavior: 'smooth' })
}

function buildSearchSnippet(content: string, keyword: string) {
  const normalizedContent = content.replace(/\s+/g, ' ').trim()
  const position = normalizedContent.toLowerCase().indexOf(keyword)
  if (position < 0) {
    return normalizedContent.slice(0, 90)
  }
  const start = Math.max(0, position - 28)
  const end = Math.min(normalizedContent.length, position + keyword.length + 52)
  return `${start > 0 ? '...' : ''}${normalizedContent.slice(start, end)}${end < normalizedContent.length ? '...' : ''}`
}

function learningSessionStatusLabel(status: LearningSessionStatus) {
  const labels: Record<LearningSessionStatus, string> = {
    CREATED: '已创建',
    DIAGNOSING: '诊断中',
    PLANNING: '规划中',
    TEACHING: '教学中',
    PRACTICE: '练习中',
    REFLECTION: '复盘中',
    COMPLETED: '已完成'
  }
  return labels[status] || status
}

function learningSessionStatusTag(status: LearningSessionStatus) {
  if (status === 'COMPLETED') {
    return 'success'
  }
  if (status === 'PRACTICE' || status === 'REFLECTION') {
    return 'warning'
  }
  if (status === 'CREATED') {
    return 'info'
  }
  return 'primary'
}

function strategyLabel(strategy: string) {
  const labels: Record<string, string> = {
    concept_first: '概念优先',
    example_first: '案例优先',
    source_code_first: '源码优先',
    prerequisite_first: '前置补齐',
    practice_first: '练习优先',
    debug_misconception: '纠偏讲解',
    summary_review: '总结复盘'
  }
  return labels[strategy] || strategy
}

function masteryLabel(masteryLevel: KnowledgeMapPrerequisite['masteryLevel']) {
  if (!masteryLevel) {
    return '未记录'
  }
  return `${masteryLevel}%`
}

function trustLabel(document: LearningDocument) {
  if (document.chunkCount > 0 && document.processStatus === 'completed') {
    return '可引用'
  }
  if (document.chunkCount > 0) {
    return '部分可引用'
  }
  return '待处理'
}

async function runTutorAction(action: TutorAction) {
  if (!action.routeName) {
    return
  }

  const query: Record<string, string> = {}
  const payload = action.payload || {}
  if (payload.knowledgePointId) {
    query.knowledgePointId = String(payload.knowledgePointId)
  }
  if (payload.subject) {
    query.subject = String(payload.subject)
  }
  if (payload.topic) {
    query.topic = String(payload.topic)
    query.source = 'chat'
  }

  await router.push({ name: action.routeName, query })
}

async function send() {
  await sendPrompt(draft.value)
}

async function sendPrompt(content: string) {
  const message = content.trim()
  if (!message) {
    return
  }
  if (content === draft.value) {
    draft.value = ''
  }
  try {
    await workspaceStore.sendMessage(message)
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '发送失败')
  }
}

async function completeLearningSession() {
  try {
    await workspaceStore.completeActiveLearningSession()
    await loadProgressSummary()
    ElMessage.success('本次学习会话已完成')
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '完成学习会话失败')
  }
}

function goLearningPath(knowledgePointId?: number) {
  if (knowledgePointId) {
    router.push({ name: 'learn', query: { knowledgePointId: String(knowledgePointId) } })
    return
  }
  router.push({ name: 'learn', query: activeTopicLabel.value ? { topic: activeTopicLabel.value, source: 'chat' } : {} })
}

function goPractice() {
  if (activeKnowledgeMap.value?.knowledgePointId) {
    router.push({ name: 'practice', query: { knowledgePointId: String(activeKnowledgeMap.value.knowledgePointId) } })
    return
  }
  router.push({ name: 'practice', query: activeTopicLabel.value ? { topic: activeTopicLabel.value, source: 'chat' } : {} })
}

function goKnowledgeGraph() {
  const query: Record<string, string> = {
    subject: activeKnowledgeMap.value?.subject || 'Java'
  }
  if (activeKnowledgeMap.value?.knowledgePointId) {
    query.focusKnowledgePointId = String(activeKnowledgeMap.value.knowledgePointId)
  }
  router.push({ name: 'knowledgeGraph', query })
}
</script>
