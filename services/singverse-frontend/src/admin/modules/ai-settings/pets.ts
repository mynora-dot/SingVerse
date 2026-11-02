import type { Component } from 'vue'
import AssistantPet from './components/AssistantPet.vue'
import AssistantPetImage from './components/AssistantPetImage.vue'
import idle5 from './assets/pets/panda-note-idle.png'
import pet5 from './assets/pets/panda-note.png'
import idle31 from './assets/pets/virtual-singer-idle.png'
import pet31 from './assets/pets/virtual-singer.png'
import idle32 from './assets/pets/cyber-dj-idle.png'
import pet32 from './assets/pets/cyber-dj.png'
import idle33 from './assets/pets/star-mage-idle.png'
import pet33 from './assets/pets/star-mage.png'
import idle34 from './assets/pets/fantasy-knight-idle.png'
import pet34 from './assets/pets/fantasy-knight.png'
import idle35 from './assets/pets/android-singer-idle.png'
import pet35 from './assets/pets/android-singer.png'
import idle36 from './assets/pets/vampire-violinist-idle.png'
import pet36 from './assets/pets/vampire-violinist.png'
import idle37 from './assets/pets/service-robot-idle.png'
import pet37 from './assets/pets/service-robot.png'
import idle38 from './assets/pets/industrial-mecha-idle.png'
import pet38 from './assets/pets/industrial-mecha.png'
import idle39 from './assets/pets/cyber-navigator-idle.png'
import pet39 from './assets/pets/cyber-navigator.png'
import idle40 from './assets/pets/ai-core-idle.png'
import pet40 from './assets/pets/ai-core.png'
import idle41 from './assets/pets/space-pilot-idle.png'
import pet41 from './assets/pets/space-pilot.png'
import idle42 from './assets/pets/alien-traveler-idle.png'
import pet42 from './assets/pets/alien-traveler.png'

interface AssistantPetAppearance { id: string; name: string; description: string; component: Component; src?: string; animationSrc?: string }
/** Stable persisted IDs; keep in sync with the API petId enum and backend allowlist. */
export const assistantPets: readonly AssistantPetAppearance[] = [
  { id: 'music-sprite', name: '音符团子', description: '戴上耳机，陪你整理每一首歌。', component: AssistantPet },
  { id: 'panda-note', name: '05 · 音符熊猫', description: '抱着音符，陪你享受音乐。', component: AssistantPetImage, src: pet5, animationSrc: idle5 },
  { id: 'virtual-singer', name: '31 · 虚拟歌姬', description: '用清亮歌声点亮音乐时光。', component: AssistantPetImage, src: pet31, animationSrc: idle31 },
  { id: 'cyber-dj', name: '32 · 赛博 DJ', description: '跟随节拍，发现下一首好歌。', component: AssistantPetImage, src: pet32, animationSrc: idle32 },
  { id: 'star-mage', name: '33 · 星月法师', description: '让星光陪伴每一段旋律。', component: AssistantPetImage, src: pet33, animationSrc: idle33 },
  { id: 'fantasy-knight', name: '34 · 幻想剑士', description: '和你踏上音乐冒险。', component: AssistantPetImage, src: pet34, animationSrc: idle34 },
  { id: 'android-singer', name: '35 · 仿生少女', description: '来自未来的音乐伙伴。', component: AssistantPetImage, src: pet35, animationSrc: idle35 },
  { id: 'vampire-violinist', name: '36 · 吸血鬼乐师', description: '用琴弦奏响夜色中的旋律。', component: AssistantPetImage, src: pet36, animationSrc: idle36 },
  { id: 'service-robot', name: '37 · 服务机器人', description: '随时待命的音乐搭档。', component: AssistantPetImage, src: pet37, animationSrc: idle37 },
  { id: 'industrial-mecha', name: '38 · 工业机甲', description: '钢铁外壳，热爱音乐的心。', component: AssistantPetImage, src: pet38, animationSrc: idle38 },
  { id: 'cyber-navigator', name: '39 · 赛博领航员', description: '在霓虹之间寻找新的声音。', component: AssistantPetImage, src: pet39, animationSrc: idle39 },
  { id: 'ai-core', name: '40 · 悬浮 AI 核心', description: '让灵感与旋律环绕身边。', component: AssistantPetImage, src: pet40, animationSrc: idle40 },
  { id: 'space-pilot', name: '41 · 太空飞行员', description: '一起探索音乐的星海。', component: AssistantPetImage, src: pet41, animationSrc: idle41 },
  { id: 'alien-traveler', name: '42 · 外星旅者', description: '从遥远星球带来音乐问候。', component: AssistantPetImage, src: pet42, animationSrc: idle42 },
]
