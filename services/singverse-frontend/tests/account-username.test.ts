// @vitest-environment jsdom
import { afterEach, expect, it, vi } from 'vitest'
import { flushPromises, mount, type VueWrapper } from '@vue/test-utils'
import { createMemoryHistory, createRouter } from 'vue-router'
import ParametersView from '../src/admin/modules/system-parameters/views/ParametersView.vue'
import LoginView from '../src/admin/modules/auth/views/LoginView.vue'
import { auth } from '../src/admin/core/auth/api'
let wrapper: VueWrapper | undefined
afterEach(() => { wrapper?.unmount(); vi.unstubAllGlobals(); auth.account = null; document.body.innerHTML = '' })
async function page(component: typeof ParametersView | typeof LoginView) {
  const router = createRouter({ history: createMemoryHistory(), routes: [{ path: '/', component }, { path: '/login', component: { template: '<div>登录</div>' } }, { path: '/overview', component: { template: '<div>概览</div>' } }] })
  await router.push('/')
  wrapper = mount(component, { attachTo: document.body, global: { plugins: [router] } })
  await flushPromises()
  return router
}
it('展示当前账号并提交改名验证凭据，成功后跳转登录', async () => {
  const fetcher = vi.fn().mockImplementation((path: string) => Promise.resolve(path.endsWith('/username') ? new Response(null) : Response.json(path.endsWith('/csrf') ? { token: 'csrf' } : { username: 'family', mfaEnabled: true, defaultPassword: false, recoveryCodesRemaining: 10 })))
  vi.stubGlobal('fetch', fetcher)
  const router = await page(ParametersView)
  expect(wrapper!.text()).toContain('family')
  await wrapper!.findAll('button').find(b => b.text() === '修改登录账号')!.trigger('click')
  await flushPromises()
  const dialog = document.querySelector('.el-dialog')!
  const inputs = dialog.querySelectorAll('input')
  for (const [index, value] of ['current-password', '123456', 'family-new'].entries()) {
    const input = inputs[index]!; input.value = value; input.dispatchEvent(new Event('input', { bubbles: true }))
  }
  dialog.querySelector('form')!.dispatchEvent(new Event('submit', { bubbles: true, cancelable: true }))
  await flushPromises()
  const call = fetcher.mock.calls.find(c => c[0].endsWith('/username'))!
  expect(JSON.parse(call[1].body)).toMatchObject({ password: 'current-password', code: '123456', newUsername: 'family-new' })
  expect(auth.account).toBeNull()
  expect(router.currentRoute.value.path).toBe('/login')
})
it('登录提交用户输入的新账号', async () => {
  const fetcher = vi.fn().mockImplementation((path: string) => Promise.resolve(Response.json(path.endsWith('/csrf') ? { token: 'csrf' } : path.endsWith('/login') ? { state: 'authenticated' } : { username: 'family-new', mfaEnabled: false, defaultPassword: false, recoveryCodesRemaining: 0 })))
  vi.stubGlobal('fetch', fetcher)
  await page(LoginView)
  const inputs = wrapper!.findAll('input')
  await inputs[0]!.setValue('family-new'); await inputs[1]!.setValue('current-password')
  await wrapper!.get('form').trigger('submit'); await flushPromises()
  const call = fetcher.mock.calls.find(c => c[0].endsWith('/login'))!
  expect(JSON.parse(call[1].body)).toEqual({ username: 'family-new', password: 'current-password' })
})
