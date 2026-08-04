<template>
  <div class="agent-page">
    <section class="agent-toolbar panel">
      <div class="section-head">
        <div>
          <p class="eyebrow">Agent Suggestions</p>
          <h2>主动建议</h2>
        </div>
        <el-button type="primary" :icon="Refresh" :loading="agentLoading" @click="generateAgentSuggestionsFlow">
          生成建议
        </el-button>
      </div>
      <div class="filter-row">
        <el-select v-model="agentTypeFilter" placeholder="类型">
          <el-option label="全部类型" value="all" />
          <el-option label="学习规划" value="planning" />
          <el-option label="教学" value="teaching" />
          <el-option label="练习" value="practice" />
          <el-option label="分析" value="analysis" />
        </el-select>
        <el-select v-model="agentStatusFilter" placeholder="状态">
          <el-option label="待处理" value="pending" />
          <el-option label="已确认" value="confirmed" />
          <el-option label="已完成" value="completed" />
          <el-option label="已忽略" value="dismissed" />
          <el-option label="全部状态" value="" />
        </el-select>
        <el-button :loading="agentLoading" @click="loadAgentSuggestionsFlow">刷新</el-button>
      </div>
    </section>

    <section class="agent-list panel" v-loading="agentLoading">
      <article v-for="item in agentSuggestions" :key="item.id" class="agent-suggestion-card">
        <div class="agent-card-head">
          <div class="agent-kind">
            <el-tag size="small" effect="plain">{{ agentTypeLabel(item.agentType) }}</el-tag>
            <el-tag size="small" :type="impactTagType(item.impactLevel)" effect="plain">
              {{ impactLabel(item.impactLevel) }}
            </el-tag>
          </div>
          <el-tag size="small" :type="agentStatusTagType(item.status)" effect="plain">
            {{ agentStatusLabel(item.status) }}
          </el-tag>
        </div>

        <h2>{{ item.title }}</h2>
        <p class="agent-suggestion-text">{{ item.suggestion }}</p>
        <div class="agent-reason">
          <strong>为什么建议你这么做</strong>
          <p>{{ item.reason || '根据你的学习目标、最近练习和会话记录生成。' }}</p>
        </div>

        <div class="agent-actions">
          <el-button
            size="small"
            :disabled="item.status !== 'pending'"
            :loading="agentActionLoadingId === item.id"
            @click="confirmAgentSuggestionFlow(item)"
          >
            确认
          </el-button>
          <el-button
            size="small"
            type="primary"
            plain
            :disabled="item.status === 'completed' || item.status === 'dismissed' || (item.requiresConfirmation && item.status === 'pending')"
            :loading="agentActionLoadingId === item.id"
            @click="completeAgentSuggestionFlow(item)"
          >
            标记完成
          </el-button>
          <el-button
            size="small"
            text
            type="danger"
            :disabled="item.status === 'completed' || item.status === 'dismissed'"
            :loading="agentActionLoadingId === item.id"
            @click="dismissAgentSuggestionFlow(item)"
          >
            忽略
          </el-button>
        </div>
      </article>

      <el-empty v-if="agentSuggestions.length === 0" description="暂无 Agent 建议" :image-size="90" />
    </section>
  </div>
</template>

<script setup lang="ts">
import { onMounted, ref, watch } from 'vue'
import { Refresh } from '@element-plus/icons-vue'
import { ElMessage } from 'element-plus'
import {
  completeAgentSuggestion,
  confirmAgentSuggestion,
  dismissAgentSuggestion,
  generateAgentSuggestions,
  listAgentSuggestions
} from '../api'
import type { AgentSuggestion } from '../types/domain'
import {
  agentStatusLabel,
  agentStatusTagType,
  agentTypeLabel,
  impactLabel,
  impactTagType
} from '../utils/format'

const agentSuggestions = ref<AgentSuggestion[]>([])
const agentLoading = ref(false)
const agentActionLoadingId = ref<number | null>(null)
const agentTypeFilter = ref('all')
const agentStatusFilter = ref('pending')

onMounted(loadAgentSuggestionsFlow)

watch([agentTypeFilter, agentStatusFilter], async () => {
  await loadAgentSuggestionsFlow()
})

async function loadAgentSuggestionsFlow() {
  agentLoading.value = true
  try {
    agentSuggestions.value = await listAgentSuggestions(
      agentStatusFilter.value || undefined,
      agentTypeFilter.value === 'all' ? undefined : agentTypeFilter.value
    )
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '加载 Agent 建议失败')
  } finally {
    agentLoading.value = false
  }
}

async function generateAgentSuggestionsFlow() {
  agentLoading.value = true
  try {
    await generateAgentSuggestions(agentTypeFilter.value)
    agentStatusFilter.value = 'pending'
    await loadAgentSuggestionsFlow()
    ElMessage.success('Agent 建议已生成')
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '生成 Agent 建议失败')
  } finally {
    agentLoading.value = false
  }
}

async function confirmAgentSuggestionFlow(item: AgentSuggestion) {
  agentActionLoadingId.value = item.id
  try {
    await confirmAgentSuggestion(item.id, '前端确认执行')
    await loadAgentSuggestionsFlow()
    ElMessage.success('建议已确认')
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '确认建议失败')
  } finally {
    agentActionLoadingId.value = null
  }
}

async function completeAgentSuggestionFlow(item: AgentSuggestion) {
  agentActionLoadingId.value = item.id
  try {
    await completeAgentSuggestion(item.id, '前端标记完成')
    await loadAgentSuggestionsFlow()
    ElMessage.success('建议已完成')
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '完成建议失败')
  } finally {
    agentActionLoadingId.value = null
  }
}

async function dismissAgentSuggestionFlow(item: AgentSuggestion) {
  agentActionLoadingId.value = item.id
  try {
    await dismissAgentSuggestion(item.id, '前端忽略建议')
    await loadAgentSuggestionsFlow()
    ElMessage.success('建议已忽略')
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '忽略建议失败')
  } finally {
    agentActionLoadingId.value = null
  }
}
</script>
