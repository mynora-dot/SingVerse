<script setup lang="ts">
import { onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElAlert, ElButton, ElEmpty, ElMessageBox, ElTag } from 'element-plus'
import { active, bytes, lx, message, platforms, qualities, stateNames, type Batch, type Item, type Status } from './api'
const props = defineProps<{ visible: boolean; status: Status | null }>()
const emit = defineEmits<{ changed: [] }>()
const route = useRoute(), router = useRouter()
const batches = ref<Batch[]>([]), total = ref(0), page = ref(0), loading = ref(false), error = ref(''), selected = ref<Batch | null>(null)
const items = ref<Item[]>([]), itemPage = ref(0), itemTotal = ref(0), itemError = ref(''), actionError = ref(''), busy = ref(''), itemLoading = ref(false)
let alive = true, generation = 0, itemGeneration = 0, timer: ReturnType<typeof setTimeout> | undefined
const date = (value: string) => new Date(value).toLocaleString('zh-CN', { hour12: false })
const retryable = (batch: Batch) => ['FAILED', 'INTERRUPTED', 'CANCELLED'].some(s => (batch.counts[s] || 0) > 0)
function schedule() { clearTimeout(timer); if (alive && !document.hidden && ((props.status?.activeBatches || 0) > 0 || batches.value.some(b => active(b.status)) || (selected.value && active(selected.value.status)) || (props.visible && error.value))) timer = setTimeout(() => void refresh(), error.value ? 5000 : 2500) }
async function refresh(nextPage = page.value) {
  const token = ++generation; loading.value = true
  try { const result = await lx.batches(nextPage); if (!alive || token !== generation) return; batches.value = result.items; total.value = result.total; page.value = nextPage; error.value = ''; if (selected.value) { const current = result.items.find(b => b.id === selected.value?.id); if (current) selected.value = current; await details(selected.value.id, itemPage.value, !current) } if (alive && token === generation) emit('changed') }
  catch (e) { if (alive && token === generation) error.value = message(e) }
  finally { if (alive && token === generation) { loading.value = false; schedule() } }
}
async function details(id: string, nextPage = 0, loadBatch = true) {
  const token = ++itemGeneration; itemLoading.value = true; itemError.value = ''
  try { const [result, batch] = await Promise.all([lx.items(id, nextPage), loadBatch ? lx.batch(id) : Promise.resolve(null)]); if (!alive || token !== itemGeneration) return; items.value = result.items; itemTotal.value = result.total; itemPage.value = nextPage; if (batch) selected.value = batch }
  catch (e) { if (alive && token === itemGeneration) itemError.value = message(e) }
  finally { if (alive && token === itemGeneration) itemLoading.value = false }
}
function choose(batch: Batch) { selected.value = batch; itemPage.value = 0; items.value = []; void router.replace({ query: { ...route.query, tab: 'records', batch: batch.id } }); void details(batch.id) }
async function action(batch: Batch, type: 'cancel' | 'retry') {
  if (busy.value) return
  const title = type === 'cancel' ? '取消未完成下载' : '重新尝试下载'
  const description = type === 'cancel' ? `取消“${batch.name}”中尚未完成的歌曲？已经下载并入库的歌曲会保留。正在发布完整文件的歌曲会完成入库。` : `重试失败、中断和取消的歌曲。保存到：${props.status?.target.path || '未设置'}；使用音源：${props.status?.activeSource?.name || '未启用'}；音质：${qualities[batch.quality]}。`
  try { await ElMessageBox.confirm(description, title, { confirmButtonText: type === 'cancel' ? '取消下载' : '开始重试', cancelButtonText: '返回', type: type === 'cancel' ? 'warning' : 'info' }) } catch { return }
  busy.value = batch.id; actionError.value = ''
  try { if (type === 'cancel') await lx.cancel(batch.id); else { const result = await lx.retry(batch.id); if (alive) choose(result.batch) } if (alive) await refresh(0) }
  catch (e) { if (alive) actionError.value = message(e) }
  finally { if (alive) busy.value = '' }
}
function visibility() { clearTimeout(timer); if (document.hidden) { generation++; itemGeneration++; loading.value = false; itemLoading.value = false } else void refresh() }
watch(() => props.visible, value => { if (value && !document.hidden) void refresh(); else schedule() })
watch(() => props.status?.activeBatches, schedule)
watch(() => route.query.batch, id => { if (typeof id === 'string' && id !== selected.value?.id) void details(id) })
onMounted(() => { document.addEventListener('visibilitychange', visibility); const id = route.query.batch; if (typeof id === 'string') void details(id); void refresh() })
onBeforeUnmount(() => { alive = false; generation++; itemGeneration++; clearTimeout(timer); document.removeEventListener('visibilitychange', visibility) })
defineExpose({ refresh })
</script>
<template>
  <section aria-label="下载记录">
    <div class="toolbar"><p class="help">离开或刷新页面不会停止下载。完整文件会自动加入曲库。</p><ElButton :loading="loading" @click="refresh()">刷新记录</ElButton></div>
    <ElAlert v-if="error" :title="error" type="error" :closable="false" show-icon role="alert" />
    <ElAlert v-if="actionError" :title="actionError" type="error" :closable="false" show-icon role="alert" />
    <ElEmpty v-if="!batches.length && !loading && !error" description="还没有下载记录，先搜索歌曲或导入歌单。" />
    <article v-for="batch in batches" :key="batch.id" class="batch" :class="{ selected: selected?.id === batch.id }">
      <div class="batch-title"><ElButton link type="primary" @click="choose(batch)">{{ batch.name }}</ElButton><ElTag :type="batch.status === 'COMPLETED' ? 'success' : batch.status === 'FAILED' ? 'danger' : 'info'">{{ stateNames[batch.status] || batch.status }}</ElTag></div>
      <p class="help">{{ date(batch.createdAt) }} · {{ batch.total }} 首 · {{ qualities[batch.quality] }}</p>
      <p class="counts" aria-live="polite">已入库 {{ (batch.counts.SUCCESS || 0) + (batch.counts.SKIPPED || 0) }} · 排队 {{ batch.counts.QUEUED || 0 }} · 处理中 {{ (batch.counts.RESOLVING || 0) + (batch.counts.DOWNLOADING || 0) + (batch.counts.PUBLISHING || 0) }} · 失败 / 中断 {{ (batch.counts.FAILED || 0) + (batch.counts.INTERRUPTED || 0) }}</p>
      <div class="actions"><ElButton @click="choose(batch)">查看歌曲</ElButton><ElButton v-if="active(batch.status)" :loading="busy === batch.id" :disabled="!!busy || batch.status === 'CANCELLING'" @click="action(batch, 'cancel')">取消未完成项目</ElButton><ElButton v-if="retryable(batch)" :loading="busy === batch.id" :disabled="!!busy || !status?.ready" @click="action(batch, 'retry')">重试未完成歌曲</ElButton></div>
    </article>
    <nav v-if="total > 20" class="pages" aria-label="下载记录分页"><ElButton :disabled="page === 0 || loading" @click="refresh(page - 1)">上一页</ElButton><span>第 {{ page + 1 }} 页 · 共 {{ total }} 批</span><ElButton :disabled="(page + 1) * 20 >= total || loading" @click="refresh(page + 1)">下一页</ElButton></nav>
    <section v-if="selected || itemError" class="details" aria-label="批次歌曲详情">
      <h3>{{ selected?.name || '下载详情' }}</h3><p class="help">保存位置：{{ selected?.path }} · 任务音源版本已固定</p>
      <ElAlert v-if="itemError" :title="itemError" type="error" :closable="false" show-icon role="alert" />
      <p v-if="itemLoading && !items.length" role="status">正在读取歌曲记录…</p>
      <ul class="item-list"><li v-for="item in items" :key="item.id"><div><strong>{{ item.track.title }}</strong><p class="help">{{ item.track.artists.join(' / ') }} · {{ platforms[item.track.platform] }}<span v-if="item.resolvedQuality"> · {{ qualities[item.resolvedQuality] }}</span></p></div><div class="result"><span role="status">{{ stateNames[item.status] }}</span><span v-if="item.status === 'DOWNLOADING'" class="help">{{ bytes(item.receivedBytes) }}<template v-if="item.totalBytes"> / {{ bytes(item.totalBytes) }}</template></span><a v-if="item.songId && ['SUCCESS', 'SKIPPED'].includes(item.status)" :href="'#/library/songs?detailSongId=' + encodeURIComponent(item.songId)">打开歌曲 ↗</a></div><p v-if="item.message" class="item-message">{{ item.message }}</p></li></ul>
      <nav v-if="itemTotal > 20 && selected" class="pages" aria-label="批次歌曲分页"><ElButton :disabled="itemPage === 0 || itemLoading" @click="details(selected.id, itemPage - 1, false)">上一页</ElButton><span>第 {{ itemPage + 1 }} 页 · 共 {{ itemTotal }} 首</span><ElButton :disabled="(itemPage + 1) * 20 >= itemTotal || itemLoading" @click="details(selected.id, itemPage + 1, false)">下一页</ElButton></nav>
    </section>
  </section>
