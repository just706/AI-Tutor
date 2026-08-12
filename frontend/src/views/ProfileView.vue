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
      <section class="memory-section">
        <div class="memory-section-head">
          <div>
            <p class="eyebrow">Long-term Memory</p>
            <h3>长期学习记忆</h3>
          </div>
          <el-button :icon="Refresh" circle :loading="loadingMemories" @click="loadMemories" />
        </div>
        <el-empty v-if="!loadingMemories && memories.length === 0" description="暂无长期记忆" :image-size="54" />
        <div v-else v-loading="loadingMemories" class="memory-list">
          <article v-for="memory in memories" :key="memory.id" class="memory-item">
            <div class="memory-item-head">
              <el-tag size="small" effect="plain">{{ memoryTypeLabel(memory.memoryType) }}</el-tag>
              <el-tag :type="memoryStatusType(memory.status)" size="small" effect="light">
                {{ memoryStatusLabel(memory.status) }}
              </el-tag>
            </div>
            <p>{{ memory.content }}</p>
            <small>{{ memory.topic ? `主题：${memory.topic}` : '全局学习偏好' }}</small>
            <div class="memory-actions">
              <el-button v-if="memory.status === 'ACTIVE'" size="small" text @click="suppressMemory(memory.id)">
                暂不使用
              </el-button>
              <el-popconfirm title="删除后无法恢复" confirm-button-text="删除" @confirm="deleteMemory(memory.id)">
                <template #reference>
                  <el-button size="small" text type="danger">删除</el-button>
                </template>
              </el-popconfirm>
            </div>
          </article>
        </div>
      </section>
    </aside>
  </div>
</template>

<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { Check, Refresh } from '@element-plus/icons-vue'
import { ElMessage } from 'element-plus'
import { deleteLearnerMemory, listLearnerMemories, suppressLearnerMemory } from '../api'
import { useAuthStore } from '../stores/auth'
import { useWorkspaceStore } from '../stores/workspace'
import type { LearnerMemory, LearnerMemoryStatus, LearnerMemoryType } from '../types/domain'

const authStore = useAuthStore()
const workspaceStore = useWorkspaceStore()
const memories = ref<LearnerMemory[]>([])
const loadingMemories = ref(false)

onMounted(async () => {
  try {
    await Promise.all([workspaceStore.loadProfile(), loadMemories()])
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

async function loadMemories() {
  loadingMemories.value = true
  try {
    memories.value = await listLearnerMemories()
  } finally {
    loadingMemories.value = false
  }
}

async function suppressMemory(memoryId: number) {
  try {
    await suppressLearnerMemory(memoryId)
    await loadMemories()
    ElMessage.success('该记忆不会再用于后续回答')
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '操作失败')
  }
}

async function deleteMemory(memoryId: number) {
  try {
    await deleteLearnerMemory(memoryId)
    memories.value = memories.value.filter((memory) => memory.id !== memoryId)
    ElMessage.success('已删除长期记忆')
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '删除失败')
  }
}

function memoryTypeLabel(type: LearnerMemoryType) {
  return {
    preference: '学习偏好',
    difficulty_pattern: '困难模式',
    misconception: '待澄清误解'
  }[type]
}

function memoryStatusLabel(status: LearnerMemoryStatus) {
  return {
    ACTIVE: '使用中',
    SUPPRESSED: '已抑制',
    EXPIRED: '已过期'
  }[status]
}

function memoryStatusType(status: LearnerMemoryStatus) {
  if (status === 'ACTIVE') return 'success'
  if (status === 'SUPPRESSED') return 'warning'
  return 'info'
}
</script>
