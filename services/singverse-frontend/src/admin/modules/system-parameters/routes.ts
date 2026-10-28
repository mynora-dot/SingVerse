import type { RouteRecordRaw } from 'vue-router'
import ParametersView from './views/ParametersView.vue'

export const systemParametersRoutes: RouteRecordRaw[] = [
  { path: '/system/parameters', component: ParametersView, meta: { title: '系统参数' } },
]
