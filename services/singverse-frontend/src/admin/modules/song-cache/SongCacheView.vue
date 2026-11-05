<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, reactive, ref } from 'vue'
import { onBeforeRouteLeave } from 'vue-router'
import { ElButton, ElMessageBox, ElPagination } from 'element-plus'
import { ApiError } from '../../core/auth/api'
import PageHeader from '../../shared/ui/PageHeader.vue'
import { songCache, type CacheEntry, type CachePage, type CachePolicy, type CacheSettings } from './api'

const gib = 1024 ** 3
const result = ref<CachePage | null>(null)
const settings = ref<CacheSettings | null>(null)
const draft = reactive({ capacityGiB: '' as string | number, policy: 'LRU' as CachePolicy, ttlHours: '' as string | number })
const page = ref(1), loading = ref(false), busy = ref(false), error = ref(''), notice = ref(''), conflict = ref(false)
let active = true, epoch = 0
const policies: { value: CachePolicy; label: string; description: string }[] = [
  { value: 'LRU', label: '最近使用优先（推荐）', description: '空间不足时先清理最久未使用的歌曲，适合日常家庭点歌。' },
  { value: 'LFU', label: '热门歌曲优先', description: '空间不足时先清理命中次数较少的歌曲，优先保留反复点唱的歌曲。' },
  { value: 'TTL', label: '到期清理', description: '按保留时长自动清理；空间不足时先清理最久未使用的歌曲，适合临时聚会。' },
  { value: 'MANUAL', label: '手动清理', description: '由管理员决定保留哪些歌曲；容量不足时停止新的解码任务，已有缓存仍可使用。' },
]
const maxBytes = computed(() => Math.round(Number(draft.capacityGiB) * gib))
const dirty = computed(() => !!settings.value && (maxBytes.value !== settings.value.maxBytes || draft.policy !== settings.value.policy || Number(draft.ttlHours) !== settings.value.ttlHours))
const validation = computed(() => !Number.isFinite(maxBytes.value) || maxBytes.value < 64 * 1024 ** 2 || maxBytes.value > 1024 ** 5
  ? '缓存容量应在 0.0625～1048576 GiB 之间。'
  : !Number.isInteger(Number(draft.ttlHours)) || Number(draft.ttlHours) < 1 || Number(draft.ttlHours) > 8760
    ? '保留时长应为 1～8760 小时的整数。' : '')
const selectedPolicy = computed(() => policies.find(p => p.value === draft.policy)!)
const usedPercent = computed(() => result.value ? Math.min(100, result.value.usageBytes / result.value.settings.maxBytes * 100) : 0)

