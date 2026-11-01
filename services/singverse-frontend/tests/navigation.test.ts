// @vitest-environment jsdom
import { flushPromises, mount } from '@vue/test-utils'
import { afterEach, beforeEach, expect, it, vi } from 'vitest'
import App from '../src/admin/App.vue'
import { router } from '../src/admin/app/router/index'

beforeEach(() => {
  vi.stubGlobal('scrollTo', vi.fn())
  vi.stubGlobal('fetch', vi.fn().mockImplementation((path: string) => Promise.resolve(Response.json(path.includes('/auth/session') ? { username: 'admin', defaultPassword: true, mfaEnabled: false, recoveryCodesRemaining: 0 } : { status: 'UP' }))))
})

afterEach(() => vi.unstubAllGlobals())

it('键盘跳转主要内容不会破坏 hash 路由', async () => {
  await router.push('/overview')
  const wrapper = mount(App, { attachTo: document.body, global: { plugins: [router] } })
  await flushPromises()
  await wrapper.get('.skip-link').trigger('click')
  expect(document.activeElement?.id).toBe('main-content')
  expect(router.currentRoute.value.path).toBe('/overview')
  wrapper.unmount()
})

it('未知链接显示明确提示，用户可以返回概览', async () => {
  await router.push('/missing-page')
  const wrapper = mount(App, { global: { plugins: [router] } })
  await flushPromises()
  expect(wrapper.text()).toContain('页面不存在')
  await wrapper.get('.text-link').trigger('click')
  await flushPromises()
  expect(router.currentRoute.value.path).toBe('/overview')
  expect(wrapper.get('h1').text()).toBe('概览')
  wrapper.unmount()
})


it('窄屏导航可展开，切换页面后收起并同步无障碍状态', async () => {
  await router.push('/overview')
  const wrapper = mount(App, { global: { plugins: [router] } })
  await flushPromises()
  const toggle = wrapper.get('.mobile-nav-toggle')
  expect(toggle.attributes('aria-expanded')).toBe('false')
  await toggle.trigger('click')
  expect(toggle.attributes('aria-expanded')).toBe('true')
  expect(wrapper.get('.sidebar').classes()).toContain('sidebar-open')
  await router.push('/system/parameters')
  await flushPromises()
  expect(toggle.attributes('aria-expanded')).toBe('false')
  expect(wrapper.get('.sidebar').classes()).not.toContain('sidebar-open')
  wrapper.unmount()
})

it('导航直接提供分类与账号安全入口，Escape 关闭移动菜单', async () => {
  await router.push('/overview')
  const wrapper = mount(App, { global: { plugins: [router] } })
  await flushPromises()
  expect(wrapper.get('#admin-navigation').text()).toContain('歌曲分类')
  expect(wrapper.get('#admin-navigation').text()).toContain('账号与安全')
  await wrapper.get('.mobile-nav-toggle').trigger('click')
  await wrapper.get('.sidebar').trigger('keydown', { key: 'Escape' })
  expect(wrapper.get('.mobile-nav-toggle').attributes('aria-expanded')).toBe('false')
  expect(wrapper.find('.nav-scrim').exists()).toBe(false)
  wrapper.unmount()
})

it('导航按任务平铺分组，深链接高亮所属页面并同步面包屑', async () => {
  await router.push('/system/parameters')
  const wrapper = mount(App, { global: { plugins: [router] } })
  await flushPromises()
  const groups = wrapper.findAll('.nav-group')
  expect(groups.map(group => group.get('.nav-group-title').text())).toEqual(['曲库', '来源与导入', '设备与服务', '系统设置'])
  expect(wrapper.get('.breadcrumb').text()).toContain('系统设置')
  expect(wrapper.get('.breadcrumb').text()).toContain('账号与安全')
  const active = wrapper.findAll('#admin-navigation [aria-current="page"]')
  expect(active.map(item => item.text())).toEqual(['账号与安全'])
  // 所有页面一次点击可达，无需先展开分组。
  await wrapper.findAll('.nav-item').find(item => item.text() === '服务状态')!.trigger('click')
  await flushPromises()
  expect(router.currentRoute.value.path).toBe('/system')
  expect(wrapper.get('#admin-navigation [aria-current="page"]').text()).toBe('服务状态')
  expect(wrapper.get('.breadcrumb').text()).toContain('设备与服务')
  expect(groups[1]!.text()).toContain('自定义规则')
  expect(groups[1]!.text()).toContain('高级')
  wrapper.unmount()
})

it('日常设备与服务独立成组，子路由仍高亮父级入口', async () => {
  await router.push('/system/ai/features')
  const wrapper = mount(App, { global: { plugins: [router] } })
  await flushPromises()
  const groups = wrapper.findAll('.nav-group')
  expect(groups[0]!.findAll('.nav-item').map(item => item.text())).toEqual(['全部歌曲', '歌手库', '歌单管理', '歌曲分类', '元数据整理'])
  expect(groups[2]!.findAll('.nav-item').map(item => item.text())).toEqual(['电视设备', '服务状态', '性能节点配置'])
  expect(groups[3]!.findAll('.nav-item').map(item => item.text())).toEqual(['AI 设置', '账号与安全'])
  expect(wrapper.get('#admin-navigation [aria-current="page"]').text()).toBe('AI 设置')
  expect(wrapper.get('.breadcrumb').text()).toContain('AI 设置')
  wrapper.unmount()
})
