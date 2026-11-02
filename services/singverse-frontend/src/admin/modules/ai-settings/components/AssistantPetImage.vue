<script setup lang="ts">
import { ref, watch } from 'vue'
const props = defineProps<{ src: string; animationSrc?: string; still?: boolean; paused?: boolean }>()
const ready = ref(false), failed = ref(false)
watch(() => [props.animationSrc, props.still], () => { ready.value = false; failed.value = false })
</script>
<template>
  <span class="assistant-pet assistant-pet-image" :class="{ still, paused, 'sprite-ready': ready && !failed && !still }" aria-hidden="true">
    <img class="pet-static" :src="src" alt="" width="128" height="128" draggable="false" decoding="async" />
    <img v-if="animationSrc && !still" :key="animationSrc" class="pet-sheet" :src="animationSrc" alt="" width="256" height="256" draggable="false" decoding="async" @load="ready = true" @error="failed = true" />
  </span>
</template>
<style scoped>
.assistant-pet-image{position:relative;display:inline-block;width:128px;height:128px;flex-shrink:0;overflow:hidden}
.assistant-pet-image img{display:block;pointer-events:none;user-select:none}
.pet-static{width:100%;height:100%;object-fit:contain}
.pet-sheet{position:absolute;left:0;top:0;width:200%;height:200%;max-width:none;visibility:hidden;animation:pet-frames 3.6s steps(1,end) infinite}
.sprite-ready .pet-static{visibility:hidden}
.sprite-ready .pet-sheet{visibility:visible}
.paused .pet-sheet{animation-play-state:paused}
/* Four equal cells, read left-to-right then top-to-bottom. Hold the neutral pose between gestures. */
@keyframes pet-frames{0%,60%,100%{transform:translate(0,0)}65%,85%{transform:translate(-50%,0)}70%,80%{transform:translate(0,-50%)}75%{transform:translate(-50%,-50%)}}
@media(prefers-reduced-motion:reduce){.pet-sheet{display:none;animation:none}.sprite-ready .pet-static{visibility:visible}}
</style>
