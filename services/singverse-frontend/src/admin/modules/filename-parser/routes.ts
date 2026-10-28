import type { RouteRecordRaw } from 'vue-router'

export const filenameParserRoutes: RouteRecordRaw[] = [
  { path: '/library/filename-parser', component: () => import('./views/FilenameParserView.vue'), meta: { title: '解析规则' } },
  { path: '/library/filename-parser-profiles', component: () => import('./views/FilenameProfilesView.vue'), meta: { title: '解析方案' } },
]
