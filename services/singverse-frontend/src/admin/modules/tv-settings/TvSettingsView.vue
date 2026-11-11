<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { onBeforeRouteLeave } from 'vue-router'
import { ElButton, ElInput, ElInputNumber, ElSwitch, ElMessage, ElMessageBox } from 'element-plus'
import PageHeader from '../../shared/ui/PageHeader.vue'
import { tvSettings, type TvSettings } from './api'

const settings = ref<TvSettings | null>(null)
const snapshot = ref('')
const busy = ref(false)
const error = ref('')
const fileInput = ref<HTMLInputElement | null>(null)
const selected = ref(0)
const uploadProgress = ref('')
const dirty = computed(() => !!settings.value && JSON.stringify(settings.value) !== snapshot.value)
const enabledBanners = computed(() => settings.value?.banners.filter(b => b.enabled) ?? [])
const activeBanner = computed(() => enabledBanners.value[selected.value % Math.max(1, enabledBanners.value.length)])
function adopt(value: TvSettings) { settings.value = value; snapshot.value = JSON.stringify(value); selected.value = 0 }
async function discard(): Promise<boolean> {
  if (!dirty.value) return true
  try { await ElMessageBox.confirm('此操作会放弃尚未保存的文案、排序和开关修改。', '放弃修改？', { type: 'warning', confirmButtonText: '放弃修改', cancelButtonText: '继续编辑' }); return true }
  catch { return false }
}
async function load() {
  if (busy.value || !await discard()) return
  busy.value = true; error.value = ''
  try { adopt(await tvSettings.read()) } catch (e) { error.value = (e as Error).message } finally { busy.value = false }
}
async function save() {
  if (!settings.value || busy.value) return
  busy.value = true; error.value = ''
  try { adopt(await tvSettings.save(settings.value)); ElMessage.success('电视设置已保存，首页将在下一次同步后更新') }
  catch (e) { error.value = (e as Error).message } finally { busy.value = false }
}
async function upload(event: Event) {
  const input = event.target as HTMLInputElement
  const files = Array.from(input.files ?? []); input.value = ''
  if (!files.length || !settings.value || busy.value) return
  if (files.length + settings.value.banners.length > 8) { error.value = `最多保存8张封面，本次还可上传${8 - settings.value.banners.length}张`; return }
  const invalid = files.find(file => !['image/jpeg', 'image/png'].includes(file.type) || file.size > 2_000_000)
  if (invalid) { error.value = `「${invalid.name}」不符合要求，请选择2MB以内的JPEG或PNG图片`; return }
  // Each upload commits independently; retain its returned revision for the next file.
  if (dirty.value) { error.value = '请先保存当前修改，再上传新封面'; return }
  busy.value = true; error.value = ''
  let uploaded = 0
  try {
    for (const file of files) {
      uploadProgress.value = `正在上传 ${uploaded + 1} / ${files.length}：${file.name}`
      adopt(await tvSettings.upload(file, settings.value!.revision))
      uploaded++
    }
    ElMessage.success(`${uploaded}张封面已上传并启用`)
  } catch (e) {
    error.value = `已成功上传${uploaded}/${files.length}张；「${files[uploaded]!.name}」上传失败：${(e as Error).message}。已停止后续上传，请重新加载核对封面列表后再选择剩余图片。`
  } finally { busy.value = false; uploadProgress.value = '' }
}
async function remove(index: number) {
  if (!settings.value || busy.value) return
  if (dirty.value) { error.value = '请先保存当前修改，再移除封面'; return }
  const banner = settings.value.banners[index]!
  try { await ElMessageBox.confirm(`移除「${banner.label}」后，电视将不再展示此封面。`, '移除封面', { type: 'warning', confirmButtonText: '移除', cancelButtonText: '取消' }) } catch { return }
  busy.value = true; error.value = ''
  try { adopt(await tvSettings.remove(banner.id, settings.value.revision)); ElMessage.success('封面已移除') }
  catch (e) { error.value = (e as Error).message } finally { busy.value = false }
}
function move(index: number, step: number) {
  if (!settings.value || busy.value) return
  const banners = settings.value.banners
  if (index + step < 0 || index + step >= banners.length) return
  const [banner] = banners.splice(index, 1); if (banner) banners.splice(index + step, 0, banner)
}
onBeforeRouteLeave(() => discard())
onMounted(load)
</script>

