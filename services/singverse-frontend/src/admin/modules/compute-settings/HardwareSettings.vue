<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import { ElAlert, ElButton } from 'element-plus'
import { authRequest } from '../../core/auth/api'
interface Settings { backend: string; effectiveBackend: string; deploymentBackend: string; revision: number; writable: boolean; cpuFallback?: boolean; detectedBackends?: string[] }
interface Result { passed: boolean; backend: string; hardware: boolean; elapsedMs: number }
interface Capability { decodeAcceleration?: boolean; toneMappingAcceleration?: boolean; zeroCopy?: boolean; backend: string; installed: boolean; samplePassed: boolean; codecs: Record<string, { samplePassed: boolean }> }
const cpuFallback = ref(true)
const settings = ref<Settings | null>(null), backend = ref('deployment'), busy = ref(false), error = ref(''), saved = ref(false)
const result = ref<Result | null>(null), capabilities = ref<Capability[]>([])
const choices = [{ value: 'deployment', label: '遵循部署配置' }, { value: 'auto', label: '自动选择可用硬件' }, { value: 'software', label: '关闭硬件加速（仅 CPU）' }, { value: 'qsv', label: 'Intel Quick Sync（QSV）' }, { value: 'vaapi', label: 'Linux Intel / AMD（VA-API）' }, { value: 'nvenc', label: 'NVIDIA（NVENC）' }, { value: 'amf', label: 'Windows AMD（AMF）' }, { value: 'videotoolbox', label: 'Apple（VideoToolbox）' }, { value: 'rkmpp', label: 'Rockchip（RKMPP）' }]
const label = (value: string) => choices.find(c => c.value === value)?.label ?? value
const visibleChoices = computed(() => choices.filter(item => ['deployment', 'auto'].includes(item.value) || (item.value === 'software' ? !settings.value?.detectedBackends?.length : settings.value?.detectedBackends?.includes(item.value))))
const available = computed(() => capabilities.value.filter(item => item.backend === 'software' ? !settings.value?.detectedBackends?.length : settings.value?.detectedBackends?.includes(item.backend)).sort((a, b) => Number(b.backend === settings.value?.effectiveBackend) - Number(a.backend === settings.value?.effectiveBackend)))
const codecs = (item: Capability) => Object.entries(item.codecs).filter(([, codec]) => codec.samplePassed).map(([codec]) => codec.toUpperCase())
const stages = (item: Capability) => [
  { label: '硬解', verified: item.decodeAcceleration },
  { label: '零拷贝', verified: item.zeroCopy },
  { label: 'GPU 色调映射', verified: item.toneMappingAcceleration },
]
let active = true
async function load() {
  busy.value = true; error.value = ''; saved.value = false; result.value = null
  try { const value = await authRequest<Settings>('/api/v1/transcoding/hardware-settings'); if (active) { settings.value = value; backend.value = value.backend === 'software' && value.detectedBackends?.length ? 'auto' : value.backend; cpuFallback.value = value.cpuFallback !== false } }
  catch (e) { if (active) error.value = e instanceof Error ? e.message : '读取失败' }
  finally { if (active) busy.value = false }
}
async function test() {
  busy.value = true; error.value = ''; result.value = null; saved.value = false
  try {
    const value = await authRequest<Result>('/api/v1/transcoding/hardware-settings/test', 'POST', { backend: backend.value }, 45000)
    if (active) result.value = value
  } catch (e) { if (active) error.value = e instanceof Error ? e.message : '测试失败' }
  finally { if (active) busy.value = false }
}
async function save() {
  if (!settings.value) return
  busy.value = true; error.value = ''; saved.value = false
  try {
    const value = await authRequest<Settings>('/api/v1/transcoding/hardware-settings', 'POST', { backend: backend.value, revision: settings.value.revision, cpuFallback: cpuFallback.value }, 45000)
    if (active) { settings.value = value; saved.value = true }
  } catch (e) { if (active) error.value = e instanceof Error ? e.message : '保存失败' }
  finally { if (active) busy.value = false }
}
async function inspect() {
  busy.value = true; error.value = ''
  try { const value = await authRequest<{ backends: Capability[] }>('/api/v1/transcoding/capabilities', 'GET', undefined, 60000); if (active) capabilities.value = value.backends }
  catch (e) { if (active) error.value = e instanceof Error ? e.message : '探测失败' }
  finally { if (active) busy.value = false }
}
onMounted(load)
onBeforeUnmount(() => { active = false })
</script>
<template>
  <section class="hardware-settings" aria-label="转码与硬件加速" :aria-busy="busy">
    <header class="hardware-heading">
      <div class="heading-copy"><span class="hardware-icon" aria-hidden="true"><svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.5"><rect x="5" y="5" width="14" height="14" rx="3"/><rect x="9" y="9" width="6" height="6" rx="1"/><path d="M9 2v3m6-3v3M9 19v3m6-3v3M2 9h3m-3 6h3m14-6h3m-3 6h3"/></svg></span><div><h2>转码与硬件加速</h2><p>为兼容播放选择编码方式，减轻视频转码时的处理负担。</p></div></div>
      <ElButton v-if="settings" text :disabled="busy" @click="load">重新读取设置</ElButton>
    </header>
    <div v-if="!settings" class="hardware-empty"><p>读取当前节点的加速设置，查看可用的编码方式。</p><ElButton :loading="busy" @click="load">读取硬件加速设置</ElButton></div>
    <template v-else>
      <div class="hardware-config">
        <div class="backend-field"><label for="hardware-backend">硬件加速方式</label><select id="hardware-backend" v-model="backend" aria-label="硬件加速方式" :disabled="busy" @change="result = null; saved = false"><option v-for="choice in visibleChoices" :key="choice.value" :value="choice.value">{{ choice.label }}</option></select><p>保存后对新任务生效，正在播放的歌曲继续使用原设置。</p></div>
        <aside class="current-backend"><span class="current-caption"><span class="status-dot" aria-hidden="true"></span>当前生效：</span><strong>{{ label(settings.effectiveBackend) }}</strong><small>{{ settings.detectedBackends?.length ? `检测到：${settings.detectedBackends.map(label).join('、')}` : '未检测到加速硬件，使用 CPU' }}</small></aside>
      </div>
      <div class="fallback-policy"><label><input v-model="cpuFallback" type="checkbox" :disabled="busy || !settings.writable" @change="saved = false"> 加速不可用时自动降级到 CPU</label><p>同时用于视频转码与伴奏生成，保存后对新任务生效。</p></div><div class="hardware-actions"><div><ElButton type="primary" :disabled="busy || !settings.writable" @click="save">保存硬件加速设置</ElButton><ElButton :disabled="busy" @click="test">测试所选方式</ElButton></div><span>选择“遵循部署配置”可恢复默认方式</span></div>
      <ElAlert v-if="!settings.writable" title="计算服务尚未配置转码缓存目录，请先完成部署配置。" type="warning" :closable="false" />
      <p v-if="result" role="status" class="feedback" :class="{ 'feedback-error': !result.passed }">{{ result.passed ? `测试通过：${label(result.backend)}` : '测试未通过，请检查驱动、系统支持及设备权限' }}{{ result.passed && !result.hardware ? ' · 当前使用 CPU 软件编码' : '' }} · {{ result.elapsedMs }} ms</p>
      <p v-if="saved" role="status" class="feedback">硬件加速设置已保存；选择“遵循部署配置”可恢复环境变量指定的方式。</p>
      <section class="encoder-section" aria-label="节点编码能力">
        <div class="encoder-heading"><div><h3>节点编码能力</h3><p>{{ capabilities.length ? '编码测试与素材验证分别展示，实际播放以任务结果为准。' : '探测当前节点支持的编码方式。' }}</p></div><ElButton :disabled="busy" @click="inspect">检测当前硬件</ElButton></div>
        <div v-if="capabilities.length" class="encoder-results">
          <div v-if="available.length" class="encoder-table-wrap"><table class="encoder-table"><thead><tr><th scope="col">加速方式</th><th scope="col">编码测试通过</th><th scope="col">素材验证</th></tr></thead><tbody><tr v-for="item in available" :key="item.backend"><th scope="row"><div class="encoder-name">{{ label(item.backend) }}<span v-if="item.backend === settings.effectiveBackend" class="current-tag">当前使用</span></div><small>{{ codecs(item).length ? '可用' : '已安装 · 测试未通过' }}</small></th><td><div class="codec-tags"><span v-for="codec in codecs(item)" :key="codec" class="codec-tag">{{ codec }}</span><span v-if="!codecs(item).length" class="muted">暂无通过的编码</span></div></td><td><span v-if="item.backend === 'software'" class="muted">CPU 软件处理</span><div v-else class="stage-tags"><span v-for="stage in stages(item)" :key="stage.label" class="stage-tag" :class="{ verified: stage.verified }">{{ stage.label }}<span>{{ stage.verified ? '已验证' : '待验证' }}</span></span></div></td></tr></tbody></table></div>
          <p v-else class="no-encoders">当前节点没有通过安装检查的编码方式，请检查媒体工具与驱动。</p>

        </div>
      </section>
    </template>
    <ElAlert v-if="error" :title="error" type="error" :closable="false" />

  </section>
