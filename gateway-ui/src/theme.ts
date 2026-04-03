import type { ThemeConfig } from 'antd';

const theme: ThemeConfig = {
  token: {
    colorBgBase: '#0a0f1c',           // 整体背景 - 深邃海军蓝黑
    colorBgContainer: '#111827',      // 卡片背景 - 稍亮的深灰
    colorBgElevated: '#1f2937',       // 悬浮/弹窗背景
    colorBorder: 'rgba(148, 163, 184, 0.12)',   // 柔和边框
    colorText: '#f1f5f9',
    colorTextSecondary: '#94a3b8',
    colorTextTertiary: '#64748b',
    borderRadius: 12,
    boxShadow: '0 10px 15px -3px rgba(0, 0, 0, 0.3)',
    colorPrimary: '#3b82f6',
    colorSuccess: '#10b981',
    colorWarning: '#f59e0b',
    colorError: '#ef4444',
    colorBgContainerDisabled: 'rgba(255,255,255,0.05)',
  },
  components: {
    Card: {
      colorBgContainer: '#111827',
      borderRadius: 12,
      boxShadow: '0 4px 20px rgba(0, 0, 0, 0.25)',
    },
    Layout: {
      colorBgHeader: '#0a0f1c',
      colorBgBody: '#0a0f1c',
    },
    Menu: {
      colorBgContainer: '#0a0f1c',
    },
    Button: {
      borderRadius: 8,
      colorPrimary: '#3b82f6',
    },
    Tag: {
      colorBgContainer: 'rgba(255,255,255,0.08)',
    },
    Alert: {
      colorBgContainer: 'rgba(245, 158, 11, 0.1)',
    },
    Statistic: {
      colorText: '#f1f5f9',
      colorTextSecondary: '#94a3b8',
    },
    Input: {
      colorBgContainer: '#111827',
      colorBorder: 'rgba(148, 163, 184, 0.12)',
    },
    Select: {
      colorBgContainer: '#111827',
      colorBorder: 'rgba(148, 163, 184, 0.12)',
    },
    Modal: {
      colorBgContainer: '#111827',
      colorBgElevated: '#1f2937',
    },
    Drawer: {
      colorBgContainer: '#111827',
    },
    Table: {
      colorBgContainer: '#111827',
      colorBorder: 'rgba(148, 163, 184, 0.12)',
    },
  },
};

export default theme;
