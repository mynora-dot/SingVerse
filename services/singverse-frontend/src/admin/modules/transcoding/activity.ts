import { ApiError, authRequest } from '../../core/auth/api'

export type ActivityState = 'PREPARING' | 'BUFFERING' | 'PLAYING' | 'PAUSED' | 'COMPLETED' | 'STOPPED' | 'SKIPPED' | 'ERROR'
export interface PlaybackAttempt { attemptId: string; writeToken: string; eventSeq: number; leaseSeconds: number; startedAt: number; serverTime: number }
export interface ActivityEvent { writeToken: string; eventSeq: number; state: ActivityState; actualPlayedMs: number; elapsedMs: number; occurredAt: number; lastActivityAt: number | null; durationMs?: number }
export const adminActivity = {
  create: (songId: string, startedAt: number) => authRequest<PlaybackAttempt>('/api/v1/playback-attempts', 'POST', { songId, deviceName: '管理端在线播放', startedAt }),
  event: (id: string, body: ActivityEvent) => authRequest(`/api/v1/playback-attempts/${encodeURIComponent(id)}/events`, 'POST', body),
}

/** One logical preview survives source/track rebuilds. Only the main media controls its clock. */
export class AdminPlaybackActivity {
  private state: ActivityState = 'PREPARING'
  private started = 0
  private startedAt = 0
  private lastActivityAt: number | null = null
  private measured = 0
  private actual = 0
  private duration?: number
  private attempt?: PlaybackAttempt
  private creating?: Promise<void>
  private sending = false
  private pending?: Omit<ActivityEvent, 'writeToken' | 'eventSeq'>
  private initial?: Omit<ActivityEvent, 'writeToken' | 'eventSeq'>
  private sequence = -1
  private lastSent = 0
  private timer?: ReturnType<typeof setInterval>
  private ended = false

  constructor(private readonly songId: string, private readonly clock = () => performance.now(), private readonly utcClock = () => Date.now()) {}
  get closed() { return this.ended }

  transition(state: ActivityState, durationMs?: number) {
    if (this.ended) return
    const now = this.clock()
    if (this.started) this.measure(now)
    this.state = state
    if (durationMs && Number.isFinite(durationMs) && durationMs > 0 && durationMs <= 86_400_000) this.duration = Math.round(durationMs)
    if (!this.started && state !== 'PLAYING') return
    if (!this.started) { this.started = now || Number.EPSILON; this.startedAt = Math.floor(this.utcClock()); this.measured = now; this.timer = setInterval(() => this.tick(), 5000) }
    if (state === 'PLAYING') this.lastActivityAt = this.timestamp(now)
    if (['COMPLETED', 'STOPPED', 'SKIPPED', 'ERROR'].includes(state)) { this.ended = true; clearInterval(this.timer) }
    this.queue(now)
  }

  private measure(now: number) {
    if (this.state === 'PLAYING') { this.actual += Math.max(0, now - this.measured); this.lastActivityAt = this.timestamp(now) }
    this.measured = now
  }
  private timestamp(now: number) { return this.startedAt + Math.max(0, Math.floor(now - this.started)) }
  private tick() {
    const now = this.clock()
    this.measure(now)
    if (this.state === 'PLAYING' || now - this.lastSent >= 15_000) this.queue(now)
  }
  private queue(now: number) {
    this.pending = { state: this.state, actualPlayedMs: Math.floor(this.actual), elapsedMs: Math.max(0, Math.floor(now - this.started)), occurredAt: this.timestamp(now), lastActivityAt: this.lastActivityAt, ...(this.duration ? { durationMs: this.duration } : {}) }
    if (!this.attempt && !this.initial && this.state === 'PLAYING') this.initial = this.pending
    void this.drain()
  }
  private async drain() {
    if (this.sending || !this.pending) return
    this.sending = true
    try {
      if (!this.attempt) {
        this.creating ??= adminActivity.create(this.songId, this.startedAt).then(value => { this.attempt = value; this.sequence = value.eventSeq }).catch(() => {})
        await this.creating
        if (!this.attempt) { this.creating = undefined; return }
      }
      if (this.initial) {
        const value = this.initial; this.initial = undefined
        if (this.pending === value) this.pending = undefined
        this.lastSent = this.clock()
        try { await adminActivity.event(this.attempt.attemptId, { ...value, writeToken: this.attempt.writeToken, eventSeq: ++this.sequence }) }
        catch (error) {
          if (this.expired(error)) return
          // A later cumulative event can recover after a temporary transport failure.
        }
      }
      while (this.pending) {
        const value = this.pending; this.pending = undefined
        this.lastSent = this.clock()
        try { await adminActivity.event(this.attempt.attemptId, { ...value, writeToken: this.attempt.writeToken, eventSeq: ++this.sequence }) }
        catch (error) {
          if (this.expired(error)) return
          // Activity collection must never stop or retry media playback.
        }
      }
    } finally { this.sending = false }
  }
  private expired(error: unknown) {
    if (!(error instanceof ApiError && error.status === 410)) return false
    this.ended = true; this.pending = undefined; this.initial = undefined; clearInterval(this.timer)
    return true
  }
}
