// @vitest-environment jsdom
import { afterEach, expect, it, vi } from 'vitest'
import { computeNode } from '../src/admin/modules/compute-settings/api'

afterEach(() => vi.unstubAllGlobals())
it('扫描和配对均使用同源Java接口及CSRF，配对只提交发现ID与配置版本', async () => {
  const fetch = vi.fn().mockImplementation(async (path: string) => Response.json(path.endsWith('/csrf') ? { token: 'discovery-csrf' } : {}))
  vi.stubGlobal('fetch', fetch)
  await computeNode.discover()
  expect(fetch.mock.calls[1]![0]).toBe('/api/v1/compute-node/discovery')
  expect(fetch.mock.calls[1]![1]).toEqual(expect.objectContaining({ method: 'POST', credentials: 'same-origin', cache: 'no-store', headers: expect.objectContaining({ 'X-CSRF-Token': 'discovery-csrf' }), body: '{}' }))
  await computeNode.discover({ network: '192.168.1.0/24', port: 28090 })
  expect(fetch.mock.calls[3]![1].body).toBe(JSON.stringify({ network: '192.168.1.0/24', port: 28090 }))
  await computeNode.pair('opaque-discovery-ticket', 7)
  expect(fetch.mock.calls[5]![0]).toBe('/api/v1/compute-node/pair')
  expect(fetch.mock.calls[5]![1]).toEqual(expect.objectContaining({ method: 'POST', credentials: 'same-origin', headers: expect.objectContaining({ 'X-CSRF-Token': 'discovery-csrf' }), body: JSON.stringify({ discoveryId: 'opaque-discovery-ticket', revision: 7 }) }))
})
