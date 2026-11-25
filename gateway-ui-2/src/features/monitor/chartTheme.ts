/**
 * Chart theme for the monitor page.
 * Values mirror the login page palette (features/auth/login.css) and the
 * token-usage charts, so the console keeps one periwinkle→violet→cyan series
 * language. Semantic red/amber are reused only for warning/error metrics.
 */

export const SERIES_COLORS: Record<string, string> = {
  blue: '#6998ef',
  cyan: '#4fd6e6',
  purple: '#a492ea',
  magenta: '#b6a6f2',
  orange: '#eab308',
  red: '#ef4444',
  green: '#7fdfe8',
}

export const AXIS_TEXT = '#95a3bc'
export const AXIS_LINE = 'rgba(145, 169, 222, 0.18)'
export const AXIS_GRID = 'rgba(145, 169, 222, 0.08)'

/** Stable identity: vue-echarts re-inits (and disposes) when initOptions identity changes. */
export const CHART_INIT_OPTIONS = { renderer: 'svg' as const }

export const chartTooltip = {
  trigger: 'axis' as const,
  backgroundColor: '#17233c',
  borderColor: 'rgba(145, 169, 222, 0.23)',
  borderWidth: 1,
  padding: [8, 12] as [number, number],
  textStyle: { color: '#edf2ff', fontSize: 12 },
  extraCssText: 'border-radius:10px;box-shadow:0 12px 28px rgba(4,7,15,0.46);',
}

/** Series color → vertical area gradient (solid ink fading to transparent). */
export function areaFade(color: string) {
  return {
    type: 'linear' as const,
    x: 0, y: 0, x2: 0, y2: 1,
    colorStops: [
      { offset: 0, color: `${color}4d` },
      { offset: 1, color: `${color}00` },
    ],
  }
}

/** Compact axis/tooltip numbers: 12.4K / 3.1M. */
export function compactNumber(value: number): string {
  const abs = Math.abs(value)
  if (abs >= 1e6) return `${(value / 1e6).toFixed(1)}M`
  if (abs >= 1e3) return `${(value / 1e3).toFixed(1)}K`
  if (abs >= 1) return value.toFixed(1)
  return value.toFixed(2)
}