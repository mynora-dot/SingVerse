<script setup lang="ts">
import { computed, ref } from 'vue'
import { ElAlert, ElButton, ElTag } from 'element-plus'
import { authRequest } from '../../../core/auth/api'
import { base, importModel, type Model, type Node } from '../api'
import { backendName, backendStates, bytes, failure, modelNames, operationStates, type Readiness } from '../presentation'

const props = defineProps<{ node?: Node; nodeError: string; readiness: Readiness; reload: (refresh?: boolean) => Promise<void> }>()
const busy = ref(false), error = ref('')
const detected = computed(() => props.node?.backends.filter(item => !['cpu', 'xpu'].includes(item.id) && (item.detected || item.device)) || [])
const visibleBackends = computed(() => props.node?.backends.filter(item => item.id === 'cpu' ? !detected.value.length || props.node?.cpuFallback !== false : detected.value.some(found => found.id === item.id)) || [])
const catalog = computed<Model[]>(() => [
  ...(props.node?.fastClassifier ? [props.node.fastClassifier] : []),
  ...(props.node?.models || (props.node ? [{ ...props.node.model, id: props.node.model.id || 'htdemucs' }] : [])).filter(model => model.id === 'htdemucs-openvino' ? detected.value.some(item => item.id === 'openvino') : !detected.value.length || props.node?.cpuFallback !== false || detected.value.some(item => ['cuda', 'rocm', 'mps'].includes(item.id))),
])
const modelBusy = computed(() => ['DOWNLOADING', 'IMPORTING', 'VERIFYING', 'TESTING'].includes(props.node?.operation.state || ''))
const auto = computed(() => props.node?.selection === 'auto')
async function run(task: () => Promise<unknown>, refresh = false) {
  busy.value = true; error.value = ''
  try { await task(); await props.reload(refresh) } catch (failed) { error.value = failure(failed) } finally { busy.value = false }
}
async function setFallback(event: Event) {
  const input = event.target as HTMLInputElement, enabled = input.checked
  input.checked = props.node?.cpuFallback !== false
  await run(async () => {
    const current = await authRequest<{ backend: string; revision: number }>('/api/v1/transcoding/hardware-settings')
    await authRequest('/api/v1/transcoding/hardware-settings', 'POST', { backend: current.backend, revision: current.revision, cpuFallback: enabled }, 45000)
  })
}
const recheck = () => run(async () => undefined, true)
const call = (action: string, body?: unknown, suffix = '') => authRequest(`${base}/model/${action}${suffix}`, 'POST', body, 60000)
const download = (model: Model) => run(() => call('download', undefined, model.id === 'htdemucs' ? '' : '?model=' + encodeURIComponent(model.id)))
const backendAction = (action: 'test' | 'select', backend: string) => run(() => call(action, { backend }))
async function upload(model: Model, event: Event) {
  const input = event.target as HTMLInputElement, file = input.files?.[0]
  if (!file) return
  if (file.size > model.maxBytes) { error.value = '模型文件超过所选模型清单的大小限制'; input.value = ''; return }
  await run(() => importModel(file, model.id)); input.value = ''
}
function offlineFiles(model: Model) {
  if (model.files) return model.files.map(file => ({ name: file.name, url: file.url, sha256: file.sha256 }))
  return props.node ? [{ name: props.node.model.filename, url: props.node.model.downloadUrl, sha256: props.node.model.sha256 }] : []
}
function backendTone(state: string) { return state === 'AVAILABLE' ? 'success' : state === 'FAILED' ? 'danger' : state === 'UNVERIFIED' ? 'warning' : 'info' }
</script>

