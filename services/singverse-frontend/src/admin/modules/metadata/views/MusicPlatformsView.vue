<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import { onBeforeRouteLeave, useRoute, useRouter } from 'vue-router'
import { ElAlert, ElButton, ElMessage, ElMessageBox, ElSwitch, ElTag } from 'element-plus'
import PageHeader from '../../../shared/ui/PageHeader.vue'
import { metadata } from '../api/metadata'
import { providerNames, type MetadataBatch, type MetadataSettings, type MusicProvider } from '../types'

const route = useRoute(), router = useRouter()
const platforms = Object.keys(providerNames) as MusicProvider[]
const marks: Record<MusicProvider, string> = { NETEASE: '云', QQ: 'Q', KUGOU: '酷', KUWO: '我', QISHUI: '汽' }
const config = ref<MetadataSettings>({ enabled: true, providers: [...platforms], autoApply: false, threshold: .95, minIntervalSeconds: 3, maxIntervalSeconds: 8 })
const ready = ref(false), loading = ref(false), busy = ref(false), error = ref(''), baseline = ref('')
const latest = ref<MetadataBatch | null>(null), taskUnknown = ref(false)
const dirty = computed(() => ready.value && JSON.stringify(config.value) !== baseline.value)
const invalid = computed(() => config.value.enabled && !config.value.providers.length ? '请至少选择一个平台，或关闭平台查询。' :
  !Number.isFinite(config.value.threshold) || config.value.threshold < .8 || config.value.threshold > 1 ? '自动应用阈值需要在 80%～100% 之间。' :
  !Number.isInteger(config.value.minIntervalSeconds) || !Number.isInteger(config.value.maxIntervalSeconds) || config.value.minIntervalSeconds! < 0 || config.value.maxIntervalSeconds! > 300 || config.value.maxIntervalSeconds! < config.value.minIntervalSeconds! ? '请求间隔需要在 0～300 秒之间，最大值不能小于最小值。' : '')
const thresholdPercent = computed({ get: () => Math.round(config.value.threshold * 100), set: value => { config.value.threshold = Number(value) / 100 } })
const returnTo = computed(() => typeof route.query.returnTo === 'string' && /^\/(library(?:\/|\?|$)|system\/tasks(?:\/|\?|$))/.test(route.query.returnTo) ? route.query.returnTo : '/library/songs/metadata')
let active = true, generation = 0
async function load() {
  if (loading.value || busy.value || dirty.value) return
  const token = ++generation; loading.value = true; error.value = ''
  const [settings, batch] = await Promise.allSettled([metadata.settings(), metadata.latest()])
  if (!active || token !== generation) return
  if (settings.status === 'fulfilled') {
    config.value = { ...settings.value, providers: [...settings.value.providers], minIntervalSeconds: settings.value.minIntervalSeconds ?? 3, maxIntervalSeconds: settings.value.maxIntervalSeconds ?? 8 }
    baseline.value = JSON.stringify(config.value); ready.value = true
  } else { ready.value = false; error.value = settings.reason instanceof Error ? settings.reason.message : '音乐平台读取失败，请重试。' }
  latest.value = batch.status === 'fulfilled' ? batch.value ?? null : null
  taskUnknown.value = batch.status === 'rejected'
  loading.value = false
}
function enableAll() { config.value.enabled = true; config.value.providers = [...platforms] }
async function save() {
  if (!ready.value || busy.value || loading.value || !dirty.value || invalid.value) return
  busy.value = true; error.value = ''
  try {
    if (latest.value?.status === 'RUNNING' || taskUnknown.value) {
      try { await ElMessageBox.confirm('保存设置会暂停正在运行的歌曲信息匹配批次；已写入结果保留，之后可在歌曲信息页继续。', '保存并暂停批次', { confirmButtonText: '保存设置', cancelButtonText: '继续编辑' }) } catch { return }
    }
    if (!active) return
    const result = await metadata.saveSettings({ ...config.value, providers: [...config.value.providers] })
    if (!active) return
    config.value = { ...result, providers: [...result.providers], minIntervalSeconds: result.minIntervalSeconds ?? 3, maxIntervalSeconds: result.maxIntervalSeconds ?? 8 }
    baseline.value = JSON.stringify(config.value)
    if (latest.value?.status === 'RUNNING') latest.value = { ...latest.value, status: 'PAUSED' }
    ElMessage.success('音乐平台设置已保存')
  } catch (failure) { if (active) error.value = failure instanceof Error ? failure.message : '保存失败，修改已保留，可重试。' }
  finally { if (active) busy.value = false }
}
async function canLeave() {
  if (busy.value) { ElMessage.info('正在保存设置，请稍候'); return false }
  if (!dirty.value) return true
  try { await ElMessageBox.confirm('离开会放弃尚未保存的音乐平台设置。', '有未保存的设置', { confirmButtonText: '放弃修改', cancelButtonText: '继续编辑' }); return true } catch { return false }
}
function beforeUnload(event: BeforeUnloadEvent) { if (dirty.value || busy.value) event.preventDefault() }
onBeforeRouteLeave(canLeave)
onMounted(() => { void load(); window.addEventListener('beforeunload', beforeUnload) })
onBeforeUnmount(() => { active = false; generation++; window.removeEventListener('beforeunload', beforeUnload) })
</script>

