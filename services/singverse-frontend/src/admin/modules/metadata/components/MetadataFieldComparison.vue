<script setup lang="ts">
import { ElCheckbox, ElTag } from 'element-plus'
import { fieldNames } from '../types'
import { reviewFields } from '../single-types'
import type { Values, ReviewField } from '../single-types'
import { equal, present } from '../composables/useSingleSongScrape'
const props = defineProps<{ before: Values; draft: Values; fields: ReviewField[]; edits: Partial<Values>; disabled?: boolean }>()
const emit = defineEmits<{ change: [fields: ReviewField[]] }>()
function toggle(field: ReviewField, checked: unknown) { emit('change', checked ? [...props.fields, field] : props.fields.filter(f => f !== field)) }
function state(field: ReviewField) { if (present(props.draft[field]) && !equal(props.before[field], props.draft[field]) && !props.fields.includes(field)) return '不应用'; return !present(props.draft[field]) ? '无候选值' : equal(props.before[field], props.draft[field]) ? '无变化' : present(props.before[field]) ? '将修改' : '待补全' }
function displayValue(value: unknown, field: string): string {
  if (!present(value)) return '—'
  if (Array.isArray(value)) return value.join('、')
  if (field === 'durationMs') { const seconds = Math.round(Number(value) / 1000); return `${Math.floor(seconds / 60)}:${String(seconds % 60).padStart(2, '0')}` }
  if (field === 'cover') return '已选择封面'
  return String(value)
}
</script>
<template>
  <div class="field-comparison"><table><thead><tr><th>应用字段</th><th>当前值</th><th>候选 / 编辑值</th><th>状态</th></tr></thead><tbody>
    <tr v-for="field in reviewFields" :key="field"><th><ElCheckbox :aria-label="`应用${fieldNames[field]}`" :model-value="fields.includes(field)" :disabled="disabled || !present(draft[field]) || equal(before[field], draft[field])" @change="value => toggle(field, value)">{{ fieldNames[field] }}</ElCheckbox></th>
      <td>{{ displayValue(before[field], field) }}</td><td><span>{{ displayValue(draft[field], field) }}</span><small v-if="Object.hasOwn(edits, field)" class="edited">手工编辑</small></td>
      <td><ElTag :type="state(field) === '将修改' ? 'warning' : state(field) === '待补全' ? 'success' : 'info'" size="small">{{ state(field) }}</ElTag></td></tr>
  </tbody></table></div>
</template>
<style scoped>
.field-comparison { overflow-x: auto; }
table { width: 100%; border-collapse: collapse; font-size: 12px; table-layout: fixed; }
th, td { text-align: left; padding: 10px 8px; border-bottom: 1px solid var(--sv-line); vertical-align: middle; overflow-wrap: anywhere; }
thead th { color: var(--sv-text-muted); font-weight: 500; background: var(--sv-surface-muted); }
th:first-child { width: 98px; } th:last-child { width: 74px; }
:deep(.el-checkbox) { margin-right: 0; white-space: normal; height: auto; }
:deep(.el-checkbox__label) { font-size: 12px; padding-left: 6px; }
td { color: var(--sv-text-muted); }.edited { display: block; color: var(--el-color-primary); margin-top: 4px; }
</style>