<template>
  <div class="node-tab">
    <section class="sv-panel overview" :class="readiness.ready ? 'is-ready' : 'is-pending'" aria-label="准备情况">
      <span class="sv-status-dot" :class="readiness.ready ? 'ok' : 'warn'" aria-hidden="true" />
      <div class="overview-copy"><h2>{{ readiness.title }}</h2><p>{{ readiness.ready ? '已经可以在“歌曲”中为单音轨歌曲生成独立伴奏。' : readiness.hint }}</p></div>
      <div class="overview-actions"><ElButton :loading="busy" @click="recheck">重新检测</ElButton></div>
    </section>
    <ElAlert v-if="error" :title="error" type="error" show-icon :closable="false" role="alert" />

    <ol v-if="node" class="steps">
      <li class="sv-panel step">
        <header><span class="step-no" aria-hidden="true">1</span><h3>准备模型</h3><ElTag :type="readiness.modelInstalled ? 'success' : 'warning'" effect="light" size="small">{{ readiness.modelInstalled ? '已安装' : '未安装' }}</ElTag></header>
        <p class="muted">下载一个适合当前设备的模型即可。已安装的模型可直接使用。</p>
        <p v-if="modelBusy || node.operation.state === 'FAILED'" class="operation" role="status">{{ operationStates[node.operation.state] || node.operation.state }} · 已处理 {{ (node.operation.bytes / 1024 ** 2).toFixed(1) }} MiB<ElButton v-if="modelBusy" link type="primary" :disabled="busy" @click="run(() => call('cancel'))">取消</ElButton></p>
        <p v-if="node.operation.error" class="inline-error">{{ node.operation.error }}</p>
        <div v-for="model in catalog" :key="model.id" class="model-row" :data-model="model.id">
          <div class="model-copy">
            <strong>{{ modelNames[model.id]?.name || model.id }}</strong><span v-if="model.id === 'panns-cnn6'" class="muted">扫描专用，只做短片段人声分类；不运行完整分离模型，不需要选择设备。{{ node.fastClassifier?.ready ? '已就绪' : '请下载模型并准备节点音频运行环境' }}</span>

            <span v-if="!model.writable" class="muted">模型目录未配置、不可写或为只读预置目录，无法在网页下载或导入；请在节点配置独立的可写模型卷。</span>
          </div>
          <ElTag :type="model.installed ? 'success' : 'info'" effect="light" size="small">{{ model.installed ? '已安装并校验' : '未安装' }}</ElTag>
          <div v-if="!model.installed" class="model-actions">
            <ElButton size="small" type="primary" :disabled="busy || modelBusy || !model.writable" @click="download(model)">下载到节点</ElButton>
            <label class="upload el-button el-button--small" :class="{ 'is-disabled': busy || modelBusy || !model.writable }">
              <input type="file" class="file-input" :accept="model.id === 'htdemucs' ? '.th' : '.zip'" :aria-label="`离线导入${modelNames[model.id]?.name || model.id}`" :disabled="busy || modelBusy || !model.writable" @change="upload(model, $event)">
              离线导入
            </label>
          </div>
          <details v-if="!model.installed" class="model-files"><summary>无法联网？手动下载文件</summary>
            <p class="muted">仅接受下列官方文件{{ model.id === 'htdemucs' ? '（原始 .th 权重）' : model.id === 'panns-cnn6' ? '，将该 PTH 文件放在 ZIP 根目录后导入' : '，打包为 ZIP 后导入；ZIP 根目录必须恰好包含 XML 和 BIN 两个文件' }}。</p>
            <div v-for="file in offlineFiles(model)" :key="file.name"><a :href="file.url" target="_blank" rel="noreferrer">下载 {{ file.name }}</a><p class="digest">SHA-256：{{ file.sha256 }}</p></div>
          </details>
        </div>
      </li>

      <li class="sv-panel step">
        <header><span class="step-no" aria-hidden="true">2</span><h3>处理设备</h3><ElTag :type="readiness.backendSelected ? 'success' : 'warning'" effect="light" size="small">{{ readiness.backendSelected ? `正在使用 ${backendName(node.selected)}` : '尚未通过试跑' }}</ElTag></header>
        <p class="muted">优先自动使用已通过试跑的加速设备。首次使用或更换模型后，请先试跑。</p>
        <label class="fallback-toggle"><input type="checkbox" :checked="node.cpuFallback !== false" :disabled="busy || modelBusy" @change="setFallback($event)"> 加速不可用时自动降级到 CPU</label>
        <p class="muted">{{ node.cpuFallback !== false ? '允许自动使用 CPU，处理可能较慢；关闭后，加速不可用的任务会失败。' : '已关闭 CPU 降级，加速不可用时停止处理。' }}此设置也用于视频转码。</p>
        <details class="resource-details"><summary>内存与存储</summary><p class="muted">{{ node.environment.processor || node.environment.system }} · {{ node.environment.architecture }}</p><p class="muted">可用内存 {{ bytes(node.memoryAvailableBytes) }} · 工作目录剩余 {{ bytes(node.diskFreeBytes) }}</p></details>
        <div v-for="backend in visibleBackends" :key="backend.id" class="backend-row">
          <div class="backend-copy"><strong>{{ backendName(backend.id) }}</strong><span class="muted">{{ backend.device?.name || '设备信息未提供' }}<template v-if="backend.reason"> · {{ backend.reason }}</template><template v-if="backend.elapsedMs"> · 试跑 {{ backend.elapsedMs }} ms</template></span></div>
          <ElTag :type="backendTone(backend.state)" effect="light" size="small">{{ node.selected === backend.id ? '使用中' : backendStates[backend.state] || backend.state }}</ElTag>
          <div class="backend-actions">
            <ElButton v-if="['UNVERIFIED', 'FAILED', 'AVAILABLE'].includes(backend.state)" size="small" :disabled="busy || modelBusy" @click="backendAction('test', backend.id)">{{ backend.id === 'cpu' ? '验证自动降级' : backend.state === 'AVAILABLE' ? '重新试跑' : '试跑' }}</ElButton>

          </div>
        </div>
        <p v-if="detected.length && node.selected === 'cpu'" class="muted">加速设备暂不可用，已自动降级到 CPU。</p><p class="selection">{{ auto ? '自动选择可用设备' : '当前使用固定设备设置' }}<ElButton v-if="!auto" link type="primary" :disabled="busy" @click="backendAction('select', 'auto')">改为自动选择</ElButton></p>
      </li>
    </ol>
    <section v-else class="sv-panel empty"><p>{{ nodeError || '正在读取计算节点…' }}</p><p class="muted">请先在“计算节点设置”中连接节点，即可准备模型。</p></section>

  </div>
