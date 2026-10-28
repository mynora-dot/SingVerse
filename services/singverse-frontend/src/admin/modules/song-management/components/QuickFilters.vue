<script setup lang="ts">
const props = defineProps<{ status: string; unclassified: boolean; tagIds: string[] }>()
const emit = defineEmits<{ 'update:status': [value: string]; 'update:unclassified': [value: boolean]; 'update:tagIds': [value: string[]] }>()
function clear() {
  emit('update:tagIds', [])
  emit('update:unclassified', false)
  emit('update:status', 'ALL')
}
</script>

<template>
  <div class="quick-filters" role="group" aria-label="歌曲快速筛选">
    <button class="quick-filter-chip" :aria-pressed="status === 'REVIEW'" @click="emit('update:status', status === 'REVIEW' ? 'ALL' : 'REVIEW')">待复核</button>
    <button class="quick-filter-chip" :aria-pressed="unclassified" @click="emit('update:unclassified', !unclassified)">未分类</button>
    <button v-if="props.status !== 'ALL' || props.unclassified || props.tagIds.length" class="quick-filter-clear" @click="clear">清除筛选</button>
  </div>
</template>

<style scoped>
.quick-filters { display: flex; flex-wrap: wrap; gap: 8px; }
button { padding: 6px 12px; border: 1px solid var(--el-border-color); border-radius: var(--el-border-radius-base); background: var(--el-bg-color); color: var(--el-text-color-primary); cursor: pointer; }
button[aria-pressed='true'] { color: var(--el-color-primary); border-color: var(--el-color-primary); }
</style>
