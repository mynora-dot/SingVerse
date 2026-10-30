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
  expect(wrapper.text()).toContain('把喜欢的音乐，留在自己的生活里')
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

it('菜单按用途分为两级，深链接展开所属分组并同步面包屑', async () => {
  await router.push('/system/parameters')
  const wrapper = mount(App, { global: { plugins: [router] } })
  await flushPromises()
  const groups = wrapper.findAll('.el-sub-menu')
  expect(groups.map(group => group.get('.el-sub-menu__title').text())).toEqual(['曲库管理', '文件名解析', '系统设置'])
  expect(groups[2]!.classes()).toContain('is-opened')
  expect(wrapper.get('.breadcrumb').text()).toContain('系统设置')
  expect(wrapper.get('.breadcrumb').text()).toContain('账号与安全')
  expect(wrapper.get('.el-menu-item.is-active').text()).toBe('账号与安全')
  await groups[1]!.get('.el-sub-menu__title').trigger('click')
  await flushPromises()
  expect(groups[1]!.classes()).toContain('is-opened')
  expect(groups[2]!.classes()).not.toContain('is-opened')
  expect(groups[1]!.text()).toContain('解析方案')
  expect(groups[1]!.text()).toContain('自定义规则')
  wrapper.unmount()
})

it('AI 设置位于系统分组首位，歌曲分类仍属于曲库管理', async () => {
  await router.push('/system/ai/features')
  const wrapper = mount(App, { global: { plugins: [router] } })
  await flushPromises()
  const groups = wrapper.findAll('.el-sub-menu')
  expect(groups[0]!.text()).toContain('歌曲分类')
  expect(groups[2]!.findAll('.el-menu-item').map(item => item.text())).toEqual(['AI 设置', '电视设备', '性能节点配置', '服务状态', '账号与安全'])
  expect(wrapper.get('.el-menu-item.is-active').text()).toBe('AI 设置')
  expect(wrapper.get('.breadcrumb').text()).toContain('AI 设置')
  wrapper.unmount()
})
