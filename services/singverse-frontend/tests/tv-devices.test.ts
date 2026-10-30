// @vitest-environment jsdom
import { flushPromises, mount } from '@vue/test-utils'
import { beforeEach, expect, it, vi } from 'vitest'
import TvDevices from '../src/admin/modules/tv-devices/TvDevicesView.vue'
import { authRequest } from '../src/admin/core/auth/api'

vi.mock('../src/admin/core/auth/api')
const pending = [
  { id: 'first', name: '客厅电视', expiresAt: '2026-10-30T04:00:00+08:00' },
  { id: 'second', name: '客厅电视', expiresAt: '2026-10-30T04:00:00+08:00' },
]
beforeEach(() => {
  vi.resetAllMocks()
  vi.mocked(authRequest).mockResolvedValue({ devices: [], pending })
})

it('无需全局注册即可渲染实际输入框；各申请独立输入并保留前导零，回车提交对应授权码', async () => {
  const wrapper = mount(TvDevices)
  try {
    await flushPromises()
    expect(wrapper.find('el-input').exists()).toBe(false)
    const forms = wrapper.findAll('form.authorization-form')
    expect(forms).toHaveLength(2)
    const first = forms[0]!, second = forms[1]!
    expect(first.get('label').attributes('for')).toBe(first.get('input').attributes('id'))
    expect(first.get('input').attributes('inputmode')).toBe('numeric')
    expect(first.get('button[type="submit"]').attributes('disabled')).toBeDefined()
    await first.get('input').setValue('012345')
    expect(first.get('button[type="submit"]').attributes('disabled')).toBeUndefined()
    expect((second.get('input').element as HTMLInputElement).value).toBe('')
    await first.trigger('submit'); await flushPromises()
    expect(authRequest).toHaveBeenCalledWith('/api/v1/admin/device-authorizations/first/approve', 'POST', { code: '012345' })
    expect((first.get('input').element as HTMLInputElement).value).toBe('')
  } finally { wrapper.unmount() }
})

it('非六位数字不批准；服务端拒绝时保留已填授权码供修改', async () => {
  const wrapper = mount(TvDevices)
  try {
    await flushPromises()
    const form = wrapper.get('form.authorization-form')
    for (const value of ['12345', '12ab56']) {
      await form.get('input').setValue(value)
      expect(form.get('button[type="submit"]').attributes('disabled')).toBeDefined()
      await form.trigger('submit'); await flushPromises()
    }
    expect(authRequest).toHaveBeenCalledTimes(1)
    await form.get('input').setValue('012345')
    vi.mocked(authRequest).mockRejectedValueOnce(new Error('授权码不匹配'))
    await form.trigger('submit'); await flushPromises()
    expect(wrapper.text()).toContain('授权码不匹配')
    expect((form.get('input').element as HTMLInputElement).value).toBe('012345')
    expect(form.get('button[type="submit"]').attributes('disabled')).toBeUndefined()
  } finally { wrapper.unmount() }
})
