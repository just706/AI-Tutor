<template>
  <div class="practice-page split-page">
    <aside class="path-panel panel">
      <div class="section-head">
        <div>
          <p class="eyebrow">Knowledge</p>
          <h2>选择知识点</h2>
        </div>
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
          <h2>{{ selectedKnowledgePoint?.name || '选择知识点后开始练习' }}</h2>
        </div>
        <el-button text type="primary" @click="router.push({ name: 'learn' })">回到学习路径</el-button>
      </div>

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
        <el-button type="primary" :loading="questionLoading" @click="generateQuestionFlow">生成题目</el-button>
      </div>

      <div class="question-list">
        <article v-for="question in questions" :key="question.id" class="question-item">
          <div class="question-head">
            <div>
              <el-tag size="small" effect="plain">{{ questionTypeLabel(question.questionType) }}</el-tag>
              <el-tag size="small" effect="plain">{{ difficultyLabel(question.difficulty) }}</el-tag>
            </div>
            <small>{{ question.knowledgePointName || selectedKnowledgePoint?.name }}</small>
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
      </div>
    </section>

    <aside class="practice-side panel">
      <p class="eyebrow">Bridge</p>
      <h2>学习与练习联动</h2>
      <p class="side-copy">
        从 Learning Path 进入这里时，会自动带上知识点。练习结果会更新掌握度，学习分析和 Agent 建议也会跟着变化。
      </p>
      <div class="context-block">
        <span>当前知识点</span>
        <strong>{{ selectedKnowledgePoint?.name || '未选择' }}</strong>
      </div>
      <el-button :icon="Reading" @click="router.push({ name: 'learn' })">去教学模式</el-button>
    </aside>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, reactive, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { Reading } from '@element-plus/icons-vue'
import { ElMessage } from 'element-plus'
import { generateQuestions, listKnowledgeTree, listQuestions, submitAnswer } from '../api'
import type { AnswerResult, KnowledgePoint, Question } from '../types/domain'
import { difficultyLabel, questionTypeLabel } from '../utils/format'
import { findKnowledgePoint, firstSelectableKnowledgePoint, flattenKnowledgePoints } from '../utils/knowledge'
import { renderMarkdown } from '../utils/markdown'

const route = useRoute()
const router = useRouter()
const knowledgeTree = ref<KnowledgePoint[]>([])
const selectedKnowledgePointId = ref<number | null>(null)
const loadingKnowledge = ref(false)
const practiceForm = reactive({
  questionType: 'single_choice',
  difficulty: 'medium',
  count: 1
})
const questions = ref<Question[]>([])
const answerDrafts = reactive<Record<number, string>>({})
const answerResults = reactive<Record<number, AnswerResult>>({})
const questionLoading = ref(false)
const answerLoadingId = ref<number | null>(null)

const selectedKnowledgePoint = computed(() =>
  flattenKnowledgePoints(knowledgeTree.value).find((item) => item.id === selectedKnowledgePointId.value) || null
)

onMounted(async () => {
  await loadKnowledgeTreeFlow()
  await applyRouteKnowledgePoint()
})

watch(
  () => route.query.knowledgePointId,
  async () => {
    await applyRouteKnowledgePoint()
  }
)

watch(selectedKnowledgePointId, async () => {
  await loadQuestionsFlow()
})

async function loadKnowledgeTreeFlow() {
  loadingKnowledge.value = true
  try {
    knowledgeTree.value = await listKnowledgeTree('Java')
    selectedKnowledgePointId.value = firstSelectableKnowledgePoint(knowledgeTree.value)?.id || null
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

async function generateQuestionFlow() {
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

function choiceValue(option: string) {
  const first = option.trim().charAt(0).toUpperCase()
  return first >= 'A' && first <= 'D' ? first : option
}
</script>