function adopt(value: CacheSettings) {
  settings.value = { ...value }
  Object.assign(draft, { capacityGiB: value.maxBytes / gib, policy: value.policy, ttlHours: value.ttlHours })
  conflict.value = false
}
function failure(value: unknown) {
  if (!active) return
  error.value = value instanceof Error ? value.message : '读取缓存失败，请重试'
}
async function load(replaceSettings = false) {
  const seq = ++epoch
  loading.value = true; error.value = ''
  try {
    const data = await songCache.list(page.value - 1, 20)
    if (!active || seq !== epoch) return
    result.value = data
    if (!settings.value || replaceSettings || !dirty.value) adopt(data.settings)
    const last = Math.max(1, Math.ceil(data.totalElements / 20))
    if (page.value > last) { page.value = last; await load() }
  } catch (value) { if (seq === epoch) failure(value) }
  finally { if (active && seq === epoch) loading.value = false }
}
async function discard() {
  if (!dirty.value) return true
  try {
    await ElMessageBox.confirm('放弃尚未保存的缓存容量和策略修改？', '有未保存的设置', { confirmButtonText: '放弃修改', cancelButtonText: '继续编辑', type: 'warning', autofocus: false })
    return true
  } catch { return false }
}
async function reloadSettings() {
  if (busy.value || loading.value || !await discard()) return
  await load(true)
}
async function save() {
  if (!settings.value || busy.value || loading.value || !dirty.value || conflict.value) return
  if (validation.value) { error.value = validation.value; return }
  busy.value = true; error.value = ''; notice.value = ''
  try {
    const saved = await songCache.save({ maxBytes: maxBytes.value, policy: draft.policy, ttlHours: Number(draft.ttlHours), revision: settings.value.revision })
    if (!active) return
    adopt(saved); notice.value = '缓存设置已保存'
    await load()
  } catch (value) { failure(value); if (value instanceof ApiError && value.status === 409) conflict.value = true }
  finally { if (active) busy.value = false }
}
async function remove(entry: CacheEntry) {
  if (busy.value || loading.value || entry.leased) return
  try {
    await ElMessageBox.confirm(`删除“${entry.title || '未命名歌曲'}”的这份解码缓存？原始歌曲会保留，下次需要时将重新解码。`, '删除缓存', { confirmButtonText: '删除缓存', cancelButtonText: '保留', type: 'warning', autofocus: false })
  } catch { return }
  if (!active || busy.value) return
  busy.value = true; error.value = ''; notice.value = ''
  try { await songCache.remove(entry.id); if (active) { notice.value = '缓存已删除'; await load() } }
  catch (value) { failure(value) }
  finally { if (active) busy.value = false }
}
async function clear() {
  if (busy.value || loading.value || !result.value || result.value.usageBytes <= 0 && result.value.entryCount <= 0) return
  try {
    await ElMessageBox.confirm('清理所有未在使用的解码缓存？正在播放的缓存会自动跳过，原始歌曲和离线兼容版本会保留。', '清理缓存', { confirmButtonText: '清理缓存', cancelButtonText: '保留', type: 'warning', autofocus: false })
  } catch { return }
  if (!active || busy.value) return
  busy.value = true; error.value = ''; notice.value = ''
  try {
    const cleared = await songCache.clear()
    if (active) { notice.value = `已清理 ${cleared.removed} 份缓存${cleared.skipped ? `，跳过 ${cleared.skipped} 份正在使用的缓存` : ''}`; await load() }
  } catch (value) { failure(value) }
  finally { if (active) busy.value = false }
}
function bytes(value: number) {
  if (value >= gib) return `${(value / gib).toLocaleString('zh-CN', { maximumFractionDigits: 2 })} GiB`
  if (value >= 1024 ** 2) return `${(value / 1024 ** 2).toLocaleString('zh-CN', { maximumFractionDigits: 1 })} MiB`
  return `${(value / 1024).toLocaleString('zh-CN', { maximumFractionDigits: 1 })} KiB`
}
function date(value: string) { const time = new Date(value); return Number.isNaN(time.getTime()) ? '未知' : time.toLocaleString('zh-CN', { hour12: false }) }
function format(entry: CacheEntry) {
  const mode = entry.target.mode
  const output = mode === 'REMUX' ? '重封装，保留源编码'
    : mode === 'AUDIO_TRANSCODE' ? entry.mediaType === 'VIDEO' ? '音频转 AAC，保留视频编码' : 'AAC 音频'
      : entry.mediaType === 'VIDEO' ? `${(entry.target.codec || 'h264').toUpperCase()} · 画质上限 ${entry.target.width || '—'} × ${entry.target.height || '—'}` : 'AAC 音频'
  return `${output}${entry.target.start ? ` · 从 ${entry.target.start} 秒起` : ''}`
}
function beforeUnload(event: BeforeUnloadEvent) { if (dirty.value || busy.value) { event.preventDefault(); event.returnValue = '' } }
onBeforeRouteLeave(() => busy.value ? false : discard())
onMounted(() => { window.addEventListener('beforeunload', beforeUnload); void load() })
onBeforeUnmount(() => { active = false; epoch++; window.removeEventListener('beforeunload', beforeUnload) })
</script>

