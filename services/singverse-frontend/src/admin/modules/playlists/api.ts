import { authRequest } from '../../core/auth/api'
export interface Song { id:string; title:string; artists:string[]; coverPath?:string }
export interface Playlist {id:string;name:string;description:string;revision:number;count:number;missingCount:number;coverPath?:string;coverVersion?:string;songIds:string[];songs:Song[]}
export interface Draft {name:string;description:string;songIds:string[];songs:Song[];candidateCount:number;libraryCount:number}
export const playlists={
 list:(q='',page=0)=>authRequest<{items:Playlist[];totalElements:number}>(`/api/v1/playlists?q=${encodeURIComponent(q)}&page=${page}&size=12`),
 get:(id:string)=>authRequest<Playlist>(`/api/v1/playlists/${encodeURIComponent(id)}`),
 save:(input:{requestId:string;name:string;description:string;songIds:string[];revision:number},id?:string)=>authRequest<Playlist>(`/api/v1/playlists${id?'/'+encodeURIComponent(id):''}`,id?'PUT':'POST',input),
 remove:(id:string,revision:number)=>authRequest(`/api/v1/playlists/${encodeURIComponent(id)}?revision=${revision}`,'DELETE'),
 generate:(input:{prompt:string;modelServiceId:string;q:string;limit:number})=>authRequest<Draft>('/api/v1/playlists/generate','POST',input,190000),
}
