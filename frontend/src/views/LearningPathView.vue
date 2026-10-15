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
      <el-select :model-value="knowledgeSubject" filterable allow-create clearable placeholder="选择或输入学科" @change="selectSubject">
        <el-option v-for="subject in subjects" :key="subject" :label="subject" :value="subject" />
      </el-select>
      <p v-if="selectionNotice" class="empty-line">{{ selectionNotice }}</p>
      <div class="topic-search-box">
        <p class="eyebrow">Free Topic</p>
        <el-input v-model.trim="topicInput" placeholder="输入想学习的主题" @keydown.enter.prevent="useTopicInput" />
        <el-button type="primary" plain @click="useTopicInput">使用自由主题</el-button>
      </div>
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
        <p class="eyebrow">Free Topic</p>
        <h3>{{ chatTopic }}</h3>
        <p>
          可以选择已收录的知识点，也可以按当前主题生成临时学习路径。
          临时路径不等于已有教材依据或经过验证的课程。
        </p>
        <div class="handoff-actions">
          <el-button type="primary" :loading="aiPathLoading" @click="generateAiPathFlow">生成 AI 路径</el-button>
          <el-button @click="goPractice">
            生成练习
          </el-button>
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
        <el-button :icon="EditPen" :disabled="!selectedKnowledgePoint && !chatTopic" @click="goPractice">进入练习</el-button>
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
import { computed, onMounted, onUnmounted, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import { EditPen, Reading, Refresh } from '@element-plus/icons-vue'
import { ElMessage } from 'element-plus'
import { evaluateTeaching, generateAiPath, listLearningRecords, startTeaching } from '../api'
import { useWorkspaceStore } from '../stores/workspace'
import type {
  AiGeneratedPath,
  LearningRecord,
  TeachingEvaluationResult,
  TeachingStartResult
} from '../types/domain'
import { useKnowledgeSelection } from '../composables/useKnowledgeSelection'
import { formatTime } from '../utils/format'
import { renderMarkdown } from '../utils/markdown'

const router = useRouter()
const workspaceStore = useWorkspaceStore()
const { knowledgeSubject, subjects, knowledgeTree, selectedKnowledgePoint, selectedKnowledgePointId,
  activeTopic: chatTopic, generationTopic, selectionKey, selectionNotice, loadingKnowledge, loadKnowledgeTreeFlow,
  selectSubject, selectKnowledgePoint, selectTopic, navigationQuery } = useKnowledgeSelection()
const topicInput = ref('')
const learningRecords = ref<LearningRecord[]>([])
const teachingLoading = ref(false)
const aiPathLoading = ref(false)
const teachingAnswer = ref('')
const teachingResult = ref<TeachingStartResult | null>(null)
const evaluationResult = ref<TeachingEvaluationResult | null>(null)
const aiGeneratedPath = ref<AiGeneratedPath | null>(null)

const selectedRecord = computed(() =>
  learningRecords.value.find((item) => item.knowledgePointId === selectedKnowledgePointId.value) || null
)

onMounted(async () => {
  await Promise.all([loadKnowledgeTreeFlow(), loadLearningRecordsFlow()])
})

let contextVersion = 0
onUnmounted(() => { contextVersion++ })
watch(selectionKey, () => {
  contextVersion++
  topicInput.value = chatTopic.value
  aiGeneratedPath.value = null
  teachingResult.value = null
  evaluationResult.value = null
  teachingAnswer.value = ''
  teachingLoading.value = false
  aiPathLoading.value = false
}, { immediate: true, flush: 'sync' })

function useTopicInput() {
  if (!topicInput.value.trim()) {
    ElMessage.warning('请输入主题')
    return
  }
  void selectTopic(topicInput.value)
}

async function loadLearningRecordsFlow() {
  try {
    learningRecords.value = await listLearningRecords()
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '加载学习记录失败')
  }
}

async function generateAiPathFlow() {
  if (!chatTopic.value) {
    ElMessage.warning('缺少 Chat 主题')
    return
  }

  aiPathLoading.value = true
  const version = contextVersion
  try {
    const result = await generateAiPath(generationTopic.value)
    if (version !== contextVersion) return
    aiGeneratedPath.value = result
    ElMessage.success('AI 路径已生成')
  } catch (error) {
    if (version === contextVersion) ElMessage.error(error instanceof Error ? error.message : '生成 AI 路径失败')
  } finally {
    if (version === contextVersion) aiPathLoading.value = false
  }
}

async function startTeachingFlow() {
  if (!selectedKnowledgePoint.value) {
    ElMessage.warning('请选择知识点')
    return
  }

  teachingLoading.value = true
  const version = contextVersion
  try {
    const result = await startTeaching(selectedKnowledgePoint.value.id)
    if (version !== contextVersion) return
    teachingResult.value = result
    evaluationResult.value = null
    await workspaceStore.loadConversations()
    if (version !== contextVersion) return
    await workspaceStore.selectConversation(result.conversationId)
    await loadLearningRecordsFlow()
    ElMessage.success('教学已开始')
  } catch (error) {
    if (version === contextVersion) ElMessage.error(error instanceof Error ? error.message : '开始教学失败')
  } finally {
    if (version === contextVersion) teachingLoading.value = false
  }
}

async function evaluateTeachingFlow() {
  if (!selectedKnowledgePoint.value) {
    ElMessage.warning('请选择知识点')
    return
  }
  if (!teachingResult.value || teachingResult.value.knowledgePointId !== selectedKnowledgePoint.value.id) {
    ElMessage.warning('请先开始当前知识点的教学')
    return
  }
  const answer = teachingAnswer.value.trim()
  if (!answer) {
    ElMessage.warning('请输入回答')
    return
  }

  teachingLoading.value = true
  const version = contextVersion
  try {
    const result = await evaluateTeaching(
      teachingResult.value.conversationId,
      selectedKnowledgePoint.value.id,
      answer
    )
    if (version !== contextVersion) return
    evaluationResult.value = result
    teachingAnswer.value = ''
    await workspaceStore.selectConversation(evaluationResult.value.conversationId)
    await loadLearningRecordsFlow()
  } catch (error) {
    if (version === contextVersion) ElMessage.error(error instanceof Error ? error.message : '提交回答失败')
  } finally {
    if (version === contextVersion) teachingLoading.value = false
  }
}

async function goPractice() {
  if (!selectedKnowledgePoint.value && !chatTopic.value) {
    ElMessage.warning('请选择知识点或输入自由主题')
    return
  }
  await router.push({ name: 'practice', query: navigationQuery.value })
}
</script>
