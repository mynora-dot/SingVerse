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
  expect(wrapper.text()).toContain('欢迎回到你的家庭音乐空间')
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
  expect(wrapper.get('#admin-navigation').classes()).toContain('navigation-open')
  await router.push('/system/parameters')
  await flushPromises()
  expect(toggle.attributes('aria-expanded')).toBe('false')
  expect(wrapper.get('#admin-navigation').classes()).not.toContain('navigation-open')
  wrapper.unmount()
})
