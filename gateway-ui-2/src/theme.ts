import type { ThemeConfig } from 'ant-design-vue/es/config-provider/context'

/**
 * Ant Design Vue theme configuration.
 * The public login page (features/auth/login.css) is the visual source of truth:
 * deep navy canvas, periwinkle→violet accents, translucent gradient panels with a
 * 1px light border. Colors stay aligned with styles/variables.css.
 */
const theme = {
  token: {
    colorBgBase: '#080d19',
    colorBgContainer: '#101a2e',
    colorBgElevated: '#101a2e',
    colorBorder: 'rgba(145, 169, 222, 0.22)',
    colorBorderSecondary: 'rgba(132, 154, 196, 0.12)',
    colorText: '#edf2ff',
    colorTextSecondary: '#a5b4cb',
    colorTextTertiary: '#7d8dab',
    // Kept in sync with --text-muted so placeholders read as hints, not values.
    colorTextPlaceholder: '#7183a0',
    borderRadius: 11,
    borderRadiusLG: 20,
    borderRadiusSM: 9,
    controlHeight: 38,
    boxShadow: '0 18px 44px -24px rgba(2, 6, 23, 0.85)',
    boxShadowSecondary: '0 24px 60px -32px rgba(2, 6, 23, 0.9)',
    colorPrimary: '#7f9dff',
    colorPrimaryHover: '#93aefc',
    colorPrimaryActive: '#6a8cf0',
    colorSuccess: '#22c55e',
    colorWarning: '#eab308',
    colorError: '#ef4444',
    colorBgMask: 'rgba(3, 7, 18, 0.62)',
    colorSplit: 'rgba(132, 154, 196, 0.12)',
    controlItemBgHover: 'rgba(127, 157, 255, 0.10)',
    controlItemBgActive: 'rgba(127, 157, 255, 0.14)',
    colorBgContainerDisabled: 'rgba(148,163,184,0.08)',
    colorFillSecondary: 'rgba(148, 163, 184, 0.08)',
    colorFillTertiary: 'rgba(148, 163, 184, 0.05)',
  },
  components: {
    Card: {
      colorBgContainer: 'transparent',
      borderRadius: 20,
      boxShadow: 'none',
      paddingLG: 24,
    },
    Layout: {
      // The login canvas (washes + grid) shows through the shell.
      colorBgHeader: 'transparent',
      colorBgBody: 'transparent',
      colorBgTrigger: 'transparent',
    },
    Menu: {
      // ant-design-vue 4.2 Menu derives its dark/dropdown palette from these.
      colorItemBg: 'transparent',
      colorItemBgHover: 'rgba(127, 157, 255, 0.07)',
      colorItemBgActive: 'rgba(127, 157, 255, 0.10)',
      colorItemBgSelected: 'rgba(105, 152, 239, 0.16)',
      colorItemText: '#9fb0cc',
      colorItemTextHover: '#e8efff',
      colorItemTextSelected: '#e9f0ff',
      colorGroupTitle: '#8fa1c0',
      colorSubItemBg: 'transparent',
      radiusItem: 10,
      radiusSubMenuItem: 10,
      colorActiveBarWidth: 0,
      colorActiveBarHeight: 0,
      // v5-style aliases kept for dropdown menus rendered by newer hooks.
      itemBg: 'transparent',
      itemColor: '#9fb0cc',
      itemHoverBg: 'rgba(127, 157, 255, 0.07)',
      itemHoverColor: '#e8efff',
      itemSelectedBg: 'rgba(105, 152, 239, 0.16)',
      itemSelectedColor: '#e9f0ff',
      itemActiveBg: 'rgba(127, 157, 255, 0.10)',
      subMenuItemBg: 'transparent',
    },
    Button: {
      borderRadius: 11,
      colorPrimary: '#7f9dff',
      primaryShadow: '0 12px 28px -14px rgba(126, 157, 251, 0.55)',
      defaultBg: 'rgba(255, 255, 255, 0.02)',
      defaultBorderColor: 'rgba(145, 169, 222, 0.24)',
      defaultColor: '#dbe5f7',
    },
    Tag: {
      colorBgContainer: 'rgba(148,163,184,0.08)',
      borderRadiusSM: 999,
    },
    Alert: {
      colorBgContainer: 'rgba(127, 157, 255, 0.08)',
      borderRadiusLG: 14,
    },
    Statistic: {
      colorText: '#edf2ff',
      colorTextSecondary: '#a5b4cb',
    },
    Descriptions: {
      labelBg: 'transparent',
    },
    Input: {
      colorBgContainer: '#0b1323',
      colorBorder: 'rgba(126, 150, 193, 0.29)',
      activeBorderColor: '#7ca7ff',
      hoverBorderColor: 'rgba(128, 158, 215, 0.48)',
      activeShadow: '0 0 0 3px rgba(96, 149, 255, 0.12)',
      borderRadius: 11,
      paddingBlock: 7,
    },
    InputNumber: {
      colorBgContainer: '#0b1323',
      colorBorder: 'rgba(126, 150, 193, 0.29)',
      activeBorderColor: '#7ca7ff',
      borderRadius: 11,
    },
    Select: {
      colorBgContainer: '#0b1323',
      colorBorder: 'rgba(126, 150, 193, 0.29)',
      colorText: '#edf2ff',
      optionSelectedBg: 'rgba(127, 157, 255, 0.14)',
      borderRadius: 11,
      colorBgElevated: '#0e1729',
    },
    Modal: {
      modalContentBg: '#0f1829',
      modalHeaderBg: 'transparent',
      modalHeadingColor: '#edf2ff',
      modalCloseColor: '#8ea1bf',
      modalFooterBg: 'transparent',
      borderRadiusLG: 20,
    },
    Drawer: {
      colorBgElevated: '#0d1526',
    },
    Table: {
      colorBgContainer: 'transparent',
      colorBorder: 'rgba(132, 154, 196, 0.12)',
      headerBg: 'rgba(255, 255, 255, 0.02)',
      headerColor: '#8fa1c0',
      headerSplitColor: 'transparent',
      headerSortActiveBg: 'rgba(255, 255, 255, 0.04)',
      headerSortHoverBg: 'rgba(255, 255, 255, 0.06)',
      borderColor: 'rgba(132, 154, 196, 0.10)',
      rowHoverBg: 'rgba(127, 157, 255, 0.06)',
      footerBg: 'transparent',
    },
    Tabs: {
      itemColor: '#95a3bc',
      itemHoverColor: '#cdd9f2',
      itemSelectedColor: '#e8efff',
      inkBarColor: '#8faaff',
      titleFontSize: 13,
    },
    Pagination: {
      itemActiveBg: 'rgba(127, 157, 255, 0.16)',
      colorPrimary: '#7f9dff',
    },
    Switch: {
      colorPrimary: '#7f9dff',
    },
    Checkbox: {
      colorPrimary: '#7f9dff',
    },
    Radio: {
      colorPrimary: '#7f9dff',
    },
    Message: {
      contentBg: '#111c30',
      colorText: '#edf2ff',
    },
    Notification: {
      notificationBg: '#111c30',
    },
    Popover: {
      colorBgElevated: '#0e1729',
    },
    Tooltip: {
      colorBgSpotlight: '#17233c',
      colorTextLightSolid: '#edf2ff',
    },
    Empty: {
      colorTextDescription: '#7d8dab',
    },
  },
} as ThemeConfig

export default theme