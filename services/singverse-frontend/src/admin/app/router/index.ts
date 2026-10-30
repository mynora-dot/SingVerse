import { createRouter, createWebHashHistory } from 'vue-router'
import { computeSettingsRoutes } from '../../modules/compute-settings/routes'
import { aiSettingsRoutes } from '../../modules/ai-settings/routes'
import { songManagementRoutes } from '../../modules/song-management/routes'
import { mediaLibraryRoutes } from '../../modules/media-library/routes'
import { filenameParserRoutes } from '../../modules/filename-parser/routes'
import { overviewRoutes } from '../../modules/overview/routes'
import { systemStatusRoutes } from '../../modules/system-status/routes'
import NotFoundView from '../views/NotFoundView.vue'
import { authRoutes } from '../../modules/auth/routes'
import { systemParametersRoutes } from '../../modules/system-parameters/routes'
import { refreshAccount } from '../../core/auth/api'
import { adminBasePath } from '../../core/hosting/mount-path'

export const router = createRouter({
  history: createWebHashHistory(adminBasePath),
  routes: [
    { path: "/system/tv-devices", component: () => import("../../modules/tv-devices/TvDevicesView.vue"), meta: { title: "电视设备" } },
    ...mediaLibraryRoutes,
    ...songManagementRoutes,
    ...filenameParserRoutes,
    ...authRoutes,
    ...systemParametersRoutes,
    ...aiSettingsRoutes,
    ...computeSettingsRoutes,
    { path: '/', redirect: '/overview' },
    ...overviewRoutes,
    ...systemStatusRoutes,
    { path: '/:pathMatch(.*)*', component: NotFoundView, meta: { title: '页面不存在' } },
  ],
  scrollBehavior: () => ({ top: 0 }),
})

router.beforeEach(async (to) => {
  if (to.path === '/login') return true
  try {
    if (await refreshAccount()) return true
  } catch { /* 登录页提供网络错误与重试入口。 */ }
  return { path: '/login', query: { redirect: to.fullPath } }
})

router.afterEach((to) => {
  document.title = `${String(to.meta.title)} · SingVerse 管理中心`
})
