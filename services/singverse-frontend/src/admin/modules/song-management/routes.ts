import type { RouteRecordRaw } from 'vue-router'

export const songManagementRoutes: RouteRecordRaw[] = [
  { path: '/library/classification', component: () => import('../classification/views/ClassificationView.vue'), meta: { title: '歌曲分类' } },
  { path: '/library/songs/metadata', component: () => import('../metadata/views/MetadataScrapeView.vue'), meta: { title: '元数据刮削', activeMenu: '/library/songs' } },
  { path: '/library/songs', component: () => import('./views/SongsView.vue'), meta: { title: '歌曲管理' } },
]
