// @vitest-environment jsdom
import { flushPromises, mount, type VueWrapper } from '@vue/test-utils'
import { afterEach, beforeEach, expect, it, vi } from 'vitest'
import LocalFolderPicker from '../src/admin/modules/media-library/components/LocalFolderPicker.vue'
import { media } from '../src/admin/modules/media-library/api/media'
import type { DirectoryPage } from '../src/admin/modules/media-library/types'

vi.mock('../src/admin/modules/media-library/api/media', () => ({ media: { directories: vi.fn() } }))
let wrapper: VueWrapper
const locations = [
  { name: '媒体目录', path: '/media', mounted: true, readable: true, writable: false },
  { name: '下载目录', path: '/downloads', mounted: true, readable: true, writable: true },
  { name: '我的歌曲', path: '/mnt/我的歌曲', mounted: true, readable: true, writable: true },
  { name: '私有目录', path: '/private-mount', mounted: true, readable: false, writable: false },
]
function listing(path: string, names: string[] = [], hasMore = false): DirectoryPage {
  return { path, parent: path === '' ? null : path.slice(0, path.lastIndexOf('/')) || '/',
    items: names.map(name => ({ name, path: `${path}/${name}` })), hasMore,
    locations: path ? [] : locations, containerized: true }
}
function button(text: string) { return wrapper.findAll('button').find(item => item.text() === text)! }
async function open(initialPath = '', download = false) {
  wrapper = mount(LocalFolderPicker, { props: { initialPath, download, sources: [] }, global: { stubs: { teleport: true } } })
  await flushPromises()
}
beforeEach(() => {
  vi.mocked(media.directories).mockImplementation(async (path = '') => listing(path, path === '/media' ? ['中文歌曲', '其他歌曲'] : []))
})
afterEach(() => { wrapper?.unmount(); document.body.innerHTML = ''; vi.resetAllMocks(); vi.useRealTimers() })

it('优先打开媒体挂载并保留真实挂载快捷入口，区分只读与无权限', async () => {
  await open()
  expect(media.directories).toHaveBeenLastCalledWith('/media', 0, '')
  expect(wrapper.get('.folder-chosen').text()).toContain('/media')
  expect(wrapper.get('button[aria-label="前往媒体目录 /media"]').text()).toContain('只读')
  expect(wrapper.get('button[aria-label="前往私有目录 /private-mount"]').attributes('disabled')).toBeDefined()
  await wrapper.get('button[aria-label="前往我的歌曲 /mnt/我的歌曲"]').trigger('click'); await flushPromises()
  expect(wrapper.get('.folder-chosen').text()).toContain('/mnt/我的歌曲')
  expect(wrapper.findAll('.folder-place')).toHaveLength(4)
  await button('使用当前文件夹').trigger('click')
  expect(wrapper.emitted('select')).toEqual([['/mnt/我的歌曲']])
})

it('下载用途默认进入下载目录，编辑时保留原目录', async () => {
  await open('', true)
  expect(media.directories).toHaveBeenLastCalledWith('/downloads', 0, '')
  wrapper.unmount()
  await open('/mnt/我的歌曲', true)
  expect(media.directories).toHaveBeenLastCalledWith('/mnt/我的歌曲', 0, '')
})

it('窄屏快捷选择框可直接前往挂载目录，并保留权限说明', async () => {
  await open()
  const select = wrapper.get('select[aria-label="快速前往"]')
  expect(select.text()).toContain('媒体目录 · /media（只读）')
  expect(select.get('option[value="/private-mount"]').attributes('disabled')).toBeDefined()
  await select.setValue('/mnt/我的歌曲'); await flushPromises()
  expect(media.directories).toHaveBeenLastCalledWith('/mnt/我的歌曲', 0, '')
  expect(wrapper.get('.folder-chosen').text()).toContain('/mnt/我的歌曲')
})

