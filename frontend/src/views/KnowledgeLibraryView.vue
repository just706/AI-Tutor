<template>
  <div class="library-page">
    <section class="library-qa panel">
      <div class="section-head">
        <div>
          <p class="eyebrow">Knowledge Library</p>
          <h2>资料问答</h2>
        </div>
        <el-button type="primary" :loading="ragSending" @click="createRagConversation">新建资料会话</el-button>
        <el-button v-if="workspaceStore.currentConversation?.mode === 'rag'" @click="openMainChat">在主聊天继续</el-button>
      </div>

      <div class="source-summary">
        <strong>{{ selectedDocumentIds.length }}</strong>
        <span>个资料来源参与回答</span>
        <small v-if="workspaceStore.currentConversation?.mode === 'rag'">{{ workspaceStore.savingDocuments ? '正在保存教材选择…' : '教材选择已随会话保存' }}</small>
      </div>
      <el-alert v-if="unavailableDocumentIds.length" type="warning" :closable="false"
        title="部分已选教材不可用，请取消选择或等待资料处理完成。" />

      <div class="rag-composer">
        <el-input
          v-model="ragQuestion"
          type="textarea"
          resize="none"
          :rows="5"
          placeholder="输入教材中的概念名称和问题，例如：某概念有什么用途？"
        />
        <el-button type="primary" :icon="Promotion" :loading="ragSending"
          :disabled="loadingDocuments || !selectedDocumentIds.length || unavailableDocumentIds.length > 0 || workspaceStore.savingDocuments || workspaceStore.loadingMessages" @click="sendRagQuestion">
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
          <RagSources :sources="message.sources" />
          <RagRetry :message="message" />
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
        <el-upload accept=".txt,.md,.markdown,.pdf,.doc,.docx" :show-file-list="false" :http-request="uploadLearningDocument">
          <el-button :icon="Upload" :loading="uploadingDocument">上传</el-button>
        </el-upload>
      </div>

      <el-checkbox-group v-model="selectedDocumentIds" class="source-list" :disabled="loadingDocuments || ragSending || workspaceStore.savingDocuments || workspaceStore.loadingMessages" @change="saveDocumentSelection">
        <el-checkbox v-for="id in missingDocumentIds" :key="`missing-${id}`" :value="id">资料 #{{ id }}（已删除或不可用，请取消选择）</el-checkbox>
        <article
          v-for="document in documents"
          :key="document.id"
          class="source-card"
          :class="{ active: selectedDocumentDetail?.id === document.id }"
        >
          <el-checkbox :value="document.id" :disabled="document.processStatus !== 'completed' && !selectedDocumentIds.includes(document.id)">
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
            <el-button
              v-if="selectedDocumentDetail?.id === document.id && personalExtraction?.status === 'failed'"
              text
              type="success"
              :loading="graphGeneratingDocumentId === document.id"
              :disabled="document.processStatus !== 'completed' || document.chunkCount === 0"
              @click.stop="generatePersonalGraph(document.id)"
            >
              重新生成
            </el-button>
            <el-button text :loading="documentActionLoadingId === document.id" @click.stop="reprocessLearningDocument(document.id)">
              重处理
            </el-button>
            <el-button text type="danger" :loading="documentActionLoadingId === document.id" @click.stop="deleteLearningDocument(document.id)">
              删除
            </el-button>
          </div>
        </article>
      </el-checkbox-group>
      <p v-if="loadingDocuments">正在加载教材…</p>
      <el-empty v-else-if="documents.length === 0" description="还没有资料来源" :image-size="90" />
    </aside>

    <aside class="library-evidence panel">
      <div class="section-head">
        <div>
          <p class="eyebrow">Evidence</p>
          <h2>回答依据</h2>
        </div>
      </div>

      <div v-if="ragSources.length > 0" class="evidence-list">
        <RagSources :sources="ragSources" expanded />
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
        <section class="personal-graph-review">
          <div class="section-head compact">
            <div>
              <p class="eyebrow">Personal Graph</p>
              <h3>个人图谱候选</h3>
            </div>
            <el-button
              v-if="personalExtraction?.status === 'completed'"
              type="primary"
              :loading="publishingExtraction"
              @click="publishPersonalGraph"
            >
              确认发布
            </el-button>
          </div>
          <div v-if="loadingPersonalExtraction" class="empty-line">正在加载图谱审核结果...</div>
          <div v-else-if="personalExtraction?.status === 'failed'" class="graph-review-error">
            {{ personalGraphFailureMessage(personalExtraction.errorMessage) }}
          </div>
          <template v-else-if="personalExtraction">
            <div v-if="personalExtraction.status === 'processing'" class="personal-graph-progress">
              <el-steps :active="extractionStageIndex(personalExtraction.stage)" finish-status="success" align-center>
                <el-step title="正在提取知识实体" />
                <el-step title="正在合并重复实体" />
                <el-step title="正在建立知识关系" />
                <el-step title="知识图谱生成完成" />
              </el-steps>
              <el-progress :percentage="personalExtraction.progress || 0" :stroke-width="8" />
            </div>
            <el-tag :type="personalExtraction.status === 'published' ? 'success' : 'warning'" effect="plain">
              {{ extractionStatusLabel(personalExtraction.status) }}
            </el-tag>
            <div v-if="personalExtraction.candidates.nodes.length > 0" class="graph-review-list">
              <strong>候选节点</strong>
              <article v-for="node in personalExtraction.candidates.nodes" :key="node.name">
                <div>
                  <b>{{ node.name }}</b>
                  <el-tag size="small" effect="plain">{{ node.confidence }}%</el-tag>
                </div>
                <p>{{ node.description || '暂无说明' }}</p>
                <small v-for="evidence in node.evidence" :key="evidence.chunkIndex">#{{ evidence.chunkIndex }} · {{ evidence.snippet }}</small>
              </article>
            </div>
            <div v-if="personalExtraction.candidates.edges.length > 0" class="graph-review-list">
              <strong>候选关系</strong>
              <article v-for="edge in personalExtraction.candidates.edges" :key="`${edge.sourceName}-${edge.targetName}-${edge.relationType}`">
                <div>
                  <b>{{ edge.sourceName }} → {{ edge.targetName }}</b>
                  <el-tag size="small" effect="plain">{{ edge.relationType }} · {{ edge.confidence }}%</el-tag>
                </div>
                <p>{{ edge.relationReason || '暂无关系说明' }}</p>
                <small v-for="evidence in edge.evidence" :key="evidence.chunkIndex">#{{ evidence.chunkIndex }} · {{ evidence.snippet }}</small>
              </article>
            </div>
            <div v-if="personalExtraction.candidates.nodes.length === 0 && personalExtraction.status !== 'processing'" class="empty-line">
              当前资料没有可确认的候选知识点。
            </div>
            <el-button
              v-if="personalExtraction.status === 'published'"
              type="primary"
              plain
              @click="viewPersonalGraph"
            >
              查看知识图谱
            </el-button>
          </template>
          <div v-else class="empty-line">生成后将在这里审核候选节点和关系。</div>
        </section>
      </div>

      <div v-else class="empty-line">选择资料或完成一次资料问答后，这里会展示依据。</div>
    </aside>
  </div>
