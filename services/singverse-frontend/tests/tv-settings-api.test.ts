// @vitest-environment jsdom
import { afterEach, expect, it, vi } from 'vitest'
import { tvSettings } from '../src/admin/modules/tv-settings/api'
afterEach(() => vi.unstubAllGlobals())
it('HTML 413 响应显示代理限制原因，保持原始 multipart 边界和版本', async () => {
  const fetch = vi.fn().mockResolvedValueOnce(new Response(JSON.stringify({ token: 'test-csrf' }), { status: 200 })).mockResolvedValueOnce(new Response('<html>too large</html>', { status: 413 }))
  vi.stubGlobal('fetch', fetch)
  const file = new File(['image'], 'cover.png', { type: 'image/png' })
  await expect(tvSettings.upload(file, 7)).rejects.toThrow('图片被上传入口拒绝（413）')
  const [path, request] = fetch.mock.calls[1]!
  expect(path).toBe('/api/v1/admin/tv-settings/banners?revision=7')
  expect(request.headers).toEqual({ 'X-CSRF-Token': 'test-csrf' })
  expect(request.body.get('file')).toBe(file)
  expect(request.credentials).toBe('same-origin')
})
it('显示后端返回的具体错误原因', async () => {
  vi.stubGlobal('fetch', vi.fn().mockResolvedValueOnce(new Response(JSON.stringify({ token: 'test-csrf' }))).mockResolvedValueOnce(new Response(JSON.stringify({ detail: '图片内容无法识别' }), { status: 400 })))
  await expect(tvSettings.upload(new File(['bad'], 'bad.png'), 0)).rejects.toThrow('图片内容无法识别')
})
