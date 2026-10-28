import type { ParserWorkspace, ProfileEntry, RuleDefinition, RulePreview } from './types'

export function expressionFor(rule: RuleDefinition | undefined, workspace: ParserWorkspace | null): string {
  return rule?.expression ?? workspace?.builtinRules.find(p => p.id === rule?.builtinId)?.expression ?? ''
}
export function copyValue<T>(value: T): T { return JSON.parse(JSON.stringify(value)) as T }
export function errorMessage(error: unknown): string { return error instanceof Error ? error.message : '请求失败，请重试' }

export interface FormatItem { id: string; name: string; example: string; description: string }

export function formatItems(workspace: ParserWorkspace | null): FormatItem[] {
  if (!workspace) return []
  return workspace.builtinRules.flatMap(preset => {
    const definition = workspace.definitions.find(d => d.type === 'BUILTIN' && d.builtinId === preset.id)
    return definition ? [{ id: definition.id, name: definition.name, example: preset.example, description: preset.description }] : []
  })
}

export function attemptFor(preview: RulePreview | null, id: string) {
  return preview?.attempts.find(attempt => attempt.id === id)
}

export function candidateEntries(workspace: ParserWorkspace): ProfileEntry[] {
  return workspace.definitions.filter(d => d.type === 'BUILTIN').map(d => ({ ruleId: d.id, enabled: true }))
}
