// @vitest-environment jsdom
import { flushPromises, mount } from '@vue/test-utils'
import { afterEach, beforeEach, expect, it, vi } from 'vitest'
import { createMemoryHistory, createRouter, type Router } from 'vue-router'
import OverviewView from '../src/admin/modules/overview/views/OverviewView.vue'
import { auth } from '../src/admin/core/auth/api'

let router: Router
type Reply = unknown | (() => Response)
function serve(routes: Record<string, Reply>) {
  const fetchMock = vi.fn((path: string) => {
    const key = Object.keys(routes).find(prefix => path.startsWith(prefix))
    if (!key) return Promise.resolve(Response.json({ detail: '未定义' }, { status: 404 }))
    const reply = routes[key]
    return Promise.resolve(typeof reply === 'function' ? (reply as () => Response)() : Response.json(reply))
  })
  vi.stubGlobal('fetch', fetchMock)
  return fetchMock
}
const source = (id: string, lastScanError: string | null = null) => ({ id, name: `来源${id}`, type: 'LOCAL', location: `/music/${id}`, username: '', parserProfileId: 'default', passwordConfigured: false, createdAt: '', updatedAt: '', lastScanError })

beforeEach(async () => {
  router = createRouter({ history: createMemoryHistory(), routes: [{ path: '/:p(.*)*', component: { template: '<div />' } }] })
  await router.push('/overview')
  auth.account = { username: 'admin', defaultPassword: false, mfaEnabled: true, recoveryCodesRemaining: 8 } as typeof auth.account
})
afterEach(() => { vi.unstubAllGlobals(); auth.account = null })

it('使用真实接口统计歌曲、待复核、来源与电视，并把待办链接到对应页面', async () => {
  const fetchMock = serve({
    '/api/v1/media-songs?page=0&size=1&status=ALL': { items: [], page: 0, size: 1, totalElements: 1280 },
    '/api/v1/media-songs?page=0&size=1&status=REVIEW': { items: [], page: 0, size: 1, totalElements: 12 },
    '/api/v1/media-sources': { items: [source('a'), source('b', '目录不可读')], page: 0, size: 100, totalElements: 2 },
    '/api/v1/admin/tv-devices': { devices: [{ id: 't1', name: '客厅', revoked: false }, { id: 't2', name: '旧电视', revoked: true }], pending: [{ id: 'p', name: '卧室', expiresAt: '' }] },
    '/api/health': { status: 'UP' },
  })
  const wrapper = mount(OverviewView, { global: { plugins: [router] } })
  try {
    await flushPromises()
    const tiles = wrapper.findAll('.stat-tile').map(tile => tile.text())
    expect(tiles[0]).toContain('1,280')
    expect(tiles[1]).toContain('12')
    expect(tiles[2]).toContain('2')
    expect(tiles[3]).toContain('1')
    const todos = wrapper.findAll('.todo-item').map(item => item.text())
    expect(todos.some(text => text.includes('1 台电视等待授权'))).toBe(true)
    expect(todos.some(text => text.includes('1 个媒体库最近扫描失败') && text.includes('来源b'))).toBe(true)
    expect(todos.some(text => text.includes('12 首歌曲待复核'))).toBe(true)
    expect(todos.some(text => text.includes('默认密码') || text.includes('二次验证'))).toBe(false)
    const review = wrapper.findAll('.todo-action').find(link => link.text().includes('去复核'))!
    await review.trigger('click'); await flushPromises()
    expect(router.currentRoute.value.fullPath).toBe('/library/songs?status=REVIEW')
    expect(fetchMock.mock.calls.every(([path]) => String(path).startsWith('/api/'))).toBe(true)
  } finally { wrapper.unmount() }
})

it('单项读取失败只影响该项，不显示“无待办”，可重新读取恢复', async () => {
  let songsFail = true
  serve({
    '/api/v1/media-songs': () => songsFail ? Response.json({ detail: '数据库忙' }, { status: 503 }) : Response.json({ items: [], page: 0, size: 1, totalElements: 0 }),
    '/api/v1/media-sources': { items: [], page: 0, size: 100, totalElements: 0 },
    '/api/v1/admin/tv-devices': { devices: [], pending: [] },
    '/api/health': { status: 'UP' },
  })
  auth.account = { username: 'admin', defaultPassword: true, mfaEnabled: false, recoveryCodesRemaining: 0 } as typeof auth.account
  const wrapper = mount(OverviewView, { global: { plugins: [router] } })
  try {
    await flushPromises()
    expect(wrapper.findAll('.stat-tile')[0]!.text()).toContain('读取失败')
    expect(wrapper.findAll('.stat-tile')[2]!.text()).toContain('0')
    expect(wrapper.get('[role="alert"]').text()).toContain('1 项状态读取失败')
    const todos = wrapper.get('.todo-list').text()
    expect(todos).toContain('仍在使用默认密码')
    expect(todos).toContain('还没有添加媒体库')
    expect(todos).toContain('未开启二次验证')
    songsFail = false
    await wrapper.get('[role="alert"] button').trigger('click'); await flushPromises()
    expect(wrapper.find('[role="alert"]').exists()).toBe(false)
    expect(wrapper.findAll('.stat-tile')[0]!.text()).toContain('0')
  } finally { wrapper.unmount() }
})
