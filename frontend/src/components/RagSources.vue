<template>
  <details v-if="sources?.length" class="message-sources" :open="expanded">
    <summary>教材依据（{{ sources.length }}）</summary>
    <p class="source-hint">以下为回答时检索到的教材片段，按当时的引用顺序展示。</p>
    <article v-for="(source, index) in sources" :key="`${source.documentId}-${source.chunkIndex}-${index}`">
      <strong>片段 {{ index + 1 }}</strong>
      <template v-if="source.available !== false">
        <small>{{ source.fileName }} · 教材第 {{ source.chunkIndex + 1 }} 段</small>
        <p>{{ source.snippet }}</p>
      </template>
      <p v-else role="status" class="source-unavailable">教材已删除或暂不可访问，无法查看该引用原文。可重新选择教材后提问。</p>
    </article>
  </details>
</template>

<script setup lang="ts">
import type { RagSource } from '../types/domain'
defineProps<{ sources?: RagSource[]; expanded?: boolean }>()
</script>

<style scoped>
.message-sources { margin-top: 10px; font-size: 13px; overflow-wrap: anywhere; }
summary { cursor: pointer; color: #0f766e; }
article { padding: 10px 0; }
p { margin: 6px 0 0; line-height: 1.6; white-space: pre-wrap; }
small { display: block; margin-top: 4px; }
.source-hint { color: #64748b; }
.source-unavailable { color: #92400e; }
</style>
