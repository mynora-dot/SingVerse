<script setup lang="ts">
import { ElCard, ElIcon, ElTag } from 'element-plus'
import {
  ArrowRight,
  Monitor,
  Cellphone,
  FolderOpened,
  VideoPlay,
  SetUp,
  Document,
} from '@element-plus/icons-vue'
import ServiceHealthCard from '../../system-status/components/ServiceHealthCard.vue'

const entries = [
  { icon: FolderOpened, title: '歌曲位置', description: '连接歌曲目录与 WebDAV', to: '/library/media' },
  { icon: Document, title: '解析规则', description: '维护文件名解析的共享规则', to: '/library/filename-parser' },
  { icon: Monitor, title: '系统状态', description: '查看服务连接与访问地址', to: '/system' },
]
const upcoming = [
  { icon: FolderOpened, title: '自动扫描', description: '定时检查媒体来源，自动发现新增歌曲。' },
  { icon: VideoPlay, title: '聚会点歌', description: '亲友扫码进入聚会，点歌并查看当前队列。' },
  { icon: SetUp, title: '电视播放', description: '配对电视端，回报真实播放状态与音量。' },
]
const bars = [18, 34, 52, 28, 68, 86, 46, 74, 30, 60, 92, 40, 66, 24, 50]
</script>

<template>
  <section class="overview">
    <div class="page-heading">
      <div>
        <h1>概览</h1>
        <p class="page-description">欢迎回到你的家庭音乐空间。</p>
      </div>
      <span class="edition-label">SINGVERSE / NAS</span>
    </div>

    <section class="stage-band" aria-labelledby="hero-title">
      <div class="stage-copy">
        <p class="stage-kicker"><span class="small-dot" />相聚 · 开唱</p>
        <h2 id="hero-title">让每一次相聚，<br />都有好歌相伴。</h2>
        <p>自己的歌曲，熟悉的朋友。从这里，打理属于家人的 KTV。</p>
        <div class="stage-actions">
          <a class="stage-cta" href="/party/" target="_blank" rel="noopener">打开亲友入口<ElIcon><ArrowRight /></ElIcon></a>
          <RouterLink class="stage-cta ghost" to="/library/media">管理媒体库</RouterLink>
        </div>
      </div>
      <div class="waveform" aria-hidden="true">
        <i v-for="(height, index) in bars" :key="height" :style="{ height: `${height}px`, animationDelay: `${index * 0.13}s` }" />
      </div>
    </section>

    <div class="overview-grid">
      <ServiceHealthCard />
      <ElCard class="entry-card" shadow="never">
        <div class="section-heading">
          <h2>快速访问</h2>
          <span class="muted">从这里开始</span>
        </div>
        <a href="/party/" target="_blank" rel="noopener" class="entry-row">
          <span class="entry-icon"><ElIcon><Cellphone /></ElIcon></span>
          <span><strong>亲友网页</strong><small>打开当前 H5 欢迎页</small></span>
          <ElIcon><ArrowRight /></ElIcon>
        </a>
        <RouterLink v-for="entry in entries" :key="entry.title" class="entry-row" :to="entry.to">
          <span class="entry-icon"><ElIcon><component :is="entry.icon" /></ElIcon></span>
          <span><strong>{{ entry.title }}</strong><small>{{ entry.description }}</small></span>
          <ElIcon><ArrowRight /></ElIcon>
        </RouterLink>
      </ElCard>
    </div>

    <section class="upcoming-section" aria-labelledby="upcoming-title">
      <div class="section-heading">
        <h2 id="upcoming-title">逐步完善你的音乐空间</h2>
        <span class="muted">后续功能</span>
      </div>
      <div class="upcoming-grid">
        <ElCard v-for="item in upcoming" :key="item.title" shadow="never" class="upcoming-card">
          <ElIcon :size="22"><component :is="item.icon" /></ElIcon>
          <h3>{{ item.title }}</h3>
          <p>{{ item.description }}</p>
          <ElTag type="info" size="small" effect="plain">尚未接入</ElTag>
        </ElCard>
      </div>
      <p class="scope-note">
        当前版本提供账号登录、二次验证、媒体来源配置、本地及 WebDAV 文件名扫描与服务健康检查。自动扫描、配对和播放功能尚未实现。
      </p>
    </section>
  </section>
</template>
