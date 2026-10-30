<script setup lang="ts">
import { onMounted, onBeforeUnmount, reactive, ref, computed } from 'vue'
import { ElIcon, ElLoading, ElAlert, ElButton, ElCard, ElDialog, ElEmpty, ElForm, ElFormItem, ElInput, ElMessage, ElMessageBox, ElTag, ElCheckbox } from 'element-plus'
import { FolderOpened, Plus, RefreshRight } from '@element-plus/icons-vue'
import { parserProfiles } from '../../filename-parser/api/rules'
import type { ParserWorkspace } from '../../filename-parser/types'
import { useRouter } from 'vue-router'
import { media, type MediaSource, type SourceType, type ScanTask, type DirectoryPage } from '../api/media'

const router = useRouter()
const vLoading = ElLoading.directive
const items = ref<MediaSource[]>([])
const moreSources = ref(false), sourcePage = ref(0)
const advanced = ref(false), picker = ref(false), directoryLoading = ref(false), directoryError = ref('')
const directory = ref<DirectoryPage | null>(null), selectedDirectory = ref(''), directoryPage = ref(0)
let directoryGeneration = 0
async function browse(path = '', page = 0) {
  const token = ++directoryGeneration
  directoryLoading.value = true; directoryError.value = ''; selectedDirectory.value = ''; directory.value = null
  try {
    const result = await media.directories(path, page)
    if (!active || token !== directoryGeneration || !picker.value) return
    directory.value = result; directoryPage.value = page; selectedDirectory.value = result.path
  } catch (e) { if (active && token === directoryGeneration) directoryError.value = e instanceof Error ? e.message : '读取文件夹失败' }
  finally { if (active && token === directoryGeneration) directoryLoading.value = false }
}
function chooseFolder() { picker.value = true; void browse(draft.location) }
function confirmFolder() {
  if (!selectedDirectory.value || directoryLoading.value || directoryError.value) return
  draft.location = selectedDirectory.value
  picker.value = false; directoryGeneration++
}
function changeType(type: SourceType) {
  if (editing.value || saving.value || draft.type === type) return
  Object.assign(draft, { type, location: '', username: '', password: '' }); clearPassword.value = false
}
const loading = ref(false), saving = ref(false), loaded = ref(false), dialog = ref(false)
const error = ref(''), formError = ref(''), editing = ref<MediaSource | null>(null), clearPassword = ref(false)
const draft = reactive({ scanEnabled: false, scanCron: '0 3 * * *', parserProfileId: 'default', name: '', type: 'LOCAL' as SourceType, location: '', username: '', password: '' })
const profileWorkspace = ref<ParserWorkspace | null>(null), profilesLoading = ref(false), profilesError = ref('')
const selectedProfile = computed(() => profileWorkspace.value?.profiles.find(p => p.id === draft.parserProfileId))
const enabledEntries = computed(() => selectedProfile.value?.entries.filter(e => e.enabled) ?? [])
const scanning = ref('')
const localResults = reactive<Record<string, { totalSongs: number; needsReviewSongs: number }>>({})
const tasks = reactive<Record<string, ScanTask | undefined>>({})
const taskVersions: Record<string, number> = {}
const taskErrors = reactive<Record<string, string>>({})
const cancelling = reactive<Record<string, boolean>>({})
let taskTimer: ReturnType<typeof setTimeout> | undefined, taskGeneration = 0, loadGeneration = 0
function taskActive(task?: ScanTask) { return !!task && ['QUEUED', 'RUNNING'].includes(task.status) }
function taskLabel(task: ScanTask) {
  if (task.cancelRequested && taskActive(task)) return '正在取消'
  if (taskActive(task) && task.phase === 'COMMITTING') return '正在提交索引'
  return { QUEUED: '排队中', RUNNING: '正在扫描目录', SUCCEEDED: '扫描完成', FAILED: '扫描失败', CANCELLED: '已取消', INTERRUPTED: '已中断' }[task.status]
}
function scheduleTasks(token = taskGeneration) {
  clearTimeout(taskTimer)
  if (!active || token !== taskGeneration) return
  if (items.value.some(item => item.type === 'WEBDAV' && (taskActive(tasks[item.id]) || taskErrors[item.id]))) {
    taskTimer = setTimeout(() => { void refreshTasks(false, token) }, 2000)
  }
}
async function refreshTasks(latest: boolean, token = taskGeneration) {
  const sources = items.value.filter(item => item.type === 'WEBDAV')
  await Promise.all(sources.map(async item => {
    const previous = tasks[item.id], version = taskVersions[item.id] ?? 0
    if (!latest && !taskActive(previous) && !taskErrors[item.id]) return
    try {
      const task = latest || !previous ? await media.latestTask(item.id) : await media.task(item.id, previous.id)
      if (!active || token !== taskGeneration || version !== (taskVersions[item.id] ?? 0)) return
      tasks[item.id] = task; taskErrors[item.id] = ''
    } catch (e) {
      if (active && token === taskGeneration && version === (taskVersions[item.id] ?? 0)) taskErrors[item.id] = e instanceof Error ? e.message : '读取扫描状态失败'
    }
  }))
  scheduleTasks(token)
}
async function cancelScan(item: MediaSource) {
  const task = tasks[item.id]
  if (!task || cancelling[item.id]) return
  cancelling[item.id] = true
  try {
    const updated = await media.cancelTask(item.id, task.id)
    if (active) { taskVersions[item.id] = (taskVersions[item.id] ?? 0) + 1; tasks[item.id] = updated; scheduleTasks() }
  } catch (e) { if (active) error.value = e instanceof Error ? e.message : '取消失败' }
  finally { if (active) cancelling[item.id] = false }
}
let active = true, profileGeneration = 0
async function loadProfiles() {
  const token = ++profileGeneration
  profilesLoading.value = true; profilesError.value = ''
  try { const result = await parserProfiles.load(); if (active && token === profileGeneration) profileWorkspace.value = result }
  catch (e) { if (active && token === profileGeneration) profilesError.value = e instanceof Error ? e.message : '加载解析方案失败' }
  finally { if (active && token === profileGeneration) profilesLoading.value = false }
}
function profileName(id: string) {
  return profileWorkspace.value?.profiles.find(p => p.id === id)?.name ?? (profilesError.value ? '方案加载失败' : '方案不可用')
}
async function scan(item: MediaSource) {
  if (scanning.value) return
  try { await ElMessageBox.confirm('扫描会按已保存的解析方案更新该来源的歌曲索引，移除已不存在的文件记录。不会修改原始文件。', item.type === 'WEBDAV' ? '扫描 WebDAV 歌曲' : '扫描本地歌曲', { confirmButtonText: '开始扫描', cancelButtonText: '取消' }) }
  catch { return }
  scanning.value = item.id; error.value = ''
  try {
    if (item.type === 'WEBDAV') {
      const task = await media.createTask(item.id)
      if (!active) return
      taskVersions[item.id] = (taskVersions[item.id] ?? 0) + 1
      tasks[item.id] = task; taskErrors[item.id] = ''; scheduleTasks()
      ElMessage.success('后台扫描已创建，可以离开页面，稍后查看结果')
      return
    }
    const result = await media.scan(item.id)
    if (!active) return
    localResults[item.id] = result
    ElMessage.success(`扫描完成：${result.totalSongs} 首，${result.needsReviewSongs} 首需复核`)
    await load()

  } catch (e) { if (active) error.value = e instanceof Error ? e.message : '扫描失败' }
  finally { if (active) scanning.value = '' }
}
async function showSongs(item: MediaSource) {
  await router.push({ path: '/library/songs', query: { sourceId: item.id } })
}
async function load(append = false) {
  const token = ++loadGeneration
  loading.value = true; error.value = ''
  try {
    const next = append ? sourcePage.value + 1 : 0
    const result = await media.list(next, 100)
    if (!active || token !== loadGeneration) return
    items.value = append ? [...items.value, ...result.items] : result.items
    sourcePage.value = next; moreSources.value = (next + 1) * 100 < result.totalElements; loaded.value = true
    clearTimeout(taskTimer); taskGeneration++; void refreshTasks(true)
  } catch (e) { if (active && token === loadGeneration) error.value = e instanceof Error ? e.message : '读取媒体库失败' }
  finally { if (active && token === loadGeneration) loading.value = false }
}
function open(type: SourceType, item?: MediaSource) {
  editing.value = item ?? null
  Object.assign(draft, { scanEnabled: item?.scanEnabled ?? false, scanCron: item?.scanCron ?? '0 3 * * *', name: item?.name ?? '', type, location: item?.location ?? '', username: item?.username ?? '', password: '', parserProfileId: item?.parserProfileId ?? 'default' })
  advanced.value = false; clearPassword.value = false; formError.value = ''; dialog.value = true; void loadProfiles()
}
function closed() { picker.value = false; directoryGeneration++; draft.password = ''; formError.value = ''; editing.value = null }
async function save() {
  if (saving.value) return
  if (!draft.location.trim()) { formError.value = draft.type === 'LOCAL' ? '请选择歌曲文件夹' : '请填写 WebDAV 目录地址'; return }
  if (profilesLoading.value || profilesError.value || !selectedProfile.value) { formError.value = '请选择有效的解析方案，或重新加载方案列表'; return }
  if (draft.scanEnabled && draft.scanCron.trim().split(/\s+/).length !== 5) { formError.value = '请输入五段 cron：分 时 日 月 周'; return }
  saving.value = true; formError.value = ''
  try {
    await media.save({ name: draft.name.trim() || (draft.type === 'LOCAL' ? draft.location.split(/[\\/]/).filter(Boolean).pop() || '本地歌曲' : 'WebDAV 歌曲').slice(0, 100), type: draft.type, location: draft.location, parserProfileId: draft.parserProfileId,
      ...(draft.scanEnabled || editing.value?.scanEnabled || editing.value?.scanCron ? { scanEnabled: draft.scanEnabled, scanCron: draft.scanCron.trim() } : {}),
      ...(draft.type === 'WEBDAV' ? { username: draft.username,
        password: clearPassword.value ? '' : draft.password || (editing.value ? undefined : '') } : {}) }, editing.value?.id)
    dialog.value = false; draft.password = ''; ElMessage.success('媒体库配置已保存'); await load()
  } catch (e) { formError.value = e instanceof Error ? e.message : '保存失败' }
  finally { saving.value = false }
}
async function remove(item: MediaSource) {
  try {
    await ElMessageBox.confirm(`移除“${item.name}”的媒体库配置？原始文件不会被删除。`, '移除媒体库', { confirmButtonText: '移除', cancelButtonText: '取消', type: 'warning' })
  } catch { return }
  try { await media.remove(item.id); ElMessage.success('已移除媒体库配置'); await load() }
  catch (e) { error.value = e instanceof Error ? e.message : '移除失败' }
}
onMounted(() => { void load(); void loadProfiles() })
onBeforeUnmount(() => { active = false; profileGeneration++; taskGeneration++; loadGeneration++; directoryGeneration++; clearTimeout(taskTimer); draft.password = '' })
</script>

