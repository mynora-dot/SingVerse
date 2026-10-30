<script setup lang="ts">
/** Local, code-drawn appearance. Add future pet renderers through the pet registry. */
defineProps<{ still?: boolean }>()
</script>
<template>
  <span class="assistant-pet" :class="{ still }" aria-hidden="true">
    <svg viewBox="0 0 100 100" fill="none"><ellipse cx="50" cy="90" rx="30" ry="4" fill="currentColor" opacity=".12" /><g class="pet-body"><path d="M20 57C20 34 31 20 50 20S80 34 80 57V65C80 81 67 88 50 88S20 81 20 65Z" fill="#C9E9D8" stroke="#35745B" stroke-width="2.5"/><path d="M25 51V46C25 30 36 17 50 17S75 30 75 46V51" stroke="#35745B" stroke-width="6" stroke-linecap="round"/><rect x="17" y="45" width="12" height="22" rx="6" fill="#35745B"/><rect x="71" y="45" width="12" height="22" rx="6" fill="#35745B"/><path d="M38 56V61M62 56V61" stroke="#294E41" stroke-width="4" stroke-linecap="round"/><path d="M44 69Q50 75 56 69" stroke="#294E41" stroke-width="2.5" stroke-linecap="round"/><ellipse cx="33" cy="67" rx="5" ry="3" fill="#EAB8A8"/><ellipse cx="67" cy="67" rx="5" ry="3" fill="#EAB8A8"/><path d="M47 14L52 5L57 14" fill="#D7A857"/><path d="M85 26V15L94 13V23M85 26C80 24 79 30 83 30C86 30 86 27 85 26M94 23C89 21 88 27 92 27C95 27 95 24 94 23" stroke="#6B9A80" stroke-width="2" stroke-linecap="round"/></g></svg>
  </span>
</template>
<style scoped>
.assistant-pet{display:inline-block;width:90px;height:90px;color:#35745b}.assistant-pet svg{width:100%;height:100%;overflow:visible}.pet-body{animation:pet-breathe 4s ease-in-out infinite;transform-origin:50px 85px}.still .pet-body{animation:none}@keyframes pet-breathe{50%{transform:translateY(-3px) rotate(-2deg)}}@media(prefers-reduced-motion:reduce){.pet-body{animation:none}}
</style>
