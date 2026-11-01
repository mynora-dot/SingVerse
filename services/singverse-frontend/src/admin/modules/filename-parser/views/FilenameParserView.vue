<script setup lang="ts">
import PageHeader from '../../../shared/ui/PageHeader.vue'
import { computed, nextTick, onBeforeUnmount, onMounted, ref } from 'vue'
import { ElAlert, ElButton, ElCard, ElDialog, ElForm, ElFormItem, ElInput, ElMessage, ElTable, ElTableColumn, ElTag } from 'element-plus'
import { Plus } from '@element-plus/icons-vue'
import { ApiError } from '../../../core/auth/api'
import { ruleLibrary } from '../api/rules'
import type { ParserWorkspace, RuleDefinition, RuleInput } from '../types'
import { copyValue, errorMessage, expressionFor } from '../utils'

const workspace = ref<ParserWorkspace | null>(null)
const loading = ref(false)
const busy = ref(false)
const error = ref('')
const conflict = ref(false)
const referencedBy = ref<{ id: string; name: string }[]>([])
const editing = ref(false)
const editorId = ref<string | null>(null)
const editor = ref<RuleInput>({ name: '', type: 'TEMPLATE', expression: '{artist}-{title}', builtinId: null })
const editorError = ref('')
const expressionInput = ref<HTMLTextAreaElement>()
const fields = ['artist', 'title', 'code', 'language', 'version', 'quality', 'extra']
const affected = computed(() => workspace.value?.definitions.find(d => d.id === editorId.value)?.referencedBy ?? [])
let active = true
onBeforeUnmount(() => { active = false })

async function load() {
  loading.value = true
  error.value = ''
  try {
    const response = await ruleLibrary.load()
    if (!active) return
    workspace.value = response
    conflict.value = false
    if (editing.value) ElMessage.info('已加载最新规则库，编辑内容已保留；请核对引用方案后重新保存。')
  } catch (failure) { if (active) error.value = errorMessage(failure) }
  finally { if (active) loading.value = false }
}
function edit(rule?: RuleDefinition, copy = false) {
  if (busy.value || loading.value || (rule?.type === 'BUILTIN' && !copy)) return
  editorId.value = copy ? null : rule?.id ?? null
  const preset = workspace.value?.builtinRules.find(p => p.id === rule?.builtinId)
  editor.value = {
    name: rule ? (copy ? `${rule.name}（副本）`.slice(0, 100) : rule.name) : '',
    type: preset?.type ?? (rule?.type === 'REGEX' ? 'REGEX' : 'TEMPLATE'),
    expression: rule ? expressionFor(rule, workspace.value) : '{artist}-{title}', builtinId: null,
  }
  editorError.value = ''
  editing.value = true
}
async function insertField(field: string) {
  const input = expressionInput.value
  const start = input?.selectionStart ?? editor.value.expression.length
  const end = input?.selectionEnd ?? start
  const text = `{${field}}`
  editor.value.expression = editor.value.expression.slice(0, start) + text + editor.value.expression.slice(end)
  await nextTick(); input?.focus(); input?.setSelectionRange(start + text.length, start + text.length)
}
function failure(errorValue: unknown) {
  const text = errorMessage(errorValue)
  if (errorValue instanceof ApiError) {
    conflict.value = errorValue.status === 409 && !errorValue.details?.referencedBy?.length
    referencedBy.value = errorValue.details?.referencedBy ?? []
    return `${errorValue.details?.field ? `${errorValue.details.field}：` : ''}${text}`
  }
  return text
}
async function save() {
  if (!workspace.value || busy.value || conflict.value) return
  if (!editor.value.name.trim() || !editor.value.expression.trim()) { editorError.value = '请填写名称和表达式。'; return }
  busy.value = true; editorError.value = ''; error.value = ''
  try {
    const input = copyValue(editor.value); input.name = input.name.trim()
    const response = editorId.value ? await ruleLibrary.update(editorId.value, workspace.value.revision, input) : await ruleLibrary.create(workspace.value.revision, input)
    if (!active) return
    workspace.value = response; editing.value = false; referencedBy.value = []
    ElMessage.success('规则已保存，引用方案将在下次预览时使用新定义。')
  } catch (errorValue) { if (active) editorError.value = failure(errorValue) }
  finally { if (active) busy.value = false }
}
async function remove(rule: RuleDefinition) {
  if (!workspace.value || busy.value || loading.value) return
  busy.value = true; error.value = ''; referencedBy.value = []
  try { const response = await ruleLibrary.remove(rule.id, workspace.value.revision); if (active) workspace.value = response }
  catch (errorValue) { if (active) error.value = failure(errorValue) }
  finally { if (active) busy.value = false }
}
onMounted(() => { void load() })
</script>

