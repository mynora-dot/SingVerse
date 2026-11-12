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
  expect(router.currentRoute.value.path).toBe('/overview'); expect(wrapper.get('h1').text()).toBe('曲库概览'); wrapper.unmount()
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
  expect(wrapper.findAll('.primary-navigation a').map(a => a.text())).toEqual(['首页','曲库','加歌','整理','客厅'])
  expect(wrapper.findAll('.section-navigation a').map(a => a.text())).toEqual(['歌曲','歌手','歌单','分类标签'])
  const expected = ['/library/songs','/library/artists','/library/playlists','/library/classification','/library/media','/library/downloads','/library/media/recognition','/library/songs/metadata','/library/audio-processing','/library/review','/system/tv-devices','/system/tv-settings','/library/playback-history','/library/missing-feedback','/system/parameters','/system/music-platforms','/library/cache','/system/compute','/system/ai','/system']
  expect(navigationGroups.flatMap(g => g.children.map(e => e.path)).sort()).toEqual(expected.sort())
  await wrapper.get('.mobile-nav-toggle').trigger('click')
  expect(wrapper.get('#admin-navigation').text()).toContain('账号与安全'); expect(wrapper.get('#admin-navigation').text()).toContain('分类标签')
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
  expect(router.currentRoute.value.meta.activeMenu).toBe('/library/media/recognition')
  await router.push('/library/filename-parser?profile=chosen')
  expect(router.currentRoute.value.path).toBe('/library/media/recognition/rules')
  expect(router.currentRoute.value.query.profile).toBe('chosen')
  expect(navigationContext(router.currentRoute.value.path).entry?.label).toBe('文件名识别')
  expect(navigationContext('/library/filename-parser-profiles').entry?.label).toBe('文件名识别')
})

it('音乐平台在设置中直接可达并高亮，显示真实五平台配置', async () => {
  vi.mocked(fetch).mockImplementation(async input => {
    const path = String(input)
    if (path.endsWith('/metadata/settings')) return Response.json({ enabled: true, providers: ['NETEASE', 'QQ', 'KUGOU', 'KUWO', 'QISHUI'], autoApply: false, threshold: .95, minIntervalSeconds: 3, maxIntervalSeconds: 8 })
    if (path.endsWith('/metadata/batches/latest')) return new Response(null, { status: 204 })
    return Response.json({ username: 'admin', status: 'UP', items: [], totalElements: 0 })
  })
  await router.push('/system/music-platforms')
  const wrapper = mount(App, { global: { plugins: [router] } }); await flushPromises()
  expect(wrapper.get('.settings-link').attributes('aria-current')).toBe('page')
  expect(wrapper.get('.section-navigation [aria-current="page"]').text()).toBe('音乐平台')
  expect(wrapper.get('h1').text()).toBe('音乐平台')
  expect(wrapper.findAll('.platform-options input[type="checkbox"]')).toHaveLength(5)
  expect(wrapper.text()).toContain('已开启 5 个平台')
  wrapper.unmount()
})

it('顶栏全局搜索跳转到歌曲工作区并携带搜索词与透镜', async () => {
  await router.push('/overview')
  const wrapper = mount(App, { global: { plugins: [router] } }); await flushPromises()
  await wrapper.get('.search-input').setValue('周杰伦')
  await wrapper.get('.masthead-search').trigger('submit'); await flushPromises()
  expect(router.currentRoute.value.path).toBe('/library/songs')
  expect(router.currentRoute.value.query).toEqual({ lens: 'songs', search: '周杰伦' })
  wrapper.unmount()
})
