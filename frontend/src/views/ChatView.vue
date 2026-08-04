<template>
  <div class="chat-workspace">
    <aside class="chat-thread-list panel">
      <div class="section-head">
        <div>
          <p class="eyebrow">Conversations</p>
          <h2>学习会话</h2>
        </div>
        <el-button :icon="Plus" circle @click="createNewConversation" />
      </div>
      <el-input v-model.trim="threadQuery" class="chat-search" :prefix-icon="Search" placeholder="搜索会话..." />

      <div v-loading="workspaceStore.loadingConversations" class="thread-scroll">
        <button
          v-for="conversation in filteredConversations"
          :key="conversation.id"
          class="conversation-row"
          :class="{ active: workspaceStore.currentConversationId === conversation.id }"
          type="button"
          @click="workspaceStore.selectConversation(conversation.id)"
        >
          <span>
            <strong>{{ conversation.title || '未命名会话' }}</strong>
            <small>{{ modeLabel(conversation.mode) }} · {{ formatTime(conversation.updateTime) }}</small>
          </span>
        </button>
        <el-empty
          v-if="!workspaceStore.loadingConversations && filteredConversations.length === 0"
          description="暂无会话"
          :image-size="82"
        />
      </div>
    </aside>

    <section class="chat-main panel">
      <header class="chat-main-head">
        <div>
          <p class="eyebrow">{{ currentModeLabel }}</p>
          <h2>{{ workspaceStore.currentConversation?.title || 'AI 学习问答' }}</h2>
        </div>
        <el-tag v-if="workspaceStore.currentConversation" effect="plain">
          {{ modeLabel(workspaceStore.currentConversation.mode) }}
        </el-tag>
      </header>

      <div ref="messageScroller" v-loading="workspaceStore.loadingMessages" class="message-list wide">
        <div v-if="workspaceStore.messages.length === 0" class="empty-chat">
          <h3>把问题丢给 AI Tutor</h3>
          <p>可以问概念、代码、学习路径，也可以让它按你的档案调整讲解方式。</p>
        </div>

        <article
          v-for="(message, index) in workspaceStore.messages"
          :key="`${message.createTime}-${index}`"
          class="message"
          :class="message.role"
        >
          <div class="message-meta">{{ message.role === 'user' ? '你' : 'AI Tutor' }}</div>
          <div
            v-if="message.role === 'assistant'"
            class="message-bubble markdown-body"
            v-html="renderMarkdown(message.messageContent)"
          />
          <div v-else class="message-bubble">{{ message.messageContent }}</div>
        </article>
      </div>

      <footer class="composer">
        <el-input
          v-model="draft"
          type="textarea"
          resize="none"
          :autosize="{ minRows: 2, maxRows: 5 }"
          placeholder="问一个问题，或让 AI Tutor 继续解释当前知识点..."
          @keydown.enter.exact.prevent="send"
        />
        <el-button type="primary" :icon="Promotion" :loading="workspaceStore.sending" @click="send">
          发送
        </el-button>
      </footer>
    </section>

  </div>
</template>

<script setup lang="ts">
import { computed, nextTick, onMounted, ref, watch } from 'vue'
import { Plus, Promotion, Search } from '@element-plus/icons-vue'
import { ElMessage } from 'element-plus'
import { useWorkspaceStore } from '../stores/workspace'
import { formatTime, modeLabel } from '../utils/format'
import { renderMarkdown } from '../utils/markdown'

const workspaceStore = useWorkspaceStore()
const draft = ref('')
const threadQuery = ref('')
const messageScroller = ref<HTMLElement | null>(null)

const currentModeLabel = computed(() => modeLabel(workspaceStore.currentConversation?.mode || 'chat'))
const filteredConversations = computed(() => {
  const keyword = threadQuery.value.toLowerCase()
  if (!keyword) {
    return workspaceStore.conversations
  }
  return workspaceStore.conversations.filter((item) =>
    `${item.title} ${modeLabel(item.mode)}`.toLowerCase().includes(keyword)
  )
})

onMounted(async () => {
  try {
    await workspaceStore.loadConversations()
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '加载聊天失败')
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

async function createNewConversation() {
  try {
    await workspaceStore.addConversation('新的学习会话', 'chat')
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '创建会话失败')
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
</script>
