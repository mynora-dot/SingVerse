<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { ElAlert, ElButton, ElCheckbox, ElDialog, ElMessage, ElMessageBox, ElTag } from 'element-plus'
import { classification } from '../api/classification'
import { dimensionNames, originNames, type ClassState, type Dimension, type History, type Tag } from '../types'
function toggleList(list: Dimension[], dim: Dimension, checked: boolean) { const at = list.indexOf(dim); if (checked && at < 0) list.push(dim); else if (!checked && at >= 0) list.splice(at, 1) }

const props = defineProps<{ songIds: string[] }>()
const emit = defineEmits<{ close: []; changed: [] }>()
const states = ref<ClassState[]>([]), tags = ref<Tag[]>([]), history = ref<History[]>([]), historyPage = ref(0)
const selected = ref<Dimension[]>([]), values = ref<Partial<Record<Dimension, string[]>>>({}), locked = ref<Dimension[]>([])
const loading = ref(true), busy = ref(false), error = ref('')
const attempts = new Map<string, { body: string; id: string }>()
function names(ids: string[]) { return ids.map(id => tags.value.find(t => t.id === id)?.name ?? id).join('、') || '未知 / 无标签' }
async function loadHistory() { if (props.songIds.length === 1) history.value = await classification.history(props.songIds[0]!, historyPage.value) }
async function load() {
  loading.value = true; error.value = ''
  try { const [vocabulary, current] = await Promise.all([classification.tags(), Promise.all(props.songIds.map(id => classification.state(id)))]); tags.value = vocabulary; states.value = current
    if (current.length === 1) { for (const [dim, value] of Object.entries(current[0]!.dimensions)) { values.value[dim as Dimension] = [...value.tagIds]; if (value.locked) locked.value.push(dim as Dimension) } }
    await loadHistory()
  } catch (e) { error.value = e instanceof Error ? e.message : '分类读取失败' } finally { loading.value = false }
}
function toggle(dim: Dimension, id: string, checked: boolean) { const ids = values.value[dim] ?? []; values.value[dim] = checked ? [...ids, id] : ids.filter(t => t !== id) }
async function save() {
  if (busy.value || !selected.value.length || states.value.length !== props.songIds.length) return
  if (selected.value.some(dim => !(values.value[dim]?.length))) { try { await ElMessageBox.confirm('选中维度中存在空标签，将明确清除这些维度的现有标签。未选中维度保留。', '确认清除分类', { type: 'warning' }) } catch { return } }
  busy.value = true; error.value = ''; let succeeded = 0
  try {
    for (let i = 0; i < states.value.length; i++) {
      const current = states.value[i]!, dimensions = Object.fromEntries(selected.value.map(dim => [dim, values.value[dim] ?? []]))
      const body = JSON.stringify({ revision: current.revision, dimensions, lockedDimensions: locked.value.filter(dim => selected.value.includes(dim)) })
      let attempt = attempts.get(current.songId); if (!attempt || attempt.body !== body) { attempt = { body, id: crypto.randomUUID() }; attempts.set(current.songId, attempt) }
      try { states.value[i] = await classification.change(current.songId, { ...JSON.parse(body), requestId: attempt.id }); succeeded++ }
      catch (e) { error.value += `${current.songId}：${e instanceof Error ? e.message : '保存失败'}；` }
    }
    if (succeeded) { ElMessage.success(`已保存 ${succeeded} 首歌曲的分类`); emit('changed') }
    await loadHistory()
  } finally { busy.value = false }
}
async function undo(h: History) {
  try { await ElMessageBox.confirm('仅在分类和锁定状态没有后续变化时撤回。原媒体和刮削信息不会修改。', '撤回此次分类') } catch { return }
  busy.value = true; error.value = ''
  try { states.value[0] = await classification.undo(h.id, crypto.randomUUID(), states.value[0]!.revision); await loadHistory(); emit('changed'); ElMessage.success('分类已撤回') }
  catch (e) { error.value = e instanceof Error ? e.message : '撤回失败' } finally { busy.value = false }
}
onMounted(load)
</script>
<template>
  <ElDialog class="classification-manual-dialog" :model-value="true" :title="`手动整理分类（${songIds.length} 首）`" width="min(880px, calc(100vw - 24px))" :close-on-click-modal="false" :close-on-press-escape="!busy" :show-close="!busy" @close="emit('close')">
    <ElAlert v-if="error" :title="error" type="error" :closable="false" />
    <p v-if="loading">正在读取当前分类…</p>
    <template v-else>
      <p class="panel-description">勾选想调整的分类内容，再选择标签。其他分类保持原样；保存空标签将清除该项分类。</p>
      <div v-for="(name, dim) in dimensionNames" :key="dim" class="edit-dimension">
        <div class="dimension-heading"><ElCheckbox :model-value="selected.includes(dim)" @change="v => toggleList(selected, dim, !!v)" :disabled="busy">修改{{ name }}</ElCheckbox><ElCheckbox :model-value="locked.includes(dim)" @change="v => toggleList(locked, dim, !!v)" :disabled="busy || !selected.includes(dim)" title="锁定后，AI 自动保存和建议确认都不能覆盖这项分类">锁定</ElCheckbox></div>
        <p v-if="states.length === 1" class="panel-description">当前：{{ names(states[0]?.dimensions[dim]?.tagIds ?? []) }} · {{ originNames[states[0]?.dimensions[dim]?.origin ?? ''] ?? '尚未分类' }} {{ states[0]?.dimensions[dim]?.locked ? '· 已锁定' : '' }}</p>
        <div v-if="selected.includes(dim)" class="tag-options"><ElCheckbox v-for="tag in tags.filter(t => t.dimension === dim && t.active)" :key="tag.id" :model-value="(values[dim] ?? []).includes(tag.id)" :disabled="busy || !selected.includes(dim)" @change="v => toggle(dim, tag.id, !!v)">{{ tag.name }}</ElCheckbox></div>
      </div>
      <details v-if="states.length === 1" class="history"><summary>查看变更历史与撤回</summary><div v-for="h in history" :key="h.id" class="history-row"><div><ElTag size="small">{{ originNames[h.origin] }}</ElTag> {{ new Date(h.createdAt).toLocaleString() }}<p v-for="dim in Object.keys(dimensionNames) as Dimension[]" :key="dim"><template v-if="JSON.stringify(h.before[dim]) !== JSON.stringify(h.after[dim])">{{ dimensionNames[dim] }}：{{ names(h.before[dim]?.tagIds ?? []) }} → {{ names(h.after[dim]?.tagIds ?? []) }}</template></p></div><ElButton :disabled="busy || h.reverted || h.origin === 'UNDO' || h.afterRevision !== states[0]?.revision" @click="undo(h)">{{ h.reverted ? '已撤回' : '撤回' }}</ElButton></div><ElButton :disabled="busy || historyPage === 0" @click="historyPage--; loadHistory().catch(e => error = String(e))">上一页</ElButton><ElButton :disabled="busy || history.length < 20" @click="historyPage++; loadHistory().catch(e => error = String(e))">下一页</ElButton></details>
    </template>
    <template #footer><ElButton :disabled="busy" @click="emit('close')">关闭</ElButton><ElButton type="primary" :loading="busy" :disabled="loading || !selected.length || states.length !== songIds.length" @click="save">保存分类修改</ElButton></template>
  </ElDialog>
