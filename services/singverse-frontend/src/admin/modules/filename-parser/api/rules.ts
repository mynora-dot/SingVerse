import { authRequest } from '../../../core/auth/api'
import type { FilenameRule, RuleConfiguration, RulePreview } from '../types'
import type { ParserWorkspace, RuleInput, ProfileInput, ProfileEntry, ProfilePreview } from '../types'

const base = '/api/v1/filename-parser'
export const filenameRules = {
  load: () => authRequest<RuleConfiguration>(`${base}/rules`),
  save: (revision: number, rules: FilenameRule[]) => authRequest<RuleConfiguration>(`${base}/rules`, 'PUT', { revision, rules }),
  preview: (filename: string, rules: FilenameRule[]) => authRequest<RulePreview>(`${base}/preview`, 'POST', { filename, rules }),
}

export const ruleLibrary = {
  load: () => authRequest<ParserWorkspace>(`${base}/rule-definitions`),
  create: (revision: number, definition: RuleInput) => authRequest<ParserWorkspace>(`${base}/rule-definitions`, 'POST', { revision, definition }),
  update: (id: string, revision: number, definition: RuleInput) => authRequest<ParserWorkspace>(`${base}/rule-definitions/${encodeURIComponent(id)}`, 'PUT', { revision, definition }),
  remove: (id: string, revision: number) => authRequest<ParserWorkspace>(`${base}/rule-definitions/${encodeURIComponent(id)}?revision=${revision}`, 'DELETE'),
}
export const parserProfiles = {
  load: () => authRequest<ParserWorkspace>(`${base}/profiles`),
  create: (revision: number, profile: ProfileInput) => authRequest<ParserWorkspace>(`${base}/profiles`, 'POST', { revision, profile }),
  update: (id: string, revision: number, profile: ProfileInput) => authRequest<ParserWorkspace>(`${base}/profiles/${encodeURIComponent(id)}`, 'PUT', { revision, profile }),
  remove: (id: string, revision: number) => authRequest<ParserWorkspace>(`${base}/profiles/${encodeURIComponent(id)}?revision=${revision}`, 'DELETE'),
  preview: (filename: string, revision: number, entries: ProfileEntry[]) => authRequest<ProfilePreview>(`${base}/profiles/preview`, 'POST', { filename, revision, entries }),
}