<template>
  <section class="tv-settings">
    <PageHeader title="客厅，也有你的风格" description="为客厅里的 SingVerse 配置专属首页。所有已授权电视共用此设置。">
      <template #actions><ElButton :disabled="busy" @click="load">重新加载</ElButton><ElButton type="primary" :loading="busy" :disabled="!settings || !dirty" @click="save">保存设置</ElButton></template>
    </PageHeader>
    <div v-if="error" role="alert" class="tv-error">{{ error }}</div>
    <template v-if="settings">
      <section class="sv-panel tv-preview-panel">
        <div class="tv-section-title"><div><h2>首页预览</h2><p>即时预览当前草稿，保存后同步至电视</p></div><span>电视主视觉 · 居中裁切</span></div>
        <div class="tv-hero-preview" :style="activeBanner ? { backgroundImage: `linear-gradient(90deg, rgb(32 26 18 / 93%), rgb(32 26 18 / 19%)), url('${activeBanner.imagePath}?v=${activeBanner.version}')` } : {}">
          <span class="tv-wordmark">SingVerse</span><small>你的私人音乐现场</small><h3>{{ settings.headline }}</h3><p>{{ settings.subtitle }}</p><span class="tv-fake-button">浏览曲库</span>
          <div v-if="enabledBanners.length" class="tv-preview-pages" aria-label="预览封面选择"><button v-for="(banner, index) in enabledBanners" :key="banner.id" :class="{ active: index === selected % enabledBanners.length }" :aria-label="`预览${banner.label}`" @click="selected = index">{{ index + 1 }}</button></div>
          <span v-else class="tv-preview-note">未启用自定义封面 · 电视使用曲库封面或统一占位</span>
        </div>
      </section>
      <section class="sv-panel tv-form">
        <div class="tv-section-title"><div><h2>首页个性化</h2><p>标题最多80字，说明最多160字；建议使用简短文案</p></div></div>
        <label><span>首页标题</span><ElInput v-model="settings.headline" :disabled="busy" maxlength="80" show-word-limit aria-label="首页标题" /></label>
        <label><span>首页说明</span><ElInput v-model="settings.subtitle" :disabled="busy" maxlength="160" show-word-limit aria-label="首页说明" /></label>
        <div class="tv-option"><span>自动轮播<small>关闭后仍可用电视遥控器切换封面</small></span><ElSwitch v-model="settings.autoRotate" :disabled="busy" aria-label="自动轮播" /></div>
        <div class="tv-option"><span>轮播间隔<small>停留在首页时生效，5至60秒</small></span><div><ElInputNumber v-model="settings.rotationSeconds" :min="5" :max="60" :disabled="busy || !settings.autoRotate" aria-label="轮播间隔" /> 秒</div></div>
      </section>
      <section class="sv-panel tv-banners">
        <div class="tv-section-title"><div><h2>首页封面 <span>{{ settings.banners.length }} / 8</span></h2><p>建议16:9横图，电视主视觉居中裁切；JPEG / PNG，单张不超过2MB、4096像素。上传即保存并启用，排序和开关需点击保存。</p></div><ElButton :disabled="busy || dirty || settings.banners.length >= 8" @click="fileInput?.click()">选择多张封面</ElButton></div>
        <input ref="fileInput" type="file" accept="image/jpeg,image/png" multiple hidden @change="upload">
        <p v-if="uploadProgress" role="status">{{ uploadProgress }}</p>
        <p v-if="!settings.banners.length" class="tv-empty">还没有自定义封面，上传多张图片打造你的专属首页。</p>
        <div v-for="(banner, index) in settings.banners" :key="banner.id" class="tv-banner-row">
          <img :src="`${banner.imagePath}?v=${banner.version}`" :alt="banner.label">
          <div class="tv-banner-label"><ElInput v-model="banner.label" maxlength="80" :disabled="busy" :aria-label="`封面${index + 1}名称`" /><span>第 {{ index + 1 }} 张</span></div>
          <ElSwitch v-model="banner.enabled" :disabled="busy" :aria-label="`启用封面${index + 1}`" /><ElButton :disabled="busy || index === 0" :aria-label="`上移封面${index + 1}`" @click="move(index, -1)">上移</ElButton><ElButton :disabled="busy || index === settings.banners.length - 1" :aria-label="`下移封面${index + 1}`" @click="move(index, 1)">下移</ElButton><ElButton type="danger" plain :disabled="busy || dirty" @click="remove(index)">移除</ElButton>
        </div>
      </section>
    </template>
    <p v-else-if="busy">正在读取电视设置…</p>
  </section>
</template>

<style scoped>
.tv-settings {
  display: grid;
  grid-template-columns: minmax(0, 1.15fr) minmax(0, 1fr);
  gap: 24px;
  align-items: start
}

.tv-settings>.page-heading,
.tv-settings>.tv-error,
.tv-banners {
  grid-column: 1/-1
}

.tv-error {
  background: var(--el-color-danger-light-9);
  color: var(--el-color-danger);
  border-radius: 10px;
  padding: 14px 18px
}

.tv-preview-panel,
.tv-form,
.tv-banners {
  padding: 24px
}

.tv-section-title {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 20px;
  margin-bottom: 20px
}

.tv-section-title h2 {
  font-size: 18px;
  margin: 0
}

.tv-section-title p,
.tv-section-title>span {
  font-size: 13px;
  color: var(--el-text-color-secondary);
  margin: 8px 0 0
}

.tv-section-title h2 span {
  font-size: 13px;
  color: var(--el-text-color-secondary);
  margin-left: 8px
}

