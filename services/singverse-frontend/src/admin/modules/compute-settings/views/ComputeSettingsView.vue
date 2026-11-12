<script setup lang="ts">
import PageHeader from '../../../shared/ui/PageHeader.vue'
import { computed, nextTick, onBeforeUnmount, onMounted, reactive, ref, watch } from 'vue'
import { onBeforeRouteLeave, useRoute, useRouter } from 'vue-router'
import { ElAlert, ElButton, ElInput, ElMessageBox, ElSkeleton, ElTag, ElTabs, ElTabPane, ElDialog, ElSwitch, ElDrawer } from 'element-plus'
import { RefreshRight, Check, ArrowRight } from '@element-plus/icons-vue'
import AudioModelSettings from '../AudioModelSettings.vue'
import HardwareSettings from '../HardwareSettings.vue'
import { ApiError } from '../../../core/auth/api'
import { computeNode } from '../api'
import type { NodeSettings, NodeInput, NodeCheck, NodeMode, DiscoveredNode, NodeDiscoveryInput } from '../types'

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
const scanning = ref(false), pairing = ref(false), scanCompleted = ref(false), discoveryConflict = ref(false)
const scanError = ref(''), scanMessage = ref(''), pairingError = ref(''), discovered = ref<DiscoveredNode[]>([])
const scanRange = reactive({ network: '', port: '' as string | number })
const draft = reactive<NodeInput>({ mode: 'AUTO', endpoint: '', token: '', clearToken: false, mappings: [], revision: 0 })
let active = true, currentGeneration = 0, draftGeneration = 0, loadGeneration = 0, scanGeneration = 0
watch(draft, () => { draftCheck.value = null; formError.value = ''; draftGeneration++ }, { deep: true, flush: 'sync' })
watch(scanRange, () => { scanError.value = '' })
const busy = computed(() => saving.value || testing.value || pairing.value)
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
    settings.value = value; populate(value); resetDiscovery(); void checkCurrent()
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
  resetDiscovery(); helpVisible.value = false; editing.value = true; saved.value = false
  await nextTick()
  if (active) { editorHeading.value?.focus({ preventScroll: true }); editorHeading.value?.scrollIntoView?.({ block: 'start' }) }
}
function closeEditor(done: () => void) { if (!busy.value) { cancel(); done() } }
function cancel() { if (busy.value) return; resetDiscovery(); if (settings.value) populate(settings.value); editing.value = false }
function cancelScan() { scanGeneration++; scanning.value = false; scanMessage.value = '已取消等待扫描结果，可重新扫描或手动连接。' }
function resetDiscovery() {
  scanGeneration++; scanning.value = false; scanCompleted.value = false; scanError.value = ''; scanMessage.value = ''
  pairingError.value = ''; discovered.value = []; discoveryConflict.value = false
}
function discoveryInput(): NodeDiscoveryInput | null {
  const network = scanRange.network.trim(), port = Number(scanRange.port)
  if (network) {
    const match = /^(\d{1,3})\.(\d{1,3})\.(\d{1,3})\.(\d{1,3})\/(\d{2})$/.exec(network)
    const parts = match?.slice(1, 5).map(Number) ?? [], prefix = Number(match?.[5])
    const privateAddress = parts[0] === 10 || parts[0] === 172 && parts[1]! >= 16 && parts[1]! <= 31 || parts[0] === 192 && parts[1] === 168
    if (!match || parts.some(part => part > 255) || prefix < 24 || prefix > 32 || !privateAddress || parts[3]! % 2 ** (32 - prefix) !== 0) {
      scanError.value = '请填写局域网 IPv4 网段（/24～/32），例如 192.168.1.0/24。'; return null
    }
  }
  if (scanRange.port !== '' && (!Number.isInteger(port) || port < 1 || port > 65535)) {
    scanError.value = '扫描端口应为 1～65535 的整数。'; return null
  }
  return { ...(network ? { network } : {}), ...(scanRange.port !== '' ? { port } : {}) }
}
async function scan() {
  if (busy.value || scanning.value || !enabled.value) return
  scanError.value = ''
  const input = discoveryInput()
  if (!input) return
  const sequence = ++scanGeneration
  scanning.value = true; scanCompleted.value = false; scanMessage.value = ''; discovered.value = []; pairingError.value = ''
  try {
    const value = await computeNode.discover(input)
    if (!active || !editing.value || sequence !== scanGeneration) return
    discovered.value = value.items; scanMessage.value = value.message; scanCompleted.value = true; discoveryConflict.value = false
  } catch (failure) { if (active && editing.value && sequence === scanGeneration) scanError.value = message(failure, '扫描失败，请重试或手动填写地址。') }
  finally { if (active && sequence === scanGeneration) scanning.value = false }
}
async function connectDiscovered(node: DiscoveredNode) {
  if (busy.value || scanning.value || !node.pairingAvailable || !settings.value) return
  if (dirty.value) {
    try { await ElMessageBox.confirm('连接此节点会替换当前连接，并放弃尚未保存的手动设置。配对失败时保留原连接与草稿。', '连接此节点？', { confirmButtonText: '连接此节点', cancelButtonText: '继续编辑', type: 'warning', autofocus: false }) }
    catch { return }
  }
  if (!active || !editing.value || busy.value || !settings.value) return
  pairing.value = true; pairingError.value = ''; discoveryConflict.value = false
  try {
    const value = await computeNode.pair(node.id, settings.value.revision)
    if (!active) return
    settings.value = value; populate(value); resetDiscovery(); currentCheck.value = null; editing.value = false; saved.value = true; void checkCurrent()
  } catch (failure) {
    if (active) { pairingError.value = message(failure, '连接此节点失败，当前连接保持不变。'); discoveryConflict.value = failure instanceof ApiError && failure.status === 409 }
  } finally { if (active) pairing.value = false }
}
function validate() {
  if (draft.mode === 'DISABLED') return true
  if (usesDeployment.value) return true
  if (!draft.endpoint.trim()) { formError.value = '请填写计算服务地址。'; return false }
  if (!draft.clearToken && !draft.token.trim() && !canKeepToken.value) { formError.value = '请填写连接密钥，可从计算服务的 token 文件获取。'; return false }
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
    settings.value = value; populate(value); resetDiscovery(); currentCheck.value = null; editing.value = false; saved.value = true; void checkCurrent()
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
onBeforeUnmount(() => { active = false; currentGeneration++; draftGeneration++; loadGeneration++; scanGeneration++ })
</script>

<template>
  <section class="compute-settings">
    <PageHeader title="计算与模型" description="让 NAS 处理音轨识别、伴奏和兼容播放。先连接计算服务，再按需要准备硬件与模型。" />
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

          <div class="connection-controls">
            <div class="enable-node"><span>启用计算服务</span><ElSwitch v-model="enabled" aria-label="启用计算服务" :disabled="busy" /></div>
          </div>
          <section v-if="enabled" class="node-discovery" aria-label="局域网计算节点">
            <div class="discovery-heading"><div><h3>从局域网连接</h3><p>由服务节点扫描局域网，选用支持配对的节点即可连接，无需手动填写密钥。</p></div></div>
            <details class="discovery-range">
              <summary>指定扫描范围</summary>
              <p>留空时扫描服务节点所在网段的 18090 / 8090 端口。可填写其他局域网网段或自定义端口。</p>
              <label><span>IPv4 网段（可选）</span><ElInput v-model="scanRange.network" aria-label="扫描网段" placeholder="例如 192.168.1.0/24" maxlength="32" :disabled="busy || scanning" @keydown.enter.prevent="scan" /></label>
              <label><span>端口（可选）</span><ElInput v-model="scanRange.port" aria-label="扫描端口" inputmode="numeric" placeholder="例如 18090" maxlength="5" :disabled="busy || scanning" @keydown.enter.prevent="scan" /></label>
              <small>仅支持局域网 IPv4 网段 /24～/32，端口范围 1～65535。</small>
            </details>
            <div class="discovery-actions"><ElButton :loading="scanning" :disabled="busy" @click="scan">扫描局域网内计算节点</ElButton><ElButton v-if="scanning" text @click="cancelScan">取消扫描</ElButton></div>
            <p v-if="scanning" class="discovery-status" role="status">正在扫描，请稍候…扫描不会更改当前连接。</p>
            <ElAlert v-if="scanError" class="editor-feedback" :title="scanError" type="error" :closable="false" show-icon />
            <p v-if="scanMessage" class="discovery-status" role="status">{{ scanMessage }}</p>
            <p v-if="scanCompleted && !discovered.length" class="discovery-empty">未发现可连接的计算节点。请确认设备在局域网内，或指定网段与端口后重试，也可在下方手动连接。</p>
            <ul v-if="discovered.length" class="discovery-list" aria-label="扫描到的计算节点">
              <li v-for="node in discovered" :key="node.id">
                <div><strong>{{ node.name || '计算节点' }}</strong><code>{{ node.endpoint }}</code><p v-if="node.message">{{ node.message }}</p><small v-if="node.pairingAvailable">选用后会验证并保存此连接</small></div>
                <ElButton type="primary" plain :disabled="busy || scanning || !node.pairingAvailable || discoveryConflict" :aria-label="`连接${node.name || '计算节点'}（${node.endpoint}）`" @click="connectDiscovered(node)">连接此节点</ElButton>
              </li>
            </ul>
            <p v-if="pairing" class="discovery-status" role="status">正在配对并验证连接…</p>
            <ElAlert v-if="pairingError" class="editor-feedback" :title="pairingError" type="error" :closable="false" show-icon><ElButton v-if="discoveryConflict" text :disabled="busy" @click="load">放弃草稿，读取最新配置</ElButton></ElAlert>
          </section>
        <form id="compute-connection-form" @submit.prevent="save()">
          <div v-if="enabled" class="manual-settings">
            <label class="node-field"><span>计算服务地址</span><ElInput v-model="draft.endpoint" aria-label="计算服务地址" placeholder="例如 http://192.168.1.20:18090" maxlength="512" :disabled="busy" /></label>
            <label class="node-field"><span>连接密钥 <ElTag v-if="canKeepToken || hasDeploymentToken" size="small" effect="plain">已配置</ElTag></span><ElInput v-model="draft.token" aria-label="计算节点认证凭据" type="password" autocomplete="new-password" :placeholder="hasDeploymentToken ? '已使用部署密钥，留空保留' : canKeepToken ? '留空保留已保存密钥' : '填写计算服务的连接密钥'" maxlength="256" show-password :disabled="busy || draft.clearToken" /><small v-if="!hasDeploymentToken && !canKeepToken && endpointChanged && effectiveEndpoint" class="credential-warning">服务地址已改变，需重新填写密钥。</small></label>
            <ElButton class="credential-help" link type="primary" @click="helpVisible = true">如何获取连接信息？</ElButton>
            <p class="setup-help">原始曲库由业务服务管理，计算节点通过任务授权读取，无需挂载歌曲目录或配置路径映射。回源地址由业务服务随任务提供。</p>
            <label v-if="canKeepToken && !usesDeployment" class="node-clear-token"><input v-model="draft.clearToken" type="checkbox" :disabled="busy" @change="draft.token = ''">清除已保存密钥，暂停使用此连接</label>
          </div>
          <p v-else class="disabled-note">保存后停用计算服务，已有歌曲和生成成果保留。</p>
          <ElAlert v-if="formError" class="editor-feedback" :title="formError" type="error" :closable="false" show-icon><ElButton v-if="formError.includes('配置已被修改')" text :disabled="busy" @click="load">放弃草稿，读取最新配置</ElButton></ElAlert>
          <div v-if="draftCheck" class="draft-result editor-feedback" :class="{ success: draftCheck.status === 'AVAILABLE' }" role="status"><strong>{{ draftCheck.status === 'AVAILABLE' ? '连接测试通过' : '连接尚未成功，当前连接保持不变' }}</strong><p>{{ draftCheck.message }}<span v-if="draftCheck.status === 'AVAILABLE'"> · {{ draftCheck.elapsedMs }} ms</span></p><ElButton v-if="canSaveOffline" text :disabled="busy" @click="save(true)">仅保存配置，稍后连接</ElButton></div>
        </form>
        </section>
        <template #footer><div class="editor-actions"><ElButton v-if="draft.mode !== 'DISABLED' && !draft.clearToken" text :disabled="busy" @click="testDraft">仅测试连接</ElButton><span class="action-spacer" /><ElButton :disabled="busy" @click="cancel">取消</ElButton><ElButton type="primary" :loading="busy" :disabled="!dirty" @click="save()">保存设置</ElButton></div></template>
      </ElDrawer>
      <ElDialog v-model="helpVisible" title="获取连接信息" width="min(560px, 92vw)" append-to-body :close-on-click-modal="false">
        <div class="setup-help"><p>填写主服务可访问的计算设备地址。内网可用 HTTP，公网需用 HTTPS；独立部署默认端口为 18090。</p><p>在计算设备上执行以下命令获取密钥：</p><code>docker compose --project-directory . -f infra/docker/compose/compose.compute.yaml exec -T singverse-compute cat /run/singverse-compute/token</code></div>
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
.task-settings { margin-top: 20px; }
.task-settings :deep(.hardware-settings), .task-settings :deep(#audio-models) { margin-top: 0 !important; }
.task-empty { padding: 24px 0; border-bottom: 1px solid var(--sv-border-subtle); }
.task-empty h2 { margin: 0; font-size: 16px; }
.task-empty p { margin: 8px 0 0; color: var(--sv-text-secondary); font-size: 13px; line-height: 1.8; }
.sr-only { position: absolute; width: 1px; height: 1px; padding: 0; margin: -1px; overflow: hidden; clip-path: inset(50%); white-space: nowrap; border: 0; }


.connection-editor { padding: 0 8px; }
.connection-drawer :deep(.el-drawer__header) { padding: 28px; margin-bottom: 0; border-bottom: 1px solid var(--sv-border-subtle); }
.connection-drawer :deep(.el-drawer__body) { padding: 28px 20px; }
.connection-drawer :deep(.el-drawer__footer) { padding: 20px 28px; border-top: 1px solid var(--sv-border-subtle); }
.action-spacer { flex: 1; }
.connection-card { display: flex; align-items: center; gap: 20px; padding: 0 0 20px; border-bottom: 1px solid var(--sv-border-subtle); }
.connection-copy { flex: 1; min-width: 0; }
.connection-title { display: flex; align-items: center; flex-wrap: wrap; gap: 12px; }
.connection-title h2 { margin: 0; font-size: 15px; line-height: 1.6; }
.connection-address { display: block; margin-top: 8px; overflow-wrap: anywhere; font: 12px/1.6 var(--sv-font-mono); color: var(--sv-text-secondary); }
.connection-time { color: var(--sv-text-secondary); font-size: 12px; }
.connection-error { margin: 8px 0 0; font-size: 13px; color: var(--sv-semantic-warn); }
.connection-actions { display: flex; align-items: center; flex-wrap: wrap; gap: 8px; flex-shrink: 0; }
@media (max-width: 720px) { .connection-card { flex-direction: column; align-items: stretch; gap: 16px; } }
.connection-actions .el-button, .editor-actions .el-button { margin: 0; }
.saved-note { display: flex; gap: 4px; align-items: center; color: var(--sv-accent-primary); font-size: 12px; }
.saved-note svg { width: 14px; }

.editor-heading { max-width: 640px; display: flex; align-items: center; justify-content: space-between; gap: 12px; margin: 0; width: 100%; }
.editor-heading h2 { margin: 0; font-size: 16px; scroll-margin-top: 100px; }
.editor-heading p { margin: 8px 0 0; font-size: 12px; color: var(--sv-text-secondary); }
.draft-note { color: var(--sv-text-secondary); font-size: 12px; }
.connection-controls { max-width: 640px; display: flex; align-items: center; flex-wrap: wrap; gap: 20px; margin-bottom: 20px; }
.enable-node { width: 100%; display: flex; justify-content: space-between; align-items: center; gap: 8px; font-size: 14px; font-weight: 500; }
.disabled-note { margin: 0; font-size: 13px; color: var(--sv-text-secondary); }
input[type=radio], input[type=checkbox] { accent-color: var(--sv-accent-primary); flex-shrink: 0; }
.manual-settings { max-width: 640px; }
.node-discovery { padding: 20px; margin: 8px 0 28px; border: 1px solid var(--sv-border-subtle); border-radius: 8px; background: var(--sv-surface-2); }
.discovery-heading h3 { margin: 0; font-size: 14px; }
.discovery-heading p, .discovery-range p, .discovery-status, .discovery-empty { font-size: 12px; color: var(--sv-text-secondary); line-height: 1.8; margin: 8px 0; }
.discovery-range { margin: 16px 0; }
.discovery-range label { display: grid; gap: 8px; margin-top: 12px; font-size: 12px; }
.discovery-range > small { display: block; margin-top: 12px; color: var(--sv-text-secondary); font-size: 12px; line-height: 1.7; }
.discovery-actions { display: flex; gap: 8px; align-items: center; flex-wrap: wrap; }
.discovery-actions .el-button { margin: 0; }
.discovery-list { list-style: none; padding: 0; margin: 16px 0 0; }
.discovery-list li { display: flex; align-items: center; gap: 16px; padding: 16px 0; border-top: 1px solid var(--sv-border-subtle); }
.discovery-list li > div { min-width: 0; flex: 1; }
.discovery-list strong { display: block; font-size: 13px; }
.discovery-list code { display: block; overflow-wrap: anywhere; font: 12px/1.7 var(--sv-font-mono); color: var(--sv-text-secondary); margin-top: 4px; }
.discovery-list p, .discovery-list small { display: block; font-size: 12px; line-height: 1.7; color: var(--sv-text-secondary); margin: 4px 0 0; }
.discovery-list .el-button { flex: none; font-size: 12px; }
.node-field { display: grid; gap: 12px; font-size: 14px; margin: 28px 0; }
.node-field :deep(.el-input__wrapper) { min-height: 40px; }
.credential-help { margin-bottom: 8px; font-size: 12px; }
.credential-warning { color: var(--sv-semantic-warn) !important; }
.node-field > span { display: flex; align-items: center; gap: 8px; }
.node-field small, .field-warning { font-size: 12px; line-height: 1.7; color: var(--sv-text-secondary); }
.node-paths { border-top: 1px solid var(--sv-border-subtle); padding-top: 16px; margin-top: 16px; }
summary { font-size: 12px; cursor: pointer; line-height: 1.8; }
summary span { color: var(--sv-text-secondary); margin-left: 8px; font-size: 12px; }
.setup-help p, .node-paths p { font-size: 12px; line-height: 1.8; color: var(--sv-text-secondary); }
.setup-help code { display: block; background: var(--sv-surface-3); border: 1px solid var(--sv-border-subtle); border-radius: 8px; padding: 12px; font: 12px/1.8 var(--sv-font-mono); overflow-wrap: anywhere; white-space: normal; user-select: all; }
.node-mapping { display: grid; grid-template-columns: minmax(0,1fr) auto minmax(0,1fr) 34px; gap: 8px; align-items: end; margin: 16px 0; }
.node-mapping label { display: grid; gap: 8px; color: var(--sv-text-secondary); font-size: 12px; }
.node-mapping > span { align-self: center; padding-top: 16px; color: var(--sv-text-secondary); }
.node-mapping .el-button { padding: 8px; margin: 0; }
.node-clear-token { display: flex; align-items: center; gap: 8px; margin-top: 20px; color: var(--sv-text-secondary); font-size: 12px; }
.editor-feedback { margin-top: 20px; }
.draft-result { background: var(--sv-surface-3); padding: 16px 16px; border-radius: 8px; border: 1px solid var(--sv-border-default); font-size: 13px; }
.draft-result.success { background: var(--sv-accent-soft); }
.draft-result p { color: var(--sv-text-secondary); margin: 8px 0 0; font-size: 12px; line-height: 1.8; }
.draft-result .el-button { margin-top: 8px; padding-left: 0; }
.editor-actions { max-width: 640px; display: flex; flex-wrap: wrap; align-items: center; gap: 12px; margin: 0; padding: 0; }
.editor-actions small { margin-left: auto; color: var(--sv-text-secondary); font-size: 12px; }
@media(max-width: 720px) {
  .connection-card { padding: 0 0 20px; }
  .editor-actions small { width: 100%; margin: 2px 0 0; }
}
@media(max-width: 480px) {
  
  .connection-card { padding: 0 0 20px; }
  .node-mapping { grid-template-columns: minmax(0,1fr) 34px; }
  .node-mapping > span { display: none; }
  .node-mapping label { grid-column: 1; }
  .node-mapping .el-button { grid-column: 2; grid-row: 1/3; align-self: center; }
}

@media(max-width:768px) {
  .node-clear-token, .compute-settings :deep(.el-button), .compute-settings :deep(.el-input__wrapper), .compute-settings :deep(.el-input__inner), .compute-settings :deep(.el-switch), .compute-settings summary, .task-settings :deep(.el-tabs__item) { min-height:44px; }
  .compute-settings summary { align-content:center; }
}
</style>
