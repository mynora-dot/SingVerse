// @vitest-environment jsdom
import { flushPromises, mount, type VueWrapper } from '@vue/test-utils'
import { afterEach, beforeEach, expect, it, vi } from 'vitest'
import { createMemoryHistory, createRouter, type Router } from 'vue-router'
import { ElPagination, ElSelect } from 'element-plus'
import View from '../src/admin/modules/background-tasks/BackgroundTasksView.vue'
import { backgroundTasks, type BackgroundTask, type ItemPage, type TaskPage } from '../src/admin/modules/background-tasks/api'
import { frameProgress } from '../src/admin/modules/background-tasks/presentation'

vi.mock('../src/admin/modules/background-tasks/api')
const sample = (changes: Partial<BackgroundTask> = {}): BackgroundTask => ({
  isBatch: true, batchKind: 'OPERATION', scopeSealed: true, parentId: null, batches: [], detailMode: 'CHILDREN',
  childrenCounts: { completed: 0, queued: 0, running: 1, failed: 0 },
  operation: { status: 'RUNNING', businessStatus: 'RUNNING', stage: 'SCANNING', progress: { completed: null, total: null, percent: null, unit: 'SONGS' }, finishedAt: null },
  id: 'task-one', type: 'WEBDAV_SCAN', businessId: 'scan-business', name: 'WebDAV 扫描 · 家庭曲库', subjectName: '家庭曲库',
  sourceId: 'source', songId: null, status: 'RUNNING', businessStatus: 'RUNNING', stage: 'SCANNING',
  progress: { completed: null, total: null, percent: null, unit: 'SONGS' }, statistics: { entries: 80, total_songs: 4 },
  execution: {}, waitingReason: '', error: '', createdAt: '2026-11-08T01:00:00Z', updatedAt: '2026-11-08T01:00:10Z',
  startedAt: null, finishedAt: null, itemsAvailable: false, timeIncomplete: true,
  relatedAvailable: true, relatedPath: '/library/media?source=source', observedAt: '2026-11-08T01:00:12Z', ...changes,
})
const page = (tasks = [sample()], total = tasks.length): TaskPage => ({
  items: tasks, totalElements: total, page: 0, size: 20, observedAt: '2026-11-08T01:00:12Z',
})
const emptyItems: ItemPage = { items: [], page: 0, size: 20, totalElements: 0, counts: {} }
let wrapper: VueWrapper | undefined, router: Router
beforeEach(() => {
  vi.useFakeTimers(); vi.resetAllMocks()
  vi.spyOn(document, 'visibilityState', 'get').mockReturnValue('visible')
  vi.mocked(backgroundTasks.list).mockResolvedValue(page())
  vi.mocked(backgroundTasks.detail).mockResolvedValue(sample())
  vi.mocked(backgroundTasks.children).mockResolvedValue({ ...page([]), counts: {} })
  vi.mocked(backgroundTasks.items).mockResolvedValue(emptyItems)
  router = createRouter({ history: createMemoryHistory(), routes: [
    { path: '/system/tasks', component: View }, { path: '/system/tasks/:id', component: View },
    { path: '/library/media', component: { template: '<div/>' } },
  ] })
})
afterEach(() => { wrapper?.unmount(); wrapper = undefined; vi.restoreAllMocks(); vi.useRealTimers() })
async function open(path = '/system/tasks') {
  await router.push(path); await router.isReady()
  wrapper = mount(View, { global: { plugins: [router] } }); await flushPromises()
}
const button = (name: string) => wrapper!.findAll('button').find(entry => entry.text() === name)!
async function tick(ms: number) { await vi.advanceTimersByTimeAsync(ms); await flushPromises() }