<template>
  <section class="music-platforms-page management-page">
    <PageHeader title="音乐平台" description="为歌曲信息匹配和封面查找选择来源。新安装默认开启全部五个平台，可按需调整。">
      <template #actions><ElButton :disabled="busy" @click="router.push(returnTo)">{{ route.query.returnTo ? '返回原页面' : '查看歌曲信息' }}</ElButton></template>
    </PageHeader>
    <ElAlert v-if="error" :title="error" type="error" :closable="false" show-icon><ElButton v-if="!ready" text :loading="loading" @click="load">重新读取</ElButton></ElAlert>
    <p v-if="loading && !ready" role="status" class="platform-loading">正在读取音乐平台设置…</p>
    <form v-if="ready" aria-label="音乐平台设置" @submit.prevent="save">
      <fieldset :disabled="busy || loading" class="platform-form">
        <section class="platform-panel" aria-labelledby="platform-heading">
          <div class="platform-heading"><div><h2 id="platform-heading">歌曲信息与封面</h2><p>搜索时仅发送歌名、歌手等关键词，媒体文件留在你的 NAS。</p></div><ElSwitch v-model="config.enabled" :disabled="busy || loading" aria-label="启用音乐平台查询" /></div>
          <div class="platform-selection-heading"><span>{{ config.enabled ? `已开启 ${config.providers.length} 个平台` : '平台查询已关闭，仍可手工编辑与上传封面' }}</span><ElButton text :disabled="config.enabled && config.providers.length === platforms.length" @click="enableAll">启用全部平台</ElButton></div>
          <div class="platform-options"><label v-for="platform in platforms" :key="platform" class="platform-option" :class="{ selected: config.providers.includes(platform) }"><span class="platform-mark" :data-platform="platform" aria-hidden="true">{{ marks[platform] }}</span><span class="platform-name">{{ providerNames[platform] }}<small>{{ config.providers.includes(platform) ? config.enabled ? '已开启' : '已选中 · 查询已关闭' : '未开启' }}</small></span><input v-model="config.providers" type="checkbox" :value="platform" :aria-label="providerNames[platform]" /></label></div>
          <p class="platform-note">这里决定可用平台；单曲审核与封面窗口中还可以临时选择本次搜索的平台。</p>
        </section>
        <section class="platform-panel" aria-labelledby="automation-heading">
          <div class="platform-heading"><div><h2 id="automation-heading">批量匹配后的应用方式</h2><p>单曲搜索始终需要核对后确认。批量匹配可按以下规则处理。</p></div><ElTag :type="config.autoApply ? 'warning' : 'info'">{{ config.autoApply ? '自动应用已开启' : '逐曲审核' }}</ElTag></div>
          <label class="platform-switch-row"><span><strong>高相似度时自动应用</strong><small>默认关闭；开启后，批量匹配的第一候选达到阈值时可直接写入。</small></span><ElSwitch v-model="config.autoApply" :disabled="busy || loading" aria-label="高相似度时自动应用" /></label>
          <details class="platform-advanced"><summary>匹配阈值与请求节奏</summary><div class="platform-fields"><label>自动应用阈值<span class="platform-number"><input v-model.number="thresholdPercent" aria-label="自动应用阈值百分比" type="number" min="80" max="100" step="1" required /><span>%</span></span><small>相似度是匹配参考，不代表正确率；版本接近时请人工核对。</small></label><label>每首歌曲完成后等待<span class="platform-interval"><input v-model.number="config.minIntervalSeconds" aria-label="批量最小间隔秒" type="number" min="0" max="300" step="1" required /><span>至</span><input v-model.number="config.maxIntervalSeconds" aria-label="批量最大间隔秒" type="number" min="0" max="300" step="1" required /><span>秒</span></span><small>默认 3～8 秒，可设为 0；平台限流时仍会暂停请求并提示重试。</small></label></div></details>
        </section>
      </fieldset>
      <ElAlert v-if="taskUnknown" title="暂时无法读取匹配任务状态；保存设置仍会暂停正在运行的批次。" type="warning" :closable="false" />
      <p v-if="invalid" role="alert" class="platform-validation">{{ invalid }}</p>
      <div class="platform-save"><p>{{ dirty ? '有未保存的修改' : '当前设置已保存' }}<small>保存会暂停运行中的匹配批次，可在歌曲信息页继续。</small></p><ElButton type="primary" native-type="submit" :loading="busy" :disabled="!dirty || !!invalid || loading">保存设置</ElButton></div>
    </form>
  </section>