<template>
  <div class="media-library-page management-page">
    <div class="media-heading page-heading">
      <div><h1>歌曲位置</h1><p class="page-description">添加歌曲所在的文件夹，扫描后整理到歌曲库。</p></div>
      <div class="media-actions"><ElButton :icon="RefreshRight" :loading="loading" @click="load()">刷新</ElButton><ElButton type="primary" :icon="Plus" @click="open('LOCAL')">添加歌曲位置</ElButton></div>
    </div>
    <ElAlert v-if="error" :title="error" type="error" :closable="false" show-icon class="media-error"><ElButton text @click="load()">重试</ElButton></ElAlert>
    <section v-loading="loading" class="source-cards" aria-label="歌曲位置">
      <ElEmpty v-if="loaded && !items.length && !error" description="添加本地目录或 WebDAV，开始整理你的歌曲。"><ElButton type="primary" @click="open('LOCAL')">添加第一个目录</ElButton></ElEmpty>
      <ElCard v-for="row in items" :key="row.id" shadow="never" class="source-card">
        <div class="source-content">
          <span class="source-icon"><ElIcon><FolderOpened /></ElIcon></span>
          <div class="source-info">
            <div class="source-title"><h2>{{ row.name }}</h2><ElTag type="info" effect="plain">{{ row.type === 'LOCAL' ? '本地目录' : 'WebDAV' }}</ElTag></div>
            <p class="source-location">{{ row.location }}</p>
            <div v-if="tasks[row.id]" class="scan-progress" aria-live="polite">
              <strong>{{ taskLabel(tasks[row.id]!) }}</strong>
              <span>已处理 {{ tasks[row.id]!.directories }} 个目录、{{ tasks[row.id]!.entries }} 个条目；{{ tasks[row.id]!.totalSongs }} 首，{{ tasks[row.id]!.needsReviewSongs }} 首需复核</span>
              <span v-if="tasks[row.id]!.error" class="scan-error">{{ tasks[row.id]!.error }}</span>
              <ElButton v-if="taskActive(tasks[row.id]) && tasks[row.id]!.phase === 'SCANNING'" link type="warning" :loading="cancelling[row.id]" :disabled="tasks[row.id]!.cancelRequested" @click="cancelScan(row)">取消扫描</ElButton>
            </div>
            <p v-else-if="localResults[row.id]" class="panel-description" aria-live="polite">扫描完成：{{ localResults[row.id]!.totalSongs }} 首，{{ localResults[row.id]!.needsReviewSongs }} 首需复核</p>
            <p v-else class="panel-description">{{ row.type === 'WEBDAV' ? '已配置 · 按需连接' : '已配置 · 可扫描歌曲' }}</p>
            <p v-if="taskErrors[row.id]" class="scan-error">状态读取失败：{{ taskErrors[row.id] }}，正在重试</p>
            <p class="panel-description">{{ row.scanEnabled ? '自动扫描已开启' : '自动扫描未开启' }}<span v-if="row.nextScanAt"> · 下次：{{ new Date(row.nextScanAt).toLocaleString('zh-CN', { timeZone: 'Asia/Shanghai' }) }}</span></p>
            <p v-if="row.lastScanError" class="scan-error">{{ row.lastScanError }}</p>
          </div>
          <div class="source-actions">
            <ElButton type="primary" :loading="scanning === row.id" :disabled="!!scanning || taskActive(tasks[row.id])" @click="scan(row)">扫描歌曲</ElButton>
            <ElButton @click="showSongs(row)">查看歌曲</ElButton>
            <details class="source-menu"><summary :aria-label="`${row.name}的更多操作`">⋯</summary><div><ElButton text @click="open(row.type, row)">编辑</ElButton><ElButton text type="danger" @click="remove(row)">移除</ElButton></div></details>
          </div>
        </div>
      </ElCard>
      <ElButton v-if="moreSources" :loading="loading" @click="load(true)">显示更多位置</ElButton>
    </section>
    <p class="feature-note">本地目录优先读取音频与视频内嵌标签，缺失信息用文件名补充；WebDAV 按文件名整理。扫描不会修改原文件，不代表已验证可播放。</p>
    <ElDialog v-model="dialog" :title="editing ? '编辑歌曲位置' : '添加歌曲位置'" width="min(560px, calc(100vw - 32px))" class="media-dialog" :close-on-click-modal="false" :close-on-press-escape="!saving" :show-close="!saving" :before-close="done => { if (!saving) done() }" @closed="closed">
      <ElForm label-position="top" @submit.prevent="save">
        <div v-if="!editing" class="source-types" aria-label="位置类型"><ElButton :type="draft.type === 'LOCAL' ? 'primary' : 'default'" :disabled="saving" @click="changeType('LOCAL')">本地目录</ElButton><ElButton :type="draft.type === 'WEBDAV' ? 'primary' : 'default'" :disabled="saving" @click="changeType('WEBDAV')">WebDAV</ElButton></div>
        <template v-if="draft.type === 'LOCAL'">
          <ElFormItem label="歌曲文件夹" required><div class="folder-selection"><span>{{ draft.location || '尚未选择文件夹' }}</span><ElButton :disabled="saving" @click="chooseFolder">选择文件夹</ElButton></div></ElFormItem>
          <p class="profile-help">浏览 NAS / 服务器上可访问的文件夹。Docker 部署需先将歌曲目录只读挂载到容器。</p>
        </template>
        <template v-else>
          <ElFormItem label="WebDAV 目录地址" required><ElInput v-model="draft.location" placeholder="https://nas.example/dav/歌曲目录/" maxlength="2048" :disabled="saving" /></ElFormItem>
          <ElFormItem label="用户名（可选）"><ElInput v-model="draft.username" autocomplete="off" maxlength="256" :disabled="saving" /></ElFormItem>
          <ElFormItem :label="editing?.passwordConfigured ? '密码 / 应用密码（留空保留）' : '密码 / 应用密码（可选）'"><ElInput v-model="draft.password" type="password" show-password autocomplete="new-password" maxlength="1024" :disabled="saving || clearPassword" /></ElFormItem>
          <ElCheckbox v-if="editing?.passwordConfigured" v-model="clearPassword" :disabled="saving" @change="draft.password = ''">清除已保存密码</ElCheckbox>
          <p class="muted">凭据仅加密保存在服务端，不会回显。修改地址或账号时需重新填写密码或明确清除。保存不会访问远端，建议使用 HTTPS。OpenList 地址填写 /dav/ 加挂载目录，账号需开启 WebDAV 读取权限；扫描仅列目录、索引文件名，不下载视频。</p>
        </template>
        <ElFormItem label="名称（可选）"><ElInput v-model="draft.name" placeholder="留空使用文件夹名称" maxlength="100" :disabled="saving" /></ElFormItem>
        <details :open="advanced" class="source-advanced" @toggle="advanced = ($event.target as HTMLDetailsElement).open"><summary>更多设置 <span>自动扫描、文件名识别</span></summary>
        <ElFormItem label="定时扫描"><ElCheckbox v-model="draft.scanEnabled" :disabled="saving">启用定时扫描</ElCheckbox></ElFormItem>
        <ElFormItem v-if="draft.scanEnabled" label="Cron 表达式" required><ElInput v-model="draft.scanCron" aria-label="扫描 Cron 表达式" placeholder="0 3 * * *" maxlength="120" :disabled="saving" /><p class="profile-help">标准五段：分 时 日 月 周。例：0 3 * * * 每天 03:00；*/30 * * * * 每半小时。时区：Asia/Shanghai。同一来源扫描中跳过，停机期间不逐次补跑。</p></ElFormItem>
        <ElFormItem label="解析方案" required class="profile-field">
          <select v-model="draft.parserProfileId" aria-label="解析方案" :disabled="saving || profilesLoading || !!profilesError" class="profile-select">
            <option v-for="profile in profileWorkspace?.profiles ?? []" :key="profile.id" :value="profile.id">{{ profile.name }}</option>
          </select>
          <p v-if="selectedProfile" class="profile-help">{{ enabledEntries.length }} 条启用规则，按方案中的顺序匹配，第一条完整命中生效。</p>
          <p v-if="selectedProfile && !enabledEntries.length" class="profile-help">此方案没有启用规则，扫描结果会标记为需复核，不会使用其他方案兜底。</p>
          <p v-if="!profilesLoading && !profilesError && !selectedProfile" class="profile-help">所选方案已不可用，请重新选择。</p>
          <p class="profile-help">{{ draft.type === 'LOCAL' ? '保存后扫描，优先使用文件内的歌名、歌手、专辑与发行日期，缺失时用文件名补充。' : '保存后手动发起后台扫描，按文件名提取歌曲信息。' }} <a href="#/library/filename-parser-profiles">管理解析方案 ↗</a></p>
        </ElFormItem>
        </details>
        <p class="profile-help">使用{{ profileName(draft.parserProfileId) }}，可在更多设置中修改。</p>
        <ElAlert v-if="profilesError" :title="profilesError" type="error" :closable="false"><ElButton text @click="loadProfiles">重新加载解析方案</ElButton></ElAlert>
        <ElAlert v-if="formError" :title="formError" type="error" :closable="false" show-icon />
        <div class="dialog-actions"><ElButton :disabled="saving" @click="dialog = false">取消</ElButton><ElButton type="primary" native-type="submit" :loading="saving" :disabled="profilesLoading || !!profilesError || !selectedProfile">{{ editing ? '保存修改' : '添加位置' }}</ElButton></div>
      </ElForm>
    </ElDialog>

    <ElDialog v-model="picker" title="选择歌曲文件夹" width="min(560px, calc(100vw - 32px))" append-to-body :close-on-click-modal="false" @closed="directoryGeneration++">
      <p class="panel-description">选择歌曲所在的文件夹，包含子文件夹。仅显示服务端可访问的目录。</p>
      <ElAlert v-if="directoryError" :title="directoryError" type="error" :closable="false"><ElButton @click="browse()">返回根目录</ElButton></ElAlert>
      <div v-loading="directoryLoading" class="directory-browser">
        <template v-if="directory">
          <div class="directory-toolbar"><ElButton :disabled="directory.parent === null" @click="browse(directory.parent ?? '')">返回上一级</ElButton><span>{{ directory.path || '文件系统' }}</span></div>
          <p v-if="!directory.items.length" class="panel-description">此文件夹没有可浏览的子文件夹。</p>
          <div v-for="entry in directory.items" :key="entry.path" class="directory-row">
            <label><input v-model="selectedDirectory" type="radio" :value="entry.path" :aria-label="`选择 ${entry.name}`" /><ElIcon><FolderOpened /></ElIcon><span>{{ entry.name }}</span></label><ElButton text @click="browse(entry.path)">打开</ElButton>
          </div>
          <div class="directory-toolbar"><ElButton :disabled="directoryPage === 0" @click="browse(directory.path, directoryPage - 1)">上一页</ElButton><ElButton :disabled="!directory.hasMore" @click="browse(directory.path, directoryPage + 1)">下一页</ElButton></div>
        </template>
      </div>
      <p class="source-location">已选择：{{ selectedDirectory || '请选择文件夹' }}</p>
      <template #footer><ElButton @click="picker = false; directoryGeneration++">取消</ElButton><ElButton type="primary" :disabled="directoryLoading || !!directoryError || !selectedDirectory" @click="confirmFolder">选择此文件夹</ElButton></template>
    </ElDialog>
  </div>
