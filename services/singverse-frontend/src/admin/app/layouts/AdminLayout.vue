<script setup lang="ts">
import { computed, nextTick, ref, watch } from 'vue'
import { ElButton, ElDropdown, ElDropdownItem, ElDropdownMenu, ElIcon, ElMessage } from 'element-plus'
import { ArrowDown, Close, Headset, Key, Menu, SwitchButton, TopRight } from '@element-plus/icons-vue'
import AssistantWidget from '../../modules/ai-settings/components/AssistantWidget.vue'
import { navigationContext, navigationGroups, overviewEntry } from '../navigation'
import { auth, authRequest } from '../../core/auth/api'
import { useRouter, useRoute } from 'vue-router'

const route = useRoute()
const router = useRouter()
const navigationOpen = ref(false)
const navToggle = ref<InstanceType<typeof ElButton>>()
const navigationList = ref<HTMLElement>()
const currentNavigation = computed(() => navigationContext(String(route.meta.activeMenu || route.path)))
const activePath = computed(() => currentNavigation.value.entry?.path ?? route.path)
const accountLabel = computed(() => auth.account?.username ?? 'admin')
const securityHint = computed(() => auth.account?.defaultPassword ? '仍在使用默认密码' : auth.account?.mfaEnabled ? '二次验证已开启' : '二次验证未开启')
watch(() => route.path, () => { navigationOpen.value = false })

async function toggleNavigation() {
  navigationOpen.value = !navigationOpen.value
  if (navigationOpen.value) {
    await nextTick()
    navigationList.value?.querySelector<HTMLElement>('[aria-current="page"], a')?.focus()
  }
}
function closeNavigation() {
  if (!navigationOpen.value) return
  navigationOpen.value = false
  ;(navToggle.value?.$el as HTMLElement | undefined)?.focus()
}
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
    <aside class="sidebar" :class="{ 'sidebar-open': navigationOpen }" aria-label="管理中心导航" @keydown.esc="closeNavigation">
      <RouterLink to="/overview" class="brand" aria-label="SingVerse 管理中心首页">
        <span class="brand-mark"><ElIcon :size="18"><Headset /></ElIcon></span>
        <span><strong>SingVerse</strong><small>管理中心</small></span>
      </RouterLink>
      <nav id="admin-navigation" ref="navigationList" class="navigation" aria-label="主导航">
        <RouterLink :to="overviewEntry.path" class="nav-item" :class="{ 'is-active': activePath === overviewEntry.path }" :aria-current="activePath === overviewEntry.path ? 'page' : undefined"><ElIcon><component :is="overviewEntry.icon" /></ElIcon><span>{{ overviewEntry.label }}</span></RouterLink>
        <section v-for="group in navigationGroups" :key="group.id" class="nav-group" :aria-labelledby="`nav-group-${group.id}`">
          <h2 :id="`nav-group-${group.id}`" class="nav-group-title">{{ group.label }}</h2>
          <ul>
            <li v-for="entry in group.children" :key="entry.path">
              <RouterLink :to="entry.path" class="nav-item" :class="{ 'is-active': activePath === entry.path }" :aria-current="activePath === entry.path ? 'page' : undefined"><ElIcon><component :is="entry.icon" /></ElIcon><span>{{ entry.label }}</span><small v-if="'advanced' in entry && entry.advanced" class="nav-hint">高级</small></RouterLink>
            </li>
          </ul>
        </section>
      </nav>
      <a class="sidebar-party" href="/party/" target="_blank" rel="noopener">打开亲友点歌页<ElIcon><TopRight /></ElIcon></a>
    </aside>
    <div class="workspace" :class="{ 'batch-workspace': ['/library/songs/metadata', '/library/classification'].includes(route.path) }">
      <header class="topbar">
        <div class="topbar-leading">
          <ElButton ref="navToggle" class="mobile-nav-toggle" :icon="navigationOpen ? Close : Menu" :aria-label="navigationOpen ? '收起导航' : '打开导航菜单'" :aria-expanded="navigationOpen" aria-controls="admin-navigation" @click="toggleNavigation" />
          <nav class="breadcrumb" aria-label="当前位置">
            <span v-if="currentNavigation.group">{{ currentNavigation.group.label }}</span><span v-if="currentNavigation.group" class="separator" aria-hidden="true">/</span><strong aria-current="page">{{ currentNavigation.entry?.label || route.meta.title }}</strong>
          </nav>
        </div>
        <div class="topbar-actions">
          <ElDropdown trigger="click" placement="bottom-end">
            <button class="account-chip" type="button" aria-label="账号菜单">
              <span class="avatar">{{ accountLabel.slice(0, 1) }}</span>
              <span class="account-label">{{ accountLabel }}</span>
              <span v-if="auth.account?.defaultPassword" class="account-alert" aria-label="安全提醒" />
              <ElIcon><ArrowDown /></ElIcon>
            </button>
            <template #dropdown>
              <ElDropdownMenu>
                <ElDropdownItem :icon="Key" disabled><span class="dropdown-meta">{{ securityHint }}</span></ElDropdownItem>
                <ElDropdownItem :icon="Key" @click="router.push('/system/parameters')">账号与安全</ElDropdownItem>
                <ElDropdownItem divided :icon="SwitchButton" @click="logout">退出登录</ElDropdownItem>
              </ElDropdownMenu>
            </template>
          </ElDropdown>
        </div>
      </header>
      <main id="main-content" tabindex="-1">
        <RouterView />
      </main>
    </div>
  </div>
  <button v-if="navigationOpen" class="nav-scrim" type="button" aria-label="关闭导航" @click="closeNavigation" />
  <AssistantWidget />
</template>

<style scoped>
/* 批处理页面在常规顶栏下只保留一个纵向滚动区。 */
.batch-workspace { height: 100vh; height: 100dvh; overflow: hidden; }
.batch-workspace > .topbar { position: relative; top: auto; flex-shrink: 0; }
.batch-workspace > main { flex: 1 1 auto; min-height: 0; overflow-y: auto; overscroll-behavior-y: contain; }
.account-alert { width: 8px; height: 8px; background: var(--sv-semantic-warn); border-radius: 50%; }
.account-chip .el-icon { color: var(--sv-text-tertiary); font-size: 12px; }
.dropdown-meta { color: var(--sv-text-tertiary); font-size: var(--sv-font-meta); }
.nav-scrim { position: fixed; inset: 0; z-index: 25; background: rgb(15 23 42 / 40%); border: 0; }
</style>