<template>
  <section class="song-cache-page">
    <PageHeader title="歌曲缓存" description="服务节点保存已完成的音视频解码结果，命中缓存时无需连接计算节点。">
      <template #actions><ElButton :loading="loading" :disabled="busy" @click="load()">刷新列表</ElButton></template>
    </PageHeader>
    <div v-if="error" role="alert" class="cache-feedback cache-error">{{ error }}<ElButton v-if="conflict" text :disabled="busy || loading" @click="reloadSettings">重新读取设置</ElButton></div>
    <p v-if="notice" role="status" class="cache-feedback cache-success">{{ notice }}</p>
    <template v-if="result">
      <section class="sv-panel cache-storage" aria-label="缓存容量概况">
        <div><span class="cache-eyebrow">服务节点缓存占用</span><p class="cache-storage-number">{{ bytes(result.usageBytes) }} <span>/ {{ bytes(result.settings.maxBytes) }}</span></p><progress :value="usedPercent" max="100" aria-label="缓存容量使用比例"/><p class="cache-hint">占用包含正在接收的数据 · {{ result.entryCount }} 份已完成缓存</p></div>
        <div class="cache-storage-policy"><span class="cache-eyebrow">当前策略</span><strong>{{ policies.find(p => p.value === result!.settings.policy)?.label }}</strong><ElButton type="danger" plain :disabled="busy || loading || result.usageBytes <= 0 && result.entryCount <= 0" @click="clear">清理缓存</ElButton></div>
      </section>
      <form v-if="settings" class="sv-panel cache-settings" @submit.prevent="save">
        <div class="cache-section-heading"><div><h2>缓存设置</h2><p>自动清理会跳过正在播放的缓存。缩小容量后，将按策略逐步清理。</p></div><span v-if="dirty" class="cache-draft">尚未保存</span></div>
        <div class="cache-fields">
          <label>最大缓存容量 <span class="cache-unit">GiB</span><input v-model="draft.capacityGiB" type="number" min="0.0625" max="1048576" step="any" required aria-label="最大缓存容量" :disabled="busy || loading"/><small>按 NAS 可用空间设置，1 GiB = 1024 MiB。</small></label>
          <label>缓存策略<select v-model="draft.policy" aria-label="缓存策略" :disabled="busy || loading"><option v-for="policy in policies" :key="policy.value" :value="policy.value">{{ policy.label }}</option></select><small>{{ selectedPolicy.description }}</small></label>
          <label v-if="draft.policy === 'TTL'">保留时长 <span class="cache-unit">小时</span><input v-model="draft.ttlHours" type="number" min="1" max="8760" step="1" required aria-label="缓存保留时长" :disabled="busy || loading"/><small>从缓存生成完成起计算，最多 8760 小时。</small></label>
        </div>
        <div class="cache-settings-footer"><p v-if="dirty && validation" class="cache-validation" role="alert">{{ validation }}</p><span v-else class="cache-hint">仅管理解码衍生数据，不修改原始歌曲。</span><div><ElButton :disabled="busy || loading" @click="reloadSettings">重新读取设置</ElButton><ElButton type="primary" native-type="submit" :loading="busy" :disabled="loading || !dirty || !!validation || conflict">保存设置</ElButton></div></div>
      </form>
      <section class="sv-panel cache-list" aria-label="已缓存的音视频">
        <div class="cache-section-heading"><div><h2>已缓存的音视频 <span>{{ result.totalElements }}</span></h2><p>整曲缓存支持跳转复用；不同画质、音轨、字幕或独立生成的片段可能有多份缓存。离线兼容版本仍在歌曲播放设置中管理。</p></div></div>
        <p v-if="loading" class="cache-loading" role="status">正在读取缓存…</p>
        <div v-else-if="!result.items.length" class="cache-empty"><span aria-hidden="true">♫</span><h3>暂无歌曲缓存</h3><p>首次解码由计算节点执行，完成后自动保存到服务节点，可在这里查看。</p></div>
        <template v-else>
          <div class="cache-table-heading" aria-hidden="true"><span>歌曲 / 格式</span><span>大小</span><span>命中 / 最近使用</span><span>状态 / 操作</span></div>
          <article v-for="entry in result.items" :key="entry.id" class="cache-row">
            <div class="cache-song"><span class="cache-media-icon" :class="{ audio: entry.mediaType === 'AUDIO' }">{{ entry.mediaType === 'AUDIO' ? '音频' : '视频' }}</span><div><h3>{{ entry.title || '未命名歌曲' }}</h3><p>{{ entry.artist || entry.songId || entry.id }}</p><small>{{ format(entry) }}</small></div></div>
            <div class="cache-size"><span class="mobile-label">大小</span>{{ bytes(entry.bytes) }}</div>
            <div class="cache-access"><strong>命中 {{ entry.hits }} 次</strong><time :datetime="entry.lastAccessAt">{{ date(entry.lastAccessAt) }}</time><small :title="date(entry.createdAt)">生成于 {{ date(entry.createdAt) }}</small></div>
            <div class="cache-row-actions"><span :class="{ 'cache-in-use': entry.leased }">{{ entry.leased ? '正在使用' : '可复用' }}</span><ElButton text type="danger" :disabled="entry.leased || busy || loading" :aria-label="`删除${entry.title || '未命名歌曲'}的缓存`" @click="remove(entry)">删除缓存</ElButton></div>
          </article>
        </template>
        <ElPagination v-if="result.totalElements" :current-page="page" :page-size="20" :total="result.totalElements" :disabled="busy || loading" layout="total, prev, pager, next" @current-change="value => { page = value; load() }"/>
      </section>
    </template>
    <p v-else-if="loading" role="status" class="cache-loading">正在读取缓存…</p>
    <section v-else class="sv-panel cache-unavailable"><h2>暂时无法读取歌曲缓存</h2><p>请检查服务节点的缓存目录、磁盘空间和读写权限。缓存管理无需连接计算节点。</p><div><ElButton @click="load()">重试</ElButton></div></section>
  </section>
