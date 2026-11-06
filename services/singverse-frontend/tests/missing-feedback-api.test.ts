// @vitest-environment jsdom
import { afterEach, expect, it, vi } from 'vitest'
import { missingFeedback } from '../src/admin/modules/missing-feedback/api'

afterEach(() => vi.unstubAllGlobals())
it('管理员查询使用同源参数，登记和处理都携带CSRF及稳定载荷', async () => {
  const fetch = vi.fn().mockImplementation(async (path: string) => path.endsWith('/csrf')
    ? Response.json({ token: 'feedback-csrf' }) : Response.json({ items: [], totalElements: 0 }))
  vi.stubGlobal('fetch', fetch)
  await missingFeedback.list('歌名 & 歌手', 'PROCESSING', 2)
  const url = new URL(fetch.mock.calls[0]![0] as string, 'http://localhost')
  expect(url.pathname).toBe('/api/v1/missing-song-feedback')
  expect(url.searchParams.get('q')).toBe('歌名 & 歌手')
  expect(url.searchParams.get('status')).toBe('PROCESSING')
  expect(url.searchParams.get('page')).toBe('2')
  expect(fetch.mock.calls[0]![1]).toEqual(expect.objectContaining({ method: 'GET', credentials: 'same-origin', cache: 'no-store' }))
  const input = { requestId: 'same-request', title: '后来', artist: '刘若英', versionLabel: '', description: '', submittedBy: '' }
  await missingFeedback.create(input)
  expect(fetch.mock.calls[1]![0]).toBe('/api/v1/auth/csrf')
  expect(fetch.mock.calls[2]![0]).toBe('/api/v1/missing-song-feedback')
  expect(fetch.mock.calls[2]![1]).toEqual(expect.objectContaining({ method: 'POST', credentials: 'same-origin', headers: expect.objectContaining({ 'X-CSRF-Token': 'feedback-csrf' }), body: JSON.stringify(input) }))
  const handling = { revision: 4, status: 'RESOLVED' as const, handlingNote: '已扫描', resolvedSongId: 'song-1' }
  await missingFeedback.update('feedback/one', handling)
  expect(fetch.mock.calls[4]![0]).toBe('/api/v1/missing-song-feedback/feedback%2Fone')
  expect(fetch.mock.calls[4]![1]).toEqual(expect.objectContaining({ method: 'PUT', headers: expect.objectContaining({ 'X-CSRF-Token': 'feedback-csrf' }), body: JSON.stringify(handling) }))
})
