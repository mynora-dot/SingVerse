// @vitest-environment jsdom
import { flushPromises, mount, type VueWrapper } from '@vue/test-utils'
import { afterEach, beforeEach, expect, it, vi } from 'vitest'
import { ElDialog, ElInput, ElMessageBox } from 'element-plus'
import MediaLibraryView from '../src/admin/modules/media-library/views/MediaLibraryView.vue'
import { media } from '../src/admin/modules/media-library/api/media'
import { embeddedMetadata } from '../src/admin/modules/media-library/api/embeddedMetadata'
import { lx } from '../src/admin/modules/lx-downloads/api'

import { parserProfiles, ruleLibrary } from '../src/admin/modules/filename-parser/api/rules'
import { createMemoryHistory, createRouter, type Router } from 'vue-router'
import { workspace } from './fixtures/filename-workspace'

vi.mock('../src/admin/modules/filename-parser/api/rules', () => ({ parserProfiles: { load: vi.fn(), create: vi.fn(), update: vi.fn(), remove: vi.fn(), preview: vi.fn() }, ruleLibrary: { load: vi.fn(), create: vi.fn(), update: vi.fn(), remove: vi.fn() } }))
vi.mock('../src/admin/modules/media-library/api/embeddedMetadata', () => ({ embeddedMetadata: { summary: vi.fn() } }))
vi.mock('../src/admin/modules/lx-downloads/api', () => ({ lx: { status: vi.fn() } }))
vi.mock('../src/admin/modules/media-library/api/media', () => ({ media: { get: vi.fn(), directories: vi.fn(), list: vi.fn(), save: vi.fn(), remove: vi.fn(), scan: vi.fn(), songs: vi.fn(), createTask: vi.fn(), latestTask: vi.fn(), latestIngest: vi.fn(), task: vi.fn(), cancelTask: vi.fn() } }))
let wrapper: VueWrapper
let router: Router
beforeEach(async () => {
  router = createRouter({ history: createMemoryHistory(), routes: [
    { path: '/library/media', component: MediaLibraryView },
    { path: '/library/media/recognition', component: { template: '<div />' } },
    { path: '/library/songs', component: { template: '<div />' } },
  ] })
  await router.push('/library/media')
  vi.mocked(parserProfiles.load).mockResolvedValue(workspace())
  vi.mocked(ruleLibrary.load).mockResolvedValue(workspace())
  vi.mocked(media.list).mockResolvedValue({ items: [], page: 0, size: 20, totalElements: 0 })
  vi.mocked(media.directories).mockImplementation(async (path = '') => ({ path: path || '/media', parent: '/',
    items: path === '/media/songs' ? [] : [{ name: 'songs', path: '/media/songs' }], hasMore: false }))
  vi.mocked(media.latestIngest).mockResolvedValue(undefined)
  vi.mocked(embeddedMetadata.summary).mockResolvedValue({ counts: {} } as never)
  vi.mocked(media.latestTask).mockResolvedValue(undefined)
  vi.mocked(media.save).mockResolvedValue({ id: 'saved' } as never)
})
afterEach(() => { wrapper?.unmount(); document.body.innerHTML = ''; vi.clearAllMocks(); vi.restoreAllMocks() })
function button(text: string) {
  return wrapper.findAll('button').find(item => item.text() === text)!
}
it('任务中心来源深链接读取不在当前页的来源，并打开对应业务面板', async () => {
  await router.push('/library/media?source=outside-page')
  const source = { id: 'outside-page', name: '目标曲库', type: 'WEBDAV', location: 'https://nas.example.invalid/dav/', username: '', parserProfileId: 'default', passwordConfigured: false, createdAt: '', updatedAt: '' }
  vi.mocked(media.get).mockResolvedValue(source as never)
  wrapper = mount(MediaLibraryView, { global: { plugins: [router], stubs: { WebDavMetadataDialog: { props: ['source'], template: '<div class="linked-source">{{ source.name }}</div>' } } } })
  await flushPromises()
  expect(media.get).toHaveBeenCalledWith('outside-page')
  expect(wrapper.get('.linked-source').text()).toBe('目标曲库')
})
it('WebDAV 地址在卡片中显示解码后的中文，编辑时保留原地址', async () => {
  const location = 'http://nas.example.invalid/%E5%AA%92%E4%BD%93%E5%BA%93/ktv/music/'
  vi.mocked(media.list).mockResolvedValue({ items: [{ id: 'source-1', name: 'all', type: 'WEBDAV', location, username: '', parserProfileId: 'default', passwordConfigured: false, createdAt: '', updatedAt: '' }], page: 0, size: 20, totalElements: 1 })
  wrapper = mount(MediaLibraryView, { global: { plugins: [router], stubs: { teleport: true } } })
  await flushPromises()
  expect(wrapper.get('.source-location').text()).toBe('http://nas.example.invalid/媒体库/ktv/music/')
  expect(button('完善信息').exists()).toBe(true)
  await wrapper.get('summary[aria-label="all的更多操作"]').trigger('click')
  await button('编辑').trigger('click')
  await flushPromises()
  expect((wrapper.findComponent(ElDialog).get('input[placeholder="https://nas.example/dav/歌曲目录/"]').element as HTMLInputElement).value).toBe(location)
})
it('本地目录表单独立于远端认证，保存后刷新真实列表', async () => {
  wrapper = mount(MediaLibraryView, { global: { plugins: [router], stubs: { teleport: true } } })
  await flushPromises()
  await button('添加来源').trigger('click')
  await flushPromises()
  const form = wrapper.findComponent(ElDialog)
  expect(form.text()).toContain('歌曲文件夹')
  expect(form.text()).not.toContain('用户名')
  const inputs = form.findAllComponents(ElInput)
  await inputs[0]!.setValue('本地歌曲')
  await selectFolder()
  await form.get('form').trigger('submit')
  await flushPromises()
  expect(media.save).toHaveBeenCalledWith({ startScan: true, name: '本地歌曲', type: 'LOCAL', location: '/media/songs', parserProfileId: 'default', useForLxDownloads: false }, undefined)
  expect(media.list).toHaveBeenCalledTimes(2)
})
it('WebDAV 编辑不回显密码，留空时不覆盖密码', async () => {
  vi.mocked(media.list).mockResolvedValue({ items: [{ id: 'source-1', name: '远端歌曲', type: 'WEBDAV', location: 'https://nas.example.invalid/dav/', username: 'singer', parserProfileId: 'default', passwordConfigured: true, createdAt: '', updatedAt: '' }], page: 0, size: 20, totalElements: 1 })
  wrapper = mount(MediaLibraryView, { global: { plugins: [router], stubs: { teleport: true } } })
  await flushPromises()
  expect(wrapper.text()).toContain('按需连接')
  await wrapper.get('summary[aria-label="远端歌曲的更多操作"]').trigger('click'); await flushPromises()
  await button('编辑').trigger('click')
  await flushPromises()
  const form = wrapper.findComponent(ElDialog)
  expect(form.get('input[type="password"]').element).toHaveProperty('value', '')
  await form.get('form').trigger('submit')
  await flushPromises()
  expect(media.save).toHaveBeenCalledWith(expect.objectContaining({ password: undefined, username: 'singer' }), 'source-1')
})
it('列表失败提供重试，保存失败保留表单并显示原因', async () => {
  vi.mocked(media.list).mockRejectedValueOnce(new Error('读取失败'))
  wrapper = mount(MediaLibraryView, { global: { plugins: [router], stubs: { teleport: true } } })
  await flushPromises()
  expect(wrapper.text()).toContain('读取失败')
  await button('重试').trigger('click')
  await flushPromises()
  await button('添加来源').trigger('click'); await flushPromises(); await button('WebDAV').trigger('click')
  await flushPromises()
  const form = wrapper.findComponent(ElDialog)
  const inputs = form.findAllComponents(ElInput)
  await inputs[3]!.setValue('远端')
  await inputs[0]!.setValue('file:///invalid')
  vi.mocked(media.save).mockRejectedValueOnce(new Error('地址无效'))
  await form.get('form').trigger('submit')
  await flushPromises()
  expect(form.text()).toContain('地址无效')
  expect(form.props('modelValue')).toBe(true)
})

