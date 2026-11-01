<script setup lang="ts">
import { ElIcon } from 'element-plus'
import { ArrowRight, FolderOpened, Headset, MagicStick, CollectionTag, Document, Monitor } from '@element-plus/icons-vue'
import ServiceHealthCard from '../../system-status/components/ServiceHealthCard.vue'
const entries = [
  { icon: Headset, title: '歌曲库', description: '浏览收藏，播放熟悉的旋律', to: '/library/songs', label: '浏览歌曲' },
  { icon: MagicStick, title: '完善歌曲信息', description: '匹配平台元数据，审核后保存', to: '/library/songs/metadata', label: '开始整理' },
  { icon: CollectionTag, title: '让收藏井井有条', description: '按语种、风格与场景管理分类', to: '/library/classification', label: '管理分类' },
]
const steps = [
  { icon: FolderOpened, title: '连接媒体库', text: '添加本地目录或 WebDAV，让音乐有处可寻。', to: '/library/media' },
  { icon: Document, title: '识别文件名', text: '挑选适合的解析方案，提取歌名与歌手。', to: '/library/filename-parser-profiles' },
  { icon: Monitor, title: '检查服务状态', text: '查看 NAS 服务健康与亲友访问入口。', to: '/system' },
]
const bars = [24, 42, 72, 54, 96, 124, 80, 108, 148, 92, 64, 116, 80, 48, 28]
</script>

<template>
  <section class="overview">
    <div class="page-heading"><div><p class="page-eyebrow">YOUR MUSIC, YOUR SPACE</p><h1>空间概览</h1><p class="page-description">把喜欢的音乐，留在自己的生活里。</p></div><span class="edition-label">SingVerse / 家庭音乐空间</span></div>
    <section class="music-hero" aria-labelledby="hero-title">
      <div class="hero-copy"><span class="hero-kicker">属于家人的音乐时光</span><h2 id="hero-title">每一次相聚，<br />都有好歌相伴<span>。</span></h2><p>收藏熟悉的旋律，打理自己的曲库。<br />从这里，准备下一次尽兴开唱。</p><div class="hero-actions"><RouterLink class="hero-primary" to="/library/songs">打开歌曲库 <ElIcon><ArrowRight /></ElIcon></RouterLink><RouterLink class="hero-secondary" to="/library/media">添加媒体库</RouterLink></div></div>
      <div class="record-art" aria-hidden="true"><div class="record-orbit"></div><div class="record"><div class="record-label"><ElIcon><Headset /></ElIcon><span>SINGVERSE</span><i></i></div></div><div class="record-caption"><span>THE SOUND OF HOME</span><div class="mini-wave"><i v-for="(height, i) in bars" :key="i" :style="{height: `${height / 6}px`}" /></div></div></div>
    </section>
    <div class="overview-shortcuts"><RouterLink v-for="entry in entries" :key="entry.to" :to="entry.to" class="shortcut-card"><span class="shortcut-icon"><ElIcon><component :is="entry.icon" /></ElIcon></span><h2>{{ entry.title }}</h2><p>{{ entry.description }}</p><span class="shortcut-link">{{ entry.label }} <ElIcon><ArrowRight /></ElIcon></span></RouterLink></div>
    <div class="overview-bottom"><section class="workspace-guide"><div class="section-heading"><h2>开始打理你的音乐空间</h2><span class="muted">从收藏到整理</span></div><RouterLink v-for="(step, index) in steps" :key="step.to" :to="step.to" class="guide-row"><span class="guide-number">0{{ index + 1 }}</span><div><strong>{{ step.title }}</strong><p>{{ step.text }}</p></div><ElIcon><ArrowRight /></ElIcon></RouterLink></section><ServiceHealthCard /></div>
    <div class="overview-note"><span class="small-dot" />歌曲与数据由自己的 NAS 管理。<a href="/party/" target="_blank" rel="noopener">查看亲友欢迎页 ↗</a></div>
    <p class="feature-note">已支持文件名扫描、歌曲分类、元数据审核与原始音视频在线播放；亲友点歌、队列和电视会话播放正在筹备中。</p>
  </section>
</template>