</template>

<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import { Promotion, Upload } from '@element-plus/icons-vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import type { UploadRequestOptions } from 'element-plus'
import {
  createPersonalGraphExtraction,
  deleteDocument,
  getDocument,
  getPersonalGraphExtraction,
  getLatestPersonalGraphExtraction,
  listDocumentChunks,
  listDocuments,
  publishPersonalGraphExtraction,
  reprocessDocument,
  uploadDocument
} from '../api'
import { useWorkspaceStore } from '../stores/workspace'
import type {
  DocumentChunk,
  LearningDocument,
  LearningDocumentDetail,
  PersonalGraphExtraction
} from '../types/domain'
import { renderMarkdown } from '../utils/markdown'
import RagSources from '../components/RagSources.vue'
import RagRetry from '../components/RagRetry.vue'

const workspaceStore = useWorkspaceStore()
const router = useRouter()
const documents = ref<LearningDocument[]>([])
const loadingDocuments = ref(true)
const selectedDocumentIds = ref<number[]>([])
const selectedDocumentDetail = ref<LearningDocumentDetail | null>(null)
const documentChunks = ref<DocumentChunk[]>([])
const loadingDocumentDetail = ref(false)
const documentActionLoadingId = ref<number | null>(null)
const uploadingDocument = ref(false)
const ragQuestion = ref('')
const ragSources = computed(() => [...workspaceStore.messages].reverse().find(message => message.role === 'assistant')?.sources || [])
const creatingConversation = ref(false)
const ragSending = computed(() => workspaceStore.sending || creatingConversation.value)
const missingDocumentIds = computed(() => loadingDocuments.value ? [] : selectedDocumentIds.value.filter(id => !documents.value.some(document => document.id === id)))
const unavailableDocumentIds = computed(() => loadingDocuments.value ? [] : selectedDocumentIds.value.filter(id => !documents.value.some(document => document.id === id && document.processStatus === 'completed')))
watch(() => workspaceStore.currentConversation, conversation => {
  if (conversation?.mode === 'rag') selectedDocumentIds.value = [...conversation.documentIds]
  else selectedDocumentIds.value = []
}, { immediate: true })
const personalExtraction = ref<PersonalGraphExtraction | null>(null)
const loadingPersonalExtraction = ref(false)
const graphGeneratingDocumentId = ref<number | null>(null)
const publishingExtraction = ref(false)
let extractionPollTimer: ReturnType<typeof window.setInterval> | null = null

