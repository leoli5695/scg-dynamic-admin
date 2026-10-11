/**
 * Unified HTTP client for the gateway admin API.
 *
 * Handles auth token injection, the ApiResponse envelope ({code, message, data}),
 * legacy raw-response endpoints, 401/403 handling, and AbortController-based
 * request cancellation. Ported 1:1 from the legacy React api/http.ts; only the
 * i18n access (i18n.global.t) and the feedback import changed.
 */
import axios, { type AxiosError, type AxiosRequestConfig } from 'axios'
import i18n from '@/i18n'
import logger from '@/utils/logger'
import { message } from '@/utils/feedback'

/** Standard backend response envelope (dto/ApiResponse.java). */
export interface ApiResponseEnvelope<T> {
  code: number
  message?: string
  data?: T
  traceId?: string
  timestamp?: number
}

/** Error thrown for any failed API call. */
export class ApiError extends Error {
  /** HTTP status code (0 for network errors). */
  readonly status: number
  /** Business code from ApiResponse envelope when available. */
  readonly bizCode?: number
  /** Raw response body when available. */
  readonly payload?: unknown

  constructor(message: string, status: number, bizCode?: number, payload?: unknown) {
    super(message)
    this.name = 'ApiError'
    this.status = status
    this.bizCode = bizCode
    this.payload = payload
  }
}

/** True when the error originates from a cancelled (aborted) request. */
export const isAbortError = (error: unknown): boolean =>
  axios.isCancel(error)
  || (error instanceof DOMException && error.name === 'CanceledError')
  || (axios.isAxiosError(error) && error.code === 'ERR_CANCELED')

const http = axios.create({
  baseURL: import.meta.env.VITE_API_BASE_URL || '',
  // Long timeout: AI analysis endpoints can take minutes.
  timeout: 5 * 60 * 1000,
  // NOTE: no instance-level Content-Type default. Forcing application/json here
  // broke multipart uploads (FormData got JSON-serialized without a boundary).
})

/** Guard against repeated 403 toasts from concurrent API calls. */
let last403Toast = 0

http.interceptors.request.use((config) => {
  const token = localStorage.getItem('token')
  if (token) {
    config.headers.Authorization = `Bearer ${token}`
  }
  return config
})

http.interceptors.response.use(
  (response) => response,
  (error: AxiosError) => {
    if (isAbortError(error)) {
      return Promise.reject(error)
    }
    const status = error.response?.status
    if (status === 401) {
      // Token invalid / expired: clear credentials and redirect to login.
      localStorage.removeItem('token')
      localStorage.removeItem('username')
      localStorage.removeItem('nickname')
      localStorage.removeItem('role')
      localStorage.removeItem('licenseEdition')
      if (!window.location.pathname.startsWith('/login')) {
        window.location.href = '/login'
      }
    } else if (status === 403) {
      // 403 means the token is valid but the user lacks permission: only toast,
      // keep the user on the current page (do not force logout / lose form data).
      const now = Date.now()
      if (now - last403Toast > 3000) {
        last403Toast = now
        message.error(i18n.global.t('common.error_no_permission'))
      }
    } else {
      logger.error(
        `HTTP ${status ?? 'ERR'} ${error.config?.method?.toUpperCase()} ${error.config?.url}`,
        error.response?.data ?? error.message,
      )
    }
    return Promise.reject(error)
  },
)

/**
 * Detect whether a response body is an ApiResponse envelope.
 * Some legacy endpoints return the raw payload directly. Error envelopes may omit
 * `data`, so a numeric non-200 `code` also identifies an envelope.
 */
const isEnvelope = (body: unknown): body is ApiResponseEnvelope<unknown> =>
  typeof body === 'object'
  && body !== null
  && !Array.isArray(body)
  && typeof (body as ApiResponseEnvelope<unknown>).code === 'number'
  && ('data' in (body as Record<string, unknown>)
    || (body as ApiResponseEnvelope<unknown>).code !== 200)