</template>

<style scoped>
.music-platforms-page { max-width: 1120px; }
.platform-form { padding: 0; margin: 0; border: 0; min-width: 0; }
.platform-panel { padding: 24px; margin-bottom: 20px; border: 1px solid var(--sv-border-subtle); border-radius: var(--sv-radius-md); background: var(--sv-surface-2); }
.platform-heading,.platform-selection-heading,.platform-switch-row,.platform-save { display: flex; align-items: center; justify-content: space-between; gap: 20px; }
.platform-heading { align-items: flex-start; margin-bottom: 20px; }
.platform-heading h2 { margin: 0 0 8px; font-size: 18px; }
.platform-heading p,.platform-note,.platform-loading { margin: 0; color: var(--sv-text-secondary); font-size: 13px; line-height: 1.8; }
.platform-selection-heading { margin-bottom: 12px; color: var(--sv-text-secondary); font-size: 13px; }
.platform-options { display: grid; grid-template-columns: repeat(3,minmax(0,1fr)); gap: 12px; }
.platform-option { display: flex; align-items: center; gap: 12px; min-width: 0; padding: 18px 14px; border: 1px solid var(--sv-border-default); border-radius: var(--sv-radius-sm); cursor: pointer; }
.platform-option.selected { border-color: var(--sv-accent-primary); background: var(--sv-accent-soft); }
.platform-mark { display: grid; place-items: center; flex-shrink: 0; width: 40px; height: 40px; border-radius: 50%; background: var(--sv-surface-2); color: var(--sv-accent-primary); font-size: 20px; font-weight: 650; }
.platform-name { flex: 1; min-width: 0; font-weight: 600; font-size: 14px; }
.platform-name small,.platform-switch-row small,.platform-save small { display: block; color: var(--sv-text-secondary); font-size: 12px; font-weight: 400; margin-top: 6px; line-height: 1.6; }
.platform-option input { width: 18px; height: 18px; flex-shrink: 0; accent-color: var(--sv-accent-primary); }
.platform-note { margin-top: 16px; }
.platform-switch-row { font-size: 14px; }
.platform-switch-row :deep(.el-switch) { flex-shrink: 0; }
.platform-advanced { margin-top: 24px; padding-top: 18px; border-top: 1px solid var(--sv-border-subtle); }
.platform-advanced summary { cursor: pointer; font-size: 13px; padding: 4px 0; }
.platform-fields { display: grid; grid-template-columns: 1fr 1fr; gap: 24px; margin-top: 18px; }
.platform-fields > label { display: flex; flex-direction: column; gap: 9px; font-size: 13px; }
.platform-fields small { color: var(--sv-text-secondary); line-height: 1.7; }
.platform-number,.platform-interval { display: flex; align-items: center; gap: 10px; }
.platform-fields input { width: 90px; min-width: 0; padding: 10px; border: 1px solid var(--sv-border-default); border-radius: var(--sv-radius-sm); background: var(--sv-surface-2); color: var(--sv-text-primary); font: inherit; }
.platform-save { padding: 4px 0 20px; }
.platform-save p { font-size: 13px; margin: 0; }
.platform-validation { color: var(--el-color-danger); font-size: 13px; }
@media(max-width: 850px) { .platform-options { grid-template-columns: repeat(2,minmax(0,1fr)); }.platform-fields { grid-template-columns: 1fr; } }
@media(max-width: 520px) { .platform-panel { padding: 18px; }.platform-options { grid-template-columns: 1fr; }.platform-heading { gap: 12px; }.platform-selection-heading { align-items: flex-start; gap: 8px; }.platform-save { align-items: stretch; flex-direction: column; }.platform-save :deep(.el-button) { min-height: 44px; } }
</style>
