// @vitest-environment jsdom
import { mount } from '@vue/test-utils'
import { expect, it } from 'vitest'
import AssistantPetImage from '../src/admin/modules/ai-settings/components/AssistantPetImage.vue'

it('动作图加载成功才替换静态形象，失败回退静态图', async () => {
  const pet = mount(AssistantPetImage, { props: { src: '/pet.png', animationSrc: '/idle.png' } })
  expect(pet.classes()).not.toContain('sprite-ready')
  await pet.get('.pet-sheet').trigger('load')
  expect(pet.classes()).toContain('sprite-ready')
  await pet.get('.pet-sheet').trigger('error')
  expect(pet.classes()).not.toContain('sprite-ready')
  expect(pet.get('.pet-static').attributes('src')).toBe('/pet.png')
  pet.unmount()
})

it('切换形象重置加载状态，静态预览不加载动作图，暂停不移除当前帧', async () => {
  const pet = mount(AssistantPetImage, { props: { src: '/pet.png', animationSrc: '/idle.png' } })
  await pet.get('.pet-sheet').trigger('load')
  await pet.setProps({ paused: true })
  expect(pet.classes()).toContain('paused')
  expect(pet.classes()).toContain('sprite-ready')
  await pet.setProps({ src: '/new.png', animationSrc: '/new-idle.png' })
  expect(pet.classes()).not.toContain('sprite-ready')
  expect(pet.get('.pet-sheet').attributes('src')).toBe('/new-idle.png')
  await pet.setProps({ still: true })
  expect(pet.find('.pet-sheet').exists()).toBe(false)
  expect(pet.get('.pet-static').attributes('src')).toBe('/new.png')
  pet.unmount()
})
