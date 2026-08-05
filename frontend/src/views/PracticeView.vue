<template>
  <div class="practice-page split-page">
    <aside class="path-panel panel">
      <div class="section-head">
        <div>
          <p class="eyebrow">Knowledge</p>
          <h2>选择知识点</h2>
        </div>
      </div>

      <div class="topic-search-box">
        <p class="eyebrow">Free Topic</p>
        <el-input
          v-model.trim="topicInput"
          placeholder="输入主题，例如 高等数学"
          @keydown.enter.prevent="useTopicInput"
        />
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

    <section class="practice-main panel">
      <div class="section-head">
        <div>
          <p class="eyebrow">Practice</p>
          <h2>{{ practiceTitle }}</h2>
        </div>
        <el-button text type="primary" @click="goLearningPath">回到学习路径</el-button>
      </div>

      <section v-if="activeTopic && !selectedKnowledgePoint" class="handoff-panel">
        <p class="eyebrow">AI Temporary Practice</p>
        <h3>{{ activeTopic }}</h3>
        <p>
          这个主题还没有对应的标准知识点，练习会由 AI 临时生成。
          当前结果不写入题库，也暂不计入掌握度。
        </p>
        <div class="handoff-actions">
          <el-button @click="router.push({ name: 'learn', query: { topic: activeTopic, source: 'practice' } })">
            生成学习路径
          </el-button>
          <el-button @click="router.push({ name: 'chat', query: { topic: activeTopic } })">回到 Chat</el-button>
        </div>
      </section>

      <div class="practice-toolbar">
        <el-select v-model="practiceForm.questionType" placeholder="题型">
          <el-option label="单选题" value="single_choice" />
          <el-option label="判断题" value="true_false" />
          <el-option label="简答题" value="short_answer" />
        </el-select>
        <el-select v-model="practiceForm.difficulty" placeholder="难度">
          <el-option label="简单" value="easy" />
          <el-option label="中等" value="medium" />
          <el-option label="困难" value="hard" />
        </el-select>
        <el-input-number v-model="practiceForm.count" :min="1" :max="5" />
        <el-button type="primary" :loading="questionLoading" @click="generateQuestionFlow">
          {{ selectedKnowledgePoint ? '生成题目' : '生成 AI 临时题' }}
        </el-button>
      </div>

      <div class="question-list">
        <template v-if="selectedKnowledgePoint">
          <article v-for="question in questions" :key="question.id" class="question-item">
            <div class="question-head">
              <div>
                <el-tag size="small" effect="plain">{{ questionTypeLabel(question.questionType) }}</el-tag>
                <el-tag size="small" effect="plain">{{ difficultyLabel(question.difficulty) }}</el-tag>
              </div>
              <small>{{ question.knowledgePointName || selectedKnowledgePoint.name }}</small>
            </div>

            <p class="question-content">{{ question.content }}</p>

            <el-radio-group
              v-if="question.questionType === 'single_choice'"
              v-model="answerDrafts[question.id]"
              class="option-group"
            >
              <el-radio v-for="option in question.options || []" :key="option" :label="choiceValue(option)">
                {{ option }}
              </el-radio>
            </el-radio-group>

            <el-radio-group
              v-else-if="question.questionType === 'true_false'"
              v-model="answerDrafts[question.id]"
              class="option-group"
            >
              <el-radio label="true">true</el-radio>
              <el-radio label="false">false</el-radio>
            </el-radio-group>

            <el-input
              v-else
              v-model="answerDrafts[question.id]"
              type="textarea"
              resize="none"
              :rows="3"
              placeholder="输入你的答案"
            />

            <div class="question-actions">
              <el-button :loading="answerLoadingId === question.id" @click="submitAnswerFlow(question)">
                提交答案
              </el-button>
            </div>

            <div v-if="answerResults[question.id]" class="feedback-box">
              <strong>
                {{ answerResults[question.id].score }} 分 ·
                {{ answerResults[question.id].correct ? '回答正确' : '需要复习' }}
              </strong>
              <div class="markdown-body" v-html="renderMarkdown(answerResults[question.id].feedback)" />
            </div>
          </article>
          <el-empty v-if="questions.length === 0" description="暂无题目" :image-size="90" />
        </template>

        <template v-else>
          <article v-for="question in aiQuestions" :key="question.temporaryId" class="question-item">
            <div class="question-head">
              <div>
                <el-tag size="small" effect="plain">{{ questionTypeLabel(question.questionType) }}</el-tag>
                <el-tag size="small" effect="plain">{{ difficultyLabel(question.difficulty) }}</el-tag>
                <el-tag size="small" effect="plain">AI 临时</el-tag>
              </div>
              <small>{{ question.topic }}</small>
            </div>

            <p class="question-content">{{ question.content }}</p>

            <el-radio-group
              v-if="question.questionType === 'single_choice'"
              v-model="aiAnswerDrafts[question.temporaryId]"
              class="option-group"
            >
              <el-radio v-for="option in question.options || []" :key="option" :label="choiceValue(option)">
                {{ option }}
              </el-radio>
            </el-radio-group>

            <el-radio-group
              v-else-if="question.questionType === 'true_false'"
              v-model="aiAnswerDrafts[question.temporaryId]"
              class="option-group"
            >
              <el-radio label="true">true</el-radio>
              <el-radio label="false">false</el-radio>
            </el-radio-group>

            <el-input
              v-else
              v-model="aiAnswerDrafts[question.temporaryId]"
              type="textarea"
              resize="none"
              :rows="3"
              placeholder="输入你的答案"
            />

            <div class="question-actions">
              <el-button @click="submitAiAnswerFlow(question)">
                {{ question.questionType === 'short_answer' ? '查看参考反馈' : '提交答案' }}
              </el-button>
            </div>

            <div v-if="aiAnswerResults[question.temporaryId]" class="feedback-box">
              <strong>{{ aiAnswerResults[question.temporaryId].title }}</strong>
              <div class="markdown-body" v-html="renderMarkdown(aiAnswerResults[question.temporaryId].feedback)" />
            </div>
          </article>
          <el-empty v-if="aiQuestions.length === 0" description="暂无临时题目" :image-size="90" />
        </template>
      </div>
    </section>

    <aside class="practice-side panel">
      <p class="eyebrow">Bridge</p>
      <h2>学习与练习联动</h2>
      <p class="side-copy">
        标准知识点练习会更新掌握度；自由主题练习先作为临时训练，用来承接 Chat 中出现的新主题。
      </p>
      <div class="context-block">
        <span>当前内容</span>
        <strong>{{ selectedKnowledgePoint?.name || activeTopic || '未选择' }}</strong>
      </div>
      <el-button :icon="Reading" @click="goLearningPath">去教学模式</el-button>
    </aside>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, reactive, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { Reading } from '@element-plus/icons-vue'
