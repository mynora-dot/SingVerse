// @vitest-environment jsdom
import { flushPromises, mount } from '@vue/test-utils'
import { afterEach, beforeEach, expect, it, vi } from 'vitest'
import { createMemoryHistory, createRouter, type Router } from 'vue-router'
import OverviewView from '../src/admin/modules/overview/views/OverviewView.vue'
import { auth } from '../src/admin/core/auth/api'
import { preview } from './fixtures/filename-workspace'

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
const emptyTasks = { items: [], page: 0, size: 20, totalElements: 0, observedAt: '' }
const baseOverview = (over: Record<string, unknown> = {}) => ({
  library: { totalSongs: 0, reviewSongs: 0, formalSongs: 0, formalVariants: 0, formalMedia: 0 },
  sources: { total: 0, failedTotal: 0, neverScanned: 0, failed: [] },
  review: { metadataBatch: null, classification: { activeTasks: 0, reviewItems: 0, latestTaskId: '' }, missingFeedbackPending: 0 },
  tv: { activeDevices: 0, pending: 0 },
  tasks: { activeTotal: 0, counts: {}, items: [], observedAt: '' },
  recentSongs: [],
  playback30d: { totalPlays: 0, effectivePlays: 0, playedSongs: 0, playedMs: 0 },
  cache: { usageBytes: 0, entryCount: 0 },
  readiness: { compute: { configured: true, mode: 'AUTO' }, ai: { configured: true, enabledFeatures: ['PLAYLIST'] } },
  playlists: [],
  observedAt: '2026-11-11T00:00:00Z',
  errors: {},
  ...over,
})

beforeEach(async () => {
  router = createRouter({ history: createMemoryHistory(), routes: [{ path: '/:p(.*)*', component: { template: '<div />' } }] })
  await router.push('/overview')
  auth.account = { username: 'admin', defaultPassword: false, mfaEnabled: true, recoveryCodesRemaining: 8 } as typeof auth.account
})
afterEach(() => { vi.useRealTimers(); vi.unstubAllGlobals(); auth.account = null })

