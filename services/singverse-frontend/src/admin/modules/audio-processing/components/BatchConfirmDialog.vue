<script setup lang="ts">
import { computed } from 'vue'
import { ElButton, ElDialog } from 'element-plus'
import type { Batch } from '../api'
import { backendName, profileName } from '../presentation'

const props = defineProps<{ batch?: Batch; ready: boolean; busy: boolean }>()
const emit = defineEmits<{ start: []; close: []; cancel: [] }>()
const preparing = computed(() => props.batch?.state === 'PREPARING')
const pending = computed(() => props.batch?.counts.PENDING || 0)
const skipped = computed(() => {
  const c = props.batch?.categories || {}
  return [['已有伴奏', c.READY], ['正在生成', c.PROCESSING], ['多音轨或无音轨，不需要生成', c.INAPPLICABLE]].filter(([, count]) => count) as [string, number][]
})
</script>

<template>
  <ElDialog :model-value="!!batch" title="确认生成伴奏" width="min(520px, 94vw)" :close-on-click-modal="!busy" :close-on-press-escape="!busy" :show-close="!busy" @close="emit('close')">
    <template v-if="batch">
      <p v-if="preparing" class="lead" role="status">正在准备所选歌曲：{{ batch.prepared ?? 0 }} / {{ batch.selectionTotal ?? batch.total }}。准备完成后请确认开始。</p>
      <p v-else-if="batch.state === 'FAILED'" class="lead" role="alert">{{ batch.error || '歌曲准备失败，请重新选择歌曲后重试。' }}</p>
      <p v-else-if="batch.state === 'CANCELLED'" class="lead">已取消准备。</p>
      <p v-else class="lead">将为 <strong>{{ pending }}</strong> 首歌曲生成独立伴奏。</p>
      <p class="muted">方案：{{ profileName(batch.separationProfile) }} · {{ batch.outputFormat === 'flac' ? 'FLAC' : 'AAC 192k' }}</p>
      <p v-if="batch.separationProfile === 'DSP_FAST'" class="muted">中心抵消也可能削弱鼓、贝斯和主奏，并留下混响。建议先对代表歌曲进行短片试听。</p>
      <ul v-if="batch.state === 'PREVIEW'" class="breakdown">
        <li v-if="batch.categories.ELIGIBLE">直接生成 {{ batch.categories.ELIGIBLE }} 首（单音轨）</li>
        <li v-if="batch.categories.UNPROBED">先读取音轨 {{ batch.categories.UNPROBED }} 首，确认只有一条音轨后才生成</li>
        <li v-for="[reason, count] in skipped" :key="reason" class="muted">跳过 {{ count }} 首：{{ reason }}</li>
      </ul>
      <p class="muted">使用设备：{{ batch.backend ? backendName(batch.backend) : '自动选择已通过试跑的设备' }}。生成在后台进行，可以随时在“生成任务”中查看或停止；已生成的伴奏会保留。</p>
      <p v-if="batch.state === 'PREVIEW' && !pending" class="muted">所选歌曲都不需要生成。</p>
    </template>
    <template #footer>
      <ElButton :disabled="busy" @click="emit('close')">返回</ElButton>
      <ElButton v-if="preparing" :disabled="busy" @click="emit('cancel')">取消准备</ElButton>
      <ElButton type="primary" :loading="busy" :disabled="busy || batch?.state !== 'PREVIEW' || !ready || !pending" @click="emit('start')">开始生成</ElButton>
    </template>
  </ElDialog>
</template>

<style scoped>
.lead{margin:0 0 12px;font-size:15px;color:var(--sv-text-primary)}.breakdown{margin:0 0 12px;padding-left:20px;display:grid;gap:4px;font-size:var(--sv-font-body)}.muted{margin:0;font-size:var(--sv-font-meta);line-height:1.6;color:var(--sv-text-secondary)}
</style>
