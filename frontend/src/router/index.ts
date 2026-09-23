import { createRouter, createWebHistory } from 'vue-router'
import WorkspaceLayout from '../layouts/WorkspaceLayout.vue'
import { useAuthStore } from '../stores/auth'
import LoginView from '../views/LoginView.vue'
import RegisterView from '../views/RegisterView.vue'

const router = createRouter({
  history: createWebHistory(),
  routes: [
    {
      path: '/',
      redirect: '/chat'
    },
    {
      path: '/login',
      name: 'login',
      component: LoginView
    },
    {
      path: '/register',
      name: 'register',
      component: RegisterView
    },
    {
      path: '/',
      component: WorkspaceLayout,
      meta: { requiresAuth: true },
      children: [
        {
          path: 'dashboard',
          name: 'dashboard',
          component: () => import('../views/DashboardView.vue')
        },
        {
          path: 'chat',
          name: 'chat',
          component: () => import('../views/ChatView.vue')
        },
        {
          path: 'learn',
          name: 'learn',
          component: () => import('../views/LearningPathView.vue')
        },
        {
          path: 'knowledge-graph',
          name: 'knowledgeGraph',
          component: () => import('../views/KnowledgeGraphView.vue')
        },
        {
          path: 'practice',
          name: 'practice',
          component: () => import('../views/PracticeView.vue')
        },
        {
          path: 'library',
          name: 'library',
          component: () => import('../views/KnowledgeLibraryView.vue')
        },
        {
          path: 'analysis',
          name: 'analysis',
          component: () => import('../views/LearningAnalysisView.vue')
        },
        {
          path: 'agent',
          name: 'agent',
          component: () => import('../views/AgentSuggestionsView.vue')
        },
        {
          path: 'profile',
          name: 'profile',
          component: () => import('../views/ProfileView.vue')
        }
      ]
    }
  ]
})

router.beforeEach(async (to) => {
  const authStore = useAuthStore()
  const requiresAuth = to.matched.some((record) => record.meta.requiresAuth)

  if (requiresAuth && !authStore.isAuthenticated) {
    return '/login'
  }
  if ((to.name === 'login' || to.name === 'register') && authStore.isAuthenticated) {
    return '/chat'
  }
  if (requiresAuth && authStore.isAuthenticated && !authStore.user) {
    try {
      await authStore.loadCurrentUser()
    } catch {
      authStore.logout()
      return '/login'
    }
  }
})

export default router
