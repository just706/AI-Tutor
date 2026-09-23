<template>
  <div class="knowledge-graph-page" v-loading="loading">
    <section class="knowledge-graph-hero panel">
      <div>
        <p class="eyebrow">Knowledge Graph</p>
        <h2>{{ graphMode === 'master' ? '知识关系图谱' : '个人知识图谱' }}</h2>
        <p>{{ graphMode === 'master' ? '查看公共知识点之间的前置依赖关系。' : '查看已确认发布的资料知识点与来源证据。' }}</p>
      </div>
      <div class="knowledge-graph-controls">
        <el-radio-group v-model="graphMode" @change="loadGraphFlow">
          <el-radio-button value="master">Master</el-radio-button>
          <el-radio-button value="personal">Personal</el-radio-button>
        </el-radio-group>
        <el-radio-group v-if="graphMode === 'personal'" v-model="renderMode">
          <el-radio-button value="2d">2D</el-radio-button>
          <el-radio-button value="3d">3D</el-radio-button>
        </el-radio-group>
        <template v-if="graphMode === 'master'">
          <el-input v-model.trim="subjectInput" placeholder="学科，例如 Java" @keydown.enter.prevent="loadGraphFlow" />
          <el-button type="primary" @click="loadGraphFlow">加载图谱</el-button>
        </template>
      </div>
    </section>

    <section class="knowledge-graph-layout">
      <div class="knowledge-graph-canvas-panel panel">
        <div
          class="knowledge-graph-canvas"
          :class="{ 'is-3d': graphMode === 'personal' && renderMode === '3d' }"
          :style="{ minHeight: `${canvasHeight}px` }"
        >
          <template v-if="graphMode === 'master' || renderMode === '2d'">
          <svg class="knowledge-graph-edges" :viewBox="`0 0 ${canvasWidth} ${canvasHeight}`" preserveAspectRatio="none">
            <line
              v-for="edge in drawableEdges"
              :key="edge.id"
              :class="{ related: edge.relationType === 'related' }"
              :x1="edge.x1"
              :y1="edge.y1"
              :x2="edge.x2"
              :y2="edge.y2"
            />
          </svg>

          <button
            v-for="node in positionedNodes"
            :key="node.id"
            class="graph-node"
            :class="[node.status, { focused: node.id === focusedNodeId }]"
            :style="{ left: `${node.x}%`, top: `${node.y}px` }"
            type="button"
            @click="focusedNodeId = node.id"
          >
            <strong>{{ node.name }}</strong>
            <small>{{ node.summary }}</small>
          </button>

          <el-empty
            v-if="!loading && graph.nodes.length === 0"
            :description="graphMode === 'master' ? '当前学科还没有知识点' : '还没有已发布的个人图谱'"
            :image-size="120"
          />
          </template>
          <PersonalGraph3DView
            v-else
            :nodes="graph.nodes"
            :edges="graph.edges"
            @node-click="focusedNodeId = $event"
          />
        </div>
      </div>

      <aside class="knowledge-graph-side panel">
        <div class="section-head compact">
          <div>
            <p class="eyebrow">Overview</p>
            <h3>{{ graph.title }}</h3>
          </div>
        </div>
        <div v-if="graphMode === 'master'" class="graph-legend">
          <span><i class="mastered"></i> 已掌握</span>
          <span><i class="learning"></i> 学习中</span>
          <span><i class="weak"></i> 薄弱</span>
          <span><i class="not-started"></i> 未开始</span>
        </div>
        <div v-else class="graph-legend personal-legend">
          <span><i class="personal"></i> 已确认节点</span>
          <span><i class="related"></i> 相关关系</span>
        </div>
        <div class="graph-stats">
          <div>
            <span>节点</span>
            <strong>{{ graph.nodes.length }}</strong>
          </div>
          <div>
            <span>关系</span>
            <strong>{{ graph.edges.length }}</strong>
          </div>
        </div>
        <div v-if="focusedNode" class="graph-focus-card">
          <span>当前高亮</span>
          <strong>{{ focusedNode.name }}</strong>
          <small>{{ focusedNode.summary }}</small>
          <p v-if="focusedNode.description">{{ focusedNode.description }}</p>
          <small v-if="focusedNode.evidence?.[0]">#{{ focusedNode.evidence[0].chunkIndex }} · {{ focusedNode.evidence[0].snippet }}</small>
          <el-button v-if="graphMode === 'master'" type="primary" plain @click="goLearningPath(focusedNode.id)">按这个知识点学习</el-button>
        </div>
        <div class="graph-edge-list">
          <p class="eyebrow">Relations</p>
          <article v-for="edge in graph.edges" :key="edge.id">
            <strong>{{ nodeName(edge.sourceId) }} → {{ nodeName(edge.targetId) }}</strong>
            <small>{{ edge.relationReason || relationLabel(edge.relationType) }}</small>
          </article>
          <div v-if="graph.edges.length === 0" class="empty-line">暂无关系。</div>
        </div>
      </aside>
    </section>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, reactive, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { getKnowledgeGraph, getPersonalGraph } from '../api'
