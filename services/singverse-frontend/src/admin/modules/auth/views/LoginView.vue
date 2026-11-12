<script setup lang="ts">
import { ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElButton, ElInput, ElAlert, ElForm, ElFormItem, ElIcon } from 'element-plus'
import { ArrowRight } from '@element-plus/icons-vue'
import { authRequest, refreshAccount } from '../../../core/auth/api'
const route = useRoute(), router = useRouter()
const username = ref('admin')
const password = ref(''), code = ref(''), stage = ref(false), busy = ref(false), error = ref('')
async function submit() {
  busy.value = true; error.value = ''
  try {
    const result = stage.value
      ? await authRequest<{ state: string }>('/api/v1/auth/mfa/verify', 'POST', { code: code.value })
      : await authRequest<{ state: string }>('/api/v1/auth/login', 'POST', { username: username.value, password: password.value })
    password.value = ''; code.value = ''
    if (result.state === 'mfaRequired') { stage.value = true; return }
    await refreshAccount()
    const redirect = typeof route.query.redirect === 'string' ? route.query.redirect : '/overview'
    await router.replace(redirect.startsWith('/') && !redirect.startsWith('//') && !redirect.startsWith('/login') ? redirect : '/overview')
  } catch (e) { error.value = e instanceof Error ? e.message : '登录失败' }
  finally { busy.value = false }
}
function restart() { stage.value = false; code.value = ''; password.value = ''; error.value = '' }
</script>
<template>
  <div class="login-page">
    <aside class="login-aside" aria-hidden="true">
      <div class="login-brand">
        <img class="brand-mark" src="/assets/brand/logo-mark.png" width="32" height="32" alt="">
        <span><strong>SingVerse<span class="brand-dot">.</span></strong><small>NAS 管理中心</small></span>
      </div>
      <div>
        <div class="login-hero">
          <h2>家庭音乐资料室</h2>
          <p>连接 NAS 上的歌曲，整理曲库与歌单，为客厅里的下一次欢唱做好准备</p>
        </div>
      </div>
      <p class="login-foot">家庭自托管 KTV · 数据留在你的 NAS</p>
    </aside>
    <main class="login-main">
      <section class="login-card" aria-labelledby="login-title">
        <h1 id="login-title">{{ stage ? '二次验证' : '登录管理中心' }}</h1>
        <p class="muted">{{ stage ? '输入验证器动态码，或使用一枚恢复码' : '使用管理员账号管理家庭曲库' }}</p>
        <ElAlert v-if="error" :title="error" type="error" :closable="false" show-icon />
        <ElForm label-position="top" @submit.prevent="submit">
          <ElFormItem v-if="!stage" label="账号"><ElInput v-model="username" maxlength="32" autocomplete="username" size="large" /></ElFormItem>
          <ElFormItem v-if="!stage" label="密码"><ElInput v-model="password" type="password" show-password autocomplete="current-password" maxlength="256" size="large" autofocus /></ElFormItem>
          <ElFormItem v-else label="动态码 / 恢复码"><ElInput v-model="code" autocomplete="one-time-code" maxlength="128" size="large" autofocus /></ElFormItem>
          <ElButton native-type="submit" type="primary" size="large" :loading="busy" :disabled="stage ? !code : !password" class="full-width">
            {{ stage ? '验证并登录' : '登录' }}<ElIcon v-if="!stage" class="el-icon--right"><ArrowRight /></ElIcon>
          </ElButton>
          <ElButton v-if="stage" link @click="restart">返回密码登录</ElButton>
        </ElForm>
        <p class="muted login-note">忘记密码或丢失验证方式时，请在部署主机上使用管理员恢复命令</p>
      </section>
    </main>
  </div>
</template>