it('使用聚合接口统计真实数字，并把待办链接到对应页面', async () => {
  const fetchMock = serve({
    '/api/v1/admin/overview': baseOverview({
      library: { totalSongs: 1280, reviewSongs: 12, formalSongs: 900, formalVariants: 950, formalMedia: 1000 },
      sources: { total: 2, failedTotal: 1, neverScanned: 0, failed: [{ id: 'b', name: '来源b', lastScanError: '目录不可读' }] },
      tv: { activeDevices: 1, pending: 1 },
    }),
    '/api/v1/background-tasks': emptyTasks,
    '/api/health': { status: 'UP' },
  })
  const wrapper = mount(OverviewView, { global: { plugins: [router] } })
  try {
    await flushPromises()
    const tiles = wrapper.findAll('.stat-block').map(tile => tile.text())
    expect(tiles[0]).toContain('1,280')
    expect(tiles[1]).toContain('900')
    expect(tiles[2]).toContain('12')
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

it('单块读取失败只影响该块，不显示“无待办”，可重新读取恢复', async () => {
  let fail = true
  serve({
    '/api/v1/admin/overview': () => Response.json(baseOverview(fail ? { library: null, errors: { library: '数据库忙' } } : {})),
    '/api/v1/background-tasks': emptyTasks,
    '/api/health': { status: 'UP' },
  })
  auth.account = { username: 'admin', defaultPassword: true, mfaEnabled: false, recoveryCodesRemaining: 0 } as typeof auth.account
  const wrapper = mount(OverviewView, { global: { plugins: [router] } })
  try {
    await flushPromises()
    expect(wrapper.findAll('.stat-block')[0]!.text()).toContain('读取失败')
    expect(wrapper.findAll('.stat-block')[3]!.text()).toContain('0')
    expect(wrapper.get('[role="alert"]').text()).toContain('1 项状态读取失败')
    const todos = wrapper.get('.todo-list').text()
    expect(todos).toContain('仍在使用默认密码')
    expect(todos).toContain('还没有添加媒体库')
    expect(todos).toContain('未开启二次验证')
    fail = false
    await wrapper.get('[role="alert"] button').trigger('click'); await flushPromises()
    expect(wrapper.find('[role="alert"]').exists()).toBe(false)
    expect(wrapper.findAll('.stat-block')[0]!.text()).toContain('0')
  } finally { wrapper.unmount() }
})

it('首页展示真实收藏与后台任务，未知整体进度不会冒充百分比', async () => {
  const song = { id: 'recent-song', result: { ...preview().result, title: '收藏里的歌', artists: ['家庭歌手'] } }
  serve({
    '/api/v1/admin/overview': baseOverview({
      library: { totalSongs: 1, reviewSongs: 0, formalSongs: 1, formalVariants: 1, formalMedia: 1 },
      sources: { total: 1, failedTotal: 0, neverScanned: 0, failed: [] },
      recentSongs: [song],
      playlists: [{ id: 'playlist', name: '周末相聚', count: 3 }],
    }),
    '/api/v1/background-tasks': { items: [{ id: 'task-1', name: '准备全家合唱', type: 'AUDIO_BATCH', status: 'RUNNING', stage: 'PROCESSING', progress: { completed: null, total: null, percent: null, unit: 'SONGS' }, statistics: {} }], page: 0, size: 20, totalElements: 1, observedAt: '' },
    '/api/health': { status: 'UP' },
  })
  const wrapper = mount(OverviewView, { global: { plugins: [router] } })
  try {
    await flushPromises()
    expect(wrapper.get('.shelf-song').text()).toContain('收藏里的歌')
    expect(wrapper.get('.shelf-song').text()).toContain('家庭歌手')
    expect(wrapper.get('.shelf-song').attributes('href')).toContain('detailSongId=recent-song')
    expect(wrapper.get('.home-playlists').text()).toContain('周末相聚3 首歌曲')
    expect(wrapper.get('.home-task').text()).toContain('整体进度暂不可用')
    expect(wrapper.get('.home-task').text()).not.toContain('%')
    await wrapper.get('.home-task').trigger('click'); await flushPromises()
    expect(router.currentRoute.value.fullPath).toBe('/system/tasks/task-1')
  } finally { wrapper.unmount() }
})

it('首页离开或后台隐藏后停止任务轮询，聚合接口只在进入时读取一次', async () => {
  vi.useFakeTimers()
  const visibility = vi.spyOn(document, 'visibilityState', 'get').mockReturnValue('visible')
  const fetchMock = serve({
    '/api/v1/admin/overview': baseOverview(),
    '/api/v1/background-tasks': emptyTasks,
    '/api/health': { status: 'UP' },
  })
  const taskCalls = () => fetchMock.mock.calls.filter(([path]) => String(path).startsWith('/api/v1/background-tasks')).length
  const overviewCalls = () => fetchMock.mock.calls.filter(([path]) => String(path).startsWith('/api/v1/admin/overview')).length
  const wrapper = mount(OverviewView, { global: { plugins: [router] } })
  try {
    await flushPromises(); expect(taskCalls()).toBe(1); expect(overviewCalls()).toBe(1)
    await vi.advanceTimersByTimeAsync(10000); expect(taskCalls()).toBe(2); expect(overviewCalls()).toBe(1)
    visibility.mockReturnValue('hidden'); document.dispatchEvent(new Event('visibilitychange'))
    await vi.advanceTimersByTimeAsync(30000); expect(taskCalls()).toBe(2)
    visibility.mockReturnValue('visible'); document.dispatchEvent(new Event('visibilitychange'))
    await flushPromises(); expect(taskCalls()).toBe(3)
    wrapper.unmount()
    await vi.advanceTimersByTimeAsync(30000); expect(taskCalls()).toBe(3)
  } finally { if (wrapper.exists()) wrapper.unmount(); visibility.mockRestore() }
})

it('空曲库给出连接来源的引导，而不是伪造收藏', async () => {
  serve({
    '/api/v1/admin/overview': baseOverview(),
    '/api/v1/background-tasks': emptyTasks,
    '/api/health': { status: 'UP' },
  })
  const wrapper = mount(OverviewView, { global: { plugins: [router] } })
  try {
    await flushPromises()
    expect(wrapper.find('.recent-shelf').exists()).toBe(false)
    expect(wrapper.get('.empty-state').text()).toContain('从第一首喜欢的歌开始')
    expect(wrapper.get('.todo-list').text()).toContain('还没有添加媒体库')
    expect(wrapper.findAll('.stat-block')[0]!.text()).toContain('0')
  } finally { wrapper.unmount() }
})

it('未配置计算节点或 AI 时就绪 chips 给出引导链接', async () => {
  serve({
    '/api/v1/admin/overview': baseOverview({ readiness: { compute: { configured: false, mode: '' }, ai: { configured: false, enabledFeatures: [] } } }),
    '/api/v1/background-tasks': emptyTasks,
    '/api/health': { status: 'UP' },
  })
  const wrapper = mount(OverviewView, { global: { plugins: [router] } })
  try {
    await flushPromises()
    const chips = wrapper.findAll('.readiness-chip.warn')
    expect(chips).toHaveLength(2)
    expect(chips[0]!.text()).toContain('计算节点未配置')
    expect(chips[0]!.attributes('href')).toContain('/system/compute')
    expect(chips[1]!.text()).toContain('AI 未配置')
    expect(chips[1]!.attributes('href')).toContain('/system/ai')
  } finally { wrapper.unmount() }
})

it('待办 inbox 覆盖平台审核、分类确认与缺歌心愿并链接到对应批次', async () => {
  serve({
    '/api/v1/admin/overview': baseOverview({
      review: {
        metadataBatch: { id: 'batch-9', status: 'REVIEW', review: 4, completed: 6, total: 10 },
        classification: { activeTasks: 1, reviewItems: 7, latestTaskId: 'task-3' },
        missingFeedbackPending: 2,
      },
    }),
    '/api/v1/background-tasks': emptyTasks,
    '/api/health': { status: 'UP' },
  })
  const wrapper = mount(OverviewView, { global: { plugins: [router] } })
  try {
    await flushPromises()
    const todos = wrapper.findAll('.todo-item').map(item => item.text())
    expect(todos.some(text => text.includes('4 首平台匹配待审核'))).toBe(true)
    expect(todos.some(text => text.includes('7 首分类建议待确认'))).toBe(true)
    expect(todos.some(text => text.includes('2 条缺歌心愿待处理'))).toBe(true)
    const actions = wrapper.findAll('.todo-action')
    expect(actions.find(a => a.text().includes('去审核'))!.attributes('href')).toContain('/library/songs/metadata?batch=batch-9')
    expect(actions.find(a => a.text().includes('去确认'))!.attributes('href')).toContain('/library/classification?task=task-3')
    expect(actions.find(a => a.text().includes('查看心愿'))!.attributes('href')).toContain('/library/missing-feedback')
  } finally { wrapper.unmount() }
})
