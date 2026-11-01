<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { ElButton, ElDrawer, ElTag } from 'element-plus'
import { VideoPlay, EditPen } from '@element-plus/icons-vue'
import EmbeddedMetadataPanel from '../../media-library/components/EmbeddedMetadataPanel.vue'
import type { EmbeddedDetail } from '../../media-library/api/embeddedMetadata'
import { songTitle, songArtists } from '../../metadata/utils'
import type { Tag } from '../../classification/types'
import { confirmedField, fileFormat, fileSize, platformDuration, displayTime, songTags, songReviewLabel, needsSongReview, sharedIdentity, languageLabels, versionLabels } from '../presentation'
import type { MediaSong } from '../../media-library/types'
import { authRequest } from '../../../core/auth/api'
import { songRoles, type AudioRoles } from '../../audio-processing/api'
import { rolesSummary } from '../../audio-processing/presentation'
const props = withDefaults(defineProps<{ song: MediaSong | null; visible: boolean; tags?: Tag[] }>(), { tags: () => [] })
const emit = defineEmits<{ close: []; play: []; edit: []; classify: [] }>()
const embedded = ref<EmbeddedDetail>(), coverFailed = ref(false)
const roles = ref<AudioRoles>(), rolesError = ref(false)
let rolesTurn = 0
watch(() => [props.song?.id, props.visible], async () => {
  embedded.value = undefined; coverFailed.value = false; roles.value = undefined; rolesError.value = false
  const turn = ++rolesTurn, id = props.song?.id
  if (!id || !props.visible) return
  try { const value = await authRequest<AudioRoles>(`${songRoles(id)}/audio-roles`); if (turn === rolesTurn) roles.value = value }
  catch { if (turn === rolesTurn) rolesError.value = true }
}, { immediate: true })
const audio = computed(() => rolesSummary(roles.value))
const artwork = computed(() => {
  const song = props.song
  if (!song) return ''
  if (song.metadata && !song.metadata.stale && song.metadata.fields.includes('cover') && song.metadata.track.coverUrl) return `/api/v1/metadata/songs/${encodeURIComponent(song.id)}/cover`
  return embedded.value?.result?.cover ? `/api/v1/media-songs/${encodeURIComponent(song.id)}/embedded-metadata/cover` : ''
})
watch(artwork, () => { coverFailed.value = false })
const album = computed(() => {
  const value = props.song ? confirmedField(props.song, 'album') : null
  return typeof value === 'string' && !['未知专辑', 'Unknown Album'].includes(value) ? value : ''
})
const duration = computed(() => {
  if (props.song && platformDuration(props.song)) return platformDuration(props.song)
  const seconds = Number(embedded.value?.result?.technical?.duration)
  return Number.isFinite(seconds) && seconds > 0 ? `${Math.floor(seconds / 60)}:${String(Math.floor(seconds % 60)).padStart(2, '0')}` : ''
})
const lyrics = computed(() => {
  const value = embedded.value?.result?.lyrics
  if (!value) return ''
  if (value.lines?.length) return value.lines.map(line => line.text).join('\n').trim()
  return value.text.replace(/^\[(?:ti|ar|al|by|offset|length):.*\]\s*$/gmi, '').replace(/\[\d{1,3}:\d{2}(?:[.:]\d{1,3})?\]/g, '').trim()
})
const syncedLyrics = computed(() => ['milliseconds', 'lrc', 'mpeg-frames'].includes(embedded.value?.result?.lyrics?.timing ?? ''))
const classifications = computed(() => props.song ? songTags(props.song, props.tags) : [])
const facts = computed(() => {
  const song = props.song
  if (!song) return []
  const result: { label: string; value: string }[] = []
  const date = confirmedField(song, 'releaseDate')
  if (date) result.push({ label: '发行日期', value: String(date) })
  if (song.result.language !== 'UNKNOWN' && languageLabels[song.result.language]) result.push({ label: '语种', value: languageLabels[song.result.language]! })
  if (song.result.versionType !== 'UNKNOWN' && versionLabels[song.result.versionType]) result.push({ label: '版本', value: versionLabels[song.result.versionType]! })
  return result
})
const origin = computed(() => {
  if (props.song?.metadata?.stale) return '已有信息待重新核对'
  const labels: Record<string, string> = { FILE: '文件内嵌标签', SHARED: '共享曲库', PLATFORM: '平台信息', MANUAL: '人工整理', UPLOAD: '上传图片' }
  return [...new Set(Object.values(props.song?.metadata?.origins ?? {}).map(item => labels[item.kind ?? ''] || '已保存信息'))].join('、') || '文件名解析'
})
</script>

