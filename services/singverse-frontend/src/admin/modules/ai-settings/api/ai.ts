import { authRequest } from '../../../core/auth/api'
import type { AiService, Feature, Provider, ServiceInput, ServiceKind, TestResult } from '../types'
const root = '/api/v1/ai'
const collection = (kind: ServiceKind) => `${root}/${kind === 'MODEL' ? 'models' : 'search-services'}`
export const ai = {
  providers: () => authRequest<Provider[]>(`${root}/providers`),
  services: (kind: ServiceKind) => authRequest<AiService[]>(collection(kind)),
  save: (kind: ServiceKind, input: ServiceInput, id?: string) => authRequest<AiService>(`${collection(kind)}${id ? '/' + encodeURIComponent(id) : ''}`, id ? 'PUT' : 'POST', input),
  remove: (kind: ServiceKind, id: string) => authRequest(`${collection(kind)}/${encodeURIComponent(id)}`, 'DELETE'),
  features: () => authRequest<Feature[]>(`${root}/features`),
  bind: (feature: Feature) => authRequest<Feature>(`${root}/features/${encodeURIComponent(feature.id)}`, 'PUT', feature),
  test: (service: AiService, mode: string, query: string) => authRequest<TestResult>(`${collection(service.kind)}/${encodeURIComponent(service.id)}/test`, 'POST', { mode, query }, 190000),
}
