<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, ref, watch } from 'vue'
import { ElButton, ElDropdown, ElDropdownItem, ElDropdownMenu, ElIcon, ElMessage } from 'element-plus'
import { ArrowDown, Close, Key, Menu, Search, SwitchButton, TopRight, Clock, Setting } from '@element-plus/icons-vue'
import AssistantWidget from '../../modules/ai-settings/components/AssistantWidget.vue'
import ThemeSwitcher from '../../shared/ui/ThemeSwitcher.vue'
import { navigationContext, navigationGroups, overviewEntry, taskEntry } from '../navigation'
import { useActiveTaskCount } from '../composables/use-active-task-count'
import { auth, authRequest } from '../../core/auth/api'
import { useRouter, useRoute } from 'vue-router'

const route = useRoute(), router = useRouter()
const navigationOpen = ref(false), navToggle = ref<InstanceType<typeof ElButton>>(), navigationList = ref<HTMLElement>()
const sectionNavigation = ref<HTMLElement>()
const currentNavigation = computed(() => navigationContext(String(route.meta.activeMenu || route.path)))
const activePath = computed(() => currentNavigation.value.entry?.path ?? route.path)
watch(activePath, async () => {
  await nextTick()
  const nav = sectionNavigation.value?.querySelector<HTMLElement>('nav')
  const current = nav?.querySelector<HTMLElement>('[aria-current="page"]')
  if (!nav || !current) return
  const bounds = nav.getBoundingClientRect(), entry = current.getBoundingClientRect()
  if (entry.left < bounds.left) nav.scrollLeft += entry.left - bounds.left - 12
  else if (entry.right > bounds.right) nav.scrollLeft += entry.right - bounds.right + 12
}, { immediate: true })
const primaryGroups = navigationGroups.filter(group => group.id !== 'settings')
const { count: taskCount } = useActiveTaskCount()
const taskBadge = computed(() => taskCount.value > 99 ? '99+' : String(taskCount.value))
const searchTerm = ref('')
function submitSearch() {
  const term = searchTerm.value.trim()
  void router.push({ path: '/library/songs', query: { lens: 'songs', ...(term ? { search: term } : {}) } })
}
const accountLabel = computed(() => auth.account?.username ?? 'admin')
const securityHint = computed(() => auth.account?.defaultPassword ? '仍在使用默认密码' : auth.account?.mfaEnabled ? '二次验证已开启' : '二次验证未开启')
let previousOverflow = ''
watch(navigationOpen, open => {
  if (open) { previousOverflow = document.body.style.overflow; document.body.style.overflow = 'hidden' }
  else document.body.style.overflow = previousOverflow
})
onBeforeUnmount(() => { if (navigationOpen.value) document.body.style.overflow = previousOverflow })
watch(() => route.fullPath, () => { navigationOpen.value = false })
async function toggleNavigation() {
  navigationOpen.value = !navigationOpen.value
  if (navigationOpen.value) { await nextTick(); (navigationList.value?.querySelector<HTMLElement>('[aria-current="page"]') ?? navigationList.value?.querySelector<HTMLElement>('a'))?.focus() }
}
async function closeNavigation() {
  if (!navigationOpen.value) return
  navigationOpen.value = false
  await nextTick()
  ;(navToggle.value?.$el as HTMLElement | undefined)?.focus()
}
function trapNavigation(event: KeyboardEvent) {
  if (event.key === 'Escape') { event.preventDefault(); closeNavigation(); return }
  if (event.key !== 'Tab' || !navigationOpen.value) return
  const nodes = navigationList.value?.querySelectorAll<HTMLElement>('a, button')
  if (!nodes?.length) return
  const first = nodes[0]!, last = nodes[nodes.length - 1]!
  if (event.shiftKey && document.activeElement === first) { event.preventDefault(); last.focus() }
  else if (!event.shiftKey && document.activeElement === last) { event.preventDefault(); first.focus() }
}
async function logout() {
  try { await authRequest('/api/v1/auth/logout', 'POST'); auth.account = null; await router.replace('/login') }
  catch (error) { ElMessage.error(error instanceof Error ? error.message : '退出失败，请重试') }
}
function focusMainContent() { document.getElementById('main-content')?.focus() }
</script>
<template>
  <a class="skip-link" href="#main-content" @click.prevent="focusMainContent">跳转到主要内容</a>
  <div class="admin-shell">
    <header class="app-masthead" :inert="navigationOpen || undefined">
      <div class="masthead-inner">
        <RouterLink to="/overview" class="brand" aria-label="SingVerse 家庭音乐中心首页"><img class="brand-mark" src="/assets/brand/logo-mark.png" width="34" height="34" alt=""><span><strong>SingVerse<span class="brand-dot">.</span></strong><small>家庭音乐资料室</small></span></RouterLink>
        <nav class="primary-navigation" aria-label="主导航">
          <RouterLink :to="overviewEntry.path" :aria-current="activePath === overviewEntry.path ? 'page' : undefined">{{ overviewEntry.label }}</RouterLink>
          <RouterLink v-for="group in primaryGroups" :key="group.id" :to="group.path" :aria-current="currentNavigation.group?.id === group.id ? 'page' : undefined">{{ group.label }}</RouterLink>
        </nav>
        <form class="masthead-search" role="search" @submit.prevent="submitSearch">
          <ElIcon class="search-icon" aria-hidden="true"><Search /></ElIcon>
          <input v-model="searchTerm" type="search" class="search-input" placeholder="搜索歌曲或歌手" aria-label="搜索歌曲">
        </form>
        <div class="masthead-actions">
          <RouterLink class="utility-link" :to="taskEntry.path" :aria-label="taskCount > 0 ? `任务中心，${taskCount} 个进行中` : '任务中心'" :aria-current="activePath === taskEntry.path ? 'page' : undefined"><ElIcon><Clock /></ElIcon><span class="utility-label">任务</span><span v-if="taskCount > 0" class="task-count" aria-hidden="true">{{ taskBadge }}</span></RouterLink>
          <RouterLink class="utility-link settings-link" to="/system/parameters" :aria-current="currentNavigation.group?.id === 'settings' ? 'page' : undefined" aria-label="设置"><ElIcon><Setting /></ElIcon></RouterLink>
          <ThemeSwitcher />
          <ElDropdown trigger="click" placement="bottom-end"><button class="account-chip" type="button" aria-label="账号菜单"><span class="avatar">{{ accountLabel.slice(0, 1) }}</span><span v-if="auth.account?.defaultPassword" class="account-alert" aria-label="安全提醒" /><ElIcon><ArrowDown /></ElIcon></button><template #dropdown><ElDropdownMenu><ElDropdownItem disabled>{{ accountLabel }} · {{ securityHint }}</ElDropdownItem><ElDropdownItem :icon="Key" @click="router.push('/system/parameters')">账号与安全</ElDropdownItem><ElDropdownItem divided :icon="SwitchButton" @click="logout">退出登录</ElDropdownItem></ElDropdownMenu></template></ElDropdown>
          <ElButton ref="navToggle" class="mobile-nav-toggle" :icon="navigationOpen ? Close : Menu" :aria-label="navigationOpen ? '收起导航' : '打开导航菜单'" :aria-expanded="navigationOpen" aria-controls="admin-navigation" @click="toggleNavigation" />
        </div>
      </div>
    </header>
    <div class="workspace" :inert="navigationOpen || undefined">
      <div v-if="currentNavigation.group" ref="sectionNavigation" class="section-navigation"><nav :aria-label="currentNavigation.group.label + '分区'"><RouterLink v-for="entry in currentNavigation.group.children" :key="entry.path" :to="entry.path" class="section-link" :aria-current="activePath === entry.path ? 'page' : undefined">{{ entry.label }}<small v-if="'advanced' in entry && entry.advanced">高级</small></RouterLink></nav></div>
      <main id="main-content" tabindex="-1"><RouterView /></main>
      <footer class="workspace-footer"><span>SingVerse · 你的家庭音乐资料室</span><a href="/party/" target="_blank" rel="noopener">打开亲友点歌页 <ElIcon><TopRight /></ElIcon></a></footer>
    </div>
    <div v-if="navigationOpen" class="navigation-dialog" role="dialog" aria-modal="true" aria-label="全部功能" @keydown="trapNavigation">
      <nav id="admin-navigation" ref="navigationList" class="navigation-sheet" aria-label="全部导航">
        <div class="sheet-heading"><strong>全部功能</strong><button type="button" aria-label="关闭导航" @click="closeNavigation"><ElIcon><Close /></ElIcon></button></div>
        <RouterLink :to="overviewEntry.path" class="nav-item" @click="closeNavigation" :aria-current="activePath === overviewEntry.path ? 'page' : undefined"><ElIcon><component :is="overviewEntry.icon" /></ElIcon>{{ overviewEntry.label }}</RouterLink>
        <section v-for="group in navigationGroups" :key="group.id" class="nav-group" :aria-labelledby="`nav-group-${group.id}`"><h2 :id="`nav-group-${group.id}`" class="nav-group-title">{{ group.label }}</h2><ul><li v-for="entry in group.children" :key="entry.path"><RouterLink :to="entry.path" class="nav-item" @click="closeNavigation" :aria-current="activePath === entry.path ? 'page' : undefined"><ElIcon><component :is="entry.icon" /></ElIcon>{{ entry.label }}<small v-if="'advanced' in entry && entry.advanced">高级</small></RouterLink></li></ul></section>
        <ThemeSwitcher variant="sheet" />
        <RouterLink :to="taskEntry.path" class="nav-item" @click="closeNavigation" :aria-current="activePath === taskEntry.path ? 'page' : undefined"><ElIcon><Clock /></ElIcon>{{ taskEntry.label }}<span v-if="taskCount > 0" class="task-count" aria-hidden="true">{{ taskBadge }}</span></RouterLink><a class="nav-item" href="/party/" target="_blank" rel="noopener">亲友点歌页 <ElIcon><TopRight /></ElIcon></a>
      </nav>
    </div>
    <button v-if="navigationOpen" class="nav-scrim" type="button" tabindex="-1" aria-label="关闭导航背景" @click="closeNavigation" />
  </div>
  <div :inert="navigationOpen || undefined"><AssistantWidget /></div>
</template>
