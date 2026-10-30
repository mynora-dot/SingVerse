<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, reactive, ref } from 'vue'
import { onBeforeRouteLeave, onBeforeRouteUpdate, useRoute } from 'vue-router'
import { ElAlert, ElButton, ElCard, ElCheckbox, ElDialog, ElEmpty, ElForm, ElFormItem, ElIcon, ElInput, ElInputNumber, ElMessage, ElMessageBox, ElOption, ElRadioButton, ElRadioGroup, ElSelect, ElSwitch, ElTag } from 'element-plus'
import { ArrowRight, Connection, MagicStick, Plus, Search, Setting } from '@element-plus/icons-vue'
import { ai } from '../api/ai'
import { updateAssistantSettings } from '../assistant-state'
import { assistantPets } from '../pets'
import type { AiService, Feature, Provider, ServiceInput, ServiceKind, TestResult } from '../types'
const route = useRoute()
const section = computed(() => route.path.split('/')[3] ?? 'models')
const assistantPage = computed(() => route.path.endsWith('/features/assistant'))
const assistant = reactive<Feature>({ id: 'assistant', modelServiceId: null, searchServiceId: null, enabled: false, intervalSeconds: 3, revision: 0, displayMode: 'PET', petId: 'music-sprite' })
const originalAssistant = ref('')
const assistantDirty = computed(() => ready.value && originalAssistant.value !== JSON.stringify(assistant))
const kind = computed<ServiceKind>(() => section.value === 'search' ? 'SEARCH' : 'MODEL')
const providers = ref<Provider[]>([]), models = ref<AiService[]>([]), searches = ref<AiService[]>([]), features = ref<Feature[]>([])
const loading = ref(false), ready = ref(false), busy = ref(false), error = ref(''), formError = ref('')
const editing = ref(false), editingId = ref<string>(), original = ref('')
const draft = reactive<ServiceInput>({ name: '', provider: '', protocol: '', endpoint: '', model: '', outputMode: 'JSON_SCHEMA', apiKey: '', clearKey: false, enabled: true, timeoutSeconds: 60, maxResults: 5, revision: 0 })
const editorKind = ref<ServiceKind>('MODEL')
const editingService = computed(() => [...models.value, ...searches.value].find(s => s.id === editingId.value))
const availableProviders = computed(() => providers.value.filter(p => p.kind === editorKind.value))
const services = computed(() => kind.value === 'MODEL' ? models.value : searches.value)
const classification = reactive<Feature>({ id: 'classification', modelServiceId: null, searchServiceId: null, enabled: false, intervalSeconds: 3, revision: 0 })
const originalFeature = ref('')
const featureDirty = computed(() => ready.value && originalFeature.value !== JSON.stringify(classification))
const editorDirty = computed(() => editing.value && original.value !== JSON.stringify(draft))
const testing = ref<AiService | null>(null), testingBusy = ref(false), testMode = ref('generation'), query = ref(''), testError = ref(''), testResult = ref<TestResult | null>(null)
const defaultSearch = computed(() => features.value.find(f => f.id === 'default-search')?.searchServiceId ?? null)
let active = true, generation = 0, testGeneration = 0
function providerName(id: string) { return providers.value.find(p => p.id === id)?.name ?? id }
function message(e: unknown) { return e instanceof Error ? e.message : '请求失败，请重试' }
async function load() {
  const token = ++generation; loading.value = true; error.value = ''
  try {
    const [p, m, s, f] = await Promise.all([ai.providers(), ai.services('MODEL'), ai.services('SEARCH'), ai.features()])
    if (!active || token !== generation) return
    providers.value = p; models.value = m; searches.value = s; features.value = f
    const saved = f.find(feature => feature.id === 'classification')
    if (saved) Object.assign(classification, saved)
    originalFeature.value = JSON.stringify(classification)
    const savedAssistant = f.find(feature => feature.id === 'assistant')
    if (savedAssistant) { Object.assign(assistant, savedAssistant); updateAssistantSettings(savedAssistant) }
    originalAssistant.value = JSON.stringify(assistant); ready.value = true
  } catch (e) { if (active && token === generation) error.value = message(e) }
  finally { if (active && token === generation) loading.value = false }
}
function openEditor(service?: AiService) {
  editorKind.value = service?.kind ?? kind.value; editingId.value = service?.id; formError.value = ''
  const preset = providers.value.find(p => p.kind === editorKind.value)!
  Object.assign(draft, { name: service?.name ?? '', provider: service?.provider ?? preset.id, protocol: service?.protocol ?? preset.protocol, endpoint: service?.endpoint ?? preset.endpoint, model: service?.model ?? '', outputMode: service?.outputMode ?? preset.outputMode, apiKey: '', clearKey: false, enabled: service?.enabled ?? true, timeoutSeconds: service?.timeoutSeconds ?? 60, maxResults: service?.maxResults ?? 5, revision: service?.revision ?? 0 })
  original.value = JSON.stringify(draft); editing.value = true
}
async function changeProvider(id: string) {
  const preset = providers.value.find(p => p.id === id)
  if (!preset) return
  draft.protocol = preset.protocol; draft.endpoint = preset.endpoint; draft.outputMode = preset.outputMode
  draft.apiKey = ''; draft.clearKey = false
}
async function discard() {
  try { await ElMessageBox.confirm('离开将放弃尚未保存的配置。', '有未保存的配置', { confirmButtonText: '放弃修改', cancelButtonText: '继续编辑', type: 'warning' }); return true } catch { return false }
}
async function closeEditor() {
  if (busy.value || editorDirty.value && !await discard()) return
  editing.value = false; draft.apiKey = ''; draft.clearKey = false; formError.value = ''
}
async function saveService() {
  if (busy.value) return
  if (!draft.name.trim() || !draft.endpoint.trim() || editorKind.value === 'MODEL' && !draft.model.trim()) { formError.value = '请填写名称、服务地址及模型 ID。'; return }
  busy.value = true; formError.value = ''
  try {
    await ai.save(editorKind.value, { ...draft, name: draft.name.trim(), endpoint: draft.endpoint.trim(), model: draft.model.trim() }, editingId.value)
    editing.value = false; draft.apiKey = ''; ElMessage.success('服务配置已保存'); await load()
  } catch (e) { formError.value = message(e) }
  finally { busy.value = false }
}
async function remove(service: AiService) {
  try { await ElMessageBox.confirm(`删除“${service.name}”的服务配置？被功能引用时需要先解除绑定。`, '删除服务配置', { type: 'warning', confirmButtonText: '删除', cancelButtonText: '取消' }) } catch { return }
  busy.value = true; error.value = ''
  try { await ai.remove(service.kind, service.id); await load(); ElMessage.success('服务配置已删除') }
  catch (e) { error.value = message(e) } finally { busy.value = false }
}
async function saveFeature() {
  busy.value = true; error.value = ''
  try { const saved = await ai.bind({ ...classification }); Object.assign(classification, saved); originalFeature.value = JSON.stringify(classification); ElMessage.success('功能配置已保存；活动分类任务已暂停') }
  catch (e) { error.value = message(e) } finally { busy.value = false }
}
async function saveAssistant() {
  if (busy.value) return
  busy.value = true; error.value = ''
  try {
    const saved = await ai.bind({ ...assistant })
    Object.assign(assistant, saved); originalAssistant.value = JSON.stringify(assistant)
    features.value = features.value.map(f => f.id === saved.id ? saved : f)
    updateAssistantSettings(saved); ElMessage.success('助手配置已保存')
  } catch (e) { error.value = message(e) } finally { busy.value = false }
}
async function setDefault(id: string | null) {
  const saved = features.value.find(f => f.id === 'default-search')
  if (!saved) return
  busy.value = true; error.value = ''
  try { const next = await ai.bind({ ...saved, searchServiceId: id, enabled: !!id }); features.value = features.value.map(f => f.id === next.id ? next : f); ElMessage.success('默认搜索服务已更新') }
  catch (e) { error.value = message(e) } finally { busy.value = false }
}
function openTest(service: AiService) { testing.value = service; testMode.value = 'generation'; query.value = ''; testResult.value = null; testError.value = ''; testGeneration++ }
function closeTest() { if (testingBusy.value) return; testing.value = null; testGeneration++ }
async function test() {
  if (!testing.value || testingBusy.value) return
  if (testing.value.kind === 'SEARCH' && !query.value.trim()) { testError.value = '请输入测试关键词'; return }
  const token = ++testGeneration; testingBusy.value = true; testError.value = ''; testResult.value = null
  try { const result = await ai.test(testing.value, testMode.value, query.value.trim()); if (active && token === testGeneration) testResult.value = result }
  catch (e) { if (active && token === testGeneration) testError.value = message(e) }
  finally { if (active && token === testGeneration) testingBusy.value = false }
}
async function canLeave() {
  if (busy.value || testingBusy.value) { ElMessage.info('请求处理中，请稍候'); return false }
  if ((editorDirty.value || featureDirty.value || assistantDirty.value) && !await discard()) return false
  const saved = features.value.find(f => f.id === 'classification')
  if (saved) Object.assign(classification, saved)
  originalFeature.value = JSON.stringify(classification)
  const savedAssistant = features.value.find(f => f.id === 'assistant')
  if (savedAssistant) Object.assign(assistant, savedAssistant)
  originalAssistant.value = JSON.stringify(assistant)
  editing.value = false; draft.apiKey = ''; testing.value = null; testGeneration++; return true
}
onBeforeRouteLeave(canLeave)
onBeforeRouteUpdate(canLeave)
function beforeUnload(event: BeforeUnloadEvent) { if (editorDirty.value || featureDirty.value || assistantDirty.value) event.preventDefault() }
onMounted(() => { void load(); window.addEventListener('beforeunload', beforeUnload) })
onBeforeUnmount(() => { active = false; generation++; testGeneration++; draft.apiKey = ''; window.removeEventListener('beforeunload', beforeUnload) })
</script>

