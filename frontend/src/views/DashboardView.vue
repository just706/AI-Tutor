<template>
  <div class="dashboard-page">
    <section class="action-strip">
      <button class="hero-action primary" type="button" @click="continueChat">
        <el-icon><ChatDotRound /></el-icon>
        <span>
          <strong>继续聊天</strong>
          <small>{{ latestConversation?.title || '输入第一句后自动命名' }}</small>
        </span>
      </button>
      <button class="hero-action" type="button" @click="router.push({ name: 'learn' })">
        <el-icon><Reading /></el-icon>
        <span>
          <strong>开始学习</strong>
          <small>{{ primaryWeakPoint?.knowledgePointName || '从知识点进入教学或练习' }}</small>
        </span>
      </button>
      <button class="hero-action" type="button" @click="router.push({ name: 'agent' })">
        <el-icon><MagicStick /></el-icon>
        <span>
          <strong>查看建议</strong>
          <small>{{ pendingSuggestions.length }} 条待处理 Agent 建议</small>
        </span>
      </button>
    </section>

    <section class="dashboard-overview" v-loading="loading">
      <div class="overview-main panel">
        <div class="section-head">
          <div>
            <p class="eyebrow">Today</p>
            <h2>推荐行动</h2>
          </div>
          <el-button text type="primary" @click="router.push({ name: 'agent' })">查看全部</el-button>
        </div>

        <div class="recommended-list">
          <article v-for="action in recommendedActions" :key="action.title" class="recommended-item">
            <el-icon><component :is="action.icon" /></el-icon>
            <div>
              <strong>{{ action.title }}</strong>
              <p>{{ action.detail }}</p>
            </div>
          </article>
        </div>
      </div>

      <aside class="overview-side panel">
        <p class="eyebrow">Learning Signal</p>
        <h2>当前状态</h2>
        <div class="signal-stack">
          <div class="signal-row">
            <span>平均掌握</span>
            <strong>{{ overview?.averageMasteryLevel ?? 0 }}%</strong>
          </div>
          <el-progress :percentage="overview?.averageMasteryLevel ?? 0" :show-text="false" :stroke-width="8" />
          <div class="signal-row">
            <span>答题正确率</span>
            <strong>{{ overview?.answerAccuracy ?? 0 }}%</strong>
          </div>
          <el-progress :percentage="overview?.answerAccuracy ?? 0" :show-text="false" :stroke-width="8" />
          <div class="signal-row">
            <span>学习时长</span>
            <strong>{{ formatMinutes(overview?.totalStudyTime) }}</strong>
          </div>
        </div>
      </aside>
    </section>

    <section class="dashboard-lower">
      <div class="panel">
        <div class="section-head">
          <div>
            <p class="eyebrow">Focus</p>
            <h2>优先关注</h2>
          </div>
          <el-button text type="primary" @click="router.push({ name: 'analysis' })">学习分析</el-button>
        </div>
        <div v-if="weakPoints.length === 0" class="empty-line">暂无明显薄弱点，继续保持节奏。</div>
        <article v-for="point in weakPoints" :key="point.knowledgePointId" class="focus-row">
          <div>
            <strong>{{ point.knowledgePointName }}</strong>
            <p>{{ point.reason }}</p>
          </div>
          <el-tag type="warning" effect="plain">{{ point.masteryLevel ?? point.answerAccuracy ?? 0 }}%</el-tag>
        </article>
      </div>

      <div class="panel">
        <div class="section-head">
          <div>
            <p class="eyebrow">Recent</p>
            <h2>最近会话</h2>
          </div>
          <el-button text type="primary" @click="router.push({ name: 'chat' })">进入聊天</el-button>
        </div>
        <button
          v-for="conversation in recentConversations"
          :key="conversation.id"
          class="conversation-row"
          type="button"
          @click="openConversation(conversation.id)"
        >
          <span>
            <strong>{{ conversation.title || '未命名会话' }}</strong>
            <small>{{ modeLabel(conversation.mode) }} · {{ formatTime(conversation.updateTime) }}</small>
          </span>
          <el-icon><ArrowRight /></el-icon>
        </button>
        <div v-if="recentConversations.length === 0" class="empty-line">还没有会话。</div>
      </div>

      <div class="panel accent-panel">
        <div class="section-head">
          <div>
            <p class="eyebrow">Agent</p>
            <h2>建议线索</h2>
          </div>
        </div>
        <article v-for="item in pendingSuggestions.slice(0, 3)" :key="item.id" class="agent-mini">
          <strong>{{ item.title }}</strong>
          <p>{{ item.reason || item.suggestion }}</p>
        </article>
        <div v-if="pendingSuggestions.length === 0" class="empty-line">暂无待处理建议。</div>
      </div>
    </section>
  </div>
