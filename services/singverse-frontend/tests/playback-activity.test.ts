// @vitest-environment jsdom
import { flushPromises } from '@vue/test-utils'
import { afterEach, beforeEach, expect, it, vi } from 'vitest'
import { AdminPlaybackActivity, adminActivity, type PlaybackAttempt } from '../src/admin/modules/transcoding/activity'
import { ApiError } from '../src/admin/core/auth/api'

const epoch = Date.parse('2026-11-07T00:00:00Z')
const created: PlaybackAttempt = { attemptId: 'server-attempt', writeToken: 'server-issued-token', eventSeq: -1, leaseSeconds: 120, startedAt: epoch, serverTime: epoch }
let activity: AdminPlaybackActivity | undefined
beforeEach(() => { vi.useFakeTimers(); vi.setSystemTime(epoch); vi.spyOn(adminActivity, 'create').mockResolvedValue(created); vi.spyOn(adminActivity, 'event').mockResolvedValue(undefined) })
afterEach(async () => { activity?.transition('STOPPED'); await flushPromises(); activity = undefined; vi.restoreAllMocks(); vi.useRealTimers(); vi.unstubAllGlobals() })

it('只从实际PLAYING开始，使用墙钟累计，暂停、缓冲和跳转不增加实播', async () => {
  let now = 1000
  activity = new AdminPlaybackActivity('song', () => now)
  activity.transition('PREPARING'); activity.transition('BUFFERING')
  expect(adminActivity.create).not.toHaveBeenCalled()
  activity.transition('PLAYING', 200_000); await flushPromises()
  expect(adminActivity.create).toHaveBeenCalledOnce()
  expect(adminActivity.create).toHaveBeenCalledWith('song', epoch)
  expect(adminActivity.event).toHaveBeenLastCalledWith('server-attempt', expect.objectContaining({ state: 'PLAYING', actualPlayedMs: 0, elapsedMs: 0, occurredAt: epoch, lastActivityAt: epoch, eventSeq: 0, writeToken: created.writeToken, durationMs: 200_000 }))
  now += 3000; activity.transition('PAUSED'); await flushPromises()
  expect(adminActivity.event).toHaveBeenLastCalledWith('server-attempt', expect.objectContaining({ state: 'PAUSED', actualPlayedMs: 3000, elapsedMs: 3000 }))
  now += 60_000; activity.transition('BUFFERING'); await flushPromises()
  expect(adminActivity.event).toHaveBeenLastCalledWith('server-attempt', expect.objectContaining({ actualPlayedMs: 3000, elapsedMs: 63_000, occurredAt: epoch + 63_000, lastActivityAt: epoch + 3000 }))
  now += 1000; activity.transition('PLAYING'); await flushPromises()
  expect(adminActivity.event).toHaveBeenLastCalledWith('server-attempt', expect.objectContaining({ actualPlayedMs: 3000, lastActivityAt: epoch + 64_000 }))
  now += 5000; activity.transition('COMPLETED'); await flushPromises()
  expect(adminActivity.event).toHaveBeenLastCalledWith('server-attempt', expect.objectContaining({ state: 'COMPLETED', actualPlayedMs: 8000, elapsedMs: 69_000 }))
  activity.transition('PLAYING'); expect(adminActivity.create).toHaveBeenCalledOnce()
})

it('开始请求迟到时仍先上报PLAYING，再上报停止，不产生另一实例', async () => {
  let resolve!: (value: PlaybackAttempt) => void
  vi.mocked(adminActivity.create).mockReturnValue(new Promise(done => { resolve = done }))
  let now = 1000
  activity = new AdminPlaybackActivity('old-song', () => now)
  activity.transition('PLAYING'); now += 1200; activity.transition('STOPPED')
  resolve(created); await flushPromises()
  expect(adminActivity.event).toHaveBeenCalledTimes(2)
  expect(vi.mocked(adminActivity.event).mock.calls.map(call => call[1].state)).toEqual(['PLAYING', 'STOPPED'])
  expect(adminActivity.event).toHaveBeenLastCalledWith('server-attempt', expect.objectContaining({ eventSeq: 1, actualPlayedMs: 1200 }))
})

it('慢创建保留注册前的实播时长及最初UTC锚点', async () => {
  let resolve!: (value: PlaybackAttempt) => void
  vi.mocked(adminActivity.create).mockReturnValue(new Promise(done => { resolve = done }))
  let now = 1000
  activity = new AdminPlaybackActivity('song', () => now)
  activity.transition('PLAYING')
  now += 12_000; resolve(created); await flushPromises()
  expect(adminActivity.event).toHaveBeenCalledOnce()
  expect(adminActivity.event).toHaveBeenLastCalledWith('server-attempt', expect.objectContaining({ state: 'PLAYING', actualPlayedMs: 0, elapsedMs: 0, occurredAt: epoch, lastActivityAt: epoch }))
  now += 3000; activity.transition('PAUSED'); await flushPromises()
  expect(adminActivity.event).toHaveBeenLastCalledWith('server-attempt', expect.objectContaining({ state: 'PAUSED', actualPlayedMs: 15_000, elapsedMs: 15_000, occurredAt: epoch + 15_000, lastActivityAt: epoch + 15_000 }))
})

