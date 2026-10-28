<script setup lang="ts">
import { ElEmpty } from 'element-plus'
</script>

<template>
  <section class="not-found">
    <ElEmpty description="页面不存在"
      ><p class="page-description">这个地址在管理中心里没有对应的页面，可能是链接过期或功能尚未开放。</p
      ><RouterLink to="/overview" class="text-link">返回概览</RouterLink></ElEmpty
    >
  </section>
</template>

<style scoped>
.not-found {
  padding: 48px 0;
}
.not-found :deep(.el-empty__description) {
  margin-bottom: 12px;
}
.not-found .page-description {
  margin: 0 0 20px;
  text-align: center;
}
</style>