it('默认读取未完成任务，未知总量不显示百分比，筛选、搜索与分页传给服务端', async () => {
  vi.mocked(backgroundTasks.list).mockResolvedValue(page([sample()], 45))
  await open()
  expect(backgroundTasks.list).toHaveBeenCalledWith({ view: 'ACTIVE', type: '', status: '', q: '', page: 0 })
  expect(wrapper!.text()).toContain('已发现 4 首歌曲')
  expect(wrapper!.text()).not.toContain('100%')
  await tick(2000); expect(backgroundTasks.list).toHaveBeenCalledTimes(2)
  await wrapper!.findAllComponents(ElSelect)[0]!.vm.$emit('change', 'METADATA'); await flushPromises()
  await wrapper!.findAllComponents(ElSelect)[1]!.vm.$emit('change', 'FAILED'); await flushPromises()
  await wrapper!.get('input[aria-label="搜索任务名称或编号"]').setValue('  meta  ')
  await wrapper!.get('form').trigger('submit'); await flushPromises()
  expect(backgroundTasks.list).toHaveBeenLastCalledWith(expect.objectContaining({ type: 'METADATA', status: 'FAILED', q: 'meta', page: 0 }))
  wrapper!.getComponent(ElPagination).vm.$emit('current-change', 2); await flushPromises()
  expect(backgroundTasks.list).toHaveBeenLastCalledWith(expect.objectContaining({ page: 1 }))
  await button('历史记录').trigger('click'); await flushPromises()
  expect(backgroundTasks.list).toHaveBeenLastCalledWith(expect.objectContaining({ view: 'HISTORY', page: 0, status: '' }))
  const count = vi.mocked(backgroundTasks.list).mock.calls.length
  await tick(10000); expect(backgroundTasks.list).toHaveBeenCalledTimes(count)
})

it('可直接打开详情，待审核与失败单列，帧进度明确属于当前阶段，明细支持分页筛选', async () => {
  vi.mocked(backgroundTasks.detail).mockResolvedValue(sample({ type: 'AUDIO_BATCH', name: '音频批次',
    progress: { completed: 2, total: 3, percent: 66.66, unit: 'SONGS' }, stage: 'PROCESSING',
    execution: { stage: 'separate', progress: { frames: 200, completedFrames: 50 } },
    statistics: { review: 1, failed: 1, applied: 0 }, detailMode: 'ITEMS', itemsAvailable: true, relatedAvailable: false,
  }))
  vi.mocked(backgroundTasks.items).mockResolvedValue({ ...emptyItems, totalElements: 25, counts: { REVIEW: 24, FAILED: 1 },
    items: [{ id: 'i', songId: 'song', title: '待审核歌曲', status: 'REVIEW', message: '', execution: {} }] })
  await open('/system/tasks/task-one?view=HISTORY')
  expect(backgroundTasks.detail).toHaveBeenCalledWith('task-one')
  expect(wrapper!.text()).toContain('当前阶段 25%')
  expect(wrapper!.text()).toContain('处理完成不代表已全部保存')
  expect(wrapper!.text()).toContain('部分时间未记录')
  expect(wrapper!.text()).toContain('关联对象或原业务任务已移除或过期')
  expect(wrapper!.text()).toContain('待审核歌曲')
  expect(wrapper!.text()).not.toContain('等待更新')
  wrapper!.getComponent(ElPagination).vm.$emit('current-change', 2); await flushPromises()
  expect(backgroundTasks.items).toHaveBeenLastCalledWith('task-one', 1, '')
  wrapper!.getComponent(ElSelect).vm.$emit('change', 'FAILED'); await flushPromises()
  expect(backgroundTasks.items).toHaveBeenLastCalledWith('task-one', 0, 'FAILED')
  await button('返回任务列表').trigger('click'); await flushPromises()
  expect(router.currentRoute.value.fullPath).toBe('/system/tasks?view=HISTORY')
})

it('请求失败保留上次结果并延后五秒重试，隐藏停轮询，恢复立即读取，卸载停止', async () => {
  await open()
  vi.mocked(backgroundTasks.list).mockRejectedValueOnce(new Error('暂时不可读取'))
  await tick(2000)
  expect(wrapper!.text()).toContain('仍显示上次成功读取的结果')
  expect(wrapper!.text()).toContain('家庭曲库')
  await tick(4999); expect(backgroundTasks.list).toHaveBeenCalledTimes(2)
  await tick(1); expect(backgroundTasks.list).toHaveBeenCalledTimes(3)
  const visibility = vi.spyOn(document, 'visibilityState', 'get')
  visibility.mockReturnValue('hidden'); document.dispatchEvent(new Event('visibilitychange'))
  await tick(10000); expect(backgroundTasks.list).toHaveBeenCalledTimes(3)
  visibility.mockReturnValue('visible'); document.dispatchEvent(new Event('visibilitychange')); await flushPromises()
  expect(backgroundTasks.list).toHaveBeenCalledTimes(4)
  wrapper!.unmount(); wrapper = undefined
  await tick(10000); expect(backgroundTasks.list).toHaveBeenCalledTimes(4)
})

