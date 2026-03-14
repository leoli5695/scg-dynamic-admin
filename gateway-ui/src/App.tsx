import { useState, useEffect } from 'react';
import { Layout, Menu, theme, Avatar, Dropdown, Space, message } from 'antd';
import type { MenuProps } from 'antd';
import { 
  AppstoreOutlined,      // Services - 应用图标
  SafetyOutlined,        // Strategies - 安全/策略图标  
  DeploymentUnitOutlined, // Routes - 路由/部署图标
  UserOutlined,
  LogoutOutlined
} from '@ant-design/icons';
import { Routes, Route, useNavigate, useLocation } from 'react-router-dom';
import RoutesPage from './pages/RoutesPage';
import ServicesPage from './pages/ServicesPage';
import StrategiesPage from './pages/StrategiesPage';
import LoginPage from './pages/LoginPage';
import LanguageSwitcher from './components/LanguageSwitcher';
import './App.css';

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

const menuItems: MenuItem[] = [
  getItem('Services', 'services', <AppstoreOutlined />),
  getItem('Routes', 'routes', <DeploymentUnitOutlined />),
  getItem('Strategies', 'strategies', <SafetyOutlined />),
];

const App: React.FC = () => {
  const [current, setCurrent] = useState('services');
  const [isLoggedIn, setIsLoggedIn] = useState(false);
  const [user, setUser] = useState<{ username: string; nickname: string; role: string } | null>(null);
  const navigate = useNavigate();
  const location = useLocation();
  const {
    token: { colorBgContainer },
  } = theme.useToken();

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
    navigate('/login');
  };

  const renderContent = () => {
    switch (current) {
      case 'services':
        return <ServicesPage />;
      case 'routes':
        return <RoutesPage />;
      case 'strategies':
        return <StrategiesPage />;
      default:
        return <ServicesPage />;
    }
  };

  // If not logged in and not on login page, redirect to login
  if (!isLoggedIn && location.pathname !== '/login') {
    return <LoginPage />;
  }

  // If logged in and on login page, redirect to main
  if (isLoggedIn && location.pathname === '/login') {
    navigate('/');
    return null;
  }

  // User dropdown menu items
  const userMenuItems: MenuItem[] = [
    {
      key: 'logout',
      icon: <LogoutOutlined />,
      label: 'Logout',
      onClick: handleLogout
    }
  ];

  return (
    <Layout style={{ minHeight: '100vh' }}>
      {/* Left Sidebar */}
      <Sider width={200} theme="dark" className="sidebar">
        <div className="logo">
          Gateway Admin
        </div>
        <Menu
          theme="dark"
          mode="inline"
          selectedKeys={[current]}
          items={menuItems}
          onClick={onClick}
          style={{ borderRight: 'none', fontSize: '14px' }}
        />
      </Sider>
      
      {/* Main Content Area */}
      <Layout>
        <Header style={{ 
          background: colorBgContainer,
          display: 'flex', 
          alignItems: 'center', 
          justifyContent: 'space-between',
          padding: '0 24px',
          borderBottom: '1px solid #f0f0f0'
        }}>
          <div style={{ fontSize: '16px', fontWeight: 500 }}>
            API Gateway Management Console
          </div>
          <div style={{ display: 'flex', alignItems: 'center', gap: '16px' }}>
            <LanguageSwitcher />
            {isLoggedIn && user && (
              <Dropdown menu={{ items: userMenuItems }} placement="bottomRight" arrow>
                <Space style={{ cursor: 'pointer', padding: '8px' }}>
                  <Avatar style={{ backgroundColor: '#667eea' }} icon={<UserOutlined />} />
                  <span>{user.nickname || user.username}</span>
                </Space>
              </Dropdown>
            )}
          </div>
        </Header>
        <Content style={{ 
          padding: '24px', 
          background: colorBgContainer,
          overflow: 'auto'
        }}>
          <Routes>
            <Route path="/login" element={<LoginPage />} />
            <Route path="/services" element={<ServicesPage />} />
            <Route path="/routes" element={<RoutesPage />} />
            <Route path="/strategies" element={<StrategiesPage />} />
            <Route path="/" element={<ServicesPage />} />
          </Routes>
        </Content>
      </Layout>
    </Layout>
  );
};

export default App;
