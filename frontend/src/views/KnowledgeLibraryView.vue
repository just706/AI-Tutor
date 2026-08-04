<template>
  <div class="library-page">
    <section class="library-qa panel">
      <div class="section-head">
        <div>
          <p class="eyebrow">Knowledge Library</p>
          <h2>资料问答</h2>
        </div>
        <el-button type="primary" :loading="ragSending" @click="createRagConversation">新建资料会话</el-button>
      </div>

      <div class="source-summary">
        <strong>{{ selectedDocumentIds.length }}</strong>
        <span>个资料来源参与回答</span>
      </div>

      <div class="rag-composer">
        <el-input
          v-model="ragQuestion"
          type="textarea"
          resize="none"
          :rows="5"
          placeholder="基于已上传资料提问，例如：请总结 HashMap 的核心知识点..."
        />
        <el-button type="primary" :icon="Promotion" :loading="ragSending" @click="sendRagQuestion">
          基于资料回答
        </el-button>
      </div>

      <div v-if="workspaceStore.currentConversation?.mode === 'rag'" class="rag-history">
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
      <el-empty v-else description="创建资料会话后开始问答" :image-size="90" />
    </section>

    <aside class="library-sources panel">
      <div class="section-head">
        <div>
          <p class="eyebrow">Sources</p>
          <h2>资料来源可信度</h2>
        </div>
        <el-upload accept=".txt,.md,.markdown,.pdf" :show-file-list="false" :http-request="uploadLearningDocument">
          <el-button :icon="Upload" :loading="uploadingDocument">上传</el-button>
        </el-upload>
      </div>

      <el-checkbox-group v-model="selectedDocumentIds" class="source-list">
        <article
          v-for="document in documents"
          :key="document.id"
          class="source-card"
          :class="{ active: selectedDocumentDetail?.id === document.id }"
        >
          <el-checkbox :label="document.id">
            <strong>{{ document.fileName }}</strong>
          </el-checkbox>
          <div class="source-meta">
            <el-tag size="small" effect="plain">{{ document.fileType }}</el-tag>
            <el-tag size="small" :type="document.chunkCount > 0 ? 'success' : 'warning'" effect="plain">
              {{ trustLabel(document) }}
            </el-tag>
            <span>{{ document.chunkCount }} 个片段</span>
          </div>
          <div class="source-actions">
            <el-button text type="primary" @click.stop="loadDocumentDetailFlow(document.id)">查看依据</el-button>
            <el-button text :loading="documentActionLoadingId === document.id" @click.stop="reprocessLearningDocument(document.id)">
              重处理
            </el-button>
            <el-button text type="danger" :loading="documentActionLoadingId === document.id" @click.stop="deleteLearningDocument(document.id)">
              删除
            </el-button>
          </div>
        </article>
      </el-checkbox-group>
      <el-empty v-if="documents.length === 0" description="还没有资料来源" :image-size="90" />
    </aside>

    <aside class="library-evidence panel">
      <div class="section-head">
        <div>
          <p class="eyebrow">Evidence</p>
          <h2>回答依据</h2>
        </div>
      </div>

      <div v-if="ragSources.length > 0" class="evidence-list">
        <article v-for="source in ragSources" :key="`${source.documentId}-${source.chunkIndex}`" class="evidence-card">
          <strong>{{ source.fileName }} · #{{ source.chunkIndex }}</strong>
          <p>{{ source.snippet }}</p>
        </article>
      </div>

      <div v-else-if="selectedDocumentDetail" v-loading="loadingDocumentDetail" class="document-preview">
        <strong>{{ selectedDocumentDetail.fileName }}</strong>
        <small>{{ selectedDocumentDetail.fileType }} · {{ selectedDocumentDetail.chunkCount }} 个片段</small>
        <p>{{ selectedDocumentDetail.preview || '暂无预览内容' }}</p>
        <div class="chunk-list">
          <article v-for="chunk in documentChunks.slice(0, 5)" :key="chunk.id" class="chunk-item">
            <strong>#{{ chunk.chunkIndex }}</strong>
            <p>{{ chunk.chunkText }}</p>
          </article>
        </div>
      </div>

      <div v-else class="empty-line">选择资料或完成一次资料问答后，这里会展示依据。</div>
    </aside>
  </div>
</template>

<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { Promotion, Upload } from '@element-plus/icons-vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import type { UploadRequestOptions } from 'element-plus'
import {
  deleteDocument,
  getDocument,
  listDocumentChunks,
  listDocuments,
  reprocessDocument,
  sendRagChat,
  uploadDocument
} from '../api'
import { useWorkspaceStore } from '../stores/workspace'
import type {
  DocumentChunk,
  LearningDocument,
  LearningDocumentDetail,
  RagSource
} from '../types/domain'
import { renderMarkdown } from '../utils/markdown'

