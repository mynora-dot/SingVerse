import { vi } from 'vitest'

/** jsdom has no decoder. Model native play/pause events without simulating media decoding. */
export function mockMediaElements() {
  vi.spyOn(HTMLMediaElement.prototype, 'load').mockImplementation(() => {})
  const play = vi.spyOn(HTMLMediaElement.prototype, 'play').mockImplementation(async function (this: HTMLMediaElement) {
    Object.defineProperty(this, 'paused', { configurable: true, value: false })
    this.dispatchEvent(new Event('play')); this.dispatchEvent(new Event('playing'))
  })
  const pause = vi.spyOn(HTMLMediaElement.prototype, 'pause').mockImplementation(function (this: HTMLMediaElement) {
    const changed = !this.paused
    Object.defineProperty(this, 'paused', { configurable: true, value: true })
    if (changed) this.dispatchEvent(new Event('pause'))
  })
  return { play, pause }
}
