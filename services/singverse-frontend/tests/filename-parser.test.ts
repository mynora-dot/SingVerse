// @vitest-environment jsdom
import { flushPromises, mount, type VueWrapper } from '@vue/test-utils'
import { afterEach, beforeEach, expect, it, vi } from 'vitest'
import { ElDialog, ElMessageBox } from 'element-plus'
import { createMemoryHistory, createRouter, RouterLink, type Router } from 'vue-router'
import FilenameParserView from '../src/admin/modules/filename-parser/views/FilenameParserView.vue'
import { ruleLibrary } from '../src/admin/modules/filename-parser/api/rules'
import { ApiError } from '../src/admin/core/auth/api'
import { workspace } from './fixtures/filename-workspace'

vi.mock('../src/admin/modules/filename-parser/api/rules', () => ({ ruleLibrary: { load: vi.fn(), create: vi.fn(), update: vi.fn(), remove: vi.fn() } }))
let wrapper: VueWrapper
let router: Router
beforeEach(() => {
  vi.spyOn(ElMessageBox, 'confirm').mockResolvedValue('confirm' as never)
  vi.mocked(ruleLibrary.load).mockResolvedValue(workspace())
  vi.mocked(ruleLibrary.create).mockResolvedValue({ ...workspace(), revision: 1 })
  vi.mocked(ruleLibrary.update).mockResolvedValue({ ...workspace(), revision: 1 })
})
afterEach(() => { wrapper?.unmount(); document.body.innerHTML = ''; vi.restoreAllMocks(); vi.resetAllMocks() })
async function open() {
  router = createRouter({ history: createMemoryHistory(), routes: [
    { path: '/library/media/recognition/rules', component: FilenameParserView },
    { path: '/library/media/recognition', component: { template: '<div>识别方案</div>' } },
    { path: '/library/media', component: { template: '<div>歌曲来源</div>' } },
  ] })
  await router.push('/library/media/recognition/rules')
  wrapper = mount({ template: '<RouterView />' }, { global: { plugins: [router], stubs: { teleport: true } } }); await flushPromises()
}
function button(text: string) { return wrapper.findAll('button').find(b => b.text() === text)! }
it('规则页只维护定义及引用，不再提供组合启停排序和文本预览', async () => {
  await open()
  expect(wrapper.text()).toContain('引用方案')
  expect(wrapper.text()).toContain('默认解析方案')
  expect(wrapper.find('[role="switch"]').exists()).toBe(false)
  expect(wrapper.find('input[aria-label="媒体文件名"]').exists()).toBe(false)
  expect(wrapper.findAll('button').some(b => ['上移', '下移', '解析并校验草稿'].includes(b.text()))).toBe(false)
  const links = wrapper.findAllComponents(RouterLink)
  expect(links.some(l => JSON.stringify(l.props('to')).includes('reverse-profile'))).toBe(true)
})
it('新增规则直接后端校验保存，无需组合预览；内置规则只能复制', async () => {
  await open()
  expect(wrapper.find('button[aria-label="编辑 书名号"]').exists()).toBe(false)
  await button('新增规则').trigger('click'); await flushPromises()
  const dialog = wrapper.findComponent(ElDialog)
  await dialog.get('input[aria-label="规则名称"]').setValue('新反向')
  await dialog.get('textarea').setValue('{title}-{artist}')
  await dialog.get('form').trigger('submit'); await flushPromises()
  expect(ruleLibrary.create).toHaveBeenCalledWith(0, { name: '新反向', type: 'TEMPLATE', expression: '{title}-{artist}', builtinId: null })
  await wrapper.get('button[aria-label="复制 书名号"]').trigger('click'); await flushPromises()
  expect((wrapper.findComponent(ElDialog).get('textarea').element as HTMLTextAreaElement).value).toBe('{artist}《{title}》')
})
it('编辑共享规则前显示影响方案，正则校验错误保留编辑内容', async () => {
  await open()
  await wrapper.get('button[aria-label="编辑 正向"]').trigger('click'); await flushPromises()
  const dialog = wrapper.findComponent(ElDialog)
  expect(dialog.text()).toContain('保存会影响 1 个引用方案：默认解析方案')
  await dialog.get('select').setValue('REGEX')
  await dialog.get('textarea').setValue('[')
  vi.mocked(ruleLibrary.update).mockRejectedValueOnce(new ApiError(400, '正则无效', { field: 'expression' }))
  await dialog.get('form').trigger('submit'); await flushPromises()
  expect(dialog.text()).toContain('expression：正则无效')
  expect((dialog.get('textarea').element as HTMLTextAreaElement).value).toBe('[')
})
it('被引用的规则删除受阻，并展示引用方案链接', async () => {
  await open()
  vi.mocked(ruleLibrary.remove).mockRejectedValueOnce(new ApiError(409, '正被方案引用', { referencedBy: [{ id: 'default', name: '默认解析方案' }] }))
  await wrapper.get('button[aria-label="删除 正向"]').trigger('click'); await flushPromises()
  expect(wrapper.text()).toContain('正被方案引用')
  expect(ruleLibrary.remove).toHaveBeenCalledWith('forward', 0)
  expect(wrapper.text()).toContain('默认解析方案')
})
it('修订冲突后加载最新配置，仍保留待保存的编辑表达式', async () => {
  await open()
  await wrapper.get('button[aria-label="编辑 正向"]').trigger('click'); await flushPromises()
  const dialog = wrapper.findComponent(ElDialog)
  await dialog.get('textarea').setValue('{title}-{artist}')
  vi.mocked(ruleLibrary.update).mockRejectedValueOnce(new ApiError(409, '修订号已变化'))
  await dialog.get('form').trigger('submit'); await flushPromises()
  expect(dialog.text()).toContain('修订号已变化')
  vi.mocked(ruleLibrary.load).mockResolvedValueOnce({ ...workspace(), revision: 9 })
  await dialog.findAll('button').find(b => b.text() === '加载最新规则库，保留编辑内容')!.trigger('click'); await flushPromises()
  expect((dialog.get('textarea').element as HTMLTextAreaElement).value).toBe('{title}-{artist}')
  await dialog.get('form').trigger('submit'); await flushPromises()
  expect(ruleLibrary.update).toHaveBeenLastCalledWith('forward', 9, expect.objectContaining({ expression: '{title}-{artist}' }))
})
it('加载失败可以重试', async () => {
  vi.mocked(ruleLibrary.load).mockRejectedValueOnce(new Error('加载失败'))
  await open(); expect(wrapper.text()).toContain('加载失败')
  await button('加载最新规则库，保留编辑内容').trigger('click'); await flushPromises()
  expect(ruleLibrary.load).toHaveBeenCalledTimes(2)
})
it('返回歌曲来源、取消和刷新都保护未保存规则，保存失败也不会丢失输入', async () => {
  await open()
  await button('新增规则').trigger('click'); await flushPromises()
  const dialog = wrapper.findComponent(ElDialog)
  await dialog.get('input[aria-label="规则名称"]').setValue('待保存格式')
  await dialog.get('textarea').setValue('{title}_{artist}')
  vi.mocked(ruleLibrary.create).mockRejectedValueOnce(new Error('保存失败'))
  await dialog.get('form').trigger('submit'); await flushPromises()
  vi.mocked(ElMessageBox.confirm).mockRejectedValueOnce('cancel')
  await button('取消').trigger('click'); await flushPromises()
  expect(dialog.props('modelValue')).toBe(true)
  expect((dialog.get('textarea').element as HTMLTextAreaElement).value).toBe('{title}_{artist}')
  const event = new Event('beforeunload', { cancelable: true }); window.dispatchEvent(event)
  expect(event.defaultPrevented).toBe(true)
  vi.mocked(ElMessageBox.confirm).mockRejectedValueOnce('cancel')
  await router.push('/library/media'); await flushPromises()
  expect(router.currentRoute.value.path).toBe('/library/media/recognition/rules')
  expect(dialog.text()).toContain('保存失败')
})
