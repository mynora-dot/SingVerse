import { authRequest } from '../../core/auth/api'
import type { NodeSettings, NodeInput, NodeCheck, NodeDiscovery, NodeDiscoveryInput } from './types'
const base = '/api/v1/compute-node'
export const computeNode = {
  get: () => authRequest<NodeSettings>(base),
  save: (input: NodeInput) => authRequest<NodeSettings>(base, 'PUT', input),
  test: (input: NodeInput) => authRequest<NodeCheck>(base + '/test', 'POST', input),
  discover: (input: NodeDiscoveryInput = {}) => authRequest<NodeDiscovery>(base + '/discovery', 'POST', input, 45000),
  pair: (discoveryId: string, revision: number) => authRequest<NodeSettings>(base + '/pair', 'POST', { discoveryId, revision }, 45000),
}
