/**
 * Authentication service.
 * Endpoints aligned with controller/AuthController.java (/api/auth).
 * Ported 1:1 from the legacy authService.ts (framework-agnostic).
 */
import httpClient from '@/api/http'
import logger from '@/utils/logger'

/** POST /api/auth/login response data (built in AuthController#login). */
export interface LoginResult {
  token: string
  username: string
  nickname?: string
  role?: string
  licenseEdition?: string
}

/** Currently authenticated user (persisted in localStorage). */
export interface AuthUser {
  username: string
  nickname: string
  role: string
  licenseEdition: string
}

const STORAGE_KEYS = ['token', 'username', 'nickname', 'role', 'licenseEdition'] as const

/** POST /api/auth/login */
export const loginRequest = (username: string, password: string, signal?: AbortSignal) =>
  httpClient.post<LoginResult>('/api/auth/login', { username, password }, { signal })

/** POST /api/auth/logout (revokes the token server-side; fire-and-forget). */
export const logoutRequest = (signal?: AbortSignal) => {
  // Capture the token NOW and send it explicitly: local storage is cleared right
  // after logout, so relying on the request interceptor would race the clear and
  // often send /auth/logout with NO token, leaving it valid until expiry.
  const token = localStorage.getItem('token')
  return httpClient.post<void>('/api/auth/logout', undefined, {
    signal,
    timeout: 5000,
    headers: token ? { Authorization: `Bearer ${token}` } : undefined,
  }).catch((err) => {
    logger.warn('Logout request failed (ignored):', err instanceof Error ? err.message : err)
  })
}

/** Persist login result into localStorage (keeps legacy key names). */
export const persistLogin = (result: LoginResult): AuthUser => {
  const user: AuthUser = {
    username: result.username,
    nickname: result.nickname || result.username,
    role: result.role || 'USER',
    licenseEdition: result.licenseEdition || 'BASIC',
  }
  localStorage.setItem('token', result.token)
  localStorage.setItem('username', user.username)
  localStorage.setItem('nickname', user.nickname)
  localStorage.setItem('role', user.role)
  localStorage.setItem('licenseEdition', user.licenseEdition)
  return user
}

/** Read the stored user when a token is present. */
export const getStoredUser = (): AuthUser | null => {
  const token = localStorage.getItem('token')
  const username = localStorage.getItem('username')
  if (!token || !username) return null
  return {
    username,
    nickname: localStorage.getItem('nickname') || username,
    role: localStorage.getItem('role') || 'USER',
    licenseEdition: localStorage.getItem('licenseEdition') || 'BASIC',
  }
}

/** Clear all auth storage. */
export const clearStoredAuth = (): void => {
  STORAGE_KEYS.forEach((key) => localStorage.removeItem(key))
}
