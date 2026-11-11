<script setup lang="ts">
/** 统一页面容器：提供纵向节奏与最小宽度约束，标题用 PageHeader，内容直接放入默认插槽。 */
</script>

<template>
  <section class="page-shell management-page"><slot /></section>
</template>

<style scoped>
.page-shell {
  min-width: 0;
}

.page-shell> :deep(* + *) {
  margin-top: 24px;
}
</style>