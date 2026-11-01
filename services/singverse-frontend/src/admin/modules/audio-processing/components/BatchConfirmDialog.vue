<script setup lang="ts">
import { computed } from 'vue'
import { ElButton, ElDialog } from 'element-plus'
import type { Batch } from '../api'
import { backendName } from '../presentation'

const props = defineProps<{ batch?: Batch; ready: boolean; busy: boolean }>()
const emit = defineEmits<{ start: []; close: [] }>()
const pending = computed(() => props.batch?.counts.PENDING || 0)
const skipped = computed(() => {
  const c = props.batch?.categories || {}
  return [['已有伴奏', c.READY], ['正在生成', c.PROCESSING], ['多音轨或无音轨，不需要生成', c.INAPPLICABLE]].filter(([, count]) => count) as [string, number][]
})
</script>

<template>
  <ElDialog :model-value="!!batch" title="确认生成伴奏" width="min(520px, 94vw)" @close="emit('close')">
    <template v-if="batch">
      <p class="lead">将为 <strong>{{ pending }}</strong> 首歌曲生成独立伴奏。</p>
      <ul class="breakdown">
        <li v-if="batch.categories.ELIGIBLE">直接生成 {{ batch.categories.ELIGIBLE }} 首（单音轨）</li>
        <li v-if="batch.categories.UNPROBED">先读取音轨 {{ batch.categories.UNPROBED }} 首，确认只有一条音轨后才生成</li>
        <li v-for="[reason, count] in skipped" :key="reason" class="muted">跳过 {{ count }} 首：{{ reason }}</li>
      </ul>
      <p class="muted">使用设备：{{ batch.backend ? backendName(batch.backend) : '自动选择已通过试跑的设备' }}。生成在后台进行，可以随时在“生成任务”中查看或停止；已生成的伴奏会保留。</p>
      <p v-if="!pending" class="muted">所选歌曲都不需要生成。</p>
    </template>
    <template #footer>
      <ElButton :disabled="busy" @click="emit('close')">返回</ElButton>
      <ElButton type="primary" :loading="busy" :disabled="busy || !ready || !pending" @click="emit('start')">开始生成</ElButton>
    </template>
  </ElDialog>
</template>

<style scoped>
.lead{margin:0 0 10px;font-size:15px;color:var(--sv-text-primary)}.breakdown{margin:0 0 12px;padding-left:20px;display:grid;gap:4px;font-size:var(--sv-font-body)}.muted{margin:0;font-size:var(--sv-font-meta);line-height:1.6;color:var(--sv-text-secondary)}
</style>
