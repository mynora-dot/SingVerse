// @vitest-environment jsdom
import { flushPromises, mount, type VueWrapper } from '@vue/test-utils'
import { afterEach, beforeEach, expect, it, vi } from 'vitest'
import PartyApp from '../src/party/PartyApp.vue'
import { request } from '../src/party/api'
vi.mock('../src/party/api', () => ({ request: vi.fn(), requestId: () => 'stable-request', PartyHttpError: class extends Error { status = 401 } }))
let wrapper: VueWrapper | undefined
const snapshot = { id: 'party', revision: 1, actorId: 'guest', host: false, status: 'ACTIVE', acceptingRequests: true, queue: [] }
beforeEach(() => { sessionStorage.clear(); history.replaceState(null, '', '/party/'); vi.mocked(request).mockReset(); vi.mocked(request).mockImplementation(async (path: string) => { if (path.includes('snapshot')) return structuredClone(snapshot); if (path.includes('classification-tags')) return []; if (path.includes('library/songs')) return { items: [{ id: 'song', title: '测试歌曲', artists: ['测试歌手'], version: 'UNKNOWN' }], totalElements: 1 }; return {} }) })
afterEach(() => { wrapper?.unmount(); vi.restoreAllMocks() })
it('二维码预览不自动加入，确认后才创建参与身份', async () => {
  history.replaceState(null, '', '/party/#invite=invitation')
  vi.mocked(request).mockImplementation(async (path: string) => { if (path === 'invitations/preview') return { sessionId: 'party', deviceName: '客厅电视' }; if (path === 'join') return { token: 'participant-token', sessionId: 'party', name: '亲友' }; if (path.includes('snapshot')) return snapshot; if (path.includes('classification-tags')) return []; return { items: [], totalElements: 0 } })
  wrapper = mount(PartyApp); await flushPromises()
  expect(wrapper.text()).toContain('客厅电视'); expect(vi.mocked(request).mock.calls.some(c => c[0] === 'join')).toBe(false)
  await wrapper.get('#nickname').setValue('亲友'); await wrapper.findAll('button').find(b => b.text() === '确认加入这场聚会')!.trigger('click'); await flushPromises()
  expect(request).toHaveBeenCalledWith('join', 'POST', { invitation: 'invitation', name: '亲友' })
  expect(location.hash).toBe(''); expect(sessionStorage.getItem('singverse-party')).toContain('participant-token')
})
it('暂停接单禁用点歌，访客遥控不显示主持操作', async () => {
  sessionStorage.setItem('singverse-party', JSON.stringify({ token: 'token', sessionId: 'party', name: '亲友' })); snapshot.acceptingRequests = false
  wrapper = mount(PartyApp); await flushPromises()
  expect(wrapper.get('.add').attributes('disabled')).toBeDefined()
  await wrapper.findAll('nav button').find(b => b.text() === '遥控')!.trigger('click')
  expect(wrapper.text()).toContain('遥控需要主人'); expect(wrapper.find('.remote-grid').exists()).toBe(false)
  snapshot.acceptingRequests = true
})
it('点歌结果未知时保留原请求 ID，重试不会另发一次点歌', async () => {
  sessionStorage.setItem('singverse-party', JSON.stringify({ token: 'token', sessionId: 'party', name: '亲友' }))
  wrapper = mount(PartyApp); await flushPromises()
  const implementation = vi.mocked(request).getMockImplementation()!
  vi.mocked(request).mockImplementation(async (...args) => { if (args[0].includes('commands')) throw new Error('连接中断'); return implementation(...args) })
  await wrapper.get('.add').trigger('click'); await flushPromises()
  const first = vi.mocked(request).mock.calls.find(c => c[0].includes('commands'))!
  expect(first[2]).toMatchObject({ requestId: 'stable-request', songId: 'song', action: 'ADD' })
  await wrapper.findAll('button').find(b => b.text() === '重试原请求')!.trigger('click'); await flushPromises()
  const commands = vi.mocked(request).mock.calls.filter(c => c[0].includes('commands'))
  expect(commands).toHaveLength(2); expect(commands[1]![2]).toEqual(first[2])
})
it('退出仅清理当前参与身份，不结束聚会或撤回歌曲', async () => {
  sessionStorage.setItem('singverse-party', JSON.stringify({ token: 'token', sessionId: 'party', name: '亲友' }))
  wrapper = mount(PartyApp); await flushPromises()
  await wrapper.findAll('button').find(b => b.text() === '离开本场')!.trigger('click')
  expect(sessionStorage.getItem('singverse-party')).toBeNull(); expect(vi.mocked(request).mock.calls.some(c => c[0].includes('commands'))).toBe(false)
})