it('本地与 WebDAV 都保存所选完整方案，编辑可回显方案', async () => {
  wrapper = mount(MediaLibraryView, { global: { plugins: [router], stubs: { teleport: true } } })
  await flushPromises()
  for (const [label, location] of [['LOCAL', '/media/songs'], ['WEBDAV', 'https://nas.example.invalid/dav/']]) {
    await button('添加来源').trigger('click'); await flushPromises()
    if (label === 'WEBDAV') await button('WebDAV').trigger('click'); await flushPromises()
    const form = wrapper.findComponent(ElDialog)
    const inputs = form.findAllComponents(ElInput)
    if (label === 'LOCAL') { await inputs[0]!.setValue('反向歌曲'); await selectFolder() }
    else { await inputs[3]!.setValue('反向歌曲'); await inputs[0]!.setValue(location!) }
    await form.get('summary').trigger('click')
    await form.get('select[aria-label="解析方案"]').setValue('reverse-profile')
    await flushPromises()
    expect(form.text()).toContain('1 条启用规则')
    await form.get('form').trigger('submit'); await flushPromises()
    expect(media.save).toHaveBeenLastCalledWith(expect.objectContaining({ parserProfileId: 'reverse-profile' }), undefined)
    expect(wrapper.findComponent(ElDialog).props('modelValue')).toBe(false)
  }
})

