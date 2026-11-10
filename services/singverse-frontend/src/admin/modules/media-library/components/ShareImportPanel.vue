<script setup lang="ts">
import { computed, onBeforeUnmount, ref, watch } from 'vue'
import { ElAlert, ElButton, ElInput, ElProgress } from 'element-plus'
import { shareImports, mediaVerification, type ShareImportJob, type ImportDifference } from '../api/shareImports'
import type { MediaSource } from '../types'

const props = defineProps<{ source: MediaSource; jobId?: string }>()
const emit = defineEmits<{ updated: []; uploading: [value: boolean] }>()
const job = ref<ShareImportJob>(), error = ref(''), busy = ref(false), uploading = ref(false)
const remove = ref(''), add = ref(''), differences = ref<ImportDifference[]>([])
const cursor = ref(0), more = ref(false), showingDifferences = ref(false)
let timer: ReturnType<typeof setTimeout> | undefined, controller: AbortController | undefined, generation = 0
const working = computed(() => ['PARSING', 'QUEUED', 'RUNNING', 'VERIFYING', 'PREVIEW'].includes(job.value?.state ?? ''))
const stage = computed(() => ({ PARSING: '正在解析资料包', PREVIEW: '请核对自己的媒体目录', QUEUED: '等待导入', RUNNING: '正在保存资料', VERIFYING: '资料已保存，正在核对媒体路径', SUCCEEDED: '资料处理结束', FAILED: '处理暂未完成', CANCELLED: '已取消，已完成的资料保留' }[job.value?.state ?? ''] ?? ''))
const progress = computed(() => job.value?.phase === 'VERIFYING' ? job.value.verifiedCount : job.value?.processedCount ?? 0)
const percent = computed(() => Math.min(100, Math.round(progress.value * 100 / Math.max(1, job.value?.totalCount ?? 0))))
function accept(value: ShareImportJob) {
  const changed = job.value?.state !== value.state
  job.value = value
  if (changed && ['VERIFYING', 'SUCCEEDED'].includes(value.state)) emit('updated')
}
async function load(initial = false) {
  const token = generation
  try {
    const requested = initial && props.jobId ? props.jobId : job.value?.id
    const value = requested ? await shareImports.get(props.source.id, requested) : await shareImports.latest(props.source.id)
    if (token !== generation) return
    accept(value)
    if (initial) { remove.value = value.removePrefix ?? ''; add.value = value.addPrefix ?? '' }
    error.value = ''
  } catch (e) { if (token === generation) error.value = e instanceof Error ? e.message : '读取导入任务失败' }
  finally { if (token === generation) { clearTimeout(timer); if (job.value?.id && working.value && job.value.state !== 'PREVIEW') timer = setTimeout(() => void load(), 2000) } }
}
watch([() => props.source.id, () => props.jobId], () => { generation++; clearTimeout(timer); job.value = undefined; differences.value = []; showingDifferences.value = false; void load(true) }, { immediate: true })
async function choose(event: Event) {
  const input = event.target as HTMLInputElement, file = input.files?.[0]; input.value = ''
  if (!file || uploading.value || working.value) return
  const token = generation
  uploading.value = true; emit('uploading', true); error.value = ''; controller = new AbortController()
  try {
    const value = await shareImports.upload(props.source.id, file, controller.signal)
    if (token !== generation) return
    accept(value); remove.value = ''; add.value = ''; differences.value = []; showingDifferences.value = false
    clearTimeout(timer); void load()
  } catch (e) { if (token === generation) error.value = controller.signal.aborted ? '上传已取消，可以重新选择资料包' : e instanceof Error ? e.message : '上传失败' }
  finally { uploading.value = false; emit('uploading', false) }
}
async function action(value: 'apply' | 'cancel' | 'retry') {
  if (!job.value?.id || busy.value) return
  busy.value = true; error.value = ''
  try { accept(await shareImports.action(props.source.id, job.value.id, value)); clearTimeout(timer); void load() }
  catch (e) { error.value = e instanceof Error ? e.message : '操作失败' }
  finally { busy.value = false }
}
async function map() {
  if (!job.value?.id) return
  busy.value = true; error.value = ''
  try { accept(await shareImports.mapping(props.source.id, job.value.id, remove.value.trim(), add.value.trim())) }
  catch (e) { error.value = e instanceof Error ? e.message : '目录映射失败' }
  finally { busy.value = false }
}
const mappingChanged = computed(() => remove.value.trim() !== (job.value?.removePrefix ?? '') || add.value.trim() !== (job.value?.addPrefix ?? ''))
async function showDifferences(append = false) {
  if (!job.value?.id) return
  busy.value = true
  try {
    const value = await shareImports.differences(props.source.id, job.value.id, append ? cursor.value : 0)
    differences.value = append ? [...differences.value, ...value.items] : value.items
    more.value = value.hasMore; cursor.value = value.nextCursor; showingDifferences.value = true
  } catch (e) { error.value = e instanceof Error ? e.message : '读取差异失败' }
  finally { busy.value = false }
}
const fieldName = (field: string) => ({ title: '歌名', artists: '歌手', album: '专辑', releaseDate: '发行日期', cover: '封面' }[field] ?? field)
function display(value: unknown) { return Array.isArray(value) ? value.join('、') : typeof value === 'string' ? value : '—' }
function beforeUnload(event: BeforeUnloadEvent) { if (uploading.value) { event.preventDefault(); event.returnValue = '' } }
window.addEventListener('beforeunload', beforeUnload)
onBeforeUnmount(() => { generation++; controller?.abort(); clearTimeout(timer); window.removeEventListener('beforeunload', beforeUnload) })
</script>
<template>
  <section class="share-import" aria-label="导入曲库资料">
    <h3>导入曲库资料</h3>
    <p>先将媒体转存或挂载到自己的 OpenList，再选择收到的完整 ZIP。已有歌曲会补充资料，本地订正会保留。</p>
    <p class="root">自己的媒体根目录：{{ source.location }}</p>
    <label class="file-choice" :class="{ disabled: uploading || working }">选择资料包<input type="file" accept=".zip,application/zip" aria-label="选择资料包" :disabled="uploading || working" @change="choose"></label>
    <div v-if="uploading" role="status"><ElProgress :percentage="0" indeterminate /><p>正在上传，请保持页面打开。上传完成后可关闭页面，后台会继续处理。</p><ElButton @click="controller?.abort()">取消上传</ElButton></div>
    <ElAlert v-if="error" :title="error" type="error" :closable="false" />
    <div v-if="job?.id" class="job" aria-live="polite">
      <strong>{{ stage }}</strong><p v-if="job.deletionCount">共享方提示移除了 {{ job.deletionCount }} 首歌曲，仅供核对，本机资料会保留。</p>
      <p v-if="job.error">{{ job.error }}</p>
      <template v-if="job.state === 'PREVIEW'">
        <p>共 {{ job.totalCount }} 首 · 预计新增 {{ job.preview?.newSongs ?? 0 }} 首 · 为已有 {{ job.preview?.existingSongs ?? 0 }} 首合并资料</p>
        <ul class="samples"><li v-for="sample in job.samples" :key="sample.originalPath"><span>包内：{{ sample.originalPath }}</span><span>自己的地址：{{ sample.mediaURL }}</span><span v-if="sample.incoming">资料预览：{{ display(sample.incoming.title) }} / {{ display(sample.incoming.artists) }}<template v-if="sample.local">；本机现有：{{ display(sample.local.title) }} / {{ display(sample.local.artists) }}</template></span></li></ul>
        <details><summary>内部目录改过名称？调整目录前缀</summary><p>只调整匹配的目录，其余路径保留。留空移除前缀时，会为所有路径添加目录。</p><label>移除包内前缀<ElInput v-model="remove" placeholder="如 原曲库/华语，不填表示保留" /></label><label>添加自己的目录前缀<ElInput v-model="add" placeholder="如 KTV/华语，不填表示不添加" /></label><ElButton :loading="busy" @click="map">更新路径预览</ElButton></details>
        <p>确认后先保存资料，再核对目录和大小；核验通过后进入正常点歌范围。目录核对不代表已验证可播放。</p>
        <ElButton type="primary" :loading="busy" :disabled="mappingChanged" @click="action('apply')">确认导入资料</ElButton>
      </template>
      <template v-else>
        <ElProgress v-if="working" :percentage="percent" :indeterminate="job.state === 'PARSING'" />
        <p>新增 {{ job.addedCount }} · 更新 {{ job.updatedCount }} · 保留本地修改 {{ job.preservedCount }} · 未变化 {{ job.unchangedCount }}</p>
        <p>已核对 {{ job.presentCount }} · 待核验 {{ Math.max(0, job.totalCount - job.verifiedCount) }} · 缺失 {{ job.missingCount }} · 大小不符 {{ job.mismatchCount }} · 连接问题 {{ job.unreachableCount }}</p>
        <p v-if="working">上传已完成，可以关闭页面。稍后从任务中心返回。</p>
        <ElButton v-if="job.preservedCount || job.missingCount || job.mismatchCount || job.unreachableCount" :loading="busy" @click="showDifferences()">查看差异与待处理项</ElButton>
        <ElButton v-if="['FAILED', 'CANCELLED'].includes(job.state) || job.state === 'SUCCEEDED' && (job.missingCount || job.mismatchCount || job.unreachableCount)" :loading="busy" @click="action('retry')">{{ job.phase === 'VERIFYING' ? '重试未通过的核验' : '继续处理' }}</ElButton>
      </template>
      <ElButton v-if="working" :loading="busy" @click="action('cancel')">取消任务</ElButton>
      <div v-if="showingDifferences" class="differences"><p v-if="!differences.length">没有需要处理的差异。</p><article v-for="item in differences" :key="item.ordinal"><strong>{{ item.relativePath }}</strong><p>{{ mediaVerification(item.verification) }}<span v-if="item.verificationError"> · {{ item.verificationError }}</span></p><p v-for="field in item.fields" :key="field"><template v-if="field === 'cover'">封面：本地封面已保留，共享封面已另存。</template><template v-else>{{ fieldName(field) }}：保留本地「{{ display(item.local?.[field]) }}」；共享「{{ display(item.incoming?.[field]) }}」</template></p></article><ElButton v-if="more" :loading="busy" @click="showDifferences(true)">显示更多差异</ElButton></div>
    </div>
  </section>
</template>
<style scoped>
.share-import{border-top:1px solid var(--sv-border-subtle);padding:22px 0}h3{font-size:16px;margin:0 0 8px}p{font-size:13px;line-height:1.7;color:var(--sv-text-secondary);margin:8px 0}.root,.samples,.differences{overflow-wrap:anywhere}.file-choice{display:inline-flex;position:relative;padding:8px 14px;border:1px solid var(--sv-border-subtle);border-radius:6px;cursor:pointer;margin:12px 0;overflow:hidden}.file-choice input{position:absolute;inset:0;opacity:0;width:100%;cursor:pointer}.file-choice:focus-within{outline:2px solid var(--sv-accent-primary)}.disabled{opacity:.5;cursor:default}.job{padding:12px;background:var(--sv-surface-3);border-radius:8px}.job>.el-button{margin:8px 8px 0 0}.samples{padding-left:20px;font-size:12px}.samples li{margin:12px 0}.samples span{display:block}details{margin:14px 0}summary{cursor:pointer;font-size:13px}label{display:block;margin:10px 0;font-size:13px}label .el-input{margin-top:6px}.differences article{border-top:1px solid var(--sv-border-subtle);padding:12px 0;font-size:13px}
</style>
