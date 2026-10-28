// @vitest-environment jsdom
import { flushPromises, mount, type VueWrapper } from '@vue/test-utils'
import { createMemoryHistory, createRouter, type Router } from 'vue-router'
import { afterEach, beforeEach, expect, it, vi } from 'vitest'
import { ElMessageBox } from 'element-plus'
import FilenameProfilesView from '../src/admin/modules/filename-parser/views/FilenameProfilesView.vue'
import { parserProfiles } from '../src/admin/modules/filename-parser/api/rules'
import { ApiError } from '../src/admin/core/auth/api'
import type { ParserWorkspace, ProfilePreview } from '../src/admin/modules/filename-parser/types'
import { workspace, preview } from './fixtures/filename-workspace'

vi.mock('../src/admin/modules/filename-parser/api/rules', () => ({ parserProfiles: { load: vi.fn(), create: vi.fn(), update: vi.fn(), remove: vi.fn(), preview: vi.fn() } }))
let wrapper: VueWrapper
let router: Router
let server: ParserWorkspace
const clone = <T,>(value: T): T => JSON.parse(JSON.stringify(value)) as T
beforeEach(() => {
  server = workspace()
  vi.spyOn(ElMessageBox, 'confirm').mockResolvedValue('confirm' as Awaited<ReturnType<typeof ElMessageBox.confirm>>)
  vi.mocked(parserProfiles.load).mockImplementation(async () => clone(server))
  vi.mocked(parserProfiles.preview).mockImplementation(async () => ({ revision: server.revision, profileId: null, profileName: null, preview: preview() }))
  vi.mocked(parserProfiles.create).mockImplementation(async (revision, profile) => {
    server = { ...server, revision: revision + 1, changedId: 'created-profile', profiles: [...server.profiles, { id: 'created-profile', ...clone(profile) }] }
    return clone(server)
  })
  vi.mocked(parserProfiles.update).mockImplementation(async (id, revision, profile) => {
    server = { ...server, revision: revision + 1, changedId: id, profiles: server.profiles.map(p => p.id === id ? { id, ...clone(profile) } : p) }
    return clone(server)
  })
  vi.mocked(parserProfiles.remove).mockImplementation(async (id, revision) => {
    server = { ...server, revision: revision + 1, changedId: id, profiles: server.profiles.filter(p => p.id !== id) }
    return clone(server)
  })
})
afterEach(() => { wrapper?.unmount(); document.body.innerHTML = ''; vi.restoreAllMocks(); vi.resetAllMocks() })
async function open(profile = 'reverse-profile') {
  router = createRouter({ history: createMemoryHistory(), routes: [
    { path: '/library/filename-parser-profiles', component: FilenameProfilesView },
    { path: '/library/filename-parser', component: { template: '<div>解析规则</div>' } },
    { path: '/other', component: { template: '<div>其他页面</div>' } },
  ] })
  await router.push(`/library/filename-parser-profiles?profile=${profile}`)
  wrapper = mount({ template: '<RouterView />' }, { global: { plugins: [router], stubs: { teleport: true } } })
  await flushPromises()
}
function button(text: string) { return wrapper.findAll('button').find(b => b.text() === text)! }
function profileButton(name: string) { return wrapper.findAll('button.profile-choice').find(b => b.text().includes(name))! }
function formatButton(name: string) { return wrapper.findAll('button.format-choice').find(b => b.text().includes(name))! }

