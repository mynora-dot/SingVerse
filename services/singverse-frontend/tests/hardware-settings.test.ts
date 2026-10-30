// @vitest-environment jsdom
import { flushPromises, mount } from '@vue/test-utils'
import { beforeEach, expect, it, vi } from 'vitest'
import HardwareSettings from '../src/admin/modules/compute-settings/HardwareSettings.vue'
import { authRequest } from '../src/admin/core/auth/api'
vi.mock('../src/admin/core/auth/api')
const initial = { backend: 'deployment', effectiveBackend: 'auto', deploymentBackend: 'auto', revision: 3, writable: true }
beforeEach(() => {
  vi.resetAllMocks()
  vi.mocked(authRequest).mockImplementation(async (path, _method, body) => {
    if (path.endsWith('/test')) return { passed: true, backend: 'software', hardware: false, elapsedMs: 10 }
    if (body) return { ...initial, backend: 'software', effectiveBackend: 'software', revision: 4 }
    return initial
  })
})
it('读取、测试草稿、保存和恢复部署选择保持分离', async () => {
  const wrapper = mount(HardwareSettings)
  const button = (text: string) => wrapper.findAll('button').find(b => b.text() === text)!
  try {
    await button('读取硬件加速设置').trigger('click'); await flushPromises()
    await wrapper.get('select').setValue('software')
    expect(authRequest).toHaveBeenCalledTimes(1)
    await button('测试所选方式').trigger('click'); await flushPromises()
    expect(authRequest).toHaveBeenCalledWith('/api/v1/transcoding/hardware-settings/test', 'POST', { backend: 'software' }, 45000)
    expect(wrapper.text()).toContain('当前使用 CPU 软件编码')
    expect(wrapper.text()).toContain('当前生效：自动选择')
    await button('保存硬件加速设置').trigger('click'); await flushPromises()
    expect(authRequest).toHaveBeenCalledWith('/api/v1/transcoding/hardware-settings', 'POST', { backend: 'software', revision: 3 }, 45000)
    expect(wrapper.text()).toContain('硬件加速设置已保存')
    await wrapper.get('select').setValue('deployment')
    await button('保存硬件加速设置').trigger('click'); await flushPromises()
    expect(authRequest).toHaveBeenLastCalledWith('/api/v1/transcoding/hardware-settings', 'POST', { backend: 'deployment', revision: 4 }, 45000)
  } finally { wrapper.unmount() }
})
it('读取失败可重试，保存冲突显示错误且不宣告成功', async () => {
  vi.mocked(authRequest).mockRejectedValueOnce(new Error('节点暂不可用'))
  const wrapper = mount(HardwareSettings)
  try {
    await wrapper.find('button').trigger('click'); await flushPromises()
    expect(wrapper.text()).toContain('节点暂不可用')
    await wrapper.find('button').trigger('click'); await flushPromises()
    vi.mocked(authRequest).mockRejectedValueOnce(new Error('配置已变化，请重新读取'))
    await wrapper.findAll('button').find(b => b.text() === '保存硬件加速设置')!.trigger('click'); await flushPromises()
    expect(wrapper.text()).toContain('配置已变化，请重新读取')
    expect(wrapper.text()).not.toContain('硬件加速设置已保存')
  } finally { wrapper.unmount() }
})

it('能力按当前方式优先展示，其他平台默认收起，CPU 不显示 GPU 待验证', async () => {
  vi.mocked(authRequest).mockImplementation(async path => path.endsWith('/capabilities') ? {
    backends: [
      { backend: 'software', installed: true, samplePassed: true, codecs: { h264: { samplePassed: true } } },
      { backend: 'qsv', installed: false, samplePassed: false, codecs: {} },
      { backend: 'videotoolbox', installed: true, samplePassed: true, decodeAcceleration: true, zeroCopy: true, toneMappingAcceleration: false, codecs: { h264: { samplePassed: true }, hevc: { samplePassed: true }, av1: { samplePassed: false } } },
    ],
  } : { ...initial, effectiveBackend: 'videotoolbox' })
  const wrapper = mount(HardwareSettings)
  try {
    await wrapper.find('button').trigger('click'); await flushPromises()
    await wrapper.findAll('button').find(b => b.text() === '探测所有编码器')!.trigger('click'); await flushPromises()
    const rows = wrapper.findAll('tbody tr')
    expect(rows).toHaveLength(2)
    expect(rows[0]!.text()).toContain('Apple（VideoToolbox）')
    expect(rows[0]!.text()).toContain('当前使用')
    expect(rows[0]!.findAll('.codec-tag').map(tag => tag.text())).toEqual(['H264', 'HEVC'])
    expect(rows[0]!.findAll('.stage-tag.verified').map(tag => tag.text())).toEqual(['硬解已验证', '零拷贝已验证'])
    expect(rows[0]!.text()).toContain('GPU 色调映射待验证')
    expect(rows[1]!.text()).toContain('CPU 软件处理')
    expect(rows[1]!.text()).not.toContain('待验证')
    expect(wrapper.get('.unavailable-details').attributes('open')).toBeUndefined()
    expect(wrapper.get('.unavailable-details').text()).toContain('Intel Quick Sync')
    expect(wrapper.get('.deployment-note').attributes('open')).toBeUndefined()
  } finally { wrapper.unmount() }
})
