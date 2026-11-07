import { expect, it, vi } from 'vitest'
import { requestId } from '../src/shared/request-id'

it('LAN HTTP 缺少 randomUUID 时仍生成符合服务端契约的随机 UUID v4', () => {
  const original = Object.getOwnPropertyDescriptor(crypto, 'randomUUID')
  Object.defineProperty(crypto, 'randomUUID', { configurable: true, value: undefined })
  try {
    const values = Array.from({ length: 100 }, requestId)
    expect(new Set(values).size).toBe(100)
    for (const value of values) expect(value).toMatch(/^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/)
  } finally { if (original) Object.defineProperty(crypto, 'randomUUID', original); else Reflect.deleteProperty(crypto, 'randomUUID'); vi.restoreAllMocks() }
})
