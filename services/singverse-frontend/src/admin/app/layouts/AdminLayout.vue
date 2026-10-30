<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { ElButton, ElDropdown, ElDropdownItem, ElDropdownMenu, ElIcon, ElMenu, ElMenuItem, ElSubMenu, ElMessage } from 'element-plus'
import type { MenuInstance } from 'element-plus'
import { ArrowDown, ArrowRight, Expand, Fold, Headset, Key, Setting, SwitchButton } from '@element-plus/icons-vue'
import AssistantWidget from '../../modules/ai-settings/components/AssistantWidget.vue'
import { navigationContext, navigationGroups, overviewEntry } from '../navigation'
import { auth, authRequest } from '../../core/auth/api'
import { useRouter, useRoute } from 'vue-router'

const route = useRoute()
const router = useRouter()
const navigationOpen = ref(false)
const navigationMenu = ref<MenuInstance>()
const currentNavigation = computed(() => navigationContext(String(route.meta.activeMenu || route.path)))
const openGroups = computed(() => [currentNavigation.value.group?.id ?? 'library'])
const accountLabel = computed(() => auth.account?.username ?? 'admin')
watch(() => route.path, () => {
  navigationOpen.value = false
  navigationMenu.value?.open(currentNavigation.value.group?.id ?? 'library')
}, { flush: 'post' })
async function logout() {
  try {
    await authRequest('/api/v1/auth/logout', 'POST')
    auth.account = null
    await router.replace('/login')
  } catch (error) { ElMessage.error(error instanceof Error ? error.message : '退出失败，请重试') }
}

function focusMainContent() {
  document.getElementById('main-content')?.focus()
}
</script>

<template>
  <a class="skip-link" href="#main-content" @click.prevent="focusMainContent">跳转到主要内容</a>
  <div class="admin-shell">
    <aside class="sidebar" :class="{ 'sidebar-open': navigationOpen }" aria-label="管理中心导航" @keydown.esc="navigationOpen = false">
      <RouterLink to="/overview" class="brand" aria-label="SingVerse 管理中心首页">
        <span class="brand-mark"><ElIcon :size="22"><Headset /></ElIcon></span>
        <span><strong>SingVerse<span class="brand-dot">.</span></strong><small>NAS 管理中心</small></span>
      </RouterLink>
      <p class="nav-label">音乐工作台</p>
      <ElMenu ref="navigationMenu" :default-active="currentNavigation.entry?.path || route.path" :default-openeds="openGroups" unique-opened router id="admin-navigation" class="navigation" aria-label="主导航">
        <ElMenuItem :index="overviewEntry.path"><ElIcon><component :is="overviewEntry.icon" /></ElIcon><span>{{ overviewEntry.label }}</span></ElMenuItem>
        <ElSubMenu v-for="group in navigationGroups" :key="group.id" :index="group.id">
          <template #title><ElIcon><component :is="group.icon" /></ElIcon><span>{{ group.label }}</span></template>
          <ElMenuItem v-for="entry in group.children" :key="entry.path" :index="entry.path"><span>{{ entry.label }}</span></ElMenuItem>
        </ElSubMenu>
      </ElMenu>
      <div class="sidebar-note"><span class="sidebar-note-icon"><Headset /></span><strong>音乐，由你收藏。</strong><p>从一首熟悉的歌，<br />到属于家人的音乐空间。</p><RouterLink to="/library/media">整理我的曲库 <span aria-hidden="true">↗</span></RouterLink></div>
      <div class="sidebar-bottom"><span class="small-dot" /><span>家的音乐，自己掌管</span></div>
    </aside>
    <div class="workspace" :class="{ 'batch-workspace': ['/library/songs/metadata', '/library/classification'].includes(route.path) }">
      <header class="topbar">
        <div class="topbar-leading">
          <ElButton class="mobile-nav-toggle" :icon="navigationOpen ? Fold : Expand" :aria-label="navigationOpen ? '收起导航' : '导航菜单'" :aria-expanded="navigationOpen" aria-controls="admin-navigation" @click="navigationOpen = !navigationOpen"><span class="nav-toggle-label">{{ navigationOpen ? '收起导航' : '导航菜单' }}</span></ElButton>
          <div class="breadcrumb">
            <span>{{ currentNavigation.group?.label || '我的音乐空间' }}</span><span class="separator">/</span><strong>{{ currentNavigation.entry?.label || route.meta.title }}</strong>
          </div>
        </div>
        <div class="topbar-actions">
          <a class="party-link" aria-label="打开亲友入口" href="/party/" target="_blank" rel="noopener"><span class="party-link-text">亲友入口</span><ElIcon><ArrowRight /></ElIcon></a>
          <ElDropdown trigger="click" placement="bottom-end">
            <button class="account-chip" type="button" aria-label="账号菜单">
              <span class="avatar">{{ accountLabel.slice(0, 1) }}</span>
              <span class="account-label">{{ accountLabel }}</span>
              <ElIcon><ArrowDown /></ElIcon>
            </button>
            <template #dropdown>
              <ElDropdownMenu>
                <ElDropdownItem :icon="Key" disabled><span class="dropdown-meta">{{ auth.account?.mfaEnabled ? '二次验证已开启' : '二次验证未开启' }}</span></ElDropdownItem>
                <ElDropdownItem :icon="Setting" @click="router.push('/system/parameters')">系统参数</ElDropdownItem>
                <ElDropdownItem divided :icon="SwitchButton" @click="logout">退出登录</ElDropdownItem>
              </ElDropdownMenu>
            </template>
          </ElDropdown>
        </div>
      </header>
      <main id="main-content" tabindex="-1">
        <RouterView />
      </main>
      <footer class="page-footer">
        <span>SingVerse · 相聚，开唱。</span><span>你的收藏 · 你的空间</span>
      </footer>
    </div>
  </div>
  <button v-if="navigationOpen" class="nav-scrim" type="button" aria-label="关闭导航" @click="navigationOpen = false" />
  <AssistantWidget />
</template>

<style scoped>
/* Batch page owns one vertical scroll area below a normal-flow header. */
.batch-workspace { height: 100vh; height: 100dvh; overflow: hidden; }
.batch-workspace > .topbar { position: relative; top: auto; flex-shrink: 0; }
.batch-workspace > main { flex: 1 1 auto; min-height: 0; overflow-y: auto; overscroll-behavior-y: contain; }
.batch-workspace > .page-footer { flex-shrink: 0; }

.topbar-leading {
  display: flex;
  gap: var(--sv-space-4);
  align-items: center;
  min-width: 0;
}
.party-link {
  display: inline-flex;
  gap: 5px;
  align-items: center;
  height: 36px;
  padding: 0 14px;
  color: var(--sv-text-secondary);
  background: var(--sv-surface-2);
  border: 1px solid var(--sv-border-subtle);
  border-radius: var(--sv-radius-full);
  font-size: var(--sv-font-meta);
  font-weight: 550;
}
.party-link:hover {
  color: var(--sv-accent-muted);
  border-color: var(--sv-border-default);
}
.party-link .el-icon {
  font-size: 13px;
}
.account-chip .el-icon {
  color: var(--sv-text-tertiary);
  font-size: 13px;
}
.dropdown-meta {
  color: var(--sv-text-tertiary);
  font-size: var(--sv-font-meta);
}
.nav-scrim {
  position: fixed;
  inset: 0;
  z-index: 25;
  background: rgb(14 18 26 / 45%);
  border: 0;
}
</style>
