import { reactive } from 'vue'

export interface Account {
  username: string
  defaultPassword: boolean
  mfaEnabled: boolean
  recoveryCodesRemaining: number
}
export const auth = reactive<{ account: Account | null }>({ account: null })
