<script setup lang="ts">
import { reactive, ref, watch } from 'vue'
import { ElDialog, ElButton, ElInput, ElTabs, ElTabPane, ElMessageBox } from 'element-plus'
import { fieldNames } from '../types'
import { reviewFields } from '../single-types'
import type { Values, ReviewField } from '../single-types'
const props = defineProps<{ open: boolean; before: Values; draft: Values; original: Values | null }>()
const emit = defineEmits<{ close: []; save: [values: Values]; cover: [] }>()
let baseline = ''
const inputs = reactive<Record<string, string>>({}), errors = ref<Record<string, string>>({}), tab = ref('fields')
watch(() => props.open, open => {
  if (!open) return
  errors.value = {}; tab.value = 'fields'
  for (const field of reviewFields) {
    const value = props.draft[field]
    inputs[field] = Array.isArray(value) ? value.join('、') : field === 'durationMs' && value ? String(Number(value) / 1000) : value == null ? '' : String(value)
  }
  baseline = JSON.stringify(inputs)
})
async function close() {
  if (JSON.stringify(inputs) !== baseline) {
    try { await ElMessageBox.confirm('放弃尚未保存到审核草稿的编辑？', '有未保存的编辑', { confirmButtonText: '放弃编辑', cancelButtonText: '继续编辑' }) } catch { return }
  }
  emit('close')
}
function show(value: unknown) { return value == null || value === '' || (Array.isArray(value) && !value.length) ? '—' : Array.isArray(value) ? value.join('、') : String(value) }
function save() {
  const values = { ...props.draft }, invalid: Record<string, string> = {}
  for (const field of reviewFields) {
    if (field === 'cover') continue
    const value = (inputs[field] ?? '').trim()
    if (/[\x00-\x1f\x7f]/.test(value)) invalid[field] = '不能包含控制字符'
    if (field === 'artists' || field === 'aliases') {
      const list = [...new Set(value.split('、').map(v => v.trim()).filter(Boolean))]
      if (list.length > 20 || list.some(v => v.length > 200)) invalid[field] = '最多 20 项，每项不超过 200 字符'
      values[field] = list
    } else if (field === 'durationMs') {
      if (value && (!/^\d+(\.\d{1,3})?$/.test(value) || Number(value) <= 0 || Number(value) > 86400)) invalid[field] = '输入大于 0、最多 86400 秒，可保留三位小数'
      values[field] = value ? Math.round(Number(value) * 1000) : null
    } else {
      if (value.length > (field === 'releaseDate' ? 10 : 500)) invalid[field] = '文本长度超出限制'
      if (field === 'releaseDate' && value) {
        if (!/^\d{4}(-\d{2}(-\d{2})?)?$/.test(value)) invalid[field] = '格式为 YYYY、YYYY-MM 或 YYYY-MM-DD'
        else {
          const date = value.length === 4 ? `${value}-01-01` : value.length === 7 ? `${value}-01` : value
          const parsed = new Date(`${date}T00:00:00Z`)
          if (Number.isNaN(parsed.getTime()) || parsed.toISOString().slice(0, 10) !== date) invalid[field] = '日期不存在'
        }
      }
      values[field as Exclude<ReviewField, 'artists' | 'aliases' | 'durationMs' | 'cover'>] = value || null
    }
  }
  errors.value = invalid
  if (!Object.keys(invalid).length) emit('save', values)
}
</script>
<template>
  <ElDialog :model-value="open" title="预览与编辑元数据" width="min(760px, calc(100vw - 32px))" append-to-body :close-on-click-modal="false" :before-close="close">
    <p class="editor-hint">编辑仅保存在本次审核草稿中；主窗口点击“确认应用”后才写入。留空不会清除现有值。</p>
    <ElTabs v-model="tab"><ElTabPane label="字段对比与编辑" name="fields"><div class="editor-scroll"><table><thead><tr><th>字段 / 当前值</th><th>平台原始值</th><th>编辑值</th></tr></thead><tbody>
      <tr v-for="field in reviewFields.filter(f => f !== 'cover')" :key="field"><td><strong>{{ fieldNames[field] }}</strong><small>{{ show(before[field]) }}{{ field === 'durationMs' && before[field] ? ' ms' : '' }}</small></td><td>{{ original ? show(original[field]) : '无平台候选' }}{{ field === 'durationMs' && original?.durationMs ? ' ms' : '' }}</td>
        <td><ElInput v-model="inputs[field]" :aria-label="`编辑${fieldNames[field]}`" :maxlength="field === 'artists' || field === 'aliases' ? 4100 : 500" :placeholder="field === 'releaseDate' ? 'YYYY-MM-DD，可只填年/月' : field === 'durationMs' ? '秒，支持三位小数' : field === 'artists' || field === 'aliases' ? '多项用顿号分隔' : '留空保留当前值'" :aria-invalid="!!errors[field]" /><small v-if="errors[field]" role="alert" class="field-error">{{ errors[field] }}</small></td></tr>
    </tbody></table></div></ElTabPane><ElTabPane label="封面" name="cover"><p class="editor-hint">封面使用候选图片或上传至受管存储；不会修改媒体原件。</p><ElButton @click="emit('cover')">选择或上传封面</ElButton></ElTabPane><ElTabPane label="更多信息" name="more"><p class="editor-hint">“当前值”来自入库文件名解析及未失效的确认元数据，不代表已经读取文件内嵌标签。平台时长是外部信息，不代表本地探测结果。</p></ElTabPane></ElTabs>
    <template #footer><ElButton @click="close">取消编辑</ElButton><ElButton type="primary" @click="save">保存编辑到草稿</ElButton></template>
  </ElDialog>
</template>
<style scoped>
.editor-hint { color: var(--sv-text-muted); font-size: 12px; line-height: 1.8; margin-bottom: 16px; }.editor-scroll { max-height: 54vh; overflow: auto; }table { width: 100%; border-collapse: collapse; font-size: 12px; table-layout: fixed; }th,td { border: 1px solid var(--sv-line); padding: 10px; text-align: left; vertical-align: top; overflow-wrap: anywhere; }th { background: var(--sv-surface-muted); font-weight: 500; }th:first-child { width: 24%; }th:nth-child(2) { width: 28%; }small { display: block; margin-top: 5px; color: var(--sv-text-muted); }.field-error { color: var(--el-color-danger); }@media(max-width: 600px) { th:nth-child(2),td:nth-child(2) { display: none; }th:first-child { width: 30%; } }
</style>
