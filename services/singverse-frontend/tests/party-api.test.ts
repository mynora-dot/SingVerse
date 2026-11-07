import { afterEach, expect, it, vi } from 'vitest'
import { request } from '../src/party/api'

afterEach(() => { vi.unstubAllGlobals(); vi.useRealTimers() })

it('响应头已到达时仍限制响应体读取时间', async () => {
  vi.useFakeTimers()
  let requestSignal!: AbortSignal
  vi.stubGlobal('fetch', vi.fn(async (_url: string, options: RequestInit) => {
    requestSignal = options.signal as AbortSignal
    return { ok: true, json: () => new Promise((_resolve, reject) => requestSignal.addEventListener('abort', () => reject(new DOMException('Aborted', 'AbortError')), { once: true })) }
  }))
  const pending = request('library/songs')
  const rejected = expect(pending).rejects.toMatchObject({ name: 'AbortError' })
  await vi.advanceTimersByTimeAsync(10000)
  await rejected
  expect(requestSignal.aborted).toBe(true)
  expect(vi.getTimerCount()).toBe(0)
})

it('调用方取消传入HTTP请求，成功后移除监听及定时器', async () => {
  vi.useFakeTimers()
  const controller = new AbortController(), remove = vi.spyOn(controller.signal, 'removeEventListener')
  let requestSignal!: AbortSignal
  const fetch = vi.fn(async (_url: string, options: RequestInit) => {
    requestSignal = options.signal as AbortSignal
    return { ok: true, json: async () => ({ revision: 1 }) }
  })
  vi.stubGlobal('fetch', fetch)
  await expect(request('sessions/party/snapshot', 'GET', undefined, 'participant-token', controller.signal)).resolves.toEqual({ revision: 1 })
  expect(fetch).toHaveBeenCalledWith('/api/v1/party/sessions/party/snapshot', expect.objectContaining({ credentials: 'omit', redirect: 'error', headers: { Accept: 'application/json', Authorization: 'Bearer participant-token' } }))
  expect(remove).toHaveBeenCalledWith('abort', expect.any(Function))
  expect(vi.getTimerCount()).toBe(0)
  controller.abort()
  expect(requestSignal.aborted).toBe(false)

  const cancelled = new AbortController(); cancelled.abort()
  await request('library/songs', 'GET', undefined, undefined, cancelled.signal)
  expect(requestSignal.aborted).toBe(true)
})
