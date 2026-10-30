<script setup lang="ts">
import { ElAlert, ElCard, ElDescriptions, ElDescriptionsItem } from 'element-plus'
import ServiceHealthCard from '../components/ServiceHealthCard.vue'
import { adminBasePath } from '../../../core/hosting/mount-path'

const origin = window.location.origin
</script>

<template>
  <section class="system-view">
    <div class="page-heading">
      <div>
        <h1>系统状态</h1>
        <p class="page-description">检查当前 NAS 的服务连接。</p>
      </div>
    </div>
    <ServiceHealthCard /><ElCard shadow="never" class="address-card"
      ><div class="section-heading"><h2>访问入口</h2></div>
      <ElDescriptions :column="1" border
        ><ElDescriptionsItem label="当前服务">{{ origin }}</ElDescriptionsItem
        ><ElDescriptionsItem label="管理中心"
          ><a :href="adminBasePath">{{ origin }}{{ adminBasePath }}</a></ElDescriptionsItem
        ><ElDescriptionsItem label="亲友网页"
          ><a href="/party/" target="_blank" rel="noopener">{{ origin }}/party/</a></ElDescriptionsItem
        ></ElDescriptions
      ></ElCard
    ><ElAlert title="关于服务状态" type="info" :closable="false" show-icon
      ><p>
        服务健康表示主服务可以响应。曲库扫描、分类、元数据管理与原始音视频在线播放已提供；亲友点歌、配对与电视会话播放仍在筹备中。
      </p></ElAlert
    >
  </section>
</template>