it('方案加载失败时禁止保存，重新加载后可继续编辑', async () => {
  wrapper = mount(MediaLibraryView, { global: { plugins: [router], stubs: { teleport: true } } })
  await flushPromises()
  vi.mocked(parserProfiles.load).mockRejectedValueOnce(new Error('方案读取失败'))
  await button('添加来源').trigger('click'); await flushPromises()
  expect(wrapper.findComponent(ElDialog).text()).toContain('方案读取失败')
  expect(button('保存并扫描').attributes('disabled')).toBeDefined()
  await button('重新加载解析方案').trigger('click'); await flushPromises()
  expect(button('保存并扫描').attributes('disabled')).toBeUndefined()
})

it('本地扫描完成保留当前位置，查看歌曲时筛选来源', async () => {
  vi.mocked(media.list).mockResolvedValue({ items: [{ id: 'local', name: '本地', type: 'LOCAL', location: '/media/songs', username: '', parserProfileId: 'default', passwordConfigured: false, createdAt: '', updatedAt: '' }], page: 0, size: 20, totalElements: 1 })
  vi.spyOn(ElMessageBox, 'confirm').mockImplementation(() => Promise.resolve('confirm') as ReturnType<typeof ElMessageBox.confirm>)
  vi.mocked(media.scan).mockResolvedValue({ sourceId: 'local', parserProfileId: 'default', parserProfileName: '默认解析方案', parserRevision: 0, totalSongs: 1, matchedSongs: 0, needsReviewSongs: 1, skippedFiles: 0, scannedAt: '' })
  vi.mocked(media.songs).mockResolvedValue({ items: [{ id: 'song', relativePath: '未知.mkv', result: { title: null, artists: [], needsReview: true } } as never], page: 0, size: 20, totalElements: 1 })
  wrapper = mount(MediaLibraryView, { global: { plugins: [router], stubs: { teleport: true } } }); await flushPromises()
  await button('扫描歌曲').trigger('click'); await flushPromises()
  expect(media.scan).toHaveBeenCalledWith('local')
  expect(router.currentRoute.value.path).toBe('/library/media')
  await button('查看歌曲').trigger('click'); await flushPromises()
  expect(router.currentRoute.value.path).toBe('/library/songs')
  expect(router.currentRoute.value.query.sourceId).toBe('local')
})

