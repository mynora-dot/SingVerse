import type { RouteRecordRaw } from 'vue-router'

export const filenameParserRoutes: RouteRecordRaw[] = [
  { path: '/library/media/recognition', component: () => import('./views/FilenameProfilesView.vue'), meta: { title: '歌曲来源 · 文件名识别', activeMenu: '/library/media' } },
  { path: '/library/media/recognition/rules', component: () => import('./views/FilenameParserView.vue'), meta: { title: '歌曲来源 · 自定义规则', activeMenu: '/library/media' } },
  { path: '/library/filename-parser', redirect: to => ({ path: '/library/media/recognition/rules', query: to.query, hash: to.hash }) },
  { path: '/library/filename-parser-profiles', redirect: to => ({ path: '/library/media/recognition', query: to.query, hash: to.hash }) },
]
