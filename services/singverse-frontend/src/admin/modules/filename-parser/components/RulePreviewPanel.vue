<script setup lang="ts">
import { ElAlert, ElTable, ElTableColumn } from 'element-plus'
import type { RulePreview } from '../types'

defineProps<{ preview: RulePreview | null; stale: boolean }>()
const statuses: Record<string, string> = { DISABLED: '已停用', NO_MATCH: '未匹配', PARTIAL: '部分捕获', AMBIGUOUS: '存在歧义', MATCHED: '完整匹配' }
</script>

<template>
        <div v-if="preview" class="preview-result" :class="{ 'preview-stale': stale }">
          <ElAlert :title="preview.selectedRule ? `命中规则：${preview.selectedRule.name}` : '未完整命中：请检查规则和部分捕获'" :type="preview.selectedRule ? 'success' : 'warning'" :closable="false" show-icon />
          <dl class="parsed-fields"><div><dt>歌手</dt><dd data-testid="parsed-artists">{{ preview.result.artists.join('、') || '未确定' }}</dd></div><div><dt>歌名</dt><dd data-testid="parsed-title">{{ preview.result.title || '未确定' }}</dd></div></dl>
          <details class="rules-help"><summary>匹配细节与完整解析 JSON</summary>
            <dl class="parsed-fields parsed-fields-meta"><div><dt>标准化名称</dt><dd>{{ preview.result.normalizedFilename || '—' }}</dd></div><div><dt>实际匹配文本</dt><dd>{{ preview.matchTarget || '—' }}</dd></div><div><dt>结构置信度（非正确率）</dt><dd>{{ Math.round(preview.result.confidence * 100) }}%</dd></div><div><dt>未识别片段</dt><dd>{{ preview.result.unparsedTokens.join('；') || '无' }}</dd></div></dl>
            <h3>逐规则匹配详情</h3><ElTable :data="preview.attempts" row-key="id"><ElTableColumn prop="name" label="规则" min-width="180" /><ElTableColumn label="状态" width="110"><template #default="{ row }">{{ statuses[row.status] }}</template></ElTableColumn><ElTableColumn label="捕获与说明" min-width="260"><template #default="{ row }"><strong v-if="row.id === preview.selectedRule?.id">已选中 · </strong>{{ row.detail }}<pre v-if="Object.keys(row.captures).length">{{ JSON.stringify(row.captures, null, 2) }}</pre><pre v-if="row.candidates.length">{{ JSON.stringify(row.candidates, null, 2) }}</pre></template></ElTableColumn></ElTable>
            <pre>{{ JSON.stringify(preview, null, 2) }}</pre>
          </details>
        </div>
</template>

<style scoped>
.parsed-fields {
  margin-top: 0;
}
.preview-result pre {
  margin: 8px 0 0;
  padding: 10px 12px;
  background: var(--sv-surface-muted);
  border: 1px solid var(--sv-line);
  border-radius: var(--sv-r-xs);
}
.preview-result .el-table {
  margin-top: 12px;
}
</style>
