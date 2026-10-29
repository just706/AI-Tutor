<template>
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
      @click="emit('navigate')"
    >
      <el-icon><ChatDotRound /></el-icon>
      <span>AI Chat</span>
    </RouterLink>
    <RouterLink
      class="workspace-nav-item"
      :to="{ name: 'chat', query: { ...conversationQuery, panel: 'sources' } }"
      :class="{ active: route.name === 'chat' && route.query.panel === 'sources' }"
      @click="emit('navigate')"
    >
      <el-icon><Collection /></el-icon>
      <span>Sources</span>
    </RouterLink>
    <RouterLink
      class="workspace-nav-item"
      :to="{ name: 'chat', query: { ...conversationQuery, panel: 'progress' } }"
      :class="{ active: route.name === 'chat' && route.query.panel === 'progress' }"
      @click="emit('navigate')"
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
    <RouterLink class="workspace-nav-item soft" :to="{ name: 'profile' }" @click="emit('navigate')">
      <el-icon><Setting /></el-icon>
      <span>学习档案</span>
    </RouterLink>
    <button class="workspace-nav-item soft as-button" type="button" @click="logout">
      <el-icon><SwitchButton /></el-icon>
      <span>退出登录</span>
    </button>
  </div>
</template>

<script setup lang="ts">
import { computed } from 'vue'
import { RouterLink, useRoute, useRouter } from 'vue-router'
import { ChatDotRound, Collection, DataAnalysis, Plus, Setting, SwitchButton } from '@element-plus/icons-vue'
import { useAuthStore } from '../stores/auth'
import { useWorkspaceStore } from '../stores/workspace'
import { formatTime, modeLabel } from '../utils/format'

const emit = defineEmits<{ navigate: [] }>()
const route = useRoute()
const router = useRouter()
const authStore = useAuthStore()
const workspaceStore = useWorkspaceStore()
const conversationQuery = computed(() => ({ conversationId: workspaceStore.currentConversationId || undefined }))

async function newStudySession() {
  workspaceStore.startDraftConversation()
  await router.push({ name: 'chat' })
  emit('navigate')
}

async function selectConversation(conversationId: number) {
  await router.push({ name: 'chat', query: { conversationId } })
  emit('navigate')
}

async function logout() {
  workspaceStore.reset()
  authStore.logout()
  await router.push('/login')
  emit('navigate')
}
</script>
