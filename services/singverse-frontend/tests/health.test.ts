import { afterEach, describe, expect, it, vi } from 'vitest'
import { getServiceHealth } from '../src/admin/modules/system-status/api/health'
import { getJson } from '../src/admin/shared/http/client'

afterEach(() => {
  vi.unstubAllGlobals()
  vi.useRealTimers()
})

function respond(body: unknown, status = 200) {
  const fetchMock = vi.fn().mockResolvedValue(Response.json(body, { status }))
  vi.stubGlobal('fetch', fetchMock)
  return fetchMock
}

describe('同源服务健康检查', () => {
  it('读取真实 Actuator 结构，忽略额外字段，不绑定服务端语言', async () => {
    const fetchMock = respond({ status: 'UP', additionalField: true })
    await expect(getServiceHealth()).resolves.toEqual({ status: 'UP' })
    expect(fetchMock).toHaveBeenCalledWith(
      '/api/health',
      expect.objectContaining({
        credentials: 'same-origin',
        redirect: 'error',
        cache: 'no-store',
      }),
    )
  })

  it.each(['DOWN', 'OUT_OF_SERVICE', 'UNKNOWN'])('不把 %s 误报为健康', async (status) => {
    respond({ status })
    await expect(getServiceHealth()).resolves.toEqual({ status })
  })

  it('保留失败 HTTP 状态，不将 503 当作成功', async () => {
    respond({ status: 'DOWN' }, 503)
    await expect(getServiceHealth()).rejects.toMatchObject({ kind: 'http', status: 503 })
  })

  it.each([null, {}, { status: true }, { status: 'SUCCESS' }, { status: ['UP'] }])(
    '拒绝非法健康响应 %j',
    async (data) => {
      respond(data)
      await expect(getServiceHealth()).rejects.toMatchObject({ kind: 'invalid-response' })
    },
  )

  it('拒绝代理返回的 HTML 登录页', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn().mockResolvedValue(
        new Response('<html>Login</html>', {
          headers: { 'Content-Type': 'text/html' },
        }),
      ),
    )
    await expect(getServiceHealth()).rejects.toMatchObject({ kind: 'invalid-response' })
  })

  it('拒绝标记为 JSON 的损坏响应', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn().mockResolvedValue(
        new Response('{', {
          headers: { 'Content-Type': 'application/json' },
        }),
      ),
    )
    await expect(getServiceHealth()).rejects.toMatchObject({ kind: 'invalid-response' })
  })

  it('报告断网，并且下一次调用可恢复', async () => {
    const fetchMock = vi
      .fn()
      .mockRejectedValueOnce(new TypeError('Failed to fetch'))
      .mockResolvedValueOnce(Response.json({ status: 'UP' }))
    vi.stubGlobal('fetch', fetchMock)
    await expect(getServiceHealth()).rejects.toMatchObject({ kind: 'network' })
    await expect(getServiceHealth()).resolves.toEqual({ status: 'UP' })
  })

  it('超时会取消请求，避免一直显示检查中', async () => {
    vi.useFakeTimers()
    vi.stubGlobal(
      'fetch',
      vi.fn(
        (_path, options: RequestInit) =>
          new Promise((_resolve, reject) => {
            options.signal?.addEventListener('abort', () =>
              reject(new DOMException('Aborted', 'AbortError')),
            )
          }),
      ),
    )
    const result = expect(getJson('/api/health', 20)).rejects.toMatchObject({
      kind: 'timeout',
    })
    await vi.advanceTimersByTimeAsync(20)
    await result
    expect(vi.getTimerCount()).toBe(0)
  })

  it('不允许通过 API 客户端请求其他服务器', async () => {
    const fetchMock = vi.fn()
    vi.stubGlobal('fetch', fetchMock)
    await expect(getJson('//other.example/health')).rejects.toThrow('同源')
    expect(fetchMock).not.toHaveBeenCalled()
  })

  it('不允许遗漏 /api 前缀', async () => {
    const fetchMock = vi.fn()
    vi.stubGlobal('fetch', fetchMock)
    await expect(getJson('/health')).rejects.toThrow('/api')
    expect(fetchMock).not.toHaveBeenCalled()
  })
})