</template>

<script setup lang="ts">
import type { Component } from 'vue'
import { computed, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ArrowRight, ChatDotRound, EditPen, MagicStick, Reading } from '@element-plus/icons-vue'
import { ElMessage } from 'element-plus'
import { getLearningAnalysisOverview, listAgentSuggestions } from '../api'
import { useWorkspaceStore } from '../stores/workspace'
import type { AgentSuggestion, LearningAnalysisOverview, WeakKnowledgePoint } from '../types/domain'
import { formatMinutes, formatTime, modeLabel } from '../utils/format'

interface RecommendedAction {
  title: string
  detail: string
  icon: Component
}

const router = useRouter()
const workspaceStore = useWorkspaceStore()
const overview = ref<LearningAnalysisOverview | null>(null)
const agentSuggestions = ref<AgentSuggestion[]>([])
const loading = ref(false)

const latestConversation = computed(() => workspaceStore.conversations[0] || null)
const recentConversations = computed(() => workspaceStore.conversations.slice(0, 4))
const weakPoints = computed(() => overview.value?.weakKnowledgePoints.slice(0, 3) || [])
const primaryWeakPoint = computed<WeakKnowledgePoint | null>(() => weakPoints.value[0] || null)
const pendingSuggestions = computed(() => agentSuggestions.value.filter((item) => item.status === 'pending'))

const recommendedActions = computed<RecommendedAction[]>(() => [
  {
    title: primaryWeakPoint.value ? `复习 ${primaryWeakPoint.value.knowledgePointName}` : '补全学习档案',
    detail: primaryWeakPoint.value?.reason || '让 AI Tutor 先了解你的方向、目标和当前水平。',
    icon: Reading
  },
  {
    title: '做一组练习',
    detail: overview.value?.answerAccuracy
      ? `当前正确率 ${overview.value.answerAccuracy}%，用练习校准掌握程度。`
      : '从知识点进入练习，先生成 1-3 道题。',
    icon: EditPen
  },
  {
    title: pendingSuggestions.value[0]?.title || '生成学习建议',
    detail: pendingSuggestions.value[0]?.reason || '让 Agent 根据最近学习记录给出下一步建议。',
    icon: MagicStick
  }
])

onMounted(loadDashboard)

async function loadDashboard() {
  loading.value = true
  try {
    const [overviewResult, suggestionResult] = await Promise.all([
      getLearningAnalysisOverview(),
      listAgentSuggestions('pending')
    ])
    overview.value = overviewResult
    agentSuggestions.value = suggestionResult
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '加载 Dashboard 失败')
  } finally {
    loading.value = false
  }
}

async function continueChat() {
  try {
    if (latestConversation.value) {
      await workspaceStore.selectConversation(latestConversation.value.id)
    } else {
      workspaceStore.startDraftConversation()
    }
    await router.push({ name: 'chat' })
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '打开会话失败')
  }
}

async function openConversation(conversationId: number) {
  await workspaceStore.selectConversation(conversationId)
  await router.push({ name: 'chat' })
}
</script>