import { ElMessage } from 'element-plus'
import { generateAiPractice, generateQuestions, listKnowledgeTree, listQuestions, submitAnswer } from '../api'
import type { AiPracticeQuestion, AnswerResult, KnowledgePoint, Question } from '../types/domain'
import { difficultyLabel, questionTypeLabel } from '../utils/format'
import { findKnowledgePoint, firstSelectableKnowledgePoint, flattenKnowledgePoints } from '../utils/knowledge'
import { renderMarkdown } from '../utils/markdown'

interface LocalAiAnswerResult {
  title: string
  feedback: string
}

const route = useRoute()
const router = useRouter()
const knowledgeTree = ref<KnowledgePoint[]>([])
const selectedKnowledgePointId = ref<number | null>(null)
const topicInput = ref('')
const loadingKnowledge = ref(false)
const practiceForm = reactive({
  questionType: 'single_choice',
  difficulty: 'medium',
  count: 1
})
const questions = ref<Question[]>([])
const aiQuestions = ref<AiPracticeQuestion[]>([])
const answerDrafts = reactive<Record<number, string>>({})
const answerResults = reactive<Record<number, AnswerResult>>({})
const aiAnswerDrafts = reactive<Record<number, string>>({})
const aiAnswerResults = reactive<Record<number, LocalAiAnswerResult>>({})
const questionLoading = ref(false)
const answerLoadingId = ref<number | null>(null)

const selectedKnowledgePoint = computed(() =>
  flattenKnowledgePoints(knowledgeTree.value).find((item) => item.id === selectedKnowledgePointId.value) || null
)
const activeTopic = computed(() => topicInput.value.trim())
const practiceTitle = computed(() => {
  if (selectedKnowledgePoint.value) {
    return selectedKnowledgePoint.value.name
  }
  if (activeTopic.value) {
    return activeTopic.value
  }
  return '选择知识点或输入自由主题'
})

onMounted(async () => {
  applyRouteTopic()
  await loadKnowledgeTreeFlow()
  await applyRouteKnowledgePoint()
})

watch(
  () => [route.query.knowledgePointId, route.query.topic],
  async () => {
    applyRouteTopic()
    await applyRouteKnowledgePoint()
  }
)

watch(selectedKnowledgePointId, async () => {
  if (selectedKnowledgePoint.value) {
    topicInput.value = ''
    aiQuestions.value = []
    clearAiAnswerState()
  }
  await loadQuestionsFlow()
})

function applyRouteTopic() {
  const topic = String(route.query.topic || '').trim()
  if (topic) {
    topicInput.value = topic
    selectedKnowledgePointId.value = null
    questions.value = []
  }
}

async function loadKnowledgeTreeFlow() {
  loadingKnowledge.value = true
  try {
    knowledgeTree.value = await listKnowledgeTree('Java')
    if (!activeTopic.value && !selectedKnowledgePointId.value) {
      selectedKnowledgePointId.value = firstSelectableKnowledgePoint(knowledgeTree.value)?.id || null
    }
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '加载知识点失败')
  } finally {
    loadingKnowledge.value = false
  }
}

