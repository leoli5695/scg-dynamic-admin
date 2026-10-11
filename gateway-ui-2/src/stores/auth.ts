/**
 * Auth store — single source of truth for login state (replaces the legacy
 * React AuthContext). Setup-style Pinia store so `isLoggedIn` stays reactive.
 */
import { defineStore } from 'pinia'
import { computed, ref } from 'vue'
import {
  type AuthUser,
  clearStoredAuth,
  getStoredUser,
  loginRequest,
  logoutRequest,
  persistLogin,
} from '@/api/authService'

export const useAuthStore = defineStore('auth', () => {
  const user = ref<AuthUser | null>(getStoredUser())
  const isLoggedIn = computed(() => user.value !== null)

  /** Performs login and persists the session. Throws ApiError on failure. */
  async function login(username: string, password: string): Promise<AuthUser> {
    const result = await loginRequest(username, password)
    const authUser = persistLogin(result)
    user.value = authUser
    return authUser
  }

  /** Clears the session locally and notifies the backend. */
  async function logout(): Promise<void> {
    // Dispatch logout BEFORE clearing storage so it still carries the Bearer token
    // and the backend can revoke it (see authService.logoutRequest).
    const pending = logoutRequest()
    clearStoredAuth()
    user.value = null
    await pending
  }

  return { user, isLoggedIn, login, logout }
})
