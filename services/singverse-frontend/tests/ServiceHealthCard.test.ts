// @vitest-environment jsdom
import { flushPromises, mount } from '@vue/test-utils'
import { afterEach, expect, it, vi } from 'vitest'
import ServiceHealthCard from '../src/admin/modules/system-status/components/ServiceHealthCard.vue'

afterEach(() => vi.unstubAllGlobals())

it('显示连接失败，点击重新检查后恢复，且不保留旧失败状态', async () => {
  vi.stubGlobal(
    'fetch',
    vi
      .fn()
      .mockRejectedValueOnce(new TypeError('offline'))
      .mockResolvedValueOnce(Response.json({ status: 'UP' })),
  )
  const wrapper = mount(ServiceHealthCard)
  await flushPromises()
  expect(wrapper.get('[role="status"]').text()).toContain('连接失败')
  await wrapper.get('button').trigger('click')
  await flushPromises()
  expect(wrapper.get('[role="status"]').text()).toContain('运行正常')
  expect(wrapper.get('[role="status"]').text()).not.toContain('连接失败')
  wrapper.unmount()
})

it('HTTP 200 携带 DOWN 时显示状态异常', async () => {
  vi.stubGlobal('fetch', vi.fn().mockResolvedValue(Response.json({ status: 'DOWN' })))
  const wrapper = mount(ServiceHealthCard)
  await flushPromises()
  expect(wrapper.get('[role="status"]').text()).toContain('状态异常')
  expect(wrapper.get('[role="status"]').text()).not.toContain('运行正常')
  wrapper.unmount()
})
