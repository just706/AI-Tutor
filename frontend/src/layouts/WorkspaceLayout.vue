<template>
  <main class="workspace-shell">
    <aside class="workspace-sidebar">
      <div class="brand-block">
        <div class="brand-mark">AI</div>
        <div>
          <div class="brand-name">AI Tutor</div>
          <div class="brand-subtitle">Learning Workspace</div>
        </div>
      </div>

      <el-button class="sidebar-primary" type="primary" :icon="Plus" @click="newStudySession">
        新学习会话
      </el-button>

      <nav class="workspace-nav workspace-hub-nav" aria-label="主导航">
        <RouterLink
          class="workspace-nav-item"
          :to="{ name: 'chat', query: conversationQuery }"
          :class="{ active: route.name === 'chat' && !route.query.panel }"
        >
          <el-icon><ChatDotRound /></el-icon>
          <span>AI Chat</span>
        </RouterLink>
        <RouterLink
          class="workspace-nav-item"
          :to="{ name: 'chat', query: { ...conversationQuery, panel: 'sources' } }"
          :class="{ active: route.name === 'chat' && route.query.panel === 'sources' }"
        >
          <el-icon><Collection /></el-icon>
          <span>Sources</span>
        </RouterLink>
        <RouterLink
          class="workspace-nav-item"
          :to="{ name: 'chat', query: { ...conversationQuery, panel: 'progress' } }"
          :class="{ active: route.name === 'chat' && route.query.panel === 'progress' }"
        >
          <el-icon><DataAnalysis /></el-icon>
          <span>Progress</span>
        </RouterLink>
      </nav>

      <section class="sidebar-conversations">
        <div class="sidebar-section-head">
          <span>最近会话</span>
          <el-button text type="primary" size="small" @click="workspaceStore.loadConversations()">刷新</el-button>
        </div>
        <div v-loading="workspaceStore.loadingConversations" class="sidebar-conversation-list">
          <button
            v-for="conversation in workspaceStore.conversations.slice(0, 8)"
            :key="conversation.id"
            class="sidebar-conversation-item"
            :class="{ active: workspaceStore.currentConversationId === conversation.id && route.name === 'chat' }"
            type="button"
            @click="selectConversation(conversation.id)"
          >
            <strong>{{ conversation.title || '未命名会话' }}</strong>
            <small>{{ modeLabel(conversation.mode) }} · {{ formatTime(conversation.updateTime) }}</small>
          </button>
          <div v-if="!workspaceStore.loadingConversations && workspaceStore.conversations.length === 0" class="sidebar-empty">
            暂无会话
          </div>
        </div>
      </section>

      <div class="sidebar-footer">
        <RouterLink class="workspace-nav-item soft" :to="{ name: 'profile' }">
          <el-icon><Setting /></el-icon>
          <span>学习档案</span>
        </RouterLink>
        <button class="workspace-nav-item soft as-button" type="button" @click="logout">
          <el-icon><SwitchButton /></el-icon>
          <span>退出登录</span>
        </button>
      </div>
    </aside>

    <section class="workspace-main">
      <header class="workspace-topbar">
        <div>
          <p class="eyebrow">{{ currentRouteLabel }}</p>
          <h1>{{ currentRouteTitle }}</h1>
        </div>
        <div class="topbar-actions">
          <el-tag v-if="workspaceStore.profile.learningDirection" effect="plain">
            {{ workspaceStore.profile.learningDirection }}
          </el-tag>
          <el-button :icon="User" circle @click="router.push({ name: 'profile' })" />
        </div>
      </header>

      <div class="workspace-content">
        <RouterView />
      </div>
    </section>
  </main>
</template>

<script setup lang="ts">
import { computed, onMounted, watch } from 'vue'
import { RouterLink, RouterView, useRoute, useRouter } from 'vue-router'
import {
  ChatDotRound,
  Collection,
  DataAnalysis,
  Plus,
  Setting,
  SwitchButton,
  User
} from '@element-plus/icons-vue'
import { ElMessage } from 'element-plus'
import { useAuthStore } from '../stores/auth'
import { useWorkspaceStore } from '../stores/workspace'
import { formatTime, modeLabel } from '../utils/format'

const route = useRoute()
const router = useRouter()
const authStore = useAuthStore()
const workspaceStore = useWorkspaceStore()
const conversationQuery = computed(() => ({ conversationId: workspaceStore.currentConversationId || undefined }))

const routeLabels: Record<string, { label: string; title: string }> = {
  chat: { label: 'AI Tutor Workspace', title: 'AI Chat' },
  learn: { label: 'Learning Path Detail', title: '学习路径详情' },
  knowledgeGraph: { label: 'Knowledge Graph', title: '知识关系图谱' },
  practice: { label: 'Practice Detail', title: '练习详情' },
  library: { label: 'Sources Detail', title: '资料详情' },
  analysis: { label: 'Progress Detail', title: '学习分析详情' },
  agent: { label: 'Agent Suggestions', title: 'Agent 建议详情' },
  dashboard: { label: 'Progress Detail', title: '学习总览详情' },
  profile: { label: 'Profile', title: '学习档案' }
}

const currentRoute = computed(() => routeLabels[String(route.name)] || null)
const currentRouteLabel = computed(() => currentRoute.value?.label || 'Learning Workspace')
const currentRouteTitle = computed(() => currentRoute.value?.title || 'AI Tutor')

onMounted(async () => {
  try {
    await Promise.all([workspaceStore.loadProfile(), workspaceStore.loadConversations(Number(route.query.conversationId) || undefined)])
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '工作台加载失败')
  }
})

watch(() => route.query.conversationId, async value => {
  const id = Number(value)
  if (!Number.isSafeInteger(id) || id <= 0 || id === workspaceStore.currentConversationId || workspaceStore.loadingConversations) return
  try { await workspaceStore.selectConversation(id) }
  catch (error) { ElMessage.error(error instanceof Error ? error.message : '加载会话失败') }
})

watch([() => workspaceStore.currentConversationId, () => route.name], async ([id]) => {
  if (!['chat', 'library'].includes(String(route.name))) return
  if ((Number(route.query.conversationId) || null) === id) return
  await router.replace({ query: { ...route.query, conversationId: id || undefined } })
})

async function newStudySession() {
  workspaceStore.startDraftConversation()
  await router.push({ name: 'chat' })
}

async function selectConversation(conversationId: number) {
  await router.push({ name: 'chat', query: { conversationId } })
}

async function logout() {
  workspaceStore.reset()
  authStore.logout()
  await router.push('/login')
}
</script>
