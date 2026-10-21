<template>
  <section class="learning-evidence" v-loading="loading">
    <div class="section-head">
      <div>
        <h2>学习依据</h2>
        <small>工程状态分 · 非精确教育测量</small>
      </div>
      <div class="evidence-controls">
        <el-select v-model="selectedPoint" clearable placeholder="全部知识点" aria-label="筛选学习依据" @change="load">
          <el-option v-for="point in points" :key="point.knowledgePointId" :label="point.knowledgePointName" :value="point.knowledgePointId" />
        </el-select>
        <el-tooltip content="刷新记录">
          <el-button :icon="Refresh" aria-label="刷新记录" :loading="loading" @click="load" />
        </el-tooltip>
      </div>
    </div>
    <el-alert v-if="error" :title="error" type="error" :closable="false" />
    <p v-else-if="items.length === 0" class="empty-line">尚无可追溯评价。</p>
    <details v-for="item in items" :key="item.id" class="evidence-item">
      <summary>
        <el-icon class="evidence-toggle"><ArrowRight /></el-icon>
        <span class="evidence-heading">
          <strong>{{ snapshot(item).knowledgePointName || `知识点 #${item.knowledgePointId}` }}</strong>
          <small>{{ item.createTime.replace('T', ' ') }}</small>
        </span>
        <span class="evidence-result">
          <el-tag size="small" :type="item.assessmentType === 'objective' ? 'info' : 'warning'" effect="plain">
            {{ item.assessmentType === 'objective' ? '答案键比对' : '模型评价' }}
          </el-tag>
          <span>{{ item.score === null ? '未评分' : `${item.score} 分` }}</span>
          <strong>{{ item.masteryBefore }} → {{ item.masteryAfter }}</strong>
        </span>
      </summary>
      <dl class="evidence-detail">
        <dt>题目 / 评价主题</dt><dd>{{ snapshot(item).question }}</dd>
        <template v-if="snapshot(item).options?.length">
          <dt>选项</dt><dd><ol type="A"><li v-for="(option, index) in snapshot(item).options" :key="index">{{ option }}</li></ol></dd>
        </template>
        <dt>本次作答</dt><dd>{{ item.studentAnswer }}</dd>
        <template v-if="snapshot(item).referenceAnswer">
          <dt>参考答案</dt><dd>{{ snapshot(item).referenceAnswer }}</dd>
        </template>
        <dt>反馈</dt><dd>{{ item.feedback }}</dd>
        <template v-if="snapshot(item).reviewTaskId">
          <dt>复习来源</dt><dd>任务 #{{ snapshot(item).reviewTaskId }} · 来源依据 #{{ snapshot(item).sourceEvidenceId }}</dd>
        </template>
        <dt>状态依据</dt><dd>{{ item.reason }}</dd>
        <dt>记录</dt><dd>#{{ item.id }}<template v-if="item.priorEvidenceId"> · 上次评分 #{{ item.priorEvidenceId }}</template> · {{ item.ruleVersion }}<template v-if="snapshot(item).model"> · {{ snapshot(item).model }}</template></dd>
      </dl>
    </details>
  </section>
</template>

<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { ArrowRight, Refresh } from '@element-plus/icons-vue'
import { listLearningEvidence } from '../api'
import type { KnowledgePointProgress, LearningEvidence } from '../types/domain'

defineProps<{ points: KnowledgePointProgress[] }>()
const selectedPoint = ref<number>()
const items = ref<LearningEvidence[]>([])
const loading = ref(false)
const error = ref('')
let requestVersion = 0
type EvidenceSnapshot = { knowledgePointName?: string; question?: string; options?: string[]; referenceAnswer?: string; model?: string; reviewTaskId?: number; sourceEvidenceId?: number }

function snapshot(item: LearningEvidence): EvidenceSnapshot {
  try { return JSON.parse(item.snapshot) || {} } catch { return {} }
}

async function load() {
  const version = ++requestVersion
  loading.value = true
  error.value = ''
  try {
    const result = await listLearningEvidence(selectedPoint.value, 100)
    if (version === requestVersion) items.value = result
  } catch (cause) {
    if (version === requestVersion) {
      items.value = []
      error.value = cause instanceof Error ? cause.message : '加载学习依据失败'
    }
  } finally {
    if (version === requestVersion) loading.value = false
  }
}
onMounted(load)
</script>

<style scoped>
.learning-evidence { padding: 24px 0; border-top: 1px solid var(--line, #dfe4e0); min-width: 0; }
.learning-evidence h2 { margin: 0 0 6px; font-size: 20px; }
.evidence-controls { display: flex; gap: 8px; max-width: 100%; }
.evidence-controls .el-select { width: 200px; }
.evidence-item { border-bottom: 1px solid var(--line, #dfe4e0); }
.evidence-toggle { flex: 0 0 16px; margin-top: 3px; }
.evidence-item[open] .evidence-toggle { transform: rotate(90deg); }
.evidence-item summary { display: flex; justify-content: space-between; gap: 16px; padding: 16px 0; cursor: pointer; }
.evidence-heading { display: grid; gap: 6px; min-width: 0; overflow-wrap: anywhere; margin-right: auto; }
.evidence-heading small, .learning-evidence > .section-head small { color: #66736c; }
.evidence-result { display: flex; align-items: center; gap: 12px; flex-wrap: wrap; flex-shrink: 0; }
.evidence-detail { display: grid; grid-template-columns: 112px minmax(0, 1fr); gap: 12px 16px; margin: 0; padding: 0 0 20px; }
.evidence-detail dt { color: #66736c; }
.evidence-detail dd { margin: 0; white-space: pre-wrap; overflow-wrap: anywhere; line-height: 1.6; }
.evidence-detail ol { margin: 0; padding-left: 24px; }
@media (max-width: 640px) {
  .learning-evidence .section-head, .evidence-item summary { align-items: flex-start; flex-direction: column; }
  .evidence-controls { width: 100%; }
  .evidence-controls .el-select { width: auto; flex: 1; }
  .evidence-result { flex-shrink: 1; }
  .evidence-detail { grid-template-columns: minmax(0, 1fr); gap: 6px; }
  .evidence-detail dd { margin-bottom: 10px; }
}
</style>
