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

      <nav class="workspace-nav" aria-label="主导航">
        <RouterLink
          v-for="item in navItems"
          :key="item.name"
          class="workspace-nav-item"
          :to="{ name: item.name }"
          :class="{ active: route.name === item.name }"
        >
          <el-icon><component :is="item.icon" /></el-icon>
          <span>{{ item.label }}</span>
        </RouterLink>
      </nav>

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
import type { Component } from 'vue'
import { computed, onMounted } from 'vue'
import { RouterLink, RouterView, useRoute, useRouter } from 'vue-router'
import {
  ChatDotRound,
  Collection,
  DataAnalysis,
  EditPen,
  House,
  MagicStick,
  Plus,
  Reading,
  Setting,
  SwitchButton,
  User
} from '@element-plus/icons-vue'
import { ElMessage } from 'element-plus'
import { useAuthStore } from '../stores/auth'
import { useWorkspaceStore } from '../stores/workspace'

interface NavItem {
  name: string
  label: string
  title: string
  icon: Component
}

const route = useRoute()
const router = useRouter()
const authStore = useAuthStore()
const workspaceStore = useWorkspaceStore()

const navItems: NavItem[] = [
  { name: 'dashboard', label: 'Dashboard', title: '学习总览', icon: House },
  { name: 'chat', label: 'AI Chat', title: 'AI Chat', icon: ChatDotRound },
  { name: 'learn', label: 'Learning Path', title: '学习路径', icon: Reading },
  { name: 'practice', label: 'Practice', title: '练习', icon: EditPen },
  { name: 'library', label: 'Knowledge Library', title: '知识资料库', icon: Collection },
  { name: 'analysis', label: 'Learning Analysis', title: '学习分析', icon: DataAnalysis },
  { name: 'agent', label: 'Agent Suggestions', title: 'Agent 建议', icon: MagicStick }
]

const currentRoute = computed(() =>
  navItems.find((item) => item.name === route.name)
    || (route.name === 'profile' ? { label: 'Profile', title: '学习档案' } : null)
)
const currentRouteLabel = computed(() => currentRoute.value?.label || 'Learning Workspace')
const currentRouteTitle = computed(() => currentRoute.value?.title || 'AI Tutor')

onMounted(async () => {
  try {
    await Promise.all([workspaceStore.loadProfile(), workspaceStore.loadConversations()])
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '工作台加载失败')
  }
})

async function newStudySession() {
  workspaceStore.startDraftConversation()
  await router.push({ name: 'chat' })
}

async function logout() {
  workspaceStore.reset()
  authStore.logout()
  await router.push('/login')
}
</script>
