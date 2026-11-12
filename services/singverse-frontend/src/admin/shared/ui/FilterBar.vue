<script setup lang="ts">
/** 贴近内容的筛选条；保留可选吸附和独立动作槽位。 */
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
  gap: 8px 12px;
  padding: 0 0 12px;
  background: var(--sv-surface-0);
  border-bottom: 1px solid var(--sv-border-subtle);
}

.filter-bar.is-sticky {
  position: sticky;
  top: calc(var(--sv-topbar-height) + 8px);
  z-index: 10;
  padding-top: 12px;
}

.filter-controls {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 12px;
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
