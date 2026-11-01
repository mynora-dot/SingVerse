<script setup lang="ts">
import PageHeader from '../../../shared/ui/PageHeader.vue'
import { computed, nextTick, onBeforeUnmount, onMounted, reactive, ref, watch } from 'vue'
import { onBeforeRouteLeave } from 'vue-router'
import { ElAlert, ElButton, ElInput, ElMessageBox, ElSkeleton, ElTag } from 'element-plus'
import { Connection, RefreshRight, Plus, Delete, Check, ArrowRight, Warning } from '@element-plus/icons-vue'
import HardwareSettings from '../HardwareSettings.vue'
import { computeNode } from '../api'
import type { NodeSettings, NodeInput, NodeCheck, NodeMode } from '../types'

const settings = ref<NodeSettings | null>(null)
const editorHeading = ref<HTMLElement | null>(null)
const currentCheck = ref<NodeCheck | null>(null), draftCheck = ref<NodeCheck | null>(null)
const loading = ref(false), checking = ref(false), testing = ref(false), saving = ref(false), editing = ref(false)
const error = ref(''), currentError = ref(''), formError = ref(''), saved = ref(false)
const draft = reactive<NodeInput>({ mode: 'AUTO', endpoint: '', token: '', clearToken: false, mappings: [], revision: 0 })
const modes = [
  { id: 'AUTO', title: '使用随应用部署的服务', short: '随应用部署', description: '自动连接，无需填写' },
  { id: 'MANUAL', title: '连接其他设备', short: '其他设备', description: '填写已部署服务的地址' },
  { id: 'DISABLED', title: '暂不使用', short: '未启用', description: '扫描时仅使用文件名' },
] as const
let active = true, currentGeneration = 0, draftGeneration = 0, loadGeneration = 0
watch(draft, () => { draftCheck.value = null; formError.value = ''; draftGeneration++ }, { deep: true, flush: 'sync' })
const busy = computed(() => saving.value || testing.value)
const dirty = computed(() => !!settings.value && (draft.mode !== settings.value.mode || draft.endpoint !== settings.value.endpoint || !!draft.token || draft.clearToken || JSON.stringify(draft.mappings) !== JSON.stringify(settings.value.mappings)))
const endpointChanged = computed(() => !!settings.value && draft.endpoint.trim().replace(/\/$/, '') !== settings.value.endpoint)
const canKeepToken = computed(() => !!settings.value?.hasToken && !endpointChanged.value)
const connected = computed(() => currentCheck.value?.status === 'AVAILABLE')
const effectiveEndpoint = computed(() => settings.value?.mode === 'AUTO' ? settings.value.automaticEndpoint : settings.value?.endpoint)
const currentMode = computed(() => modes.find(mode => mode.id === settings.value?.mode)?.short)
const headline = computed(() => {
  if (settings.value?.mode === 'DISABLED') return '暂未启用计算服务'
  if (checking.value) return '正在检查连接…'
  if (connected.value) return currentCheck.value?.capabilities.length ? '计算服务已就绪' : '已连接，暂无可用功能'
  if (settings.value?.mode === 'AUTO' && !settings.value.automaticEndpoint) return '还没有连接计算服务'
  if (currentCheck.value?.status === 'MISSING_TOKEN') return '需要连接密钥'
  return '计算服务暂不可用'
})
const explanation = computed(() => {
  if (settings.value?.mode === 'DISABLED') return '扫描仍可使用文件名整理歌曲，已有歌曲信息不受影响。'
  if (checking.value) return '正在确认服务能否连接，以及可以使用哪些功能。'
  if (connected.value) return settings.value?.mode === 'AUTO' ? '已自动连接随应用部署的服务，可以直接使用，无需额外设置。' : '已连接你指定的设备，后续扫描会使用此服务。'
  if (!effectiveEndpoint.value) return '连接后，扫描本地歌曲时可以读取音频和视频文件中的歌曲信息。'
  return currentError.value || currentCheck.value?.message || '请检查服务是否已启动，然后重新检查连接。'
})
const nextAction = computed(() => settings.value?.mode === 'DISABLED' ? '启用计算服务' : connected.value ? '更换连接方式' : effectiveEndpoint.value ? '检查连接设置' : '连接其他设备')
const canSaveOffline = computed(() => draftCheck.value && draftCheck.value.status !== 'AVAILABLE' && draft.mode !== 'DISABLED' && !draft.clearToken)
function snapshot(): NodeInput { return { ...draft, mappings: draft.mappings.map(mapping => ({ ...mapping })) } }
function populate(value: NodeSettings) {
  Object.assign(draft, { mode: value.mode, endpoint: value.endpoint, token: '', clearToken: false, mappings: value.mappings.map(mapping => ({ ...mapping })), revision: value.revision })
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
  currentCheck.value = null; currentError.value = ''
  if (value.mode === 'DISABLED') { checking.value = false; return }
  if (value.mode === 'MANUAL' && !value.hasToken) {
    currentCheck.value = { status: 'MISSING_TOKEN', message: '请填写计算服务的连接密钥后再使用。', capabilities: [], elapsedMs: 0 }; return
  }
  checking.value = true
  try {
    const result = await computeNode.test({ mode: value.mode, endpoint: value.endpoint, token: '', clearToken: false, mappings: value.mappings, revision: value.revision })
    if (active && sequence === currentGeneration) currentCheck.value = result
  } catch (failure) { if (active && sequence === currentGeneration) currentError.value = message(failure, '连接检查失败，请重试。') }
  finally { if (active && sequence === currentGeneration) checking.value = false }
}
async function openEditor(mode?: NodeMode) {
  if (!settings.value) return
  populate(settings.value); draft.mode = mode ?? (settings.value.mode === 'DISABLED' ? (settings.value.automaticEndpoint ? 'AUTO' : 'MANUAL') : !effectiveEndpoint.value ? 'MANUAL' : settings.value.mode)
  editing.value = true; saved.value = false
  await nextTick()
  if (active) { editorHeading.value?.focus({ preventScroll: true }); editorHeading.value?.scrollIntoView?.({ block: 'start' }) }
}
function cancel() { if (settings.value) populate(settings.value); editing.value = false }
function validate() {
  if (draft.mode !== 'MANUAL') return true
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
    settings.value = value; populate(value); editing.value = false; saved.value = true; void checkCurrent()
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
    <PageHeader class="compute-heading" title="性能节点配置" description="连接计算服务，用于读取歌曲内嵌信息、视频转码与硬件加速。不使用时可关闭，不影响曲库管理。" />
    <ElAlert v-if="error" :title="error" type="error" :closable="false" show-icon><ElButton text @click="load">重新读取</ElButton></ElAlert>
    <ElSkeleton v-if="loading && !settings" :rows="5" animated />
    <template v-if="settings">
      <section class="connection-card" :class="{ ready: connected, inactive: settings.mode === 'DISABLED' }" aria-label="当前计算服务">
        <div class="connection-summary">
          <span class="connection-emblem"><Connection v-if="checking || connected || settings.mode === 'DISABLED'" /><Warning v-else /></span>
          <div class="connection-copy"><div class="connection-eyebrow">当前连接 <span>{{ currentMode }}</span></div><h2 aria-live="polite">{{ headline }}</h2><p>{{ explanation }}</p></div>
          <ElTag v-if="connected && !checking" type="success" effect="light">已连接</ElTag>
        </div>
        <div v-if="effectiveEndpoint && settings.mode !== 'DISABLED'" class="connection-address"><span>服务地址</span><code>{{ effectiveEndpoint }}</code><span v-if="connected" class="connection-time">{{ currentCheck?.elapsedMs }} ms</span></div>
        <div class="connection-actions"><ElButton v-if="!editing" :type="connected ? 'default' : 'primary'" :icon="connected ? undefined : ArrowRight" :disabled="checking" @click="openEditor()">{{ nextAction }}</ElButton><ElButton v-if="settings.mode !== 'DISABLED'" text :icon="RefreshRight" :loading="checking" :disabled="loading || busy" @click="checkCurrent">重新检查</ElButton><span v-if="saved" role="status" class="saved-note"><Check />配置已保存</span></div>
        <div class="connection-purpose"><div><span class="purpose-icon">♪</span><div><h3>扫描时读取文件标签</h3><p>使用文件中的歌名、歌手、专辑等信息，减少手动整理。</p></div></div><ElTag v-if="connected && currentCheck?.capabilities.includes('media-tags')" type="success" effect="plain">可使用</ElTag><span v-else class="purpose-pending">{{ checking ? '检查中' : connected ? '服务尚未提供此能力' : '连接可用后使用' }}</span></div>
      </section>

      <HardwareSettings :key="`${settings.mode}:${settings.revision}`" />
      <section v-if="editing" class="connection-editor" aria-label="连接设置">
        <div class="editor-heading"><div><h2 ref="editorHeading" tabindex="-1">连接设置</h2><p>选择计算服务所在的位置。</p></div><span v-if="dirty" class="draft-note">尚未保存</span></div>
        <form @submit.prevent="save()">
          <fieldset class="connection-options" :disabled="busy"><legend class="sr-only">连接方式</legend><label v-for="mode in modes" :key="mode.id" :class="{ selected: draft.mode === mode.id }"><input v-model="draft.mode" type="radio" name="node-mode" :value="mode.id"><span><strong>{{ mode.title }}</strong><small>{{ mode.description }}</small></span></label></fieldset>
          <div v-if="draft.mode === 'AUTO'" class="mode-explanation"><strong>{{ settings.automaticEndpoint ? '已找到随应用部署的服务' : '未找到随应用部署的服务' }}</strong><code v-if="settings.automaticEndpoint">{{ settings.automaticEndpoint }}</code><p>{{ settings.automaticEndpoint ? '地址和连接密钥已由部署配置提供，连接后即可使用。' : '如果你在另一台设备部署了计算服务，请选择“连接其他设备”。' }}</p><span v-if="settings.automaticEndpoint && !settings.automaticHasToken" class="field-warning">部署的连接密钥尚未就绪，请确认计算服务已启动。</span></div>
          <div v-else-if="draft.mode === 'MANUAL'" class="manual-settings">
            <label class="node-field"><span>计算服务地址</span><ElInput v-model="draft.endpoint" aria-label="计算服务地址" placeholder="例如 http://192.168.1.20:18090" maxlength="512" :disabled="busy" /><small>填写 SingVerse 主服务可以访问的地址。内网可用 HTTP，公网需使用 HTTPS。</small></label>
            <label class="node-field"><span>连接密钥 <ElTag v-if="canKeepToken" size="small" effect="plain">已保存</ElTag></span><ElInput v-model="draft.token" aria-label="计算节点认证凭据" type="password" autocomplete="new-password" :placeholder="canKeepToken ? '留空保留已保存密钥' : '填写计算服务的连接密钥'" maxlength="256" show-password :disabled="busy || draft.clearToken" /><small>{{ endpointChanged && settings.endpoint ? '服务地址已改变，需重新填写密钥。' : '密钥用于确认连接权限，保存后加密存储，不回显原文。' }}</small></label>
            <details class="setup-help"><summary>在哪里获取地址和密钥？</summary><p>先在目标设备部署 singverse-compute。使用该设备的内网地址和发布端口；独立 Compose 默认端口为 18090。</p><p>在计算设备上读取连接密钥，然后填写到上方：</p><code>docker compose -f compose.compute.yaml exec -T singverse-compute cat /run/singverse-compute/token</code><p>计算服务与主服务需要能读取同一份媒体文件。</p></details>
            <details class="node-paths"><summary>高级设置 <span>{{ draft.mappings.length ? `${draft.mappings.length} 条目录映射` : '目录路径不同或需要清除密钥时使用' }}</span></summary><p>两端媒体目录路径相同，无需设置。路径不同则添加对应关系，例如主服务 /mnt/music 对应计算设备 /media；此设置不会上传文件。</p><div v-for="(mapping, index) in draft.mappings" :key="index" class="node-mapping"><label>主服务的媒体目录<ElInput v-model="mapping.localRoot" :aria-label="`本机媒体路径 ${index + 1}`" placeholder="/mnt/music" :disabled="busy" /></label><span aria-hidden="true">→</span><label>计算设备的媒体目录<ElInput v-model="mapping.remoteRoot" :aria-label="`节点媒体路径 ${index + 1}`" placeholder="/media" :disabled="busy" /></label><ElButton :icon="Delete" :aria-label="`移除路径映射 ${index + 1}`" :disabled="busy" @click="draft.mappings.splice(index, 1)" /></div><ElButton :icon="Plus" :disabled="busy || draft.mappings.length >= 16" @click="draft.mappings.push({ localRoot: '', remoteRoot: '' })">添加目录映射</ElButton><label v-if="canKeepToken" class="node-clear-token"><input v-model="draft.clearToken" type="checkbox" :disabled="busy" @change="draft.token = ''">清除已保存密钥，暂停使用此连接</label></details>
          </div>
          <div v-else class="mode-explanation"><strong>扫描时只使用文件名整理信息</strong><p>已有歌曲信息不会删除。重新启用后，再次扫描即可尝试读取文件标签。</p></div>
          <ElAlert v-if="formError" class="editor-feedback" :title="formError" type="error" :closable="false" show-icon><ElButton v-if="formError.includes('配置已被修改')" text :disabled="busy" @click="load">放弃草稿，读取最新配置</ElButton></ElAlert>
          <div v-if="draftCheck" class="draft-result editor-feedback" :class="{ success: draftCheck.status === 'AVAILABLE' }" role="status"><strong>{{ draftCheck.status === 'AVAILABLE' ? '连接测试通过' : '连接尚未成功，当前连接保持不变' }}</strong><p>{{ draftCheck.message }}<span v-if="draftCheck.status === 'AVAILABLE'"> · {{ draftCheck.elapsedMs }} ms</span></p><ElButton v-if="canSaveOffline" text :disabled="busy" @click="save(true)">仅保存配置，稍后连接</ElButton></div>
          <footer class="editor-actions"><ElButton type="primary" native-type="submit" :loading="busy" :disabled="!dirty">{{ draft.mode === 'DISABLED' ? '停用计算服务' : draft.clearToken ? '保存设置' : '连接并使用' }}</ElButton><ElButton v-if="draft.mode !== 'DISABLED' && !draft.clearToken" :disabled="busy" @click="testDraft">仅测试连接</ElButton><ElButton text :disabled="busy" @click="cancel">取消</ElButton><small>{{ draft.mode === 'DISABLED' || draft.clearToken ? '保存后对新扫描生效。' : '连接检查通过后保存，对新扫描生效。' }}</small></footer>
        </form>
      </section>
      <p class="connection-footnote">计算服务只负责处理文件，歌曲信息由 SingVerse 保存。服务离线时，仍可使用文件名扫描。</p>
    </template>
  </section>
</template>

<style scoped>
.compute-settings { max-width: 1040px; }
.sr-only { position: absolute; width: 1px; height: 1px; padding: 0; margin: -1px; overflow: hidden; clip-path: inset(50%); white-space: nowrap; border: 0; }
.compute-heading { margin-bottom: 24px; }


.connection-card, .connection-editor { padding: 28px; background: var(--sv-surface-1); border: 1px solid var(--sv-border-default); border-radius: 16px; }
.connection-card { border-top: 3px solid var(--sv-border-default); }
.connection-card.ready { border-top-color: var(--sv-accent-primary); }
.connection-summary { display: flex; align-items: flex-start; gap: 16px; }
.connection-emblem { display: grid; place-items: center; flex: 0 0 48px; height: 48px; border-radius: 14px; background: var(--sv-accent-soft); color: var(--sv-accent-primary); }
.connection-emblem svg { width: 25px; }
.connection-copy { min-width: 0; flex: 1; }
.connection-eyebrow { display: flex; align-items: center; gap: 10px; color: var(--sv-text-secondary); font-size: 11px; margin-bottom: 8px; }
.connection-eyebrow span { padding-left: 10px; border-left: 1px solid var(--sv-border-default); }
.connection-copy h2 { font-size: 22px; line-height: 1.4; margin: 0; }
.connection-copy p { font-size: 13px; line-height: 1.8; margin: 8px 0 0; color: var(--sv-text-secondary); }
.connection-summary > .el-tag { margin-top: 26px; flex-shrink: 0; }
.connection-address { display: flex; gap: 12px; align-items: baseline; margin: 20px 0 0 64px; font-size: 11px; color: var(--sv-text-secondary); }
.connection-address code { min-width: 0; overflow-wrap: anywhere; font: 12px var(--sv-font-mono); color: var(--sv-text-primary); }
.connection-address > span:first-child { flex-shrink: 0; }
.connection-time { flex-shrink: 0; margin-left: auto; }
.connection-actions { display: flex; align-items: center; flex-wrap: wrap; gap: 8px; margin: 20px 0 24px 64px; }
.connection-actions .el-button, .editor-actions .el-button { margin: 0; }
.saved-note { display: flex; gap: 4px; align-items: center; color: var(--sv-accent-primary); font-size: 12px; }
.saved-note svg { width: 14px; }
.connection-purpose { display: flex; align-items: center; justify-content: space-between; gap: 16px; padding-top: 22px; border-top: 1px solid var(--sv-border-subtle); }
.connection-purpose > div { display: flex; align-items: center; gap: 12px; }
.purpose-icon { display: grid; place-items: center; background: var(--sv-surface-3); color: var(--sv-text-secondary); border-radius: 9px; width: 34px; height: 38px; font-size: 22px; flex-shrink: 0; }
.connection-purpose h3 { margin: 0 0 5px; font-size: 13px; }
.connection-purpose p, .purpose-pending { margin: 0; font-size: 12px; color: var(--sv-text-secondary); line-height: 1.7; }
.purpose-pending { flex-shrink: 0; }
.connection-editor { margin-top: 20px; }
.editor-heading { display: flex; align-items: center; justify-content: space-between; gap: 12px; margin-bottom: 20px; }
.editor-heading h2 { margin: 0; font-size: 16px; scroll-margin-top: 100px; }
.editor-heading p { margin: 6px 0 0; font-size: 12px; color: var(--sv-text-secondary); }
.draft-note { color: var(--sv-text-secondary); font-size: 11px; }
.connection-options { display: grid; grid-template-columns: repeat(3, minmax(0, 1fr)); gap: 10px; border: 0; margin: 0 0 22px; padding: 0; }
.connection-options label { display: flex; align-items: center; gap: 10px; border: 1px solid var(--sv-border-default); border-radius: 10px; padding: 14px 12px; cursor: pointer; }
.connection-options label.selected { background: var(--sv-accent-soft); border-color: var(--sv-accent-primary); }
.connection-options strong { display: block; font-size: 12px; line-height: 1.5; }
.connection-options small { display: block; color: var(--sv-text-secondary); font-size: 11px; line-height: 1.6; margin-top: 4px; }
input[type=radio], input[type=checkbox] { accent-color: var(--sv-accent-primary); flex-shrink: 0; }
.mode-explanation { background: var(--sv-surface-3); border-radius: 10px; padding: 16px 18px; font-size: 13px; }
.mode-explanation code { display: block; margin-top: 10px; font: 12px var(--sv-font-mono); overflow-wrap: anywhere; }
.mode-explanation p { color: var(--sv-text-secondary); font-size: 12px; line-height: 1.8; margin: 8px 0 0; }
.manual-settings { max-width: 720px; }
.node-field { display: grid; gap: 8px; font-size: 13px; margin: 20px 0; }
.node-field > span { display: flex; align-items: center; gap: 8px; }
.node-field small, .field-warning { font-size: 12px; line-height: 1.7; color: var(--sv-text-secondary); }
.setup-help, .node-paths { border-top: 1px solid var(--sv-border-subtle); padding-top: 16px; margin-top: 16px; }
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
.editor-actions { display: flex; flex-wrap: wrap; align-items: center; gap: 10px; margin-top: 24px; padding-top: 20px; border-top: 1px solid var(--sv-border-subtle); }
.editor-actions small { margin-left: auto; color: var(--sv-text-secondary); font-size: 11px; }
.connection-footnote { margin: 16px 4px 0; color: var(--sv-text-secondary); font-size: 12px; line-height: 1.8; }
@media(max-width: 720px) {
  .connection-card, .connection-editor { padding: 22px; }
  .connection-options { grid-template-columns: minmax(0,1fr); }
  .connection-options label { padding: 12px; }
  .connection-options label > span { display: flex; gap: 10px; flex-wrap: wrap; align-items: baseline; }
  .connection-options small { margin: 0; }
  .editor-actions small { width: 100%; margin: 2px 0 0; }
}
@media(max-width: 480px) {
  
  .connection-card, .connection-editor { padding: 18px; }
  .connection-summary { gap: 12px; flex-wrap: wrap; }
  .connection-emblem { flex-basis: 38px; height: 38px; border-radius: 10px; }
  .connection-emblem svg { width: 21px; }
  .connection-copy h2 { font-size: 19px; }
  .connection-summary > .el-tag { display: none; }
  .connection-address { margin-left: 0; flex-wrap: wrap; gap: 6px 10px; }
  .connection-address code { flex-basis: calc(100% - 60px); }
  .connection-time { margin-left: 0; }
  .connection-actions { margin-left: 0; }
  .connection-purpose { flex-wrap: wrap; gap: 10px; }
  .connection-purpose > .el-tag, .purpose-pending { margin-left: 46px; }
  .connection-purpose h3 { font-size: 12px; }
  .connection-purpose p { font-size: 11px; }
  .node-mapping { grid-template-columns: minmax(0,1fr) 34px; }
  .node-mapping > span { display: none; }
  .node-mapping label { grid-column: 1; }
  .node-mapping .el-button { grid-column: 2; grid-row: 1/3; align-self: center; }
}
</style>
