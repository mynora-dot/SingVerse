import type { RouteRecordRaw } from 'vue-router'
import LoginView from './views/LoginView.vue'

export const authRoutes: RouteRecordRaw[] = [
  { path: '/login', component: LoginView, meta: { title: '登录' } },
]
