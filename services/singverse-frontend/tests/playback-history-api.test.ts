// @vitest-environment jsdom
import { afterEach, expect, it, vi } from 'vitest'
import { playbackHistory } from '../src/admin/modules/playback-history/api'

afterEach(() => vi.unstubAllGlobals())
it('播放管理查询使用同源管理员会话并编码筛选条件', async () => {
  const fetch = vi.fn().mockImplementation(async () => Response.json({ items: [], page: 0, size: 20, totalElements: 0 }))
  vi.stubGlobal('fetch', fetch)
  await playbackHistory.list({ q: '歌名 & 歌手', period: '7d', songId: 'song/1', page: 2, size: 20 })
  const url = new URL(fetch.mock.calls[0]![0], 'https://example.test')
  expect(url.pathname).toBe('/api/v1/playback-history')
  expect(Object.fromEntries(url.searchParams)).toEqual({ q: '歌名 & 歌手', period: '7d', songId: 'song/1', page: '2', size: '20' })
  expect(fetch.mock.calls[0]![1]).toMatchObject({ credentials: 'same-origin', method: 'GET', cache: 'no-store' })
  await playbackHistory.popular({ period: '30d', page: 0, size: 20 })
  expect(fetch.mock.calls[1]![0]).toBe('/api/v1/playback-history/popular?period=30d&page=0&size=20')
  await playbackHistory.summary({ period: 'all', q: '' })
  expect(fetch.mock.calls[2]![0]).toBe('/api/v1/playback-history/summary?period=all')
  expect(fetch).toHaveBeenCalledTimes(3)
})
