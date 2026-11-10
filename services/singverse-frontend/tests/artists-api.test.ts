import { beforeEach, expect, it, vi } from 'vitest'
import { authRequest } from '../src/admin/core/auth/api'
import { artists } from '../src/admin/modules/artists/api/artists'
vi.mock('../src/admin/core/auth/api',()=>({authRequest:vi.fn(),ApiError:class extends Error{}}))
beforeEach(()=>vi.mocked(authRequest).mockReset())
it('歌手类型与封面状态在分页前由服务端筛选',async()=>{
  await artists.list('周 / jay',2,20,'MALE',30,'MISSING')
  expect(authRequest).toHaveBeenCalledWith('/api/v1/artists?q=%E5%91%A8%20%2F%20jay&page=2&size=20&type=MALE&countLessThan=30&artwork=MISSING')
})
it('批次使用同源接口提交完整筛选快照、平台和幂等标识',async()=>{
  const input={requestId:'request',artistIds:[],allMatching:true,filter:{q:'周',type:'MALE',countLessThan:null,artwork:'MISSING'},provider:'NETEASE' as const}
  await artists.startArtworkBatch(input)
  expect(authRequest).toHaveBeenCalledWith('/api/v1/artists/artwork-batches','POST',input,30000)
  await artists.artworkBatch('batch/1',2)
  expect(authRequest).toHaveBeenLastCalledWith('/api/v1/artists/artwork-batches/batch%2F1?page=2&size=20')
})
it('批量类型写入保留每位歌手修订号',async()=>{
  const selected=[{id:'a',revision:1},{id:'b',revision:4}]
  await artists.categories(selected,'FEMALE')
  expect(authRequest).toHaveBeenCalledWith('/api/v1/artists/categories','POST',{artists:selected,type:'FEMALE'},30000)
})