/**
 * Extract the business payload from a response body.
 * Throws ApiError when the envelope reports a non-200 business code.
 */
function unwrap<T>(body: unknown, fallbackStatus: number): T {
  if (isEnvelope(body)) {
    if (body.code !== 200) {
      throw new ApiError(body.message || `Business error ${body.code}`, fallbackStatus, body.code, body)
    }
    return body.data as T
  }
  return body as T
}

/**
 * Map transport-level failures (no business envelope) to user-friendly text.
 * Backend-provided envelope messages are always preferred when present.
 */
function friendlyMessage(error: AxiosError): string {
  const status = error.response?.status
  if (status === undefined) {
    if (error.code === 'ECONNABORTED' || error.code === 'ETIMEDOUT') {
      return i18n.global.t('common.error_timeout')
    }
    return i18n.global.t('common.error_network')
  }
  if (status >= 500) return i18n.global.t('common.error_server')
  if (status === 404) return i18n.global.t('common.error_not_found')
  if (status === 429) return i18n.global.t('common.error_too_many')
  if (status >= 400) return i18n.global.t('common.error_request_failed')
  return error.message
}

/** Convert an axios error into an ApiError with the best available message. */
function toApiError(error: unknown): ApiError {
  if (error instanceof ApiError) return error
  if (axios.isAxiosError(error)) {
    const status = error.response?.status ?? 0
    const body = error.response?.data as ApiResponseEnvelope<unknown> | undefined
    const msg = (isEnvelope(body) && body.message) || friendlyMessage(error)
    return new ApiError(msg, status, isEnvelope(body) ? body.code : undefined, error.response?.data)
  }
  return new ApiError(error instanceof Error ? error.message : String(error), 0)
}

export interface RequestOptions {
  /** URL query parameters. */
  params?: Record<string, unknown>
  /** AbortSignal for cancellation. */
  signal?: AbortSignal
  /** Per-request timeout override. */
  timeout?: number
  /** Custom request headers (e.g. multipart uploads). */
  headers?: Record<string, string>
  /** Response type (default json). */
  responseType?: AxiosRequestConfig['responseType']
  /** Progress callback for uploads. */
  onUploadProgress?: (percent: number) => void
}

async function request<T>(method: string, url: string, body?: unknown, options?: RequestOptions): Promise<T> {
  try {
    const isFormData = typeof FormData !== 'undefined' && body instanceof FormData
    const headers: Record<string, string> = { ...(options?.headers || {}) }
    // Set JSON content type for real bodies only; let axios assign the multipart
    // boundary for FormData uploads.
    if (!isFormData && body !== undefined && !headers['Content-Type']) {
      headers['Content-Type'] = 'application/json'
    }
    const response = await http.request({
      method,
      url,
      data: body,
      params: options?.params,
      signal: options?.signal,
      timeout: options?.timeout,
      headers,
      responseType: options?.responseType,
      onUploadProgress: options?.onUploadProgress
        ? (e) => {
          if (e.total) options.onUploadProgress!(Math.round((e.loaded / e.total) * 100))
        }
        : undefined,
    })
    return unwrap<T>(response.data, response.status)
  } catch (error) {
    throw toApiError(error)
  }
}

/** Typed HTTP helpers returning the unwrapped business payload. */
export const httpClient = {
  get: <T>(url: string, options?: RequestOptions) => request<T>('GET', url, undefined, options),
  post: <T>(url: string, body?: unknown, options?: RequestOptions) => request<T>('POST', url, body, options),
  put: <T>(url: string, body?: unknown, options?: RequestOptions) => request<T>('PUT', url, body, options),
  delete: <T>(url: string, options?: RequestOptions) => request<T>('DELETE', url, undefined, options),
  /** Raw axios access for special cases (blob downloads, SSE). Prefer typed helpers. */
  raw: http,
}

export default httpClient
