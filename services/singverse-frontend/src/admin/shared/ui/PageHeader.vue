<script setup lang="ts">
/** 管理端统一页面标题：标题与说明在左，页面级操作在右，窄屏自动换行。 */
defineProps<{ title: string; description?: string }>()
</script>

<template>
  <header class="page-heading sv-page-header">
    <div class="sv-page-header-copy">
      <h1>{{ title }}</h1>
      <p v-if="description || $slots.description" class="page-description"><slot name="description">{{ description }}</slot></p>
    </div>
    <div v-if="$slots.actions" class="sv-page-header-actions"><slot name="actions" /></div>
  </header>
</template>
