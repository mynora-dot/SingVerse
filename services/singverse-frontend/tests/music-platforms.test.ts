// @vitest-environment jsdom
import { flushPromises, mount, type VueWrapper } from '@vue/test-utils'
import { afterEach, beforeEach, expect, it, vi } from 'vitest'
import { createMemoryHistory, createRouter } from 'vue-router'
import { ElMessageBox } from 'element-plus'
import MusicPlatformsView from '../src/admin/modules/metadata/views/MusicPlatformsView.vue'
import { metadata } from '../src/admin/modules/metadata/api/metadata'
import { providerNames, type MetadataBatch, type MetadataSettings, type MusicProvider } from '../src/admin/modules/metadata/types'

vi.mock('../src/admin/modules/metadata/api/metadata', () => ({ metadata: { settings: vi.fn(), saveSettings: vi.fn(), latest: vi.fn() } }))
const platforms = Object.keys(providerNames) as MusicProvider[]
const initial: MetadataSettings = { enabled: true, providers: platforms, autoApply: false, threshold: .95, minIntervalSeconds: 3, maxIntervalSeconds: 8 }
let wrapper: VueWrapper | undefined
function button(name: string) { return wrapper!.findAll('button').find(b => b.text() === name)! }
async function page(path = '/system/music-platforms') {
  const router = createRouter({ history: createMemoryHistory(), routes: [
    { path: '/system/music-platforms', component: MusicPlatformsView },
    { path: '/library/songs/metadata', component: { template: '<div>歌曲信息</div>' } },
    { path: '/library/songs', component: { template: '<div>曲库</div>' } },
  ] })
  await router.push(path)
  wrapper = mount({ template: '<RouterView />' }, { global: { plugins: [router] } })
  await flushPromises(); return router
}
async function save() { await wrapper!.get('form[aria-label="音乐平台设置"]').trigger('submit'); await flushPromises() }
async function interval(min: number, max: number) {
  await wrapper!.get('input[aria-label="批量最小间隔秒"]').setValue(String(min))
  await wrapper!.get('input[aria-label="批量最大间隔秒"]').setValue(String(max))
}
beforeEach(() => {
  vi.mocked(metadata.settings).mockResolvedValue({ ...initial, providers: [...platforms] })
  vi.mocked(metadata.latest).mockResolvedValue(undefined)
  vi.mocked(metadata.saveSettings).mockImplementation(async value => ({ ...value, providers: [...value.providers] }))
})
afterEach(() => { wrapper?.unmount(); wrapper = undefined; vi.restoreAllMocks(); vi.clearAllMocks(); document.body.innerHTML = '' })

