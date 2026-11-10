<script setup lang="ts">
defineProps<{ label: string; steps: { title: string; description: string }[] }>()
</script>

<template>
  <ol class="task-guide" :aria-label="label">
    <li v-for="(step, index) in steps" :key="step.title">
      <span class="guide-number" aria-hidden="true">0{{ index + 1 }}</span>
      <div><strong>{{ step.title }}</strong><p>{{ step.description }}</p></div>
    </li>
  </ol>
</template>

<style scoped>
.task-guide {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  padding: 0;
  margin: 0 0 24px;
  list-style: none;
  border-block: 1px solid var(--sv-border-subtle);
}

.task-guide li {
  display: flex;
  gap: 14px;
  align-items: flex-start;
  padding: 20px 22px;
  min-width: 0;
}

.task-guide li:first-child {
  padding-left: 0;
}

.task-guide li+li {
  border-left: 1px solid var(--sv-border-subtle);
}

.guide-number {
  font: 22px/1.4 var(--sv-font-editorial);
  color: var(--sv-terracotta);
}

strong {
  font-size: 13px;
  font-weight: 600;
}

p {
  margin-top: 4px;
  font-size: 12px;
  color: var(--sv-text-secondary);
  line-height: 1.7;
}

@media (max-width: 650px) {
  .task-guide {
    grid-template-columns: 1fr;
    padding: 8px 0;
  }

  .task-guide li,
  .task-guide li:first-child {
    padding: 10px 0;
    gap: 12px;
  }

  .task-guide li+li {
    border: 0;
  }

  .guide-number {
    font-size: 18px;
    min-width: 24px;
  }

  p {
    margin-top: 0;
    font-size: 11px;
  }
}
</style>
