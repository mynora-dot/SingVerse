// @vitest-environment jsdom
import { flushPromises, mount } from '@vue/test-utils'
import { afterEach, beforeEach, expect, it, vi } from 'vitest'
import App from '../src/admin/App.vue'
import { router } from '../src/admin/app/router/index'
import { navigationGroups, navigationContext } from '../src/admin/app/navigation'

beforeEach(() => {
  vi.stubGlobal('scrollTo', vi.fn())
  vi.stubGlobal('fetch', vi.fn().mockImplementation((path: string) => Promise.resolve(path.includes('/background-tasks/batch-1') ? Response.json({detail:'任务不存在'}, {status:404}) : Response.json((path.includes('/ai/') || path.endsWith('/classification/tags')) ? [] : path.includes('/auth/session') ? { username: 'admin', defaultPassword: true, mfaEnabled: false, recoveryCodesRemaining: 0 } : { status: 'UP', items: [], totalElements: 0, devices: [], pending: [] }))))
})
afterEach(() => vi.unstubAllGlobals())
it('键盘跳转主要内容不会破坏 hash 路由', async () => {
  await router.push('/overview')
  const wrapper = mount(App, { attachTo: document.body, global: { plugins: [router] } })
  await flushPromises(); await wrapper.get('.skip-link').trigger('click')
  expect(document.activeElement?.id).toBe('main-content'); expect(router.currentRoute.value.path).toBe('/overview')
  wrapper.unmount()
})
it('未知链接提供返回空间的入口', async () => {
  await router.push('/missing-page'); const wrapper = mount(App, { global: { plugins: [router] } }); await flushPromises()
  expect(wrapper.text()).toContain('页面不存在'); await wrapper.get('.text-link').trigger('click'); await flushPromises()
  expect(router.currentRoute.value.path).toBe('/overview'); expect(wrapper.get('h1').text()).toBe('好歌，在家相聚。'); wrapper.unmount()
})
it('窄屏全部导航切页后关闭，Escape 恢复焦点并限制键盘范围', async () => {
  await router.push('/overview'); const wrapper = mount(App, { attachTo: document.body, global: { plugins: [router] } }); await flushPromises()
  const toggle = wrapper.get('.mobile-nav-toggle'); await toggle.trigger('click'); await flushPromises()
  expect(toggle.attributes('aria-expanded')).toBe('true'); expect(wrapper.get('.navigation-dialog').attributes('aria-modal')).toBe('true')
  expect(wrapper.get('.workspace').attributes('inert')).toBeDefined()
  const links = wrapper.findAll('#admin-navigation a'); (links[links.length - 1]!.element as HTMLElement).focus()
  await wrapper.get('.navigation-dialog').trigger('keydown', { key: 'Tab' })
  expect(document.activeElement).toBe(wrapper.get('.sheet-heading button').element)
  await wrapper.get('.navigation-dialog').trigger('keydown', { key: 'Escape' }); await flushPromises()
  expect(toggle.attributes('aria-expanded')).toBe('false'); expect(document.activeElement).toBe(toggle.element)
  await toggle.trigger('click'); await router.push('/system/parameters'); await flushPromises()
  expect(wrapper.find('.navigation-dialog').exists()).toBe(false); wrapper.unmount()
})
it('精简主导航与当前分区提供全部现有管理功能', async () => {
  await router.push('/library/songs'); const wrapper = mount(App, { global: { plugins: [router] } }); await flushPromises()
  expect(wrapper.findAll('.primary-navigation a').map(a => a.text())).toEqual(['概览','曲库','添加与整理','电视与反馈'])
  expect(wrapper.findAll('.section-navigation a').map(a => a.text())).toEqual(['全部歌曲','歌手','共享歌单'])
  const expected = ['/library/songs','/library/artists','/library/playlists','/library/media','/library/downloads','/library/songs/metadata','/library/audio-processing','/library/classification','/system/tv-devices','/system/tv-settings','/library/playback-history','/library/missing-feedback','/system/parameters','/library/cache','/system/compute','/system/ai','/system']
  expect(navigationGroups.flatMap(g => g.children.map(e => e.path)).sort()).toEqual(expected.sort())
  await wrapper.get('.mobile-nav-toggle').trigger('click')
  expect(wrapper.get('#admin-navigation').text()).toContain('账号与安全'); expect(wrapper.get('#admin-navigation').text()).toContain('分类与标签')
  wrapper.unmount()
})
it('设置子路由与任务批次深链接高亮所属入口', async () => {
  await router.push('/system/ai/features'); const wrapper = mount(App, { global: { plugins: [router] } }); await flushPromises()
  expect(wrapper.get('.section-navigation [aria-current="page"]').text()).toBe('AI 与助手')
  expect(wrapper.get('.settings-link').attributes('aria-current')).toBe('page')
  await router.push('/system/tasks/batch-1'); await flushPromises()
  expect(wrapper.get('.utility-link[aria-current="page"]').text()).toBe('任务')
  expect(navigationContext('/system/tasks/batch-1').entry?.path).toBe('/system/tasks')
  wrapper.unmount()
})
it('旧方案和规则链接归入歌曲来源并保留指定方案、查询与锚点', async () => {
  await router.push('/library/filename-parser-profiles?profile=chosen&source=existing#preview')
  expect(router.currentRoute.value.path).toBe('/library/media/recognition')
  expect(router.currentRoute.value.query).toEqual({ profile: 'chosen', source: 'existing' })
  expect(router.currentRoute.value.hash).toBe('#preview')
  expect(router.currentRoute.value.meta.activeMenu).toBe('/library/media')
  await router.push('/library/filename-parser?profile=chosen')
  expect(router.currentRoute.value.path).toBe('/library/media/recognition/rules')
  expect(router.currentRoute.value.query.profile).toBe('chosen')
  expect(navigationContext(router.currentRoute.value.path).entry?.label).toBe('歌曲来源')
  expect(navigationContext('/library/filename-parser-profiles').entry?.label).toBe('歌曲来源')
})
