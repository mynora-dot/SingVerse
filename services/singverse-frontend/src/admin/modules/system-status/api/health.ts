import { getJson, RequestError } from '../../../shared/http/client'

export interface ServiceHealth {
  status: 'UP' | 'DOWN' | 'OUT_OF_SERVICE' | 'UNKNOWN'
}

// 当前沿用既有运维端点；未来业务接口统一放在 /api/v1。
export async function getServiceHealth(): Promise<ServiceHealth> {
  const data = await getJson('/api/health')
  if (
    typeof data !== 'object' ||
    data === null ||
    !('status' in data) ||
    typeof data.status !== 'string' ||
    !['UP', 'DOWN', 'OUT_OF_SERVICE', 'UNKNOWN'].includes(data.status)
  ) {
    throw new RequestError('健康检查响应不符合约定', 'invalid-response')
  }
  return { status: data.status as ServiceHealth['status'] }
}
