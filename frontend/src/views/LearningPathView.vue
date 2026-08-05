<template>
  <div class="learning-page split-page">
    <aside class="path-panel panel">
      <div class="section-head">
        <div>
          <p class="eyebrow">Path</p>
          <h2>知识点路径</h2>
        </div>
        <el-button :icon="Refresh" circle :loading="loadingKnowledge" @click="loadKnowledgeTreeFlow" />
      </div>
      <el-input v-model.trim="knowledgeSubject" placeholder="学科，例如 Java" @change="loadKnowledgeTreeFlow" />
      <el-tree
        v-loading="loadingKnowledge"
        class="knowledge-tree"
        :data="knowledgeTree"
        node-key="id"
        default-expand-all
        highlight-current
        :props="{ label: 'name', children: 'children' }"
        @node-click="selectKnowledgePoint"
      />
    </aside>

    <section class="learning-main panel">
      <div class="section-head">
        <div>
          <p class="eyebrow">Selected Point</p>
          <h2>{{ selectedKnowledgePoint?.name || chatTopic || '选择一个知识点' }}</h2>
        </div>
        <el-tag v-if="selectedRecord" effect="plain">{{ selectedRecord.masteryLevel }}% 掌握</el-tag>
      </div>

      <section v-if="chatTopic" class="handoff-panel">
        <p class="eyebrow">From Chat</p>
        <h3>{{ chatTopic }}</h3>
        <p>
          这个主题来自 AI Chat。当前学习路径只展示已经进入标准知识库的知识点；
          如果没有自动定位，说明它还没有对应的数据库知识点。
        </p>
        <div class="handoff-actions">
          <el-button type="primary" :loading="aiPathLoading" @click="generateAiPathFlow">生成 AI 路径</el-button>
          <el-button @click="router.push({ name: 'chat' })">回到 Chat 继续学</el-button>
          <el-button @click="router.push({ name: 'library', query: { topic: chatTopic } })">用资料补充来源</el-button>
        </div>
      </section>

      <section v-if="aiGeneratedPath" class="ai-path-panel">
        <div class="section-head compact">
          <div>
            <p class="eyebrow">AI Generated Path</p>
            <h3>{{ aiGeneratedPath.topic }}</h3>
          </div>
          <el-tag effect="plain">AI 生成</el-tag>
        </div>
        <p v-if="aiGeneratedPath.summary" class="ai-path-summary">{{ aiGeneratedPath.summary }}</p>
        <article v-for="step in aiGeneratedPath.steps" :key="step.orderIndex" class="ai-path-step">
          <div class="ai-path-step-index">{{ step.orderIndex }}</div>
          <div>
            <h4>{{ step.title }}</h4>
            <p>{{ step.goal }}</p>
            <small v-if="step.explanation">{{ step.explanation }}</small>
            <div v-if="step.keyPoints.length" class="ai-path-tags">
              <el-tag v-for="point in step.keyPoints" :key="point" size="small" effect="plain">
                {{ point }}
              </el-tag>
            </div>
            <ul v-if="step.actions.length">
              <li v-for="action in step.actions" :key="action">{{ action }}</li>
            </ul>
          </div>
          <span v-if="step.estimatedTime" class="ai-path-time">{{ step.estimatedTime }}</span>
        </article>
      </section>

      <div class="learning-actions">
        <el-button
          type="primary"
          :icon="Reading"
          :loading="teachingLoading"
          :disabled="!selectedKnowledgePoint"
          @click="startTeachingFlow"
        >
          开始教学
        </el-button>
        <el-button :icon="EditPen" :disabled="!selectedKnowledgePoint" @click="goPractice">进入练习</el-button>
      </div>

      <div v-if="teachingResult" class="teaching-box">
        <div class="section-head compact">
          <div>
            <p class="eyebrow">Teaching</p>
            <h3>{{ teachingResult.knowledgePointName }}</h3>
          </div>
          <el-button text type="primary" @click="router.push({ name: 'chat' })">查看对话</el-button>
        </div>
        <div class="markdown-body" v-html="renderMarkdown(teachingResult.teachingContent)" />
      </div>

      <section class="answer-panel">
        <h3>理解检查</h3>
        <p>开始教学后，可以在这里提交你的理解，AI Tutor 会给出反馈并更新学习记录。</p>
        <el-input
          v-model="teachingAnswer"
          type="textarea"
          resize="none"
          :rows="5"
          placeholder="写下你的理解或答案..."
        />
        <el-button :loading="teachingLoading" @click="evaluateTeachingFlow">提交回答</el-button>
        <div v-if="evaluationResult" class="feedback-box">
          <strong>{{ evaluationResult.masteryLevel }}% 掌握 · {{ evaluationResult.learningStatus }}</strong>
          <div class="markdown-body" v-html="renderMarkdown(evaluationResult.feedback)" />
        </div>
      </section>
    </section>

    <aside class="records-panel panel">
      <div class="section-head">
        <div>
          <p class="eyebrow">Records</p>
          <h2>学习记录</h2>
        </div>
      </div>
      <div v-if="learningRecords.length === 0" class="empty-line">暂无学习记录。</div>
      <article v-for="record in learningRecords" :key="record.knowledgePointId" class="record-card">
        <div>
          <strong>{{ record.knowledgePointName || record.knowledgePointId }}</strong>
          <small>{{ record.learningStatus }} · {{ formatTime(record.updateTime) }}</small>
        </div>
        <el-progress :percentage="record.masteryLevel" :stroke-width="8" />
      </article>
    </aside>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { EditPen, Reading, Refresh } from '@element-plus/icons-vue'
