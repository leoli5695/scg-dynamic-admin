/**
 * Access log helpers — port of gateway-ui/src/features/access-log/utils.ts.
 * Tones map onto the shared login-skin badges/pills (status-badge-*, al-method-*)
 * instead of antd's palette names. JSON is tokenised for rendering as Vue nodes:
 * no innerHTML anywhere, so upstream bodies can never execute as markup.
 */
import dayjs from 'dayjs'

export type StatusTone = 'success' | 'warning' | 'error' | 'info' | 'neutral'

/** Badge tone for an HTTP status code. */
export const getStatusTone = (code: number): StatusTone => {
  if (code < 200) return 'neutral'
  if (code < 300) return 'success'
  if (code < 400) return 'info'
  if (code < 500) return 'warning'
  return 'error'
}

/** Pill variant for an HTTP method (falls back to the neutral pill). */
export const getMethodTone = (method?: string): string => {
  switch ((method || '').toUpperCase()) {
    case 'GET': return 'get'
    case 'POST': return 'post'
    case 'PUT': return 'put'
    case 'DELETE': return 'delete'
    case 'PATCH': return 'patch'
    default: return 'other'
  }
}

/** Duration tone thresholds shared by the table and the expanded detail. */
export const getDurationTone = (ms: number): string => (
  ms > 1000 ? 'is-error' : ms > 500 ? 'is-warning' : 'is-success'
)

export const formatClock = (ts?: string): string => {
  if (!ts) return '-'
  const parsed = dayjs(ts)
  return parsed.isValid() ? parsed.format('HH:mm:ss.SSS') : '-'
}

export const formatFullTime = (ts?: string): string => {
  if (!ts) return '-'
  const parsed = dayjs(ts)
  return parsed.isValid() ? parsed.format('YYYY-MM-DD HH:mm:ss.SSS') : '-'
}

/** Compact single-line JSON (falls back to the raw string). */
export const renderRawJson = (str?: string): string | null => {
  if (!str) return null
  try {
    return JSON.stringify(JSON.parse(str))
  } catch {
    return str
  }
}

/**
 * JSON.parse + highlighting a multi-megabyte body blocks the main thread for
 * seconds. Above this size the body waits behind a click and is never tokenised.
 */
export const LARGE_BODY_CHARS = 128 * 1024

export interface JsonToken {
  text: string
  kind: 'key' | 'string' | 'number' | 'boolean' | 'null' | 'bracket' | 'plain'
}

const TOKEN_PATTERN = /("(?:\\.|[^"\\])*")(\s*:)?|\b(?:true|false|null)\b|-?\d+(?:\.\d+)?(?:[eE][+-]?\d+)?|[{}[\],:]|\s+|./g

/** Pretty-printed JSON split into highlight tokens; invalid JSON stays one plain token. */
export const tokenizeJson = (str?: string): JsonToken[] => {
  if (!str) return []
  let formatted: string
  try {
    formatted = JSON.stringify(JSON.parse(str), null, 2)
  } catch {
    return [{ text: str, kind: 'plain' }]
  }
  const tokens: JsonToken[] = []
  for (const match of formatted.matchAll(TOKEN_PATTERN)) {
    const [text, quoted, colon] = match
    if (quoted !== undefined) {
      tokens.push({ text: quoted, kind: colon ? 'key' : 'string' })
      if (colon) tokens.push({ text: colon, kind: 'bracket' })
      continue
    }
    if (text === 'true' || text === 'false') tokens.push({ text, kind: 'boolean' })
    else if (text === 'null') tokens.push({ text, kind: 'null' })
    else if (/^-?\d/.test(text)) tokens.push({ text, kind: 'number' })
    else if (/^[{}[\],:]$/.test(text)) tokens.push({ text, kind: 'bracket' })
    else tokens.push({ text, kind: 'plain' })
  }
  return tokens
}