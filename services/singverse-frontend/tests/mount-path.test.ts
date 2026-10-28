// @vitest-environment jsdom
import { expect, it } from 'vitest'
import { resolveAdminBasePath } from '../src/admin/core/hosting/mount-path'

it.each([
  ['https://nas.example/admin/#/overview', '/admin/'],
  ['https://nas.example/admin/index.html#/system', '/admin/'],
  ['https://nas.example/#/overview', '/'],
  ['https://nas.example/index.html#/system', '/'],
])('按实际访问地址 %s 确定管理端挂载路径', (url, expected) => {
  expect(resolveAdminBasePath(url)).toBe(expected)
})
