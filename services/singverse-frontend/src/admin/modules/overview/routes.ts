import type { RouteRecordRaw } from 'vue-router'
import OverviewView from './views/OverviewView.vue'

export const overviewRoutes: RouteRecordRaw[] = [
  { path: '/overview', component: OverviewView, meta: { title: '概览' } },
]
