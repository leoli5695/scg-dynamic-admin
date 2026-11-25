/** Monitor page helpers — port of gateway-ui/src/features/monitor/utils.tsx. */

export type Tone = 'success' | 'warning' | 'error' | 'info' | 'neutral'

/** Human-readable byte size. */
export function formatBytes(bytes: number): { value: number; unit: string; display: string } {
  if (!bytes) return { value: 0, unit: 'B', display: '0 B' }
  const k = 1024
  const sizes = ['B', 'KB', 'MB', 'GB']
  const i = Math.min(Math.floor(Math.log(bytes) / Math.log(k)), sizes.length - 1)
  const value = parseFloat((bytes / Math.pow(k, i)).toFixed(2))
  const unit = sizes[i]
  return { value, unit, display: `${value} ${unit}` }
}

/** Prometheus instance status → capsule tone. */
export function instanceTone(status?: string): Tone {
  if (status === 'UP') return 'success'
  if (status === 'DOWN') return 'error'
  return 'warning'
}

/** K8s pod phase → capsule tone. */
export function podTone(phase?: string): Tone {
  switch (phase) {
    case 'Running': return 'success'
    case 'Pending': return 'info'
    case 'Failed':
    case 'Error': return 'error'
    default: return 'warning'
  }
}

/** Route health status → capsule tone. */
export function healthTone(status?: string): Tone {
  if (status === 'HEALTHY') return 'success'
  if (status === 'WARNING') return 'warning'
  return 'error'
}

/** HTTP method → capsule tone. */
export function methodTone(method?: string): Tone {
  switch (method) {
    case 'GET': return 'info'
    case 'POST': return 'success'
    case 'PUT': return 'warning'
    case 'DELETE': return 'error'
    default: return 'neutral'
  }
}

/** Percent value (0-100) clamped for ant-progress. */
export function progressPercent(value: number | undefined): number {
  return Math.min(100, Math.max(0, value || 0))
}

/** Map the legacy capsule tone buckets onto the Vue-native badge tones. */
export type VnTone = 'ok' | 'warn' | 'err' | 'info' | 'idle'
export function vnTone(tone: Tone): VnTone {
  if (tone === 'success') return 'ok'
  if (tone === 'warning') return 'warn'
  if (tone === 'error') return 'err'
  if (tone === 'info') return 'info'
  return 'idle'
}