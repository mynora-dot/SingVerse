<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import NodeTab from '../audio-processing/components/NodeTab.vue'
import { base, type Node } from '../audio-processing/api'
import { readiness, failure } from '../audio-processing/presentation'
import { authRequest } from '../../core/auth/api'
const node = ref<Node>(), error = ref('')
const ready = computed(() => readiness(node.value, error.value))
let active = true, generation = 0, timer: ReturnType<typeof setTimeout> | undefined
async function reload(refresh = false) {
  const turn = ++generation
  try { const value = await authRequest<Node>(`${base}/node${refresh ? '?refresh=true' : ''}`, 'GET', undefined, 60000); if (active && turn === generation) { node.value = value; error.value = '' } }
  catch (e) { if (active && turn === generation) { node.value = undefined; error.value = failure(e) } }
}
async function poll() { await reload(); if (active) timer = setTimeout(poll, 4000) }
onMounted(poll)
onBeforeUnmount(() => { active = false; generation++; clearTimeout(timer) })
</script>
<template><section id="audio-models" style="margin-top:24px;scroll-margin-top:24px"><NodeTab :node="node" :node-error="error" :readiness="ready" :reload="reload" /></section></template>