<template>
  <section class="ai-settings-page management-page">
    <div class="page-heading"><div><p class="page-eyebrow">AI WORKSPACE / SETTINGS</p><h1>AI 设置</h1><p class="page-description">连接模型与搜索服务，让每项 AI 能力各有所用。</p></div><ElButton :loading="loading" :disabled="busy || editing || featureDirty || assistantDirty" @click="load">刷新配置</ElButton></div>
    <nav class="ai-tabs" aria-label="AI 设置分区"><RouterLink to="/system/ai/models" :aria-current="section === 'models' ? 'page' : undefined"><ElIcon><MagicStick /></ElIcon>模型服务</RouterLink><RouterLink to="/system/ai/search" :aria-current="section === 'search' ? 'page' : undefined"><ElIcon><Search /></ElIcon>搜索服务</RouterLink><RouterLink to="/system/ai/features" :aria-current="section === 'features' ? 'page' : undefined"><ElIcon><Setting /></ElIcon>功能配置</RouterLink></nav>
    <ElAlert v-if="error" :title="error" type="error" :closable="false" show-icon><ElButton v-if="!ready" text @click="load">重新加载</ElButton></ElAlert>
    <p v-if="loading && !ready" role="status" class="ai-loading">正在读取 AI 配置…</p>
    <template v-if="ready && section !== 'features'">
      <div class="ai-section-heading"><div><h2>{{ kind === 'MODEL' ? '我的模型服务' : '我的搜索服务' }} <span class="count-badge">{{ services.length }}</span></h2><p class="muted">{{ kind === 'MODEL' ? '可配置多个厂商和模型，再在功能配置中按需绑定。' : '集中管理搜索接口；本次仅提供查询测试，不参与歌曲分类。' }}</p></div><ElButton type="primary" :icon="Plus" :disabled="busy || loading" @click="openEditor()">{{ kind === 'MODEL' ? '添加模型服务' : '添加搜索服务' }}</ElButton></div>
      <div v-if="kind === 'SEARCH'" class="ai-default-search"><span>默认搜索服务</span><ElSelect :model-value="defaultSearch" placeholder="尚未选择" clearable :disabled="busy" aria-label="默认搜索服务" @change="value => setDefault(value || null)"><ElOption v-for="service in searches.filter(s => s.enabled)" :key="service.id" :value="service.id" :label="service.name" /></ElSelect><small>供后续 AI 功能使用；选择后不会自动发起查询。</small></div>
      <ElEmpty v-if="!services.length" :description="kind === 'MODEL' ? '添加第一个模型服务，连接你的 AI 能力' : '添加搜索服务，为后续联网能力做好准备'" />
      <div class="ai-service-grid"><ElCard v-for="service in services" :key="service.id" shadow="never" class="ai-service-card"><div class="ai-service-heading"><span class="ai-service-icon"><ElIcon><component :is="kind === 'MODEL' ? MagicStick : Search" /></ElIcon></span><div><h3>{{ service.name }}</h3><span>{{ providerName(service.provider) }}</span></div><ElTag :type="service.enabled ? 'success' : 'info'">{{ service.enabled ? '已启用' : '已停用' }}</ElTag></div><dl><dt>{{ kind === 'MODEL' ? '模型 ID' : '接口协议' }}</dt><dd>{{ kind === 'MODEL' ? service.model : service.protocol }}</dd><dt>服务地址</dt><dd :title="service.endpoint">{{ service.endpoint }}</dd><dt>凭据</dt><dd>{{ service.hasKey ? '密钥已保存' : '未配置密钥' }} · {{ service.timeoutSeconds }} 秒超时</dd></dl><div class="ai-service-footer"><ElButton :icon="Connection" :disabled="busy" @click="openTest(service)">{{ kind === 'MODEL' ? '测试服务' : '测试查询' }}</ElButton><div><ElButton text :disabled="busy" @click="openEditor(service)">编辑</ElButton><ElButton text type="danger" :disabled="busy" @click="remove(service)">删除</ElButton></div></div></ElCard></div>
      <div class="ai-provider-list"><span>支持的服务</span><span v-for="provider in providers.filter(p => p.kind === kind)" :key="provider.id">{{ provider.name }}</span></div>
    </template>
    <template v-if="ready && section === 'features' && !assistantPage">
      <ElCard shadow="never" class="ai-feature-card"><div class="ai-feature-header"><span class="ai-service-icon"><ElIcon><MagicStick /></ElIcon></span><div><h2>歌曲分类</h2><p class="muted">为分类任务选择模型；任务和词表仍在曲库管理中。</p></div><ElSwitch v-model="classification.enabled" aria-label="启用歌曲 AI 分类" :disabled="busy" /></div><ElForm label-position="top" class="ai-feature-form" @submit.prevent="saveFeature"><ElFormItem label="分类使用的模型服务"><ElSelect v-model="classification.modelServiceId" clearable placeholder="选择已启用的模型服务" aria-label="分类模型服务" :disabled="busy"><ElOption v-for="service in models.filter(m => m.enabled)" :key="service.id" :value="service.id" :label="`${service.name} · ${service.model}`" /></ElSelect></ElFormItem><ElFormItem label="分类任务请求间隔（秒）"><ElInputNumber v-model="classification.intervalSeconds" :min="2" :max="300" :disabled="busy" /></ElFormItem><p class="muted">修改配置将暂停活动分类任务，已有任务继续使用原配置快照。测试连接与结构化输出请在模型服务中执行。</p><div class="ai-feature-actions"><ElButton type="primary" native-type="submit" :loading="busy" :disabled="!featureDirty">保存功能配置</ElButton><RouterLink to="/library/classification">前往歌曲分类 <ElIcon><ArrowRight /></ElIcon></RouterLink></div></ElForm></ElCard>
      <ElCard shadow="never" class="assistant-feature-entry"><div class="ai-feature-header"><span class="ai-service-icon"><ElIcon><MagicStick /></ElIcon></span><div><h2>AI 助手</h2><p class="muted">管理右下角助手入口、展示形态和宠物形象。</p></div><ElTag :type="assistant.enabled ? 'success' : 'info'">{{ assistant.enabled ? '已开启' : '未开启' }}</ElTag></div><div class="ai-feature-actions"><RouterLink to="/system/ai/features/assistant">配置 AI 助手 <ElIcon><ArrowRight /></ElIcon></RouterLink><span class="muted">对话与工具能力规划中</span></div></ElCard>
      <p class="feature-note">模型、搜索服务与功能配置独立管理。后续可为助手等功能独立增加模型、搜索及工具能力配置。</p>
    </template>

    <template v-if="ready && assistantPage">
      <RouterLink to="/system/ai/features" class="assistant-back">← 返回功能配置</RouterLink>
      <ElCard shadow="never" class="ai-feature-card">
        <div class="ai-feature-header"><span class="ai-service-icon"><ElIcon><MagicStick /></ElIcon></span><div><h2>AI 助手</h2><p class="muted">为你的音乐工作台，添一份陪伴。</p></div><ElSwitch v-model="assistant.enabled" aria-label="启用 AI 助手" :disabled="busy" /></div>
        <ElForm label-position="top" class="assistant-settings-form" @submit.prevent="saveAssistant">
          <ElFormItem label="助手展示形态"><ElRadioGroup v-model="assistant.displayMode" aria-label="助手展示形态" :disabled="busy"><ElRadioButton value="PET">宠物形态</ElRadioButton><ElRadioButton value="CHAT">聊天框形态</ElRadioButton></ElRadioGroup></ElFormItem>
          <div v-if="assistant.displayMode === 'PET'" class="assistant-pet-options"><h3>宠物形象</h3><ElRadioGroup v-model="assistant.petId" aria-label="宠物形象" :disabled="busy"><ElRadioButton v-for="pet in assistantPets" :key="pet.id" :value="pet.id"><component :is="pet.component" still /><strong>{{ pet.name }}</strong><small>{{ pet.description }}</small></ElRadioButton></ElRadioGroup><p class="muted">后续可扩展更多宠物形象，当前使用本地绘制资源。</p></div>
          <div v-else class="assistant-chat-preview"><ElIcon :size="24"><Connection /></ElIcon><div><strong>AI 助手</strong><p>简洁聊天入口，常驻页面右下角。</p></div></div>
          <ElAlert title="对话与工具能力规划中" description="本次开放助手展示与入口配置，不会调用模型、搜索服务或修改曲库。保存后应用到所有管理页面，亲友 H5 不显示。" type="info" :closable="false" show-icon />
          <div class="ai-feature-actions"><ElButton type="primary" native-type="submit" :loading="busy" :disabled="!assistantDirty">保存助手配置</ElButton><span class="muted">{{ assistant.enabled ? '保存后显示可拖动的助手入口' : '保存后隐藏助手入口' }}</span></div>
        </ElForm>
      </ElCard>
    </template>

    <ElDialog class="ai-service-dialog" :model-value="editing" :title="`${editingId ? '编辑' : '添加'}${editorKind === 'MODEL' ? '模型' : '搜索'}服务`" width="min(620px, calc(100vw - 32px))" :close-on-click-modal="false" :close-on-press-escape="!busy" :show-close="!busy" :before-close="() => closeEditor()">
      <ElForm label-position="top" @submit.prevent="saveService"><div class="ai-form-grid"><ElFormItem label="配置名称" required><ElInput v-model="draft.name" maxlength="100" placeholder="例如：日常分类模型" :disabled="busy" /></ElFormItem><ElFormItem label="服务厂商"><ElSelect v-model="draft.provider" :disabled="busy" @change="changeProvider"><ElOption v-for="provider in availableProviders" :key="provider.id" :label="provider.name" :value="provider.id" /></ElSelect></ElFormItem></div><template v-if="editorKind === 'MODEL'"><ElFormItem label="接口协议"><ElSelect v-model="draft.protocol" :disabled="busy" @change="() => { if (draft.protocol === 'ANTHROPIC') draft.outputMode = 'JSON_SCHEMA' }"><ElOption label="OpenAI Responses" value="RESPONSES" /><ElOption label="Chat Completions 兼容" value="CHAT_COMPLETIONS" /><ElOption label="Anthropic Messages" value="ANTHROPIC" /><ElOption label="Gemini GenerateContent" value="GEMINI" /></ElSelect></ElFormItem><ElFormItem label="模型 ID" required><ElInput v-model="draft.model" maxlength="200" placeholder="填写厂商提供的模型 ID" :disabled="busy" /></ElFormItem></template><ElFormItem :label="draft.protocol === 'GEMINI' ? 'API 基础地址（例如 /v1beta）' : '完整服务接口地址'" required><ElInput v-model="draft.endpoint" maxlength="2048" placeholder="https://服务地址/接口路径" :disabled="busy" /></ElFormItem><ElFormItem :label="editingService?.hasKey ? 'API 密钥（留空保留）' : 'API 密钥（本地无鉴权服务可留空）'"><ElInput v-model="draft.apiKey" type="password" show-password autocomplete="new-password" maxlength="4096" :disabled="busy || draft.clearKey" /></ElFormItem><ElCheckbox v-if="editingService?.hasKey" v-model="draft.clearKey" :disabled="busy" @change="draft.apiKey = ''">清除已保存密钥</ElCheckbox><p class="ai-form-hint">更换厂商、协议或地址时，请重填密钥或明确清除。保存配置不会自动调用服务。</p><div class="ai-form-grid"><ElFormItem label="请求超时（秒）"><ElInputNumber v-model="draft.timeoutSeconds" :min="5" :max="180" :disabled="busy" /></ElFormItem><ElFormItem v-if="editorKind === 'MODEL'" label="分类结构化输出模式"><ElSelect v-model="draft.outputMode" :disabled="busy || draft.protocol === 'ANTHROPIC'"><ElOption label="JSON Schema" value="JSON_SCHEMA" /><ElOption label="JSON 模式 + 本地校验" value="JSON_OBJECT" /></ElSelect></ElFormItem><ElFormItem v-else label="最多返回结果"><ElInputNumber v-model="draft.maxResults" :min="1" :max="10" :disabled="busy" /></ElFormItem></div><ElSwitch v-model="draft.enabled" active-text="启用此服务" :disabled="busy" /><ElAlert v-if="formError" :title="formError" type="error" :closable="false" class="ai-form-error" /><div class="dialog-actions"><ElButton :disabled="busy" @click="closeEditor">取消</ElButton><ElButton type="primary" native-type="submit" :loading="busy">保存服务</ElButton></div></ElForm>
    </ElDialog>
    <ElDialog class="ai-test-dialog" :model-value="!!testing" :title="testing ? `测试 · ${testing.name}` : '服务测试'" width="min(620px, calc(100vw - 32px))" :close-on-click-modal="false" :show-close="!testingBusy" :close-on-press-escape="!testingBusy" :before-close="() => closeTest()">
      <template v-if="testing"><p class="ai-form-hint">测试将向已保存的服务发起实际请求，可能消耗服务额度；不会修改曲库。</p><ElForm label-position="top" @submit.prevent="test"><ElFormItem v-if="testing.kind === 'MODEL'" label="验证类型"><ElSelect v-model="testMode" :disabled="testingBusy" @change="testResult = null; testError = ''"><ElOption label="普通生成连接" value="generation" /><ElOption label="歌曲分类结构化输出" value="classification" /></ElSelect></ElFormItem><ElFormItem v-else label="查询关键词"><ElInput v-model="query" maxlength="200" placeholder="输入测试关键词" :disabled="testingBusy" @input="testResult = null; testError = ''" /></ElFormItem><ElAlert v-if="testError" :title="testError" type="error" :closable="false" /><ElAlert v-if="testResult" :title="`${testResult.message} · ${testResult.elapsedMs} ms`" type="success" :closable="false" /><div v-if="testResult?.results.length" class="ai-search-results"><article v-for="hit in testResult.results" :key="hit.url"><a :href="hit.url" target="_blank" rel="noopener noreferrer">{{ hit.title }}</a><small>{{ hit.url }}</small><p>{{ hit.snippet }}</p></article></div><div class="dialog-actions"><ElButton :disabled="testingBusy" @click="closeTest">关闭</ElButton><ElButton type="primary" native-type="submit" :loading="testingBusy">{{ testingBusy ? '正在测试' : '开始测试' }}</ElButton></div></ElForm></template>
    </ElDialog>
  </section>
