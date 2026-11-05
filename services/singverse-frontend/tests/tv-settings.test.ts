// @vitest-environment jsdom
import { mount, flushPromises } from '@vue/test-utils'
import { beforeEach, expect, it, vi } from 'vitest'
import { ElMessageBox } from 'element-plus'
import TvSettingsView from '../src/admin/modules/tv-settings/TvSettingsView.vue'
import { tvSettings, type TvSettings } from '../src/admin/modules/tv-settings/api'

vi.mock('../src/admin/modules/tv-settings/api')
vi.mock('vue-router', () => ({ onBeforeRouteLeave: vi.fn() }))
const original: TvSettings = { revision: 2, headline: '把生活，唱成热爱。', subtitle: '你的私人音乐现场', autoRotate: true, rotationSeconds: 8, banners: [
  { id: 'one', label: '封面一', enabled: true, version: 'v1', imagePath: '/api/v1/admin/tv-settings/banners/one/image' },
  { id: 'two', label: '封面二', enabled: true, version: 'v2', imagePath: '/api/v1/admin/tv-settings/banners/two/image' },
] }
beforeEach(() => { vi.resetAllMocks(); vi.mocked(tvSettings.read).mockResolvedValue(structuredClone(original)) })
it('修改封面顺序并保存完整草稿，预览可切换多张图片', async () => {
  const wrapper = mount(TvSettingsView)
  try {
    await flushPromises()
    expect(wrapper.findAll('.tv-banner-row')).toHaveLength(2)
    await wrapper.get('button[aria-label="预览封面二"]').trigger('click')
    expect(wrapper.get('.tv-hero-preview').attributes('style')).toContain('/two/image')
    await wrapper.get('button[aria-label="下移封面1"]').trigger('click')
    vi.mocked(tvSettings.save).mockImplementation(async value => ({ ...structuredClone(value), revision: 3 }))
    await wrapper.findAll('button').find(el => el.text() === '保存设置')!.trigger('click'); await flushPromises()
    expect(tvSettings.save).toHaveBeenCalledWith(expect.objectContaining({ revision: 2, banners: [expect.objectContaining({ id: 'two' }), expect.objectContaining({ id: 'one' })] }))
    expect(wrapper.get('button[aria-label="上移封面1"]').attributes('disabled')).toBeDefined()
  } finally { wrapper.unmount() }
})
it('版本冲突保留草稿并提示，不覆盖其他管理员的设置', async () => {
  const wrapper = mount(TvSettingsView)
  try {
    await flushPromises()
    await wrapper.get('input[aria-label="首页标题"]').setValue('新的标题')
    vi.mocked(tvSettings.save).mockRejectedValueOnce(new Error('电视设置已被修改，请重新加载后再试'))
    await wrapper.findAll('button').find(el => el.text() === '保存设置')!.trigger('click'); await flushPromises()
    expect(wrapper.get('[role="alert"]').text()).toContain('已被修改')
    expect((wrapper.get('input[aria-label="首页标题"]').element as HTMLInputElement).value).toBe('新的标题')
    expect(tvSettings.read).toHaveBeenCalledTimes(1)
  } finally { wrapper.unmount() }
})
it('确认移除时携带当前版本，未保存草稿禁止移除', async () => {
  const confirm = vi.spyOn(ElMessageBox, 'confirm').mockResolvedValue('confirm' as Awaited<ReturnType<typeof ElMessageBox.confirm>>)
  vi.mocked(tvSettings.remove).mockResolvedValue({ ...original, revision: 3, banners: [original.banners[1]!] })
  const wrapper = mount(TvSettingsView)
  try {
    await flushPromises()
    await wrapper.findAll('.tv-banner-row button').find(el => el.text() === '移除')!.trigger('click'); await flushPromises()
    expect(tvSettings.remove).toHaveBeenCalledWith('one', 2)
    expect(wrapper.findAll('.tv-banner-row')).toHaveLength(1)
    await wrapper.get('input[aria-label="首页标题"]').setValue('草稿')
    expect(wrapper.findAll('.tv-banner-row button').find(el => el.text() === '移除')!.attributes('disabled')).toBeDefined()
  } finally { wrapper.unmount(); confirm.mockRestore() }
})

async function chooseFiles(wrapper: ReturnType<typeof mount>, files: File[]) {
  const input = wrapper.get('input[type="file"]')
  Object.defineProperty(input.element, 'files', { value: files, configurable: true })
  await input.trigger('change'); await flushPromises()
}
it('多选逐张上传使用最新版本，失败保留成功图片并停止', async () => {
  const wrapper = mount(TvSettingsView)
  try {
    await flushPromises()
    expect(wrapper.get('input[type="file"]').attributes('multiple')).toBeDefined()
    const files = ['a.png', 'b.png', 'c.png'].map(name => new File(['image'], name, { type: 'image/png' }))
    vi.mocked(tvSettings.upload).mockResolvedValueOnce({ ...structuredClone(original), revision: 3, banners: [...original.banners, { ...original.banners[0]!, id: 'three' }] }).mockRejectedValueOnce(new Error('上传入口拒绝（413）'))
    await chooseFiles(wrapper, files)
    expect(tvSettings.upload).toHaveBeenNthCalledWith(1, files[0], 2)
    expect(tvSettings.upload).toHaveBeenNthCalledWith(2, files[1], 3)
    expect(tvSettings.upload).toHaveBeenCalledTimes(2)
    expect(wrapper.findAll('.tv-banner-row')).toHaveLength(3)
    expect(wrapper.get('[role="alert"]').text()).toContain('已成功上传1/3张')
    expect(wrapper.get('[role="alert"]').text()).toContain('b.png')
    expect(wrapper.get('[role="alert"]').text()).toContain('413')
  } finally { wrapper.unmount() }
})
it('整批预先校验数量和文件类型，不发出部分上传', async () => {
  const wrapper = mount(TvSettingsView)
  try {
    await flushPromises()
    await chooseFiles(wrapper, Array.from({ length: 7 }, (_, i) => new File(['x'], `${i}.png`, { type: 'image/png' })))
    expect(wrapper.get('[role="alert"]').text()).toContain('还可上传6张')
    await chooseFiles(wrapper, [new File(['x'], 'ok.png', { type: 'image/png' }), new File(['x'], 'bad.webp', { type: 'image/webp' })])
    expect(wrapper.get('[role="alert"]').text()).toContain('bad.webp')
    expect(tvSettings.upload).not.toHaveBeenCalled()
  } finally { wrapper.unmount() }
})
it('整批成功后展示全部图片并使用最新版本保存', async () => {
  const wrapper = mount(TvSettingsView)
  try {
    await flushPromises()
    vi.mocked(tvSettings.upload).mockImplementation(async (_, revision) => ({ ...structuredClone(original), revision: revision + 1, banners: Array.from({ length: revision + 1 }, (_, i) => ({ ...original.banners[0]!, id: `image-${i}` })) }))
    await chooseFiles(wrapper, ['a.png', 'b.png'].map(name => new File(['x'], name, { type: 'image/png' })))
    expect(wrapper.findAll('.tv-banner-row')).toHaveLength(4)
    expect(wrapper.find('[role="alert"]').exists()).toBe(false)
    await wrapper.get('input[aria-label="首页标题"]').setValue('新的标题')
    vi.mocked(tvSettings.save).mockResolvedValue({ ...original, revision: 5 })
    await wrapper.findAll('button').find(el => el.text() === '保存设置')!.trigger('click'); await flushPromises()
    expect(tvSettings.save).toHaveBeenCalledWith(expect.objectContaining({ revision: 4 }))
  } finally { wrapper.unmount() }
})
