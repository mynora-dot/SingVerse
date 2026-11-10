import type { RouteRecordRaw } from 'vue-router'
import ParametersView from './views/ParametersView.vue'

export const systemParametersRoutes: RouteRecordRaw[] = [
  { path: '/system/parameters', component: ParametersView, meta: { title: '账号与安全' } },
  { path: '/system/music-platforms', component: () => import('../metadata/views/MusicPlatformsView.vue'), meta: { title: '音乐平台' } },
]
