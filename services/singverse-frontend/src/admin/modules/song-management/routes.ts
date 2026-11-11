import type { RouteLocationNormalized, RouteRecordRaw } from 'vue-router'

const fileOnlyParams = ['q', 'status', 'tagIds', 'unclassified', 'size', 'detailSongId', 'reviewSongId']

/** 旧正式资料深链仅带 search/sourceId/page，补 lens=formal 保留语义；文件专属参数与空查询默认歌曲透镜。 */
export function resolveSongsLens(to: RouteLocationNormalized) {
  const query = to.query
  if (query.lens === 'songs' || query.lens === 'formal') return true
  if (fileOnlyParams.some(key => key in query)) return true
  if (['search', 'sourceId', 'page'].some(key => key in query)) return { path: '/library/songs', query: { ...query, lens: 'formal' }, hash: to.hash }
  return true
}

export const songManagementRoutes: RouteRecordRaw[] = [
  { path: '/library/playback-history', component: () => import('../playback-history/PlaybackHistoryView.vue'), meta: { title: '播放记录' } },
  { path: '/library/missing-feedback', component: () => import('../missing-feedback/MissingFeedbackView.vue'), meta: { title: '缺歌反馈' } },
  { path: '/library/audio-processing', component: () => import('../audio-processing/AudioProcessingView.vue'), meta: { title: '原唱与伴奏' } },
  { path: '/library/playlists', component: () => import('../playlists/PlaylistsView.vue'), meta: { title: '歌单管理' } },
  { path: '/library/artists', component: () => import('../artists/views/ArtistsView.vue'), meta: { title: '歌手库' } },
  { path: '/library/classification', component: () => import('../classification/views/ClassificationView.vue'), meta: { title: '歌曲分类' } },
  { path: '/library/songs/metadata', component: () => import('../metadata/views/MetadataScrapeView.vue'), meta: { title: '歌曲信息整理', activeMenu: '/library/songs/metadata' } },
  { path: '/library/review', component: () => import('../metadata/views/MetadataScrapeView.vue'), meta: { title: '待审核', activeMenu: '/library/review', review: true } },
  { path: '/library/songs/files', redirect: to => ({ path: '/library/songs', query: { ...to.query, lens: 'songs' }, hash: to.hash }) },
  { path: '/library/songs', component: () => import('./views/SongsWorkspaceView.vue'), beforeEnter: resolveSongsLens, meta: { title: '歌曲', activeMenu: '/library/songs' } },
]
