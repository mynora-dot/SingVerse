// @vitest-environment jsdom
import { flushPromises, mount, type VueWrapper } from '@vue/test-utils'
import { beforeEach, afterEach, expect, it, vi } from 'vitest'
import { createMemoryHistory, createRouter } from 'vue-router'
import { ElMessageBox } from 'element-plus'
import ClassificationView from '../src/admin/modules/classification/views/ClassificationView.vue'
import ClassificationEditor from '../src/admin/modules/classification/components/ClassificationEditor.vue'
import { classification } from '../src/admin/modules/classification/api/classification'
import type { Task } from '../src/admin/modules/classification/types'
vi.mock('../src/admin/modules/classification/api/classification', () => ({ classification: Object.fromEntries(['settings', 'saveSettings', 'test', 'tags', 'saveTag', 'state', 'change', 'history', 'undo', 'preview', 'start', 'tasks', 'task', 'control', 'retry', 'apply'].map(name => [name, vi.fn()])) }))
let wrapper: VueWrapper
const settings = { enabled: true, endpoint: 'https://example.test/v1/chat/completions', model: 'fixture', hasKey: true, timeoutSeconds: 60, intervalSeconds: 3, revision: 1 }
const tags = [{ id: 'genre-0', dimension: 'GENRE' as const, name: '流行', aliases: [], active: true, revision: 1, count: 1 }, { id: 'custom-0', dimension: 'CUSTOM' as const, name: '家庭常唱', aliases: [], active: true, revision: 1, count: 0 }]
const input = { songId: 'song-1', title: '歌曲', artists: ['歌手'], album: null, releaseDate: null, language: 'UNKNOWN', version: 'UNKNOWN', qualityTags: [] }
function task(status = 'RUNNING'): Task { return { id: 'task-1', status, dimensions: ['GENRE'], autoDimensions: [], model: 'fixture', total: 1, completed: 1, counts: { PENDING: 0, PROCESSING: 0, REVIEW: 1, APPLIED: 0, FAILED: 0, UNCHANGED: 0, CANCELLED: 0 }, items: [{ id: 'item-1', songId: 'song-1', status: 'REVIEW', input, revision: 0, suggestion: { dimensions: { GENRE: ['genre-0'] }, reasons: { GENRE: '根据已有信息推断' }, newTagSuggestions: ['新风格'] }, error: null, current: { songId: 'song-1', revision: 0, dimensions: {} } }], page: 0, size: 20, totalElements: 1, createdAt: '2026-10-28T00:00:00Z', updatedAt: '2026-10-28T00:00:00Z' } }
function button(text: string) { return wrapper.findAll('button').find(b => b.text() === text)! }
async function page(path = '/library/classification?songIds=song-1') {
  const router = createRouter({ history: createMemoryHistory(), routes: [{ path: '/library/classification', component: ClassificationView }, { path: '/library/songs', component: { template: '<div>songs</div>' } }] }); await router.push(path)
  wrapper = mount({ template: '<RouterView />' }, { global: { plugins: [router], stubs: { teleport: true } } }); await flushPromises(); return router
}
beforeEach(() => {
  vi.mocked(classification.settings).mockResolvedValue(settings)
  vi.mocked(classification.tags).mockResolvedValue(tags)
  vi.mocked(classification.tasks).mockResolvedValue([])
  vi.mocked(classification.task).mockResolvedValue(task())
  vi.mocked(classification.state).mockResolvedValue({ songId: 'song-1', revision: 0, dimensions: {} })
  vi.mocked(classification.history).mockResolvedValue([])
  vi.mocked(classification.preview).mockResolvedValue({ items: [input], total: 1, model: settings })
})
afterEach(() => { wrapper?.unmount(); vi.clearAllMocks(); vi.restoreAllMocks(); document.body.innerHTML = '' })
it('任务默认不自动应用，先预览真实输入，确认才启动', async () => {
  vi.mocked(classification.start).mockResolvedValue(task())
  await page(); await button('预览输入并启动').trigger('click'); await flushPromises()
  expect(classification.preview).toHaveBeenCalledWith(expect.objectContaining({ songIds: ['song-1'], autoDimensions: [] }))
  expect(classification.start).not.toHaveBeenCalled()
  expect(wrapper.text()).toContain('不会发送媒体、路径')
  await button('确认启动').trigger('click'); await flushPromises()
  expect(classification.start).toHaveBeenCalledWith(expect.objectContaining({ autoDimensions: [] }))
  expect(wrapper.text()).toContain('待审核 1')
  expect(classification.apply).not.toHaveBeenCalled()
})
it('刷新指定任务恢复状态，暂停后显示继续，终止说明不会撤销写入', async () => {
  vi.mocked(classification.task).mockResolvedValue(task('PAUSED'))
  await page('/library/classification?task=task-1')
  expect(button('继续')).toBeDefined(); expect(button('暂停')).toBeUndefined()
  vi.spyOn(ElMessageBox, 'confirm').mockResolvedValue('confirm' as Awaited<ReturnType<typeof ElMessageBox.confirm>>)
  vi.mocked(classification.control).mockResolvedValue(task('CANCELLED'))
  await button('终止').trigger('click'); await flushPromises()
  expect(ElMessageBox.confirm).toHaveBeenCalledWith(expect.stringContaining('不会自动撤销'), expect.any(String), expect.any(Object))
  expect(classification.control).toHaveBeenCalledWith('task-1', 'cancel')
})
it('审核显示来源和新标签建议，选择后确认才能写入', async () => {
  await page('/library/classification?task=task-1')
  await button('审核建议').trigger('click'); await flushPromises()
  expect(wrapper.text()).toContain('根据已有信息推断')
  expect(wrapper.text()).toContain('新标签建议（未发布）')
  expect(classification.apply).not.toHaveBeenCalled()
  await button('确认所选维度').trigger('click'); await flushPromises()
  expect(classification.apply).toHaveBeenCalledWith('task-1', 'item-1', expect.any(String), 0, ['GENRE'])
})
it('重新生成与应用分开，失败显示真实错误', async () => {
  vi.spyOn(ElMessageBox, 'confirm').mockResolvedValue('confirm' as Awaited<ReturnType<typeof ElMessageBox.confirm>>)
  vi.mocked(classification.retry).mockRejectedValue(new Error('模型已关闭'))
  await page('/library/classification?task=task-1'); await button('重新生成').trigger('click'); await flushPromises()
  expect(wrapper.text()).toContain('模型已关闭')
  expect(classification.apply).not.toHaveBeenCalled()
})
it('人工批量整理只提交勾选维度并保护其余分类', async () => {
  vi.mocked(classification.change).mockResolvedValue({ songId: 'song-1', revision: 1, dimensions: { GENRE: { tagIds: ['genre-0'], origin: 'MANUAL', locked: true, confirmed: true } } })
  wrapper = mount(ClassificationEditor, { props: { songIds: ['song-1'] }, global: { stubs: { teleport: true } } }); await flushPromises()
  const label = wrapper.findAll('.edit-dimension').find(d => d.text().includes('修改风格'))!
  await label.findAll('input[type=checkbox]')[0]!.setValue(true)
  await label.findAll('input[type=checkbox]')[1]!.setValue(true)
  await label.findAll('input[type=checkbox]')[2]!.setValue(true)
  await button('保存所选维度').trigger('click'); await flushPromises()
  expect(classification.change).toHaveBeenCalledWith('song-1', expect.objectContaining({ revision: 0, dimensions: { GENRE: ['genre-0'] }, lockedDimensions: ['GENRE'] }))
  expect(wrapper.emitted('changed')).toHaveLength(1)
})
it('词表提供分类真实数量和停用状态，不自动发布新标签建议', async () => {
  await page(); await button('分类词表').trigger('click'); await flushPromises()
  expect(wrapper.text()).toContain('家庭常唱')
  expect(wrapper.text()).toContain('流行')
  expect(classification.saveTag).not.toHaveBeenCalled()
})
