export class RequestError extends Error {
  constructor(
    message: string,
    readonly kind: 'http' | 'network' | 'timeout' | 'invalid-response',
    readonly status?: number,
  ) {
    super(message)
    this.name = 'RequestError'
  }
}

// 同源 HTTP 是唯一传输边界，不依赖 Java SDK、Entity 或数据库。
export async function getJson(path: string, timeoutMs = 5000): Promise<unknown> {
  if (!path.startsWith('/') || path.startsWith('//')) {
    throw new Error('请求必须使用同源绝对路径')
  }
  if (path !== '/api' && !path.startsWith('/api/')) {
    throw new Error('接口请求必须使用 /api 前缀')
  }
  const controller = new AbortController()
  const timer = setTimeout(() => controller.abort(), timeoutMs)
  try {
    const response = await fetch(path, {
      headers: { Accept: 'application/json' },
      credentials: 'same-origin',
      cache: 'no-store',
      redirect: 'error',
      signal: controller.signal,
    })
    if (!response.ok) {
      throw new RequestError(`服务返回 HTTP ${response.status}`, 'http', response.status)
    }
    const contentType = response.headers.get('content-type')?.split(';')[0]?.trim()
    if (contentType !== 'application/json') {
      throw new RequestError('服务未返回有效的 JSON 响应', 'invalid-response')
    }
    try {
      return await response.json()
    } catch {
      if (controller.signal.aborted) throw new RequestError('连接超时，请稍后重试', 'timeout')
      throw new RequestError('服务响应格式异常', 'invalid-response')
    }
  } catch (error) {
    if (error instanceof RequestError) throw error
    if (controller.signal.aborted) throw new RequestError('连接超时，请稍后重试', 'timeout')
    throw new RequestError('无法连接服务，请检查 NAS 服务与网络', 'network')
  } finally {
    clearTimeout(timer)
  }
}