onBeforeUnmount(() => {
  stopExtractionPolling()
})

onMounted(async () => {
  await loadDocumentsFlow()
  const firstDocumentId = selectedDocumentIds.value[0]
  if (firstDocumentId !== undefined && documents.value.some(document => document.id === firstDocumentId)) {
    await loadDocumentDetailFlow(firstDocumentId)
  }
})

async function uploadLearningDocument(options: UploadRequestOptions) {
  uploadingDocument.value = true
  try {
    const result = await uploadDocument(options.file as File)
    await loadDocumentsFlow()
    selectedDocumentIds.value = [result.documentId]
    await saveDocumentSelection()
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
  loadingDocuments.value = true
  try {
    documents.value = await listDocuments()
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '加载资料失败')
  } finally {
    loadingDocuments.value = false
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
    await loadPersonalGraphExtraction(documentId)
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '加载资料依据失败')
  } finally {
    loadingDocumentDetail.value = false
  }
}

async function loadPersonalGraphExtraction(documentId: number) {
  stopExtractionPolling()
  loadingPersonalExtraction.value = true
  try {
    personalExtraction.value = await getLatestPersonalGraphExtraction(documentId)
    if (personalExtraction.value?.status === 'processing') {
      startExtractionPolling(documentId, personalExtraction.value.id)
    }
  } catch (error) {
    personalExtraction.value = null
    ElMessage.error(error instanceof Error ? error.message : '加载个人图谱失败')
  } finally {
    loadingPersonalExtraction.value = false
  }
}

async function generatePersonalGraph(documentId: number) {
  stopExtractionPolling()
  graphGeneratingDocumentId.value = documentId
  try {
    personalExtraction.value = await createPersonalGraphExtraction(documentId)
    if (personalExtraction.value.status === 'processing') {
      startExtractionPolling(documentId, personalExtraction.value.id)
      ElMessage.success('候选图谱任务已开始')
      return
    }
    if (selectedDocumentDetail.value?.id !== documentId) {
      await loadDocumentDetailFlow(documentId)
    }
    if (personalExtraction.value.status === 'failed') {
      ElMessage.error(personalGraphFailureMessage(personalExtraction.value.errorMessage))
    } else {
      ElMessage.success('候选图谱已生成，请在资料详情审核')
    }
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '生成知识图谱失败')
  } finally {
    graphGeneratingDocumentId.value = null
  }
}

async function publishPersonalGraph() {
  if (!personalExtraction.value) {
    return
  }
  publishingExtraction.value = true
  try {
    personalExtraction.value = await publishPersonalGraphExtraction(personalExtraction.value.id)
    ElMessage.success('个人图谱已发布')
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '发布个人图谱失败')
  } finally {
    publishingExtraction.value = false
  }
}

function startExtractionPolling(documentId: number, extractionId: number) {
  stopExtractionPolling()
  extractionPollTimer = window.setInterval(async () => {
    try {
      const latest = await getPersonalGraphExtraction(extractionId)
      if (selectedDocumentDetail.value?.id !== documentId) {
        stopExtractionPolling()
        return
      }
      personalExtraction.value = latest
      if (latest.status !== 'processing') {
        stopExtractionPolling()
        if (latest.status === 'completed') {
          ElMessage.success('知识图谱生成完成，请审核候选结果')
        }
      }
    } catch (error) {
      stopExtractionPolling()
      ElMessage.error(error instanceof Error ? error.message : '获取图谱进度失败')
    }
  }, 1000)
}

