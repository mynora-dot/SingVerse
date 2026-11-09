<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, ref } from 'vue'
import { ElAlert, ElButton, ElDialog, ElIcon, ElInput, ElTag } from 'element-plus'
import { ArrowRight, Back, FolderOpened, RefreshRight, Search } from '@element-plus/icons-vue'
import { media } from '../api/media'
import type { DirectoryLocation, DirectoryPage, MediaSource } from '../types'

const props = defineProps<{ initialPath: string; sources: MediaSource[]; download: boolean }>()
const emit = defineEmits<{ select: [path: string]; close: [] }>()
const directory = ref<DirectoryPage | null>(null), locations = ref<DirectoryLocation[]>([])
const pathInput = ref(''), selected = ref(''), filter = ref(''), error = ref(''), loading = ref(false)
const page = ref(0), containerized = ref(false)
const listElement = ref<HTMLDivElement>()
let active = true, generation = 0, filterTimer: ReturnType<typeof setTimeout> | undefined
let lastRequest = { path: '', page: 0, q: '' }
const savedLocations = computed(() => props.sources.filter(source => source.type === 'LOCAL'
  && !locations.value.some(location => location.path === source.location)))
const crumbs = computed(() => {
  const current = directory.value?.path ?? ''
  if (!current) return []
  const windows = /^[A-Za-z]:[\\/]/.test(current), separator = windows ? '\\' : '/'
  const parts = current.split(/[\\/]/).filter(Boolean)
  const root = windows ? `${parts.shift()}\\` : '/'
  return [{ name: windows ? root : '根目录', path: root }, ...parts.map((name, index) => ({
    name, path: root + parts.slice(0, index + 1).join(separator),
  }))]
})
const selectedLocation = computed(() => locations.value.find(location => location.path === selected.value))

async function browse(path = '', nextPage = 0, q = '', focusList = false) {
  clearTimeout(filterTimer)
  const token = ++generation
  lastRequest = { path, page: nextPage, q }
  loading.value = true; error.value = ''; selected.value = ''; pathInput.value = path
  try {
    const result = await media.directories(path, nextPage, q)
    if (!active || token !== generation) return
    directory.value = result; page.value = nextPage; pathInput.value = result.path; filter.value = q
    selected.value = result.path
    if (!result.path && result.locations) locations.value = result.locations
    if (result.containerized !== undefined) containerized.value = result.containerized
  } catch (e) {
    if (active && token === generation) error.value = e instanceof Error ? e.message : '读取文件夹失败，请重试'
  } finally { if (active && token === generation) loading.value = false }
  if (focusList && active && token === generation && !error.value) {
    await nextTick()
    listElement.value?.focus({ preventScroll: true })
  }
}
function retry() { void browse(lastRequest.path, lastRequest.page, lastRequest.q) }
function confirm() {
  if (selected.value && !loading.value && !error.value) emit('select', selected.value)
}
function close() { generation++; clearTimeout(filterTimer); emit('close') }
function openPath() {
  const path = pathInput.value.trim()
  if (!path) { error.value = '请输入后端可访问的绝对路径，例如 /media/歌曲'; return }
  void browse(path)
}
function filterFolders() {
  clearTimeout(filterTimer)
  const path = directory.value?.path, query = filter.value
  if (!path) return
  // Invalidate an outstanding page immediately, before the debounce finishes.
  generation++; loading.value = true; selected.value = ''
  filterTimer = setTimeout(() => { void browse(path, 0, query) }, 250)
}
onMounted(async () => {
  const token = generation + 1
  await browse('')
  if (!active || token !== generation) return
  if (props.initialPath.trim()) { await browse(props.initialPath.trim()); return }
  const preferred = locations.value.find(location => location.path === (props.download ? '/downloads' : '/media') && location.readable)
  if (preferred) await browse(preferred.path)
})
onBeforeUnmount(() => { active = false; generation++; clearTimeout(filterTimer) })
</script>

