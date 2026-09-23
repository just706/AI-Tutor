<template>
  <main class="auth-page">
    <section class="auth-panel">
      <div class="auth-brand">
        <div class="brand-mark large">AI</div>
        <h1>AI Tutor</h1>
        <p>登录后继续你的学习工作台</p>
      </div>

      <el-form class="auth-form" :model="form" label-position="top" @submit.prevent="submit">
        <el-form-item label="用户名">
          <el-input v-model.trim="form.username" size="large" autocomplete="username" />
        </el-form-item>
        <el-form-item label="密码">
          <el-input
            v-model="form.password"
            size="large"
            type="password"
            autocomplete="current-password"
            show-password
          />
        </el-form-item>
        <el-button class="auth-submit" type="primary" size="large" :loading="authStore.loading" @click="submit">
          登录
        </el-button>
      </el-form>

      <div class="auth-switch">
        <span>还没有账号？</span>
        <RouterLink to="/register">注册</RouterLink>
      </div>
    </section>
  </main>
</template>

<script setup lang="ts">
import { ElMessage } from 'element-plus'
import { reactive } from 'vue'
import { RouterLink, useRouter } from 'vue-router'
import { useAuthStore } from '../stores/auth'

const router = useRouter()
const authStore = useAuthStore()
const form = reactive({
  username: '',
  password: ''
})

async function submit() {
  if (!form.username || !form.password) {
    ElMessage.warning('请输入用户名和密码')
    return
  }
  try {
    await authStore.login(form.username, form.password)
    await router.push('/chat')
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '登录失败')
  }
}
</script>
