<script setup lang="ts">
import PageHeader from '../../../shared/ui/PageHeader.vue'
import { computed, nextTick, onBeforeUnmount, onMounted, reactive, ref, watch } from 'vue'
import { onBeforeRouteLeave, useRoute, useRouter } from 'vue-router'
import { ElAlert, ElButton, ElInput, ElMessageBox, ElSkeleton, ElTag, ElTabs, ElTabPane, ElDialog, ElSwitch, ElDrawer } from 'element-plus'
import { RefreshRight, Plus, Delete, Check, ArrowRight } from '@element-plus/icons-vue'
import AudioModelSettings from '../AudioModelSettings.vue'
import HardwareSettings from '../HardwareSettings.vue'
import { computeNode } from '../api'
import type { NodeSettings, NodeInput, NodeCheck, NodeMode } from '../types'

const route = useRoute(), router = useRouter()
const taskTab = ref(route.hash === '#audio-models' || route.query.tab === 'audio' ? 'audio' : 'video')
watch(() => [route.query.tab, route.hash], () => {
  taskTab.value = route.hash === '#audio-models' || route.query.tab === 'audio' ? 'audio' : 'video'
})
function selectTask(name: string | number) {
  void router.replace({ query: { ...route.query, tab: String(name) }, hash: '' })
}
const settings = ref<NodeSettings | null>(null)
const helpVisible = ref(false)
const editorHeading = ref<HTMLElement | null>(null)
const currentCheck = ref<NodeCheck | null>(null), draftCheck = ref<NodeCheck | null>(null)
const loading = ref(false), checking = ref(false), testing = ref(false), saving = ref(false), editing = ref(false)
const error = ref(''), currentError = ref(''), formError = ref(''), saved = ref(false)
const draft = reactive<NodeInput>({ mode: 'AUTO', endpoint: '', token: '', clearToken: false, mappings: [], revision: 0 })
let active = true, currentGeneration = 0, draftGeneration = 0, loadGeneration = 0
watch(draft, () => { draftCheck.value = null; formError.value = ''; draftGeneration++ }, { deep: true, flush: 'sync' })
const busy = computed(() => saving.value || testing.value)
const dirty = computed(() => !!settings.value && (draft.mode !== settings.value.mode || draft.endpoint !== displayedEndpoint(settings.value) || !!draft.token || draft.clearToken || JSON.stringify(draft.mappings) !== JSON.stringify(settings.value.mappings)))
const endpointChanged = computed(() => !!settings.value && draft.endpoint.trim().replace(/\/$/, '') !== settings.value.endpoint)
const canKeepToken = computed(() => !!settings.value?.hasToken && !endpointChanged.value)
const usesDeployment = computed(() => draft.mode === 'AUTO')
const hasDeploymentToken = computed(() => usesDeployment.value && !!settings.value?.automaticHasToken)
const connected = computed(() => currentCheck.value?.status === 'AVAILABLE')
const effectiveEndpoint = computed(() => settings.value?.mode === 'AUTO' ? settings.value.automaticEndpoint : settings.value?.endpoint)
function supports(...capabilities: string[]) { return connected.value && capabilities.some(item => currentCheck.value?.capabilities.includes(item)) }
const headline = computed(() => {
  if (settings.value?.mode === 'DISABLED') return '暂未启用计算服务'
  if (checking.value) return '正在检查连接…'
  if (connected.value) return currentCheck.value?.capabilities.length ? '计算服务已就绪' : '已连接，暂无可用功能'
  if (settings.value?.mode === 'AUTO' && !settings.value.automaticEndpoint) return '还没有连接计算服务'
  if (currentCheck.value?.status === 'MISSING_TOKEN') return '需要连接密钥'
  if (currentCheck.value?.status === 'AUTH_FAILED') return '连接密钥不匹配'
  if (currentCheck.value?.status === 'INCOMPATIBLE') return '服务版本不兼容'
  return '计算服务暂不可用'
})
const enabled = computed({
  get: () => draft.mode !== 'DISABLED',
  set: (value: boolean) => { draft.mode = value ? lastEnabledMode.value : 'DISABLED' },
})
const lastEnabledMode = ref<NodeMode>('AUTO')
function displayedEndpoint(value: NodeSettings) {
  return value.mode === 'AUTO' ? value.automaticEndpoint : value.endpoint || value.automaticEndpoint
}
watch(() => [draft.endpoint, draft.token, draft.clearToken, JSON.stringify(draft.mappings)], () => {
  const value = settings.value
  if (!value || !editing.value) return
  const mode: NodeMode = value.mode !== 'MANUAL' && !!value.automaticEndpoint
    && draft.endpoint.trim().replace(/\/$/, '') === value.automaticEndpoint
    && !draft.token.trim() && !draft.clearToken
    && JSON.stringify(draft.mappings) === JSON.stringify(value.mappings) ? 'AUTO' : 'MANUAL'
  lastEnabledMode.value = mode
  if (draft.mode !== 'DISABLED') draft.mode = mode
}, { flush: 'sync' })
const nextAction = computed(() => settings.value?.mode === 'DISABLED' ? '启用计算服务' : connected.value ? '编辑连接' : effectiveEndpoint.value ? '检查连接设置' : '连接其他设备')
const canSaveOffline = computed(() => draftCheck.value && draftCheck.value.status !== 'AVAILABLE' && draft.mode !== 'DISABLED' && !draft.clearToken)
function snapshot(): NodeInput { return { ...draft, mappings: draft.mappings.map(mapping => ({ ...mapping })) } }
function populate(value: NodeSettings) {
  lastEnabledMode.value = value.mode === 'DISABLED' ? (!value.endpoint && value.automaticEndpoint ? 'AUTO' : 'MANUAL') : value.mode
  Object.assign(draft, { mode: value.mode, endpoint: displayedEndpoint(value), token: '', clearToken: false, mappings: value.mappings.map(mapping => ({ ...mapping })), revision: value.revision })
}
async function load() {
  const sequence = ++loadGeneration
  loading.value = true; error.value = ''
  try {
    const value = await computeNode.get()
    if (!active || sequence !== loadGeneration) return
    settings.value = value; populate(value); void checkCurrent()
  } catch (failure) { if (active && sequence === loadGeneration) error.value = message(failure, '读取配置失败，请重试。') }
  finally { if (active && sequence === loadGeneration) loading.value = false }
}
async function checkCurrent() {
  const value = settings.value
  if (!value) return
  const sequence = ++currentGeneration
  currentError.value = ''
  if (value.mode === 'DISABLED') { currentCheck.value = null; checking.value = false; return }
  if (value.mode === 'MANUAL' && !value.hasToken) {
    currentCheck.value = { status: 'MISSING_TOKEN', message: '请填写计算服务的连接密钥后再使用。', capabilities: [], elapsedMs: 0 }; return
  }
  checking.value = true
  try {
    const result = await computeNode.test({ mode: value.mode, endpoint: displayedEndpoint(value), token: '', clearToken: false, mappings: value.mappings, revision: value.revision })
    if (active && sequence === currentGeneration) currentCheck.value = result
  } catch (failure) { if (active && sequence === currentGeneration) { currentCheck.value = null; currentError.value = message(failure, '连接检查失败，请重试。') } }
  finally { if (active && sequence === currentGeneration) checking.value = false }
}
async function openEditor(mode?: NodeMode) {
  if (!settings.value) return
  populate(settings.value); draft.mode = mode ?? (settings.value.mode === 'DISABLED' ? lastEnabledMode.value : !effectiveEndpoint.value ? 'MANUAL' : settings.value.mode)
  helpVisible.value = false; editing.value = true; saved.value = false
  await nextTick()
  if (active) { editorHeading.value?.focus({ preventScroll: true }); editorHeading.value?.scrollIntoView?.({ block: 'start' }) }
}
function closeEditor(done: () => void) { if (!busy.value) { cancel(); done() } }
function cancel() { if (settings.value) populate(settings.value); editing.value = false }
function validate() {
  if (draft.mode === 'DISABLED') return true
  if (usesDeployment.value) return true
  if (!draft.endpoint.trim()) { formError.value = '请填写计算服务地址。'; return false }
  if (!draft.clearToken && !draft.token.trim() && !canKeepToken.value) { formError.value = '请填写连接密钥，可从计算服务的 token 文件获取。'; return false }
  if (draft.mappings.some(mapping => !mapping.localRoot.startsWith('/') || !mapping.remoteRoot.startsWith('/'))) { formError.value = '请为每条映射填写两端的完整目录路径，例如 /media。'; return false }
  return true
}
async function testDraft(): Promise<NodeCheck | null> {
  if (!validate()) return null
  const sequence = ++draftGeneration
  testing.value = true; formError.value = ''; draftCheck.value = null
  try {
    const value = await computeNode.test(snapshot())
    if (active && sequence === draftGeneration) { draftCheck.value = value; return value }
  } catch (failure) { if (active && sequence === draftGeneration) formError.value = message(failure, '连接测试失败，请重试。') }
  finally { if (active) testing.value = false }
  return null
}
async function save(skipTest = false) {
  if (busy.value || !validate()) return
  // Keep the working connection until the replacement passes its connection check.
  if (!skipTest && draft.mode !== 'DISABLED' && !draft.clearToken) {
    const result = await testDraft()
    if (!result || result.status !== 'AVAILABLE') return
  }
  if (!active) return
  saving.value = true; formError.value = ''
  try {
    const value = await computeNode.save(snapshot())
    if (!active) return
    settings.value = value; populate(value); currentCheck.value = null; editing.value = false; saved.value = true; void checkCurrent()
  } catch (failure) { if (active) formError.value = message(failure, '保存失败，请重试。') }
  finally { if (active) saving.value = false }
}
function message(failure: unknown, fallback: string) { return failure instanceof Error ? failure.message : fallback }
onBeforeRouteLeave(async () => {
  if (busy.value) return false
  if (!editing.value || !dirty.value) return true
  try {
    await ElMessageBox.confirm('离开后将放弃尚未保存的连接设置。', '有未保存的设置', { confirmButtonText: '放弃修改', cancelButtonText: '继续编辑', type: 'warning' })
    return true
  } catch { return false }
})
onMounted(load)
onBeforeUnmount(() => { active = false; currentGeneration++; draftGeneration++; loadGeneration++ })
</script>

