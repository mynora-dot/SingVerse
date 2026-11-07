// @vitest-environment jsdom
import { afterEach, expect, it, vi } from 'vitest'
import { songCache } from '../src/admin/modules/song-cache/api'

afterEach(() => vi.unstubAllGlobals())
it('分页只读使用同源路径，缓存写操作均取得CSRF并携带管理员会话', async () => {
  const settings = { maxBytes: 1024 ** 3, policy: 'LRU' as const, ttlHours: 24, revision: 3 }
  const fetch = vi.fn().mockImplementation(async (path: string) => path.endsWith('/csrf')
    ? Response.json({ token: 'cache-csrf' }) : Response.json(settings))
  vi.stubGlobal('fetch', fetch)
  await songCache.list(2, 20)
  expect(fetch.mock.calls[0]![0]).toBe('/api/v1/transcoding/cache?page=2&size=20')
  expect(fetch.mock.calls[0]![1]).toEqual(expect.objectContaining({ credentials: 'same-origin', method: 'GET', cache: 'no-store' }))
  await songCache.save(settings)
  expect(fetch.mock.calls[1]![0]).toBe('/api/v1/auth/csrf')
  expect(fetch.mock.calls[2]![0]).toBe('/api/v1/transcoding/cache/settings')
  expect(fetch.mock.calls[2]![1]).toEqual(expect.objectContaining({ method: 'PUT', credentials: 'same-origin', headers: expect.objectContaining({ 'X-CSRF-Token': 'cache-csrf' }), body: JSON.stringify(settings) }))
  await songCache.remove('cache-id')
  expect(fetch.mock.calls[4]![0]).toBe('/api/v1/transcoding/cache/cache-id')
  expect(fetch.mock.calls[4]![1]).toEqual(expect.objectContaining({ method: 'DELETE', headers: expect.objectContaining({ 'X-CSRF-Token': 'cache-csrf' }) }))
  await songCache.clear()
  expect(fetch.mock.calls[6]![0]).toBe('/api/v1/transcoding/cache/clear')
  expect(fetch.mock.calls[6]![1]).toEqual(expect.objectContaining({ method: 'POST', headers: expect.objectContaining({ 'X-CSRF-Token': 'cache-csrf' }) }))
})

it('固定与后台任务使用同源受权接口，保留旧删除的近期确认选项', async () => {
  const fetch = vi.fn().mockImplementation(async (path: string) => path.endsWith('/csrf') ? Response.json({ token: 'csrf' }) : Response.json({}))
  vi.stubGlobal('fetch', fetch)
  await songCache.pins(1, 20)
  await songCache.pin('song /', true, 4)
  await songCache.cleanup({ mode: 'DELETE', entryId: 'entry', confirmRecent: true })
  await songCache.job('job /')
  await songCache.cancelJob('job /')
  await songCache.remove('entry', true)
  const calls = fetch.mock.calls.filter(call => !String(call[0]).endsWith('/csrf'))
  expect(calls.map(call => call[0])).toEqual(['/api/v1/transcoding/cache/pins?page=1&size=20', '/api/v1/transcoding/cache/songs/song%20%2F/pin', '/api/v1/transcoding/cache/cleanup-jobs', '/api/v1/transcoding/cache/cleanup-jobs/job%20%2F', '/api/v1/transcoding/cache/cleanup-jobs/job%20%2F/cancel', '/api/v1/transcoding/cache/entry?confirmRecent=true'])
  expect(calls[1]![1]).toMatchObject({ method: 'PUT', body: JSON.stringify({ pinned: true, revision: 4 }), headers: expect.objectContaining({ 'X-CSRF-Token': 'csrf' }), credentials: 'same-origin' })
  expect(calls[2]![1]).toMatchObject({ method: 'POST', body: JSON.stringify({ mode: 'DELETE', entryId: 'entry', confirmRecent: true }) })
})