</template>

<style scoped>
.scan-progress { display: flex; flex-direction: column; gap: 6px; font-size: 12px; overflow-wrap: anywhere; }
.scan-progress .el-button { align-self: flex-start; margin-left: 0; }
.scan-error { display: block; color: var(--el-color-danger); overflow-wrap: anywhere; }
.profile-select { width: 100%; }
.profile-field { margin-top: 22px; }
.profile-help { width: 100%; margin: 8px 0 0; font-size: 12px; line-height: 1.8; color: var(--el-text-color-secondary); }
.profile-help a { color: var(--el-color-primary); }
.media-list-header h2 {
  display: flex;
  align-items: center;
}
.media-actions .el-button + .el-button {
  margin-left: 0;
}
</style>

<style scoped>
.source-card { overflow: visible; }
.folder-selection + .profile-help { margin-bottom: 16px; }
.source-menu summary:focus-visible { outline: 2px solid var(--el-color-primary); outline-offset: 2px; }
.source-cards { display: grid; gap: 20px; min-height: 120px; }
.source-content { display: flex; align-items: center; gap: 24px; }
.source-icon { background: var(--el-color-primary-light-9); color: var(--el-color-primary); padding: 20px; border-radius: var(--sv-radius-sm); font-size: 32px; }
.source-info { flex: 1; min-width: 0; }
.source-title { display: flex; align-items: center; flex-wrap: wrap; gap: 12px; }
.source-title h2 { margin: 0; font-size: 20px; }
.source-location { overflow-wrap: anywhere; color: var(--el-text-color-regular); }
.source-menu { position: relative; }
.source-menu summary { cursor: pointer; padding: 5px 12px; border: 1px solid var(--el-border-color); border-radius: var(--sv-radius-sm); list-style: none; }
.source-menu summary::-webkit-details-marker { display: none; }
.source-menu > div { position: absolute; right: 0; top: 100%; z-index: 2; display: flex; padding: 8px; background: var(--el-bg-color); border: 1px solid var(--el-border-color); border-radius: var(--sv-radius-sm); }
.source-actions { display: flex; flex-wrap: wrap; gap: 8px; }
.source-actions .el-button { margin-left: 0; }
.source-types { display: flex; gap: 12px; margin-bottom: 24px; }
.source-types .el-button { flex: 1; margin-left: 0; }
.folder-selection { display: flex; gap: 12px; align-items: center; width: 100%; padding: 12px; border: 1px solid var(--el-border-color); border-radius: var(--sv-radius-sm); }
.folder-selection span { flex: 1; overflow-wrap: anywhere; min-width: 0; }
.source-advanced { margin-top: 24px; padding: 16px; border: 1px solid var(--el-border-color); border-radius: var(--sv-radius-sm); }
.source-advanced summary { cursor: pointer; font-weight: 600; }
.source-advanced summary span { font-weight: normal; color: var(--el-text-color-secondary); margin-left: 8px; }
.source-advanced[open] summary { margin-bottom: 20px; }
.directory-browser { min-height: 120px; margin-top: 16px; }
.directory-toolbar { display: flex; align-items: center; gap: 12px; overflow-wrap: anywhere; margin: 12px 0; }
.directory-row { display: flex; justify-content: space-between; border-bottom: 1px solid var(--el-border-color-lighter); padding: 10px 0; gap: 12px; }
.directory-row label { display: flex; align-items: center; gap: 12px; cursor: pointer; overflow-wrap: anywhere; min-width: 0; }
@media (max-width: 900px) { .source-content { flex-wrap: wrap; gap: 12px; } .source-actions { width: 100%; } .source-icon { padding: 12px; } }
</style>
