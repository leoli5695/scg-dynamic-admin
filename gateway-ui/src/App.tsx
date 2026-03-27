import { useState, useEffect, useMemo } from 'react';
import { Layout, Menu, theme, Avatar, Dropdown, Space, message, Tooltip } from 'antd';
import type { MenuProps } from 'antd';
import {
  AppstoreOutlined,      // Services - 应用图标
  SafetyOutlined,        // Strategies - 安全/策略图标
  DeploymentUnitOutlined, // Routes - 路由/部署图标
  DashboardOutlined,     // Monitor - 监控图标
  BellOutlined,          // Alert - 告警图标
  SafetyCertificateOutlined, // Certificate - 证书图标
  ApiOutlined,           // Trace - 追踪图标
  UserOutlined,
  LogoutOutlined,
  GatewayOutlined,
  MenuFoldOutlined,
  MenuUnfoldOutlined
} from '@ant-design/icons';
import { Routes, Route, useNavigate, useLocation } from 'react-router-dom';
import RoutesPage from './pages/RoutesPage';
import ServicesPage from './pages/ServicesPage';
import StrategiesPage from './pages/StrategiesPage';
import MonitorPage from './pages/MonitorPage';
import AlertPage from './pages/AlertPage';
import CertificatePage from './pages/CertificatePage';
import TracePage from './pages/TracePage';
import LoginPage from './pages/LoginPage';
import LanguageSwitcher from './components/LanguageSwitcher';
import { useTranslation } from 'react-i18next';
import './App.css';
import './App.premium.css';

const { Header, Sider, Content } = Layout;

type MenuItem = Required<MenuProps>['items'][number];

function getItem(
  label: React.ReactNode,
  key: React.Key,
  icon?: React.ReactNode,
  children?: MenuItem[],
): MenuItem {
  return {
    key,
    icon,
    children,
    label,
  } as MenuItem;
}

const App: React.FC = () => {
  const [current, setCurrent] = useState('services');
  const [collapsed, setCollapsed] = useState(false);
  const [isLoggedIn, setIsLoggedIn] = useState(false);
  const [user, setUser] = useState<{ username: string; nickname: string; role: string } | null>(null);
  const navigate = useNavigate();
  const location = useLocation();
  const { t } = useTranslation();
  const {
    token: { colorBgContainer },
  } = theme.useToken();

  // Menu items with i18n - memoized to respond to language changes
  const menuItems: MenuItem[] = useMemo(() => [
    getItem(t('menu.services'), 'services', <AppstoreOutlined />),
    getItem(t('menu.routes'), 'routes', <DeploymentUnitOutlined />),
    getItem(t('menu.strategies'), 'strategies', <SafetyOutlined />),
    getItem(t('menu.certificates'), 'certificates', <SafetyCertificateOutlined />),
    getItem(t('menu.trace'), 'trace', <ApiOutlined />),
    getItem(t('menu.monitor'), 'monitor', <DashboardOutlined />),
    getItem(t('menu.alerts'), 'alerts', <BellOutlined />),
  ], [t]);

  // Check login status on mount
  useEffect(() => {
    const token = localStorage.getItem('token');
    const username = localStorage.getItem('username');
    const nickname = localStorage.getItem('nickname');
    const role = localStorage.getItem('role');
    
    if (token && username) {
      setIsLoggedIn(true);
      setUser({ username, nickname: nickname || username, role: role || 'USER' });
    }
  }, []);

  const onClick: MenuProps['onClick'] = (e) => {
    setCurrent(e.key);
    navigate(`/${e.key}`);
  };

  const handleLogout = () => {
    localStorage.removeItem('token');
    localStorage.removeItem('username');
    localStorage.removeItem('nickname');
    localStorage.removeItem('role');
    setIsLoggedIn(false);
    setUser(null);
    message.success('Logout successful');
    // Force full page reload to reset router state
    window.location.href = '/login';
  };

  const renderContent = () => {
    switch (current) {
      case 'services':
        return <ServicesPage />;
      case 'routes':
        return <RoutesPage />;
      case 'strategies':
        return <StrategiesPage />;
      case 'certificates':
        return <CertificatePage />;
      case 'trace':
        return <TracePage />;
      case 'monitor':
        return <MonitorPage />;
      case 'alerts':
        return <AlertPage />;
      default:
        return <ServicesPage />;
    }
  };

  // User dropdown menu items
  const userMenuItems: MenuItem[] = useMemo(() => [
    {
      key: 'logout',
      icon: <LogoutOutlined />,
      label: t('common.logout'),
      onClick: handleLogout
    }
  ], [t]);

  // If not logged in, show login page only
  if (!isLoggedIn) {
    return <LoginPage />;
  }

  return (
    <Layout style={{ minHeight: '100vh' }}>
      {/* Left Sidebar - Fixed position */}
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
            <GatewayOutlined className="logo-icon" />
            {!collapsed && <span className="logo-text">API Gateway</span>}
          </div>
          <div className="sidebar-collapse-btn" onClick={() => setCollapsed(!collapsed)}>
            <Tooltip title={collapsed ? t('common.expand') : t('common.collapse')} placement="right">
              {collapsed ? <MenuUnfoldOutlined /> : <MenuFoldOutlined />}
            </Tooltip>
          </div>
        </div>
        <Menu
          theme="dark"
          mode="inline"
          selectedKeys={[current]}
          items={menuItems}
          onClick={onClick}
        />
      </Sider>
      
      {/* Main Content Area */}
      <Layout style={{ marginLeft: collapsed ? 80 : 220, transition: 'margin-left 0.2s' }}>
        <Header className="main-header">
          <div className="header-left">
            <h1 className="header-title">{t('app.console_title')}</h1>
            <span className="header-subtitle">{t('app.subtitle')}</span>
          </div>
          <div className="header-right">
            <LanguageSwitcher />
            {isLoggedIn && user && (
              <Dropdown menu={{ items: userMenuItems }} placement="bottomRight" arrow trigger={['click']}>
                <Space className="user-info">
                  <Avatar style={{ backgroundColor: '#165DFF' }} icon={<UserOutlined />} size="small" />
                  <span className="username">{user.nickname || user.username}</span>
                </Space>
              </Dropdown>
            )}
          </div>
        </Header>
        <Content className="main-content" style={{ overflow: 'auto' }}>
          {renderContent()}
        </Content>
        
        {/* Page Footer */}
        <footer className="page-footer">
          <p className="footer-text">© {new Date().getFullYear()} leoli. All rights reserved.</p>
        </footer>
      </Layout>
    </Layout>
  );
};

export default App;
