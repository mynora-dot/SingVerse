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
