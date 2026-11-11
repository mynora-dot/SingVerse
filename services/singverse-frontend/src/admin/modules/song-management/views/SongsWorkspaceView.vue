<script setup lang="ts">
import { computed } from 'vue'
import { useRoute } from 'vue-router'
import SongsView from './SongsView.vue'
import FormalSongsView from './FormalSongsView.vue'

const route = useRoute()
const lens = computed(() => (route.query.lens === 'formal' ? 'formal' : 'songs'))
</script>

<template>
  <FormalSongsView v-if="lens === 'formal'" />
  <SongsView v-else />
</template>