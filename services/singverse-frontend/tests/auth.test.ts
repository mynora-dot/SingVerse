// @vitest-environment jsdom
import { afterEach, expect, it, vi } from 'vitest'
import { auth, authRequest, refreshAccount, ApiError } from '../src/admin/core/auth/api'
import { router } from '../src/admin/app/router/index'

afterEach(() => { vi.unstubAllGlobals(); auth.account = null })
it('未登录访问管理路由会跳转登录并保留目标', async () => {
  vi.stubGlobal('fetch', vi.fn().mockImplementation(() => Promise.resolve(Response.json({ detail: '请先登录' }, { status: 401 }))))
  await router.push('/system/parameters')
  expect(router.currentRoute.value.path).toBe('/login')
  expect(router.currentRoute.value.query.redirect).toBe('/system/parameters')
})
it('二次验证未完成不能通过会话查询获得权限', async () => {
  vi.stubGlobal('fetch', vi.fn().mockImplementation(() => Promise.resolve(Response.json({}, { status: 401 }))))
  expect(await refreshAccount()).toBeNull()
  expect(auth.account).toBeNull()
})
it('写操作先获取 CSRF 并发送安全 Cookie 请求配置', async () => {
  const fetcher = vi.fn().mockImplementation((path: string) => Promise.resolve(Response.json(path.endsWith('/csrf') ? { token: 'csrf-example' } : { state: 'mfaRequired' })))
  vi.stubGlobal('fetch', fetcher)
  expect(await authRequest('/api/v1/auth/login', 'POST', { username: 'admin', password: 'test' })).toEqual({ state: 'mfaRequired' })
  expect(fetcher.mock.calls[1]?.[1]).toMatchObject({ credentials: 'same-origin', cache: 'no-store', headers: { 'X-CSRF-Token': 'csrf-example' } })
  expect(auth.account).toBeNull()
})
it('接口限定同源命名空间', async () => {
  await expect(authRequest('https://evil.example/api/v1/auth/login')).rejects.toThrow('同源')
  await expect(authRequest('/system/parameters')).rejects.toThrow('同源')
})
it('显示限流返回的信息', async () => {
  vi.stubGlobal('fetch', vi.fn().mockImplementation(() => Promise.resolve(Response.json({ detail: '尝试过于频繁，请稍后重试' }, { status: 429 }))))
  await expect(authRequest('/api/v1/auth/csrf')).rejects.toBeInstanceOf(ApiError)
  await expect(authRequest('/api/v1/auth/csrf')).rejects.toThrow('尝试过于频繁')
})

it('会话过期与密码校验失败分别处理', async () => {
  const notify = vi.spyOn(window, 'dispatchEvent')
  vi.stubGlobal('fetch', vi.fn().mockImplementation((path: string) => Promise.resolve(Response.json(path.endsWith('/csrf') ? { token: 'csrf' } : { type: 'urn:singverse:auth:session-required', detail: '请先登录' }, { status: path.endsWith('/csrf') ? 200 : 401 }))))
  await expect(authRequest('/api/v1/auth/password', 'POST', { password: 'old', newPassword: 'long new password' })).rejects.toThrow('请先登录')
  expect(notify).toHaveBeenCalledWith(expect.objectContaining({ type: 'singverse:session-expired' }))
  notify.mockRestore()
})

it('规则错误的定位字段通过 HTTP 客户端保留', async () => {
  vi.stubGlobal('fetch', vi.fn().mockResolvedValue(Response.json({ detail: '表达式无效', ruleId: 'bad', ruleIndex: 2, field: 'expression' }, { status: 400 })))
  try {
    await authRequest('/api/v1/filename-parser/rules')
    throw new Error('预期接口返回参数错误')
  } catch (error) {
    expect(error).toBeInstanceOf(ApiError)
    expect((error as ApiError).details).toMatchObject({ ruleId: 'bad', ruleIndex: 2, field: 'expression' })
  }
})
