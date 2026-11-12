<script setup lang="ts">
import { ElButton, ElPopover } from 'element-plus'
import { InfoFilled } from '@element-plus/icons-vue'
import { nextTick, ref } from 'vue'

/** 轻量内容栏：对象名称、数量、主要操作和按需说明共享一行。 */
defineProps<{ title: string; description?: string; eyebrow?: string }>()
const descriptionOpen = ref(false)
const helpButton = ref<InstanceType<typeof ElButton>>()
async function closeHelp() {
  descriptionOpen.value = false
  await nextTick()
  helpButton.value?.$el.focus()
}
</script>

<template>
  <header class="page-heading sv-page-header" :class="{ 'has-controls': $slots.actions || description || $slots.description }">
    <div class="sv-page-header-copy">
      <p v-if="eyebrow" class="eyebrow sv-page-eyebrow sr-only">{{ eyebrow }}</p>
      <h1>{{ title }}</h1>
      <slot name="meta" />
    </div>
    <div v-if="$slots.actions || description || $slots.description" class="sv-page-header-actions">
      <slot name="actions" />
      <ElPopover v-if="description || $slots.description" v-model:visible="descriptionOpen" trigger="click" role="dialog" :title="`${title}使用说明`" placement="bottom-end" :width="400" :persistent="false" popper-class="sv-page-help-popover">
        <template #reference><ElButton ref="helpButton" class="sv-page-header-help" text :icon="InfoFilled" :title="`${title}使用说明`" :aria-label="`${title}使用说明`" :aria-expanded="descriptionOpen" @keydown.esc.prevent.stop="closeHelp" /></template>
        <div class="page-description" @keydown.esc.prevent.stop="closeHelp"><slot name="description">{{ description }}</slot></div>
      </ElPopover>
    </div>
  </header>
</template>
