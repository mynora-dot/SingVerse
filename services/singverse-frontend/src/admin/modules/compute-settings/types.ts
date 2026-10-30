export type NodeMode = 'AUTO' | 'MANUAL' | 'DISABLED'
export interface PathMapping { localRoot: string; remoteRoot: string }
export interface NodeSettings { mode: NodeMode; endpoint: string; hasToken: boolean; mappings: PathMapping[]; automaticEndpoint: string; automaticHasToken: boolean; revision: number }
export interface NodeInput { mode: NodeMode; endpoint: string; token: string; clearToken: boolean; mappings: PathMapping[]; revision: number }
export interface NodeCheck { status: 'AVAILABLE' | 'DISABLED' | 'MISSING_TOKEN' | 'AUTH_FAILED' | 'UNAVAILABLE' | 'INCOMPATIBLE'; message: string; capabilities: string[]; elapsedMs: number }
