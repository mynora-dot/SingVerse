<script setup lang="ts">
import { ref, watch } from 'vue'
import { ElAlert, ElButton, ElDrawer, ElEmpty, ElPagination, ElProgress, ElTag } from 'element-plus'
import { authRequest } from '../../../core/auth/api'
import { confirmDanger } from '../../../shared/ui/confirm'
import { base, type Backend, type Batch, type BatchItem } from '../api'
import { backendName, batchProgress, batchStates, batchTones, failure, itemStates, itemTones, time } from '../presentation'

const props = defineProps<{ batches: Batch[]; total: number; page: number; focus: string; backends: Backend[] }>()
const emit = defineEmits<{ preview: [batch: Batch]; page: [page: number]; changed: []; songs: [] }>()
const itemSize = 50
const current = ref<Batch>(), itemPage = ref(0), busy = ref(false), error = ref('')
function headline(batch: Batch) { return `${batch.total} 首歌曲` }
function summary(batch: Batch) {
  const c = batch.counts, parts = [`已生成 ${c.SUCCEEDED || 0}`]
  if (c.SKIPPED) parts.push(`跳过 ${c.SKIPPED}`)
  if (c.FAILED) parts.push(`失败 ${c.FAILED}`)
  if (c.CANCELLED) parts.push(`取消 ${c.CANCELLED}`)
  const { pending } = batchProgress(batch)
  if (pending) parts.push(`剩余 ${pending}`)
  return parts.join(' · ')
}
function itemState(item: BatchItem) { return item.state === 'PROCESSING' && item.job_state === 'QUEUED' ? '排队中' : itemStates[item.state] || item.state }
async function run(task: () => Promise<unknown>) {
  busy.value = true; error.value = ''
  try { await task() } catch (failed) { error.value = failure(failed) } finally { busy.value = false }
}
async function loadCurrent() {
  if (!current.value) return
  const id = current.value.id, page = itemPage.value
  const value = await authRequest<Batch>(`${base}/batches/${encodeURIComponent(id)}?page=${page}`)
  if (current.value?.id === id && itemPage.value === page) current.value = value
}
function open(batch: Batch) { current.value = batch; itemPage.value = 0; void run(loadCurrent) }
function itemsPage(next: number) { itemPage.value = next; void run(loadCurrent) }
async function cancelBatch(batch: Batch) {
  const queued = batch.state === 'QUEUED'
  if (queued && !await confirmDanger('停止这次生成？', '还没开始的歌曲不再处理，正在生成的会被中止；已经生成好的伴奏会保留。', '停止生成', '继续生成')) return
  await run(async () => { await authRequest(`${base}/batches/${encodeURIComponent(batch.id)}/cancel`, 'POST'); emit('changed'); await loadCurrent() })
}
async function cancelItem(item: BatchItem) {
  if (!current.value) return
  const id = current.value.id
  if (item.state === 'PROCESSING' && !await confirmDanger('停止生成这首歌？', `“${item.title}”正在生成的伴奏会被中止，之后可以重新生成。`, '停止这首', '继续')) return
  await run(async () => { await authRequest(`${base}/batches/${encodeURIComponent(id)}/items/${encodeURIComponent(item.song_id)}/cancel`, 'POST'); emit('changed'); await loadCurrent() })
}
function retry(batch: Batch, backend = '') {
  return run(async () => emit('preview', await authRequest<Batch>(`${base}/batches/${encodeURIComponent(batch.id)}/retry`, 'POST', { backend })))
}
watch(() => props.focus, id => { if (id) open(props.batches.find(item => item.id === id) ?? { id, state: 'QUEUED', backend: '', total: 0, counts: {}, categories: {} }) }, { immediate: true })
defineExpose({ refresh: loadCurrent })
</script>

