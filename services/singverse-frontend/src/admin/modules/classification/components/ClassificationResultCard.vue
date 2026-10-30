<script setup lang="ts">
import { computed } from 'vue'
import { ElButton, ElTag } from 'element-plus'
import { dimensionNames, originNames, statusNames, type Dimension, type Item, type Tag } from '../types'
const props = defineProps<{ item: Item; dimensions: Dimension[]; tags: Tag[]; busy: boolean; canGenerate: boolean; cancelled: boolean }>()
defineEmits<{ review: [item: Item]; retry: [item: Item]; edit: [id: string] }>()
const hasSuggestions = computed(() => props.dimensions.some(d => props.item.suggestion?.dimensions[d]?.length))
function names(ids: string[]) { return ids.map(id => props.tags.find(t => t.id === id)?.name ?? id).join('、') }
</script>
<template>
  <article class="classification-result">
    <div class="result-song"><span class="song-mark" aria-hidden="true">♪</span><div><h3>{{ item.input.title || '未识别歌名' }}</h3><p>{{ item.input.artists.join('、') || '未识别歌手' }}</p><small v-if="item.input.album">{{ item.input.album }}</small></div></div>
    <div class="result-labels">
      <template v-for="dim in dimensions" :key="dim"><div v-if="item.status !== 'APPLIED' && item.suggestion?.dimensions[dim]?.length" class="label-line"><span>{{ dimensionNames[dim] }}</span><strong>{{ names(item.suggestion.dimensions[dim]!) }}</strong></div><p v-if="item.current.dimensions[dim]?.tagIds.length" class="current-label">当前{{ dimensionNames[dim] }}：{{ names(item.current.dimensions[dim]!.tagIds) }} <small>{{ originNames[item.current.dimensions[dim]!.origin] }}{{ item.current.dimensions[dim]!.locked ? ' · 已锁定' : '' }}</small></p></template>
      <p v-if="!hasSuggestions && !Object.keys(item.current.dimensions).length" class="result-hint">{{ item.status === 'PENDING' ? '等待处理，稍后可查看分类建议。' : item.status === 'PROCESSING' ? '正在为这首歌整理分类…' : '暂无可用分类，可以手动整理。' }}</p>
      <p v-if="item.suggestion?.newTagSuggestions.length" class="new-labels">新标签建议（未发布）：{{ item.suggestion.newTagSuggestions.join('、') }}</p>
      <p v-if="item.error" :class="item.status === 'FAILED' ? 'result-error' : 'result-hint'">{{ item.error }}</p>
    </div>
    <div class="result-actions"><ElTag size="small" :type="item.status === 'FAILED' ? 'danger' : item.status === 'APPLIED' ? 'success' : item.status === 'REVIEW' ? 'warning' : 'info'">{{ item.status === 'REVIEW' ? '待确认' : item.status === 'APPLIED' ? '已保存' : item.status === 'FAILED' ? '未成功' : statusNames[item.status] }}</ElTag><ElButton v-if="item.status === 'REVIEW'" type="primary" size="small" :disabled="busy" @click="$emit('review', item)">查看建议</ElButton><ElButton v-if="['FAILED','REVIEW','UNCHANGED'].includes(item.status) && !cancelled" text size="small" :disabled="busy || !canGenerate" @click="$emit('retry', item)">{{ item.status === 'FAILED' ? '重试' : '重新生成' }}</ElButton><ElButton text size="small" :disabled="busy || item.status === 'PROCESSING'" @click="$emit('edit', item.songId)">手动整理 / 历史</ElButton></div>
  </article>
</template>
<style scoped>
.classification-result{display:grid;grid-template-columns:minmax(160px,1fr) minmax(220px,1.7fr) 128px;gap:22px;padding:20px 0;border-bottom:1px solid var(--sv-border-subtle)}.result-song{display:flex;gap:12px;align-items:flex-start;min-width:0}.result-song>div{min-width:0}.song-mark{display:grid;place-items:center;flex-shrink:0;width:38px;height:38px;border-radius:10px;background:var(--sv-accent-soft);color:var(--sv-accent-primary);font-size:21px}.result-song h3{font-size:14px;overflow-wrap:anywhere;line-height:1.6}.result-song p,.result-song small{display:block;font-size:12px;color:var(--sv-text-secondary);margin-top:3px;overflow-wrap:anywhere}.result-labels{min-width:0}.label-line{display:flex;gap:10px;align-items:baseline;margin:0 0 7px;font-size:12px}.label-line>span{color:var(--sv-text-secondary);flex-shrink:0}.label-line strong{font-weight:500;color:var(--sv-accent-primary);background:var(--sv-accent-soft);border-radius:5px;padding:2px 7px;overflow-wrap:anywhere}.current-label,.result-hint{font-size:12px;color:var(--sv-text-secondary);line-height:1.8;overflow-wrap:anywhere}.current-label small{font-size:11px;color:var(--sv-text-tertiary)}.result-actions{display:flex;flex-direction:column;align-items:stretch;gap:8px}.result-actions>.el-tag{align-self:flex-start}.result-actions .el-button{margin:0}.new-labels,.result-error{font-size:12px;line-height:1.8;overflow-wrap:anywhere;margin-top:8px}.new-labels{color:var(--sv-text-secondary)}.result-error{color:var(--el-color-danger)}@media(max-width:850px){.classification-result{grid-template-columns:minmax(0,1fr);gap:14px;padding:20px 0}.result-actions{flex-direction:row;flex-wrap:wrap;align-items:center;gap:8px}.result-labels{padding-left:50px}.result-actions>.el-tag{align-self:center}.result-actions .el-button{min-height:32px}}@media(max-width:500px){.result-labels{padding-left:0}}
</style>
