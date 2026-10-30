export type ServiceKind = 'MODEL' | 'SEARCH'
export interface Provider { id: string; name: string; kind: ServiceKind; protocol: string; endpoint: string; outputMode: string }
export interface AiService {
  id: string; kind: ServiceKind; name: string; provider: string; protocol: string; endpoint: string; model: string
  outputMode: string; hasKey: boolean; enabled: boolean; timeoutSeconds: number; maxResults: number; revision: number
}
export interface ServiceInput {
  name: string; provider: string; protocol: string; endpoint: string; model: string; outputMode: string
  apiKey: string; clearKey: boolean; enabled: boolean; timeoutSeconds: number; maxResults: number; revision: number
}
export interface Feature { id: string; modelServiceId: string | null; searchServiceId: string | null; enabled: boolean; intervalSeconds: number; revision: number; displayMode?: 'PET' | 'CHAT'; petId?: string }
export interface TestResult { success: boolean; message: string; elapsedMs: number; results: { title: string; url: string; snippet: string }[] }