it('五个平台直接可见并展示服务端默认状态，自动应用保持关闭', async () => {
  await page()
  for (const provider of platforms) expect((wrapper!.get(`input[value="${provider}"]`).element as HTMLInputElement).checked).toBe(true)
  expect(wrapper!.get('[aria-label="高相似度时自动应用"]').attributes('aria-checked')).toBe('false')
  expect(wrapper!.text()).toContain('已开启 5 个平台')
  expect(button('保存设置').attributes('disabled')).toBeDefined()
  expect(metadata.saveSettings).not.toHaveBeenCalled()
})
it('启用全部平台后明确保存，保留原自动应用、阈值与间隔', async () => {
  vi.mocked(metadata.settings).mockResolvedValue({ ...initial, enabled: false, providers: ['NETEASE'], autoApply: true, threshold: .96, minIntervalSeconds: 1, maxIntervalSeconds: 3 })
  await page(); await button('启用全部平台').trigger('click')
  expect(metadata.saveSettings).not.toHaveBeenCalled()
  await save()
  expect(metadata.saveSettings).toHaveBeenCalledWith({ ...initial, enabled: true, providers: platforms, autoApply: true, threshold: .96, minIntervalSeconds: 1, maxIntervalSeconds: 3 })
  expect(wrapper!.text()).toContain('当前设置已保存')
})
it('读取失败不冒充全部已开启，重试成功后才展示真实配置', async () => {
  vi.mocked(metadata.settings).mockRejectedValueOnce(new Error('网络暂不可用'))
  await page()
  expect(wrapper!.text()).toContain('网络暂不可用')
  expect(wrapper!.find('form').exists()).toBe(false)
  await button('重新读取').trigger('click'); await flushPromises()
  expect(wrapper!.text()).toContain('已开启 5 个平台')
})
it('保存失败保留平台选择与输入供重试', async () => {
  vi.mocked(metadata.saveSettings).mockRejectedValueOnce(new Error('保存连接超时'))
  await page(); await wrapper!.get('input[value="QQ"]').setValue(false); await interval(10, 20); await save()
  expect(wrapper!.text()).toContain('保存连接超时')
  expect((wrapper!.get('input[value="QQ"]').element as HTMLInputElement).checked).toBe(false)
  expect((wrapper!.get('input[aria-label="批量最小间隔秒"]').element as HTMLInputElement).value).toBe('10')
  await save()
  expect(metadata.saveSettings).toHaveBeenLastCalledWith({ ...initial, providers: platforms.filter(p => p !== 'QQ'), minIntervalSeconds: 10, maxIntervalSeconds: 20 })
})
it('零秒间隔有效，倒置间隔和空平台不能保存', async () => {
  await page(); await interval(0, 0); await save()
  expect(metadata.saveSettings).toHaveBeenCalledWith({ ...initial, minIntervalSeconds: 0, maxIntervalSeconds: 0 })
  await interval(20, 10); await save()
  expect(metadata.saveSettings).toHaveBeenCalledTimes(1)
  expect(wrapper!.text()).toContain('最大值不能小于最小值')
  await interval(3, 8)
  for (const platform of platforms) await wrapper!.get(`input[value="${platform}"]`).setValue(false)
  await save(); expect(metadata.saveSettings).toHaveBeenCalledTimes(1)
  expect(wrapper!.text()).toContain('至少选择一个平台')
})
it('百分比阈值正确转换为服务端比例', async () => {
  await page(); await wrapper!.get('input[aria-label="自动应用阈值百分比"]').setValue('96'); await save()
  expect(metadata.saveSettings).toHaveBeenCalledWith({ ...initial, threshold: .96 })
})
it('保存运行中配置先确认暂停，取消保持草稿，确认后才写入', async () => {
  vi.mocked(metadata.latest).mockResolvedValue({ id: 'running', status: 'RUNNING' } as MetadataBatch)
  const confirm = vi.spyOn(ElMessageBox, 'confirm').mockRejectedValueOnce('cancel')
  await page(); await interval(10, 20); await save()
  expect(confirm).toHaveBeenCalledWith(expect.stringContaining('暂停'), '保存并暂停批次', expect.any(Object))
  expect(metadata.saveSettings).not.toHaveBeenCalled()
  confirm.mockResolvedValueOnce('confirm' as never); await save()
  expect(metadata.saveSettings).toHaveBeenCalledWith({ ...initial, minIntervalSeconds: 10, maxIntervalSeconds: 20 })
})
it('任务状态未知时明确提示，保存仍确认暂停语义', async () => {
  vi.mocked(metadata.latest).mockRejectedValue(new Error('unknown'))
  const confirm = vi.spyOn(ElMessageBox, 'confirm').mockRejectedValueOnce('cancel')
  await page(); await interval(1, 2); await save()
  expect(wrapper!.text()).toContain('暂时无法读取匹配任务状态')
  expect(confirm).toHaveBeenCalled(); expect(metadata.saveSettings).not.toHaveBeenCalled()
})
it('离页与刷新保护未保存的修改，取消离开保留输入', async () => {
  const confirm = vi.spyOn(ElMessageBox, 'confirm').mockRejectedValueOnce('cancel')
  const router = await page(); await interval(1, 2)
  const event = new Event('beforeunload', { cancelable: true }); window.dispatchEvent(event); expect(event.defaultPrevented).toBe(true)
  await router.push('/library/songs'); expect(router.currentRoute.value.path).toBe('/system/music-platforms')
  confirm.mockResolvedValueOnce('confirm' as never); await router.push('/library/songs'); expect(router.currentRoute.value.path).toBe('/library/songs')
  expect(metadata.saveSettings).not.toHaveBeenCalled()
  const after = new Event('beforeunload', { cancelable: true }); window.dispatchEvent(after); expect(after.defaultPrevented).toBe(false)
})
it('返回链接保留指定歌曲与批次，拒绝外部返回地址', async () => {
  const router = await page('/system/music-platforms?returnTo='+encodeURIComponent('/library/songs/metadata?batch=chosen&songIds=a,b'))
  await button('返回原页面').trigger('click'); await flushPromises()
  expect(router.currentRoute.value.fullPath).toBe('/library/songs/metadata?batch=chosen&songIds=a,b')
  await router.push('/system/music-platforms?returnTo='+encodeURIComponent('//evil.invalid'))
  await flushPromises(); await button('返回原页面').trigger('click'); await flushPromises()
  expect(router.currentRoute.value.path).toBe('/library/songs/metadata')
})
it('离开后迟到的配置不能覆盖重新进入读取的状态', async () => {
  let resolve!: (value: MetadataSettings) => void
  vi.mocked(metadata.settings).mockReturnValueOnce(new Promise(r => { resolve = r }))
  const router = await page(); expect(wrapper!.text()).toContain('正在读取')
  await router.push('/library/songs'); await router.push('/system/music-platforms'); await flushPromises()
  resolve({ ...initial, enabled: false, providers: ['QQ'] }); await flushPromises()
  expect(wrapper!.text()).toContain('已开启 5 个平台')
  expect(metadata.saveSettings).not.toHaveBeenCalled()
})