</template>

<style scoped>
.song-cache-page{display:grid;gap:18px}.cache-feedback{margin:0;padding:12px 16px;border-radius:9px;font-size:13px;line-height:1.7}.cache-error{color:var(--el-color-danger);background:var(--el-color-danger-light-9)}.cache-success{color:var(--el-color-success);background:var(--el-color-success-light-9)}.cache-storage{display:grid;grid-template-columns:minmax(0,1fr) auto;gap:36px;padding:24px}.cache-eyebrow{display:block;color:var(--sv-text-secondary);font-size:12px}.cache-storage-number{margin:8px 0 14px;font-size:30px;font-weight:600;letter-spacing:-.5px}.cache-storage-number span{font-size:15px;font-weight:400;color:var(--sv-text-secondary)}.cache-storage progress{display:block;width:100%;height:7px;border:0;border-radius:8px;background:var(--sv-surface-3);accent-color:var(--sv-accent-primary)}progress::-webkit-progress-bar{background:var(--sv-surface-3);border-radius:8px}progress::-webkit-progress-value{background:var(--sv-accent-primary);border-radius:8px}.cache-hint{font-size:12px;color:var(--sv-text-secondary);line-height:1.8}.cache-storage .cache-hint{margin:10px 0 0}.cache-storage-policy{display:flex;min-width:180px;flex-direction:column;align-items:flex-start;gap:12px}.cache-storage-policy strong{font-size:14px}.cache-storage-policy .el-button{margin-top:auto}.cache-settings,.cache-list{padding:22px 24px}.cache-section-heading{display:flex;justify-content:space-between;align-items:flex-start;gap:16px;margin-bottom:20px}.cache-section-heading h2{font-size:16px;margin:0}.cache-section-heading h2 span{color:var(--sv-text-secondary);font-size:12px;margin-left:8px}.cache-section-heading p{font-size:12px;line-height:1.8;color:var(--sv-text-secondary);margin:7px 0 0}.cache-draft{color:var(--sv-accent-primary);font-size:12px;white-space:nowrap}.cache-fields{display:grid;grid-template-columns:minmax(180px,.6fr) minmax(280px,1fr) minmax(140px,.5fr);gap:22px}.cache-fields label{position:relative;display:flex;flex-direction:column;gap:9px;font-size:13px}.cache-unit{position:absolute;top:0;right:0;color:var(--sv-text-secondary);font-size:12px}.cache-fields input,.cache-fields select{width:100%;min-height:38px;border:1px solid var(--sv-border-default);border-radius:7px;background:var(--sv-surface-1);color:var(--sv-text-primary);padding:8px 10px;font:inherit}.cache-fields input:focus,.cache-fields select:focus{outline:2px solid var(--sv-accent-primary);outline-offset:1px}.cache-fields small{font-size:12px;color:var(--sv-text-secondary);line-height:1.8}.cache-settings-footer{display:flex;justify-content:space-between;align-items:center;gap:16px;margin-top:20px;padding-top:18px;border-top:1px solid var(--sv-border-subtle)}.cache-settings-footer>div{display:flex;gap:10px;flex:none}.cache-settings-footer .el-button{margin:0}.cache-validation{font-size:12px;color:var(--el-color-danger)}.cache-table-heading,.cache-row{display:grid;grid-template-columns:minmax(240px,1fr) 100px minmax(160px,.55fr) 100px;gap:18px;align-items:center}.cache-table-heading{color:var(--sv-text-secondary);font-size:12px;padding:0 0 12px;border-bottom:1px solid var(--sv-border-subtle)}.cache-row{padding:17px 0;border-bottom:1px solid var(--sv-border-subtle)}.cache-song{display:flex;gap:14px;align-items:center;min-width:0}.cache-media-icon{display:grid;place-items:center;width:46px;height:46px;border-radius:11px;background:var(--sv-surface-3);color:var(--sv-text-secondary);font-size:12px;flex:none}.cache-media-icon.audio{color:var(--sv-accent-primary)}.cache-song>div{min-width:0}.cache-song h3{font-size:14px;overflow-wrap:anywhere;margin:0 0 5px}.cache-song p,.cache-song small{display:block;font-size:12px;color:var(--sv-text-secondary);line-height:1.6;margin:0;overflow-wrap:anywhere}.cache-size{font-size:13px;white-space:nowrap}.mobile-label{display:none}.cache-access{display:flex;flex-direction:column;gap:6px;min-width:0}.cache-access strong{font-size:12px;font-weight:500}.cache-access time,.cache-access small{font-size:11px;color:var(--sv-text-secondary)}.cache-access small{opacity:.8}.cache-row-actions{display:flex;flex-direction:column;align-items:flex-end;gap:6px}.cache-row-actions>span{font-size:12px;color:var(--sv-text-secondary)}.cache-row-actions>span.cache-in-use{color:var(--el-color-warning)}.cache-row-actions .el-button{font-size:12px;margin:0;padding:4px 0;height:auto}.cache-list>.el-pagination{justify-content:flex-end;margin-top:18px}.cache-empty{padding:38px 16px;text-align:center}.cache-empty>span{font-size:36px;color:var(--sv-text-secondary)}.cache-empty h3{font-size:16px;margin:14px 0 10px}.cache-empty p,.cache-loading{color:var(--sv-text-secondary);font-size:13px;line-height:1.8}.cache-unavailable{padding:32px}.cache-unavailable h2{font-size:17px}.cache-unavailable p{color:var(--sv-text-secondary);font-size:13px;line-height:1.8}.cache-unavailable>div{display:flex;align-items:center;gap:18px;margin-top:22px}.cache-unavailable .text-link{font-size:13px}
@media(max-width:1100px){.cache-fields{grid-template-columns:minmax(160px,.7fr) minmax(240px,1fr)}.cache-table-heading,.cache-row{grid-template-columns:minmax(200px,1fr) 85px minmax(140px,.6fr) 90px;gap:12px}.cache-settings-footer{align-items:flex-start}.cache-access small{display:none}}
@media(max-width:760px){.cache-storage{padding:20px;gap:22px}.cache-storage-policy{min-width:145px}.cache-storage-number{font-size:24px}.cache-settings,.cache-list{padding:20px}.cache-table-heading{display:none}.cache-row{grid-template-columns:minmax(0,1fr) auto;gap:14px}.cache-song{grid-column:1 / -1}.cache-size{grid-column:1;font-size:12px}.mobile-label{display:inline;color:var(--sv-text-secondary);margin-right:8px}.cache-access{grid-column:1;grid-row:3;gap:5px}.cache-row-actions{grid-column:2;grid-row:2 / 4;align-self:end}.cache-fields{grid-template-columns:1fr}.cache-settings-footer{flex-direction:column}.cache-settings-footer>div{width:100%;flex-wrap:wrap}.cache-list>.el-pagination{justify-content:center;max-width:100%;overflow:auto}.cache-fields label{max-width:100%}}
@media(max-width:430px){.cache-storage{grid-template-columns:1fr;gap:20px}.cache-storage-policy{flex-direction:row;flex-wrap:wrap;align-items:center;gap:12px}.cache-storage-policy .cache-eyebrow{width:100%}.cache-storage-policy .el-button{margin:0 0 0 auto}.cache-storage-policy strong{font-size:12px}}
</style>
