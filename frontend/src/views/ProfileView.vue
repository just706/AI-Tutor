<template>
  <div class="profile-page">
    <section class="profile-editor panel">
      <div class="section-head">
        <div>
          <p class="eyebrow">Profile</p>
          <h2>学习档案</h2>
        </div>
        <el-button type="primary" :icon="Check" @click="saveProfile">保存档案</el-button>
      </div>

      <el-form v-loading="workspaceStore.loadingProfile" label-position="top" class="profile-form">
        <el-form-item label="学习方向">
          <el-input v-model="workspaceStore.profile.learningDirection" placeholder="Java / Python / 数学" />
        </el-form-item>
        <el-form-item label="学习目标">
          <el-input v-model="workspaceStore.profile.learningGoal" placeholder="就业 / 考试 / 项目实践" />
        </el-form-item>
        <el-form-item label="当前水平">
          <el-input v-model="workspaceStore.profile.currentLevel" placeholder="基础 / 进阶 / 熟练" />
        </el-form-item>
        <el-form-item label="学习偏好">
          <el-input
            v-model="workspaceStore.profile.learningPreference"
            type="textarea"
            resize="none"
            :rows="5"
            placeholder="例如：案例讲解、循序渐进、先给结论再给代码"
          />
        </el-form-item>
      </el-form>
    </section>

    <aside class="profile-side panel">
      <p class="eyebrow">Personalization</p>
      <h2>AI 会如何使用档案</h2>
      <p>
        学习档案会影响普通聊天、教学讲解、练习反馈和 Agent 建议的表达方式。先填清楚方向和目标，后面再逐步细化。
      </p>
      <div class="context-block">
        <span>当前用户</span>
        <strong>{{ authStore.user?.nickname || authStore.user?.username || '未加载' }}</strong>
      </div>
    </aside>
  </div>
</template>

<script setup lang="ts">
import { onMounted } from 'vue'
import { Check } from '@element-plus/icons-vue'
import { ElMessage } from 'element-plus'
import { useAuthStore } from '../stores/auth'
import { useWorkspaceStore } from '../stores/workspace'

const authStore = useAuthStore()
const workspaceStore = useWorkspaceStore()

onMounted(async () => {
  try {
    await workspaceStore.loadProfile()
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '加载学习档案失败')
  }
})

async function saveProfile() {
  try {
    await workspaceStore.saveProfile()
    ElMessage.success('学习档案已保存')
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '保存失败')
  }
}
</script>
