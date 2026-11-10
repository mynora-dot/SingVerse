import type { MusicProvider } from './types'
import type { CoverQuality } from './single-types'

// This URL is used for draft comparison only. Go derives the saved URL again
// from the owned candidate and quality enum, never from a frontend URL.
export function coverValue(provider: MusicProvider, raw: string, quality: CoverQuality): string {
  try {
    const url = new URL(raw.replace(/^http:/, 'https:'))
    const high = quality === 'HIGH'
    if (provider === 'NETEASE') {
      url.searchParams.delete('param')
      if (high) url.searchParams.set('param', '2048y2048')
    } else if (provider === 'KUGOU') {
      url.pathname = url.pathname.replace(/(\/(?:stdmusic|albumcover)\/)(?:\d+|\{size\})(\/)/, (_match, prefix: string, suffix: string) => `${prefix}${high ? '2048' : '0'}${suffix}`)
    } else if (provider === 'KUWO') {
      url.pathname = url.pathname.replace(/(\/star\/albumcover\/)\d+(\/)/, (_match, prefix: string, suffix: string) => `${prefix}${high ? '2048' : '0'}${suffix}`)
    } else if (provider === 'QQ') {
      url.pathname = url.pathname.replace(/T002(?:R\d+x\d+)?M000/, high ? 'T002R1500x1500M000' : 'T002M000')
    } else if (provider === 'QISHUI') {
      url.pathname = url.pathname.replace(/-(?:image\.image|resize:\d+:\d+\.(?:png|jpg))$/, high ? '-resize:2048:2048.png' : '-image.image')
    }
    return url.toString()
  } catch { return raw }
}
