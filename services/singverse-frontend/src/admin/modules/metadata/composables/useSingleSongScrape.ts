import { computed, ref } from 'vue'
import { metadata } from '../api/metadata'
import { singleReview } from '../api/single-review'
import { highSimilarityCandidate } from '../candidate-selection'
import { providerNames } from '../types'
import type { MetadataCandidate, MusicProvider } from '../types'
import { reviewFields } from '../single-types'
import type { Values, ReviewField, SingleReview, ReviewResult, ReviewApply, CoverSelection } from '../single-types'
export const present = (value: unknown) => value !== null && value !== undefined && value !== '' && (!Array.isArray(value) || value.length > 0)
export const equal = (a: unknown, b: unknown) => JSON.stringify(a ?? null) === JSON.stringify(b ?? null)
export const copyValues = (values: Values): Values => JSON.parse(JSON.stringify(values)) as Values
export function candidateValues(candidate: MetadataCandidate): Values {
  const t = candidate.track
  return { title: t.title, artists: [...t.artists], album: t.album, releaseDate: t.releaseDate, aliases: [...t.aliases], durationMs: t.durationMs, cover: t.coverUrl }
}
export function useSingleSongScrape() {
  const review = ref<SingleReview | null>(null), keyword = ref(''), providers = ref<MusicProvider[]>([]), available = ref<MusicProvider[]>([])
  const settingsStatus = ref<'loading' | 'ready' | 'error'>('loading'), searched = ref(false)
  const selectionThreshold = ref(.95), autoSelected = ref(false)
  const enabled = ref(false), loading = ref(false), searching = ref(false), applying = ref(false)
  const error = ref(''), searchError = ref(''), warning = ref(''), candidates = ref<MetadataCandidate[]>([])
  const selected = ref<MetadataCandidate | null>(null), draft = ref<Values | null>(null), fields = ref<ReviewField[]>([])
  const edits = ref<Partial<Values>>({}), cover = ref<CoverSelection | null>(null), result = ref<ReviewResult | null>(null)
  let session = 0, searchGeneration = 0, attempt: { signature: string; input: ReviewApply } | null = null
  const changed = computed(() => review.value && draft.value ? reviewFields.filter(f => fields.value.includes(f) && present(draft.value![f]) && !equal(review.value!.values[f], draft.value![f])) : [])
  const dirty = computed(() => !!review.value && !!draft.value && !result.value && (reviewFields.some(f => !equal(review.value!.values[f], draft.value![f])) || !!selected.value))
  const canSearch = computed(() => !!review.value && settingsStatus.value === 'ready' && enabled.value && providers.value.some(p => available.value.includes(p)) && !!keyword.value.trim() && !loading.value && !searching.value && !applying.value)
  const preview = computed(() => {
    if (!review.value || !draft.value) return null
    const values = copyValues(review.value.values)
    for (const field of changed.value) values[field] = draft.value[field]
    return values
  })
  function clearSelection() {
    autoSelected.value = false; selected.value = null; draft.value = review.value ? copyValues(review.value.values) : null
    edits.value = {}; cover.value = null; fields.value = []; result.value = null; attempt = null
  }
  function invalidate() { session++; searchGeneration++ }
  async function open(songId: string, initialCandidates?: MetadataCandidate[], threshold?: number) {
    invalidate(); const token = session
    autoSelected.value = false; review.value = null; draft.value = null; selected.value = null; candidates.value = []; result.value = null
    edits.value = {}; cover.value = null; fields.value = []; error.value = ''; warning.value = ''; searchError.value = ''; searching.value = false; attempt = null
    enabled.value = false; providers.value = []; available.value = []; loading.value = true; searched.value = false; settingsStatus.value = 'loading'
    const [context, settings] = await Promise.allSettled([singleReview.load(songId), metadata.settings()])
    if (token !== session) return
    if (context.status === 'fulfilled') {
      review.value = context.value; draft.value = copyValues(context.value.values)
      keyword.value = [context.value.values.title, ...(context.value.values.artists as string[])].filter(Boolean).join(' ').slice(0, 200)
    } else error.value = context.reason instanceof Error ? context.reason.message : '读取歌曲失败'
    if (settings.status === 'fulfilled') {
      settingsStatus.value = 'ready'; enabled.value = settings.value.enabled; selectionThreshold.value = threshold ?? settings.value.threshold
      available.value = settings.value.enabled ? [...settings.value.providers] : []; providers.value = [...available.value]
    } else { settingsStatus.value = 'error'; warning.value = '平台设置读取失败，仍可手工编辑；重新加载后可重试平台查询。' }
    loading.value = false
    if (review.value && initialCandidates) {
      candidates.value = initialCandidates; searched.value = true
      const best = highSimilarityCandidate(initialCandidates, selectionThreshold.value)
      if (best) { choose(best); autoSelected.value = true }
    } else if (review.value && enabled.value && providers.value.length) await search()
  }
  async function search() {
    if (!canSearch.value || !review.value) return
    const token = session, generation = ++searchGeneration
    clearSelection(); searched.value = true
    searching.value = true; searchError.value = ''; warning.value = ''; candidates.value = []
    try {
      const response = await metadata.search(review.value.song.id, keyword.value.trim(), providers.value.filter(p => available.value.includes(p)))
      if (token !== session || generation !== searchGeneration) return
      candidates.value = response.candidates
      const best = highSimilarityCandidate(response.candidates, selectionThreshold.value)
      if (best) { choose(best); autoSelected.value = true }
      warning.value = response.errors.map(item => `${providerNames[item.provider]}：${item.message}`).join('；')
    } catch (failure) { if (token === session && generation === searchGeneration) searchError.value = failure instanceof Error ? failure.message : '搜索失败' }
    finally { if (token === session && generation === searchGeneration) searching.value = false }
  }
  function choose(candidate: MetadataCandidate) {
    autoSelected.value = false; selected.value = candidate; draft.value = candidateValues(candidate); edits.value = {}; result.value = null; error.value = ''; attempt = null
    cover.value = candidate.track.coverUrl ? { kind: 'candidate', id: candidate.id, value: candidate.track.coverUrl, url: metadata.candidateCover(candidate.id), label: providerNames[candidate.track.provider] } : null
    fields.value = reviewFields.filter(f => present(draft.value![f]) && !equal(review.value!.values[f], draft.value![f]))
  }
  function saveEdits(values: Values) {
    if (!draft.value || !review.value) return
    for (const field of reviewFields) {
      if (field === 'cover') continue
      if (!equal(draft.value[field], values[field])) {
        draft.value[field] = values[field]
        const original = selected.value ? candidateValues(selected.value)[field] : review.value.values[field]
        if (equal(original, values[field])) delete edits.value[field]
        else edits.value[field] = values[field]
        if (present(values[field]) && !fields.value.includes(field)) fields.value.push(field)
      }
    }
    result.value = null; error.value = ''; attempt = null
  }
  function chooseCover(selection: CoverSelection) {
    cover.value = selection; draft.value!.cover = selection.value
    if (!fields.value.includes('cover')) fields.value.push('cover')
    result.value = null; error.value = ''; attempt = null
  }
  async function apply(execute: (songId: string, input: ReviewApply) => Promise<ReviewResult> = singleReview.apply) {
    if (!review.value || !draft.value || !changed.value.length || applying.value) return
    const token = session
    const base = { fingerprint: review.value.fingerprint, version: review.value.version, candidateId: selected.value?.id ?? null,
      fields: [...changed.value], edits: { ...edits.value }, coverCandidateId: cover.value?.kind === 'candidate' ? cover.value.id : null, coverAssetId: cover.value?.kind === 'asset' ? cover.value.id : null }
    const signature = JSON.stringify(base)
    if (!attempt || attempt.signature !== signature) attempt = { signature, input: { ...base, requestId: crypto.randomUUID() } }
    applying.value = true; error.value = ''
    try { const response = await execute(review.value.song.id, attempt.input); if (token === session) result.value = response }
    catch (failure) { if (token === session) error.value = failure instanceof Error ? failure.message : '应用失败，请重试' }
    finally { if (token === session) applying.value = false }
  }
  return { review, keyword, providers, available, enabled, loading, searching, applying, error, searchError, warning, candidates,
    selected, draft, fields, edits, cover, result, changed, dirty, autoSelected, preview, canSearch, searched, settingsStatus, clearSelection, open, invalidate, search, choose, saveEdits, chooseCover, apply }
}