it('页面切换后忽略迟到响应，结束的详情停止轮询', async () => {
  let resolve!: (result: TaskPage) => void
  vi.mocked(backgroundTasks.list).mockImplementationOnce(() => new Promise(done => { resolve = done }))
  vi.mocked(backgroundTasks.detail).mockResolvedValue(sample({ name: '已结束任务', status: 'COMPLETED', finishedAt: '2026-11-08T02:00:00Z' }))
  await open()
  await router.push('/system/tasks/task-one'); await flushPromises()
  resolve(page([sample({ name: '过期列表响应' })])); await flushPromises()
  expect(wrapper!.text()).toContain('已结束任务')
  expect(wrapper!.text()).not.toContain('过期列表响应')
  await tick(10000); expect(backgroundTasks.detail).toHaveBeenCalledTimes(1)
})

it('歌手封面任务展示真实位数和中文阶段，未找到匹配与待确认分别呈现', async () => {
  vi.mocked(backgroundTasks.detail).mockResolvedValue(sample({
    type: 'ARTIST_ARTWORK', name: '歌手封面获取', stage: 'FETCHING_ARTWORK',
    progress: { completed: 2, total: 3, percent: 66.66, unit: 'ARTISTS' },
    statistics: { noMatch: 1, review: 1, applied: 0 }, detailMode: 'ITEMS', itemsAvailable: true,
    relatedPath: '/library/artists?batch=artwork-batch',
  }))
  vi.mocked(backgroundTasks.items).mockResolvedValue({ ...emptyItems, totalElements: 1,
    items: [{ id: 'artist-result', songId: null, title: '歌手甲', status: 'NO_MATCH', message: '没有可靠匹配', execution: {} }],
  })
  await open('/system/tasks/task-one')
  expect(wrapper!.text()).toContain('获取歌手封面')
  expect(wrapper!.text()).toContain('已处理 2 / 3 位')
  expect(wrapper!.text()).toContain('未找到匹配')
  expect(wrapper!.text()).toContain('待审核')
  expect(wrapper!.text()).not.toContain('FETCHING_ARTWORK')
})

it('切换到另一个详情与页面隐藏后，旧请求均不能覆盖新结果', async () => {
  let resolve!: (result: BackgroundTask) => void
  vi.mocked(backgroundTasks.detail).mockImplementationOnce(() => new Promise(done => { resolve = done }))
  await open('/system/tasks/task-one')
  vi.mocked(backgroundTasks.detail).mockResolvedValue(sample({ id: 'task-two', name: '第二个任务', status: 'PAUSED' }))
  await router.push('/system/tasks/task-two'); await flushPromises()
  resolve(sample({ name: '迟到的第一个任务' })); await flushPromises()
  expect(wrapper!.text()).toContain('第二个任务')
  expect(wrapper!.text()).not.toContain('迟到的第一个任务')
  await tick(2000); expect(backgroundTasks.detail).toHaveBeenLastCalledWith('task-two')
})

it('分离结束进入编码阶段后不沿用分离阶段的帧进度', () => {
  expect(frameProgress({ stage: 'separate', progress: { frames: 100, completedFrames: 25 } })).toContain('当前阶段 25%')
  expect(frameProgress({ stage: 'encode', progress: { frames: 100, completedFrames: 100 } })).toBe('')
})