</template>
<style scoped>
.toolbar,.batch-title,.actions,.pages{display:flex;align-items:center;gap:12px;flex-wrap:wrap}.toolbar{justify-content:space-between}.help{font-size:13px;color:var(--el-text-color-secondary);line-height:1.7;overflow-wrap:anywhere}.batch{border:1px solid var(--el-border-color-lighter);border-radius:12px;margin:16px 0;padding:20px}.batch.selected{border-color:var(--el-color-primary)}.batch-title>.el-button{font-size:16px;white-space:normal;height:auto;text-align:left;line-height:1.6}.counts{font-size:14px;line-height:1.7}.actions>.el-button{margin:0}.pages{justify-content:center;margin:20px 0;font-size:13px}.details{margin-top:28px;padding-top:12px;border-top:1px solid var(--el-border-color)}.item-list{list-style:none;margin:0;padding:0}.item-list>li{display:grid;grid-template-columns:minmax(0,1fr) auto;gap:4px 16px;padding:16px 0;border-bottom:1px solid var(--el-border-color-lighter)}.item-list strong{overflow-wrap:anywhere}.item-list p{margin:6px 0}.result{display:flex;flex-direction:column;align-items:flex-end;gap:6px;font-size:13px}.result>a{color:var(--el-color-primary)}.item-message{grid-column:1/-1;font-size:13px;color:var(--el-color-warning-dark-2);overflow-wrap:anywhere}@media(max-width:600px){.batch{padding:16px}.toolbar{align-items:flex-start}.result{max-width:130px}}
</style>