async function applyRouteKnowledgePoint() {
  const queryId = Number(route.query.knowledgePointId)
  if (queryId && findKnowledgePoint(knowledgeTree.value, queryId)) {
    selectedKnowledgePointId.value = queryId
  }
}

function selectKnowledgePoint(point: KnowledgePoint) {
  selectedKnowledgePointId.value = point.id
}

function useTopicInput() {
  if (!activeTopic.value) {
    ElMessage.warning('请输入主题')
    return
  }
  selectedKnowledgePointId.value = null
  questions.value = []
  aiQuestions.value = []
  clearAiAnswerState()
}

async function generateQuestionFlow() {
  if (selectedKnowledgePoint.value) {
    await generateStandardQuestionFlow()
    return
  }
  await generateAiQuestionFlow()
}

async function generateStandardQuestionFlow() {
  if (!selectedKnowledgePoint.value) {
    ElMessage.warning('请选择知识点')
    return
  }

  questionLoading.value = true
  try {
    questions.value = await generateQuestions(
      selectedKnowledgePoint.value.id,
      practiceForm.questionType,
      practiceForm.difficulty,
      practiceForm.count
    )
    ElMessage.success('题目已生成')
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '生成题目失败')
  } finally {
    questionLoading.value = false
  }
}

async function generateAiQuestionFlow() {
  if (!activeTopic.value) {
    ElMessage.warning('请选择知识点或输入自由主题')
    return
  }

  questionLoading.value = true
  try {
    const result = await generateAiPractice(
      activeTopic.value,
      practiceForm.questionType,
      practiceForm.difficulty,
      practiceForm.count
    )
    aiQuestions.value = result.questions
    clearAiAnswerState()
    ElMessage.success('AI 临时题已生成')
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '生成 AI 临时题失败')
  } finally {
    questionLoading.value = false
  }
}

async function loadQuestionsFlow() {
  if (!selectedKnowledgePoint.value) {
    questions.value = []
    return
  }
  try {
    questions.value = await listQuestions(selectedKnowledgePoint.value.id)
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '加载题目失败')
  }
}

async function submitAnswerFlow(question: Question) {
  const answer = answerDrafts[question.id]?.trim()
  if (!answer) {
    ElMessage.warning('请输入答案')
    return
  }

  answerLoadingId.value = question.id
  try {
    answerResults[question.id] = await submitAnswer(question.id, answer)
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '提交答案失败')
  } finally {
    answerLoadingId.value = null
  }
}

function submitAiAnswerFlow(question: AiPracticeQuestion) {
  const answer = aiAnswerDrafts[question.temporaryId]?.trim()
  if (!answer) {
    ElMessage.warning('请输入答案')
    return
  }

  if (question.questionType === 'short_answer') {
    aiAnswerResults[question.temporaryId] = {
      title: '参考反馈',
      feedback: `参考答案：${question.answer}\n\n解析：${question.analysis}`
    }
    return
  }

  const correct = aiAnswersEqual(question.questionType, question.answer, answer)
  aiAnswerResults[question.temporaryId] = {
    title: correct ? '100 分 · 回答正确' : '0 分 · 需要复习',
    feedback: `${correct ? '回答正确。' : `回答不正确。正确答案是：${question.answer}`}\n\n解析：${question.analysis}`
  }
}

function goLearningPath() {
  if (selectedKnowledgePoint.value) {
    router.push({ name: 'learn', query: { knowledgePointId: selectedKnowledgePoint.value.id } })
    return
  }
  router.push({ name: 'learn', query: activeTopic.value ? { topic: activeTopic.value, source: 'practice' } : {} })
}

function choiceValue(option: string) {
  const first = option.trim().charAt(0).toUpperCase()
  return first >= 'A' && first <= 'D' ? first : option
}

function aiAnswersEqual(questionType: string, expectedAnswer: string, studentAnswer: string) {
  if (questionType === 'single_choice') {
    return choiceValue(expectedAnswer) === choiceValue(studentAnswer)
  }
  if (questionType === 'true_false') {
    return normalizeBooleanAnswer(expectedAnswer) === normalizeBooleanAnswer(studentAnswer)
  }
  return normalizeAnswerText(expectedAnswer) === normalizeAnswerText(studentAnswer)
}

function normalizeBooleanAnswer(answer: string) {
  const normalized = answer.trim().toLowerCase()
  if (['true', 't', 'yes', 'y', '1', 'right', 'correct', '对', '正确'].includes(normalized)) {
    return 'true'
  }
  if (['false', 'f', 'no', 'n', '0', 'wrong', 'incorrect', '错', '错误'].includes(normalized)) {
    return 'false'
  }
  return normalized
}

function normalizeAnswerText(answer: string) {
  return answer.trim().replace(/\s+/g, '').toLowerCase()
}

function clearAiAnswerState() {
  Object.keys(aiAnswerDrafts).forEach((key) => delete aiAnswerDrafts[Number(key)])
  Object.keys(aiAnswerResults).forEach((key) => delete aiAnswerResults[Number(key)])
}
</script>
