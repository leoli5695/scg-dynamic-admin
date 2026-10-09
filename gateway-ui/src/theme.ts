import type { ThemeConfig } from 'antd';

/**
 * Ant Design Theme Configuration
 * Colors aligned with CSS variables in variables.css for consistency
 * Note: Antd requires hex colors in theme config (CSS vars not supported)
 */
const theme: ThemeConfig = {
  token: {
    // Background - aligned with --bg-base (#0a0f1e) and --bg-secondary
    colorBgBase: '#0a0f1e',
    colorBgContainer: '#101b30',      // aligned with --bg-card
    colorBgElevated: '#1a2740',       // aligned with --bg-elevated
    colorBorder: 'rgba(148, 163, 184, 0.18)',   // aligned with --border-default
    // Text - aligned with CSS variables
    colorText: '#eaf1fb',             // aligned with --text-primary
    colorTextSecondary: '#a5b4cb',    // aligned with --text-secondary
    colorTextTertiary: '#6b7c95',     // aligned with --text-muted
    borderRadius: 12,
    boxShadow: '0 10px 15px -3px rgba(3, 7, 18, 0.45)',
    // Brand colors - aligned with CSS variables
    colorPrimary: '#3b82f6',          // aligned with --primary-color
    colorSuccess: '#22c55e',          // aligned with --success-color
    colorWarning: '#eab308',          // aligned with --warning-color
    colorError: '#ef4444',            // aligned with --error-color
    colorBgContainerDisabled: 'rgba(148,163,184,0.08)',
  },
  components: {
    Card: {
      colorBgContainer: '#101b30',    // aligned with --bg-card
      borderRadius: 12,
      boxShadow: '0 1px 2px rgba(3, 7, 18, 0.30), 0 8px 24px -12px rgba(3, 7, 18, 0.60)',
    },
    Layout: {
      colorBgHeader: '#0e1728',       // aligned with --bg-secondary
      colorBgBody: '#0c1424',         // aligned with --bg-primary
    },
    Menu: {
      colorBgContainer: '#0e1728',    // aligned with --bg-secondary
    },
    Button: {
      borderRadius: 8,
      colorPrimary: '#3b82f6',        // aligned with --primary-color
    },
    Tag: {
      colorBgContainer: 'rgba(148,163,184,0.10)',
    },
    Alert: {
      colorBgContainer: 'rgba(234, 179, 8, 0.1)',  // aligned with --warning-color
    },
    Statistic: {
      colorText: '#eaf1fb',           // aligned with --text-primary
      colorTextSecondary: '#a5b4cb',  // aligned with --text-secondary
    },
    Descriptions: {
      labelBg: 'transparent',         // no highlight patch behind labels on dark cards
    },
    Input: {
      colorBgContainer: '#0a1120',    // aligned with --bg-input
      colorBorder: 'rgba(148, 163, 184, 0.18)',  // aligned with --border-default
    },
    Select: {
      colorBgContainer: '#101b30',    // aligned with --bg-card
      colorBorder: 'rgba(148, 163, 184, 0.25)',  // aligned with --border-strong
      colorText: '#eaf1fb',           // aligned with --text-primary
      optionSelectedBg: '#1a2740',    // aligned with --bg-elevated
    },
    Modal: {
      colorBgContainer: '#101b30',    // aligned with --bg-card
      colorBgElevated: '#1a2740',     // aligned with --bg-elevated
      lineWidthFocus: 0,
    },
    Drawer: {
      colorBgContainer: '#101b30',    // aligned with --bg-card
    },
    Table: {
      colorBgContainer: '#101b30',    // aligned with --bg-card
      colorBorder: 'rgba(148, 163, 184, 0.12)',  // aligned with --border-subtle
      // Header/hover backgrounds must be themed here: plain CSS overrides lose to antd v6 runtime CSS-in-JS
      headerBg: '#15213a',
      headerColor: '#a5b4cb',
      headerSplitColor: 'transparent',
      headerSortActiveBg: '#15213a',
      headerSortHoverBg: '#1b2a47',
      borderColor: 'rgba(148, 163, 184, 0.10)',
      rowHoverBg: 'rgba(96, 165, 250, 0.08)',
    },
  },
};

export default theme;