</template>
<style scoped>
.hardware-settings{margin-top:24px;border:1px solid var(--sv-border-default);border-radius:18px;background:var(--sv-surface-1);overflow:hidden;color:var(--sv-text-primary)}
.hardware-heading,.hardware-config,.hardware-actions,.encoder-section{padding:24px 28px}.hardware-heading{display:flex;align-items:center;justify-content:space-between;gap:20px;border-bottom:1px solid var(--sv-border-subtle)}
.heading-copy{display:flex;gap:14px;align-items:center;min-width:0}.hardware-icon{width:44px;height:44px;flex-shrink:0;display:grid;place-items:center;background:var(--sv-accent-soft);color:var(--sv-accent-primary);border-radius:12px}.hardware-icon svg{width:24px;height:24px}
h2,h3,p{margin:0}h2{font-size:19px;line-height:1.5;letter-spacing:-.3px}.hardware-heading p,.encoder-heading p,.backend-field p{font-size:13px;line-height:1.7;color:var(--sv-text-secondary);margin-top:5px}
.hardware-config{display:grid;grid-template-columns:minmax(0,1.2fr) minmax(0,1fr);gap:36px;padding-bottom:20px}.backend-field{min-width:0}.backend-field label{display:block;font-size:13px;font-weight:600;margin-bottom:10px}select{width:100%;height:44px;padding:0 12px;border:1px solid var(--sv-border-default);border-radius:10px;background:var(--sv-surface-2);color:var(--sv-text-primary);font:inherit;font-size:14px;cursor:pointer}select:focus-visible{outline:2px solid var(--sv-accent-primary);outline-offset:3px}select:disabled{cursor:wait;opacity:.6}.backend-field p{margin-top:10px}
.current-backend{display:flex;flex-direction:column;gap:9px;padding:16px 20px;align-self:start;border-radius:12px;background:var(--sv-surface-3);min-width:0}.current-caption{display:flex;align-items:center;gap:7px;color:var(--sv-text-secondary);font-size:12px}.status-dot{width:6px;height:6px;border-radius:50%;background:var(--sv-accent-primary)}.current-backend strong{font-size:16px;line-height:1.5;overflow-wrap:anywhere}.current-backend small{color:var(--sv-text-secondary);font-size:12px;line-height:1.6}
.hardware-actions{padding-top:0;display:flex;align-items:center;justify-content:space-between;flex-wrap:wrap;gap:14px}.hardware-actions>div{display:flex;flex-wrap:wrap;gap:10px}.hardware-actions :deep(.el-button){margin-left:0}.hardware-actions>span{font-size:12px;line-height:1.7;color:var(--sv-text-secondary)}
.encoder-section{border-top:1px solid var(--sv-border-subtle)}.encoder-heading{display:flex;justify-content:space-between;align-items:center;gap:20px}.encoder-heading h3{font-size:15px;font-weight:600}.encoder-results{margin-top:20px}.encoder-table-wrap{overflow-x:auto}.encoder-table{width:100%;border-collapse:collapse;text-align:left;font-size:13px}.encoder-table thead{background:var(--sv-surface-3);color:var(--sv-text-secondary)}.encoder-table thead th{padding:12px 14px;font-size:12px;font-weight:500;white-space:nowrap}.encoder-table tbody th,.encoder-table td{padding:18px 14px;vertical-align:middle;border-bottom:1px solid var(--sv-border-subtle)}.encoder-table tbody th{font-weight:500;min-width:180px}.encoder-table tbody th small{display:block;margin-top:6px;color:var(--sv-text-secondary);font-weight:400;font-size:12px}.encoder-name{display:flex;align-items:center;flex-wrap:wrap;gap:8px;line-height:1.6}.current-tag{font-size:11px;color:var(--sv-accent-primary);background:var(--sv-accent-soft);border-radius:5px;padding:2px 6px;white-space:nowrap}.codec-tags,.stage-tags{display:flex;gap:6px;flex-wrap:wrap}.codec-tag{padding:4px 7px;border:1px solid var(--sv-border-subtle);border-radius:6px;font-size:11px;background:var(--sv-surface-2);white-space:nowrap}.stage-tag{display:flex;gap:5px;align-items:center;font-size:11px;line-height:1.5;color:var(--sv-text-secondary);background:var(--sv-surface-3);padding:4px 7px;border-radius:6px;white-space:nowrap}.stage-tag>span{font-size:10px;opacity:.8}.stage-tag.verified{color:var(--sv-semantic-ok);background:var(--sv-semantic-ok-soft)}.muted,.no-encoders{font-size:12px;color:var(--sv-text-secondary);line-height:1.7}
.unavailable-details{margin-top:16px;font-size:12px}.unavailable-details summary,.deployment-note summary{cursor:pointer;color:var(--sv-text-secondary);line-height:1.8}.unavailable-details summary>span{margin-left:8px;color:var(--sv-text-tertiary);font-size:11px}.unavailable-details ul{list-style:none;padding:6px 0 0;margin:0;display:grid;grid-template-columns:repeat(2,minmax(0,1fr));column-gap:28px}.unavailable-details li{padding:10px 0;display:flex;flex-direction:column;gap:4px;color:var(--sv-text-secondary)}.unavailable-details small{font-size:11px;color:var(--sv-text-tertiary)}
.deployment-note{padding:15px 28px;background:var(--sv-surface-3);border-top:1px solid var(--sv-border-subtle);font-size:12px}.deployment-note p{color:var(--sv-text-secondary);line-height:1.8;margin-top:10px;max-width:900px}.hardware-empty{padding:24px 28px;display:flex;align-items:center;flex-wrap:wrap;gap:16px}.hardware-empty p{font-size:13px;color:var(--sv-text-secondary)}.feedback{margin:0 28px 20px;border-radius:10px;padding:12px 16px;font-size:13px;line-height:1.7;color:var(--sv-semantic-ok);background:var(--sv-semantic-ok-soft)}.feedback-error{color:var(--sv-semantic-warn);background:var(--sv-semantic-warn-soft)}.hardware-settings>:deep(.el-alert){width:auto;margin:0 28px 20px}
@media(max-width:960px){.hardware-config{gap:20px;grid-template-columns:minmax(0,1fr) minmax(0,1fr)}.encoder-table th,.encoder-table td{min-width:140px}.stage-tags{min-width:160px}}
@media(max-width:640px){.hardware-heading,.hardware-config,.hardware-actions,.encoder-section{padding-left:18px;padding-right:18px}.hardware-heading{align-items:flex-start;flex-wrap:wrap;gap:12px}.hardware-heading>.el-button{margin-left:58px}.hardware-config{grid-template-columns:minmax(0,1fr);gap:16px}.encoder-heading{align-items:flex-start;flex-direction:column;gap:12px}.unavailable-details ul{grid-template-columns:minmax(0,1fr)}.deployment-note{padding-left:18px;padding-right:18px}.hardware-empty{padding:20px 18px}.feedback{margin-left:18px;margin-right:18px}}
.fallback-policy{padding:0 28px 20px;font-size:13px}.fallback-policy p{margin-top:6px;font-size:12px;color:var(--sv-text-secondary)}
</style>
