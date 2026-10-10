/**
 * AppShell — main authenticated layout: sidebar + header + content + footer.
 * The instance-detail console renders its own header (no main header there).
 */
import React, { useMemo, useState } from 'react';
import {
  Layout, Menu, Spin, Tooltip
} from 'antd';
import type { MenuProps } from 'antd';
import {
  CloudServerOutlined, ClusterOutlined, KeyOutlined, FundOutlined,
  MenuFoldOutlined, MenuUnfoldOutlined,
} from '@ant-design/icons';
import { Outlet, useLocation, useNavigate } from 'react-router-dom';
import { useTranslation } from 'react-i18next';
import GatewayLogo from './components/GatewayLogo';
import LanguageSwitcher from './components/LanguageSwitcher';
import UserMenu from './UserMenu';

const { Header, Sider, Content } = Layout;

type MenuItem = Required<MenuProps>['items'][number];

const getItem = (
  label: React.ReactNode,
  key: React.Key,
  icon?: React.ReactNode,
): MenuItem => ({ key, icon, label }) as MenuItem;

/** Resolve the selected sidebar key from the current path. */
const selectedKeyFromPath = (pathname: string): string => {
  if (pathname.startsWith('/kubernetes')) return 'kubernetes';
  if (pathname.startsWith('/license')) return 'license';
  if (pathname.startsWith('/token-usage')) return 'token-usage';
  return 'instances';
};

const AppShell: React.FC = () => {
  const [collapsed, setCollapsed] = useState(false);
  const navigate = useNavigate();
  const location = useLocation();
  const { t } = useTranslation();

  const menuItems: MenuItem[] = useMemo(() => [
    getItem(t('menu.instances'), 'instances', <CloudServerOutlined />),
    getItem(t('menu.token_usage'), 'token-usage', <FundOutlined />),
    getItem(t('menu.kubernetes'), 'kubernetes', <ClusterOutlined />),
    getItem('License', 'license', <KeyOutlined />),
  ], [t]);

  const selectedKey = selectedKeyFromPath(location.pathname);
  // The instance console owns its own header; hide the main one there.
  const isInstanceDetail = /^\/instances\/[^/]+\/?/.test(location.pathname)
    && location.pathname !== '/instances/create';

  const handleMenuClick: MenuProps['onClick'] = (e) => {
    navigate(`/${e.key}`);
  };

  return (
    <Layout style={{ minHeight: '100vh' }}>
      <Sider
        width={220}
        theme="dark"
        className="sidebar-premium"
        collapsible
        collapsed={collapsed}
        onCollapse={setCollapsed}
        trigger={null}
        style={{
          overflow: 'auto',
          height: '100vh',
          position: 'fixed',
          left: 0,
          top: 0,
          bottom: 0,
        }}
      >
        <div className="sidebar-header">
          <div className="sidebar-logo">
            <GatewayLogo className="logo-icon" style={{ width: 28, height: 28 }} />
            {!collapsed && <span className="logo-text">API Gateway</span>}
          </div>
          <div
            className="sidebar-collapse-btn"
            onClick={() => setCollapsed(!collapsed)}
          >
            <Tooltip
              title={collapsed ? t('common.expand') : t('common.collapse')}
              placement="right"
            >
              {collapsed ? <MenuUnfoldOutlined /> : <MenuFoldOutlined />}
            </Tooltip>
          </div>
        </div>
        <Menu
          theme="dark"
          mode="inline"
          selectedKeys={[selectedKey]}
          items={menuItems}
          onClick={handleMenuClick}
        />
      </Sider>

      <Layout
        style={{
          marginLeft: collapsed ? 80 : 220,
          transition: 'margin-left 0.2s',
        }}
      >
        {!isInstanceDetail && (
          <Header className="main-header">
            <div className="header-left">
              <h1 className="header-title">{t('app.console_title')}</h1>
              <span className="header-subtitle">{t('app.subtitle')}</span>
            </div>
            <div className="header-right">
              <LanguageSwitcher />
              <UserMenu />
            </div>
          </Header>
        )}

        <Content className="main-content" style={{ overflow: 'auto' }}>
          <React.Suspense
            fallback={(
              <div style={{
                display: 'flex',
                justifyContent: 'center',
                alignItems: 'center',
                height: '100%',
                minHeight: 400,
              }}
              >
                <Spin size="large" />
              </div>
            )}
          >
            <Outlet />
          </React.Suspense>
        </Content>

        {!isInstanceDetail && (
          <footer className="page-footer">
            <p className="footer-text">
              ©
              {' '}
              {new Date().getFullYear()}
              {' '}
              leoli. All rights reserved.
            </p>
          </footer>
        )}
      </Layout>
    </Layout>
  );
};

export default AppShell;