<template>
  <section class="compute-settings">
    <PageHeader class="compute-heading" title="性能节点配置" />
    <ElAlert v-if="error" :title="error" type="error" :closable="false" show-icon><ElButton text @click="load">重新读取</ElButton></ElAlert>
    <ElSkeleton v-if="loading && !settings" :rows="5" animated />
    <template v-if="settings">
      <section class="connection-card" :class="{ ready: connected, inactive: settings.mode === 'DISABLED' }" aria-label="当前计算服务">
        <div class="connection-copy">
          <div class="connection-title"><span class="sv-status-dot" :class="connected ? 'ok' : checking ? '' : 'warn'" aria-hidden="true" /><h2 aria-live="polite">{{ headline }}</h2><span v-if="connected && !checking" class="connection-time">{{ currentCheck?.elapsedMs }} ms</span></div>
          <code v-if="effectiveEndpoint && settings.mode !== 'DISABLED'" class="connection-address">{{ effectiveEndpoint }}</code>
          <p v-if="!connected && !checking && effectiveEndpoint && settings.mode !== 'DISABLED'" class="connection-error" role="status">{{ currentError || currentCheck?.message || '请检查服务是否已启动。' }}</p>
        </div>
        <div class="connection-actions"><ElButton v-if="!editing" :type="connected ? 'default' : 'primary'" :icon="connected ? undefined : ArrowRight" :disabled="checking" @click="openEditor()">{{ nextAction }}</ElButton><ElButton v-if="settings.mode !== 'DISABLED'" text :icon="RefreshRight" :loading="checking" :disabled="loading || busy" @click="checkCurrent">重新检查</ElButton><span v-if="saved" role="status" class="saved-note"><Check />配置已保存</span></div>
      </section>

      <ElDrawer :model-value="editing" class="connection-drawer" size="min(520px, 100vw)" :append-to-body="false" :close-on-click-modal="false" :show-close="!busy" :before-close="closeEditor" destroy-on-close @opened="editorHeading?.focus()">
        <template #header><div class="editor-heading"><h2 ref="editorHeading" tabindex="-1">连接设置</h2><span v-if="dirty" class="draft-note">尚未保存</span></div></template>
        <section v-if="editing" class="connection-editor" aria-label="连接设置">

        <form id="compute-connection-form" @submit.prevent="save()">
          <div class="connection-controls">
            <div class="enable-node"><span>启用计算服务</span><ElSwitch v-model="enabled" aria-label="启用计算服务" :disabled="busy" /></div>
          </div>
          <div v-if="enabled" class="manual-settings">
            <label class="node-field"><span>计算服务地址</span><ElInput v-model="draft.endpoint" aria-label="计算服务地址" placeholder="例如 http://192.168.1.20:18090" maxlength="512" :disabled="busy" /></label>
            <label class="node-field"><span>连接密钥 <ElTag v-if="canKeepToken || hasDeploymentToken" size="small" effect="plain">已配置</ElTag></span><ElInput v-model="draft.token" aria-label="计算节点认证凭据" type="password" autocomplete="new-password" :placeholder="hasDeploymentToken ? '已使用部署密钥，留空保留' : canKeepToken ? '留空保留已保存密钥' : '填写计算服务的连接密钥'" maxlength="256" show-password :disabled="busy || draft.clearToken" /><small v-if="!hasDeploymentToken && !canKeepToken && endpointChanged && effectiveEndpoint" class="credential-warning">服务地址已改变，需重新填写密钥。</small></label>
            <ElButton class="credential-help" link type="primary" @click="helpVisible = true">如何获取连接信息？</ElButton>
            <details class="node-paths"><summary>目录映射与密钥管理 <span>{{ draft.mappings.length ? `${draft.mappings.length} 条映射` : '可选' }}</span></summary><p>仅在两端媒体路径不同时添加映射。</p><div v-for="(mapping, index) in draft.mappings" :key="index" class="node-mapping"><label>主服务目录<ElInput v-model="mapping.localRoot" :aria-label="`本机媒体路径 ${index + 1}`" placeholder="/mnt/music" :disabled="busy" /></label><span aria-hidden="true">→</span><label>计算设备目录<ElInput v-model="mapping.remoteRoot" :aria-label="`节点媒体路径 ${index + 1}`" placeholder="/media" :disabled="busy" /></label><ElButton :icon="Delete" :aria-label="`移除路径映射 ${index + 1}`" :disabled="busy" @click="draft.mappings.splice(index, 1)" /></div><ElButton :icon="Plus" :disabled="busy || draft.mappings.length >= 16" @click="draft.mappings.push({ localRoot: '', remoteRoot: '' })">添加目录映射</ElButton><label v-if="canKeepToken && !usesDeployment" class="node-clear-token"><input v-model="draft.clearToken" type="checkbox" :disabled="busy" @change="draft.token = ''">清除已保存密钥，暂停使用此连接</label></details>
          </div>
          <p v-else class="disabled-note">保存后停用计算服务，已有歌曲和生成成果保留。</p>
          <ElAlert v-if="formError" class="editor-feedback" :title="formError" type="error" :closable="false" show-icon><ElButton v-if="formError.includes('配置已被修改')" text :disabled="busy" @click="load">放弃草稿，读取最新配置</ElButton></ElAlert>
          <div v-if="draftCheck" class="draft-result editor-feedback" :class="{ success: draftCheck.status === 'AVAILABLE' }" role="status"><strong>{{ draftCheck.status === 'AVAILABLE' ? '连接测试通过' : '连接尚未成功，当前连接保持不变' }}</strong><p>{{ draftCheck.message }}<span v-if="draftCheck.status === 'AVAILABLE'"> · {{ draftCheck.elapsedMs }} ms</span></p><ElButton v-if="canSaveOffline" text :disabled="busy" @click="save(true)">仅保存配置，稍后连接</ElButton></div>
        </form>
        </section>
        <template #footer><div class="editor-actions"><ElButton v-if="draft.mode !== 'DISABLED' && !draft.clearToken" text :disabled="busy" @click="testDraft">仅测试连接</ElButton><span class="action-spacer" /><ElButton :disabled="busy" @click="cancel">取消</ElButton><ElButton type="primary" :loading="busy" :disabled="!dirty" @click="save()">保存设置</ElButton></div></template>
      </ElDrawer>
      <ElDialog v-model="helpVisible" title="获取连接信息" width="min(560px, 92vw)" append-to-body :close-on-click-modal="false">
        <div class="setup-help"><p>填写主服务可访问的计算设备地址。内网可用 HTTP，公网需用 HTTPS；独立部署默认端口为 18090。</p><p>在计算设备上执行以下命令获取密钥：</p><code>docker compose -f compose.compute.yaml exec -T singverse-compute cat /run/singverse-compute/token</code></div>
        <template #footer><ElButton @click="helpVisible = false">关闭</ElButton></template>
      </ElDialog>
      <section class="task-settings" aria-label="功能设置">
        <ElTabs v-model="taskTab" @tab-change="selectTask">
          <ElTabPane label="视频播放加速" name="video" lazy>
            <HardwareSettings v-if="supports('transcode-hls', 'transcode-offline')" :key="`${settings?.mode}:${settings?.revision}`" />
            <div v-else class="task-empty"><h2>{{ connected ? '此服务暂未提供视频转码' : '连接后设置视频播放加速' }}</h2><p>{{ connected ? '请确认计算服务已安装媒体工具并配置转码缓存目录，完成后重新检查连接。' : '先完成上方的服务连接，才能读取可用硬件和转码设置。' }}</p></div>
          </ElTabPane>
          <ElTabPane label="伴奏生成" name="audio" lazy>
            <AudioModelSettings v-if="supports('media-probe', 'audio-role-fast', 'audio-separation')" :key="`audio:${settings?.mode}:${settings?.revision}`" />
            <div v-else id="audio-models" class="task-empty"><h2>{{ connected ? '此服务暂未提供伴奏处理' : '连接后准备伴奏模型' }}</h2><p>{{ connected ? '请确认计算服务已启用音频处理并配置工作目录，完成后重新检查连接。' : '先完成上方的服务连接，再下载模型、选择设备并试跑。' }}</p></div>
          </ElTabPane>
        </ElTabs>
      </section>
    </template>
  </section>
