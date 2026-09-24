<template>
  <details v-if="sources?.length" class="message-sources">
    <summary>教材依据（{{ sources.length }}）</summary>
    <article v-for="source in sources" :key="`${source.documentId}-${source.chunkIndex}`">
      <strong>{{ source.fileName }} · 片段 {{ source.chunkIndex }}</strong>
      <p>{{ source.snippet }}</p>
    </article>
  </details>
</template>

<script setup lang="ts">
import type { RagSource } from '../types/domain'
defineProps<{ sources?: RagSource[] }>()
</script>

<style scoped>
.message-sources { margin-top: 10px; font-size: 13px; overflow-wrap: anywhere; }
summary { cursor: pointer; color: #0f766e; }
article { padding: 10px 0; }
p { margin: 6px 0 0; line-height: 1.6; }
</style>