</template>

<style scoped>
:global(.ai-service-dialog.el-dialog), :global(.ai-test-dialog.el-dialog) { display: flex; flex-direction: column; max-height: 88dvh; }
:global(.ai-service-dialog.el-dialog > .el-dialog__header), :global(.ai-test-dialog.el-dialog > .el-dialog__header) { flex-shrink: 0; }
:global(.ai-service-dialog.el-dialog > .el-dialog__body), :global(.ai-test-dialog.el-dialog > .el-dialog__body) { overflow-y: auto; min-height: 0; }

.assistant-feature-entry{margin-top:24px}.assistant-back{display:inline-block;margin-bottom:20px;font-size:13px}.assistant-settings-form{max-width:760px;margin-top:30px}.assistant-pet-options{margin:24px 0}.assistant-pet-options h3{font-size:14px;margin-bottom:16px}.assistant-pet-options :deep(.el-radio-button__inner){display:flex;flex-direction:column;align-items:center;gap:10px;padding:20px 24px;border-radius:14px!important;border:1px solid var(--sv-border-default)!important;white-space:normal;background:var(--sv-surface-1)!important;color:var(--sv-text-primary)!important;box-shadow:none!important}.assistant-pet-options :deep(.el-radio-button.is-active .el-radio-button__inner){border-color:var(--sv-accent-primary)!important;box-shadow:0 0 0 2px var(--sv-accent-soft)!important;background:var(--sv-surface-2)!important}.assistant-pet-options small{font-size:12px;font-weight:400;color:var(--sv-text-secondary)}.assistant-pet-options>p{margin-top:16px;font-size:12px}.assistant-chat-preview{display:flex;gap:14px;align-items:center;border:1px solid var(--sv-border-default);border-radius:14px;background:var(--sv-surface-2);padding:22px;margin:24px 0;color:var(--sv-accent-primary)}.assistant-chat-preview p{font-size:12px;margin-top:6px;color:var(--sv-text-secondary)}
.ai-tabs { display: flex; gap: 8px; border-bottom: 1px solid var(--sv-border-subtle); margin-bottom: 28px; overflow-x: auto; }.ai-tabs a { display: inline-flex; flex-shrink: 0; align-items: center; gap: 8px; color: var(--sv-text-secondary); padding: 14px 18px; border-bottom: 2px solid transparent; font-size: 14px; }.ai-tabs a[aria-current] { color: var(--sv-accent-primary); border-color: var(--sv-accent-primary); font-weight: 600; }.ai-section-heading { display: flex; justify-content: space-between; gap: 16px; align-items: center; margin-bottom: 24px; }.ai-section-heading h2 { margin-bottom: 8px; }.ai-service-grid { display: grid; grid-template-columns: repeat(2,minmax(0,1fr)); gap: 20px; }.ai-service-heading { display: flex; gap: 14px; align-items: center; }.ai-service-heading > div { flex: 1; min-width: 0; }.ai-service-heading h3 { overflow-wrap: anywhere; }.ai-service-heading > div > span { display: block; margin-top: 4px; color: var(--sv-text-secondary); font-size: 12px; }.ai-service-icon { display: grid; place-items: center; width: 42px; height: 42px; border-radius: 12px; background: var(--sv-accent-soft); color: var(--sv-accent-primary); font-size: 22px; flex-shrink: 0; }.ai-service-card dl { display: grid; grid-template-columns: 64px minmax(0,1fr); gap: 12px; margin: 24px 0; font-size: 12px; }.ai-service-card dt { color: var(--sv-text-secondary); }.ai-service-card dd { overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }.ai-service-footer { display: flex; justify-content: space-between; align-items: center; gap: 8px; border-top: 1px solid var(--sv-border-subtle); padding-top: 16px; }.ai-service-footer .el-button { margin: 0; }.ai-provider-list { display: flex; flex-wrap: wrap; gap: 8px; margin-top: 28px; }.ai-provider-list span { padding: 4px 8px; font-size: 11px; color: var(--sv-text-secondary); background: var(--sv-surface-3); border-radius: 5px; }.ai-provider-list span:first-child { padding-left: 0; background: transparent; }.ai-default-search { display: flex; align-items: center; flex-wrap: wrap; gap: 12px; padding: 18px; border: 1px solid var(--sv-border-subtle); background: var(--sv-surface-2); border-radius: 12px; margin-bottom: 24px; font-size: 13px; }.ai-default-search .el-select { width: 220px; }.ai-default-search small { color: var(--sv-text-secondary); }.ai-feature-header { display: flex; gap: 16px; align-items: center; }.ai-feature-header > div { flex: 1; }.ai-feature-header h2 { margin-bottom: 6px; }.ai-feature-form { max-width: 640px; margin-top: 32px; }.ai-feature-actions { display: flex; gap: 20px; align-items: center; flex-wrap: wrap; margin-top: 24px; }.ai-feature-actions a { display: inline-flex; gap: 8px; align-items: center; font-size: 13px; }.ai-form-grid { display: grid; grid-template-columns: repeat(2,minmax(0,1fr)); gap: 16px; }.ai-form-grid .el-input-number { width: 100%; }.ai-form-hint { font-size: 12px; color: var(--sv-text-secondary); line-height: 1.8; margin-bottom: 20px; }.ai-form-error { margin-top: 16px; }.ai-search-results { max-height: 40vh; overflow: auto; margin-top: 20px; }.ai-search-results article { padding: 16px 0; border-bottom: 1px solid var(--sv-border-subtle); }.ai-search-results a { font-weight: 600; }.ai-search-results small { display: block; color: var(--sv-text-tertiary); margin-top: 6px; overflow-wrap: anywhere; }.ai-search-results p { margin-top: 8px; font-size: 13px; color: var(--sv-text-secondary); overflow-wrap: anywhere; }.ai-loading { padding: 32px 0; color: var(--sv-text-secondary); }
@media(max-width:1000px) { .ai-service-grid { grid-template-columns: 1fr; } }@media(max-width:768px) { .ai-tabs { gap: 0; }.ai-tabs a { padding: 12px; font-size: 13px; }.ai-section-heading { align-items: flex-start; flex-direction: column; }.ai-form-grid { grid-template-columns: 1fr; gap: 0; }.ai-feature-header { align-items: flex-start; }.ai-feature-header h2 { font-size: 17px; }.ai-feature-header .muted { font-size: 12px; }.ai-default-search .el-select { width: 100%; }.ai-service-heading { gap: 10px; }.ai-service-heading h3 { font-size: 15px; } }
</style>