.tv-hero-preview {
  aspect-ratio: 16/9;
  position: relative;
  border-radius: 18px;
  padding: 28px 40px;
  background: radial-gradient(ellipse at 80% 20%, var(--sv-accent-muted), var(--sv-media-stage) 65%);
  background-size: cover;
  background-position: center;
  color: var(--sv-text-inverse);
  overflow: hidden
}

.tv-wordmark {
  font-size: 21px;
  font-weight: 600;
  display: block;
  margin-bottom: 28px
}

.tv-hero-preview>small {
  font-size: 12px;
  letter-spacing: 3px;
  color: rgb(255 253 247 / 72%)
}

.tv-hero-preview h3 {
  font-size: 32px;
  margin: 12px 0;
  max-width: 70%;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap
}

.tv-hero-preview p {
  font-size: 15px;
  color: rgb(255 253 247 / 78%);
  margin: 12px 0;
  max-width: 70%;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap
}

.tv-fake-button {
  display: inline-block;
  color: var(--sv-media-stage);
  background: var(--sv-text-inverse);
  border-radius: 8px;
  padding: 10px 20px;
  font-size: 13px
}

.tv-preview-pages {
  position: absolute;
  right: 28px;
  bottom: 28px;
  display: flex;
  gap: 8px
}

.tv-preview-pages button {
  border: 1px solid rgb(255 255 255 / 21%);
  background: rgb(255 253 247 / 10%);
  color: var(--sv-text-inverse);
  border-radius: 50%;
  width: 28px;
  height: 28px;
  cursor: pointer
}

.tv-preview-pages button.active {
  background: var(--sv-text-inverse);
  color: var(--sv-media-stage)
}

.tv-preview-note {
  position: absolute;
  right: 28px;
  bottom: 28px;
  font-size: 12px;
  color: rgb(255 253 247 / 60%)
}

.tv-form>label {
  display: grid;
  grid-template-columns: 90px 1fr;
  align-items: center;
  gap: 20px;
  margin: 18px 0;
  max-width: 850px
}

.tv-option {
  display: flex;
  justify-content: space-between;
  align-items: center;
  max-width: 850px;
  border-top: 1px solid var(--el-border-color-lighter);
  padding: 18px 0
}

.tv-option small {
  display: block;
  color: var(--el-text-color-secondary);
  margin-top: 6px;
  font-size: 12px
}

.tv-banner-row {
  display: flex;
  align-items: center;
  gap: 14px;
  border-top: 1px solid var(--el-border-color-lighter);
  padding: 18px 0
}

.tv-banner-row img {
  width: 160px;
  aspect-ratio: 16/9;
  object-fit: cover;
  border-radius: 9px;
  background: var(--sv-media-stage)
}

.tv-banner-label {
  flex: 1;
  min-width: 100px;
  max-width: 440px;
  margin-right: auto
}

.tv-banner-label>span {
  display: block;
  font-size: 12px;
  color: var(--el-text-color-secondary);
  margin-top: 8px
}

.tv-empty {
  padding: 32px;
  text-align: center;
  color: var(--el-text-color-secondary)
}

@media(max-width:1100px) {
  .tv-settings {
    grid-template-columns: 1fr
  }
}

@media(max-width:900px) {
  .tv-banner-row {
    flex-wrap: wrap
  }

  .tv-banner-row img {
    width: 120px
  }

  .tv-section-title {
    align-items: flex-start
  }

  .tv-form>label {
    grid-template-columns: 90px 1fr
  }

  .tv-hero-preview {
    padding: 24px
  }

  .tv-hero-preview h3 {
    font-size: 24px
  }
}

@media(max-width:500px) {

  .tv-preview-panel,
  .tv-form,
  .tv-banners {
    padding: 18px
  }

  .tv-section-title {
    flex-wrap: wrap;
    gap: 8px
  }

  .tv-section-title>span {
    display: none
  }

  .tv-hero-preview {
    padding: 16px;
    border-radius: 10px
  }

  .tv-wordmark {
    font-size: 14px;
    margin-bottom: 12px
  }

  .tv-hero-preview>small {
    font-size: 8px;
    letter-spacing: 1px
  }

  .tv-hero-preview h3 {
    font-size: 17px;
    margin: 8px 0;
    max-width: 90%
  }

  .tv-hero-preview p {
    font-size: 10px;
    margin: 6px 0;
    max-width: 90%
  }

  .tv-fake-button {
    font-size: 9px;
    padding: 5px 10px;
    border-radius: 4px
  }

  .tv-preview-note {
    font-size: 8px;
    bottom: 8px;
    right: 12px;
    left: 16px
  }

  .tv-preview-pages {
    right: 12px;
    bottom: 12px;
    gap: 5px
  }

  .tv-preview-pages button {
    width: 32px;
    height: 32px
  }

  .tv-form>label {
    grid-template-columns: 1fr;
    gap: 8px
  }

  .tv-option {
    gap: 12px
  }

  .tv-option>span {
    font-size: 13px
  }

  .tv-option .el-input-number {
    width: 120px
  }
}
</style>
