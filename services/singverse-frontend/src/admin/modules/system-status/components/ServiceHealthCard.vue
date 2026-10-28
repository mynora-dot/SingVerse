<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { ElButton, ElCard, ElIcon, ElTag } from 'element-plus'
import { Connection, RefreshRight } from '@element-plus/icons-vue'
import { getServiceHealth } from '../api/health'
import { RequestError } from '../../../shared/http/client'

const state = ref<'checking' | 'up' | 'degraded' | 'unavailable'>('checking')
const message = ref('正在向当前 NAS 查询服务状态…')
const checkedAt = ref<Date>()
const label = computed(
  () =>
    ({ checking: '检查中', up: '运行正常', degraded: '状态异常', unavailable: '连接失败' })[
      state.value
    ],
)
const tagType = computed(
  () =>
    ({ checking: 'info', up: 'success', degraded: 'warning', unavailable: 'danger' })[
      state.value
    ] as 'info' | 'success' | 'warning' | 'danger',
)
const checkedTime = computed(
  () => checkedAt.value?.toLocaleTimeString('zh-CN', { hour12: false }) || '尚未完成',
)

async function refresh() {
  if (state.value === 'checking' && checkedAt.value) return
  state.value = 'checking'
  message.value = '正在向当前 NAS 查询服务状态…'
  try {
    const health = await getServiceHealth()
    state.value = health.status === 'UP' ? 'up' : 'degraded'
    message.value =
      health.status === 'UP'
        ? '主服务已响应。可以正常访问当前 NAS。'
        : `主服务报告 ${health.status}，请检查服务运行情况。`
  } catch (error) {
    state.value = 'unavailable'
    message.value = error instanceof RequestError ? error.message : '检查失败，请稍后重试'
  } finally {
    checkedAt.value = new Date()
  }
}

onMounted(refresh)
</script>

<template>
  <ElCard class="health-card" shadow="never">
    <div class="section-heading">
      <div class="section-title">
        <ElIcon><Connection /></ElIcon>
        <h2>服务连接</h2>
      </div>
      <ElButton :icon="RefreshRight" :loading="state === 'checking'" @click="refresh"
        >重新检查</ElButton
      >
    </div>
    <div class="health-content" role="status" aria-live="polite" :aria-busy="state === 'checking'">
      <span class="status-orbit" :class="state"><span /></span>
      <div class="health-copy">
        <div class="health-title">
          <h3>{{ label }}</h3>
          <ElTag :type="tagType" size="small" effect="light">主服务</ElTag>
        </div>
        <p>{{ message }}</p>
      </div>
    </div>
    <div class="health-footer">
      <span>最近检查 {{ checkedTime }}</span
      ><span>仅反映服务健康，不代表播放或转码已就绪</span>
    </div>
  </ElCard>
</template>