const workspaceStore = useWorkspaceStore()
const documents = ref<LearningDocument[]>([])
const selectedDocumentIds = ref<number[]>([])
const selectedDocumentDetail = ref<LearningDocumentDetail | null>(null)
const documentChunks = ref<DocumentChunk[]>([])
const loadingDocumentDetail = ref(false)
const documentActionLoadingId = ref<number | null>(null)
const uploadingDocument = ref(false)
const ragQuestion = ref('')
const ragSources = ref<RagSource[]>([])
const ragSending = ref(false)

onMounted(async () => {
  await Promise.all([workspaceStore.loadConversations(), loadDocumentsFlow()])
})

async function uploadLearningDocument(options: UploadRequestOptions) {
  uploadingDocument.value = true
  try {
    const result = await uploadDocument(options.file as File)
    await loadDocumentsFlow()
    selectedDocumentIds.value = [result.documentId]
    await loadDocumentDetailFlow(result.documentId)
    ElMessage.success('资料已上传')
    options.onSuccess?.(result)
  } catch (error) {
    const uploadError = error instanceof Error ? error : new Error(String(error))
    options.onError?.(uploadError as Parameters<NonNullable<UploadRequestOptions['onError']>>[0])
    ElMessage.error(error instanceof Error ? error.message : '上传失败')
  } finally {
    uploadingDocument.value = false
  }
}

async function loadDocumentsFlow() {
  try {
    documents.value = await listDocuments()
    if (selectedDocumentIds.value.length === 0 && documents.value.length > 0) {
      selectedDocumentIds.value = documents.value.map((item) => item.id)
    }
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '加载资料失败')
  }
}

async function loadDocumentDetailFlow(documentId: number) {
  loadingDocumentDetail.value = true
  try {
    const [detail, chunks] = await Promise.all([
      getDocument(documentId),
      listDocumentChunks(documentId)
    ])
    selectedDocumentDetail.value = detail
    documentChunks.value = chunks
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '加载资料依据失败')
  } finally {
    loadingDocumentDetail.value = false
  }
}

async function reprocessLearningDocument(documentId: number) {
  documentActionLoadingId.value = documentId
  try {
    await reprocessDocument(documentId)
    await loadDocumentsFlow()
    await loadDocumentDetailFlow(documentId)
    ElMessage.success('资料已重新处理')
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '重新处理失败')
  } finally {
    documentActionLoadingId.value = null
  }
}

async function deleteLearningDocument(documentId: number) {
  try {
    await ElMessageBox.confirm('删除后会移除资料和切片，确认删除？', '删除资料', {
      type: 'warning',
      confirmButtonText: '删除',
      cancelButtonText: '取消'
    })
  } catch {
    return
  }

  documentActionLoadingId.value = documentId
  try {
    await deleteDocument(documentId)
    selectedDocumentIds.value = selectedDocumentIds.value.filter((id) => id !== documentId)
    if (selectedDocumentDetail.value?.id === documentId) {
      selectedDocumentDetail.value = null
      documentChunks.value = []
    }
    await loadDocumentsFlow()
    ElMessage.success('资料已删除')
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '删除资料失败')
  } finally {
    documentActionLoadingId.value = null
  }
}

async function createRagConversation() {
  ragSending.value = true
  try {
    await workspaceStore.addConversation('资料问答', 'rag')
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '创建资料会话失败')
  } finally {
    ragSending.value = false
  }
}

async function sendRagQuestion() {
  const question = ragQuestion.value.trim()
  if (!question) {
    ElMessage.warning('请输入问题')
    return
  }

  ragSending.value = true
  try {
    if (!workspaceStore.currentConversationId || workspaceStore.currentConversation?.mode !== 'rag') {
      await workspaceStore.addConversation('资料问答', 'rag')
    }
    if (!workspaceStore.currentConversationId) {
      return
    }
    const result = await sendRagChat(
      workspaceStore.currentConversationId,
      question,
      selectedDocumentIds.value.length > 0 ? selectedDocumentIds.value : undefined
    )
    ragQuestion.value = ''
    ragSources.value = result.sources || []
    await workspaceStore.loadConversations()
    await workspaceStore.selectConversation(result.conversationId)
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '资料问答失败')
  } finally {
    ragSending.value = false
  }
}

function trustLabel(document: LearningDocument) {
  if (document.chunkCount > 0 && document.processStatus === 'processed') {
    return '可引用'
  }
  if (document.chunkCount > 0) {
    return '部分可引用'
  }
  return '待处理'
}
</script>
