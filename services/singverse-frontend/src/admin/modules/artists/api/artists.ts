import { authRequest, ApiError } from '../../../core/auth/api'
import type { Artist, Credit, ImageKind, Page } from '../types'
import type { MediaSong } from '../../media-library/types'
const base='/api/v1/artists'
const segment=encodeURIComponent
export const artists = {
  artworkSettings:()=>authRequest<{fanartConfigured:boolean}>(`${base}/artwork-settings`),
  saveArtworkSettings:(fanartKey:string|null,clearFanartKey=false)=>authRequest<{fanartConfigured:boolean}>(`${base}/artwork-settings`,'PUT',{fanartKey,clearFanartKey}),
  candidates:(id:string,kind:ImageKind)=>authRequest<Array<{provider:string;externalId:string;attribution:string;portrait:string;background:string}>>(`${base}/${segment(id)}/images/${kind}/candidates`),
  candidatePreview:(id:string,kind:ImageKind,provider:string,externalId:string)=>`${base}/${segment(id)}/images/${kind}/candidates/${segment(provider)}/${segment(externalId)}/preview`,
  selectCandidate:(id:string,kind:ImageKind,provider:string,externalId:string)=>authRequest<Artist>(`${base}/${segment(id)}/images/${kind}/selection`,'POST',{provider,externalId}),
  songs:(id:string,page=0)=>authRequest<Page<MediaSong>>(`${base}/${segment(id)}/songs?page=${page}&size=12`),
  retry:(id:string)=>authRequest(`${base}/${segment(id)}/images/retry`,'POST',{}),
  override:(id:string)=>authRequest<{revision:number;overridden:boolean}>(`${base}/song-overrides/${segment(id)}`),
  reset:(id:string,revision:number)=>authRequest(`${base}/song-overrides/${segment(id)}?revision=${revision}`,'DELETE'),
  list: (q='',page=0,size=12) => authRequest<Page<Artist>>(`${base}?q=${segment(q)}&page=${page}&size=${size}`),
  get: (id:string) => authRequest<Artist>(`${base}/${segment(id)}`),
  save: (input: { requestId?:string; name:string; aliases:string[]; type:string; biography:string; active:boolean; revision:number },id?:string) => authRequest<Artist>(base+(id?'/'+segment(id):''),id?'PUT':'POST',input),
  credits: (q='',unbound=true,page=0,artistId='') => authRequest<Page<Credit>>(`${base}/credits?q=${segment(q)}&unbound=${unbound}&page=${page}&size=20${artistId?'&artistId='+segment(artistId):''}`),
  bind: (artistId:string|null,items:Credit[]) => authRequest<{success:boolean}>(base+'/bindings','POST',{artistId,items:items.map(({songId,credit,revision})=>({songId,credit,revision}))}),
  upload: async(id:string,kind:ImageKind,version:string,file:File,signal:AbortSignal):Promise<Artist> => {
    const csrf=await authRequest<{token:string}>('/api/v1/auth/csrf');const body=new FormData();body.append('file',file);body.append('version',version)
    let response:Response
    try{response=await fetch(`${base}/${segment(id)}/images/${kind}`,{method:'PUT',headers:{'X-CSRF-Token':csrf.token},body,credentials:'same-origin',redirect:'error',signal:AbortSignal.any([signal,AbortSignal.timeout(30000)])})}catch{throw new Error(signal.aborted?'上传已取消':'图片上传失败，请检查网络')}
    if(!response.ok){const problem=await response.json().catch(()=>({}));if(response.status===401)window.dispatchEvent(new Event('singverse:session-expired'));throw new ApiError(response.status,problem.detail || '图片上传失败')}
    return response.json() as Promise<Artist>
  },
  removeImage:(id:string,kind:ImageKind,version:string)=>authRequest<Artist>(`${base}/${segment(id)}/images/${kind}?version=${segment(version)}`,'DELETE'),
}
