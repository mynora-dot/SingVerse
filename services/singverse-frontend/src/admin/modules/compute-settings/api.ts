import { authRequest } from '../../core/auth/api'
import type { NodeSettings, NodeInput, NodeCheck } from './types'
const base = '/api/v1/compute-node'
export const computeNode = {
  get: () => authRequest<NodeSettings>(base),
  save: (input: NodeInput) => authRequest<NodeSettings>(base, 'PUT', input),
  test: (input: NodeInput) => authRequest<NodeCheck>(base + '/test', 'POST', input),
}