import { ElMessage } from 'element-plus'
import { evaluateTeaching, generateAiPath, listKnowledgeTree, listLearningRecords, startTeaching } from '../api'
import { useWorkspaceStore } from '../stores/workspace'
import type {
  AiGeneratedPath,
  KnowledgePoint,
  LearningRecord,
  TeachingEvaluationResult,
  TeachingStartResult
} from '../types/domain'
import { findKnowledgePoint, firstSelectableKnowledgePoint, flattenKnowledgePoints } from '../utils/knowledge'
import { formatTime } from '../utils/format'
import { renderMarkdown } from '../utils/markdown'

const router = useRouter()
const route = useRoute()
const workspaceStore = useWorkspaceStore()
const knowledgeSubject = ref('Java')
const knowledgeTree = ref<KnowledgePoint[]>([])
const selectedKnowledgePointId = ref<number | null>(null)
const loadingKnowledge = ref(false)
const learningRecords = ref<LearningRecord[]>([])
const teachingLoading = ref(false)
const aiPathLoading = ref(false)
const teachingAnswer = ref('')
const teachingResult = ref<TeachingStartResult | null>(null)
const evaluationResult = ref<TeachingEvaluationResult | null>(null)
const aiGeneratedPath = ref<AiGeneratedPath | null>(null)

const selectedKnowledgePoint = computed(() =>
  flattenKnowledgePoints(knowledgeTree.value).find((item) => item.id === selectedKnowledgePointId.value) || null
)
const selectedRecord = computed(() =>
  learningRecords.value.find((item) => item.knowledgePointId === selectedKnowledgePointId.value) || null
)
const chatTopic = computed(() => String(route.query.topic || '').trim())

onMounted(async () => {
  await Promise.all([loadKnowledgeTreeFlow(), loadLearningRecordsFlow()])
  await applyRouteSelection()
})

watch(
  () => [route.query.subject, route.query.knowledgePointId, route.query.topic],
  async () => {
    await applyRouteSelection()
  }
)

watch(chatTopic, () => {
  aiGeneratedPath.value = null
})

async function loadKnowledgeTreeFlow() {
  loadingKnowledge.value = true
  try {
    knowledgeTree.value = await listKnowledgeTree(knowledgeSubject.value || 'Java')
    if (!selectedKnowledgePointId.value && !chatTopic.value) {
      selectedKnowledgePointId.value = firstSelectableKnowledgePoint(knowledgeTree.value)?.id || null
    }
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '加载知识点失败')
  } finally {
    loadingKnowledge.value = false
  }
}

async function loadLearningRecordsFlow() {
  try {
    learningRecords.value = await listLearningRecords()
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '加载学习记录失败')
  }
}

function selectKnowledgePoint(point: KnowledgePoint) {
  selectedKnowledgePointId.value = point.id
}

async function applyRouteSelection() {
  const subject = String(route.query.subject || '').trim()
  if (subject && subject !== knowledgeSubject.value) {
    knowledgeSubject.value = subject
    selectedKnowledgePointId.value = null
    await loadKnowledgeTreeFlow()
  }

  const queryId = Number(route.query.knowledgePointId)
  if (queryId && findKnowledgePoint(knowledgeTree.value, queryId)) {
    selectedKnowledgePointId.value = queryId
    return
  }

  // Chat 跳过来的非标准主题不要默认选中 Java 的第一个知识点，避免用户误以为已生成了路径。
  if (chatTopic.value) {
    selectedKnowledgePointId.value = null
  }
}

async function generateAiPathFlow() {
  if (!chatTopic.value) {
    ElMessage.warning('缺少 Chat 主题')
    return
  }

  aiPathLoading.value = true
  try {
    aiGeneratedPath.value = await generateAiPath(chatTopic.value)
    ElMessage.success('AI 路径已生成')
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '生成 AI 路径失败')
  } finally {
    aiPathLoading.value = false
  }
}

async function startTeachingFlow() {
  if (!selectedKnowledgePoint.value) {
    ElMessage.warning('请选择知识点')
    return
  }

  teachingLoading.value = true
  try {
    const result = await startTeaching(selectedKnowledgePoint.value.id)
    teachingResult.value = result
    evaluationResult.value = null
    await workspaceStore.loadConversations()
    await workspaceStore.selectConversation(result.conversationId)
    await loadLearningRecordsFlow()
    ElMessage.success('教学已开始')
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '开始教学失败')
  } finally {
    teachingLoading.value = false
  }
}

async function evaluateTeachingFlow() {
  if (!selectedKnowledgePoint.value) {
    ElMessage.warning('请选择知识点')
    return
  }
  if (!workspaceStore.currentConversationId || workspaceStore.currentConversation?.mode !== 'teaching') {
    ElMessage.warning('请先开始教学')
    return
  }
  const answer = teachingAnswer.value.trim()
  if (!answer) {
    ElMessage.warning('请输入回答')
    return
  }

  teachingLoading.value = true
  try {
    evaluationResult.value = await evaluateTeaching(
      workspaceStore.currentConversationId,
      selectedKnowledgePoint.value.id,
      answer
    )
    teachingAnswer.value = ''
    await workspaceStore.selectConversation(evaluationResult.value.conversationId)
    await loadLearningRecordsFlow()
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '提交回答失败')
  } finally {
    teachingLoading.value = false
  }
}

async function goPractice() {
  if (!selectedKnowledgePoint.value) {
    ElMessage.warning('请选择知识点')
    return
  }
  await router.push({ name: 'practice', query: { knowledgePointId: selectedKnowledgePoint.value.id } })
}
</script>
