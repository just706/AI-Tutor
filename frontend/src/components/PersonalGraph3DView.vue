<template>
  <div ref="host" class="personal-graph-3d" aria-label="Personal Graph 3D view">
    <el-button class="personal-graph-3d-reset" size="small" @click="resetView">Reset view</el-button>
    <el-empty v-if="nodes.length === 0" description="No published personal graph" :image-size="110" />
    <div v-else class="personal-graph-3d-canvas" />
  </div>
</template>

<script setup lang="ts">
import { nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import ForceGraph3D from '3d-force-graph'
import type { ForceGraph3DInstance, LinkObject, NodeObject } from '3d-force-graph'
import { graphLinkLabel, graphNodeLabel } from '../utils/graphLabels'

interface GraphNode extends NodeObject {
  id: number
  name: string
  description?: string
  confidence?: number
}

interface GraphLink extends LinkObject<GraphNode> {
  relationType?: string
  relationReason?: string
}

const props = defineProps<{
  nodes: GraphNode[]
  edges: Array<{
    sourceId: number
    targetId: number
    relationType?: string
    relationReason?: string
  }>
}>()

const emit = defineEmits<{
  nodeClick: [nodeId: number]
}>()

const host = ref<HTMLElement | null>(null)
const focusedNodeId = ref<number | null>(null)
let graph: ForceGraph3DInstance | null = null
let resizeObserver: ResizeObserver | null = null

onMounted(() => {
  void renderGraph()
})

watch(() => [props.nodes, props.edges], () => {
  focusedNodeId.value = null
  void renderGraph()
}, { deep: true })

onBeforeUnmount(() => {
  resizeObserver?.disconnect()
  resizeObserver = null
  graph?._destructor()
  graph = null
})

async function renderGraph() {
  await nextTick()
  if (!host.value || props.nodes.length === 0) return
  const canvas = host.value.querySelector('.personal-graph-3d-canvas') as HTMLElement | null
  if (!canvas) return

  resizeObserver?.disconnect()
  resizeObserver = null
  graph?._destructor()
  graph = new ForceGraph3D(canvas)
    .backgroundColor('#ffffff')
    .showNavInfo(false)
    .nodeLabel((node) => graphNodeLabel(node as GraphNode))
    .nodeColor((node) => nodeColor(node))
    .linkLabel((link) => graphLinkLabel(link as GraphLink))
    .linkColor((link) => linkColor(link as LinkObject<GraphNode>))
    .linkOpacity(0.6)
    .linkDirectionalArrowLength(4)
    .linkDirectionalArrowRelPos(1)
    .onNodeClick((node) => {
      focusedNodeId.value = Number(node.id)
      emit('nodeClick', Number(node.id))
      graph?.nodeColor((candidate) => nodeColor(candidate))
      graph?.linkColor((candidate) => linkColor(candidate as LinkObject<GraphNode>))
    })
    .graphData({
      nodes: props.nodes.map(node => ({ ...node })),
      links: props.edges.map(edge => ({
        source: edge.sourceId,
        target: edge.targetId,
        relationType: edge.relationType,
        relationReason: edge.relationReason
      }))
    })

  syncGraphSize(canvas)
  resizeObserver = new ResizeObserver(() => {
    syncGraphSize(canvas)
    centerGraph(220)
  })
  resizeObserver.observe(canvas)
  window.setTimeout(() => centerGraph(500), 250)
  window.setTimeout(() => centerGraph(500), 1100)
}

function syncGraphSize(canvas: HTMLElement) {
  const width = Math.max(320, canvas.clientWidth)
  const height = Math.max(360, canvas.clientHeight)
  graph?.width(width).height(height)
}

function centerGraph(duration = 500) {
  graph?.zoomToFit(duration, 80)
}

function nodeColor(node: NodeObject) {
  const id = Number(node.id)
  return id === focusedNodeId.value ? '#f97316' : id % 2 === 0 ? '#0891b2' : '#b45309'
}

function linkColor(link: LinkObject<GraphNode>) {
  const source = typeof link.source === 'object' ? link.source.id : link.source
  const target = typeof link.target === 'object' ? link.target.id : link.target
  if (focusedNodeId.value !== null && Number(source) !== focusedNodeId.value && Number(target) !== focusedNodeId.value) {
    return '#94a3b8'
  }
  return (link as GraphLink).relationType === 'related' ? '#64748b' : '#0f766e'
}

function resetView() {
  focusedNodeId.value = null
  graph?.nodeColor((candidate) => nodeColor(candidate))
  graph?.linkColor((candidate) => linkColor(candidate as LinkObject<GraphNode>))
  centerGraph(500)
}
</script>