<template>
  <ElDrawer :model-value="visible" title="歌曲详情" size="min(680px, 100vw)" class="song-detail-drawer" :append-to-body="false" @update:model-value="value => { if (!value) emit('close') }">
    <template v-if="song">
      <section class="song-hero" aria-label="歌曲概览">
        <div class="song-artwork">
          <img v-if="artwork && !coverFailed" :src="artwork" :alt="songTitle(song) + '的封面'" decoding="async" @error="coverFailed = true">
          <div v-else class="artwork-placeholder" aria-label="暂无歌曲封面"><svg viewBox="0 0 64 64" fill="none" aria-hidden="true"><path d="M25 43V19l27-6v24M25 25l27-6" stroke="currentColor" stroke-width="3" stroke-linecap="round" stroke-linejoin="round"/><ellipse cx="18" cy="45" rx="8" ry="6" stroke="currentColor" stroke-width="3"/><ellipse cx="45" cy="39" rx="8" ry="6" stroke="currentColor" stroke-width="3"/></svg></div>
        </div>
        <div class="song-heading"><h2>{{ songTitle(song) }}</h2><p class="song-artists">{{ songArtists(song).join('、') || '未知歌手' }}</p><p v-if="album" class="song-album">{{ album }}</p><div class="song-quick-facts"><span>{{ fileFormat(song) }}</span><span v-if="duration">{{ duration }}</span><span :title="'媒体库：' + song.sourceName">{{ song.sourceName }}</span><span v-if="needsSongReview(song)" class="review-status">待核对</span></div></div>
        <div class="song-primary-actions"><ElButton type="primary" :icon="VideoPlay" @click="emit('play')">播放</ElButton><ElButton :icon="EditPen" @click="emit('edit')">{{ needsSongReview(song) ? '核对信息' : '编辑信息' }}</ElButton></div>
      </section>
      <p v-if="song.metadata?.stale" class="song-notice" role="status">文件已变化，歌曲信息需要重新核对。</p><p v-else-if="sharedIdentity(song)" class="song-notice">来自共享曲库，核对后可保存自己的订正。</p>
      <dl v-if="facts.length" class="song-facts"><div v-for="fact in facts" :key="fact.label"><dt>{{ fact.label }}</dt><dd>{{ fact.value }}</dd></div></dl>
      <section class="classification-section audio-summary" aria-label="原唱与伴奏"><div class="section-heading"><h3>原唱与伴奏</h3><RouterLink :to="{ path: '/library/audio-processing', query: { song: song.id } }" @click="emit('close')">{{ audio.tone === 'warning' ? '去处理' : '管理音轨' }}</RouterLink></div><p v-if="rolesError" class="classification-empty">暂时无法读取音轨信息</p><p v-else class="audio-status"><ElTag :type="audio.tone" effect="light" round size="small">{{ audio.tag }}</ElTag><span>{{ audio.title }}</span></p></section>
      <section class="lyrics-card" aria-label="歌曲歌词"><div class="section-heading"><h3>歌词</h3><span v-if="lyrics && syncedLyrics" class="section-caption">同步歌词</span></div><div v-if="lyrics" class="lyrics-content" tabindex="0" aria-label="歌词内容"><p>{{ lyrics }}</p></div><p v-else class="lyrics-empty">暂无可显示的歌词</p></section>
      <section class="classification-section" aria-label="歌曲分类"><div class="section-heading"><h3>分类标签</h3><ElButton text @click="emit('classify')">编辑标签</ElButton></div><div v-if="classifications.length" class="song-tag-list"><span v-for="tag in classifications" :key="tag.dimension + tag.id" class="song-tag" :class="{ inactive: !tag.active }" :title="tag.dimension + (tag.locked ? ' · 已锁定' : '')">{{ tag.name }}</span></div><p v-else class="classification-empty">还没有分类标签</p></section>
      <details class="file-details"><summary>文件与技术信息</summary><div class="file-details-content"><p class="file-path">{{ song.relativePath }}</p><dl class="file-facts"><div><dt>文件大小</dt><dd>{{ fileSize(song.fileSize) }}</dd></div><div><dt>信息来源</dt><dd>{{ origin }}</dd></div><div><dt>修改时间</dt><dd>{{ displayTime(song.modifiedAt) }}</dd></div><div><dt>扫描时间</dt><dd>{{ displayTime(song.scannedAt) }}</dd></div><div><dt>整理状态</dt><dd>{{ songReviewLabel(song) }}</dd></div><div><dt>原始解析</dt><dd>{{ song.result.needsReview ? '文件名待核对' : '文件名完整' }}</dd></div></dl>
      <EmbeddedMetadataPanel :key="song.id" :song-id="visible ? song.id : null" compact @loaded="embedded = $event" /></div></details>
    </template>
  </ElDrawer>
</template>

