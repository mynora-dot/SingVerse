<script setup lang="ts">
import PageHeader from '../../../shared/ui/PageHeader.vue'
import { ref, onMounted, onBeforeUnmount } from 'vue'
import { useRouter } from 'vue-router'
import { ElAlert, ElButton, ElInput, ElForm, ElFormItem, ElDialog, ElMessageBox, ElTag } from 'element-plus'
import { auth, authRequest, refreshAccount } from '../../../core/auth/api'
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
    const credentials = { password: password.value, code: code.value, newPassword: next.value, newUsername: next.value }
    if (operation.value === 'setup') {
      setup.value = await authRequest('/api/v1/system/parameters/mfa/setup', 'POST', credentials)
      operation.value = ''; password.value = ''; code.value = ''; return
    }
    if (operation.value === 'recovery') {
      const result = await authRequest<{ recoveryCodes: string[] }>('/api/v1/system/parameters/mfa/recovery-codes', 'POST', credentials)
      recovery.value = result.recoveryCodes; auth.account = null
    } else {
      await authRequest(operation.value === 'username' ? '/api/v1/auth/username' : operation.value === 'password' ? '/api/v1/auth/password' : '/api/v1/system/parameters/mfa/disable', 'POST', credentials)
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
    <PageHeader title="账号与安全" description="修改登录账号、密码，开启或管理二次验证。修改账号或密码后所有设备都需要重新登录。" />
    <ElAlert v-if="auth.account?.defaultPassword" title="当前仍在使用默认密码，请及时修改并开启二次验证。" type="warning" :closable="false" show-icon />
    <ElAlert v-if="error" :title="error" type="error" :closable="false" show-icon />
    <section class="security-list" aria-label="账号安全设置">
      <div class="security-row"><div><h2>登录账号 <span class="account-name">{{ auth.account?.username }}</span></h2><p>用于登录当前 NAS 的单管理员账号。</p></div><ElButton @click="start('username')">修改登录账号</ElButton></div>
      <div class="security-row"><div><h2>登录密码 <ElTag :type="auth.account?.defaultPassword ? 'warning' : 'success'" effect="light">{{ auth.account?.defaultPassword ? '待修改' : '已设置' }}</ElTag></h2><p>15～128 个字符。修改账号或密码后，所有设备都需要重新登录。</p></div><ElButton @click="start('password')">修改密码</ElButton></div>
      <div class="security-row"><div><h2>二次验证 <ElTag :type="auth.account?.mfaEnabled ? 'success' : 'info'" effect="light">{{ auth.account?.mfaEnabled ? '已开启' : '未开启' }}</ElTag></h2><p>使用认证器动态码（TOTP），登录时同时验证密码与动态码。</p></div><div class="button-row"><ElButton @click="start('setup')">{{ auth.account?.mfaEnabled ? '重新绑定认证器' : '开启二次验证' }}</ElButton><ElButton v-if="auth.account?.mfaEnabled" type="danger" plain @click="start('disable')">关闭二次验证</ElButton></div></div>
      <div class="security-row"><div><h2>恢复码</h2><p>{{ auth.account?.mfaEnabled ? `剩余 ${auth.account.recoveryCodesRemaining} 枚。每枚只能使用一次，请离线妥善保管。` : '开启二次验证后生成，可在无法使用认证器时登录。' }}</p></div><ElButton v-if="auth.account?.mfaEnabled" @click="start('recovery')">重新生成恢复码</ElButton></div>
    </section>
    <ElDialog :model-value="!!operation" :title="operation === 'username' ? '修改登录账号' : operation === 'password' ? '修改密码' : '验证身份'" width="440px" @close="operation = ''; password = ''; code = ''; next = ''; confirmPassword = ''">
      <ElForm label-position="top" @submit.prevent="submit">
        <ElFormItem label="当前密码"><ElInput v-model="password" type="password" show-password autocomplete="current-password" /></ElFormItem>
        <ElFormItem v-if="auth.account?.mfaEnabled" label="动态码 / 恢复码"><ElInput v-model="code" autocomplete="one-time-code" maxlength="128" /></ElFormItem>
        <template v-if="operation === 'username'"><ElFormItem label="新登录账号"><ElInput v-model="next" autocomplete="username" maxlength="32" /></ElFormItem><p class="muted dialog-hint">3～32 个字母、数字、下划线、点或短横线，以字母、数字或下划线开头，区分大小写。修改后请用新账号重新登录。</p></template>
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
.security-list { border-top: 1px solid var(--sv-border-subtle); }
.security-row { display: flex; align-items: center; justify-content: space-between; gap: 16px 24px; padding: 24px 0; border-bottom: 1px solid var(--sv-border-subtle); }
.security-row>div:first-child { flex: 1; min-width: 0; }
.security-row h2 { display: flex; align-items: center; gap: 12px; font-size: 16px; flex-wrap: wrap; }
.security-row p { margin-top: 8px; color: var(--sv-text-secondary); font-size: 14px; line-height: 1.7; }
.account-name { font-weight: 400; color: var(--sv-text-secondary); overflow-wrap: anywhere; }
@media(max-width:650px) { .security-row { flex-wrap: wrap; padding: 20px 0; }.security-row>div:first-child { flex-basis: 100%; } }
.dialog-hint {
  margin: 4px 0 16px;
}
.setup-secret {
  margin: 0 0 12px;
  font-size: 13px;
  color: var(--sv-text-secondary);
}
</style>