import type { KnowledgeGraphStatus, PersonalGraphEvidence } from '../types/domain'
import PersonalGraph3DView from '../components/PersonalGraph3DView.vue'

type GraphMode = 'master' | 'personal'
type RenderMode = '2d' | '3d'
type DisplayStatus = KnowledgeGraphStatus | 'personal'

interface DisplayNode {
  id: number
  name: string
  summary: string
  status: DisplayStatus
  description?: string
  confidence?: number
  evidence?: PersonalGraphEvidence[]
}

interface DisplayEdge {
  id: string
  sourceId: number
  targetId: number
  relationType?: string
  relationReason?: string
}

interface PositionedNode extends DisplayNode {
  x: number
  y: number
}

interface DrawableEdge extends DisplayEdge {
  x1: number
  y1: number
  x2: number
  y2: number
}

const route = useRoute()
const router = useRouter()
const loading = ref(false)
const graphMode = ref<GraphMode>(route.query.mode === 'personal' ? 'personal' : 'master')
const renderMode = ref<RenderMode>('2d')
const subjectInput = ref(String(route.query.subject || 'Java'))
const focusedNodeId = ref<number | null>(graphMode.value === 'master' ? Number(route.query.focusKnowledgePointId) || null : null)
const graph = reactive<{ title: string; nodes: DisplayNode[]; edges: DisplayEdge[] }>({
  title: 'Java',
  nodes: [],
  edges: []
})

const canvasWidth = 1000
const rowHeight = 126
const nodeCenterOffsetY = 30
const currentSubject = computed(() => subjectInput.value.trim() || 'Java')
const nodeMap = computed(() => new Map(graph.nodes.map((node) => [node.id, node])))
const positionedNodes = computed<PositionedNode[]>(() => {
  if (graph.nodes.length === 0) return []
  const layerMap = buildLayerMap()
  const groups = new Map<number, DisplayNode[]>()
  graph.nodes.forEach((node) => {
    const layer = layerMap.get(node.id) || 0
    const group = groups.get(layer) || []
    group.push(node)
    groups.set(layer, group)
  })
  const positioned: PositionedNode[] = []
  Array.from(groups.entries())
    .sort(([left], [right]) => left - right)
    .forEach(([layer, nodes]) => {
      nodes.forEach((node, index) => {
        positioned.push({ ...node, x: ((index + 1) / (nodes.length + 1)) * 100, y: 52 + layer * rowHeight })
      })
    })
  return positioned
})
const positionedNodeMap = computed(() => new Map(positionedNodes.value.map((node) => [node.id, node])))
const maxLayer = computed(() => Math.max(0, ...positionedNodes.value.map((node) => Math.floor((node.y - 52) / rowHeight))))
const canvasHeight = computed(() => Math.max(440, 140 + (maxLayer.value + 1) * rowHeight))
const drawableEdges = computed<DrawableEdge[]>(() => graph.edges
  .map((edge) => {
    const source = positionedNodeMap.value.get(edge.sourceId)
    const target = positionedNodeMap.value.get(edge.targetId)
    if (!source || !target) return null
    return { ...edge, x1: source.x * 10, y1: source.y + nodeCenterOffsetY, x2: target.x * 10, y2: target.y + nodeCenterOffsetY }
  })
  .filter(Boolean) as DrawableEdge[])
const focusedNode = computed(() => focusedNodeId.value ? nodeMap.value.get(focusedNodeId.value) || null : null)

onMounted(loadGraphFlow)

