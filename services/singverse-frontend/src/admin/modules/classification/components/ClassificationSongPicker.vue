<script setup lang="ts">
import { computed, onBeforeUnmount, ref } from 'vue'
import { ElAlert, ElButton, ElDialog, ElInput, ElMessage, ElPagination, type InputInstance } from 'element-plus'
import { Search } from '@element-plus/icons-vue'
import { songs } from '../../song-management/api/songs'
import { songArtists, songTitle } from '../../metadata/utils'
import type { MediaSong } from '../../media-library/types'
const props = defineProps<{ selectedIds: string[]; purpose?: string }>()
const emit = defineEmits<{ close: []; confirm: [ids: string[]]; selectedSongs: [songs: MediaSong[]] }>()
const limit=props.purpose==='playlist'?500:5000
const selectedModels=new Map<string,MediaSong>()
const selected = ref(new Set(props.selectedIds)), items = ref<MediaSong[]>([])
const search = ref(''), appliedSearch = ref(''), page = ref(0), total = ref(0), loading = ref(false), error = ref('')
const searchInput = ref<InputInstance>()
const pageSelected = computed(() => items.value.filter(song => selected.value.has(song.id)).length)
const allOnPage = computed(() => items.value.length > 0 && pageSelected.value === items.value.length)
const partialPage = computed(() => pageSelected.value > 0 && !allOnPage.value)
const rangeStart = computed(() => items.value.length ? page.value * 20 + 1 : 0)
const rangeEnd = computed(() => page.value * 20 + items.value.length)
let active = true, generation = 0
async function load() {
  const token = ++generation; loading.value = true; error.value = ''; items.value = []
  try {
    const result = await songs.list({ q: appliedSearch.value, sourceId: '', status: '', page: page.value, size: 20 })
    if (!active || token !== generation) return
    result.items.forEach(s=>selectedModels.set(s.id,s));items.value = result.items; total.value = result.totalElements
  } catch (e) { if (active && token === generation) error.value = e instanceof Error ? e.message : '歌曲读取失败，请重试' }
  finally { if (active && token === generation) loading.value = false }
}
function query() { appliedSearch.value = search.value.trim(); page.value = 0; void load() }
function changePage(value: number) { if (value - 1 === page.value) return; page.value = value - 1; void load() }
function toggle(id: string, checked: boolean) {
  const next = new Set(selected.value)
  if (checked && !next.has(id) && next.size >= limit) { ElMessage.warning(`一次最多选择 ${limit} 首歌曲`); return }
  if (checked) next.add(id); else next.delete(id)
  selected.value = next
}
function togglePage(checked: boolean) {
  const next = new Set(selected.value); let limited = false
  for (const song of items.value) {
    if (!checked) next.delete(song.id)
    else if (next.has(song.id) || next.size < limit) next.add(song.id)
    else limited = true
  }
  selected.value = next
  if (limited) ElMessage.warning(`已达到 ${limit} 首上限，其余歌曲未选中`)
}
onBeforeUnmount(() => { active = false; generation++ })
void load()
</script>
<template>
  <ElDialog class="classification-song-picker" :model-value="true" :title="purpose==='playlist'?'选择歌单歌曲':'选择要分类的歌曲'" width="min(820px, calc(100vw - 32px))" :close-on-click-modal="false" @close="emit('close')" @opened="searchInput?.focus()">
    <p class="picker-description">{{purpose==='playlist'?'搜索并勾选歌曲，换页保留选择。确认后可调整歌单顺序。':'搜索并勾选歌曲，换页后会保留选择。确认后继续设置分类内容。'}}</p>
    <form class="picker-search" @submit.prevent="query"><ElInput ref="searchInput" v-model="search" aria-label="搜索要分类的歌曲" placeholder="搜索歌名、歌手或文件名" clearable :prefix-icon="Search" @clear="query" /><ElButton native-type="submit" :loading="loading">搜索</ElButton></form>
    <div class="picker-toolbar"><label><input type="checkbox" aria-label="选择本页歌曲" :checked="allOnPage" :indeterminate="partialPage" :disabled="loading || !items.length" @change="togglePage(($event.target as HTMLInputElement).checked)">选择本页</label><span>已选 {{ selected.size }} 首</span><ElButton v-if="selected.size" text size="small" @click="selected = new Set()">清空已选</ElButton></div>
    <ElAlert v-if="error" :title="error" type="error" :closable="false" show-icon><ElButton text @click="load">重新加载</ElButton></ElAlert>
    <div v-if="loading" class="picker-empty" role="status">正在读取歌曲…</div>
    <div v-else-if="!error && !items.length" class="picker-empty"><span class="empty-note" aria-hidden="true">♪</span><h3>{{ appliedSearch ? '没有找到匹配的歌曲' : '曲库中还没有歌曲' }}</h3><p>{{ appliedSearch ? '试试其他歌名、歌手或文件名。' : '先在歌曲位置添加来源并扫描，再来选择。' }}</p><ElButton v-if="appliedSearch" text @click="search = ''; query()">清除搜索</ElButton></div>
    <div v-else-if="!error" class="picker-song-list"><label v-for="song in items" :key="song.id" class="picker-song" :class="{ selected: selected.has(song.id) }"><input type="checkbox" :aria-label="`选择歌曲：${songTitle(song)}`" :checked="selected.has(song.id)" :disabled="!selected.has(song.id) && selected.size >= limit" @change="toggle(song.id, ($event.target as HTMLInputElement).checked)"><span class="picker-song-art" aria-hidden="true">♪</span><span class="picker-song-name"><strong>{{ songTitle(song) }}</strong><small>{{ songArtists(song).join('、') || '未知歌手' }}</small></span><span class="picker-song-location"><strong>{{ song.sourceName }}</strong><small :title="song.relativePath">{{ song.relativePath }}</small></span></label></div>
    <div class="picker-pagination"><small v-if="!loading && !error">{{ items.length ? `第 ${rangeStart}–${rangeEnd} 首，共 ${total} 首` : `共 ${total} 首` }}</small><ElPagination :current-page="page + 1" :total="total" :page-size="20" :disabled="loading" layout="prev, pager, next" @current-change="changePage" /></div>
    <template #footer><div class="picker-footer"><span>已选择 <strong>{{ selected.size }}</strong> 首 <small>· 最多 {{ limit }} 首</small></span><div><ElButton @click="emit('close')">取消</ElButton><ElButton type="primary" :disabled="!selected.size && !props.selectedIds.length" @click="emit('selectedSongs',[...selected].map(id=>selectedModels.get(id)).filter((s):s is MediaSong=>!!s));emit('confirm', [...selected])">确认选择<span v-if="selected.size">（{{ selected.size }} 首）</span></ElButton></div></div></template>
  </ElDialog>
