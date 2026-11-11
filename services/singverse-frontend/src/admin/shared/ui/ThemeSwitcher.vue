<script setup lang="ts">
import { ElDropdown, ElDropdownItem, ElDropdownMenu, ElIcon } from 'element-plus'
import { Brush, Check } from '@element-plus/icons-vue'
import { useTheme } from '../../app/composables/use-theme'

withDefaults(defineProps<{ variant?: 'bar' | 'sheet' }>(), { variant: 'bar' })
const { themes, currentTheme, setTheme } = useTheme()
</script>

<template>
  <ElDropdown v-if="variant === 'bar'" trigger="click" placement="bottom-end" popper-class="theme-popper" class="theme-switcher">
    <button class="theme-trigger" type="button" aria-label="外观主题" title="外观主题"><ElIcon><Brush /></ElIcon></button>
    <template #dropdown>
      <ElDropdownMenu class="theme-menu">
        <ElDropdownItem disabled class="theme-menu-head"><span>外观主题</span><small>仅在本浏览器记住你的选择</small></ElDropdownItem>
        <ElDropdownItem v-for="theme in themes" :key="theme.id" class="theme-option" :class="{ 'is-active': theme.id === currentTheme }" @click="setTheme(theme.id)">
          <span class="theme-swatch" aria-hidden="true"><span v-for="(color, index) in theme.swatches" :key="index" :style="{ background: color }" /></span>
          <span class="theme-copy"><strong>{{ theme.label }}<small>{{ theme.en }}</small></strong><span class="theme-desc">{{ theme.description }}</span></span>
          <span class="theme-scheme">{{ theme.scheme === 'dark' ? '深色' : '浅色' }}</span>
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
        <span class="theme-swatch" aria-hidden="true"><span v-for="(color, index) in theme.swatches" :key="index" :style="{ background: color }" /></span>
        <span class="theme-card-name">{{ theme.label }}</span>
        <span class="theme-scheme">{{ theme.scheme === 'dark' ? '深色' : '浅色' }}</span>
        <span class="theme-check" aria-hidden="true"><ElIcon v-if="theme.id === currentTheme"><Check /></ElIcon></span>
        <span v-if="theme.id === currentTheme" class="sr-only">当前主题</span>
      </button>
    </div>
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
  color: var(--sv-accent-primary);
  background: var(--sv-accent-soft);
}

.theme-trigger .el-icon {
  font-size: 19px;
}

.theme-popper .el-dropdown-menu {
  min-width: 282px;
  max-width: calc(100vw - 24px);
  padding: 6px;
}

.theme-popper .el-dropdown-menu__item {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 10px 12px;
  border-radius: var(--sv-radius-md);
  line-height: 1.4;
}

.theme-popper .theme-menu-head.el-dropdown-menu__item {
  display: block;
  cursor: default;
  padding: 8px 12px 10px;
  margin-bottom: 4px;
  border-bottom: 1px solid var(--sv-border-subtle);
  border-radius: 0;
  color: var(--sv-text-primary);
  font-size: 13px;
  font-weight: 650;
}

.theme-popper .theme-menu-head.el-dropdown-menu__item:hover {
  background: none;
  color: var(--sv-text-primary);
}

.theme-menu-head small {
  display: block;
  margin-top: 3px;
  font-size: 11px;
  font-weight: 400;
  color: var(--sv-text-tertiary);
}

.theme-swatch {
  display: inline-flex;
  flex: none;
  gap: 3px;
}

.theme-swatch span {
  width: 13px;
  height: 13px;
  border-radius: 50%;
  border: 1px solid rgb(0 0 0 / 12%);
}

.theme-copy {
  display: flex;
  flex-direction: column;
  gap: 2px;
  flex: 1 1 auto;
  min-width: 0;
}

.theme-copy strong {
  display: flex;
  align-items: baseline;
  gap: 6px;
  font-size: 13px;
  font-weight: 600;
  color: var(--sv-text-primary);
}

.theme-copy strong small {
  font-size: 10px;
  font-weight: 500;
  letter-spacing: .3px;
  color: var(--sv-text-tertiary);
}

.theme-desc {
  font-size: 11px;
  color: var(--sv-text-secondary);
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.theme-scheme {
  flex: none;
  padding: 2px 7px;
  border-radius: var(--sv-radius-full);
  background: var(--sv-surface-3);
  color: var(--sv-text-secondary);
  font-size: 10px;
}

.theme-check {
  display: inline-flex;
  flex: none;
  justify-content: center;
  width: 16px;
  color: var(--sv-accent-primary);
  font-size: 15px;
}

.theme-option.is-active {
  background: var(--sv-accent-soft);
}

.theme-option.is-active .theme-copy strong {
  color: var(--sv-accent-primary);
}

.theme-sheet {
  margin-top: 20px;
}

.theme-grid {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 8px;
}

.theme-card {
  position: relative;
  display: flex;
  flex-direction: column;
  align-items: flex-start;
  gap: 7px;
  padding: 12px;
  border: 1px solid var(--sv-border-default);
  border-radius: var(--sv-radius-md);
  background: var(--sv-surface-2);
  cursor: pointer;
  text-align: left;
}

.theme-card.is-active {
  border-color: var(--sv-accent-primary);
  background: var(--sv-accent-soft);
}

.theme-card-name {
  font-size: 13px;
  font-weight: 600;
  color: var(--sv-text-primary);
}

.theme-card .theme-check {
  position: absolute;
  top: 10px;
  right: 10px;
}

@media(max-width:650px) {
  .theme-switcher {
    display: none;
  }
}
</style>