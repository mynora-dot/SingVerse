import AssistantPet from './components/AssistantPet.vue'
/** Stable persisted IDs keep additional appearances independent of assistant behavior. */
export const assistantPets = [{ id: 'music-sprite', name: '音符团子', description: '戴上耳机，陪你整理每一首歌。', component: AssistantPet }] as const
