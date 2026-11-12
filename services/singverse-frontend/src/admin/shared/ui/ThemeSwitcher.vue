<script setup lang="ts">
import { ElDropdown, ElDropdownItem, ElDropdownMenu, ElIcon } from 'element-plus'
import { Check, Moon, Sunny } from '@element-plus/icons-vue'
import { useTheme } from '../../app/composables/use-theme'

withDefaults(defineProps<{ variant?: 'bar' | 'sheet' }>(), { variant: 'bar' })
const { themes, currentTheme, setTheme } = useTheme()
</script>

<template>
  <ElDropdown v-if="variant === 'bar'" trigger="click" placement="bottom-end" popper-class="theme-popper" class="theme-switcher">
    <button class="theme-trigger" type="button" aria-label="外观主题" title="外观主题"><ElIcon><Moon v-if="currentTheme === 'dark'" /><Sunny v-else /></ElIcon></button>
    <template #dropdown>
      <ElDropdownMenu class="theme-menu">
        <ElDropdownItem v-for="theme in themes" :key="theme.id" class="theme-option" :class="{ 'is-active': theme.id === currentTheme }" @click="setTheme(theme.id)">
          <ElIcon aria-hidden="true"><Moon v-if="theme.id === 'dark'" /><Sunny v-else /></ElIcon>
          <span class="theme-copy"><strong>{{ theme.label }}</strong><span class="theme-desc">{{ theme.description }}</span></span>
          <span class="theme-check" aria-hidden="true"><ElIcon v-if="theme.id === currentTheme"><Check /></ElIcon></span>
          <span v-if="theme.id === currentTheme" class="sr-only">当前主题</span>
        </ElDropdownItem>
      </ElDropdownMenu>
    </template>
  </ElDropdown>
  <section v-else class="theme-sheet" aria-labelledby="theme-sheet-title">
    <h2 id="theme-sheet-title" class="nav-group-title">外观主题</h2>
    <div class="theme-grid" role="group" aria-label="选择外观主题">
      <button v-for="theme in themes" :key="theme.id" type="button" class="theme-card" :class="{ 'is-active': theme.id === currentTheme }" :aria-pressed="theme.id === currentTheme" @click="setTheme(theme.id)">
        <ElIcon aria-hidden="true"><Moon v-if="theme.id === 'dark'" /><Sunny v-else /></ElIcon>
        <span class="theme-card-name">{{ theme.label }}</span>
        <span class="theme-check" aria-hidden="true"><ElIcon v-if="theme.id === currentTheme"><Check /></ElIcon></span>
      </button>
    </div>
    <p class="theme-note">仅在本浏览器记住你的选择</p>
  </section>
</template>

<style>
.theme-switcher {
  display: inline-flex;
}

.theme-trigger {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 40px;
  height: 40px;
  padding: 0;
  border: 0;
  background: none;
  cursor: pointer;
  color: var(--sv-text-secondary);
  border-radius: var(--sv-radius-md);
  transition: color .16s var(--sv-ease-smooth), background .16s var(--sv-ease-smooth);
}

.theme-trigger:hover {
  color: var(--sv-text-primary);
  background: var(--sv-surface-3);
}

.theme-trigger .el-icon,
.theme-option > .el-icon,
.theme-card > .el-icon {
  flex: none;
  font-size: 16px;
}

.theme-popper .el-dropdown-menu {
  min-width: 240px;
  max-width: calc(100vw - 32px);
  padding: 4px;
}

.theme-popper .el-dropdown-menu__item {
  display: flex;
  align-items: center;
  gap: 12px;
  min-height: 56px;
  padding: 8px 12px;
  border-radius: var(--sv-radius-md);
  line-height: 1.5;
  color: var(--sv-text-secondary);
}

.theme-copy {
  display: flex;
  flex-direction: column;
  gap: 4px;
  flex: 1;
  min-width: 0;
}

.theme-copy strong {
  font-size: 14px;
  line-height: 20px;
  font-weight: 500;
  color: var(--sv-text-primary);
}

.theme-desc {
  font-size: 12px;
  line-height: 16px;
  color: var(--sv-text-secondary);
}

.theme-check {
  display: inline-flex;
  flex: none;
  justify-content: center;
  width: 16px;
  color: var(--sv-accent-primary);
  font-size: 16px;
}

.theme-option.is-active {
  background: var(--sv-surface-3);
}

.theme-sheet {
  margin-top: 24px;
}

.theme-grid {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 8px;
}

.theme-card {
  display: flex;
  align-items: center;
  gap: 8px;
  min-height: 44px;
  padding: 8px 12px;
  line-height: 20px;
  border: 1px solid var(--sv-border-default);
  border-radius: var(--sv-radius-md);
  background: var(--sv-surface-2);
  color: var(--sv-text-secondary);
  cursor: pointer;
  text-align: left;
  transition: background .16s var(--sv-ease-smooth), border-color .16s var(--sv-ease-smooth);
}

.theme-card:hover {
  background: var(--sv-surface-3);
}

.theme-card.is-active {
  border-color: var(--sv-accent-primary);
  background: var(--sv-surface-3);
}

.theme-card-name {
  flex: 1;
  font-size: 14px;
  font-weight: 500;
  color: var(--sv-text-primary);
}

.theme-note {
  margin-top: 8px;
  font-size: 12px;
  color: var(--sv-text-secondary);
}

@media(max-width:768px) {
  .theme-trigger { width: 44px; height: 44px; }
}

@media(max-width:650px) {
  .theme-switcher {
    display: none;
  }
}
</style>
