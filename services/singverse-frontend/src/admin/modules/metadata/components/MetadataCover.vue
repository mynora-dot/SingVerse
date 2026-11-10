<script setup lang="ts">
import { computed, ref, watch } from 'vue'
const props = withDefaults(defineProps<{ src?: string | null; alt: string; emptyText?: string; retryKey?: number; retryable?: boolean }>(), { emptyText: '平台未提供封面', retryKey: 0, retryable: false })
const emit = defineEmits<{ failed: []; loaded: [size: { width: number; height: number }] }>()
const failed = ref(false), loaded = ref(false), attempt = ref(0)
watch([() => props.src, () => props.retryKey], () => { failed.value = false; loaded.value = false; attempt.value = 0 })
const source = computed(() => {
  if (!props.src || !(props.retryKey || attempt.value)) return props.src
  return `${props.src}${props.src.includes('?') ? '&' : '?'}retry=${props.retryKey}-${attempt.value}`
})
function onLoad(event: Event) {
  loaded.value = true
  const img = event.target as HTMLImageElement
  emit('loaded', { width: img.naturalWidth, height: img.naturalHeight })
}
function retry() { attempt.value++; failed.value = false; loaded.value = false }
</script>
<template>
  <div class="metadata-cover" :class="{ 'cover-loading': src && !loaded && !failed, 'cover-failed': failed }" :aria-busy="!!src && !loaded && !failed">
    <img v-if="src && !failed" :src="source!" :alt="alt" loading="lazy" @load="onLoad" @error="failed = true; emit('failed')" />
    <span v-if="!src || !loaded || failed" class="cover-state" :role="failed ? 'status' : undefined"><span aria-hidden="true">♫</span><small>{{ !src ? emptyText : failed ? '封面加载失败' : '加载封面…' }}</small></span>
    <button v-if="failed && retryable" type="button" class="cover-retry" @click.stop="retry">重试封面</button>
  </div>
</template>
<style scoped>
.metadata-cover { position: relative; display: grid; place-items: center; width: 58px; height: 58px; flex-shrink: 0; overflow: hidden; border-radius: 8px; background: var(--sv-accent-soft); color: var(--sv-text-secondary); }
.metadata-cover img { display: block; width: 100%; height: 100%; object-fit: cover; }
.cover-loading img { visibility: hidden; }
.cover-state { position: absolute; inset: 0; display: flex; flex-direction: column; justify-content: center; align-items: center; gap: 3px; padding: 4px; text-align: center; }
.cover-state > span { font-size: 20px; line-height: 1; opacity: .6; }
.cover-state small { font-size: 9px; line-height: 1.4; }
.cover-retry { position: absolute; inset: auto 2px 3px; border: 0; border-radius: 3px; background: var(--sv-surface-2); color: var(--sv-accent-primary); cursor: pointer; font: inherit; font-size: 10px; padding: 3px; }
.metadata-cover:has(.cover-retry) .cover-state { padding-bottom: 24px; }
</style>
