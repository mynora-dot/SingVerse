import type { RouteRecordRaw } from 'vue-router'

export const songManagementRoutes: RouteRecordRaw[] = [
  { path: '/library/playlists', component: () => import('../playlists/PlaylistsView.vue'), meta: { title: '歌单管理' } },
  { path: '/library/artists', component: () => import('../artists/views/ArtistsView.vue'), meta: { title: '歌手库' } },
  { path: '/library/classification', component: () => import('../classification/views/ClassificationView.vue'), meta: { title: '歌曲分类' } },
  { path: '/library/songs/metadata', component: () => import('../metadata/views/MetadataScrapeView.vue'), meta: { title: '元数据整理', activeMenu: '/library/songs/metadata' } },
  { path: '/library/songs', component: () => import('./views/SongsView.vue'), meta: { title: '全部歌曲' } },
]
