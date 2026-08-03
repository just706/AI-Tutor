import { defineStore } from 'pinia'
import { computed, ref } from 'vue'
import { getCurrentUser, login as loginApi, register as registerApi } from '../api'
import { TOKEN_KEY } from '../api/http'
import type { User } from '../types/domain'

export const useAuthStore = defineStore('auth', () => {
  const token = ref(localStorage.getItem(TOKEN_KEY) || '')
  const user = ref<User | null>(null)
  const loading = ref(false)

  const isAuthenticated = computed(() => Boolean(token.value))

  function setToken(nextToken: string) {
    token.value = nextToken
    localStorage.setItem(TOKEN_KEY, nextToken)
  }

  async function login(username: string, password: string) {
    loading.value = true
    try {
      const result = await loginApi(username, password)
      setToken(result.token)
      user.value = result.user
    } finally {
      loading.value = false
    }
  }

  async function register(username: string, password: string) {
    loading.value = true
    try {
      await registerApi(username, password)
      await login(username, password)
    } finally {
      loading.value = false
    }
  }

  async function loadCurrentUser() {
    if (!token.value) {
      return
    }
    user.value = await getCurrentUser()
  }

  function logout() {
    token.value = ''
    user.value = null
    localStorage.removeItem(TOKEN_KEY)
  }

  return {
    token,
    user,
    loading,
    isAuthenticated,
    login,
    register,
    loadCurrentUser,
    logout
  }
})
