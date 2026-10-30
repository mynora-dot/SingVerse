import type { RouteRecordRaw } from 'vue-router'
const component = () => import('./views/AiSettingsView.vue')
export const aiSettingsRoutes: RouteRecordRaw[] = [
  { path: '/system/ai', redirect: '/system/ai/models' },
  ...['models', 'search', 'features', 'features/assistant'].map(section => ({ path: `/system/ai/${section}`, component, meta: { title: 'AI 设置', activeMenu: '/system/ai' } })),
]
