<script setup lang="ts">
import { ref } from 'vue'
import { ElAlert, ElButton, ElInput } from 'element-plus'
import { authRequest } from '../../../core/auth/api'
import type { MediaSource } from '../types'
const props = defineProps<{ source: MediaSource }>()
const emit = defineEmits<{ updated: [] }>()
const location = ref(props.source.location), username = ref(props.source.username), password = ref(''), error = ref(''), busy = ref(false)
async function rebind() {
  if (busy.value) return
  busy.value = true; error.value = ''
  try {
    await authRequest(`/api/v1/media-sources/${encodeURIComponent(props.source.id)}/rebind`, 'POST', { location: location.value, username: username.value, password: password.value })
    password.value = ''; emit('updated')
  } catch (e) { error.value = e instanceof Error ? e.message : '重新绑定失败' }
  finally { busy.value = false }
}
</script>
<template>
  <details class="rebind"><summary>重新绑定自己的媒体目录</summary>
    <p>保留歌曲、资料和本地订正，使用新地址重新核对媒体。核验期间暂不进入正常点歌范围。</p>
    <label>新的 WebDAV 根目录<ElInput v-model="location" maxlength="2048" :disabled="busy" /></label>
    <label>用户名<ElInput v-model="username" autocomplete="off" maxlength="256" :disabled="busy" /></label>
    <label>新来源密码 / 应用密码（无密码可留空）<ElInput v-model="password" type="password" autocomplete="new-password" maxlength="1024" show-password :disabled="busy" /></label>
    <ElAlert v-if="error" :title="error" type="error" :closable="false" />
    <ElButton :loading="busy" :disabled="!location.trim()" @click="rebind">保留资料并重新核验</ElButton>
  </details>
</template>
<style scoped>
.rebind{margin:18px 0;font-size:13px}summary{cursor:pointer}p{color:var(--sv-text-secondary);line-height:1.7}label{display:block;margin:12px 0}label .el-input{margin-top:6px}
</style>
