<script setup lang="ts">
import { onUnmounted, ref, watch } from 'vue'
const props = defineProps<{ path?: string; token?: string }>()
const image = ref('')
let controller: AbortController | undefined
function clear() { controller?.abort(); if (image.value) URL.revokeObjectURL(image.value); image.value = '' }
watch(() => [props.path, props.token], async () => {
  clear()
  if (!props.path?.startsWith('/api/v1/tv/library/songs/') || !props.token) return
  controller = new AbortController(); const current = controller
  try { const response = await fetch(props.path, { headers: { Authorization: `Bearer ${props.token}` }, credentials: 'omit', redirect: 'error', signal: current.signal }); if (!response.ok) return; const blob = await response.blob(); if (!current.signal.aborted && blob.size <= 2_000_000 && ['image/jpeg', 'image/png'].includes(blob.type)) image.value = URL.createObjectURL(blob) } catch { /* Keep the designed placeholder when a cover is unavailable. */ }
}, { immediate: true })
onUnmounted(clear)
</script>
<template><img v-if="image" :src="image" class="record-art" alt="歌曲封面"><div v-else class="record-art" aria-hidden="true">◉</div></template>
