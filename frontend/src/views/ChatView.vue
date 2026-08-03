<template>
  <main class="app-shell">
    <aside class="sidebar">
      <div class="sidebar-head">
        <div>
          <div class="product-name">AI Tutor</div>
          <div class="user-line">{{ authStore.user?.nickname || authStore.user?.username }}</div>
        </div>
        <el-button :icon="SwitchButton" circle @click="logout" />
      </div>

      <el-button class="new-conversation" type="primary" :icon="Plus" @click="createNewConversation">
        新会话
      </el-button>

      <div v-loading="workspaceStore.loadingConversations" class="conversation-list">
        <button
          v-for="conversation in workspaceStore.conversations"
          :key="conversation.id"
          class="conversation-item"
          :class="{ active: workspaceStore.currentConversationId === conversation.id }"
          @click="workspaceStore.selectConversation(conversation.id)"
        >
          <span>{{ conversation.title || '未命名会话' }}</span>
          <small>{{ formatTime(conversation.updateTime) }}</small>
        </button>

        <el-empty
          v-if="!workspaceStore.loadingConversations && workspaceStore.conversations.length === 0"
          description="暂无会话"
          :image-size="80"
        />
      </div>
    </aside>

    <section class="chat-panel">
      <header class="chat-head">
        <div>
          <h2>{{ workspaceStore.currentConversation?.title || 'AI 学习问答' }}</h2>
          <p>结合你的学习档案和当前会话上下文回答</p>
        </div>
      </header>

      <div ref="messageScroller" v-loading="workspaceStore.loadingMessages" class="message-list">
        <div v-if="workspaceStore.messages.length === 0" class="empty-chat">
          <h3>开始一次学习问答</h3>
          <p>可以先在右侧补充学习档案，再向 AI 老师提问。</p>
        </div>

        <article
          v-for="(message, index) in workspaceStore.messages"
          :key="`${message.createTime}-${index}`"
          class="message"
          :class="message.role"
        >
          <div class="message-meta">{{ message.role === 'user' ? '我' : 'AI Tutor' }}</div>
          <div v-if="message.role === 'assistant'" class="message-bubble markdown-body" v-html="renderMarkdown(message.messageContent)" />
          <div v-else class="message-bubble">{{ message.messageContent }}</div>
        </article>
      </div>

      <footer class="composer">
        <el-input
          v-model="draft"
          type="textarea"
          resize="none"
          :autosize="{ minRows: 2, maxRows: 5 }"
          placeholder="输入学习问题"
          @keydown.enter.exact.prevent="send"
        />
        <el-button type="primary" :icon="Promotion" :loading="workspaceStore.sending" @click="send">
          发送
        </el-button>
      </footer>
    </section>

    <aside class="profile-panel">
      <div class="panel-head">
        <h2>学习档案</h2>
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
            :rows="4"
            placeholder="例如：案例讲解、循序渐进"
          />
        </el-form-item>
        <el-button class="save-profile" type="primary" :icon="Check" @click="saveProfile">
          保存档案
        </el-button>
      </el-form>
    </aside>
  </main>
</template>

<script setup lang="ts">
import { Check, Plus, Promotion, SwitchButton } from '@element-plus/icons-vue'
import { ElMessage } from 'element-plus'
import MarkdownIt from 'markdown-it'
import { nextTick, onMounted, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import { useAuthStore } from '../stores/auth'
import { useWorkspaceStore } from '../stores/workspace'

const router = useRouter()
const authStore = useAuthStore()
const workspaceStore = useWorkspaceStore()
const draft = ref('')
const messageScroller = ref<HTMLElement | null>(null)
const markdown = new MarkdownIt({
  html: false,
  linkify: true,
  breaks: true
})

onMounted(async () => {
  try {
    await Promise.all([workspaceStore.loadProfile(), workspaceStore.loadConversations()])
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '加载失败')
  }
})

watch(
  () => workspaceStore.messages.length,
  async () => {
    await nextTick()
    if (messageScroller.value) {
      messageScroller.value.scrollTop = messageScroller.value.scrollHeight
    }
  }
)

function renderMarkdown(content: string) {
  return markdown.render(content || '')
}

function formatTime(value: string) {
  if (!value) {
    return ''
  }
  return value.replace('T', ' ').slice(0, 16)
}

async function createNewConversation() {
  try {
    await workspaceStore.addConversation('新的学习会话')
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '创建会话失败')
  }
}

async function saveProfile() {
  try {
    await workspaceStore.saveProfile()
    ElMessage.success('学习档案已保存')
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '保存失败')
  }
}

async function send() {
  const content = draft.value.trim()
  if (!content) {
    return
  }
  draft.value = ''
  try {
    await workspaceStore.sendMessage(content)
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '发送失败')
  }
}

async function logout() {
  workspaceStore.reset()
  authStore.logout()
  await router.push('/login')
}
</script>
