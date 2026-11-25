/**
 * Topology chart palette — hex mirror of the login skin tokens, kept in one
 * place because ECharts cannot consume CSS custom properties.
 * Sources: styles/variables.css (--gradient-primary stops, --primary-color,
 * --text-*, --bg-base) and styles/login-theme.css badge inks; cyan matches
 * features/monitor/chartTheme.ts so both chart pages read as one series family.
 */

export const TOPOLOGY_COLORS = {
  /** --gradient-primary start: gateway nodes, default edges. */
  blue: '#6998ef',
  /** --primary-color: focused path + emphasis ring. */
  periwinkle: '#7f9dff',
  /** --primary-light: selected node border. */
  periwinkleLight: '#a9c0ff',
  /** monitor series cyan: enabled routes. */
  cyan: '#4fd6e6',
  /** --gradient-primary end: service-discovery nodes. */
  violet: '#a492ea',
  /** status-badge-success ink: static services, healthy instances. */
  mint: '#86efac',
  /** status-badge-warning ink: clients, partial health, warn edges. */
  amber: '#fde68a',
  /** status-badge-error ink: unhealthy nodes, error edges. */
  rose: '#fca5a5',
  /** --text-muted: disabled routes. */
  muted: '#7d8dab',
  /** --text-disabled: disabled route labels and edges. */
  disabled: '#4d5c74',
  /** --bg-base: node ring, matches the page canvas. */
  canvas: '#080d19',
  textPrimary: '#edf2ff',
  textSecondary: '#a5b4cb',
  textTertiary: '#8fa1c0',
  tooltipBg: '#17233c',
  tooltipBorder: 'rgba(145, 169, 222, 0.23)',
  hairline: 'rgba(145, 169, 222, 0.18)',
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