<script setup lang="ts">
import { ref } from 'vue'
import { request } from './api'
const props=defineProps<{songId:string;token:string}>()
interface Roles{revision:number;originalId?:string;backingId?:string;streams?:{id:string;title?:string}[]}
const detail=ref<Roles>(),error=ref(''),original=ref(''),backing=ref(''),busy=ref(false)
async function load(){try{detail.value=await request<Roles>(`audio-roles/songs/${props.songId}`,'GET',undefined,props.token);original.value=detail.value.originalId??'';backing.value=detail.value.backingId??''}catch(e){error.value=e instanceof Error?e.message:'读取失败'}}
async function save(){busy.value=true;try{const saved=await request<Roles>(`audio-roles/songs/${props.songId}`,'PUT',{originalId:original.value,backingId:backing.value,revision:detail.value?.revision},props.token);detail.value={...detail.value,...saved};error.value='已保存到全曲库，下次选用生效'}catch(e){error.value=e instanceof Error?e.message:'保存失败'}finally{busy.value=false}}
</script>
<template><details @toggle="($event.target as HTMLDetailsElement).open && load()"><summary>纠正原唱/伴奏</summary><p v-if="error" role="status">{{error}}</p><p v-if="!detail?.streams?.length">等待计算节点探测音轨</p><template v-else><label>原唱<select v-model="original"><option value="">请选择</option><option v-for="s in detail.streams" :key="s.id" :value="s.id">{{s.title||s.id}}</option></select></label><label>伴奏<select v-model="backing"><option value="">请选择</option><option v-for="s in detail.streams" :key="s.id" :value="s.id">{{s.title||s.id}}</option></select></label><button :disabled="busy||!original||!backing||original===backing" @click="save">全曲库保存</button></template></details></template>