<template>
  <div class="batches-tab">
    <ElAlert v-if="error" :title="error" type="error" show-icon :closable="false" />
    <ElEmpty v-if="!batches.length" description="还没有生成过伴奏"><p class="muted">在“歌曲”中勾选要处理的歌曲，再点“为所选歌曲生成伴奏”。</p><ElButton type="primary" @click="emit('songs')">去选择歌曲</ElButton></ElEmpty>
    <ul v-else class="batch-list" aria-label="生成任务">
      <li v-for="batch in batches" :key="batch.id" class="batch-row">
        <div class="batch-main">
          <div class="batch-title"><ElTag :type="batchTones[batch.state] || 'info'" effect="light" round size="small">{{ batchStates[batch.state] || batch.state }}</ElTag><h3>{{ headline(batch) }}</h3><span class="muted">{{ time(batch.createdAt) }}<template v-if="batch.backend"> · {{ backendName(batch.backend) }}</template></span></div>
          <ElProgress v-if="batch.state !== 'PREVIEW' && batch.total" :percentage="batchProgress(batch).percent" :stroke-width="6" :status="batch.state === 'COMPLETED' && !batch.counts.FAILED ? 'success' : undefined" :aria-label="`${headline(batch)} 处理进度`" />
          <p class="muted">{{ batch.state === 'PREVIEW' ? '已选好歌曲，尚未开始。确认后才会处理。' : summary(batch) }}</p>
        </div>
        <div class="batch-actions">
          <ElButton v-if="batch.state === 'PREVIEW'" type="primary" size="small" :disabled="busy" @click="emit('preview', batch)">确认开始</ElButton>
          <ElButton v-if="batch.counts.FAILED" size="small" :disabled="busy" @click="retry(batch)">重试失败的歌曲</ElButton>
          <ElButton size="small" @click="open(batch)">查看详情</ElButton>
          <ElButton v-if="batch.state === 'QUEUED'" size="small" type="danger" plain :disabled="busy" @click="cancelBatch(batch)">停止</ElButton>
          <ElButton v-if="batch.state === 'PREVIEW'" size="small" link :disabled="busy" @click="cancelBatch(batch)">放弃</ElButton>
        </div>
      </li>
    </ul>
    <div v-if="total > 20" class="pagination"><ElPagination :current-page="page + 1" :page-size="20" :total="total" layout="prev, pager, next" prev-text="上一页" next-text="下一页" @current-change="emit('page', $event - 1)" /></div>

    <ElDrawer :model-value="!!current" title="任务详情" size="min(620px, 100vw)" :append-to-body="false" @close="current = undefined">
      <template v-if="current">
        <div class="detail-head">
          <ElTag :type="batchTones[current.state] || 'info'" effect="light" round>{{ batchStates[current.state] || current.state }}</ElTag>
          <span>{{ headline(current) }}</span><span class="muted">{{ time(current.createdAt) }} · {{ current.backend ? backendName(current.backend) : '自动选择设备' }}</span>
        </div>
        <ElProgress v-if="current.total && current.state !== 'PREVIEW'" :percentage="batchProgress(current).percent" :stroke-width="8" aria-label="任务处理进度" />
        <p class="muted">{{ summary(current) }}</p>
        <ul class="item-list" aria-label="任务中的歌曲">
          <li v-for="item in current.items" :key="item.song_id" class="item-row">
            <div class="item-main"><strong>{{ item.title }}</strong><p v-if="item.error" :class="item.state === 'FAILED' ? 'inline-error' : 'muted'">{{ item.error }}</p></div>
            <span v-if="item.execution?.backend" class="muted">{{ backendName(item.execution.backend) }}</span>
            <ElTag :type="itemTones[item.state] || 'info'" effect="light" size="small">{{ itemState(item) }}</ElTag>
            <ElButton v-if="['PENDING', 'PROBING', 'PROCESSING'].includes(item.state)" link :disabled="busy" :aria-label="'停止' + item.title" @click="cancelItem(item)">停止</ElButton>
          </li>
        </ul>
        <div v-if="current.total > itemSize" class="pagination"><ElPagination :current-page="itemPage + 1" :page-size="itemSize" :total="current.total" layout="prev, pager, next" prev-text="上一页" next-text="下一页" @current-change="itemsPage($event - 1)" /></div>
      </template>
    </ElDrawer>
  </div>
</template>

<style scoped>
.batches-tab{display:grid;gap:14px}.batch-list,.item-list{margin:0;padding:0;list-style:none}.batch-list{border:1px solid var(--sv-border-subtle);border-radius:var(--sv-radius-lg);background:var(--sv-surface-2);overflow:hidden}.batch-row{display:flex;gap:16px;align-items:center;justify-content:space-between;padding:14px 18px;border-bottom:1px solid var(--sv-border-subtle)}.batch-row:last-child{border-bottom:0}.batch-main{flex:1;min-width:0;display:grid;gap:6px}.batch-title{display:flex;flex-wrap:wrap;gap:6px 10px;align-items:center}.batch-title h3{margin:0;font-size:var(--sv-font-body);font-weight:600}.batch-actions{display:flex;flex-wrap:wrap;gap:6px;justify-content:flex-end;max-width:340px}.batch-actions :deep(.el-button){margin:0}
.detail-head{display:flex;flex-wrap:wrap;gap:8px 12px;align-items:center;margin-bottom:12px;font-weight:600}.item-list{margin-top:14px;border-top:1px solid var(--sv-border-subtle)}.item-row{display:flex;gap:10px;align-items:center;padding:10px 0;border-bottom:1px solid var(--sv-border-subtle)}.item-main{flex:1;min-width:0}.item-main strong{display:block;font-size:var(--sv-font-body);font-weight:500;overflow-wrap:anywhere}.item-main p{margin:2px 0 0}
.muted{margin:0;font-size:var(--sv-font-meta);color:var(--sv-text-secondary)}.inline-error{margin:0;font-size:var(--sv-font-meta);color:var(--sv-semantic-danger);overflow-wrap:anywhere}.pagination{display:flex;justify-content:center;margin-top:12px}
@media(max-width:640px){.batch-row{flex-direction:column;align-items:stretch;padding:14px}.batch-actions{justify-content:flex-start;max-width:none}.item-row{flex-wrap:wrap}}
</style>