<template>
  <section class="rules-page management-page">
    <PageHeader title="自定义规则"><template #description>高级功能：仅在需要自己编写模板或正则时使用。日常在<RouterLink class="library-link" to="/library/filename-parser-profiles">解析方案</RouterLink>中点选格式即可。</template></PageHeader>
    <ElAlert v-if="error" :title="error" type="error" :closable="false" role="alert"><RouterLink v-for="profile in referencedBy" :key="profile.id" :to="{ path: '/library/filename-parser-profiles', query: { profile: profile.id } }">{{ profile.name }} </RouterLink></ElAlert>
    <ElButton v-if="error || conflict" :disabled="loading" @click="load">加载最新规则库，保留编辑内容</ElButton>
    <p v-if="loading" role="status">正在读取规则库…</p>
    <ElCard v-if="workspace" shadow="never" class="rules-card">
      <div class="toolbar"><div><h2>规则库 <span class="count-badge">{{ workspace.definitions.length }}</span></h2><p class="panel-description">自定义的模板和正则在这里维护，方案按顺序引用它们；内置格式不用来这一页启用。</p></div><ElButton type="primary" :icon="Plus" :disabled="busy || loading || workspace.definitions.length >= 1000" @click="edit()">新增规则</ElButton></div>
      <ElTable :data="workspace.definitions" row-key="id" empty-text="暂无规则，可新增模板或正则。">
        <ElTableColumn label="规则名称" min-width="210"><template #default="{ row }"><strong class="rule-name">{{ row.name }}</strong><ElTag size="small" :type="row.type === 'BUILTIN' ? 'info' : 'success'" effect="plain">{{ row.type === 'BUILTIN' ? '内置规则' : row.type === 'REGEX' ? '高级正则' : '字段模板' }}</ElTag></template></ElTableColumn>
        <ElTableColumn label="匹配表达式" min-width="260"><template #default="{ row }"><div class="expression-cell"><code>{{ expressionFor(row as RuleDefinition, workspace) }}</code></div></template></ElTableColumn>
        <ElTableColumn label="引用方案" min-width="170"><template #default="{ row }"><span class="reference-count">{{ row.referencedBy.length }} 个方案</span><div v-for="profile in row.referencedBy" :key="profile.id"><RouterLink class="profile-reference" :to="{ path: '/library/filename-parser-profiles', query: { profile: profile.id } }">{{ profile.name }} ↗</RouterLink></div></template></ElTableColumn>
        <ElTableColumn label="操作" width="180" fixed="right"><template #default="{ row }"><ElButton link type="primary" size="small" :disabled="busy || loading" :aria-label="`复制 ${row.name}`" @click="edit(row as RuleDefinition, true)">复制</ElButton><ElButton v-if="row.type !== 'BUILTIN'" link type="primary" size="small" :disabled="busy || loading" :aria-label="`编辑 ${row.name}`" @click="edit(row as RuleDefinition)">编辑</ElButton><ElButton v-if="row.type !== 'BUILTIN'" link type="danger" size="small" :disabled="busy || loading" :aria-label="`删除 ${row.name}`" @click="remove(row as RuleDefinition)">删除</ElButton></template></ElTableColumn>
      </ElTable>
      <p class="feature-note">匹配对象为标准化后去掉扩展名的完整名称，仍保留标签。规则仅指定字段含义，不核实歌手身份。</p>
    </ElCard>
    <ElDialog v-model="editing" title="编辑解析规则" width="min(720px, 94vw)" :close-on-click-modal="false" :close-on-press-escape="!busy" :show-close="!busy">
      <ElForm label-position="top" @submit.prevent="save"><fieldset :disabled="busy || loading" class="editor-fields">
        <ElFormItem label="规则名称"><ElInput v-model="editor.name" maxlength="100" aria-label="规则名称" :disabled="busy || loading" /></ElFormItem>
        <ElFormItem label="规则类型"><select v-model="editor.type" class="native-field" aria-label="规则类型"><option value="TEMPLATE">字段模板</option><option value="REGEX">高级正则（RE2）</option></select></ElFormItem>
        <div v-if="editor.type === 'TEMPLATE'" class="field-buttons"><ElButton v-for="field in fields" :key="field" size="small" :disabled="busy || loading" @click="insertField(field)">{{ '{' + field + '}' }}</ElButton></div>
        <ElFormItem label="表达式"><textarea ref="expressionInput" v-model="editor.expression" rows="5" maxlength="2048" class="native-field" aria-label="规则表达式" spellcheck="false" /></ElFormItem>
        <p v-if="editor.type === 'TEMPLATE'" class="muted">必须有 artist、title，字段不能重复或相邻。例如 {title}-{artist}；模板多解不会任意选择。</p>
        <p v-else class="muted">例如 (?P&lt;title&gt;[^-]+)-(?P&lt;artist&gt;[^-]+)。必须有 artist、title 命名组，可选 code、language、version、quality、extra。不支持回溯引用和前后查找断言。</p>
        <ElAlert v-if="affected.length" :title="`保存会影响 ${affected.length} 个引用方案：${affected.map(p => p.name).join('、')}`" type="warning" :closable="false" />
        <p v-if="editorError" class="inline-error" role="alert">{{ editorError }}</p>
        <div class="dialog-actions">
          <ElButton v-if="conflict" :disabled="busy || loading" @click="load">加载最新规则库，保留编辑内容</ElButton>
          <ElButton :disabled="busy || loading" @click="editing = false">取消</ElButton><ElButton type="primary" native-type="submit" :loading="busy" :disabled="conflict || loading">保存规则</ElButton>
        </div>
      </fieldset></ElForm>
    </ElDialog>
  </section>
</template>

<style scoped>
.rules-page > .el-alert,
.rules-page > .el-button,
.rules-page > p[role='status'] {
  margin-bottom: 20px;
}
.rules-page > p[role='status'] {
  color: var(--sv-text-tertiary);
  font-size: 13px;
}
code {
  display: block;
  white-space: pre-wrap;
  overflow-wrap: anywhere;
}
</style>
