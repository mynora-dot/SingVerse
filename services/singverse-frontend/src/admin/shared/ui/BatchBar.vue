<script setup lang="ts">
/** 批量操作条：吸附在视口底部，明确表达选择数量与范围，动作由父级提供。 */
defineProps<{ count: number; scope?: string }>()
const emit = defineEmits<{ clear: [] }>()
</script>

<template>
  <div v-if="count > 0" class="batch-bar" role="region" aria-label="批量操作">
    <div class="batch-summary">
      <strong class="batch-count">已选 {{ count }} 首</strong>
      <span v-if="scope" class="batch-scope">{{ scope }}</span>
      <button type="button" class="batch-clear" @click="emit('clear')">取消选择</button>
    </div>
    <div class="batch-actions"><slot /></div>
  </div>
</template>

<style scoped>
.batch-bar {
  position: sticky;
  bottom: 16px;
  z-index: 15;
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 14px;
  padding: 14px 20px;
  background: var(--sv-media-stage);
  color: var(--sv-text-inverse);
  border-radius: var(--sv-radius-lg);
  box-shadow: var(--sv-shadow-lg);
}

.batch-summary {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 10px;
  min-width: 0;
}

.batch-count {
  font-size: 14px;
  font-weight: 650;
  font-variant-numeric: tabular-nums;
}

.batch-scope {
  font-size: 12px;
  color: color-mix(in srgb, var(--sv-text-inverse) 72%, transparent);
}

.batch-clear {
  border: 0;
  background: none;
  padding: 4px 6px;
  font-size: 12px;
  color: color-mix(in srgb, var(--sv-text-inverse) 72%, transparent);
  text-decoration: underline;
  cursor: pointer;
}

.batch-clear:hover { color: var(--sv-text-inverse); }

.batch-actions {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 8px;
  margin-left: auto;
}
</style>