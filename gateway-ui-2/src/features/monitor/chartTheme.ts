/**
 * Chart theme for the monitor page on the Vue-native layer.
 * Series use the vn accent family (Vue green primary, periwinkle/info blue,
 * amber warn, red err); axes and tooltip use the matte vn surfaces so charts
 * sit inside the same flat panels as the rest of the console.
 */

export const SERIES_COLORS: Record<string, string> = {
  blue: '#5b9cf8',
  cyan: '#4fd6e6',
  purple: '#7f9dff',
  magenta: '#7f9dff',
  orange: '#f0b429',
  red: '#f2555a',
  green: '#42d392',
}

export const AXIS_TEXT = '#9aa7bd'
export const AXIS_LINE = '#253048'
export const AXIS_GRID = '#1c2537'

/** Stable identity: vue-echarts re-inits (and disposes) when initOptions identity changes. */
export const CHART_INIT_OPTIONS = { renderer: 'svg' as const }

export const chartTooltip = {
  trigger: 'axis' as const,
  backgroundColor: '#1a2233',
  borderColor: '#253048',
  borderWidth: 1,
  padding: [8, 12] as [number, number],
  textStyle: { color: '#e6ebf5', fontSize: 12 },
  extraCssText: 'border-radius:6px;box-shadow:0 18px 44px -20px rgba(0,0,0,0.7);',
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