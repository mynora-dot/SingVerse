import type { RouteRecordRaw } from 'vue-router'
import MediaLibraryView from './views/MediaLibraryView.vue'

export const mediaLibraryRoutes: RouteRecordRaw[] = [
  { path: '/library/media', component: MediaLibraryView, meta: { title: '媒体库' } },
]