watch(
  () => route.query.subject,
  async (subject) => {
    subjectInput.value = String(subject || 'Java')
    if (graphMode.value === 'master') await loadGraphFlow()
  }
)

watch(
  () => route.query.focusKnowledgePointId,
  (id) => {
    if (graphMode.value === 'master') focusedNodeId.value = Number(id) || null
  }
)

watch(graphMode, async (mode) => {
  if (mode === 'master') renderMode.value = '2d'
  focusedNodeId.value = mode === 'master' ? Number(route.query.focusKnowledgePointId) || null : null
  await router.replace({ query: { ...route.query, mode } })
})

async function loadGraphFlow() {
  loading.value = true
  try {
    if (graphMode.value === 'master') {
      const result = await getKnowledgeGraph(currentSubject.value)
      graph.title = result.subject || currentSubject.value
      graph.nodes = result.nodes.map((node) => ({
        id: node.id,
        name: node.name,
        summary: `${graphStatusLabel(node.graphStatus)} · ${node.masteryLevel}%`,
        status: node.graphStatus
      }))
      graph.edges = result.edges.map((edge) => ({
        id: `${edge.prerequisitePointId}-${edge.dependentPointId}-${edge.relationType || ''}`,
        sourceId: edge.prerequisitePointId,
        targetId: edge.dependentPointId,
        relationType: edge.relationType,
        relationReason: edge.relationReason
      }))
      return
    }
    const documentId = Number(route.query.documentId) || undefined
    const result = await getPersonalGraph(documentId)
    graph.title = '我的资料图谱'
    graph.nodes = result.nodes.map((node) => ({
      id: node.id,
      name: node.name,
      summary: `置信度 ${node.confidence}%`,
      status: 'personal',
      description: node.description,
      confidence: node.confidence,
      evidence: node.source
    }))
    graph.edges = result.edges.map((edge) => ({
      id: String(edge.id),
      sourceId: edge.sourceNodeId,
      targetId: edge.targetNodeId,
      relationType: edge.relationType,
      relationReason: edge.relationReason
    }))
  } catch (error) {
    graph.nodes = []
    graph.edges = []
    ElMessage.error(error instanceof Error ? error.message : '加载知识图谱失败')
  } finally {
    loading.value = false
  }
}

function buildLayerMap() {
  const incoming = new Map<number, number>()
  const outgoing = new Map<number, number[]>()
  graph.nodes.forEach((node) => {
    incoming.set(node.id, 0)
    outgoing.set(node.id, [])
  })
  graph.edges.filter((edge) => edge.relationType !== 'related').forEach((edge) => {
    if (!incoming.has(edge.sourceId) || !incoming.has(edge.targetId)) return
    incoming.set(edge.targetId, (incoming.get(edge.targetId) || 0) + 1)
    outgoing.get(edge.sourceId)?.push(edge.targetId)
  })
  const layerMap = new Map<number, number>()
  const queue = graph.nodes.filter((node) => (incoming.get(node.id) || 0) === 0).map((node) => node.id)
  graph.nodes.forEach((node) => layerMap.set(node.id, 0))
  while (queue.length > 0) {
    const currentId = queue.shift()!
    const currentLayer = layerMap.get(currentId) || 0
    for (const targetId of outgoing.get(currentId) || []) {
      layerMap.set(targetId, Math.max(layerMap.get(targetId) || 0, currentLayer + 1))
      incoming.set(targetId, (incoming.get(targetId) || 0) - 1)
      if ((incoming.get(targetId) || 0) <= 0) queue.push(targetId)
    }
  }
  return layerMap
}

function nodeName(nodeId: number) {
  return nodeMap.value.get(nodeId)?.name || `#${nodeId}`
}

function graphStatusLabel(status: KnowledgeGraphStatus) {
  if (status === 'mastered') return '已掌握'
  if (status === 'learning') return '学习中'
  if (status === 'weak') return '薄弱'
  return '未开始'
}

function relationLabel(type?: string) {
  if (type === 'prerequisite') return '前置知识'
  if (type === 'contains') return '包含关系'
  if (type === 'related') return '相关关系'
  return '前置依赖'
}

function goLearningPath(knowledgePointId: number) {
  router.push({ name: 'learn', query: { knowledgePointId: String(knowledgePointId) } })
}
</script>
