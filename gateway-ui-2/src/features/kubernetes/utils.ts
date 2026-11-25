/**
 * Tone helpers shared by the Kubernetes page and its cards/tables.
 * Badge/dot tone names match the global .status-badge-* / .status-dot-* classes.
 */

export type K8sTone = 'success' | 'warning' | 'error' | 'info' | 'neutral'

/** Badge tone for cluster.connectionStatus (same buckets as the React page). */
export function clusterStatusTone(status: string): K8sTone {
  if (status === 'CONNECTED') return 'success'
  if (status === 'CONNECTION_FAILED' || status === 'ERROR') return 'error'
  return 'neutral'
}

/** React maps every non-connected/non-failed status to the "Unknown" label. */
export function clusterStatusTextKey(status: string): string {
  if (status === 'CONNECTED') return 'k8s.status_connected'
  if (status === 'CONNECTION_FAILED' || status === 'ERROR') return 'k8s.status_failed'
  return 'k8s.status_unknown'
}

export function phaseTone(phase?: string): K8sTone {
  if (phase === 'Running') return 'success'
  if (phase === 'Pending') return 'info'
  return 'error'
}

export function nodeStatusTone(status?: string): K8sTone {
  return status === 'True' ? 'success' : 'error'
}

export function formatNumber(value?: number): string {
  return typeof value === 'number' ? String(value) : '—'
}

export function formatMemoryGb(value?: number): string {
  return typeof value === 'number' ? `${value} GB` : '—'
}