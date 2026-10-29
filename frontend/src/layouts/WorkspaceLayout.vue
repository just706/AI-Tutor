<template>
  <main class="workspace-shell">
    <aside class="workspace-sidebar desktop-navigation">
      <WorkspaceNavigation />
    </aside>

    <section class="workspace-main">
      <header class="workspace-topbar">
        <el-tooltip content="打开导航" placement="bottom">
          <span class="workspace-menu-trigger">
            <el-button
              class="workspace-menu"
              aria-label="打开导航"
              aria-controls="mobile-workspace-navigation"
              :aria-expanded="navigationOpen"
              :icon="Menu"
              @click="navigationOpen = true"
            />
          </span>
        </el-tooltip>
        <div class="workspace-heading">
          <p class="eyebrow">{{ currentRouteLabel }}</p>
          <h1>{{ currentRouteTitle }}</h1>
        </div>
        <div class="topbar-actions">
          <el-tag v-if="workspaceStore.profile.learningDirection" effect="plain">
            {{ workspaceStore.profile.learningDirection }}
          </el-tag>
          <el-button aria-label="学习档案" :icon="User" circle @click="router.push({ name: 'profile' })" />
        </div>
      </header>

      <div class="workspace-content">
        <RouterView />
      </div>
    </section>
    <el-drawer
      v-model="navigationOpen"
      title="导航"
      direction="ltr"
      size="min(300px, calc(100vw - 32px))"
      class="mobile-navigation-drawer"
    >
      <aside id="mobile-workspace-navigation" class="workspace-sidebar">
        <WorkspaceNavigation @navigate="navigationOpen = false" />
      </aside>
    </el-drawer>
  </main>
</template>

<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { RouterView, useRoute, useRouter } from 'vue-router'
import { Menu, User } from '@element-plus/icons-vue'
import { ElMessage } from 'element-plus'
import WorkspaceNavigation from '../components/WorkspaceNavigation.vue'
import { useWorkspaceStore } from '../stores/workspace'

const route = useRoute()
const router = useRouter()
const workspaceStore = useWorkspaceStore()
const navigationOpen = ref(false)
const desktopViewport = window.matchMedia('(min-width: 861px)')
function closeDesktopNavigation(event: MediaQueryListEvent) {
  if (event.matches) navigationOpen.value = false
}
onMounted(() => desktopViewport.addEventListener('change', closeDesktopNavigation))
onBeforeUnmount(() => desktopViewport.removeEventListener('change', closeDesktopNavigation))
watch(() => route.fullPath, () => { navigationOpen.value = false })

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
</script>
