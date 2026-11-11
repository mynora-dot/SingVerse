import type { RouteRecordRaw } from 'vue-router'

export const filenameParserRoutes: RouteRecordRaw[] = [
  { path: '/library/media/recognition', component: () => import('./views/FilenameProfilesView.vue'), meta: { title: '文件名识别', activeMenu: '/library/media/recognition' } },
  { path: '/library/media/recognition/rules', component: () => import('./views/FilenameParserView.vue'), meta: { title: '文件名识别 · 自定义规则', activeMenu: '/library/media/recognition' } },
  { path: '/library/filename-parser', redirect: to => ({ path: '/library/media/recognition/rules', query: to.query, hash: to.hash }) },
  { path: '/library/filename-parser-profiles', redirect: to => ({ path: '/library/media/recognition', query: to.query, hash: to.hash }) },
]
