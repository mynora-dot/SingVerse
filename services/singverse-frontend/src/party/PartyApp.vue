<script setup lang="ts">
import SongArtwork from './SongArtwork.vue'
import { computed, onMounted, onUnmounted, ref } from 'vue'
import { request, requestId, PartyHttpError, type Credential, type Snapshot, type Song } from './api'
const serverHost = location.host
const saved = sessionStorage.getItem('singverse-party')
const credential = ref<Credential | null>(null)
try { credential.value = saved ? JSON.parse(saved) as Credential : null } catch { sessionStorage.removeItem('singverse-party') }
const invitation = new URLSearchParams(location.hash.slice(1)).get('invite') ?? ''
const preview = ref<{ sessionId: string; deviceName: string } | null>(null)
const name = ref(''), error = ref(''), notice = ref(''), busy = ref(false), tab = ref('点歌'), query = ref('')
const snapshot = ref<Snapshot | null>(null), songs = ref<Song[]>([]), page = ref(0), total = ref(0)
const tags = ref<{ id: string; name: string; dimension: string; count: number }[]>([]), selected = ref<string[]>([])
const versionNames: Record<string, string> = { ORIGINAL: '原唱', ACCOMPANIMENT: '伴奏', INSTRUMENTAL: '纯音乐', DUET: '合唱', KARAOKE: '卡拉 OK' }
const stateNames: Record<string, string> = { IDLE: '已停止', PREPARING: '准备中', BUFFERING: '缓冲中', PLAYING: '正在演唱', PAUSED: '已暂停', ENDED: '演唱结束', ERROR: '播放失败' }
const dimensions: Record<string, string> = { LANGUAGE: '语种', DECADE: '年代', GENRE: '风格', MOOD: '情绪', SCENE: '场景', CUSTOM: '自定义' }
const pending = ref<{ action: string; requestId: string; expectedRevision: number; songId?: string; itemId?: string } | null>(null)
const queue = computed(() => snapshot.value?.queue.filter(i => ['WAITING', 'HANDOFF', 'PLAYING'].includes(i.status)) ?? [])
let timer: ReturnType<typeof setTimeout> | undefined, generation = 0, stopped = false
async function perform(task: () => Promise<void>) { if (busy.value) return; busy.value = true; error.value = ''; try { await task() } catch (e) { error.value = e instanceof Error ? e.message : '连接失败，请重试' } finally { busy.value = false } }
async function refresh() { const c = credential.value; if (c) snapshot.value = await request<Snapshot>(`sessions/${c.sessionId}/snapshot`, 'GET', undefined, c.token) }
async function loadSongs() { const id = ++generation; const params = new URLSearchParams({ q: query.value, page: String(page.value), size: '20' }); selected.value.forEach(t => params.append('tagIds', t)); const result = await request<{ items: Song[]; totalElements: number }>(`library/songs?${params}`, 'GET', undefined, credential.value?.token); if (id === generation) { songs.value = result.items; total.value = result.totalElements } }
async function initialize() { await refresh(); tags.value = await request('library/classification-tags', 'GET', undefined, credential.value?.token); await loadSongs() }
async function join() { await perform(async () => { const c = await request<Credential>('join', 'POST', { invitation, name: name.value }); credential.value = c; sessionStorage.setItem('singverse-party', JSON.stringify(c)); history.replaceState(null, '', location.pathname); await initialize() }) }
async function command(action: string, itemId?: string, songId?: string) { if (action === 'NEXT' && !confirm('切换到下一首？电视仍需确认下一位开唱。')) return; await perform(async () => { if (!snapshot.value || !credential.value) return; if (pending.value) throw new Error('上一项操作结果尚未确认，请先核对已点列表'); pending.value = { action, requestId: requestId(), expectedRevision: snapshot.value.revision, ...(itemId ? { itemId } : {}), ...(songId ? { songId } : {}) }; await sendPending() }) }
async function sendPending() { if (!pending.value || !credential.value) return; try { snapshot.value = await request(`sessions/${credential.value.sessionId}/commands`, 'POST', pending.value, credential.value.token); pending.value = null; notice.value = '操作已确认' } catch (e) { await refresh().catch(() => {}); throw e } }
function forgetPending() { pending.value = null; notice.value = '已保留当前队列，请核对歌曲后再操作' }
function leave() { credential.value = null; snapshot.value = null; sessionStorage.removeItem('singverse-party'); notice.value = '已离开，请重新扫描电视二维码' }
async function poll() { if (stopped) return; try { if (credential.value && !busy.value) await refresh() } catch (e) { if (e instanceof PartyHttpError && e.status === 401) { credential.value = null; snapshot.value = null; sessionStorage.removeItem('singverse-party'); error.value = '参与权限已失效，请重新扫描电视二维码。' } else error.value = '与 NAS 同步失败，操作前请先重试连接。' } finally { if (!stopped) timer = setTimeout(() => { void poll() }, 2500) } }
onMounted(async () => { await perform(async () => { if (invitation) { preview.value = await request('invitations/preview', 'POST', { invitation }); if (credential.value?.sessionId !== preview.value?.sessionId) return } if (credential.value) await initialize() }); void poll() })
onUnmounted(() => { stopped = true; clearTimeout(timer) })
</script>
<template>
  <main class="party-shell">
    <header><a class="brand" href="/party/">◉ <span>SingVerse</span></a><span class="eyebrow">客厅唱片</span></header>
    <section v-if="!snapshot || (preview && credential?.sessionId !== preview.sessionId)" class="join-panel">
      <p class="eyebrow">好歌，留给在场的你</p><h1>今晚，<br>一起唱首歌。</h1><p class="muted">歌曲来自家里的 NAS，与亲友共用电视上的演唱队列。</p>
      <template v-if="preview"><div class="server-card"><span>即将加入</span><strong>{{ preview.deviceName }}</strong><small>{{ serverHost }}</small></div><label for="nickname">大家怎么称呼你</label><input id="nickname" v-model="name" maxlength="30" placeholder="输入昵称，同名不会合并身份"><button class="primary" :disabled="busy || !name.trim()" @click="join">确认加入这场聚会</button></template>
      <template v-else><p class="empty">用微信扫描电视上的二维码，确认后即可点歌。</p><button v-if="credential" :disabled="busy" @click="perform(initialize)">恢复连接</button></template>
    </section>
    <template v-else>
      <section class="greeting"><p class="eyebrow">{{ credential?.name }}，轮到你选歌了</p><h1>{{ tab === '点歌' ? '想唱什么？' : tab === '已点' ? '今晚的好歌' : '让演唱继续' }}</h1><p class="muted">{{ snapshot.status === 'ENDED' ? '这场聚会已结束' : snapshot.acceptingRequests ? '本场开放点歌' : '主持人已暂停接单' }} · {{ snapshot.host ? '房主' : '亲友' }}</p></section>
      <section v-if="tab === '点歌'">
        <form class="search" @submit.prevent="page = 0; perform(loadSongs)"><input v-model="query" placeholder="搜索歌名或歌手" aria-label="搜索歌名或歌手"><button class="primary" :disabled="busy">找歌</button></form>
        <details class="filters"><summary>按分类发现 <span v-if="selected.length">· 已选 {{ selected.length }} 项</span></summary><fieldset v-for="(label, dimension) in dimensions" :key="dimension"><legend>{{ label }}</legend><label v-for="tag in tags.filter(t => t.dimension === dimension)" :key="tag.id" class="chip"><input v-model="selected" type="checkbox" :value="tag.id">{{ tag.name }} <small>{{ tag.count }}</small></label></fieldset><button @click="page = 0; perform(loadSongs)">应用分类</button><button @click="selected = []; page = 0; perform(loadSongs)">清除分类</button><small>数量为全库数量；同维度任选，跨维度同时满足。</small></details>
        <div class="section-heading"><h2>你的曲库</h2><span>{{ total }} 首</span></div>
        <div v-if="!songs.length" class="empty">{{ busy ? '正在找歌…' : '没有找到歌曲，试试其他关键词或分类。' }}</div>
        <article v-for="song in songs" :key="song.id" class="song-row"><SongArtwork :path="song.coverPath" :token="credential?.token" /><div class="song-copy"><h3>{{ song.title }}</h3><p>{{ song.artists.join(' / ') || '歌手未知' }}</p><small>{{ versionNames[song.version] || '版本待确认' }}</small></div><button class="add" :aria-label="`点唱 ${song.title}`" :disabled="busy || !snapshot.acceptingRequests || snapshot.status !== 'ACTIVE'" @click="command('ADD', undefined, song.id)">＋</button></article>
        <div class="pagination"><button :disabled="page === 0 || busy" @click="page--; perform(loadSongs)">上一页</button><span>{{ page + 1 }}</span><button :disabled="(page + 1) * 20 >= total || busy" @click="page++; perform(loadSongs)">下一页</button></div>
      </section>
      <section v-else-if="tab === '已点'"><p v-if="!queue.length" class="empty">还没有待唱歌曲，先去找一首喜欢的歌。</p><article v-for="(item, index) in queue" :key="item.id" class="queue-row"><b>{{ String(index + 1).padStart(2, '0') }}</b><div class="song-copy"><h3>{{ item.song.title }}</h3><p>{{ item.ownerName }} · {{ item.status === 'WAITING' ? '待唱' : item.status === 'HANDOFF' ? '等待电视确认' : '正在唱' }}</p></div><div v-if="item.status === 'WAITING'" class="queue-actions"><button v-if="snapshot.host" :disabled="busy" @click="command('TOP', item.id)">置顶</button><button v-if="snapshot.host" :disabled="busy" @click="command('UP', item.id)">上移</button><button v-if="snapshot.host" :disabled="busy" @click="command('DOWN', item.id)">下移</button><button v-if="snapshot.host || item.ownerId === snapshot.actorId" :disabled="busy" @click="command('REMOVE', item.id)">撤回</button></div></article></section>
      <section v-else class="remote"><div class="record-large">◉</div><h2>{{ stateNames[snapshot.playback?.state || ''] || '等待开唱' }}</h2><p class="muted">播放结果以电视实际回报为准</p><template v-if="snapshot.host"><div class="remote-grid"><button :disabled="busy" @click="command('PAUSE')">暂停</button><button :disabled="busy" @click="command('RESUME')">继续</button><button :disabled="busy" @click="command('RESTART')">重唱</button><button :disabled="busy" @click="command('NEXT')">切歌</button></div></template><p v-else class="empty">遥控需要主人在电视上授予主持权限。你仍可点歌和撤回自己的待唱歌曲。</p></section>
      <footer><button class="text-button" @click="leave">离开本场</button><small>离开不会撤回已点歌曲或结束聚会。</small></footer>
      <nav aria-label="聚会导航"><button v-for="entry in ['点歌', '已点', '遥控']" :key="entry" :class="{ active: tab === entry }" @click="tab = entry">{{ entry }}</button></nav>
    </template>
    <aside v-if="error || notice || pending" class="feedback" role="status"><p v-if="error">{{ error }}</p><p v-else>{{ notice }}</p><template v-if="pending"><p>结果尚未确认，请先查看已点，避免重复点歌。</p><button :disabled="busy" @click="perform(sendPending)">重试原请求</button><button @click="forgetPending">已核对，关闭此请求</button></template><button v-else @click="error = ''; notice = ''">知道了</button></aside>
  </main>
</template>
