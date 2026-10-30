import { shallowRef } from 'vue'
import { ai } from './api/ai'
import type { Feature } from './types'

export const assistantSettings = shallowRef<Feature | null>(null)
let generation = 0
export function updateAssistantSettings(value: Feature | null) {
  generation++
  assistantSettings.value = value
}
export async function loadAssistantSettings() {
  const token = ++generation
  try {
    const features = await ai.features()
    if (token === generation) assistantSettings.value = features.find(f => f.id === 'assistant') ?? null
  } catch { if (token === generation) assistantSettings.value = null }
}
