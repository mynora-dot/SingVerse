<script setup lang="ts">
/** 状态徽章：圆点 + 文案，tone 决定语义色。未知状态用 neutral，不伪造成功。 */
withDefaults(defineProps<{ tone?: 'ok' | 'warn' | 'danger' | 'busy' | 'info' | 'neutral'; label?: string }>(), { tone: 'neutral' })
</script>

<template>
  <span class="status-badge" :class="tone"><span class="sv-status-dot" :class="tone" aria-hidden="true" /><span class="status-text"><slot>{{ label }}</slot></span></span>
</template>

<style scoped>
.status-badge {
  display: inline-flex;
  align-items: center;
  gap: 7px;
  font-size: 12px;
  font-weight: 550;
  color: var(--sv-text-secondary);
  white-space: nowrap;
}

.status-badge.ok { color: var(--sv-semantic-ok); }
.status-badge.warn { color: var(--sv-semantic-warn); }
.status-badge.danger { color: var(--sv-semantic-danger); }
.status-badge.busy { color: var(--sv-accent-primary); }
.status-badge.info { color: var(--sv-semantic-info); }

.status-text {
  overflow: hidden;
  text-overflow: ellipsis;
}
</style>