<template>
  <main class="auth-page">
    <section class="auth-panel">
      <div class="auth-brand">
        <div class="brand-mark large">AI</div>
        <h1>AI Tutor</h1>
        <p>创建账号后开始学习</p>
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
            autocomplete="new-password"
            show-password
          />
        </el-form-item>
        <el-button class="auth-submit" type="primary" size="large" :loading="authStore.loading" @click="submit">
          注册
        </el-button>
      </el-form>

      <div class="auth-switch">
        <span>已有账号？</span>
        <RouterLink to="/login">登录</RouterLink>
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
  if (form.username.length < 3 || form.password.length < 6) {
    ElMessage.warning('用户名至少 3 位，密码至少 6 位')
    return
  }
  try {
    await authStore.register(form.username, form.password)
    await router.push('/dashboard')
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '注册失败')
  }
}
</script>
