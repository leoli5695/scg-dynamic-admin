import type { ThemeConfig } from 'antd';

/**
 * Ant Design Theme Configuration
 * Colors aligned with CSS variables in variables.css for consistency
 * Note: Antd requires hex colors in theme config (CSS vars not supported)
 */
const theme: ThemeConfig = {
  token: {
    // Background - aligned with --bg-base (#09090b) and --bg-secondary
    colorBgBase: '#09090b',
    colorBgContainer: '#18181b',      // aligned with --bg-secondary
    colorBgElevated: '#27272a',       // aligned with --bg-elevated
    colorBorder: 'rgba(255, 255, 255, 0.10)',   // aligned with --border-default
    // Text - aligned with CSS variables
    colorText: '#fafafa',             // aligned with --text-primary
    colorTextSecondary: '#a1a1aa',    // aligned with --text-secondary
    colorTextTertiary: '#71717a',     // aligned with --text-muted
    borderRadius: 12,
    boxShadow: '0 10px 15px -3px rgba(0, 0, 0, 0.3)',
    // Brand colors - aligned with CSS variables
    colorPrimary: '#3b82f6',          // aligned with --primary-color
    colorSuccess: '#22c55e',          // aligned with --success-color
    colorWarning: '#eab308',          // aligned with --warning-color
    colorError: '#ef4444',            // aligned with --error-color
    colorBgContainerDisabled: 'rgba(255,255,255,0.05)',
  },
  components: {
    Card: {
      colorBgContainer: '#18181b',    // aligned with --bg-secondary
      borderRadius: 12,
      boxShadow: '0 4px 20px rgba(0, 0, 0, 0.25)',
    },
    Layout: {
      colorBgHeader: '#09090b',       // aligned with --bg-base
      colorBgBody: '#09090b',         // aligned with --bg-base
    },
    Menu: {
      colorBgContainer: '#09090b',    // aligned with --bg-base
    },
    Button: {
      borderRadius: 8,
      colorPrimary: '#3b82f6',        // aligned with --primary-color
    },
    Tag: {
      colorBgContainer: 'rgba(255,255,255,0.08)',
    },
    Alert: {
      colorBgContainer: 'rgba(234, 179, 8, 0.1)',  // aligned with --warning-color
    },
    Statistic: {
      colorText: '#fafafa',           // aligned with --text-primary
      colorTextSecondary: '#a1a1aa',  // aligned with --text-secondary
    },
    Input: {
      colorBgContainer: '#09090b',    // aligned with --bg-input
      colorBorder: 'rgba(255, 255, 255, 0.10)',  // aligned with --border-default
    },
    Select: {
      colorBgContainer: '#18181b',    // aligned with --bg-secondary
      colorBorder: 'rgba(255, 255, 255, 0.15)',  // aligned with --border-strong
      colorText: '#fafafa',           // aligned with --text-primary
      optionSelectedBg: '#27272a',    // aligned with --bg-elevated
    },
    Modal: {
      colorBgContainer: '#18181b',    // aligned with --bg-secondary
      colorBgElevated: '#27272a',     // aligned with --bg-elevated
      lineWidthFocus: 0,
    },
    Drawer: {
      colorBgContainer: '#18181b',    // aligned with --bg-secondary
    },
    Table: {
      colorBgContainer: '#18181b',    // aligned with --bg-secondary
      colorBorder: 'rgba(255, 255, 255, 0.10)',  // aligned with --border-default
    },
  },
};

export default theme;
