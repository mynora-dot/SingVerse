<script setup lang="ts">
/** 大数字统计块：真实数值 + 标签 + 可选说明/跳转。未知数值传 '—'，不伪造。 */
withDefaults(defineProps<{ label: string; value: string | number; hint?: string; to?: string; tone?: 'default' | 'highlight' | 'warn' }>(), { tone: 'default' })
</script>

<template>
  <component :is="to ? 'RouterLink' : 'div'" :to="to" class="stat-block" :class="[tone, { 'is-link': !!to }]">
    <span class="stat-value">{{ value }}</span>
    <span class="stat-label">{{ label }}</span>
    <span v-if="hint" class="stat-hint">{{ hint }}</span>
  </component>
</template>

<style scoped>
.stat-block {
  display: flex;
  flex-direction: column;
  gap: 8px;
  padding: 20px 24px;
  min-width: 0;
  background: var(--sv-surface-2);
  border: 1px solid var(--sv-border-subtle);
  border-radius: var(--sv-radius-lg);
  color: inherit;
}

.stat-block.is-link {
  transition: border-color .16s var(--sv-ease-smooth), box-shadow .16s var(--sv-ease-smooth);
}

.stat-block.is-link:hover {
  border-color: var(--sv-border-default);
  box-shadow: var(--sv-shadow-sm);
}

.stat-value {
  font: 500 var(--sv-font-display)/1.05 var(--sv-font-editorial);
  font-variant-numeric: tabular-nums;
  letter-spacing: 0;
  color: var(--sv-text-primary);
}

.stat-block.highlight .stat-value { color: var(--sv-accent-highlight); }
.stat-block.warn .stat-value { color: var(--sv-semantic-warn); }

.stat-label {
  font-size: 13px;
  font-weight: 500;
  color: var(--sv-text-secondary);
}

.stat-hint {
  margin-top: 2px;
  font-size: 12px;
  line-height: 1.6;
  color: var(--sv-text-tertiary);
}
</style>