</template>

<style scoped>
.fallback-toggle{display:flex;align-items:center;gap:8px;font-size:14px;line-height:1.8}.resource-details{font-size:12px;color:var(--sv-text-secondary)}.node-tab{display:grid;gap:14px}.overview{display:flex;gap:14px;align-items:center;padding:16px 20px}.overview.is-ready{border-color:var(--sv-semantic-ok)}.overview.is-pending{border-color:var(--sv-semantic-warn)}.overview .sv-status-dot{width:12px;height:12px}.overview-copy{flex:1;min-width:0}.overview-copy h2{margin:0;font-size:var(--sv-font-h3)}.overview-copy p{margin:4px 0 0;font-size:var(--sv-font-meta);color:var(--sv-text-secondary)}.overview-actions{display:flex;gap:12px;align-items:center;flex-wrap:wrap}.text-link{font-size:var(--sv-font-meta);color:var(--sv-accent-primary)}
.steps{display:grid;gap:14px;margin:0;padding:0;list-style:none}.step{padding:16px 20px;display:grid;gap:8px;min-width:0}.step header{display:flex;gap:10px;align-items:center;flex-wrap:wrap}.step h3{margin:0;font-size:var(--sv-font-body);font-weight:600}.step-no{display:inline-flex;align-items:center;justify-content:center;width:24px;height:24px;border-radius:50%;background:var(--sv-accent-soft);color:var(--sv-accent-primary);font-size:12px;font-weight:700}.step p{margin:0;font-size:var(--sv-font-body);line-height:1.6}
.model-row,.backend-row{display:grid;grid-template-columns:minmax(0,1fr) auto auto;gap:6px 12px;align-items:center;padding:12px 0;border-top:1px solid var(--sv-border-subtle)}.model-copy,.backend-copy{display:grid;gap:2px;min-width:0}.model-copy strong,.backend-copy strong{font-size:var(--sv-font-body);font-weight:600}.model-actions,.backend-actions{display:flex;gap:6px;flex-wrap:wrap;justify-content:flex-end}.model-actions :deep(.el-button),.backend-actions :deep(.el-button){margin:0}.model-files{grid-column:1/-1;font-size:var(--sv-font-meta)}.model-files summary{cursor:pointer;color:var(--sv-text-secondary)}.model-files a{color:var(--sv-accent-primary)}
.upload{position:relative;margin:0;overflow:hidden;cursor:pointer}.upload.is-disabled{cursor:not-allowed;opacity:.5}.file-input{position:absolute;inset:0;opacity:0;cursor:inherit;width:100%}.upload:focus-within{outline:2px solid var(--sv-accent-primary);outline-offset:2px}
.operation{display:flex;gap:8px;align-items:center;padding:6px 10px;border-radius:var(--sv-radius-sm);background:var(--sv-accent-soft);font-size:var(--sv-font-meta)!important}.selection{display:flex;flex-wrap:wrap;gap:6px;align-items:center;padding-top:10px;border-top:1px solid var(--sv-border-subtle);font-size:var(--sv-font-meta)!important;color:var(--sv-text-secondary)}
.empty{padding:20px}.empty p{margin:0 0 6px}code,.digest{overflow-wrap:anywhere;font-family:var(--sv-font-mono);font-size:12px}
.muted{font-size:var(--sv-font-meta);color:var(--sv-text-secondary)}.inline-error{color:var(--sv-semantic-danger);font-size:var(--sv-font-meta)}
@media(max-width:640px){.overview{flex-wrap:wrap;padding:14px}.step{padding:14px}.model-row,.backend-row{grid-template-columns:minmax(0,1fr) auto}.model-actions,.backend-actions{grid-column:1/-1;justify-content:flex-start}.commands{grid-template-columns:minmax(0,1fr)}}
</style>
