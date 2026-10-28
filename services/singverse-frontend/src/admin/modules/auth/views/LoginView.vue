<script setup lang="ts">
import { ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElButton, ElInput, ElAlert, ElForm, ElFormItem, ElIcon } from 'element-plus'
import { ArrowRight, Headset } from '@element-plus/icons-vue'
import { authRequest, refreshAccount } from '../../../core/auth/api'
const route = useRoute(), router = useRouter()
const password = ref(''), code = ref(''), stage = ref(false), busy = ref(false), error = ref('')
const bars = [16, 30, 48, 25, 62, 38, 72, 30, 54, 22, 43, 66, 27, 49, 19]
async function submit() {
  busy.value = true; error.value = ''
  try {
    const result = stage.value
      ? await authRequest<{ state: string }>('/api/v1/auth/mfa/verify', 'POST', { code: code.value })
      : await authRequest<{ state: string }>('/api/v1/auth/login', 'POST', { username: 'admin', password: password.value })
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
        <span class="brand-mark"><ElIcon :size="22"><Headset /></ElIcon></span>
        <span><strong>SingVerse<span class="brand-dot">.</span></strong><small>NAS 管理中心</small></span>
      </div>
      <div>
        <div class="login-hero">
          <h2>让每一次相聚，<br />都有好歌相伴。</h2>
          <p>自己的歌曲，熟悉的朋友。登录后打理媒体库、解析规则与播放设备。</p>
        </div>
        <div class="waveform"><i v-for="(height, index) in bars" :key="index" :style="{ height: `${height}px`, animationDelay: `${index * 0.11}s` }" /></div>
      </div>
      <p class="login-foot">家庭自托管 KTV · 数据留在你的 NAS</p>
    </aside>
    <main class="login-main">
      <section class="login-card" aria-labelledby="login-title">
        <p class="login-eyebrow">SINGVERSE · 家庭音乐空间</p>
        <h1 id="login-title">{{ stage ? '二次验证' : '登录管理中心' }}</h1>
        <p class="muted">{{ stage ? '输入认证器动态码，或使用一枚恢复码。' : '使用你的管理员账号，打理家的音乐。' }}</p>
        <ElAlert v-if="error" :title="error" type="error" :closable="false" show-icon />
        <ElForm label-position="top" @submit.prevent="submit">
          <ElFormItem v-if="!stage" label="账号"><ElInput model-value="admin" readonly autocomplete="username" size="large" /></ElFormItem>
          <ElFormItem v-if="!stage" label="密码"><ElInput v-model="password" type="password" show-password autocomplete="current-password" maxlength="256" size="large" autofocus /></ElFormItem>
          <ElFormItem v-else label="动态码 / 恢复码"><ElInput v-model="code" autocomplete="one-time-code" maxlength="128" size="large" autofocus /></ElFormItem>
          <ElButton native-type="submit" type="primary" size="large" :loading="busy" :disabled="stage ? !code : !password" class="full-width">
            {{ stage ? '验证并登录' : '登录' }}<ElIcon v-if="!stage" class="el-icon--right"><ArrowRight /></ElIcon>
          </ElButton>
          <ElButton v-if="stage" link @click="restart">返回密码登录</ElButton>
        </ElForm>
        <p class="muted login-note">忘记密码或丢失验证方式？请在部署主机上使用管理员恢复命令。</p>
      </section>
    </main>
  </div>
</template>
