export type RuleType = 'BUILTIN' | 'TEMPLATE' | 'REGEX'
export interface FilenameRule {
  id: string
  name: string
  type: RuleType
  enabled: boolean
  expression: string | null
  builtinId: string | null
}
export interface BuiltinRule {
  id: string
  name: string
  type: 'TEMPLATE' | 'REGEX'
  expression: string
  enabledByDefault: boolean
  example: string
  description: string
}
export interface RuleConfiguration {
  revision: number
  updatedAt: string | null
  rules: FilenameRule[]
  builtinRules: BuiltinRule[]
}
export interface ProfileReference { id: string; name: string }
export interface RuleDefinition {
  id: string
  name: string
  type: RuleType
  expression: string | null
  builtinId: string | null
  referencedBy: ProfileReference[]
}
export interface ProfileEntry { ruleId: string; enabled: boolean }
export interface ParserProfile { id: string; name: string; entries: ProfileEntry[] }
export interface ParserWorkspace {
  revision: number
  updatedAt: string | null
  definitions: RuleDefinition[]
  profiles: ParserProfile[]
  builtinRules: BuiltinRule[]
  changedId: string | null
}
export interface RuleInput { name: string; type: 'TEMPLATE' | 'REGEX'; expression: string; builtinId: null }
export interface ProfileInput { name: string; entries: ProfileEntry[] }
export interface ProfilePreview { revision: number; profileId: string | null; profileName: string | null; preview: RulePreview }
export interface RulePreview {
  result: {
    originalFilename: string
    normalizedFilename: string | null
    title: string | null
    artists: string[]
    language: string
    versionType: string
    songCode: string | null
    extension: string | null
    qualityTags: string[]
    confidence: number
    parseStatus: 'SUCCESS' | 'PARTIAL' | 'NEEDS_REVIEW' | 'FAILED'
    parserRule: string
    unparsedTokens: string[]
    needsReview: boolean
    diagnostics: { code: string; detail: string; confidenceDelta: number }[]
  }
  selectedRule: { id: string; name: string; type: RuleType } | null
  matchTarget: string | null
  attempts: {
    id: string
    name: string
    type: RuleType
    status: 'DISABLED' | 'NO_MATCH' | 'PARTIAL' | 'AMBIGUOUS' | 'MATCHED'
    captures: Record<string, string>
    candidates: Record<string, string>[]
    detail: string
  }[]
}
