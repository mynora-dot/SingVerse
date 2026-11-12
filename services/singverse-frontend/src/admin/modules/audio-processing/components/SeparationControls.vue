<script setup lang="ts">
import type { Backend, SeparationProfile } from '../api'
import { backendName, profileNames } from '../presentation'
defineProps<{ profile: SeparationProfile; backend: string; backends: Backend[]; disabled?: boolean }>()
const emit = defineEmits<{ 'update:profile': [value: SeparationProfile]; 'update:backend': [value: string] }>()
</script>

<template>
  <div class="separation-controls">
    <label>伴奏方案<select :value="profile" aria-label="伴奏方案" :disabled="disabled" @change="emit('update:profile', ($event.target as HTMLSelectElement).value as SeparationProfile)"><option v-for="(label, id) in profileNames" :key="id" :value="id">{{ label }}</option></select></label>
    <label v-if="profile !== 'DSP_FAST'">使用设备<select :value="backend" aria-label="生成伴奏使用的设备" :disabled="disabled" @change="emit('update:backend', ($event.target as HTMLSelectElement).value)"><option value="">自动选择</option><option v-for="item in backends" :key="item.id" :value="item.id">{{ backendName(item.id) }}</option></select></label>
    <p v-if="profile === 'DSP_FAST'">无需 AI 模型。居中的鼓、贝斯也可能减弱，混响和和声可能残留；请先试听。单声道或左右相同的素材不适用。</p>
    <p v-else-if="profile === 'AI_CLEAN'">优先使用已试跑的加速设备，硬件故障时用同一模型在 CPU 重试；耗时取决于节点。</p>
  </div>
</template>

<style scoped>
.separation-controls{display:flex;flex-wrap:wrap;gap:8px 16px;align-items:center}.separation-controls label{display:flex;gap:8px;align-items:center;font-size:var(--sv-font-meta);color:var(--sv-text-secondary)}.separation-controls select{min-height:40px;max-width:100%;padding:4px 8px;border:1px solid var(--sv-border-default);border-radius:8px;background:var(--sv-surface-1);color:var(--sv-text-primary);font:inherit}.separation-controls p{flex-basis:100%;margin:0;font-size:var(--sv-font-meta);line-height:1.6;color:var(--sv-text-secondary)}
</style>