it('播放5秒心跳，暂停15秒只续租且不增加实际时长，停止后不再心跳', async () => {
  activity = new AdminPlaybackActivity('song')
  activity.transition('PLAYING'); await flushPromises()
  await vi.advanceTimersByTimeAsync(5000); await flushPromises()
  expect(adminActivity.event).toHaveBeenLastCalledWith('server-attempt', expect.objectContaining({ state: 'PLAYING', actualPlayedMs: 5000 }))
  activity.transition('PAUSED'); await flushPromises()
  const count = vi.mocked(adminActivity.event).mock.calls.length
  await vi.advanceTimersByTimeAsync(10_000); expect(adminActivity.event).toHaveBeenCalledTimes(count)
  await vi.advanceTimersByTimeAsync(5000); await flushPromises()
  expect(adminActivity.event).toHaveBeenLastCalledWith('server-attempt', expect.objectContaining({ state: 'PAUSED', actualPlayedMs: 5000, occurredAt: epoch + 20_000, lastActivityAt: epoch + 5000 }))
  activity.transition('STOPPED'); await flushPromises()
  const stopped = vi.mocked(adminActivity.event).mock.calls.length
  await vi.advanceTimersByTimeAsync(60_000); expect(adminActivity.event).toHaveBeenCalledTimes(stopped)
})

it('慢活动接口串行发送并合并中间快照，保留最终状态', async () => {
  let release!: () => void
  vi.mocked(adminActivity.event).mockReturnValueOnce(new Promise(done => { release = () => done(undefined) }))
  let now = 1000
  activity = new AdminPlaybackActivity('song', () => now)
  activity.transition('PLAYING'); await flushPromises()
  now += 100; activity.transition('BUFFERING'); now += 100; activity.transition('PAUSED'); now += 100; activity.transition('STOPPED')
  expect(adminActivity.event).toHaveBeenCalledOnce()
  vi.setSystemTime(epoch + 25 * 3600_000)
  release(); await flushPromises()
  expect(adminActivity.event).toHaveBeenCalledTimes(2)
  expect(adminActivity.event).toHaveBeenLastCalledWith('server-attempt', expect.objectContaining({ state: 'STOPPED', eventSeq: 1, actualPlayedMs: 100, elapsedMs: 300, occurredAt: epoch + 300, lastActivityAt: epoch + 100 }))
})

it('创建重试不改变原有样本时间和已累计时长，UTC变化不影响单调时间轴', async () => {
  vi.mocked(adminActivity.create).mockRejectedValueOnce(new Error('暂时无法连接'))
  let now = 1000
  activity = new AdminPlaybackActivity('song', () => now)
  activity.transition('PLAYING'); await flushPromises()
  vi.setSystemTime(epoch + 2 * 3600_000)
  now += 3000; activity.transition('PAUSED'); await flushPromises()
  expect(vi.mocked(adminActivity.create).mock.calls).toEqual([['song', epoch], ['song', epoch]])
  expect(adminActivity.event).toHaveBeenCalledTimes(2)
  expect(adminActivity.event).toHaveBeenNthCalledWith(1, 'server-attempt', expect.objectContaining({ occurredAt: epoch, lastActivityAt: epoch, actualPlayedMs: 0 }))
  expect(adminActivity.event).toHaveBeenLastCalledWith('server-attempt', expect.objectContaining({ occurredAt: epoch + 3000, lastActivityAt: epoch + 3000, actualPlayedMs: 3000, elapsedMs: 3000 }))
})

it('服务端租约到期后停止当前采集，不反复创建实例或继续发送心跳', async () => {
  vi.mocked(adminActivity.event).mockRejectedValueOnce(new ApiError(410, '播放实例已到期'))
  activity = new AdminPlaybackActivity('song')
  activity.transition('PLAYING'); await flushPromises()
  expect(activity.closed).toBe(true)
  await vi.advanceTimersByTimeAsync(180_000)
  activity.transition('PAUSED'); activity.transition('PLAYING'); await flushPromises()
  expect(adminActivity.create).toHaveBeenCalledOnce()
  expect(adminActivity.event).toHaveBeenCalledOnce()
  expect(vi.getTimerCount()).toBe(0)
})

it('管理员活动API复用Cookie和CSRF，只使用服务端签发实例与writeToken', async () => {
  vi.restoreAllMocks()
  const fetch = vi.fn().mockImplementation(async (path: string) => path.endsWith('/csrf') ? Response.json({ token: 'csrf' }) : Response.json(created))
  vi.stubGlobal('fetch', fetch)
  await adminActivity.create('song', epoch)
  await adminActivity.event(created.attemptId, { writeToken: created.writeToken, eventSeq: 0, state: 'PLAYING', actualPlayedMs: 0, elapsedMs: 0, occurredAt: epoch, lastActivityAt: epoch })
  expect(fetch.mock.calls[1]![0]).toBe('/api/v1/playback-attempts')
  expect(JSON.parse(fetch.mock.calls[1]![1].body)).toEqual({ songId: 'song', deviceName: '管理端在线播放', startedAt: epoch })
  expect(fetch.mock.calls[3]![0]).toBe('/api/v1/playback-attempts/server-attempt/events')
  expect(fetch.mock.calls[3]![1]).toMatchObject({ method: 'POST', credentials: 'same-origin', headers: expect.objectContaining({ 'X-CSRF-Token': 'csrf' }) })
})
