<script setup lang="ts">
import { onMounted, onUnmounted, ref } from 'vue'
import { ElAlert, ElButton, ElCard, ElEmpty, ElInput } from 'element-plus'
import { authRequest } from '../../core/auth/api'
type Device = { id: string; name: string; revoked: boolean }
type Pending = { id: string; name: string; expiresAt: string }
const data = ref<{ devices: Device[]; pending: Pending[] }>({ devices: [], pending: [] })
const error = ref(''), busy = ref(false), codes = ref<Record<string, string>>({})
let timer: ReturnType<typeof setInterval> | undefined
async function load() { try { data.value = await authRequest('/api/v1/admin/tv-devices'); error.value = '' } catch (e) { error.value = e instanceof Error ? e.message : '加载失败' } }
async function approve(p: Pending) { if (busy.value) return; if (!/^\d{6}$/.test(codes.value[p.id] ?? '')) { error.value = '请输入电视上显示的六位数字授权码'; return }; busy.value = true; try { await authRequest(`/api/v1/admin/device-authorizations/${p.id}/approve`, 'POST', { code: codes.value[p.id] }); delete codes.value[p.id]; await load() } catch (e) { error.value = e instanceof Error ? e.message : '批准失败' } finally { busy.value = false } }
async function deny(p: Pending) { busy.value = true; try { await authRequest(`/api/v1/admin/device-authorizations/${p.id}`, 'DELETE'); await load() } catch (e) { error.value = e instanceof Error ? e.message : '拒绝失败' } finally { busy.value = false } }
async function revoke(d: Device) { if (!confirm(`撤销「${d.name}」的访问？该电视需要重新授权。`)) return; busy.value = true; try { await authRequest(`/api/v1/admin/tv-devices/${d.id}`, 'DELETE'); await load() } catch (e) { error.value = e instanceof Error ? e.message : '撤销失败' } finally { busy.value = false } }
onMounted(() => { void load(); timer = setInterval(() => { if (!busy.value) void load() }, 5000) })
onUnmounted(() => clearInterval(timer))
</script>
<template>
  <section class="tv-devices">
    <header><div><p class="eyebrow">客厅设备</p><h1>让好歌回到电视上</h1><p>核对电视显示的设备名称和六位授权码，再批准连接。电视不会获得管理员权限。</p></div><el-button @click="load">刷新</el-button></header>
    <el-alert v-if="error" :title="error" type="error" show-icon />
    <el-card><template #header><strong>等待你的确认</strong></template><el-empty v-if="!data.pending.length" description="暂无申请，在电视上输入 NAS 地址即可发起授权" />
      <form v-for="p in data.pending" :key="p.id" class="device-row authorization-form" @submit.prevent="approve(p)">
        <div class="device-info"><h3>{{ p.name }}</h3><p>填写电视上显示的授权码</p><small>有效至 {{ new Date(p.expiresAt).toLocaleTimeString() }}</small></div>
        <label class="code-field" :for="`authorization-code-${p.id}`"><span>六位授权码</span><el-input :id="`authorization-code-${p.id}`" v-model="codes[p.id]" type="text" inputmode="numeric" autocomplete="one-time-code" maxlength="6" placeholder="请输入六位数字" :aria-label="`${p.name} 的授权码`" :disabled="busy" /></label>
        <el-button type="primary" native-type="submit" :disabled="busy || !/^\d{6}$/.test(codes[p.id] ?? '')">批准连接</el-button><el-button :disabled="busy" @click="deny(p)">拒绝</el-button>
      </form>
    </el-card>
    <el-card><template #header><strong>已授权电视</strong></template><el-empty v-if="!data.devices.length" description="还没有授权的电视" /><article v-for="d in data.devices" :key="d.id" class="device-row"><div><h3>{{ d.name }}</h3><p>{{ d.revoked ? '访问已撤销' : '已授权 · 可访问曲库与本机聚会' }}</p></div><el-button v-if="!d.revoked" type="danger" plain :disabled="busy" @click="revoke(d)">撤销访问</el-button></article></el-card>
  </section>
</template>
<style scoped>
.tv-devices{display:grid;gap:24px}.tv-devices header{display:flex;align-items:center;justify-content:space-between;gap:24px;padding:12px 0}.tv-devices h1{font-size:30px;margin:8px 0 12px}.tv-devices p{color:var(--el-text-color-secondary);line-height:1.7}.eyebrow{font-size:12px;letter-spacing:2px}.device-row{display:flex;align-items:center;gap:20px;padding:20px 0;border-bottom:1px solid var(--el-border-color-lighter)}.device-row>div:first-child{flex:1;min-width:0}.device-row .code-field{display:grid;gap:8px;flex:0 0 180px;font-size:14px}.device-row .el-input{width:100%}.device-row h3{margin:0}.device-row p{margin:6px 0}.device-row small{color:var(--el-text-color-secondary)}@media(max-width:760px){.device-row{flex-wrap:wrap}}
</style>
