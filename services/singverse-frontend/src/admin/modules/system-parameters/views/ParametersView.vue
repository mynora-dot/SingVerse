<script setup lang="ts">
import { ref, onMounted, onBeforeUnmount } from 'vue'
import { useRouter } from 'vue-router'
import { ElAlert, ElButton, ElCard, ElInput, ElForm, ElFormItem, ElDialog, ElMessageBox, ElIcon, ElTag } from 'element-plus'
import { auth, authRequest, refreshAccount } from '../../../core/auth/api'
import { User, Lock, Key } from '@element-plus/icons-vue'
const router = useRouter()
const password = ref(''), code = ref(''), next = ref(''), confirmPassword = ref(''), error = ref(''), busy = ref(false)
const operation = ref(''), setup = ref<{ secret: string; qrDataUrl: string; expiresAt: string } | null>(null)
const bindingCode = ref(''), recovery = ref<string[]>([])
onMounted(() => refreshAccount().catch(e => { error.value = e.message }))
onBeforeUnmount(() => { password.value = ''; code.value = ''; recovery.value = []; setup.value = null })
function start(action: string) { operation.value = action; error.value = ''; password.value = ''; code.value = ''; next.value = ''; confirmPassword.value = '' }
async function signedOut() { auth.account = null; await router.replace('/login') }
async function submit() {
  if (operation.value === 'password' && next.value !== confirmPassword.value) { error.value = '两次输入的新密码不一致'; return }
  busy.value = true; error.value = ''
  try {
    const credentials = { password: password.value, code: code.value, newPassword: next.value }
    if (operation.value === 'setup') {
      setup.value = await authRequest('/api/v1/system/parameters/mfa/setup', 'POST', credentials)
      operation.value = ''; password.value = ''; code.value = ''; return
    }
    if (operation.value === 'recovery') {
      const result = await authRequest<{ recoveryCodes: string[] }>('/api/v1/system/parameters/mfa/recovery-codes', 'POST', credentials)
      recovery.value = result.recoveryCodes; auth.account = null
    } else {
      await authRequest(operation.value === 'password' ? '/api/v1/auth/password' : '/api/v1/system/parameters/mfa/disable', 'POST', credentials)
    }
    operation.value = ''; password.value = ''; code.value = ''; next.value = ''; confirmPassword.value = ''
    if (!recovery.value.length) await signedOut()
  } catch (e) { error.value = e instanceof Error ? e.message : '操作失败' }
  finally { busy.value = false }
}
async function confirmBinding() {
  busy.value = true; error.value = ''
  try {
    const result = await authRequest<{ recoveryCodes: string[] }>('/api/v1/system/parameters/mfa/confirm', 'POST', { code: bindingCode.value })
    recovery.value = result.recoveryCodes; setup.value = null; bindingCode.value = ''; auth.account = null
  } catch (e) { error.value = e instanceof Error ? e.message : '操作失败' }
  finally { busy.value = false }
}
async function cancelBinding() {
  try { await authRequest('/api/v1/system/parameters/mfa/setup', 'DELETE'); setup.value = null; bindingCode.value = '' }
  catch (e) { error.value = e instanceof Error ? e.message : '取消失败' }
}
function download() {
  const url = URL.createObjectURL(new Blob(['SingVerse admin MFA recovery codes\n每枚仅可使用一次，请离线妥善保管。\n\n' + recovery.value.join('\n')], { type: 'text/plain;charset=utf-8' }))
  const link = document.createElement('a'); link.href = url; link.download = 'singverse-recovery-codes.txt'; link.click(); URL.revokeObjectURL(url)
}
async function closeRecovery() {
  await ElMessageBox.confirm('确认已妥善保存恢复码？关闭后无法再次查看。', '保存恢复码', { confirmButtonText: '已保存，重新登录', cancelButtonText: '返回保存' }).then(async () => { recovery.value = []; await signedOut() }).catch(() => {})
}
</script>
<template>
  <div class="parameters-page">
    <div class="page-heading"><div><h1>系统参数</h1><p class="page-description">管理你的管理员账号，守护家庭音乐空间。</p></div><span class="page-context"><ElIcon><Lock /></ElIcon> 账号与安全</span></div>
    <ElAlert v-if="auth.account?.defaultPassword" title="当前仍在使用默认密码，请及时修改并开启二次验证。" type="warning" :closable="false" show-icon />
    <ElAlert v-if="error" :title="error" type="error" :closable="false" show-icon />
    <div class="account-banner"><span class="settings-icon"><ElIcon><User /></ElIcon></span><div><h2>admin</h2><p>管理员 · 管理当前 NAS 的全部设置</p></div><ElTag effect="plain" round>单管理员账号</ElTag></div>
    <div class="security-grid">
      <ElCard class="security-card" shadow="never">
        <div class="security-heading"><span class="settings-icon"><ElIcon><Key /></ElIcon></span><ElTag :type="auth.account?.defaultPassword ? 'warning' : 'success'" effect="light" round>{{ auth.account?.defaultPassword ? '待修改' : '已设置' }}</ElTag></div>
        <h2>登录密码</h2><p class="settings-description">使用独立的强密码，保护管理员访问权限。</p>
        <div class="settings-detail"><span>当前账号</span><strong>admin</strong></div><div class="settings-detail"><span>密码要求</span><strong>15～128 个字符</strong></div>
        <div class="security-actions"><ElButton @click="start('password')">修改密码</ElButton><p>修改后，所有设备都需要重新登录。</p></div>
      </ElCard>
      <ElCard class="security-card" shadow="never">
        <div class="security-heading"><span class="settings-icon"><ElIcon><Lock /></ElIcon></span><ElTag :type="auth.account?.mfaEnabled ? 'success' : 'info'" effect="light" round>{{ auth.account?.mfaEnabled ? '已开启' : '未开启' }}</ElTag></div>
        <h2>二次验证 <span class="heading-suffix">MFA</span></h2><p class="settings-description">通过认证器动态码，为登录多加一层保护。</p>
        <div class="settings-detail"><span>验证方式</span><strong>认证器动态码（TOTP）</strong></div><div class="settings-detail"><span>恢复码</span><strong>{{ auth.account?.mfaEnabled ? `剩余 ${auth.account.recoveryCodesRemaining} 枚` : '开启后生成' }}</strong></div>
        <div class="security-actions"><div class="button-row"><ElButton type="primary" @click="start('setup')">{{ auth.account?.mfaEnabled ? '重新绑定认证器' : '开启二次验证' }}</ElButton><ElButton v-if="auth.account?.mfaEnabled" @click="start('recovery')">重新生成恢复码</ElButton><ElButton v-if="auth.account?.mfaEnabled" type="danger" plain @click="start('disable')">关闭二次验证</ElButton></div><p>绑定后，登录时需同时验证密码与动态码。</p></div>
      </ElCard>
    </div>
    <ElDialog :model-value="!!operation" :title="operation === 'password' ? '修改密码' : '验证身份'" width="440px" @close="operation = ''; password = ''; code = ''; next = ''; confirmPassword = ''">
      <ElForm label-position="top" @submit.prevent="submit">
        <ElFormItem label="当前密码"><ElInput v-model="password" type="password" show-password autocomplete="current-password" /></ElFormItem>
        <ElFormItem v-if="auth.account?.mfaEnabled" label="动态码 / 恢复码"><ElInput v-model="code" autocomplete="one-time-code" maxlength="128" /></ElFormItem>
        <template v-if="operation === 'password'"><ElFormItem label="新密码（15～128 个字符）"><ElInput v-model="next" type="password" show-password autocomplete="new-password" /></ElFormItem><ElFormItem label="确认新密码"><ElInput v-model="confirmPassword" type="password" show-password autocomplete="new-password" /></ElFormItem></template>
        <p v-if="operation === 'disable' || operation === 'recovery'" class="muted dialog-hint">此操作会使现有登录会话失效。</p>
        <ElAlert v-if="error" :title="error" type="error" :closable="false" />
        <div class="dialog-actions"><ElButton @click="operation = ''">取消</ElButton><ElButton type="primary" native-type="submit" :loading="busy" :disabled="!password">确认</ElButton></div>
      </ElForm>
    </ElDialog>
    <ElDialog :model-value="!!setup" title="绑定认证器" width="440px" :close-on-click-modal="false" :show-close="false">
      <template v-if="setup"><p class="muted dialog-hint">使用认证器扫描二维码，再输入动态码确认。5 分钟内有效。</p><img class="mfa-qr" :src="setup.qrDataUrl" alt="认证器绑定二维码" /><p class="setup-secret">手动密钥：<code class="secret-key">{{ setup.secret }}</code></p><ElInput v-model="bindingCode" autocomplete="one-time-code" placeholder="6 位动态码" maxlength="6" /><ElAlert v-if="error" :title="error" type="error" :closable="false" /><div class="dialog-actions"><ElButton @click="cancelBinding">取消绑定</ElButton><ElButton type="primary" :loading="busy" @click="confirmBinding">确认开启</ElButton></div></template>
    </ElDialog>
    <ElDialog :model-value="!!recovery.length" title="保存恢复码" width="480px" :show-close="false" :close-on-click-modal="false" :close-on-press-escape="false">
      <ElAlert title="恢复码只展示这一次。每枚仅能使用一次，请离线保存。" type="warning" :closable="false" /><pre class="recovery-codes">{{ recovery.join('\n') }}</pre><p class="muted dialog-hint">安全设置已更新，保存后需要重新登录。</p><div class="dialog-actions"><ElButton @click="download">下载恢复码</ElButton><ElButton type="primary" @click="closeRecovery">已保存</ElButton></div>
    </ElDialog>
  </div>
</template>

<style scoped>
.dialog-hint {
  margin: 4px 0 16px;
}
.setup-secret {
  margin: 0 0 12px;
  font-size: 13px;
  color: var(--sv-text-secondary);
}
</style>
