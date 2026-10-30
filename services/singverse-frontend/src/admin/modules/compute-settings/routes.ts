import type { RouteRecordRaw } from 'vue-router'
export const computeSettingsRoutes: RouteRecordRaw[] = [
  { path: '/system/compute', component: () => import('./views/ComputeSettingsView.vue'), meta: { title: '性能节点配置', activeMenu: '/system/compute' } },
]
