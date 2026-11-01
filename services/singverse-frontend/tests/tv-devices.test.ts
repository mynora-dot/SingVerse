// @vitest-environment jsdom
import { flushPromises, mount } from '@vue/test-utils'
import { beforeEach, expect, it, vi } from 'vitest'
import TvDevices from '../src/admin/modules/tv-devices/TvDevicesView.vue'
import { authRequest } from '../src/admin/core/auth/api'
import { confirmDanger } from '../src/admin/shared/ui/confirm'

vi.mock('../src/admin/core/auth/api')
vi.mock('../src/admin/shared/ui/confirm', () => ({ confirmDanger: vi.fn() }))
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

it('撤销访问使用危险确认；取消时不发请求，确认后撤销并刷新列表', async () => {
  vi.mocked(authRequest).mockResolvedValue({ devices: [{ id: 'tv1', name: '客厅电视', revoked: false }, { id: 'tv0', name: '旧电视', revoked: true }], pending: [] })
  const wrapper = mount(TvDevices)
  try {
    await flushPromises()
    const rows = wrapper.findAll('article.device-row')
    expect(rows[0]!.text()).toContain('已授权')
    expect(rows[1]!.text()).toContain('已撤销')
    expect(rows[1]!.find('button').exists()).toBe(false)
    vi.mocked(confirmDanger).mockResolvedValueOnce(false)
    await rows[0]!.get('button').trigger('click'); await flushPromises()
    expect(confirmDanger).toHaveBeenCalledWith('撤销电视访问', expect.stringContaining('客厅电视'), '撤销访问', '保留')
    expect(authRequest).not.toHaveBeenCalledWith('/api/v1/admin/tv-devices/tv1', 'DELETE')
    vi.mocked(confirmDanger).mockResolvedValueOnce(true)
    await rows[0]!.get('button').trigger('click'); await flushPromises()
    expect(authRequest).toHaveBeenCalledWith('/api/v1/admin/tv-devices/tv1', 'DELETE')
  } finally { wrapper.unmount() }
})

it('操作失败的提示不会被后台轮询的成功刷新清除', async () => {
  vi.useFakeTimers()
  const wrapper = mount(TvDevices)
  try {
    await flushPromises()
    const form = wrapper.get('form.authorization-form')
    await form.get('input').setValue('123456')
    vi.mocked(authRequest).mockRejectedValueOnce(new Error('授权码已过期'))
    await form.trigger('submit'); await flushPromises()
    await vi.advanceTimersByTimeAsync(5000); await flushPromises()
    expect(wrapper.text()).toContain('授权码已过期')
  } finally { wrapper.unmount(); vi.useRealTimers() }
})
