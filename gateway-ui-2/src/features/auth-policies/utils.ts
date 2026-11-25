/**
 * Auth policies feature — pure helpers.
 * Labels/tones/descriptions for the five backend AuthType codes.
 */
import type { AuthType } from '@/types/authPolicy'

export type TFunc = (key: string, options?: Record<string, unknown>) => string

/** enums/AuthType codes in display order. */
export const AUTH_TYPES: AuthType[] = ['JWT', 'API_KEY', 'OAUTH2', 'BASIC', 'HMAC']

const TYPE_LABEL_KEYS: Partial<Record<AuthType, string>> = {
  API_KEY: 'auth_policy.type_api_key',
  BASIC: 'auth_policy.type_basic',
}

const TYPE_LITERAL: Partial<Record<AuthType, string>> = {
  JWT: 'JWT',
  OAUTH2: 'OAuth2',
  HMAC: 'HMAC',
}

/** Semantic tone per auth type (maps to the page-scoped tone classes in CSS). */
export type AuthTone = 'primary' | 'success' | 'accent' | 'warning' | 'error'

const TYPE_TONE: Record<string, AuthTone> = {
  JWT: 'primary',
  API_KEY: 'success',
  OAUTH2: 'accent',
  BASIC: 'warning',
  HMAC: 'error',
}

const TYPE_DESC_KEYS: Record<string, string> = {
  JWT: 'auth_policy.type_desc_jwt',
  API_KEY: 'auth_policy.type_desc_api_key',
  OAUTH2: 'auth_policy.type_desc_oauth2',
  BASIC: 'auth_policy.type_desc_basic',
  HMAC: 'auth_policy.type_desc_hmac',
}

export const getAuthTypeLabel = (type: string, t: TFunc): string => {
  const key = TYPE_LABEL_KEYS[type as AuthType]
  if (key) return t(key)
  return TYPE_LITERAL[type as AuthType] ?? type
}

export const getAuthTone = (type: string): AuthTone => TYPE_TONE[type] ?? 'primary'

/** Page-scoped tone class shared by the card icon and type tag. */
export const authToneClass = (type: string): string => `auth-tone-${getAuthTone(type)}`

/** i18n key of the tooltip description; undefined for unknown types. */
export const getAuthTypeDescKey = (type: string): string | undefined => TYPE_DESC_KEYS[type]

/**
 * Type-specific form fields. Reset when the auth type changes in the create
 * form so config of a previous type never leaks into the new payload.
 */
export const AUTH_CONFIG_FIELDS: string[] = [
  // JWT
  'secretKey', 'jwtIssuer', 'jwtAudience', 'jwtAlgorithm', 'jwtPublicKey', 'jwtClockSkewSeconds',
  // API Key
  'apiKey', 'apiKeyHeader', 'apiKeyQueryParam', 'apiKeyPrefix',
  // OAuth2
  'clientId', 'clientSecret', 'tokenEndpoint', 'userInfoEndpoint', 'requiredScopes', 'tokenCacheTtlSeconds',
  // Basic
  'basicUsername', 'basicPassword', 'realm',
  // HMAC
  'accessKey', 'signatureAlgorithm', 'clockSkewMinutes', 'requireNonce', 'validateContentMd5',
]

/**
 * Defaults matching the backend model defaults (AuthPolicyDefinition.java);
 * applied when an auth type is selected so they reach the payload even if
 * the user never touches the field.
 */
export const AUTH_TYPE_DEFAULTS: Partial<Record<AuthType, Record<string, unknown>>> = {
  JWT: { jwtAlgorithm: 'HS256', jwtClockSkewSeconds: 60 },
  API_KEY: { apiKeyHeader: 'X-API-Key' },
  BASIC: { realm: 'API Gateway' },
  HMAC: { signatureAlgorithm: 'HMAC-SHA256', clockSkewMinutes: 5, requireNonce: true },
}