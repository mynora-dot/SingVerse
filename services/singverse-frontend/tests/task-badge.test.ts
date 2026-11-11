// @vitest-environment jsdom
import { flushPromises, mount } from '@vue/test-utils'
import { afterEach, beforeEach, expect, it, vi } from 'vitest'
import App from '../src/admin/App.vue'
import { router } from '../src/admin/app/router/index'
import { auth } from '../src/admin/core/auth/api'

function mockFetch(activeTotal: number) {
  vi.stubGlobal('fetch', vi.fn().mockImplementation((path: string) => {
    if (path.includes('/auth/session')) return Promise.resolve(Response.json({ username: 'admin', defaultPassword: false, mfaEnabled: true, recoveryCodesRemaining: 0 }))
    if (path.includes('view=ACTIVE')) return Promise.resolve(Response.json({ items: [], totalElements: activeTotal }))
    if (path.includes('/ai/') || path.endsWith('/classification/tags')) return Promise.resolve(Response.json([]))
    return Promise.resolve(Response.json({ status: 'UP', items: [], totalElements: 0, devices: [], pending: [] }))
  }))
}
beforeEach(() => { vi.stubGlobal('scrollTo', vi.fn()); auth.account = null })
afterEach(() => { vi.unstubAllGlobals(); auth.account = null })

it('顶栏徽章显示活跃任务总数并写入无障碍标签', async () => {
  mockFetch(5)
  await router.push('/overview')
  const wrapper = mount(App, { global: { plugins: [router] } }); await flushPromises()
  expect(wrapper.get('.masthead-actions .task-count').text()).toBe('5')
  expect(wrapper.get('.masthead-actions .utility-link').attributes('aria-label')).toContain('5 个进行中')
  wrapper.unmount()
})

it('没有活跃任务时不显示徽章', async () => {
  mockFetch(0)
  await router.push('/overview')
  const wrapper = mount(App, { global: { plugins: [router] } }); await flushPromises()
  expect(wrapper.find('.masthead-actions .task-count').exists()).toBe(false)
  expect(wrapper.get('.masthead-actions .utility-link').attributes('aria-label')).toBe('任务中心')
  wrapper.unmount()
})

it('活跃任务超过 99 时徽章显示 99+', async () => {
  mockFetch(120)
  await router.push('/overview')
  const wrapper = mount(App, { global: { plugins: [router] } }); await flushPromises()
  expect(wrapper.get('.masthead-actions .task-count').text()).toBe('99+')
  wrapper.unmount()
})

it('只请求活跃视图且每页一条，不拉取任务明细', async () => {
  mockFetch(2)
  await router.push('/overview')
  const wrapper = mount(App, { global: { plugins: [router] } }); await flushPromises()
  const calls = vi.mocked(fetch).mock.calls.map(call => String(call[0]))
  const badgeCall = calls.find(path => path.includes('/background-tasks') && path.includes('view=ACTIVE'))
  expect(badgeCall).toContain('size=1')
  expect(badgeCall).toContain('page=0')
  wrapper.unmount()
})