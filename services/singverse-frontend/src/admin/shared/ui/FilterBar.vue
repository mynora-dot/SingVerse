<script setup lang="ts">
/** 筛选条：吸附在顶栏下方，承载搜索/筛选控件，右侧可放视图切换等动作。 */
withDefaults(defineProps<{ sticky?: boolean }>(), { sticky: true })
</script>

<template>
  <div class="filter-bar" :class="{ 'is-sticky': sticky }">
    <div class="filter-controls"><slot /></div>
    <div v-if="$slots.actions" class="filter-actions"><slot name="actions" /></div>
  </div>
</template>

<style scoped>
.filter-bar {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 12px;
  padding: 14px 18px;
  background: var(--sv-surface-1);
  border: 1px solid var(--sv-border-subtle);
  border-radius: var(--sv-radius-lg);
}

.filter-bar.is-sticky {
  position: sticky;
  top: calc(var(--sv-topbar-height) + 8px);
  z-index: 10;
  box-shadow: var(--sv-shadow-sm);
}

.filter-controls {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 10px;
  flex: 1 1 320px;
  min-width: 0;
}

.filter-actions {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 8px;
  margin-left: auto;
}
</style>