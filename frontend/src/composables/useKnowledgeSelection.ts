import { computed, onUnmounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { listKnowledgeTree } from '../api'
import type { KnowledgePoint } from '../types/domain'
import { findKnowledgePoint, flattenKnowledgePoints } from '../utils/knowledge'

/** 学科和知识点保存在地址中；未选择时不推断学科，也不自动选中首题。 */
export function useKnowledgeSelection() {
  const route = useRoute()
  const router = useRouter()
  const catalog = ref<KnowledgePoint[]>([])
  const loadingKnowledge = ref(false)
  const catalogError = ref('')
  let requestVersion = 0
  const text = (value: unknown) => typeof value === 'string' ? value.trim() : ''
  const queryPoint = computed(() => findKnowledgePoint(catalog.value, Number(route.query.knowledgePointId)))
  // 兼容只携带知识点 ID 的旧链接，学科只能来自该 ID 的实际记录。
  const knowledgeSubject = computed(() => text(route.query.subject) || queryPoint.value?.subject || '')
  const subjects = computed(() => [...new Set(flattenKnowledgePoints(catalog.value).map(point => point.subject))].sort())
  const knowledgeTree = computed(() => catalog.value.filter(point => point.subject === knowledgeSubject.value))
  const selectedKnowledgePoint = computed(() => queryPoint.value?.subject === knowledgeSubject.value ? queryPoint.value : null)
  const selectedKnowledgePointId = computed(() => selectedKnowledgePoint.value?.id || null)
  const activeTopic = computed(() => selectedKnowledgePoint.value ? '' : text(route.query.topic))
  const generationTopic = computed(() => knowledgeSubject.value && activeTopic.value
    ? `${knowledgeSubject.value}：${activeTopic.value}` : activeTopic.value)
  const selectionKey = computed(() => JSON.stringify([knowledgeSubject.value, selectedKnowledgePointId.value, activeTopic.value]))
  const selectionNotice = computed(() => {
    if (catalogError.value) return catalogError.value
    if (loadingKnowledge.value) return ''
    if (route.query.knowledgePointId && !selectedKnowledgePoint.value) return '未找到与当前学科匹配的知识点，请重新选择或使用自由主题。'
    if (!knowledgeSubject.value) return '请先选择学科，也可以直接使用自由主题。'
    if (!knowledgeTree.value.length) return '该学科暂无已收录知识点，可以使用自由主题或上传教材学习。'
    return ''
  })

  async function loadKnowledgeTreeFlow() {
    const version = ++requestVersion
    loadingKnowledge.value = true
    catalogError.value = ''
    try {
      const result = await listKnowledgeTree()
      if (version === requestVersion) catalog.value = result
    } catch (error) {
      if (version === requestVersion) {
        catalog.value = []
        catalogError.value = error instanceof Error ? error.message : '加载知识点失败，请重试。'
      }
    } finally {
      if (version === requestVersion) loadingKnowledge.value = false
    }
  }
  function selectSubject(subject: string | null | undefined) {
    return router.replace({ query: { subject: text(subject) || undefined } })
  }
  function selectKnowledgePoint(point: KnowledgePoint) {
    return router.replace({ query: { subject: point.subject, knowledgePointId: String(point.id) } })
  }
  function selectTopic(topic: string) {
    return router.replace({ query: { subject: knowledgeSubject.value || undefined, topic: topic.trim() || undefined } })
  }
  const navigationQuery = computed(() => selectedKnowledgePoint.value
    ? { subject: selectedKnowledgePoint.value.subject, knowledgePointId: String(selectedKnowledgePoint.value.id) }
    : { subject: knowledgeSubject.value || undefined, topic: activeTopic.value || undefined })
  onUnmounted(() => { requestVersion++ })
  return { knowledgeSubject, subjects, knowledgeTree, selectedKnowledgePoint, selectedKnowledgePointId,
    activeTopic, generationTopic, selectionKey, selectionNotice, loadingKnowledge, loadKnowledgeTreeFlow,
    selectSubject, selectKnowledgePoint, selectTopic, navigationQuery }
}
