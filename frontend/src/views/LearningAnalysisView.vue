<template>
  <div class="analysis-page" v-loading="analysisLoading">
    <section class="analysis-hero panel">
      <div>
        <p class="eyebrow">Learning Analysis</p>
        <h2>掌握趋势</h2>
        <p>用当前学习记录估算掌握度、正确率和薄弱点，作为下一步计划的依据。</p>
      </div>
      <div class="analysis-metrics">
        <div>
          <span>平均掌握</span>
          <strong>{{ analysisOverview?.averageMasteryLevel ?? 0 }}%</strong>
        </div>
        <div>
          <span>答题正确率</span>
          <strong>{{ analysisOverview?.answerAccuracy ?? 0 }}%</strong>
        </div>
        <div>
          <span>学习时长</span>
          <strong>{{ formatMinutes(analysisOverview?.totalStudyTime) }}</strong>
        </div>
      </div>
    </section>

    <section class="analysis-grid">
      <div class="panel mastery-panel">
        <div class="section-head">
          <div>
            <p class="eyebrow">Mastery</p>
            <h2>知识点掌握</h2>
          </div>
          <el-button text type="primary" :loading="analysisLoading" @click="loadAnalysisOverviewFlow">刷新</el-button>
        </div>
        <div v-if="knowledgeProgress.length === 0" class="empty-line">暂无知识点明细。</div>
        <article v-for="item in knowledgeProgress.slice(0, 8)" :key="item.knowledgePointId" class="progress-row">
          <div class="progress-title">
            <strong>{{ item.knowledgePointName }}</strong>
            <small>正确率 {{ item.answerAccuracy }}% · 已答 {{ item.answeredQuestionCount }} 题</small>
          </div>
          <el-progress :percentage="item.masteryLevel" :stroke-width="8" />
        </article>
      </div>

      <aside class="panel focus-panel">
        <div class="section-head">
          <div>
            <p class="eyebrow">Focus Areas</p>
            <h2>薄弱点</h2>
          </div>
        </div>
        <div v-if="!analysisOverview || analysisOverview.weakKnowledgePoints.length === 0" class="empty-line">
          暂无明显薄弱点。
        </div>
        <article
          v-for="point in analysisOverview?.weakKnowledgePoints || []"
          :key="point.knowledgePointId"
          class="focus-row"
        >
          <div>
            <strong>{{ point.knowledgePointName }}</strong>
            <p>{{ point.reason }}</p>
          </div>
          <el-tag type="warning" effect="plain">{{ point.masteryLevel ?? point.answerAccuracy ?? 0 }}%</el-tag>
        </article>
      </aside>

      <div class="panel recent-answer-panel">
        <div class="section-head">
          <div>
            <p class="eyebrow">Recent Performance</p>
            <h2>近期答题</h2>
          </div>
        </div>
        <div v-if="recentAnswers.length === 0" class="empty-line">暂无答题记录。</div>
        <article v-for="answer in recentAnswers" :key="answer.answerRecordId" class="answer-analysis-item">
          <div class="answer-analysis-head">
            <strong>{{ answer.knowledgePointName || '未知知识点' }}</strong>
            <el-tag size="small" :type="answer.correct ? 'success' : 'danger'" effect="plain">
              {{ answer.correct ? '正确' : '需要复习' }} · {{ answer.score }} 分
            </el-tag>
          </div>
          <p>{{ answer.questionContent || '题目内容为空' }}</p>
          <small v-if="answer.feedbackPreview">{{ answer.feedbackPreview }}</small>
        </article>
      </div>

      <aside class="panel study-plan-panel">
        <div class="section-head">
          <div>
            <p class="eyebrow">Study Plan</p>
            <h2>学习计划</h2>
          </div>
        </div>
        <el-form class="compact-form" label-position="top">
          <el-form-item label="周期">
            <el-select v-model="studyPlanForm.period">
              <el-option label="一周" value="week" />
              <el-option label="一个月" value="month" />
            </el-select>
          </el-form-item>
          <el-form-item label="目标">
            <el-input v-model.trim="studyPlanForm.goal" placeholder="不填则使用学习档案目标" />
          </el-form-item>
        </el-form>
        <el-button type="primary" :loading="studyPlanLoading" @click="generateStudyPlanFlow">
          生成计划
        </el-button>
        <div v-if="studyPlan" class="study-plan">
          <h3>{{ studyPlan.title }}</h3>
          <p>{{ studyPlan.goal }}</p>
          <el-tag size="small" effect="plain">{{ studyPlan.estimatedDays }} 天</el-tag>
          <ol>
            <li v-for="step in studyPlan.steps" :key="step">{{ step }}</li>
          </ol>
        </div>
      </aside>
    </section>
  </div>
</template>

<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { ElMessage } from 'element-plus'
import {
  generateStudyPlan,
  getLearningAnalysisOverview,
  listKnowledgePointProgress,
  listRecentAnswerAnalysis
} from '../api'
import type {
  KnowledgePointProgress,
  LearningAnalysisOverview,
  RecentAnswerAnalysis,
  StudyPlan
} from '../types/domain'
import { formatMinutes } from '../utils/format'

const analysisOverview = ref<LearningAnalysisOverview | null>(null)
const knowledgeProgress = ref<KnowledgePointProgress[]>([])
const recentAnswers = ref<RecentAnswerAnalysis[]>([])
const studyPlan = ref<StudyPlan | null>(null)
const analysisLoading = ref(false)
const studyPlanLoading = ref(false)
const studyPlanForm = reactive({
  period: 'week',
  goal: ''
})

onMounted(loadAnalysisOverviewFlow)

async function loadAnalysisOverviewFlow() {
  analysisLoading.value = true
  try {
    const [overview, progress, answers] = await Promise.all([
      getLearningAnalysisOverview(),
      listKnowledgePointProgress(),
      listRecentAnswerAnalysis(8)
    ])
    analysisOverview.value = overview
    knowledgeProgress.value = progress
    recentAnswers.value = answers
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '加载学习分析失败')
  } finally {
    analysisLoading.value = false
  }
}

async function generateStudyPlanFlow() {
  studyPlanLoading.value = true
  try {
    // 目标为空时交给后端使用学习档案目标，避免前端重复推断。
    studyPlan.value = await generateStudyPlan(studyPlanForm.period, studyPlanForm.goal.trim() || undefined)
    ElMessage.success('学习计划已生成')
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '生成学习计划失败')
  } finally {
    studyPlanLoading.value = false
  }
}
</script>
