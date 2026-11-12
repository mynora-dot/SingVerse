// @vitest-environment jsdom
import { flushPromises, mount } from '@vue/test-utils'
import { afterEach, expect, it, vi } from 'vitest'
import { createMemoryHistory, createRouter } from 'vue-router'
import LoginView from '../src/admin/modules/auth/views/LoginView.vue'
import { auth } from '../src/admin/core/auth/api'

afterEach(() => { vi.unstubAllGlobals(); auth.account = null })

it('登录两阶段保持验证边界，动态码失败后可重试并返回原目标', async () => {
  auth.account = null
  let verificationAttempts = 0
  const account = { username: 'family', defaultPassword: false, mfaEnabled: true, recoveryCodesRemaining: 8 }
  const fetcher = vi.fn(async (path: string) => {
    if (path.endsWith('/csrf')) return Response.json({ token: 'csrf-example' })
    if (path.endsWith('/login')) return Response.json({ state: 'mfaRequired' })
    if (path.endsWith('/mfa/verify')) {
      verificationAttempts += 1
      return verificationAttempts === 1
        ? Response.json({ detail: '动态码不正确' }, { status: 401 })
        : Response.json({ state: 'authenticated' })
    }
    if (path.endsWith('/session')) return Response.json(account)
    return Response.json({ detail: '未定义的请求' }, { status: 404 })
  })
  vi.stubGlobal('fetch', fetcher)
  const router = createRouter({ history: createMemoryHistory(), routes: [
    { path: '/login', component: LoginView },
    { path: '/library/songs', component: { template: '<main class="login-target">歌曲页</main>' } },
  ] })
  const target = '/library/songs?lens=formal&sourceId=family-library'
  await router.push({ path: '/login', query: { redirect: target } })
  const wrapper = mount({ template: '<RouterView />' }, { global: { plugins: [router] } })
  const sessionCalls = () => fetcher.mock.calls.filter(([path]) => path.endsWith('/session')).length
  try {
    await wrapper.get('input[autocomplete="username"]').setValue('family')
    await wrapper.get('input[autocomplete="current-password"]').setValue('family-password')
    await wrapper.get('form').trigger('submit'); await flushPromises()
    expect(wrapper.get('h1').text()).toBe('二次验证')
    expect(wrapper.find('input[autocomplete="current-password"]').exists()).toBe(false)
    expect((wrapper.get('input[autocomplete="one-time-code"]').element as HTMLInputElement).value).toBe('')
    expect(router.currentRoute.value.path).toBe('/login')
    expect(auth.account).toBeNull()
    expect(sessionCalls()).toBe(0)

    await wrapper.get('input[autocomplete="one-time-code"]').setValue('111111')
    await wrapper.get('form').trigger('submit'); await flushPromises()
    expect(wrapper.get('[role="alert"]').text()).toContain('动态码不正确')
    expect(wrapper.get('h1').text()).toBe('二次验证')
    expect(router.currentRoute.value.path).toBe('/login')
    expect(auth.account).toBeNull()
    expect(sessionCalls()).toBe(0)

    await wrapper.get('input[autocomplete="one-time-code"]').setValue('222222')
    await wrapper.get('form').trigger('submit'); await flushPromises()
    expect(verificationAttempts).toBe(2)
    expect(sessionCalls()).toBe(1)
    expect(auth.account).toEqual(account)
    expect(router.currentRoute.value.fullPath).toBe(target)
    expect(wrapper.get('.login-target').text()).toBe('歌曲页')
    expect(wrapper.find('.login-card').exists()).toBe(false)
  } finally { wrapper.unmount() }
})
