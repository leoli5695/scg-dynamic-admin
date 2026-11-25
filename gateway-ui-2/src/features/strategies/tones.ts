/**
 * Backend strategy types carry legacy palette hex values. Snap them onto the
 * semantic tones provided by styles/variables.css instead of styling raw hex.
 */
export type StrategyTone = 'primary' | 'success' | 'warning' | 'error' | 'info' | 'accent' | 'neutral'

const LEGACY_TONE: Record<string, StrategyTone> = {
  '#3b82f6': 'primary', '#1890ff': 'primary', '#165dff': 'primary', '#1677ff': 'primary',
  '#60a5fa': 'primary', '#4096ff': 'primary', '#4c80ec': 'primary',
  '#f59e0b': 'warning', '#faad14': 'warning', '#fa8c16': 'warning', '#eab308': 'warning',
  '#fbbf24': 'warning', '#d97706': 'warning',
  '#ef4444': 'error', '#ff4d4f': 'error', '#f5222d': 'error', '#f53f3f': 'error',
  '#dc2626': 'error', '#cf1322': 'error',
  '#10b981': 'success', '#52c41a': 'success', '#22c55e': 'success', '#00b42a': 'success',
  '#34d399': 'success', '#059669': 'success',
  '#8b5cf6': 'accent', '#722ed1': 'accent', '#7c3aed': 'accent', '#6366f1': 'accent',
  '#a855f7': 'accent', '#14c9c9': 'info', '#13c2c2': 'info', '#0fc6c2': 'info',
  '#00d9c6': 'info', '#06b6d4': 'info',
}

export const toneForLegacyColor = (hex?: string | null): StrategyTone => {
  if (!hex) return 'primary'
  return LEGACY_TONE[hex.toLowerCase()] ?? 'primary'
}

/** Category → type-tag class (page-scoped colour families). */
export const categoryToneClass = (category?: string): string => {
  switch (category) {
    case 'traffic': return 'strategy-cat-primary'
    case 'resilience': return 'strategy-cat-warning'
    default: return 'strategy-cat-success'
  }
}