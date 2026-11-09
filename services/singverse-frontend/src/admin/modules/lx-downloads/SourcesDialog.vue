<script setup lang="ts">
import { onBeforeUnmount, ref, watch } from 'vue'
import { ElAlert, ElButton, ElDialog, ElForm, ElFormItem, ElInput, ElMessage, ElMessageBox, ElTag } from 'element-plus'
import { lx, message, platforms, qualities, type LxSource } from './api'
const props = defineProps<{ modelValue: boolean }>()
const emit = defineEmits<{ 'update:modelValue': [value: boolean]; changed: [] }>()
const sources = ref<LxSource[]>([]), loading = ref(false), busy = ref(''), error = ref(''), name = ref(''), url = ref(''), script = ref(''), filename = ref(''), environment = ref('mobile'), mode = ref('file')
let generation = 0, alive = true
const capabilityLabels = (source: LxSource) => Object.entries(source.capabilities).map(([code, formats]) => `${platforms[code]}：${formats.map(format => qualities[format]).join('、')}`).join('；')
async function load() { const token = ++generation; loading.value = true; error.value = ''; try { const result = await lx.sources(); if (alive && token === generation) sources.value = result } catch (e) { if (alive && token === generation) error.value = message(e) } finally { if (alive && token === generation) loading.value = false } }
async function file(event: Event) { error.value = ''; script.value = ''; filename.value = ''; const input = event.target as HTMLInputElement; const f = input.files?.[0]; if (!f) return; if (!/\.js$/i.test(f.name) || f.size > 9_000_000) { error.value = '请选择不超过 9 MB 的 .js 音源文件'; input.value = ''; return } const token = generation; try { const content = new TextDecoder('utf-8', { fatal: true }).decode(await f.arrayBuffer()); if (!alive || token !== generation) return; script.value = content; filename.value = f.name } catch { error.value = '文件需采用 UTF-8 编码' } }
async function importing() { if (busy.value) return; const token = generation; busy.value = 'import'; error.value = ''; try { const saved = await lx.importSource({ name: name.value.trim(), environment: environment.value, ...(mode.value === 'file' ? { script: script.value } : { url: url.value.trim() }) }); await lx.activate(saved.id); emit('changed'); if (!alive || token !== generation) return; script.value = ''; url.value = ''; filename.value = ''; name.value = ''; ElMessage.success('音源已导入并启用'); await load() } catch (e) { if (alive && token === generation) error.value = message(e) } finally { if (alive) busy.value = '' } }
async function action(source: LxSource, type: 'activate' | 'test' | 'remove') { if (busy.value) return; if (type === 'remove') { try { await ElMessageBox.confirm(`移除“${source.name}”？下载记录会保留。`, '移除音源', { confirmButtonText: '移除', cancelButtonText: '保留', type: 'warning' }) } catch { return } } const token = generation; busy.value = source.id; error.value = ''; try { if (type === 'test') { const result = await lx.test(source.id); if (alive) ElMessage.success(result.message) } else { await lx[type](source.id); emit('changed'); if (alive && token === generation) await load() } } catch (e) { if (alive && token === generation) error.value = message(e) } finally { if (alive) busy.value = '' } }
watch(() => props.modelValue, open => { if (open) void load(); else { generation++; script.value = ''; url.value = ''; filename.value = ''; error.value = '' } })
onBeforeUnmount(() => { alive = false; generation++ })
</script>
<template>
  <ElDialog :model-value="modelValue" title="管理音源" width="min(760px, calc(100vw - 24px))" :close-on-click-modal="false" :before-close="done => { if (!busy) done() }" @update:model-value="value => emit('update:modelValue', value)">
    <p class="help">导入你自己的 LX 2.0 音源脚本。可以保存多份，同时启用一份；更换音源不会改变已经提交的任务。</p>
    <ElAlert v-if="error" :title="error" type="error" :closable="false" show-icon role="alert" />
    <section aria-label="已保存音源" :aria-busy="loading">
      <p v-if="loading">正在读取音源…</p><p v-else-if="!sources.length" class="help">还没有音源，先在下方导入。</p>
      <article v-for="source in sources" :key="source.id" class="source">
        <div><strong>{{ source.name }}</strong> <ElTag v-if="source.active" type="success" size="small">使用中</ElTag><p class="help">{{ source.version }} · {{ source.environment }} 兼容模式</p><p class="help">{{ capabilityLabels(source) }}</p></div>
        <div class="actions"><ElButton v-if="!source.active" :loading="busy === source.id" :disabled="!!busy" @click="action(source, 'activate')">启用</ElButton><ElButton :disabled="!!busy" @click="action(source, 'test')">测试</ElButton><ElButton link type="danger" :disabled="!!busy" @click="action(source, 'remove')">移除</ElButton></div>
      </article>
    </section>
    <h3>添加音源</h3>
    <div class="actions"><ElButton :type="mode === 'file' ? 'primary' : 'default'" :disabled="!!busy" @click="mode = 'file'">上传 .js 文件</ElButton><ElButton :type="mode === 'url' ? 'primary' : 'default'" :disabled="!!busy" @click="mode = 'url'">HTTPS 链接</ElButton></div>
    <ElForm label-position="top" @submit.prevent="importing">
      <ElFormItem v-if="mode === 'file'" label="音源文件"><input aria-label="音源文件" type="file" accept=".js" :disabled="!!busy" @change="file" /><span class="help">{{ filename || 'UTF-8 编码，最大 9 MB' }}</span></ElFormItem>
      <ElFormItem v-else label="音源 HTTPS 地址"><ElInput v-model="url" placeholder="https://example.com/source.js" type="url" maxlength="4096" :disabled="!!busy" autocomplete="off" /><span class="help">仅接受公网 HTTPS 链接。地址和脚本加密保存，页面不回显导入地址。</span></ElFormItem>
      <ElFormItem label="名称（可选）"><ElInput v-model="name" maxlength="100" placeholder="留空使用脚本名称" :disabled="!!busy" /></ElFormItem>
      <details><summary>兼容性设置</summary><label class="compatibility">运行模式 <select v-model="environment" :disabled="!!busy" aria-label="音源兼容模式"><option value="mobile">Mobile（默认）</option><option value="desktop">Desktop</option></select></label><p class="help">仅在脚本要求桌面环境时切换为 Desktop。</p></details>
      <ElButton type="primary" :loading="busy === 'import'" :disabled="!!busy || (mode === 'file' ? !script : !url.trim())" native-type="submit">导入并启用</ElButton>
    </ElForm>
    <template #footer><ElButton :disabled="!!busy" @click="emit('update:modelValue', false)">完成</ElButton></template>
  </ElDialog>
</template>
<style scoped>
.help{color:var(--el-text-color-secondary);font-size:13px;line-height:1.7;overflow-wrap:anywhere}.source{padding:16px 0;border-bottom:1px solid var(--el-border-color-lighter)}.source p{margin:6px 0}.actions{display:flex;gap:8px;flex-wrap:wrap;margin:12px 0}.actions>.el-button{margin:0}form{margin-top:20px}details{margin:16px 0}summary{cursor:pointer}.compatibility{display:block;margin-top:12px}select{padding:8px;border:1px solid var(--el-border-color);border-radius:6px;background:var(--el-bg-color);color:var(--el-text-color-primary)}input[type=file]{max-width:100%}.el-form-item .help{display:block;width:100%;margin-top:8px}
</style>