</template>
<style scoped>
.edit-dimension { padding: 10px 0; border-bottom: 1px solid var(--el-border-color-light); }
.dimension-heading,.tag-options { display: flex; flex-wrap: wrap; gap: 8px 18px; }
.tag-options .el-checkbox { margin-right: 0; }
.history { margin-top: 20px; }.history-row { display: flex; justify-content: space-between; gap: 12px; padding: 12px 0; border-bottom: 1px solid var(--el-border-color-light); }.history-row p { font-size: 12px; overflow-wrap: anywhere; }
.panel-description{font-size:12px;color:var(--sv-text-secondary);line-height:1.9;margin:8px 0}.edit-dimension{padding:14px 0}.dimension-heading{justify-content:space-between;align-items:center}.dimension-heading>.el-checkbox:last-child{font-size:12px;color:var(--sv-text-secondary)}.tag-options{background:var(--sv-surface-2);padding:12px 14px;border-radius:10px;margin-top:12px}.history summary{font-size:13px;cursor:pointer;padding:14px 0;color:var(--sv-text-secondary)}
:global(.classification-manual-dialog.el-dialog){display:flex;flex-direction:column;max-height:88dvh}:global(.classification-manual-dialog>.el-dialog__body){overflow-y:auto;min-height:0}:global(.classification-manual-dialog>.el-dialog__header),:global(.classification-manual-dialog>.el-dialog__footer){flex-shrink:0}
</style>
