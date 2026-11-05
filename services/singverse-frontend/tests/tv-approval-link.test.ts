import { expect, it } from 'vitest'
import { readApprovalLink } from '../src/admin/modules/tv-devices/approval-link'

it('accepts a public approval reference and preserves leading zeroes', () => {
  expect(readApprovalLink('#/system/tv-devices?authorization=abc-123&code=012345')).toEqual({ id: 'abc-123', code: '012345' })
})
it('ignores malformed approval data or another route', () => {
  for (const hash of ['#/system/tv-devices', '#/other?authorization=abc&code=012345', '#/system/tv-devices?authorization=x%2Fapprove&code=012345', '#/system/tv-devices?authorization=abc&code=12345', '#/system/tv-devices?authorization=abc&code=12ab56']) expect(readApprovalLink(hash)).toBeNull()
})