it('引用链接选择方案，使用成员草稿预览，不保存也不发送规则定义', async () => {
  await open()
  expect((wrapper.get('input[aria-label="方案名称"]').element as HTMLInputElement).value).toBe('反向方案')
  await button('解析当前方案').trigger('click'); await flushPromises()
  expect(parserProfiles.preview).toHaveBeenCalledWith('晴天-周杰伦.mkv', 0, [{ ruleId: 'reverse', enabled: true }])
  expect(wrapper.get('[data-testid="parsed-artists"]').text()).toBe('周杰伦')
  expect(wrapper.get('[data-testid="parsed-title"]').text()).toBe('晴天')
  expect(wrapper.text()).toContain('预览方案：反向方案')
  expect(parserProfiles.update).not.toHaveBeenCalled()
})
it('创建和复制方案只复制成员引用，保存无需先匹配成功', async () => {
  await open()
  await button('复制当前方案').trigger('click'); await flushPromises()
  expect(wrapper.text()).toContain('未保存草稿')
  await button('保存方案').trigger('click'); await flushPromises()
  expect(parserProfiles.create).toHaveBeenCalledWith(0, { name: '反向方案（副本）', entries: [{ ruleId: 'reverse', enabled: true }] })
  expect(parserProfiles.preview).not.toHaveBeenCalled()
  expect(router.currentRoute.value.query.profile).toBe('created-profile')
  expect(wrapper.text()).toContain('已保存方案')
  await button('新建方案').trigger('click'); await flushPromises()
  await wrapper.get('input[aria-label="方案名称"]').setValue('空方案')
  await button('保存方案').trigger('click'); await flushPromises()
  expect(parserProfiles.create).toHaveBeenLastCalledWith(1, { name: '空方案', entries: [] })
})
it('成员可添加、排序、启停和移除，规则表达式仅供查看', async () => {
  await open()
  await wrapper.get('select[aria-label="添加已有规则"]').setValue('forward')
  await button('添加到方案').trigger('click')
  await wrapper.get('button[aria-label="上移 正向"]').trigger('click')
  await wrapper.get('[aria-label="启用 正向"]').trigger('click')
  await button('解析当前方案').trigger('click'); await flushPromises()
  expect(parserProfiles.preview).toHaveBeenLastCalledWith('晴天-周杰伦.mkv', 0, [{ ruleId: 'forward', enabled: false }, { ruleId: 'reverse', enabled: true }])
  expect(wrapper.find('textarea').exists()).toBe(false)
  await wrapper.get('button[aria-label="移除 正向"]').trigger('click')
  await wrapper.get('input[aria-label="方案名称"]').setValue('改名反向')
  await button('保存方案').trigger('click'); await flushPromises()
  expect(parserProfiles.update).toHaveBeenCalledWith('reverse-profile', 0, { name: '改名反向', entries: [{ ruleId: 'reverse', enabled: true }] })
})
it('切换和离开页面保护未保存草稿，刷新前也触发保护', async () => {
  await open()
  await wrapper.get('input[aria-label="方案名称"]').setValue('尚未保存')
  vi.mocked(ElMessageBox.confirm).mockRejectedValueOnce('cancel')
  await profileButton('默认解析方案').trigger('click'); await flushPromises()
  expect((wrapper.get('input[aria-label="方案名称"]').element as HTMLInputElement).value).toBe('尚未保存')
  const event = new Event('beforeunload', { cancelable: true })
  window.dispatchEvent(event)
  expect(event.defaultPrevented).toBe(true)
  vi.mocked(ElMessageBox.confirm).mockRejectedValueOnce('cancel')
  await router.push('/other'); await flushPromises()
  expect(router.currentRoute.value.path).toBe('/library/filename-parser-profiles')
  await profileButton('默认解析方案').trigger('click'); await flushPromises()
  expect((wrapper.get('input[aria-label="方案名称"]').element as HTMLInputElement).value).toBe('默认解析方案')
  expect(ElMessageBox.confirm).toHaveBeenCalledTimes(3)
})
it('默认方案不可删除；删除其他方案不删除共享规则', async () => {
  await open('default')
  expect(wrapper.findAll('button').some(b => b.text() === '删除方案')).toBe(false)
  await profileButton('反向方案').trigger('click'); await flushPromises()
  await button('删除方案').trigger('click'); await flushPromises()
  expect(parserProfiles.remove).toHaveBeenCalledWith('reverse-profile', 0)
  expect(router.currentRoute.value.query.profile).toBe('default')
  expect(server.definitions.some(d => d.id === 'reverse')).toBe(true)
})
it('输入或成员变化标记旧预览，并丢弃迟到响应', async () => {
  await open(); await button('解析当前方案').trigger('click'); await flushPromises()
  await wrapper.get('input[aria-label="媒体文件名"]').setValue('新的输入.mkv')
  expect(wrapper.text()).toContain('以下为旧预览')
  let resolve!: (value: ProfilePreview) => void
  vi.mocked(parserProfiles.preview).mockImplementationOnce(() => new Promise(r => { resolve = r }))
  await button('解析当前方案').trigger('click')
  await wrapper.get('[aria-label="启用 反向"]').trigger('click')
  const late = preview(); late.result.title = '迟到结果'
  resolve({ revision: 0, profileId: null, profileName: null, preview: late }); await flushPromises()
  expect(wrapper.get('[data-testid="parsed-title"]').text()).toBe('晴天')
  expect(wrapper.text()).not.toContain('迟到结果')
})
it('版本冲突加载最新规则库后保留成员草稿，不自动删除失效引用', async () => {
  await open(); await button('复制当前方案').trigger('click'); await flushPromises()
  vi.mocked(parserProfiles.preview).mockRejectedValueOnce(new ApiError(409, '配置变化'))
  await button('解析当前方案').trigger('click'); await flushPromises()
  expect(wrapper.text()).toContain('配置变化')
  server = { ...workspace(), revision: 8, definitions: workspace().definitions.filter(d => d.id !== 'reverse'), profiles: workspace().profiles.filter(p => p.id !== 'reverse-profile') }
  await button('加载最新配置，保留草稿').trigger('click'); await flushPromises()
  expect(wrapper.text()).toContain('失效引用：reverse')
  expect((wrapper.get('input[aria-label="方案名称"]').element as HTMLInputElement).value).toBe('反向方案（副本）')
  vi.mocked(parserProfiles.preview).mockRejectedValueOnce(new ApiError(400, '规则已被删除', { ruleId: 'reverse', ruleIndex: 0, field: 'entries' }))
  await button('解析当前方案').trigger('click'); await flushPromises()
  expect(parserProfiles.preview).toHaveBeenLastCalledWith('晴天-周杰伦.mkv', 8, [{ ruleId: 'reverse', enabled: true }])
  expect(wrapper.text()).toContain('规则已被删除')
})
it('空方案未命中显示候选和诊断，错误加载可重试', async () => {
  vi.mocked(parserProfiles.load).mockRejectedValueOnce(new Error('加载失败'))
  await open()
  expect(wrapper.text()).toContain('加载失败')
  await button('重试加载').trigger('click'); await flushPromises()
  const none = preview(); none.selectedRule = null; none.result.title = null; none.result.artists = []; none.result.parseStatus = 'FAILED'; none.attempts = []
  vi.mocked(parserProfiles.preview).mockResolvedValueOnce({ revision: 0, profileId: null, profileName: null, preview: none })
  await button('解析当前方案').trigger('click'); await flushPromises()
  expect(wrapper.text()).toContain('未完整命中')
  expect(wrapper.get('[data-testid="parsed-artists"]').text()).toBe('未确定')
})
it('检测用内置格式候选一次请求，点卡片只改草稿，保存才提交', async () => {
  await open()
  expect(wrapper.findAll('button.format-choice')).toHaveLength(2)
  await button('找出能识别的格式').trigger('click'); await flushPromises()
  expect(parserProfiles.preview).toHaveBeenCalledWith('晴天-周杰伦.mkv', 0, [{ ruleId: 'builtin-book', enabled: true }, { ruleId: 'builtin-hyphen', enabled: true }])
  expect(wrapper.text()).toContain('找到 1 种能识别的格式')
  expect(formatButton('歌手-歌名').text()).toContain('能用')
  expect(formatButton('书名号').text()).toContain('识别不了')
  await formatButton('歌手-歌名').trigger('click'); await flushPromises()
  expect(formatButton('歌手-歌名').text()).toContain('已在方案里')
  expect(wrapper.text()).toContain('未保存草稿')
  expect(parserProfiles.update).not.toHaveBeenCalled()
  await button('保存方案').trigger('click'); await flushPromises()
  expect(parserProfiles.update).toHaveBeenCalledWith('reverse-profile', 0, { name: '反向方案', entries: [{ ruleId: 'reverse', enabled: true }, { ruleId: 'builtin-hyphen', enabled: true }] })
})
it('检测冲突保留成员草稿并可加载最新配置，不覆盖未保存内容', async () => {
  await open()
  await wrapper.get('input[aria-label="方案名称"]').setValue('待保护')
  vi.mocked(parserProfiles.preview).mockRejectedValueOnce(new ApiError(409, '配置变化'))
  await button('找出能识别的格式').trigger('click'); await flushPromises()
  expect(wrapper.text()).toContain('配置变化')
  expect(wrapper.text()).toContain('未保存草稿')
  expect((wrapper.get('input[aria-label="方案名称"]').element as HTMLInputElement).value).toBe('待保护')
  expect(button('保存方案').attributes('disabled')).toBeDefined()
  await button('加载最新配置，保留草稿').trigger('click'); await flushPromises()
  expect((wrapper.get('input[aria-label="方案名称"]').element as HTMLInputElement).value).toBe('待保护')
  expect(button('保存方案').attributes('disabled')).toBeUndefined()
})
it('改文件名后标记旧检测结果，重新检测使用新输入', async () => {
  await open()
  await button('找出能识别的格式').trigger('click'); await flushPromises()
  await wrapper.get('input[aria-label="媒体文件名"]').setValue('新的输入.mkv')
  expect(wrapper.text()).toContain('文件名改了，以下为旧结果')
  await button('找出能识别的格式').trigger('click'); await flushPromises()
  expect(wrapper.text()).not.toContain('文件名改了，以下为旧结果')
  expect(parserProfiles.preview).toHaveBeenLastCalledWith('新的输入.mkv', 0, [{ ruleId: 'builtin-book', enabled: true }, { ruleId: 'builtin-hyphen', enabled: true }])
})
