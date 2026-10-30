<script setup lang="ts">
import { ElButton, ElTable, ElTableColumn, ElTag } from 'element-plus'
import { metadata } from '../api/metadata'
import { confidence, itemLabel } from '../batch-display'
import { providerNames } from '../types'
import type { MetadataBatch, MetadataItem, MusicProvider } from '../types'
const props = defineProps<{ batch: MetadataBatch; busy: boolean; retryDisabledReason: string }>()
const emit = defineEmits<{ review: [item: MetadataItem]; retry: [itemId: string]; manual: [item: MetadataItem]; reason: [item: MetadataItem] }>()
function risk(item: MetadataItem) { return confidence(item, props.batch.threshold) }
</script>
<template>
  <ElTable :data="batch.items" row-key="id" empty-text="当前筛选没有歌曲，请调整状态或搜索条件" class="batch-song-table">
    <ElTableColumn label="歌曲（批次快照）" min-width="190"><template #default="{ row }"><div class="song-cell"><img v-if="row.coverAvailable" :src="metadata.songCover(row.songId)" alt="当前确认封面" loading="lazy" @error="($event.target as HTMLImageElement).style.visibility = 'hidden'" /><span v-else class="song-placeholder" aria-hidden="true">♫</span><div><strong>{{ row.title || '未识别歌名' }}</strong><small>{{ row.artists.join('、') || '未知歌手' }}</small></div></div></template></ElTableColumn>
    <ElTableColumn label="匹配建议 / 候选信息" min-width="240"><template #default="{ row }"><div v-if="row.candidates.length" class="song-cell"><img v-if="row.candidates[0].track.coverUrl" :src="metadata.candidateCover(row.candidates[0].id)" alt="候选封面" loading="lazy" @error="($event.target as HTMLImageElement).style.visibility = 'hidden'" /><span v-else class="song-placeholder" aria-hidden="true">♫</span><div><strong>{{ row.candidates[0].track.title }}</strong><small>{{ row.candidates[0].track.artists.join('、') }} · {{ providerNames[row.candidates[0].track.provider as MusicProvider] }}</small><small>{{ [row.candidates[0].track.album, row.candidates[0].track.releaseDate].filter(Boolean).join(' · ') }}</small><small v-if="row.status === 'REVIEW' && risk(row as MetadataItem) !== 'HIGH'" class="risk-warning">{{ risk(row as MetadataItem) === 'NONE' ? '未找到可信匹配，低分候选仅供核对' : '未达到高相似度阈值，请核对版本和歌手' }}</small><small v-if="row.status === 'REVIEW' && risk(row as MetadataItem) === 'HIGH' && row.candidates.length > 1 && row.candidates[0].score - row.candidates[1].score < .08" class="item-note">多个接近候选；审核时自动选中第一项，请核对版本</small><small v-if="row.error?.includes('部分平台失败')" class="risk-warning">{{ row.error }}</small></div></div><span v-else class="item-note">{{ row.status === 'PENDING' ? (batch.status === 'CANCELLED' ? '终止后不再处理' : '等待平台查询') : row.status === 'PROCESSING' ? '正在查询平台，请稍候' : row.status === 'SKIPPED_EXISTING' ? '保留已有确认结果' : row.status === 'FAILED' ? row.error : '没有找到候选，可重新匹配或手工处理' }}</span></template></ElTableColumn>
    <ElTableColumn label="相似度" width="90"><template #default="{ row }"><span v-if="row.candidates.length" :class="{ 'risk-warning': row.status === 'REVIEW' && risk(row as MetadataItem) !== 'HIGH' }">{{ Math.round(row.candidates[0].score * 100) }}%</span><span v-else>—</span></template></ElTableColumn>
    <ElTableColumn label="状态" width="150"><template #default="{ row }"><ElTag :type="row.status === 'FAILED' ? 'danger' : ['AUTO_APPLIED','MANUAL_APPLIED'].includes(row.status) ? 'success' : row.status === 'REVIEW' ? 'warning' : 'info'">{{ itemLabel(row as MetadataItem, batch.threshold, batch.status === 'CANCELLED') }}</ElTag></template></ElTableColumn>
    <ElTableColumn label="操作" min-width="190"><template #default="{ row }"><div class="row-actions">
      <template v-if="row.status === 'REVIEW'"><ElButton v-if="row.candidates.length" link type="primary" :disabled="busy" @click="emit('review', row as MetadataItem)">{{ risk(row as MetadataItem) === 'HIGH' ? '审核候选' : '核对候选' }}</ElButton><ElButton v-else link type="primary" :disabled="busy || !row.sourceId" @click="emit('manual', row as MetadataItem)">单曲手工处理</ElButton><span :title="retryDisabledReason"><ElButton link :disabled="busy || !!retryDisabledReason" @click="emit('retry', row.id)">重新匹配</ElButton></span><ElButton v-if="row.candidates.length && risk(row as MetadataItem) === 'NONE'" link :disabled="busy || !row.sourceId" @click="emit('manual', row as MetadataItem)">手工处理</ElButton></template>
      <template v-else-if="row.status === 'FAILED'"><ElButton link type="danger" @click="emit('reason', row as MetadataItem)">查看原因</ElButton><span :title="retryDisabledReason"><ElButton link type="primary" :disabled="busy || !!retryDisabledReason" @click="emit('retry', row.id)">重试</ElButton></span></template>
      <template v-else-if="['AUTO_APPLIED','MANUAL_APPLIED','SKIPPED_EXISTING'].includes(row.status)"><ElButton link @click="emit('reason', row as MetadataItem)">{{ row.status === 'SKIPPED_EXISTING' ? '跳过原因' : '应用结果' }}</ElButton><ElButton link :disabled="busy || !row.sourceId" @click="emit('manual', row as MetadataItem)">前往单曲处理</ElButton></template>
      <span v-else class="item-note">{{ row.status === 'PROCESSING' ? '请求进行中' : batch.status === 'CANCELLED' ? '已停止后续处理' : '等待调度' }}</span>
    </div></template></ElTableColumn>
  </ElTable>
</template>
<style scoped>
.batch-song-table { width: 100%; font-size: 13px; }
.batch-song-table :deep(.el-table__cell) { padding: 10px 0; vertical-align: middle; }
.batch-song-table :deep(.cell) { line-height: 1.5; padding: 0 10px; }
.song-cell { display: flex; align-items: center; gap: 9px; min-width: 0; }.song-cell > div { min-width: 0; overflow-wrap: anywhere; }.song-cell strong { display: block; font-size: 13px; font-weight: 550; color: var(--sv-text-primary); }.song-cell small { display: block; font-size: 12px; color: var(--sv-text-secondary); margin: 2px 0; }
.song-cell img,.song-placeholder { width: 40px; height: 40px; border-radius: 6px; object-fit: cover; flex-shrink: 0; }.song-placeholder { display: grid; place-items: center; color: var(--sv-text-tertiary); background: var(--sv-surface-3); font-size: 21px; }
.song-cell .risk-warning,.risk-warning { color: var(--el-color-warning-dark-2); }.item-note { font-size: 12px; color: var(--sv-text-secondary); overflow-wrap: anywhere; }.row-actions { display: flex; align-items: center; gap: 6px 12px; flex-wrap: wrap; }.row-actions .el-button { margin: 0; font-size: 12px; }
</style>
