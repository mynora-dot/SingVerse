import type { RouteRecordRaw } from 'vue-router'

export const songManagementRoutes: RouteRecordRaw[] = [
  { path: '/library/playback-history', component: () => import('../playback-history/PlaybackHistoryView.vue'), meta: { title: '播放记录' } },
  { path: '/library/missing-feedback', component: () => import('../missing-feedback/MissingFeedbackView.vue'), meta: { title: '缺歌反馈' } },
  { path: '/library/audio-processing', component: () => import('../audio-processing/AudioProcessingView.vue'), meta: { title: '原唱与伴奏' } },
  { path: '/library/playlists', component: () => import('../playlists/PlaylistsView.vue'), meta: { title: '歌单管理' } },
  { path: '/library/artists', component: () => import('../artists/views/ArtistsView.vue'), meta: { title: '歌手库' } },
  { path: '/library/classification', component: () => import('../classification/views/ClassificationView.vue'), meta: { title: '歌曲分类' } },
  { path: '/library/songs/metadata', component: () => import('../metadata/views/MetadataScrapeView.vue'), meta: { title: '元数据整理', activeMenu: '/library/songs/metadata' } },
  { path: '/library/songs/files', component: () => import('./views/SongsView.vue'), meta: { title: '媒体文件', activeMenu: '/library/songs' } },
  { path: '/library/songs', component: () => import('./views/FormalSongsView.vue'), meta: { title: '全部歌曲' } },
]
