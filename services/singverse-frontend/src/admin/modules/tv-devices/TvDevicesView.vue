<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref } from 'vue'
import { ElAlert, ElButton, ElInput, ElMessage, ElSkeleton, ElTag } from 'element-plus'
import { RefreshRight } from '@element-plus/icons-vue'
import { authRequest } from '../../core/auth/api'
import PageHeader from '../../shared/ui/PageHeader.vue'
import { confirmDanger } from '../../shared/ui/confirm'
type Device = { id: string; name: string; revoked: boolean }
type Pending = { id: string; name: string; expiresAt: string }
const data = ref<{ devices: Device[]; pending: Pending[] }>({ devices: [], pending: [] })
/** 轮询只更新 loadError；用户操作的失败保留在 actionError，直到下一次操作或手动关闭。 */
const loadError = ref(''), actionError = ref(''), busy = ref(false), loaded = ref(false), refreshing = ref(false)
const codes = ref<Record<string, string>>({})
const devices = computed(() => [...data.value.devices].sort((a, b) => Number(a.revoked) - Number(b.revoked)))
const activeCount = computed(() => data.value.devices.filter(device => !device.revoked).length)
let timer: ReturnType<typeof setInterval> | undefined
const message = (e: unknown, fallback: string) => e instanceof Error ? e.message : fallback
const expiry = (value: string) => new Date(value).toLocaleTimeString('zh-CN', { hour: '2-digit', minute: '2-digit', hour12: false })

async function load(manual = false) {
  if (manual) refreshing.value = true
  try { data.value = await authRequest('/api/v1/admin/tv-devices'); loadError.value = ''; loaded.value = true }
  catch (e) { loadError.value = message(e, '加载失败') }
  finally { refreshing.value = false }
}
async function approve(p: Pending) {
  if (busy.value) return
  if (!/^\d{6}$/.test(codes.value[p.id] ?? '')) { actionError.value = '请输入电视上显示的六位数字授权码'; return }
  busy.value = true; actionError.value = ''
  try {
    await authRequest(`/api/v1/admin/device-authorizations/${p.id}/approve`, 'POST', { code: codes.value[p.id] })
    delete codes.value[p.id]
    ElMessage.success(`已批准「${p.name}」，电视将自动完成连接`)
    await load()
  } catch (e) { actionError.value = message(e, '批准失败') } finally { busy.value = false }
}
async function deny(p: Pending) {
  if (busy.value) return
  busy.value = true; actionError.value = ''
  try { await authRequest(`/api/v1/admin/device-authorizations/${p.id}`, 'DELETE'); delete codes.value[p.id]; ElMessage.success(`已拒绝「${p.name}」的申请`); await load() }
  catch (e) { actionError.value = message(e, '拒绝失败') } finally { busy.value = false }
}
async function revoke(d: Device) {
  if (busy.value || !await confirmDanger('撤销电视访问', `撤销后「${d.name}」将立即无法访问曲库，需要在电视上重新申请授权。`, '撤销访问', '保留')) return
  busy.value = true; actionError.value = ''
  try { await authRequest(`/api/v1/admin/tv-devices/${d.id}`, 'DELETE'); ElMessage.success(`已撤销「${d.name}」的访问`); await load() }
  catch (e) { actionError.value = message(e, '撤销失败') } finally { busy.value = false }
}
onMounted(() => { void load(); timer = setInterval(() => { if (!busy.value) void load() }, 5000) })
onUnmounted(() => clearInterval(timer))
</script>
<template>
  <section class="tv-devices">
    <PageHeader title="电视设备" description="电视首次连接时会显示设备名称和六位授权码，核对一致后批准。电视只能访问曲库与聚会，不会获得管理员权限。">
      <template #actions><ElButton :icon="RefreshRight" :loading="refreshing" @click="load(true)">刷新</ElButton></template>
    </PageHeader>
    <ElAlert v-if="actionError" :title="actionError" type="error" show-icon @close="actionError = ''" />
    <ElAlert v-if="loadError" :title="loaded ? `最新状态读取失败：${loadError}，正在自动重试` : loadError" :type="loaded ? 'warning' : 'error'" show-icon :closable="false"><ElButton v-if="!loaded" text @click="load(true)">重试</ElButton></ElAlert>
    <ElSkeleton v-if="!loaded && !loadError" :rows="4" animated />
    <template v-if="loaded">
      <section class="sv-panel" aria-labelledby="tv-pending-title">
        <div class="sv-panel-head"><h2 id="tv-pending-title">等待批准 <span v-if="data.pending.length" class="sv-count-pill warn">{{ data.pending.length }}</span></h2><span class="muted">每 5 秒自动刷新</span></div>
        <p v-if="!data.pending.length" class="device-empty">暂无申请。在电视端 SingVerse 中输入本机 NAS 地址即可发起授权。</p>
        <form v-for="p in data.pending" :key="p.id" class="device-row authorization-form" @submit.prevent="approve(p)">
          <div class="device-info"><h3>{{ p.name }}</h3><p>有效至 {{ expiry(p.expiresAt) }}，过期后需在电视上重新申请</p></div>
          <label class="code-field" :for="`authorization-code-${p.id}`"><span>电视上的六位授权码</span><ElInput :id="`authorization-code-${p.id}`" v-model="codes[p.id]" type="text" inputmode="numeric" autocomplete="one-time-code" maxlength="6" placeholder="000000" :aria-label="`${p.name} 的授权码`" :disabled="busy" /></label>
          <div class="device-actions"><ElButton type="primary" native-type="submit" :disabled="busy || !/^\d{6}$/.test(codes[p.id] ?? '')">批准连接</ElButton><ElButton :disabled="busy" @click="deny(p)">拒绝</ElButton></div>
        </form>
      </section>
      <section class="sv-panel" aria-labelledby="tv-devices-title">
        <div class="sv-panel-head"><h2 id="tv-devices-title">已授权电视 <span class="sv-count-pill">{{ activeCount }}</span></h2></div>
        <p v-if="!data.devices.length" class="device-empty">还没有授权的电视。批准第一台电视后会显示在这里。</p>
        <article v-for="d in devices" :key="d.id" class="device-row" :class="{ 'is-revoked': d.revoked }">
          <div class="device-info"><h3>{{ d.name }}</h3><p>{{ d.revoked ? '访问已撤销，需要重新授权' : '可访问曲库与本机聚会' }}</p></div>
          <ElTag :type="d.revoked ? 'info' : 'success'" effect="light">{{ d.revoked ? '已撤销' : '已授权' }}</ElTag>
          <ElButton v-if="!d.revoked" type="danger" plain :disabled="busy" @click="revoke(d)">撤销访问</ElButton>
        </article>
      </section>
    </template>
  </section>
