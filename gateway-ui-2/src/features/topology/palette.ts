/**
 * Topology chart palette — hex mirror of the Vue-native tokens, kept in one
 * place because ECharts cannot consume CSS custom properties.
 * Sources: styles/vue-native.css (--vn-info / --vn-ok / --vn-warn / --vn-err /
 * --vn-ink-* / --vn-surface-* / --vn-line). Cyan matches the monitor series family.
 */

export const TOPOLOGY_COLORS = {
  /** --vn-info: gateway nodes, default edges. */
  blue: '#5b9cf8',
  /** gradient-text end: focused path + emphasis ring. */
  periwinkle: '#7f9dff',
  /** lighter periwinkle: selected node border. */
  periwinkleLight: '#a9c0ff',
  /** monitor series cyan: enabled routes. */
  cyan: '#4fd6e6',
  /** gradient-text end: service-discovery nodes. */
  violet: '#7f9dff',
  /** --vn-ok: static services, healthy instances. */
  mint: '#42d392',
  /** --vn-warn: clients, partial health, warn edges. */
  amber: '#f0b429',
  /** --vn-err: unhealthy nodes, error edges. */
  rose: '#f2555a',
  /** --vn-ink-3: disabled routes. */
  muted: '#6f7d95',
  /** dimmed ink: disabled route labels and edges. */
  disabled: '#4d5c74',
  /** --vn-surface-sunken: node ring, matches the panel canvas. */
  canvas: '#0e1420',
  textPrimary: '#e6ebf5',
  textSecondary: '#9aa7bd',
  textTertiary: '#8fa1c0',
  tooltipBg: '#1a2233',
  tooltipBorder: '#253048',
  hairline: 'rgba(37, 48, 72, 0.6)',
} as const;

/** Stable identity: vue-echarts re-inits (and disposes) when initOptions identity changes. */
export const CHART_INIT_OPTIONS = { renderer: 'svg' as const };

/** #rrggbb → rgba() with the given alpha (ECharts shadow colors need rgba). */
export function withAlpha(hex: string, alpha: number): string {
  const value = hex.replace('#', '');
  const r = parseInt(value.slice(0, 2), 16);
  const g = parseInt(value.slice(2, 4), 16);
  const b = parseInt(value.slice(4, 6), 16);
  return `rgba(${r}, ${g}, ${b}, ${alpha})`;
}