<template>
  <ElDialog :model-value="true" title="选择歌曲文件夹" width="min(780px, calc(100vw - 24px))" top="5vh" class="local-folder-dialog" append-to-body :close-on-click-modal="false" @update:model-value="close">
    <div class="folder-picker-content">
      <p class="folder-description">点击文件夹进入，在下方确认使用。</p>
      <div class="folder-browser-layout">
      <section v-if="locations.length || savedLocations.length" class="folder-places" aria-label="常用位置">
        <span class="folder-section-label">{{ containerized ? '挂载与常用位置' : '常用位置' }}</span>
        <select class="folder-place-select" aria-label="快速前往" :value="locations.some(location => location.path === directory?.path) || savedLocations.some(source => source.location === directory?.path) ? directory?.path : ''" @change="browse(($event.target as HTMLSelectElement).value)">
          <option value="">选择一个位置…</option>
          <option v-for="location in locations" :key="location.path" :value="location.path" :disabled="!location.readable">{{ location.name }} · {{ location.path }}{{ !location.readable ? '（无读取权限）' : !location.writable ? '（只读）' : '' }}</option>
          <option v-for="source in savedLocations" :key="source.id" :value="source.location">{{ source.name }} · {{ source.location }}</option>
        </select>
        <div class="folder-place-list">
          <button v-for="location in locations" :key="location.path" type="button" class="folder-place" :class="{ current: directory?.path === location.path }" :disabled="!location.readable" :aria-label="`前往${location.name} ${location.path}`" @click="browse(location.path)">
            <ElIcon><FolderOpened /></ElIcon><span><strong>{{ location.name }}</strong><code>{{ location.path }}</code></span>
            <ElTag v-if="!location.readable" type="danger" size="small">无读取权限</ElTag>
            <ElTag v-else-if="!location.writable" type="info" size="small">只读</ElTag>
            <ElTag v-else-if="location.mounted" type="info" size="small">已挂载</ElTag>
          </button>
          <button v-for="source in savedLocations" :key="source.id" type="button" class="folder-place" @click="browse(source.location)">
            <ElIcon><FolderOpened /></ElIcon><span><strong>{{ source.name }}</strong><code>{{ source.location }}</code></span>
          </button>
        </div>
      </section>
      <div class="folder-main">
      <form class="folder-path-form" aria-label="跳转文件夹" @submit.prevent="openPath">
        <label for="local-folder-path">目录路径</label>
        <div><ElInput id="local-folder-path" v-model="pathInput" aria-label="目录路径" placeholder="输入完整路径，例如 /media/歌曲" maxlength="2048" clearable @input="selected = ''" /><ElButton native-type="submit">前往</ElButton></div>
      </form>
      <div class="folder-navigation">
        <ElButton :icon="Back" aria-label="返回上一级" :disabled="!directory || directory.parent === null || loading" @click="browse(directory?.parent ?? '')" />
        <nav aria-label="目录位置"><button type="button" @click="browse('')">常用位置</button><template v-for="crumb in crumbs" :key="crumb.path"><span aria-hidden="true">/</span><button type="button" :aria-current="crumb.path === directory?.path ? 'location' : undefined" @click="browse(crumb.path)">{{ crumb.name }}</button></template></nav>
        <ElButton :icon="RefreshRight" aria-label="刷新目录" :disabled="loading" @click="retry" />
      </div>
      <ElAlert v-if="error" :title="error" type="error" :closable="false" show-icon class="folder-error"><ElButton text @click="retry">重试</ElButton><ElButton text @click="browse('')">返回常用位置</ElButton></ElAlert>
      <div class="folder-list-heading"><span>{{ directory?.path ? '子文件夹' : '文件系统' }}</span><ElInput v-if="directory?.path" v-model="filter" :prefix-icon="Search" aria-label="筛选文件夹" placeholder="按文件夹名称筛选" maxlength="100" clearable @input="filterFolders" /></div>
      <div ref="listElement" class="folder-list" role="group" aria-label="文件夹列表" :aria-busy="loading" tabindex="-1">
        <p v-if="loading" class="folder-empty" role="status">正在读取文件夹…</p>
        <template v-else-if="directory">
          <p v-if="!directory.items.length" class="folder-empty" role="status">{{ filter ? '没有匹配的子文件夹，试试其他名称。' : '此目录没有子文件夹，可以直接选择当前文件夹。' }}</p>
          <button v-for="entry in directory.items" :key="entry.path" type="button" class="folder-row" :aria-label="`进入 ${entry.name}`" @click="browse(entry.path, 0, '', true)"><ElIcon><FolderOpened /></ElIcon><span>{{ entry.name }}</span><ElIcon class="folder-next" aria-hidden="true"><ArrowRight /></ElIcon></button>
        </template>
      </div>
      <div v-if="directory && (page > 0 || directory.hasMore)" class="folder-pagination"><span>第 {{ page + 1 }} 页，每页最多 100 个文件夹</span><ElButton :disabled="page === 0 || loading" @click="browse(directory.path, page - 1, filter)">上一页</ElButton><ElButton :disabled="!directory.hasMore || loading" @click="browse(directory.path, page + 1, filter)">下一页</ElButton></div>
      <div class="folder-bottom-hint"><span>扫描时会包含子文件夹</span><details class="folder-help"><summary>找不到文件夹？</summary><p>{{ containerized ? '这里只显示后端容器内的路径。例如宿主机 /volume1/music 挂载到 /media 后，请选择 /media。目录为空或无法读取时，请检查后端的挂载和读取权限。' : '这里只显示后端服务可读取的目录。可以输入完整路径直接前往；外接存储或网络文件夹需先在服务器挂载，并允许后端读取。' }}</p></details></div>
      </div>
      </div>
    </div>
    <template #footer>
      <div class="folder-picker-footer">
        <div class="folder-chosen" role="status" aria-live="polite"><span>当前文件夹 <ElTag v-if="selectedLocation && !selectedLocation.writable" type="info" size="small">只读</ElTag></span><strong>{{ selected || '请进入要使用的文件夹' }}</strong></div>
        <div class="folder-footer-actions"><ElButton @click="close">取消</ElButton><ElButton type="primary" :disabled="loading || !!error || !selected" @click="confirm">使用当前文件夹</ElButton></div>
      </div>
    </template>
  </ElDialog>