</template>
<style scoped>
.picker-description{flex-shrink:0;font-size:12px;color:var(--sv-text-secondary);line-height:1.8;margin-bottom:18px}.picker-search{flex-shrink:0;display:flex;gap:10px}.picker-search .el-input{flex:1;min-width:0}.picker-toolbar{flex-shrink:0;display:flex;align-items:center;gap:14px;min-height:48px;font-size:12px;border-bottom:1px solid var(--sv-border-subtle)}.picker-toolbar>label{display:flex;align-items:center;gap:9px;cursor:pointer}.picker-toolbar>span{margin-left:auto;color:var(--sv-text-secondary)}input[type=checkbox]{width:16px;height:16px;accent-color:var(--sv-accent-primary);flex-shrink:0;cursor:pointer}input:focus-visible{outline:2px solid var(--sv-accent-primary);outline-offset:3px}.picker-song-list{min-height:140px;flex:1;overflow-y:auto;overscroll-behavior:contain}.picker-song{display:flex;align-items:center;gap:12px;padding:13px 10px;border-bottom:1px solid var(--sv-border-subtle);cursor:pointer;border-radius:8px}.picker-song:hover{background:var(--sv-surface-2)}.picker-song.selected{background:var(--sv-accent-soft)}.picker-song-art{display:grid;place-items:center;width:34px;height:34px;border-radius:9px;background:var(--sv-surface-2);color:var(--sv-accent-primary);font-size:21px;flex-shrink:0}.picker-song-name{flex:1;min-width:0}.picker-song-name strong{display:block;font-size:13px;font-weight:550;overflow-wrap:anywhere;line-height:1.7}.picker-song-name small{display:block;font-size:11px;color:var(--sv-text-secondary);margin-top:3px;overflow-wrap:anywhere}.picker-song-location{width:40%;min-width:0;text-align:right}.picker-song-location strong{display:block;font-size:11px;font-weight:400;color:var(--sv-text-secondary);overflow-wrap:anywhere}.picker-song-location small{display:block;white-space:nowrap;overflow:hidden;text-overflow:ellipsis;font-size:10px;color:var(--sv-text-tertiary);margin-top:4px}.picker-pagination{flex-shrink:0;display:flex;align-items:center;justify-content:space-between;flex-wrap:wrap;gap:8px;margin-top:18px}.picker-pagination>small{font-size:11px;color:var(--sv-text-secondary)}.picker-pagination :deep(.el-pagination){flex-wrap:wrap}.picker-empty{flex:1;overflow-y:auto;display:grid;place-items:center;gap:12px;padding:40px 20px;min-height:180px;text-align:center;font-size:13px;color:var(--sv-text-secondary)}.picker-empty h3{font-size:15px;font-weight:500;color:var(--sv-text-primary)}.picker-empty p{font-size:12px;line-height:1.8}.empty-note{font-size:32px;color:var(--sv-accent-primary)}.picker-footer{display:flex;align-items:center;justify-content:space-between;gap:12px;text-align:left}.picker-footer>span{font-size:12px;color:var(--sv-text-secondary)}.picker-footer strong{color:var(--sv-accent-primary)}.picker-footer small{font-size:10px}.picker-footer>div{display:flex;gap:10px}.picker-footer .el-button+.el-button{margin-left:0}
:global(.classification-song-picker.el-dialog){display:flex;flex-direction:column;max-height:88dvh}:global(.classification-song-picker>.el-dialog__body){display:flex;flex-direction:column;overflow:hidden;min-height:0}:global(.classification-song-picker>.el-dialog__header),:global(.classification-song-picker>.el-dialog__footer){flex-shrink:0}
@media(max-width:600px){.picker-song{flex-wrap:wrap;gap:8px 10px;padding:12px 8px}.picker-song-location{flex-basis:100%;width:100%;text-align:left;padding-left:26px}.picker-song-location strong{display:inline;font-size:10px}.picker-song-location small{display:inline;margin-left:6px}.picker-song-art{display:none}.picker-footer{align-items:stretch;flex-direction:column;gap:12px}.picker-footer>div{display:grid;grid-template-columns:1fr 1.5fr}.picker-pagination{justify-content:center}.picker-pagination>small{flex-basis:100%;text-align:center}}
</style>
