<script setup lang="ts">
import { useRoute } from 'vue-router'

const route = useRoute()
</script>

<template>
  <nav class="source-workspace-nav" aria-label="歌曲来源设置">
    <RouterLink to="/library/media" :aria-current="route.path === '/library/media' ? 'page' : undefined">来源与扫描</RouterLink>
    <RouterLink to="/library/media/recognition" :aria-current="route.path.startsWith('/library/media/recognition') ? 'page' : undefined">文件名识别</RouterLink>
  </nav>
</template>

<style scoped>
.source-workspace-nav { display: flex; flex-wrap: wrap; gap: 8px; margin: 0 0 28px; padding-bottom: 16px; border-bottom: 1px solid var(--sv-border-subtle); }
.source-workspace-nav a { display: inline-flex; align-items: center; min-height: 40px; padding: 8px 16px; border-radius: var(--sv-radius-sm); color: var(--sv-text-secondary); font-size: 13px; }
.source-workspace-nav a[aria-current='page'] { background: var(--el-color-primary-light-9); color: var(--el-color-primary); font-weight: 600; }
.source-workspace-nav a:hover { background: var(--sv-surface-3); }
</style>
