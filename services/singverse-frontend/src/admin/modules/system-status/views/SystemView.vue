<script setup lang="ts">
import { ElIcon } from 'element-plus'
import { ArrowRight, Connection, Monitor } from '@element-plus/icons-vue'
import ServiceHealthCard from '../components/ServiceHealthCard.vue'
import PageHeader from '../../../shared/ui/PageHeader.vue'
import { adminBasePath } from '../../../core/hosting/mount-path'

const origin = window.location.origin
const entries = [
  { label: '管理中心', href: `${origin}${adminBasePath}`, note: '当前页面所在入口' },
  { label: '亲友点歌页', href: `${origin}/party/`, note: '手机扫码前请确认手机能访问此地址，不要使用 localhost', external: true },
]
</script>

<template>
  <section class="system-view">
    <PageHeader title="服务状态" description="检查主服务是否可以响应，并核对管理中心与亲友点歌页的访问地址。" />
    <ServiceHealthCard />
    <section class="sv-panel" aria-labelledby="system-entries-title">
      <div class="sv-panel-head"><h2 id="system-entries-title">访问入口</h2></div>
      <dl class="entry-list">
        <div v-for="entry in entries" :key="entry.label" class="entry-line">
          <dt>{{ entry.label }}</dt>
          <dd><a :href="entry.href" :target="entry.external ? '_blank' : undefined" :rel="entry.external ? 'noopener' : undefined">{{ entry.href }}</a><small>{{ entry.note }}</small></dd>
        </div>
      </dl>
    </section>
    <nav class="sv-panel related-settings" aria-label="相关设置">
      <RouterLink to="/system/compute"><ElIcon><Connection /></ElIcon><span><strong>性能节点配置</strong><small>媒体探测、转码与硬件加速节点</small></span><ElIcon><ArrowRight /></ElIcon></RouterLink>
      <RouterLink to="/system/tv-devices"><ElIcon><Monitor /></ElIcon><span><strong>电视设备</strong><small>批准或撤销电视访问</small></span><ElIcon><ArrowRight /></ElIcon></RouterLink>
    </nav>
    <p class="muted">服务健康只表示主服务可以响应，不代表计算节点、转码或某首歌曲已经可以播放。</p>
  </section>
</template>

<style scoped>
.system-view { display: grid; gap: 16px; }
.system-view > .page-heading { margin-bottom: 4px; }
.system-view > * + * { margin-top: 0 !important; }
.entry-list { margin: 0; }
.entry-line { display: grid; grid-template-columns: 120px minmax(0, 1fr); gap: 12px; padding: 12px 20px; border-bottom: 1px solid var(--sv-border-subtle); }
.entry-line:last-child { border-bottom: 0; }
.entry-line dt { color: var(--sv-text-secondary); font-size: var(--sv-font-meta); }
.entry-line dd { display: grid; gap: 2px; margin: 0; }
.entry-line a { font-family: var(--sv-font-mono); font-size: var(--sv-font-meta); overflow-wrap: anywhere; }
.entry-line small { color: var(--sv-text-tertiary); font-size: var(--sv-font-label); }
.related-settings { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); padding: 6px; }
.related-settings a { display: flex; gap: 12px; align-items: center; padding: 10px 14px; color: var(--sv-text-primary); border-radius: var(--sv-radius-md); }
.related-settings a:hover { background: var(--sv-surface-3); }
.related-settings a > span { flex: 1; min-width: 0; }
.related-settings strong { display: block; font-size: var(--sv-font-meta); }
.related-settings small { display: block; color: var(--sv-text-tertiary); font-size: var(--sv-font-label); }
.related-settings .el-icon { color: var(--sv-text-tertiary); }
@media (max-width: 768px) { .entry-line { grid-template-columns: minmax(0, 1fr); gap: 4px; padding: 12px 16px; } .related-settings { grid-template-columns: minmax(0, 1fr); } }
</style>
