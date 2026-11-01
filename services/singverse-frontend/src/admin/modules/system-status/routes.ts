import type { RouteRecordRaw } from 'vue-router'
import SystemView from './views/SystemView.vue'

export const systemStatusRoutes: RouteRecordRaw[] = [
  { path: '/system', component: SystemView, meta: { title: '服务状态' } },
]
