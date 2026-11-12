<script setup lang="ts">
import type { Component } from 'vue'
import { ElIcon } from 'element-plus'
/** 空态引导：唱片纹插画 + 标题 + 说明 + 下一步动作插槽。空曲库/无来源/无节点共用。 */
defineProps<{ title: string; description?: string; icon?: Component }>()
</script>

<template>
  <div class="empty-state">
    <div class="empty-disc" aria-hidden="true"><span class="empty-disc-label"><ElIcon v-if="icon" :size="26"><component :is="icon" /></ElIcon></span></div>
    <h3 class="empty-title">{{ title }}</h3>
    <p v-if="description || $slots.description" class="empty-description"><slot name="description">{{ description }}</slot></p>
    <div v-if="$slots.action" class="empty-action"><slot name="action" /></div>
  </div>
</template>

<style scoped>
.empty-state {
  display: flex;
  flex-direction: column;
  align-items: center;
  text-align: center;
  padding: 48px 24px;
  gap: 4px;
}

.empty-disc {
  display: grid;
  place-items: center;
  width: 96px;
  height: 96px;
  margin-bottom: 16px;
  border-radius: 50%;
  background: var(--sv-record-groove), var(--sv-surface-3);
  border: 1px solid var(--sv-border-subtle);
}

.empty-disc-label {
  display: grid;
  place-items: center;
  width: 40px;
  height: 40px;
  border-radius: 50%;
  background: var(--sv-surface-2);
  border: 1px solid var(--sv-border-default);
  color: var(--sv-accent-primary);
}

.empty-title {
  font: 600 17px/1.5 var(--sv-font-sans);
  color: var(--sv-text-primary);
}

.empty-description {
  margin-top: 8px;
  max-width: 460px;
  font-size: 13px;
  line-height: 1.8;
  color: var(--sv-text-secondary);
}

.empty-action {
  margin-top: 20px;
  display: flex;
  flex-wrap: wrap;
  gap: 12px;
  justify-content: center;
}
</style>