</template>
<style scoped>
.tv-devices { display: grid; gap: 16px; }
.tv-devices > .page-heading { margin-bottom: 4px; }
.sv-panel-head h2 { display: flex; gap: 8px; align-items: center; }
.device-empty { padding: 24px 20px; color: var(--sv-text-tertiary); font-size: var(--sv-font-meta); }
.device-row { display: flex; gap: 16px; align-items: center; padding: 14px 20px; border-bottom: 1px solid var(--sv-border-subtle); }
.device-row:last-child { border-bottom: 0; }
.device-info { flex: 1; min-width: 0; }
.device-info h3 { margin: 0; font-size: var(--sv-font-body); font-weight: 600; overflow-wrap: anywhere; }
.device-info p { margin-top: 2px; color: var(--sv-text-secondary); font-size: var(--sv-font-meta); }
.device-row.is-revoked h3 { color: var(--sv-text-tertiary); }
.code-field { display: grid; flex: 0 0 180px; gap: 4px; color: var(--sv-text-secondary); font-size: var(--sv-font-label); }
.code-field :deep(input) { font-family: var(--sv-font-mono); font-size: 16px; letter-spacing: 3px; }
.device-actions { display: flex; gap: 8px; align-self: flex-end; }
.device-actions .el-button + .el-button { margin-left: 0; }
@media (max-width: 760px) { .device-row { flex-wrap: wrap; padding: 14px 16px; } .device-info { flex-basis: 100%; } .code-field { flex: 1 1 160px; } }
</style>