function stopExtractionPolling() {
  if (extractionPollTimer !== null) {
    window.clearInterval(extractionPollTimer)
    extractionPollTimer = null
  }
}

function viewPersonalGraph() {
  if (!personalExtraction.value) return
  router.push({
    name: 'knowledgeGraph',
    query: { mode: 'personal', documentId: String(personalExtraction.value.documentId) }
  })
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
    if (workspaceStore.currentConversation?.mode !== 'rag') selectedDocumentIds.value = selectedDocumentIds.value.filter((id) => id !== documentId)
    if (selectedDocumentDetail.value?.id === documentId) {
      selectedDocumentDetail.value = null
      documentChunks.value = []
      personalExtraction.value = null
    }
    await loadDocumentsFlow()
    ElMessage.success('资料已删除')
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '删除资料失败')
  } finally {
    documentActionLoadingId.value = null
  }
}

function extractionStatusLabel(status: PersonalGraphExtraction['status']) {
  if (status === 'completed') return '待确认'
  if (status === 'published') return '已发布'
  if (status === 'processing') return '生成中'
  return '生成失败'
}

function extractionStageIndex(stage?: PersonalGraphExtraction['stage']) {
  if (stage === 'merging_nodes') return 1
  if (stage === 'extracting_relations') return 2
  if (stage === 'awaiting_review') return 3
  return 0
}

function personalGraphFailureMessage(errorMessage?: string | null) {
  if (errorMessage?.includes('AI service call failed')) {
    return `${errorMessage}。请检查 DeepSeek API Key、模型名称和网络连接后重试。`
  }
  if (errorMessage?.includes('unknown candidate node')) {
    return 'AI 生成了候选节点集之外的关系，系统已拒绝保存，请重新生成。'
  }
  if (errorMessage?.includes('relation with an unknown node')) {
    return '生成结果中的关系缺少对应知识点，系统未保存候选结果，请重新生成。'
  }
  if (errorMessage?.includes('invalid personal graph relation JSON')) {
    return 'AI 返回的关系图谱格式不正确，系统未保存候选结果，请重新生成。'
  }
  if (errorMessage?.includes('invalid personal graph JSON')) {
    return 'AI 返回的图谱格式不正确，系统未保存候选结果，请重新生成。'
  }
  if (errorMessage?.includes('referenced a chunk that was not provided')) {
    return 'AI 引用了资料中不存在的片段，系统未保存候选结果，请重新生成。'
  }
  return errorMessage || '图谱抽取失败，请重新生成。'
}

async function createRagConversation() {
  creatingConversation.value = true
  try {
    await workspaceStore.addConversation('资料问答', 'rag', [...selectedDocumentIds.value])
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '创建资料会话失败')
  } finally {
    creatingConversation.value = false
  }
}

async function sendRagQuestion() {
  const question = ragQuestion.value.trim()
  if (!question) {
    ElMessage.warning('请输入问题')
    return
  }

  try {
    if (!workspaceStore.currentConversationId || workspaceStore.currentConversation?.mode !== 'rag') {
      creatingConversation.value = true
      await workspaceStore.addConversation('资料问答', 'rag', [...selectedDocumentIds.value])
      creatingConversation.value = false
    }
    if (!workspaceStore.currentConversationId) {
      return
    }
    ragQuestion.value = ''
    await workspaceStore.sendMessage(question)
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '资料问答失败')
  } finally {
    creatingConversation.value = false
  }
}

async function saveDocumentSelection() {
  if (workspaceStore.currentConversation?.mode !== 'rag') return
  try { await workspaceStore.setConversationDocuments([...selectedDocumentIds.value]) }
  catch (error) {
    selectedDocumentIds.value = [...(workspaceStore.currentConversation?.documentIds || [])]
    ElMessage.error(error instanceof Error ? error.message : '保存教材选择失败')
  }
}

function openMainChat() {
  return router.push({ name: 'chat', query: { conversationId: workspaceStore.currentConversationId || undefined } })
}

function trustLabel(document: LearningDocument) {
  if (document.chunkCount > 0 && ['processed', 'completed'].includes(document.processStatus)) {
    return '可引用'
  }
  if (document.chunkCount > 0) {
    return '部分可引用'
  }
  return '待处理'
}
</script>
