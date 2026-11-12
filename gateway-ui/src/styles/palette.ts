/**
 * Literal colour values for places CSS variables cannot reach: antd's CSS-in-JS
 * tokens, echarts/canvas options, and inline styles computed at runtime.
 *
 * This is the JS twin of styles/variables.css (both derived from the login page).
 * Change a colour here and it must change there too.
 */

export const palette = {
  brandFrom: '#477fec',
  brandTo: '#6866dc',
  primary: '#4c80ec',
  primaryHover: '#5f90f7',
  primaryActive: '#3d6cd4',
  primaryLight: '#8ab0ff',
  primaryTint: '#d8e5ff',
  accent: '#9b8cf5',
  accentHover: '#b0a2ff',
  accentLight: '#c9bfff',
  accentTint: '#e2dbff',

  success: '#3ddc97',
  successTint: '#c9f5e2',
  warning: '#e8b464',
  warningTint: '#f6e2bd',
  error: '#f2717f',
  errorTint: '#ffd7db',
  info: '#5fc9d8',
  infoTint: '#c8eef4',

  // Deep enough to carry a white label at 4.5:1.
  successSolid: '#15855d',
  warningSolid: '#996a24',
  errorSolid: '#c0424f',
  infoSolid: '#22788a',
  accentSolid: '#7061d8',

  bgBase: '#080d19',
  bgPrimary: '#0a1020',
  bgSecondary: '#0d1526',
  bgTertiary: '#101a2c',
  bgCard: '#101828',
  bgCardHover: '#141f34',
  bgElevated: '#17223a',
  bgInput: '#0c1425',
  bgChip: '#111c30',
  bgTableHead: '#131d31',
  bgSwitch: '#1b2740',

  textPrimary: '#edf2ff',
  textSecondary: '#b4c4de',
  textTertiary: '#95a3bc',
  textMuted: '#8b9bb8',
  textCaption: '#72819d',
  textDisabled: '#5d6c88',
  onBrand: '#ffffff',

  borderSubtle: 'rgba(145, 169, 222, 0.12)',
  borderDefault: 'rgba(145, 169, 222, 0.20)',
  borderStrong: 'rgba(145, 169, 222, 0.32)',
  borderSolid: '#2b3a50',
  borderSolidStrong: '#47648c',
  focus: '#7ca7ff',

  primarySoft: 'rgba(76, 128, 236, 0.14)',
  accentSoft: 'rgba(155, 140, 245, 0.14)',
  successSoft: 'rgba(61, 220, 151, 0.12)',
  warningSoft: 'rgba(232, 180, 100, 0.12)',
  errorSoft: 'rgba(242, 113, 127, 0.12)',
  infoSoft: 'rgba(95, 201, 216, 0.12)',
  hover: 'rgba(121, 157, 249, 0.10)',
  scrim: 'rgba(4, 8, 18, 0.62)',

  gradientPrimary: 'linear-gradient(105deg, #477fec, #6866dc)',
  gradientPrimaryHover: 'linear-gradient(100deg, #578ef9, #7977ed)',
  gradientCard:
    'radial-gradient(ellipse at 100% 0%, #1a2440, transparent 55%), linear-gradient(150deg, #141d31, #101828 65%)',
} as const;

/**
 * Categorical series palette. Ordered so neighbours differ in luminance as well
 * as hue, which keeps them separable for colour-blind viewers and in greyscale.
 */
export const chartColors = [
  '#6f9dff', // periwinkle
  '#65d6ce', // teal
  '#b3a0f5', // violet
  '#e8b464', // amber
  '#3ddc97', // green
  '#f2717f', // rose
  '#7bbaff', // sky
  '#cf8bf0', // orchid
] as const;

export const chartAxis = {
  grid: 'rgba(145, 169, 222, 0.10)',
  line: 'rgba(145, 169, 222, 0.20)',
  label: '#8b9bb8',
  tooltipBg: '#17223a',
  tooltipBorder: 'rgba(145, 169, 222, 0.20)',
  tooltipText: '#edf2ff',
} as const;

/** Semantic tones keyed by the names the UI already uses for status. */
export const statusTone = {
  success: { color: palette.success, solid: palette.successSolid, bg: palette.successSoft, border: 'rgba(61, 220, 151, 0.28)' },
  warning: { color: palette.warning, solid: palette.warningSolid, bg: palette.warningSoft, border: 'rgba(232, 180, 100, 0.28)' },
  error: { color: palette.error, solid: palette.errorSolid, bg: palette.errorSoft, border: 'rgba(242, 113, 127, 0.30)' },
  info: { color: palette.info, solid: palette.infoSolid, bg: palette.infoSoft, border: 'rgba(95, 201, 216, 0.28)' },
  primary: { color: palette.primaryLight, solid: palette.primary, bg: palette.primarySoft, border: 'rgba(124, 167, 255, 0.30)' },
  accent: { color: palette.accent, solid: palette.accentSolid, bg: palette.accentSoft, border: 'rgba(155, 140, 245, 0.28)' },
  neutral: { color: palette.textTertiary, solid: palette.textDisabled, bg: 'rgba(145, 169, 222, 0.08)', border: palette.borderSubtle },
} as const;

export type StatusToneName = keyof typeof statusTone;

/**
 * Alpha over a palette hex. Concatenating `${hex}20` breaks the moment a token
 * becomes an rgba() string, so tinted fills go through here instead.
 */
export const withAlpha = (hex: string, alpha: number): string => {
  const h = hex.replace('#', '');
  const full = h.length === 3 ? h.split('').map((c) => c + c).join('') : h;
  const n = parseInt(full, 16);
  return `rgba(${(n >> 16) & 255}, ${(n >> 8) & 255}, ${n & 255}, ${alpha})`;
};

/**
 * Backend-supplied hues (strategy type colours, plugin categories) are authored
 * against the old palette. Snap them onto this one instead of matching literal
 * hex strings in CSS attribute selectors, which break the moment a value changes.
 */
const LEGACY_TONE: Record<string, StatusToneName> = {
  '#3b82f6': 'primary', '#1890ff': 'primary', '#165dff': 'primary', '#1677ff': 'primary',
  '#60a5fa': 'primary', '#4096ff': 'primary',
  '#f59e0b': 'warning', '#faad14': 'warning', '#fa8c16': 'warning', '#eab308': 'warning',
  '#fbbf24': 'warning', '#d97706': 'warning',
  '#ef4444': 'error', '#ff4d4f': 'error', '#f5222d': 'error', '#f53f3f': 'error',
  '#dc2626': 'error', '#cf1322': 'error',
  '#10b981': 'success', '#52c41a': 'success', '#22c55e': 'success', '#00b42a': 'success',
  '#34d399': 'success', '#059669': 'success',
  '#8b5cf6': 'accent', '#722ed1': 'accent', '#7c3aed': 'accent', '#6366f1': 'accent',
  '#a855f7': 'accent', '#14c9c9': 'info', '#13c2c2': 'info', '#0fc6c2': 'info',
  '#00d9c6': 'info', '#06b6d4': 'info',
};

export const toneForLegacyColor = (hex?: string | null): StatusToneName => {
  if (!hex) return 'primary';
  return LEGACY_TONE[hex.toLowerCase()] ?? 'primary';
};