it('单击文件夹整行进入，使用当前文件夹确认，不再显示单选框和打开按钮', async () => {
  await open()
  expect(wrapper.find('input[type="radio"]').exists()).toBe(false)
  expect(wrapper.findAll('button').some(item => item.text() === '打开')).toBe(false)
  const row = wrapper.get('button[aria-label="进入 中文歌曲"]')
  expect(row.attributes('type')).toBe('button')
  await row.trigger('click'); await flushPromises()
  expect(media.directories).toHaveBeenLastCalledWith('/media/中文歌曲', 0, '')
  expect(wrapper.get('.folder-chosen').text()).toContain('/media/中文歌曲')
  expect(wrapper.text()).toContain('可以直接选择当前文件夹')
  expect(wrapper.emitted('select')).toBeUndefined()
  await button('使用当前文件夹').trigger('click')
  expect(wrapper.emitted('select')).toEqual([['/media/中文歌曲']])
  await wrapper.get('nav[aria-label="目录位置"]').findAll('button')[2]!.trigger('click'); await flushPromises()
  await wrapper.get('button[aria-label="进入 其他歌曲"]').trigger('click'); await flushPromises()
  expect(media.directories).toHaveBeenLastCalledWith('/media/其他歌曲', 0, '')
})

it('路径可以粘贴后回车跳转，面包屑和上一级返回正确目录', async () => {
  await open()
  await wrapper.get('input[aria-label="目录路径"]').setValue(' /mnt/我的歌曲/国语 ')
  expect(button('使用当前文件夹').attributes('disabled')).toBeDefined()
  await wrapper.get('form[aria-label="跳转文件夹"]').trigger('submit'); await flushPromises()
  expect(media.directories).toHaveBeenLastCalledWith('/mnt/我的歌曲/国语', 0, '')
  expect(wrapper.get('nav [aria-current="location"]').text()).toBe('国语')
  await wrapper.get('button[aria-label="返回上一级"]').trigger('click'); await flushPromises()
  expect(media.directories).toHaveBeenLastCalledWith('/mnt/我的歌曲', 0, '')
  await button('根目录').trigger('click'); await flushPromises()
  expect(media.directories).toHaveBeenLastCalledWith('/', 0, '')
})

it('文件夹筛选由后端分页，清空快速输入不会恢复旧筛选', async () => {
  await open()
  vi.useFakeTimers()
  const input = wrapper.get('input[aria-label="筛选文件夹"]')
  await input.setValue('中文')
  await vi.advanceTimersByTimeAsync(100)
  await input.setValue('')
  await vi.advanceTimersByTimeAsync(250); await flushPromises()
  expect(media.directories).toHaveBeenLastCalledWith('/media', 0, '')
  vi.mocked(media.directories).mockResolvedValueOnce(listing('/media', ['中文歌曲'], true))
  await input.setValue('中文'); await vi.advanceTimersByTimeAsync(250); await flushPromises()
  expect(media.directories).toHaveBeenLastCalledWith('/media', 0, '中文')
  await button('下一页').trigger('click'); await flushPromises()
  expect(media.directories).toHaveBeenLastCalledWith('/media', 1, '中文')
})

it('失败保留已浏览位置和输入，支持重试及返回快捷位置', async () => {
  await open()
  vi.mocked(media.directories).mockRejectedValueOnce(new Error('目录不存在或没有读取权限'))
  await wrapper.get('input[aria-label="目录路径"]').setValue('/missing')
  await wrapper.get('form[aria-label="跳转文件夹"]').trigger('submit'); await flushPromises()
  expect(wrapper.text()).toContain('目录不存在或没有读取权限')
  expect(button('使用当前文件夹').attributes('disabled')).toBeDefined()
  expect(wrapper.get('input[aria-label="目录路径"]').element).toHaveProperty('value', '/missing')
  await button('重试').trigger('click'); await flushPromises()
  expect(wrapper.get('.folder-chosen').text()).toContain('/missing')
  await button('常用位置').trigger('click'); await flushPromises()
  expect(button('使用当前文件夹').attributes('disabled')).toBeDefined()
  expect(wrapper.findAll('.folder-place')).toHaveLength(4)
})

it('快速切换目录后忽略迟到响应，关闭后也不提交选择', async () => {
  await open()
  let resolveOld!: (value: DirectoryPage) => void
  vi.mocked(media.directories).mockReturnValueOnce(new Promise(resolve => { resolveOld = resolve }))
  await wrapper.get('button[aria-label="前往我的歌曲 /mnt/我的歌曲"]').trigger('click')
  await wrapper.get('button[aria-label="前往下载目录 /downloads"]').trigger('click'); await flushPromises()
  resolveOld(listing('/mnt/我的歌曲')); await flushPromises()
  expect(wrapper.get('.folder-chosen').text()).toContain('/downloads')
  await button('取消').trigger('click')
  expect(wrapper.emitted('close')).toHaveLength(1)
  expect(wrapper.emitted('select')).toBeUndefined()
})
