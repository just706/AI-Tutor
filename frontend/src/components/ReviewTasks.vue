<template>
  <section class="review-section" v-loading="loading">
    <div class="section-head">
      <h2>针对性复习</h2>
      <div class="review-controls">
        <el-button :icon="Plus" :loading="loading" @click="load(true)">生成复习任务</el-button>
        <el-tooltip content="刷新复习任务">
          <el-button :icon="Refresh" aria-label="刷新复习任务" :disabled="loading" @click="load(false)" />
        </el-tooltip>
      </div>
    </div>
    <el-radio-group :model-value="status" aria-label="复习状态" :disabled="loading || submitting !== null"
      @change="changeStatus">
      <el-radio-button value="pending">待复习</el-radio-button>
      <el-radio-button value="completed">已完成</el-radio-button>
    </el-radio-group>
    <el-alert v-if="error" :title="error" type="error" :closable="false" />
    <p v-else-if="!items.length" class="empty-line">暂无复习任务。</p>
    <details v-for="task in items" :key="task.id" class="review-item" :open="activeId === task.id" @toggle="onToggle(task.id, $event)">
      <summary>
        <span>{{ task.knowledgePointName }} · #{{ task.id }}</span>
        <span class="review-meta">
          <el-tag :type="task.status === 'completed' ? 'success' : 'warning'" effect="plain">
            {{ task.status === 'completed' ? '已完成' : isDue(task) ? '待复习' : '待到期' }}
          </el-tag>
          <small>{{ task.dueTime.replace('T', ' ') }}</small>
        </span>
      </summary>
      <p class="review-question">{{ task.question }}</p>
      <p class="review-trace">
        来源依据 #{{ task.sourceEvidenceId }} · {{ task.assessmentType === 'objective' ? '答案键比对' : '模型评价' }}
        <template v-if="task.resultEvidenceId"> · 结果依据 #{{ task.resultEvidenceId }} · {{ task.completedTime?.replace('T', ' ') }}</template>
      </p>
      <template v-if="task.status === 'pending'">
        <el-radio-group v-if="task.questionType === 'single_choice'" v-model="answers[task.id]" class="review-options" :aria-label="`复习答案 #${task.id}`">
          <el-radio v-for="(option, index) in task.options" :key="index" :value="String.fromCharCode(65 + index)">
            {{ String.fromCharCode(65 + index) }}. {{ option }}
          </el-radio>
        </el-radio-group>
        <el-radio-group v-else-if="task.questionType === 'true_false'" v-model="answers[task.id]" :aria-label="`复习答案 #${task.id}`">
          <el-radio value="true">正确</el-radio><el-radio value="false">错误</el-radio>
        </el-radio-group>
        <el-input v-else v-model="answers[task.id]" type="textarea" :rows="4" :maxlength="5000" :aria-label="`复习回答 #${task.id}`" />
        <div class="review-submit">
          <el-button type="primary" :icon="Check" :loading="submitting === task.id"
            :disabled="submitting !== null || !isDue(task) || !answers[task.id]?.trim()" @click="submit(task)">提交复习</el-button>
        </div>
      </template>
      <el-alert v-if="submitErrors[task.id]" :title="submitErrors[task.id]" type="error" :closable="false" />
      <div v-if="resultFor(task)" class="review-result" role="status">
        <strong>{{ resultFor(task)!.score === null ? '未评分 · 任务待完成' : `${resultFor(task)!.score} 分` }} · {{ resultFor(task)!.masteryBefore }} → {{ resultFor(task)!.masteryAfter }}</strong>
        <p>{{ resultFor(task)!.feedback }}</p>
        <small>依据 #{{ resultFor(task)!.evidenceId }}</small>
      </div>
    </details>
    <nav class="review-pagination" aria-label="复习分页">
      <el-button :icon="ArrowLeft" aria-label="上一页复习" :disabled="loading || submitting !== null || page <= 1" @click="load(false, page - 1)" />
      <span>{{ page }} / {{ Math.max(1, Math.ceil(total / pageSize)) }} · 共 {{ total }} 项</span>
      <el-button :icon="ArrowRight" aria-label="下一页复习" :disabled="loading || submitting !== null || page * pageSize >= total" @click="load(false, page + 1)" />
    </nav>
  </section>
</template>

