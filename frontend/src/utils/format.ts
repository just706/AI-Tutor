import type { AgentSuggestion } from '../types/domain'

export function formatTime(value?: string) {
  if (!value) {
    return ''
  }
  return value.replace('T', ' ').slice(0, 16)
}

export function formatMinutes(value?: number) {
  const minutes = value || 0
  if (minutes < 60) {
    return `${minutes} 分钟`
  }
  const hours = Math.floor(minutes / 60)
  const rest = minutes % 60
  return rest > 0 ? `${hours} 小时 ${rest} 分钟` : `${hours} 小时`
}

export function modeLabel(mode: string) {
  if (mode === 'teaching') {
    return '教学'
  }
  if (mode === 'rag') {
    return '资料问答'
  }
  return 'AI 问答'
}

export function questionTypeLabel(type: string) {
  if (type === 'true_false') {
    return '判断题'
  }
  if (type === 'short_answer') {
    return '简答题'
  }
  return '单选题'
}

export function difficultyLabel(difficulty: string) {
  if (difficulty === 'easy') {
    return '简单'
  }
  if (difficulty === 'hard') {
    return '困难'
  }
  return '中等'
}

export function agentTypeLabel(type: AgentSuggestion['agentType']) {
  const labels: Record<AgentSuggestion['agentType'], string> = {
    planning: '学习规划',
    teaching: '教学',
    practice: '练习',
    analysis: '分析'
  }
  return labels[type] || type
}

export function agentStatusLabel(status: AgentSuggestion['status']) {
  const labels: Record<AgentSuggestion['status'], string> = {
    pending: '待处理',
    confirmed: '已确认',
    completed: '已完成',
    dismissed: '已忽略'
  }
  return labels[status] || status
}

export function agentStatusTagType(status: AgentSuggestion['status']) {
  if (status === 'confirmed') {
    return 'warning'
  }
  if (status === 'completed') {
    return 'success'
  }
  if (status === 'dismissed') {
    return 'info'
  }
  return 'primary'
}

export function impactLabel(impact: AgentSuggestion['impactLevel']) {
  if (impact === 'high') {
    return '高影响'
  }
  if (impact === 'medium') {
    return '中影响'
  }
  return '低影响'
}

export function impactTagType(impact: AgentSuggestion['impactLevel']) {
  if (impact === 'high') {
    return 'danger'
  }
  if (impact === 'medium') {
    return 'warning'
  }
  return 'info'
}
