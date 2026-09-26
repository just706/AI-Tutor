<template>
  <div v-if="message.role === 'user' && message.ragRequestId && message.ragStatus && message.ragStatus !== 'completed'"
    class="rag-retry" role="status">
    <span>{{ statusText }}</span>
    <el-button size="small" :disabled="workspace.sending || workspace.loadingMessages || workspace.savingDocuments" @click="retry">
      {{ message.ragStatus === 'processing' || message.ragStatus === 'uncertain' ? '检查回答' : '重试回答' }}
    </el-button>
  </div>
</template>

<script setup lang="ts">
import { computed } from 'vue'
import { ElMessage } from 'element-plus'
import type { ChatMessage } from '../types/domain'
import { useWorkspaceStore } from '../stores/workspace'

const props = defineProps<{ message: ChatMessage }>()
const workspace = useWorkspaceStore()
const statusText = computed(() => ({
  processing: '回答处理中，可稍后检查结果。',
  failed: '回答失败，问题已保存。重试将沿用当时的教材和提问对象。',
  interrupted: '上次回答已中断，可以重试。',
  uncertain: '暂时无法确认回答结果，请检查后继续。',
  completed: ''
}[props.message.ragStatus || 'uncertain']))

async function retry() {
  try { await workspace.retryRagMessage(props.message) }
  catch (error) { ElMessage.error(error instanceof Error ? error.message : '重试失败，请稍后再试') }
}
</script>

<style scoped>
.rag-retry { display: flex; flex-wrap: wrap; align-items: center; gap: 8px; margin-top: 8px; font-size: 13px; color: #866330; }
</style>