<style scoped>
.song-hero{display:grid;grid-template-columns:160px minmax(0,1fr);column-gap:24px;row-gap:16px;align-items:start}.song-artwork{grid-row:1 / 3;aspect-ratio:1;overflow:hidden;border-radius:14px;background:var(--sv-accent-soft);box-shadow:0 8px 24px rgb(26 66 54 / 9%)}.song-artwork img{display:block;width:100%;height:100%;object-fit:cover}.artwork-placeholder{display:flex;align-items:center;justify-content:center;width:100%;height:100%;color:var(--sv-accent-primary);background:linear-gradient(145deg,var(--sv-accent-soft),var(--sv-surface-3))}.artwork-placeholder svg{width:64px;height:64px;opacity:.65}.song-heading h2{margin:1px 0 8px;font-size:23px;line-height:1.4;letter-spacing:-.3px;font-weight:650;overflow-wrap:anywhere;color:var(--sv-text-primary)}.song-artists{font-size:15px;line-height:1.6;margin:0;color:var(--sv-text-secondary)}.song-album{font-size:13px;line-height:1.6;margin:5px 0 0;color:var(--sv-text-secondary);overflow-wrap:anywhere}.song-quick-facts{display:flex;align-items:center;gap:8px;flex-wrap:wrap;margin-top:12px;font-size:12px;color:var(--sv-text-secondary)}.song-quick-facts>span{padding:3px 8px;border:1px solid var(--sv-border-subtle);border-radius:5px;line-height:1.3}.song-quick-facts .review-status{color:var(--sv-accent-primary);background:var(--sv-accent-soft);border-color:transparent}.song-primary-actions{display:flex;flex-wrap:wrap;gap:8px;grid-column:2}.song-primary-actions :deep(.el-button){margin:0;min-width:88px}.song-notice{font-size:12px;line-height:1.7;color:var(--sv-text-secondary);margin:16px 0 0;padding:10px 12px;background:var(--sv-surface-3);border-radius:8px}.song-facts,.file-facts{display:grid;grid-template-columns:repeat(2,minmax(0,1fr));gap:16px 24px;margin:24px 0}.song-facts>div,.file-facts>div{min-width:0}dt{font-size:11px;line-height:1.6;color:var(--sv-text-secondary);margin-bottom:4px}dd{font-size:13px;line-height:1.6;color:var(--sv-text-primary);margin:0;overflow-wrap:anywhere}.song-facts+.lyrics-card{margin-top:0}.lyrics-card{margin-top:24px;padding:18px 20px;background:var(--sv-surface-3);border:1px solid var(--sv-border-subtle);border-radius:12px}.section-heading{display:flex;align-items:center;justify-content:space-between;gap:12px;min-height:24px}.section-heading h3{font-size:14px;font-weight:600;margin:0;color:var(--sv-text-primary)}.section-caption{font-size:11px;color:var(--sv-text-secondary)}.lyrics-content{margin-top:14px;max-height:320px;overflow:auto;scrollbar-width:thin;border-radius:4px}.lyrics-content:focus-visible{outline:2px solid var(--sv-accent-primary);outline-offset:4px}.lyrics-content p{white-space:pre-wrap;overflow-wrap:anywhere;font-size:14px;line-height:2.1;color:var(--sv-text-primary);margin:0}.lyrics-empty{font-size:13px;text-align:center;color:var(--sv-text-secondary);padding:18px 0;margin:0}.classification-section{margin:22px 0}.classification-section :deep(.el-button){padding:0;min-height:24px;height:24px;font-size:12px}.audio-status{display:flex;flex-wrap:wrap;gap:8px;align-items:center;margin:12px 0 0;font-size:13px;color:var(--sv-text-primary)}.classification-empty{font-size:12px;color:var(--sv-text-secondary);margin:12px 0 0}.song-tag-list{display:flex;flex-wrap:wrap;gap:8px;margin-top:12px}.song-tag{font-size:12px;padding:5px 10px;border-radius:6px;background:var(--sv-accent-soft);color:var(--sv-accent-primary)}.song-tag.inactive{color:var(--sv-text-secondary);background:var(--sv-surface-3)}.file-details{border-top:1px solid var(--sv-border-subtle);padding-top:14px;color:var(--sv-text-secondary);font-size:12px}.file-details>summary{cursor:pointer;min-height:30px;line-height:30px}.file-details-content{padding-top:10px}.file-path{font-size:12px;line-height:1.7;overflow-wrap:anywhere;margin:0;padding:10px 12px;background:var(--sv-surface-3);border-radius:6px}.file-facts{margin:16px 0}.file-facts dd{font-size:12px}@media(max-width:480px){.song-hero{grid-template-columns:104px minmax(0,1fr);column-gap:16px;row-gap:16px}.song-artwork{grid-row:1}.song-heading h2{font-size:19px;line-height:1.45;margin-top:0}.song-artists{font-size:13px}.song-primary-actions{grid-column:1 / -1}.song-primary-actions>.el-button{flex:1}.song-facts,.file-facts{gap:14px 16px;margin:20px 0}.song-facts+.lyrics-card{margin-top:0}.lyrics-card{margin-top:24px;padding:16px}.lyrics-content{max-height:280px}.song-album{font-size:12px}}
</style>
<style>
.song-detail-drawer .el-drawer__header{padding:18px 28px;margin-bottom:0;border-bottom:1px solid var(--sv-border-subtle)}.song-detail-drawer .el-drawer__title{font-size:15px;font-weight:600;color:var(--sv-text-primary)}.song-detail-drawer .el-drawer__body{padding:24px 28px 28px}@media(max-width:480px){.song-detail-drawer .el-drawer__header{padding:16px 20px}.song-detail-drawer .el-drawer__body{padding:20px}}
</style>
