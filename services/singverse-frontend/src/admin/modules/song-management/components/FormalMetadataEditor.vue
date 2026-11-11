<script setup lang="ts">
import { requestId as newRequestId } from '../../../../shared/request-id'
import { computed, reactive, ref, watch } from 'vue'
import { ElAlert, ElButton, ElDialog, ElInput, ElMessage } from 'element-plus'
import { formalLibrary, formalStateLabel, type FormalDocument, type FieldOperation } from '../api/formal'
const props = defineProps<{ document: FormalDocument | null }>()
const emit = defineEmits<{ close: []; saved: [FormalDocument] }>()
const labels: Record<string, string> = { title: '名称', artists: '演唱署名', aliases: '别名', album: '发行名称', releaseDate: '发行日期', referenceDurationMs: '曲目参考时长（毫秒）', kind: '版本类型', languages: '语言', timelineDurationMs: '版本时间轴（毫秒）', lyrics: '歌词资料', cover: '封面' }
const names = computed(() => props.document?.type === 'VARIANT' ? ['title', 'artists', 'kind', 'languages', 'timelineDurationMs', 'lyrics', 'cover'] : ['title', 'artists', 'aliases', 'album', 'releaseDate', 'referenceDurationMs', 'cover'])
const values = reactive<Record<string, string>>({}), choices = reactive<Record<string, 'KEEP' | 'SET' | 'CLEAR' | 'RESET'>>({})
const busy = ref(false), error = ref('')
let attempt: { content: string; id: string } | null = null
watch(() => props.document, document => {
  error.value = ''; attempt = null
  for (const name of names.value) {
    const value = document?.fields[name]
    values[name] = name === 'cover' && value ? String((value as {assetId: string}).assetId) : value == null ? '' : Array.isArray(value) ? value.join('、') : typeof value === 'object' ? JSON.stringify(value, null, 2) : String(value)
    choices[name] = 'KEEP'
  }
}, { immediate: true })
async function save() {
  if (!props.document || busy.value) return
  const operations: FieldOperation[] = []
  try {
    for (const field of names.value) {
      const op = choices[field]
      if (!op || op === 'KEEP') continue
      let value: unknown = values[field] ?? ''
      if (op === 'SET') {
        if (['artists', 'aliases', 'languages'].includes(field)) value = String(value).split('、').map(v => v.trim()).filter(Boolean)
        else if (field.endsWith('Ms')) { if (!/^\d+$/.test(String(value))) throw new Error('时长请输入整数毫秒'); value = Number(value) }
        else if (field === 'cover') value = { assetId: value }
        else if (field === 'lyrics') value = JSON.parse(String(value))
        else if (!String(value).trim()) throw new Error('确认值不能为空；需要移除内容时请选择“明确清空”')
      }
      operations.push({ field, op, ...(op === 'SET' ? { value } : {}) })
    }
    if (!operations.length) return
    busy.value = true; error.value = ''
    const content = JSON.stringify({ revision: props.document.revision, operations })
    if (attempt?.content !== content) attempt = { content, id: newRequestId() }
    const result = await formalLibrary.update(props.document, operations, undefined, attempt.id)
    ElMessage.success('正式资料已保存'); emit('saved', result)
  } catch (failure) { error.value = failure instanceof Error ? failure.message : '保存失败' }
  finally { busy.value = false }
}
async function uploadCover(event: Event) {
  const input = event.target as HTMLInputElement, file = input.files?.[0]; input.value = ''
  if (!file || busy.value) return
  if (!['image/jpeg', 'image/png'].includes(file.type) || file.size > 32 * 1024 * 1024) { error.value = '请选择 32 MiB 以内的 JPEG / PNG'; return }
  busy.value = true; error.value = ''
  try { const asset = await formalLibrary.uploadAsset(file); values.cover = asset.id; choices.cover = 'SET'; ElMessage.success('原图已保存，确认资料后采用') }
  catch (failure) { error.value = failure instanceof Error ? failure.message : '附件保存失败' }
  finally { busy.value = false }
}
</script>
<template>
  <ElDialog :model-value="!!document" title="订正正式资料" width="min(900px, calc(100vw - 24px))" :close-on-click-modal="false" :show-close="!busy" @close="emit('close')">
    <p>确认值会受到本机保护，包括与当前相同的值。明确清空后，自动整理和共享更新不会重新填回；恢复自动采用会重新选择已保存的来源。</p>
    <ElAlert v-if="error" type="error" :title="error" :closable="false" role="alert" />
    <div class="formal-fields">
      <div v-for="name in names" :key="name" class="formal-field">
        <label :for="'formal-' + name">{{ labels[name] }}<small>{{ formalStateLabel(document?.states[name]) }}</small></label>
        <ElInput v-if="name !== 'cover'" :id="'formal-' + name" v-model="values[name]" :aria-label="labels[name]" :type="name === 'lyrics' ? 'textarea' : 'text'" :rows="name === 'lyrics' ? 5 : undefined" :disabled="busy || choices[name] === 'CLEAR' || choices[name] === 'RESET'" @input="choices[name] = 'SET'" />
        <span v-else><img v-if="values.cover && choices.cover !== 'CLEAR'" :src="'/api/v1/metadata/assets/' + values.cover" alt="当前正式封面或待采用原图" class="cover-preview" /><input type="file" accept="image/jpeg,image/png" aria-label="上传正式封面原图" :disabled="busy" @change="uploadCover" /><small>JPEG / PNG，32 MiB 以内；上传后确认采用。</small></span>
        <select v-model="choices[name]" :aria-label="labels[name] + '保存方式'" :disabled="busy"><option value="KEEP">本次不修改</option><option value="SET" :disabled="name === 'cover' && !values.cover">确认此值并保护</option><option value="CLEAR">明确清空并保护</option><option value="RESET">恢复自动采用</option></select>
      </div>
    </div>
    <template #footer><ElButton :disabled="busy" @click="emit('close')">取消</ElButton><ElButton type="primary" :loading="busy" @click="save">保存正式资料</ElButton></template>
  </ElDialog>
</template>
<style scoped>
select { width: 100%; min-height: 32px; padding: 0 10px; border: 1px solid var(--sv-border-subtle); border-radius: 4px; color: var(--sv-text-primary); background: var(--sv-surface-1); font: inherit; }select:focus-visible { outline: 2px solid var(--sv-accent-primary); }.cover-preview { width: 80px; height: 80px; object-fit: contain; display: block; margin-bottom: 8px; }.formal-fields { display: grid; gap: 16px; margin-top: 20px; }.formal-field { display: grid; grid-template-columns: 150px 1fr 174px; align-items: start; gap: 12px; }.formal-field label { line-height: 1.6; }.formal-field small { display: block; color: var(--sv-text-secondary); }p { color: var(--sv-text-secondary); line-height: 1.7; }@media(max-width: 650px) { .formal-field { grid-template-columns: 1fr; gap: 6px; } }
</style>
