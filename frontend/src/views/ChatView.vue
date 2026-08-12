<template>
  <div class="chat-workspace" :class="{ 'threads-collapsed': threadCollapsed }">
    <aside class="chat-thread-list panel" :class="{ collapsed: threadCollapsed }">
      <div class="thread-panel-head" :class="{ collapsed: threadCollapsed }">
        <div v-if="!threadCollapsed">
          <p class="eyebrow">Conversations</p>
          <h2>学习会话</h2>
        </div>
        <div class="thread-actions">
          <el-tooltip :content="threadCollapsed ? '展开会话' : '收起会话'" placement="right">
            <el-button
              :aria-label="threadCollapsed ? '展开会话' : '收起会话'"
              :icon="threadCollapsed ? ArrowRight : ArrowLeft"
              circle
              @click="toggleThreads"
            />
          </el-tooltip>
          <el-tooltip content="新学习会话" placement="right">
            <el-button aria-label="新学习会话" :icon="Plus" circle @click="createNewConversation" />
          </el-tooltip>
        </div>
      </div>

      <template v-if="!threadCollapsed">
        <el-input v-model.trim="threadQuery" class="chat-search" :prefix-icon="Search" placeholder="搜索会话..." />

        <div v-loading="workspaceStore.loadingConversations" class="thread-scroll">
          <button
            v-for="conversation in filteredConversations"
            :key="conversation.id"
            class="conversation-row"
            :class="{ active: workspaceStore.currentConversationId === conversation.id }"
            type="button"
            @click="workspaceStore.selectConversation(conversation.id)"
          >
            <span>
              <strong>{{ conversation.title || '未命名会话' }}</strong>
              <small>{{ modeLabel(conversation.mode) }} · {{ formatTime(conversation.updateTime) }}</small>
            </span>
          </button>
          <el-empty
            v-if="!workspaceStore.loadingConversations && filteredConversations.length === 0"
            description="暂无会话"
            :image-size="82"
          />
        </div>
      </template>
    </aside>

    <section class="chat-main panel">
      <header class="chat-main-head">
        <div>
          <p class="eyebrow">{{ currentModeLabel }}</p>
          <h2>{{ currentChatTitle }}</h2>
        </div>
        <div class="chat-head-actions">
          <el-tag v-if="workspaceStore.currentConversation" effect="plain">
            {{ modeLabel(workspaceStore.currentConversation.mode) }}
          </el-tag>
          <el-tooltip :content="historySearchOpen ? '关闭记录搜索' : '查找聊天记录'" placement="bottom">
            <el-button
              :aria-label="historySearchOpen ? '关闭记录搜索' : '查找聊天记录'"
              :icon="Search"
              circle
              @click="toggleHistorySearch"
            />
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

      <section v-if="workspaceStore.activeLearningSession" class="learning-session-strip">
        <div>
          <p class="eyebrow">Active Learning Session</p>
          <h3>{{ workspaceStore.activeLearningSession.goal }}</h3>
          <p>{{ workspaceStore.activeLearningSession.nextAction || '继续围绕当前目标学习' }}</p>
          <ul v-if="workspaceStore.activeLearningSession.strategySource?.length" class="strategy-source-list">
            <li v-for="source in workspaceStore.activeLearningSession.strategySource" :key="source">{{ source }}</li>
          </ul>
        </div>
        <div class="learning-session-meta">
          <el-tag :type="learningSessionStatusTag(workspaceStore.activeLearningSession.status)" effect="dark">
            {{ learningSessionStatusLabel(workspaceStore.activeLearningSession.status) }}
          </el-tag>
          <el-tag v-if="workspaceStore.activeLearningSession.teachingStrategy" effect="plain">
            {{ strategyLabel(workspaceStore.activeLearningSession.teachingStrategy) }}
          </el-tag>
        </div>
      </section>

      <div ref="messageScroller" v-loading="workspaceStore.loadingMessages" class="message-list wide">
        <div v-if="workspaceStore.messages.length === 0" class="empty-chat">
          <h3>把问题交给 AI Tutor</h3>
          <p>可以问概念、代码、学习路径，也可以让它按你的档案调整讲解方式。</p>
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
  </div>
</template>

<script setup lang="ts">
import { computed, nextTick, onMounted, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import { ArrowDown, ArrowLeft, ArrowRight, ArrowUp, Close, Plus, Promotion, Search } from '@element-plus/icons-vue'
import { ElMessage } from 'element-plus'
import { useWorkspaceStore } from '../stores/workspace'
import type { LearningSessionStatus, TutorAction } from '../types/domain'
import { formatTime, modeLabel } from '../utils/format'
import { renderMarkdown } from '../utils/markdown'

const workspaceStore = useWorkspaceStore()
const router = useRouter()
const draft = ref('')
const threadQuery = ref('')
const threadCollapsed = ref(false)
const historySearchOpen = ref(false)
const historyQuery = ref('')
const activeResultIndex = ref(0)
const messageScroller = ref<HTMLElement | null>(null)

interface HistorySearchResult {
  resultIndex: number
  messageIndex: number
  roleLabel: string
  snippet: string
}

const currentModeLabel = computed(() => modeLabel(workspaceStore.currentConversation?.mode || 'chat'))
const currentChatTitle = computed(() => {
  if (workspaceStore.currentConversation?.title) {
    return workspaceStore.currentConversation.title
  }
  return workspaceStore.draftConversation ? '输入第一句话后自动命名' : 'AI 学习问答'
})
const filteredConversations = computed(() => {
  const keyword = threadQuery.value.toLowerCase()
  if (!keyword) {
    return workspaceStore.conversations
  }
  return workspaceStore.conversations.filter((item) =>
    `${item.title} ${modeLabel(item.mode)}`.toLowerCase().includes(keyword)
  )
})
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
    await workspaceStore.loadConversations()
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

async function createNewConversation() {
  workspaceStore.startDraftConversation()
}

function toggleThreads() {
  threadCollapsed.value = !threadCollapsed.value
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
  const content = draft.value.trim()
  if (!content) {
    return
  }
  draft.value = ''
  try {
    await workspaceStore.sendMessage(content)
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '发送失败')
  }
}
</script>
