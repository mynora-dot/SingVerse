import type { ParserWorkspace, RulePreview } from '../../src/admin/modules/filename-parser/types'
export function workspace(): ParserWorkspace {
  return {
    revision: 0, updatedAt: null, changedId: null,
    definitions: [
      { id: 'builtin-book', name: '书名号', type: 'BUILTIN', expression: null, builtinId: 'book', referencedBy: [] },
      { id: 'builtin-hyphen', name: '歌手-歌名', type: 'BUILTIN', expression: null, builtinId: 'hyphen', referencedBy: [] },
      { id: 'forward', name: '正向', type: 'TEMPLATE', expression: '{artist}-{title}', builtinId: null, referencedBy: [{ id: 'default', name: '默认解析方案' }] },
      { id: 'reverse', name: '反向', type: 'TEMPLATE', expression: '{title}-{artist}', builtinId: null, referencedBy: [{ id: 'reverse-profile', name: '反向方案' }] },
    ],
    profiles: [
      { id: 'default', name: '默认解析方案', entries: [{ ruleId: 'forward', enabled: true }] },
      { id: 'reverse-profile', name: '反向方案', entries: [{ ruleId: 'reverse', enabled: true }] },
    ],
    builtinRules: [
      { id: 'book', name: '书名号', type: 'TEMPLATE', expression: '{artist}《{title}》', enabledByDefault: true, example: '周杰伦《晴天》.mkv', description: '明确结构' },
      { id: 'hyphen', name: '歌手-歌名', type: 'TEMPLATE', expression: '{artist}-{title}', enabledByDefault: true, example: '周杰伦-晴天.mkv', description: '按模板指定字段顺序，匹配并不核实歌手身份' },
    ],
  }
}
export function preview(): RulePreview {
  return { result: { originalFilename: '晴天-周杰伦.mkv', normalizedFilename: '晴天-周杰伦.mkv', title: '晴天', artists: ['周杰伦'], language: 'UNKNOWN', versionType: 'UNKNOWN', songCode: null, extension: 'mkv', qualityTags: [], confidence: .9, parseStatus: 'SUCCESS', parserRule: 'CUSTOM_TEMPLATE', unparsedTokens: [], needsReview: false, diagnostics: [] }, selectedRule: { id: 'reverse', name: '反向', type: 'TEMPLATE' }, matchTarget: '晴天-周杰伦', attempts: [{ id: 'reverse', name: '反向', type: 'TEMPLATE', status: 'MATCHED', captures: { artist: '周杰伦', title: '晴天' }, candidates: [], detail: '完整命中' }, { id: 'builtin-hyphen', name: '歌手-歌名', type: 'BUILTIN', status: 'MATCHED', captures: { artist: '周杰伦', title: '晴天' }, candidates: [], detail: '完整捕获歌手和歌名' }, { id: 'builtin-book', name: '书名号', type: 'BUILTIN', status: 'NO_MATCH', captures: {}, candidates: [], detail: '未完整匹配名称或字段不符合约束' }] }
}