<script setup lang="ts">
import { onMounted, onUnmounted, reactive, ref } from 'vue'
import { ArrowLeft, ArrowRight, Check, Plus, Refresh } from '@element-plus/icons-vue'
import { answerReview, generateReviews, listReviewPage } from '../api'
import type { ReviewResult, ReviewTask } from '../types/domain'

const emit = defineEmits<{ changed: [] }>()
const items = ref<ReviewTask[]>([])
const status = ref<'pending' | 'completed'>('pending')
const page = ref(1)
const total = ref(0)
const pageSize = 20
const answers = reactive<Record<number, string>>({})
const results = reactive<Record<number, ReviewResult>>({})
const submitErrors = reactive<Record<number, string>>({})
const activeId = ref<number>()
const loading = ref(false)
const submitting = ref<number | null>(null)
const error = ref('')
const now = ref(Date.now())
let timer: ReturnType<typeof setInterval> | undefined
let version = 0

function isDue(task: ReviewTask) { return new Date(task.dueTime).getTime() <= now.value }
function resultFor(task: ReviewTask) { return task.result || results[task.id] }
function onToggle(id: number, event: Event) {
  if ((event.target as HTMLDetailsElement).open) activeId.value = id
  else if (activeId.value === id) activeId.value = undefined
}
function changeStatus(value: unknown) {
  if (value === 'pending' || value === 'completed') load(false, 1, value)
}
async function load(generate = false, targetPage = page.value, targetStatus = status.value) {
  const current = ++version
  loading.value = true
  error.value = ''
  try {
    if (generate) {
      await generateReviews()
      if (current !== version) return
      targetPage = 1
      targetStatus = 'pending'
    }
    const rows = await listReviewPage(targetStatus, targetPage, pageSize)
    if (current === version) {
      now.value = Date.now(); items.value = rows.items
      total.value = rows.total; page.value = rows.page; status.value = targetStatus
    }
  } catch (cause) {
    if (current === version) error.value = cause instanceof Error ? cause.message : '加载复习任务失败'
  } finally { if (current === version) loading.value = false }
}
async function submit(task: ReviewTask) {
  if (submitting.value !== null) return
  submitting.value = task.id
  submitErrors[task.id] = ''
  try {
    results[task.id] = await answerReview(task.id, answers[task.id]!.trim())
    if (results[task.id]!.completed) {
      task.status = 'completed'
      task.resultEvidenceId = results[task.id]!.evidenceId
      activeId.value = task.id
      await load(false, 1, 'completed')
    }
    emit('changed')
  } catch (cause) { submitErrors[task.id] = cause instanceof Error ? cause.message : '提交复习失败' }
  finally { submitting.value = null }
}
onMounted(() => { load(); timer = setInterval(() => { now.value = Date.now() }, 30000) })
onUnmounted(() => { if (timer) clearInterval(timer); version++ })
</script>

<style scoped>
.review-section { padding: 24px 0; border-top: 1px solid var(--line, #dfe4e0); min-width: 0; }
.review-section h2 { font-size: 20px; margin: 0; }
.review-controls, .review-meta { display: flex; align-items: center; flex-wrap: wrap; gap: 8px; }
.review-item { border-bottom: 1px solid var(--line, #dfe4e0); padding-bottom: 12px; }
.review-item summary { cursor: pointer; padding: 16px 0; display: flex; justify-content: space-between; gap: 12px; }
.review-question, .review-result p { white-space: pre-wrap; overflow-wrap: anywhere; line-height: 1.6; }
.review-trace, .review-meta small { color: #66736c; overflow-wrap: anywhere; font-size: 12px; }
.review-options { display: flex; flex-direction: column; align-items: flex-start; width: 100%; }
.review-options :deep(.el-radio) { height: auto; min-height: 36px; max-width: 100%; margin-right: 0; }
.review-options :deep(.el-radio__label) { white-space: normal; overflow-wrap: anywhere; min-width: 0; line-height: 1.5; }
.review-submit { padding: 16px 0; }
.review-pagination { display: flex; align-items: center; justify-content: center; gap: 12px; padding: 16px 0; font-size: 13px; }
.review-result { border-left: 3px solid #42856b; padding: 12px 16px; margin: 12px 0; }
@media (max-width: 640px) {
  .review-section .section-head, .review-item summary { flex-direction: column; align-items: flex-start; }
  .review-controls { width: 100%; }
}
</style>