const remote = { id: 'remote', name: '网盘歌曲', type: 'WEBDAV' as const, location: 'https://nas.example/dav/', username: 'reader', parserProfileId: 'default', passwordConfigured: true, createdAt: '', updatedAt: '' }
function task(status: 'RUNNING' | 'SUCCEEDED' | 'FAILED' | 'CANCELLED' = 'RUNNING') {
  return { id: 'job', sourceId: remote.id, status, phase: 'SCANNING' as const, directories: 5, entries: 300,
    totalSongs: 200, matchedSongs: 190, needsReviewSongs: 10, skippedFiles: 100, cancelRequested: false,
    error: status === 'FAILED' ? '目录读取失败，旧索引已保留' : null, createdAt: '', startedAt: '', finishedAt: null }
}
it('WebDAV 创建后台任务并展示进度，完成后不自动跳页', async () => {
  vi.useFakeTimers()
  try {
    vi.mocked(media.list).mockResolvedValue({ items: [remote], page: 0, size: 20, totalElements: 1 })
    vi.mocked(media.createTask).mockResolvedValue(task())
    vi.mocked(media.task).mockResolvedValue(task('SUCCEEDED'))
    vi.spyOn(ElMessageBox, 'confirm').mockImplementation(() => Promise.resolve('confirm') as ReturnType<typeof ElMessageBox.confirm>)
    wrapper = mount(MediaLibraryView, { global: { plugins: [router], stubs: { teleport: true } } }); await flushPromises()
    await button('扫描歌曲').trigger('click'); await flushPromises()
    expect(media.createTask).toHaveBeenCalledWith(remote.id)
    expect(wrapper.text()).toContain('已处理 5 个目录、300 个条目；200 首')
    expect(button('扫描歌曲').attributes('disabled')).toBeDefined()
    await vi.advanceTimersByTimeAsync(2000); await flushPromises()
    expect(wrapper.text()).toContain('扫描完成')
    expect(button('查看歌曲')).toBeDefined()
    expect(router.currentRoute.value.path).toBe('/library/media')
    wrapper.unmount()
    const calls = vi.mocked(media.task).mock.calls.length
    await vi.advanceTimersByTimeAsync(10000)
    expect(media.task).toHaveBeenCalledTimes(calls)
  } finally { vi.useRealTimers() }
})
it('重新进入恢复任务，支持取消并保留错误提示与重扫入口', async () => {
  vi.mocked(media.list).mockResolvedValue({ items: [remote], page: 0, size: 20, totalElements: 1 })
  vi.mocked(media.latestTask).mockResolvedValue(task())
  vi.mocked(media.cancelTask).mockResolvedValue(task('CANCELLED'))
  wrapper = mount(MediaLibraryView, { global: { plugins: [router], stubs: { teleport: true } } }); await flushPromises()
  expect(wrapper.text()).toContain('正在扫描目录')
  await button('取消扫描').trigger('click'); await flushPromises()
  expect(media.cancelTask).toHaveBeenCalledWith(remote.id, 'job')
  expect(wrapper.text()).toContain('已取消')
  vi.mocked(media.latestTask).mockResolvedValue(task('FAILED'))
  await button('刷新').trigger('click'); await flushPromises()
  expect(wrapper.text()).toContain('目录读取失败，旧索引已保留')
  expect(button('扫描歌曲').attributes('disabled')).toBeUndefined()
})
it('离开页面后忽略迟到的任务状态响应', async () => {
  let resolveTask!: (value: ReturnType<typeof task>) => void
  vi.mocked(media.list).mockResolvedValue({ items: [remote], page: 0, size: 20, totalElements: 1 })
  vi.mocked(media.latestTask).mockReturnValue(new Promise(resolve => { resolveTask = resolve }))
  wrapper = mount(MediaLibraryView, { global: { plugins: [router], stubs: { teleport: true } } }); await flushPromises()
  wrapper.unmount(); resolveTask(task()); await flushPromises()
  expect(media.task).not.toHaveBeenCalled()
})
it('创建任务前发出的最近任务请求不能覆盖新任务', async () => {
  let resolveLatest!: (value: undefined) => void
  vi.mocked(media.list).mockResolvedValue({ items: [remote], page: 0, size: 20, totalElements: 1 })
  vi.mocked(media.latestTask).mockReturnValue(new Promise(resolve => { resolveLatest = resolve }))
  vi.mocked(media.createTask).mockResolvedValue(task())
  vi.spyOn(ElMessageBox, 'confirm').mockImplementation(() => Promise.resolve('confirm') as ReturnType<typeof ElMessageBox.confirm>)
  wrapper = mount(MediaLibraryView, { global: { plugins: [router], stubs: { teleport: true } } }); await flushPromises()
  await button('扫描歌曲').trigger('click'); await flushPromises()
  resolveLatest(undefined); await flushPromises()
  expect(wrapper.text()).toContain('正在扫描目录')
  expect(wrapper.text()).toContain('200 首')
})
it('少量位置使用卡片且不显示筛选与分页表格', async () => {
  wrapper = mount(MediaLibraryView, { global: { plugins: [router], stubs: { teleport: true } } })
  await flushPromises()
  expect(media.list).toHaveBeenCalledWith(0, 100)
  expect(wrapper.find('table').exists()).toBe(false)
  expect(wrapper.find('form[aria-label="媒体来源筛选"]').exists()).toBe(false)
})
it('添加来源支持五段 cron 配置，六段表达式阻止提交', async () => {
  wrapper = mount(MediaLibraryView, { global: { plugins: [router], stubs: { teleport: true } } })
  await flushPromises(); await button('添加来源').trigger('click'); await flushPromises()
  const dialog = wrapper.findComponent(ElDialog)
  const inputs = dialog.findAllComponents(ElInput)
  await inputs[0]!.setValue('定时歌曲'); await selectFolder()
  await dialog.get('summary').trigger('click'); await dialog.get('.source-advanced input[type="checkbox"]').setValue(true); await flushPromises()
  await dialog.get('input[aria-label="扫描 Cron 表达式"]').setValue('0 0 3 * * *')
  await dialog.get('form').trigger('submit'); await flushPromises()
  expect(media.save).not.toHaveBeenCalled()
  expect(wrapper.text()).toContain('请输入五段 cron')
  await dialog.get('input[aria-label="扫描 Cron 表达式"]').setValue('0 3 * * *')
  await dialog.get('form').trigger('submit'); await flushPromises()
  expect(media.save).toHaveBeenCalledWith(expect.objectContaining({ scanEnabled: true, scanCron: '0 3 * * *' }), undefined)
})