it('批次直接展示子任务，筛选分页与返回批次、列表的位置均恢复', async () => {
  const root = sample({ id: 'root', name: '扫描批次', childrenCounts: { completed: 1, queued: 2, running: 1, failed: 0 } })
  const child = sample({ id: 'child', name: '歌曲探测任务', isBatch: false, batches: [{ id: 'root', name: '扫描批次' }], type: 'AUDIO_JOB' })
  vi.mocked(backgroundTasks.list).mockResolvedValue(page([root], 80))
  vi.mocked(backgroundTasks.detail).mockImplementation(async id => id === 'root' ? root : child)
  vi.mocked(backgroundTasks.children).mockImplementation(async id => ({ ...page([id === 'root' ? child : { ...child, id }], id === 'root' ? 25 : 1), counts: { RUNNING: 1, QUEUED: 24 } }))
  await open('/system/tasks/root?view=ALL&type=LOCAL_SCAN&q=scan&page=2&itemPage=1&itemStatus=QUEUED&itemType=AUDIO_JOB')
  expect(backgroundTasks.children).toHaveBeenLastCalledWith('root', 1, 'QUEUED', 'AUDIO_JOB')
  expect(backgroundTasks.items).not.toHaveBeenCalled()
  expect(wrapper!.text()).toContain('完成 1')
  expect(wrapper!.text()).toContain('歌曲探测任务')
  await button('歌曲探测任务').trigger('click'); await flushPromises()
  expect(router.currentRoute.value.params.id).toBe('child')
  expect(backgroundTasks.children).toHaveBeenLastCalledWith('child', 0, '', '')
  await button('返回批次').trigger('click'); await flushPromises()
  expect(router.currentRoute.value.params.id).toBe('root')
  expect(backgroundTasks.children).toHaveBeenLastCalledWith('root', 1, 'QUEUED', 'AUDIO_JOB')
  await wrapper!.findAllComponents(ElSelect)[1]!.vm.$emit('change', 'RUNNING'); await flushPromises()
  expect(backgroundTasks.children).toHaveBeenLastCalledWith('root', 0, 'RUNNING', 'AUDIO_JOB')
  await button('返回任务列表').trigger('click'); await flushPromises()
  expect(router.currentRoute.value.query).toEqual({ view: 'ALL', type: 'LOCAL_SCAN', q: 'scan', page: '2' })
})

it('扫描完成与整批等待分别显示，后续任务继续轮询', async () => {
  vi.mocked(backgroundTasks.detail).mockResolvedValue(sample({ status: 'QUEUED', stage: 'WAITING_RECOGNITION',
    operation: { ...sample().operation, status: 'COMPLETED', stage: 'COMPLETED' }, childrenCounts: { pendingDiscovery: 2, queued: 2 } }))
  await open('/system/tasks/task-one')
  expect(wrapper!.text()).toContain('扫描自身：处理完成')
  expect(wrapper!.text()).toContain('已发现 4 首歌曲')
  expect(wrapper!.text()).toContain('2 首等待后续阶段判断或创建')
  expect(wrapper!.text()).toContain('等待后续音轨处理')
  expect(backgroundTasks.items).not.toHaveBeenCalled()
  await tick(2000)
  expect(backgroundTasks.children).toHaveBeenCalledTimes(2)
})

it('迟到的子任务响应不会覆盖新批次，明细失败保留结果并五秒重试', async () => {
  let resolve!: (result: Awaited<ReturnType<typeof backgroundTasks.children>>) => void
  vi.mocked(backgroundTasks.children).mockImplementationOnce(() => new Promise(done => { resolve = done }))
  await open('/system/tasks/first')
  vi.mocked(backgroundTasks.detail).mockResolvedValue(sample({ id: 'second', name: '第二批次' }))
  const children = { ...page([sample({ id: 'new-child', name: '第二批次任务' })]), counts: { RUNNING: 1 } }
  vi.mocked(backgroundTasks.children).mockResolvedValue(children)
  await router.push('/system/tasks/second'); await flushPromises()
  resolve({ ...page([sample({ name: '过期子任务' })]), counts: {} }); await flushPromises()
  expect(wrapper!.text()).toContain('第二批次任务')
  expect(wrapper!.text()).not.toContain('过期子任务')
  vi.mocked(backgroundTasks.children).mockRejectedValueOnce(new Error('子任务读取失败'))
  await tick(2000)
  expect(wrapper!.text()).toContain('仍显示上次成功读取的结果')
  expect(wrapper!.text()).toContain('第二批次任务')
  const count = vi.mocked(backgroundTasks.children).mock.calls.length
  await tick(4999); expect(backgroundTasks.children).toHaveBeenCalledTimes(count)
  await tick(1); expect(backgroundTasks.children).toHaveBeenCalledTimes(count + 1)
})
