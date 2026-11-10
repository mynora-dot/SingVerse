// @vitest-environment jsdom
import { mount } from '@vue/test-utils'
import { expect, it } from 'vitest'
import MetadataCover from '../src/admin/modules/metadata/components/MetadataCover.vue'
import { coverValue } from '../src/admin/modules/metadata/cover-quality'

it('区分平台未提供图片、加载和失败，重试不会冒充没有封面', async () => {
  const wrapper = mount(MetadataCover, { props: { alt: '歌曲封面', src: null, retryable: true } })
  expect(wrapper.text()).toContain('平台未提供封面')
  await wrapper.setProps({ src: '/api/v1/metadata/candidates/cover/cover' })
  expect(wrapper.text()).toContain('加载封面')
  await wrapper.get('img').trigger('error')
  expect(wrapper.text()).toContain('封面加载失败')
  expect(wrapper.text()).not.toContain('平台未提供封面')
  await wrapper.get('button').trigger('click')
  expect(wrapper.get('img').attributes('src')).toContain('retry=0-1')
  Object.defineProperty(wrapper.get('img').element, 'naturalWidth', { value: 5906 })
  Object.defineProperty(wrapper.get('img').element, 'naturalHeight', { value: 5906 })
  await wrapper.get('img').trigger('load')
  expect(wrapper.emitted('loaded')).toEqual([[{ width: 5906, height: 5906 }]])
  expect(wrapper.find('.cover-state').exists()).toBe(false)
  wrapper.unmount()
})

it.each([
  ['NETEASE', 'https://p2.music.126.net/cover.jpg?param=640y640', 'https://p2.music.126.net/cover.jpg', 'https://p2.music.126.net/cover.jpg?param=2048y2048'],
  ['KUGOU', 'https://imge.kugou.com/stdmusic/400/cover.jpg', 'https://imge.kugou.com/stdmusic/0/cover.jpg', 'https://imge.kugou.com/stdmusic/2048/cover.jpg'],
  ['KUWO', 'https://img1.kuwo.cn/star/albumcover/120/a.jpg', 'https://img1.kuwo.cn/star/albumcover/0/a.jpg', 'https://img1.kuwo.cn/star/albumcover/2048/a.jpg'],
  ['QISHUI', 'https://p3-luna.douyinpic.com/img/a~tplv-test-resize:640:640.png', 'https://p3-luna.douyinpic.com/img/a~tplv-test-image.image', 'https://p3-luna.douyinpic.com/img/a~tplv-test-resize:2048:2048.png'],
  ['QQ', 'https://y.gtimg.cn/music/photo_new/T002R300x300M000album.jpg', 'https://y.gtimg.cn/music/photo_new/T002M000album.jpg', 'https://y.gtimg.cn/music/photo_new/T002R1500x1500M000album.jpg'],
] as const)('%s 原图与高清草稿值使用真实 CDN 版本，避免保存缩略图', (provider, raw, original, high) => {
  expect(coverValue(provider, raw, 'ORIGINAL')).toBe(original)
  expect(coverValue(provider, raw, 'HIGH')).toBe(high)
})