</template>

<style scoped>
.compute-settings { max-width: 1120px; }
.task-settings { margin-top: 24px; }
.task-settings :deep(.hardware-settings), .task-settings :deep(#audio-models) { margin-top: 0 !important; }
.task-empty { padding: 28px; background: var(--sv-surface-1); border: 1px solid var(--sv-border-default); border-radius: var(--sv-radius-lg); }
.task-empty h2 { margin: 0; font-size: 16px; }
.task-empty p { margin: 8px 0 0; color: var(--sv-text-secondary); font-size: 13px; line-height: 1.8; }
.sr-only { position: absolute; width: 1px; height: 1px; padding: 0; margin: -1px; overflow: hidden; clip-path: inset(50%); white-space: nowrap; border: 0; }
.compute-heading { margin-bottom: 24px; }


.connection-editor { padding: 0 8px; }
.connection-drawer :deep(.el-drawer__header) { padding: 28px; margin-bottom: 0; border-bottom: 1px solid var(--sv-border-subtle); }
.connection-drawer :deep(.el-drawer__body) { padding: 28px 20px; }
.connection-drawer :deep(.el-drawer__footer) { padding: 18px 28px; border-top: 1px solid var(--sv-border-subtle); }
.action-spacer { flex: 1; }
.connection-card { display: flex; align-items: center; gap: 20px; padding: 18px 20px; background: var(--sv-surface-1); border: 1px solid var(--sv-border-default); border-radius: 10px; }
.connection-copy { flex: 1; min-width: 0; }
.connection-title { display: flex; align-items: center; flex-wrap: wrap; gap: 10px; }
.connection-title h2 { margin: 0; font-size: 15px; line-height: 1.6; }
.connection-address { display: block; margin-top: 6px; overflow-wrap: anywhere; font: 12px/1.6 var(--sv-font-mono); color: var(--sv-text-secondary); }
.connection-time { color: var(--sv-text-secondary); font-size: 12px; }
.connection-error { margin: 8px 0 0; font-size: 13px; color: var(--sv-semantic-warn); }
.connection-actions { display: flex; align-items: center; flex-wrap: wrap; gap: 8px; flex-shrink: 0; }
@media (max-width: 720px) { .connection-card { flex-direction: column; align-items: stretch; gap: 14px; } }
.connection-actions .el-button, .editor-actions .el-button { margin: 0; }
.saved-note { display: flex; gap: 4px; align-items: center; color: var(--sv-accent-primary); font-size: 12px; }
.saved-note svg { width: 14px; }

.editor-heading { max-width: 640px; display: flex; align-items: center; justify-content: space-between; gap: 12px; margin: 0; width: 100%; }
.editor-heading h2 { margin: 0; font-size: 16px; scroll-margin-top: 100px; }
.editor-heading p { margin: 6px 0 0; font-size: 12px; color: var(--sv-text-secondary); }
.draft-note { color: var(--sv-text-secondary); font-size: 11px; }
.connection-controls { max-width: 640px; display: flex; align-items: center; flex-wrap: wrap; gap: 20px; margin-bottom: 18px; }
.enable-node { width: 100%; display: flex; justify-content: space-between; align-items: center; gap: 8px; font-size: 14px; font-weight: 500; }
.disabled-note { margin: 0; font-size: 13px; color: var(--sv-text-secondary); }
input[type=radio], input[type=checkbox] { accent-color: var(--sv-accent-primary); flex-shrink: 0; }
.manual-settings { max-width: 640px; }
.node-field { display: grid; gap: 10px; font-size: 14px; margin: 28px 0; }
.node-field :deep(.el-input__wrapper) { min-height: 40px; }
.credential-help { margin-bottom: 8px; font-size: 12px; }
.credential-warning { color: var(--sv-semantic-warn) !important; }
.node-field > span { display: flex; align-items: center; gap: 8px; }
.node-field small, .field-warning { font-size: 12px; line-height: 1.7; color: var(--sv-text-secondary); }
.node-paths { border-top: 1px solid var(--sv-border-subtle); padding-top: 16px; margin-top: 16px; }
summary { font-size: 12px; cursor: pointer; line-height: 1.8; }
summary span { color: var(--sv-text-secondary); margin-left: 8px; font-size: 11px; }
.setup-help p, .node-paths p { font-size: 12px; line-height: 1.8; color: var(--sv-text-secondary); }
.setup-help code { display: block; background: var(--sv-surface-3); border: 1px solid var(--sv-border-subtle); border-radius: 8px; padding: 12px; font: 11px/1.8 var(--sv-font-mono); overflow-wrap: anywhere; white-space: normal; user-select: all; }
.node-mapping { display: grid; grid-template-columns: minmax(0,1fr) auto minmax(0,1fr) 34px; gap: 8px; align-items: end; margin: 16px 0; }
.node-mapping label { display: grid; gap: 6px; color: var(--sv-text-secondary); font-size: 11px; }
.node-mapping > span { align-self: center; padding-top: 16px; color: var(--sv-text-secondary); }
.node-mapping .el-button { padding: 8px; margin: 0; }
.node-clear-token { display: flex; align-items: center; gap: 8px; margin-top: 18px; color: var(--sv-text-secondary); font-size: 12px; }
.editor-feedback { margin-top: 20px; }
.draft-result { background: var(--sv-surface-3); padding: 14px 16px; border-radius: 10px; border: 1px solid var(--sv-border-default); font-size: 13px; }
.draft-result.success { background: var(--sv-accent-soft); }
.draft-result p { color: var(--sv-text-secondary); margin: 6px 0 0; font-size: 12px; line-height: 1.8; }
.draft-result .el-button { margin-top: 6px; padding-left: 0; }
.editor-actions { max-width: 640px; display: flex; flex-wrap: wrap; align-items: center; gap: 10px; margin: 0; padding: 0; }
.editor-actions small { margin-left: auto; color: var(--sv-text-secondary); font-size: 11px; }
@media(max-width: 720px) {
  .connection-card { padding: 22px; }
  .editor-actions small { width: 100%; margin: 2px 0 0; }
}
@media(max-width: 480px) {
  
  .connection-card { padding: 18px; }
  .node-mapping { grid-template-columns: minmax(0,1fr) 34px; }
  .node-mapping > span { display: none; }
  .node-mapping label { grid-column: 1; }
  .node-mapping .el-button { grid-column: 2; grid-row: 1/3; align-self: center; }
}
</style>
