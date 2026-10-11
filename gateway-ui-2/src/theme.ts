import type { ThemeConfig } from 'ant-design-vue/es/config-provider/context'

/**
 * Ant Design Vue theme configuration.
 * Colors aligned with the CSS variables in styles/variables.css for consistency.
 * Ported 1:1 from the legacy React theme.ts (manual dark tokens, no darkAlgorithm)
 * so the visual baseline matches before any cleanup.
 */
const theme = {
  token: {
    colorBgBase: '#0a0f1e',
    colorBgContainer: '#101b30',
    colorBgElevated: '#1a2740',
    colorBorder: 'rgba(148, 163, 184, 0.18)',
    colorText: '#eaf1fb',
    colorTextSecondary: '#a5b4cb',
    colorTextTertiary: '#6b7c95',
    borderRadius: 12,
    boxShadow: '0 10px 15px -3px rgba(3, 7, 18, 0.45)',
    colorPrimary: '#3b82f6',
    colorSuccess: '#22c55e',
    colorWarning: '#eab308',
    colorError: '#ef4444',
    colorBgContainerDisabled: 'rgba(148,163,184,0.08)',
  },
  components: {
    Card: {
      colorBgContainer: '#101b30',
      borderRadius: 12,
      boxShadow: '0 1px 2px rgba(3, 7, 18, 0.30), 0 8px 24px -12px rgba(3, 7, 18, 0.60)',
    },
    Layout: {
      colorBgHeader: '#0e1728',
      colorBgBody: '#0c1424',
    },
    Menu: {
      colorBgContainer: '#0e1728',
    },
    Button: {
      borderRadius: 8,
      colorPrimary: '#3b82f6',
    },
    Tag: {
      colorBgContainer: 'rgba(148,163,184,0.10)',
    },
    Alert: {
      colorBgContainer: 'rgba(234, 179, 8, 0.1)',
    },
    Statistic: {
      colorText: '#eaf1fb',
      colorTextSecondary: '#a5b4cb',
    },
    Descriptions: {
      labelBg: 'transparent',
    },
    Input: {
      colorBgContainer: '#0a1120',
      colorBorder: 'rgba(148, 163, 184, 0.18)',
    },
    Select: {
      colorBgContainer: '#101b30',
      colorBorder: 'rgba(148, 163, 184, 0.25)',
      colorText: '#eaf1fb',
      optionSelectedBg: '#1a2740',
    },
    Modal: {
      colorBgContainer: '#101b30',
      colorBgElevated: '#1a2740',
      lineWidthFocus: 0,
    },
    Drawer: {
      colorBgContainer: '#101b30',
    },
    Table: {
      colorBgContainer: '#101b30',
      colorBorder: 'rgba(148, 163, 184, 0.12)',
      headerBg: '#15213a',
      headerColor: '#a5b4cb',
      headerSplitColor: 'transparent',
      headerSortActiveBg: '#15213a',
      headerSortHoverBg: '#1b2a47',
      borderColor: 'rgba(148, 163, 184, 0.10)',
      rowHoverBg: 'rgba(96, 165, 250, 0.08)',
    },
  },
} as ThemeConfig

export default theme
