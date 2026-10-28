<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { ElButton, ElDropdown, ElDropdownItem, ElDropdownMenu, ElIcon, ElMenu, ElMenuItem, ElMessage } from 'element-plus'
import { ArrowDown, ArrowRight, Expand, Fold, Headset, Key, Monitor, Setting, SwitchButton, FolderOpened, House } from '@element-plus/icons-vue'
import { auth, authRequest } from '../../core/auth/api'
import { useRouter, useRoute } from 'vue-router'

const route = useRoute()
const router = useRouter()
const navigationOpen = ref(false)
const accountLabel = computed(() => auth.account?.username ?? 'admin')
watch(() => route.path, () => { navigationOpen.value = false })
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
    <aside class="sidebar" :class="{ 'sidebar-open': navigationOpen }" aria-label="管理中心导航">
      <RouterLink to="/overview" class="brand" aria-label="SingVerse 管理中心首页">
        <span class="brand-mark"><ElIcon :size="22"><Headset /></ElIcon></span>
        <span><strong>SingVerse<span class="brand-dot">.</span></strong><small>NAS 管理中心</small></span>
      </RouterLink>
      <p class="nav-label">工作空间</p>
      <ElMenu :default-active="String(route.meta.activeMenu || route.path)" router id="admin-navigation" class="navigation" :class="{ 'navigation-open': navigationOpen }" aria-label="主导航">
        <ElMenuItem index="/overview"><ElIcon><House /></ElIcon><span>概览</span></ElMenuItem>
        <ElMenuItem index="/library/songs"><ElIcon><Headset /></ElIcon><span>歌曲</span></ElMenuItem>
        <ElMenuItem index="/library/media"><ElIcon><FolderOpened /></ElIcon><span>来源</span></ElMenuItem>
        <ElMenuItem index="/library/songs/metadata"><ElIcon :size="18"><component :is="'svg'" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M21 15v4a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2v-4"></path><polyline points="7 10 12 15 17 10"></polyline><line x1="12" y1="15" x2="12" y2="3"></line></component></ElIcon><span>刮削</span></ElMenuItem>
        <div class="nav-divider" />
        <ElMenuItem index="/library/filename-parser-profiles"><ElIcon :size="18"><component :is="'svg'" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M14 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V8z"></path><polyline points="14 2 14 8 20 8"></polyline><line x1="12" y1="18" x2="12" y2="12"></line><line x1="9" y1="15" x2="15" y2="15"></line></component></ElIcon><span>解析规则</span></ElMenuItem>
        <ElMenuItem index="/system"><ElIcon><Monitor /></ElIcon><span>系统</span></ElMenuItem>
      </ElMenu>
      <div class="sidebar-bottom"><span class="small-dot" /><span>家的音乐，自己掌管</span></div>
    </aside>
    <div class="workspace" :class="{ 'batch-workspace': ['/library/songs/metadata', '/library/classification'].includes(route.path) }">
      <header class="topbar">
        <div class="topbar-leading">
          <ElButton class="mobile-nav-toggle" :icon="navigationOpen ? Fold : Expand" :aria-expanded="navigationOpen" aria-controls="admin-navigation" @click="navigationOpen = !navigationOpen"><span class="nav-toggle-label">{{ navigationOpen ? '收起导航' : '导航菜单' }}</span></ElButton>
          <div class="breadcrumb">
            <span>管理中心</span><span class="separator">/</span><strong>{{ route.meta.title }}</strong>
          </div>
        </div>
        <div class="topbar-actions">
          <a class="party-link" href="/party/" target="_blank" rel="noopener"><span class="party-link-text">亲友入口</span><ElIcon><ArrowRight /></ElIcon></a>
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
        <span>SingVerse · 相聚，开唱。</span><span>家庭自托管 KTV</span>
      </footer>
    </div>
  </div>
  <button v-if="navigationOpen" class="nav-scrim" type="button" aria-label="关闭导航" @click="navigationOpen = false" />
</template>

<style scoped>
/* Batch page owns one vertical scroll area below a normal-flow header. */
.batch-workspace { height: 100vh; height: 100dvh; overflow: hidden; }
.batch-workspace > .topbar { position: relative; top: auto; flex-shrink: 0; }
.batch-workspace > main { flex: 1 1 auto; min-height: 0; overflow-y: auto; overscroll-behavior-y: contain; }
.batch-workspace > .page-footer { flex-shrink: 0; }

.topbar-leading {
  display: flex;
  gap: var(--sv-s-4);
  align-items: center;
  min-width: 0;
}
.party-link {
  display: inline-flex;
  gap: 5px;
  align-items: center;
  height: 36px;
  padding: 0 14px;
  color: var(--sv-text-muted);
  background: var(--sv-surface);
  border: 1px solid var(--sv-line);
  border-radius: var(--sv-r-pill);
  font-size: var(--sv-fs-meta);
  font-weight: 550;
}
.party-link:hover {
  color: var(--sv-brand-700);
  border-color: var(--sv-line-strong);
}
.party-link .el-icon {
  font-size: 13px;
}
.account-chip .el-icon {
  color: var(--sv-text-subtle);
  font-size: 13px;
}
.dropdown-meta {
  color: var(--sv-text-subtle);
  font-size: var(--sv-fs-meta);
}
.nav-scrim {
  position: fixed;
  inset: 0;
  z-index: 25;
  background: rgb(14 18 26 / 45%);
  border: 0;
}
</style>