</template>

<style scoped>
.folder-picker-content { display: flex; flex-direction: column; gap: 16px; height: min(620px, calc(88dvh - 190px)); }
.folder-description { margin: 0; color: var(--el-text-color-regular); line-height: 1.6; }
.folder-browser-layout { display: flex; flex: 1; min-height: 0; gap: 20px; }
.folder-main { display: flex; flex-direction: column; flex: 1; min-width: 0; min-height: 0; gap: 12px; overflow-y: auto; }
.folder-main > :not(.folder-list) { flex-shrink: 0; }
.folder-places { width: 172px; flex-shrink: 0; overflow-y: auto; }
.folder-place-select { display: none; }
.folder-section-label, .folder-path-form > label, .folder-list-heading > span { font-size: 13px; color: var(--el-text-color-secondary); }
.folder-place-list { display: flex; flex-direction: column; gap: 8px; margin-top: 8px; }
.folder-place { display: grid; grid-template-columns: 20px minmax(0, 1fr); align-items: start; gap: 6px 8px; min-width: 0; padding: 10px; border: 1px solid var(--el-border-color); border-radius: var(--sv-radius-sm); color: var(--el-text-color-regular); background: var(--el-fill-color-blank); text-align: left; cursor: pointer; font: inherit; }
.folder-place > .el-tag { grid-column: 2; justify-self: start; }
.folder-place > .el-icon { color: var(--el-color-primary); font-size: 20px; margin-top: 1px; }
.folder-place > span:not(.el-tag) { display: flex; flex: 1; min-width: 0; flex-direction: column; gap: 4px; }
.folder-place strong { font-size: 14px; font-weight: 500; }
.folder-place code { color: var(--el-text-color-secondary); font-size: 12px; overflow-wrap: anywhere; }
.folder-place.current, .folder-place:hover:not(:disabled) { background: var(--el-color-primary-light-9); border-color: var(--el-color-primary-light-5); }
.folder-place:disabled { opacity: .65; cursor: not-allowed; }
.folder-path-form > div { display: flex; gap: 8px; margin-top: 6px; }
.folder-path-form .el-input { min-width: 0; }
.folder-path-form .el-button, .folder-navigation > .el-button { min-height: 44px; }
.folder-navigation { display: flex; align-items: center; gap: 8px; }
.folder-navigation nav { display: flex; align-items: center; flex: 1; gap: 4px; min-width: 0; overflow-x: auto; }
.folder-navigation nav button { border: 0; background: transparent; padding: 6px; min-height: 44px; color: var(--el-color-primary); font: inherit; cursor: pointer; flex-shrink: 0; white-space: nowrap; text-align: left; }
.folder-navigation nav button[aria-current] { color: var(--el-text-color-primary); font-weight: 600; }
.folder-navigation nav > span { color: var(--el-text-color-placeholder); }
.folder-list-heading { display: flex; align-items: center; justify-content: space-between; gap: 12px; }
.folder-list-heading .el-input { width: min(240px, 68%); }
.folder-list { overflow-y: auto; min-height: 120px; border: 1px solid var(--el-border-color-lighter); border-radius: var(--sv-radius-sm); flex: 1 0 120px; }
.folder-row { display: flex; align-items: center; width: 100%; min-height: 48px; padding: 10px 12px; border: 0; border-bottom: 1px solid var(--el-border-color-lighter); gap: 12px; font: inherit; color: var(--el-text-color-regular); background: var(--el-fill-color-blank); text-align: left; cursor: pointer; }
.folder-row:last-child { border-bottom: 0; }
.folder-row:hover { background: var(--el-fill-color-light); }
.folder-row > span { flex: 1; min-width: 0; overflow-wrap: anywhere; }
.folder-row > .el-icon { flex-shrink: 0; color: var(--el-color-primary); font-size: 18px; }
.folder-row > .folder-next { color: var(--el-text-color-secondary); font-size: 14px; }
.folder-empty { padding: 28px 16px; margin: 0; text-align: center; color: var(--el-text-color-secondary); line-height: 1.7; }
.folder-pagination { display: flex; align-items: center; justify-content: flex-end; gap: 8px; font-size: 12px; }
.folder-pagination > span { margin-right: auto; color: var(--el-text-color-secondary); }
.folder-pagination .el-button + .el-button { margin-left: 0; }
.folder-pagination .el-button { min-height: 44px; }
.folder-help { font-size: 13px; color: var(--el-text-color-secondary); line-height: 1.7; }
.folder-help summary { cursor: pointer; padding: 4px 0; }
.folder-help p { margin: 8px 0 0; }
.folder-bottom-hint { display: flex; flex-wrap: wrap; align-items: baseline; justify-content: space-between; gap: 4px 12px; font-size: 12px; color: var(--el-text-color-secondary); }
.folder-picker-footer { display: flex; align-items: center; gap: 16px; border-top: 1px solid var(--el-border-color-lighter); padding-top: 16px; }
.folder-chosen { display: flex; flex: 1; min-width: 0; flex-direction: column; gap: 6px; text-align: left; }
.folder-chosen > span { color: var(--el-text-color-secondary); font-size: 12px; }
.folder-chosen strong { color: var(--el-text-color-primary); font-size: 14px; font-weight: 500; overflow-wrap: anywhere; max-height: 66px; overflow-y: auto; }
.folder-footer-actions { display: flex; gap: 8px; flex-shrink: 0; }
.folder-footer-actions .el-button { min-height: 44px; margin: 0; }
button:focus-visible, summary:focus-visible, input:focus-visible, select:focus-visible, .folder-list:focus-visible { outline: 2px solid var(--el-color-primary); outline-offset: 2px; }
@media (max-width: 600px) {
  .folder-description, .folder-section-label, .folder-path-form > label { display: none; }
  .folder-browser-layout { flex-direction: column; gap: 12px; }
  .folder-places { width: 100%; overflow: visible; }
  .folder-place-list { display: none; }
  .folder-place-select { display: block; width: 100%; min-height: 44px; margin-top: 0; padding: 8px; font: inherit; font-size: 16px; border: 1px solid var(--el-border-color); border-radius: var(--sv-radius-sm); color: var(--el-text-color-primary); background: var(--el-fill-color-blank); }
  .folder-path-form > div { margin-top: 0; }
  .folder-list { flex-shrink: 1; min-height: 72px; }
  .folder-picker-footer { flex-wrap: wrap; gap: 12px; }
  .folder-chosen { flex-basis: 100%; }
  .folder-footer-actions { width: 100%; justify-content: flex-end; }
  .folder-picker-content { height: calc(88dvh - 215px); min-height: 170px; gap: 12px; }
  .folder-navigation nav { font-size: 13px; }
}
</style>