async function selectFolder() {
  await button('选择文件夹').trigger('click'); await flushPromises()
  await wrapper.get('button[aria-label="进入 songs"]').trigger('click'); await flushPromises()
  await button('使用当前文件夹').trigger('click'); await flushPromises()
}
it('在来源内试验和选择方案，明确使用后再保存来源，WebDAV 输入全程保留', async () => {
  wrapper = mount({ template: '<RouterView />' }, { global: { plugins: [router], stubs: { teleport: true } } }); await flushPromises()
  await button('添加来源').trigger('click'); await flushPromises(); await button('WebDAV').trigger('click'); await flushPromises()
  const form = wrapper.findComponent(ElDialog)
  await form.get('input[placeholder="https://nas.example/dav/歌曲目录/"]').setValue('https://nas.example.invalid/dav/')
  await form.get('input[autocomplete="off"]').setValue('reader')
  await form.get('input[type="password"]').setValue('draft-secret')
  await form.get('input[placeholder="留空使用文件夹名称"]').setValue('收藏歌曲')
  await button('试验与调整').trigger('click'); await flushPromises()
  await vi.waitFor(() => expect(wrapper.find('input[aria-label="方案名称"]').exists()).toBe(true))
  expect(router.currentRoute.value.fullPath).toBe('/library/media')
  expect(wrapper.text()).toContain('保存修改会影响它们之后的扫描')
  await wrapper.findAll('button.profile-choice').find(b => b.text().includes('反向方案'))!.trigger('click'); await flushPromises()
  expect(media.save).not.toHaveBeenCalled()
  expect(parserProfiles.update).not.toHaveBeenCalled()
  await button('使用此方案并返回').trigger('click'); await flushPromises()
  expect(form.get('input[type="password"]').element).toHaveProperty('value', 'draft-secret')
  expect(form.get('input[autocomplete="off"]').element).toHaveProperty('value', 'reader')
  expect(form.get('input[placeholder="留空使用文件夹名称"]').element).toHaveProperty('value', '收藏歌曲')
  expect(form.get('select[aria-label="解析方案"]').element).toHaveProperty('value', 'reverse-profile')
  expect(media.save).not.toHaveBeenCalled()
  await form.get('form').trigger('submit'); await flushPromises()
  expect(media.save).toHaveBeenCalledWith(expect.objectContaining({ name: '收藏歌曲', parserProfileId: 'reverse-profile', password: 'draft-secret' }), undefined)
})
it('识别方案草稿在返回来源或关闭时受保护，拒绝放弃会留在原编辑位置', async () => {
  vi.spyOn(ElMessageBox, 'confirm').mockRejectedValue('cancel')
  wrapper = mount({ template: '<RouterView />' }, { global: { plugins: [router], stubs: { teleport: true } } }); await flushPromises()
  await button('添加来源').trigger('click'); await flushPromises()
  await button('试验与调整').trigger('click'); await flushPromises()
  await vi.waitFor(() => expect(wrapper.find('input[aria-label="方案名称"]').exists()).toBe(true))
  await wrapper.get('input[aria-label="方案名称"]').setValue('未保存的识别方案')
  expect(button('使用此方案并返回').attributes('disabled')).toBeDefined()
  await button('← 返回来源设置').trigger('click'); await flushPromises()
  expect(wrapper.get('input[aria-label="方案名称"]').element).toHaveProperty('value', '未保存的识别方案')
  const close = wrapper.findComponent(ElDialog).props('beforeClose') as (done: () => void) => Promise<void>
  const done = vi.fn(); await close(done); await flushPromises()
  expect(done).not.toHaveBeenCalled()
  expect(wrapper.text()).toContain('未保存草稿')
  await router.push('/library/songs'); await flushPromises()
  expect(router.currentRoute.value.path).toBe('/library/media')
  expect(media.save).not.toHaveBeenCalled()
})
it('自定义规则在来源工作区内打开，返回时刷新方案且保留来源草稿', async () => {
  wrapper = mount({ template: '<RouterView />' }, { global: { plugins: [router], stubs: { teleport: true } } }); await flushPromises()
  await button('添加来源').trigger('click'); await flushPromises()
  await wrapper.get('input[placeholder="留空使用文件夹名称"]').setValue('还在编辑的来源')
  await button('试验与调整').trigger('click'); await flushPromises()
  await vi.waitFor(() => expect(wrapper.find('.profiles-advanced > summary').exists()).toBe(true))
  await wrapper.get('.profiles-advanced > summary').trigger('click')
  await button('自定义规则').trigger('click'); await flushPromises()
  await vi.waitFor(() => expect(ruleLibrary.load).toHaveBeenCalledTimes(1))
  expect(ruleLibrary.load).toHaveBeenCalledTimes(1)
  expect(wrapper.text()).toContain('新增规则')
  const loads = vi.mocked(parserProfiles.load).mock.calls.length
  await button('← 返回识别方案').trigger('click'); await flushPromises()
  expect(parserProfiles.load).toHaveBeenCalledTimes(loads + 1)
  await button('← 返回来源设置').trigger('click'); await flushPromises()
  expect(wrapper.get('input[placeholder="留空使用文件夹名称"]').element).toHaveProperty('value', '还在编辑的来源')
  expect(router.currentRoute.value.fullPath).toBe('/library/media')
})
it('来源自身的未保存修改在取消、离开和刷新时受到保护', async () => {
  vi.spyOn(ElMessageBox, 'confirm').mockRejectedValue('cancel')
  wrapper = mount({ template: '<RouterView />' }, { global: { plugins: [router], stubs: { teleport: true } } }); await flushPromises()
  await button('添加来源').trigger('click'); await flushPromises()
  await wrapper.get('input[placeholder="留空使用文件夹名称"]').setValue('未保存的来源')
  await button('取消').trigger('click'); await flushPromises()
  expect(wrapper.findComponent(ElDialog).props('modelValue')).toBe(true)
  await router.push('/library/songs'); await flushPromises()
  expect(router.currentRoute.value.path).toBe('/library/media')
  const event = new Event('beforeunload', { cancelable: true }); window.dispatchEvent(event)
  expect(event.defaultPrevented).toBe(true)
  expect(wrapper.get('input[placeholder="留空使用文件夹名称"]').element).toHaveProperty('value', '未保存的来源')
})
it('下载位置标记可见，更换绑定明确确认原目录和新目录', async () => {
  vi.mocked(lx.status).mockResolvedValue({ target: { id: 'old', path: '/old-downloads' } } as never)
  vi.spyOn(ElMessageBox, 'confirm').mockResolvedValue('confirm' as never)
  wrapper = mount(MediaLibraryView, { global: { plugins: [router], stubs: { teleport: true } } }); await flushPromises()
  await button('添加来源').trigger('click'); await flushPromises(); await selectFolder()
  const form = wrapper.findComponent(ElDialog)
  await form.get('input[type="checkbox"]').setValue(true)
  await form.get('form').trigger('submit'); await flushPromises()
  expect(ElMessageBox.confirm).toHaveBeenCalledWith(expect.stringContaining('原保存位置：/old-downloads。新保存位置：/media/songs'), '更改下载保存位置', expect.anything())
  expect(media.save).toHaveBeenCalledWith(expect.objectContaining({ useForLxDownloads: true }), undefined)
})
it('编辑已绑定本地媒体库保留下载用途并显示卡片标记', async () => {
  vi.mocked(media.list).mockResolvedValue({ items: [{ id: 'bound', name: '下载曲库', type: 'LOCAL', location: '/downloads', username: '', parserProfileId: 'default', passwordConfigured: false, useForLxDownloads: true }], page: 0, size: 20, totalElements: 1 } as never)
  vi.mocked(lx.status).mockResolvedValue({ target: { id: 'bound', path: '/downloads' } } as never)
  wrapper = mount(MediaLibraryView, { global: { plugins: [router], stubs: { teleport: true } } }); await flushPromises()
  expect(wrapper.text()).toContain('下载保存位置')
  await wrapper.get('summary[aria-label="下载曲库的更多操作"]').trigger('click'); await button('编辑').trigger('click'); await flushPromises()
  const form = wrapper.findComponent(ElDialog); expect(form.get('input[type="checkbox"]').element).toHaveProperty('checked', true)
  await form.get('form').trigger('submit'); await flushPromises()
  expect(media.save).toHaveBeenCalledWith(expect.objectContaining({ useForLxDownloads: true }), 'bound')
})
it('目录加载失败禁止选择，重试后可浏览与自动命名', async () => {
  wrapper = mount(MediaLibraryView, { global: { plugins: [router], stubs: { teleport: true } } }); await flushPromises()
  await button('添加来源').trigger('click'); await flushPromises()
  vi.mocked(media.directories).mockRejectedValueOnce(new Error('目录不可读'))
  await button('选择文件夹').trigger('click'); await flushPromises()
  expect(wrapper.text()).toContain('目录不可读')
  expect(button('使用当前文件夹').attributes('disabled')).toBeDefined()
  await button('返回常用位置').trigger('click'); await flushPromises()
  await wrapper.get('button[aria-label="进入 songs"]').trigger('click'); await flushPromises()
  await button('使用当前文件夹').trigger('click'); await flushPromises()
  await wrapper.findComponent(ElDialog).get('form').trigger('submit'); await flushPromises()
  expect(media.save).toHaveBeenCalledWith(expect.objectContaining({ name: 'songs', location: '/media/songs' }), undefined)
})
it('从概览带 ?add=WEBDAV 进入时直接打开对应添加表单，并移除参数避免刷新重复弹出', async () => {
  await router.push('/library/media?add=WEBDAV')
  wrapper = mount(MediaLibraryView, { global: { plugins: [router], stubs: { teleport: true } } })
  await flushPromises()
  const form = wrapper.findComponent(ElDialog)
  expect(form.props('modelValue')).toBe(true)
  expect(form.text()).toContain('WebDAV 目录地址')
  expect(router.currentRoute.value.query.add).toBeUndefined()
  expect(router.currentRoute.value.path).toBe('/library/media')
})
