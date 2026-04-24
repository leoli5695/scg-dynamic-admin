import { useState, useEffect, useMemo, lazy, Suspense, useCallback } from "react";
import {
  Layout,
  Menu,
  Avatar,
  Dropdown,
  Space,
  message,
  Tooltip,
  ConfigProvider,
  Spin,
} from "antd";
import type { MenuProps } from "antd";
import zhCN from "antd/locale/zh_CN";
import enUS from "antd/locale/en_US";
import customTheme from "./theme";
import GatewayLogo from "./components/GatewayLogo";
import {
  ClusterOutlined,
  CloudServerOutlined,
  UserOutlined,
  LogoutOutlined,
  MenuFoldOutlined,
  MenuUnfoldOutlined,
} from "@ant-design/icons";
import { Routes, Route, useNavigate, useLocation } from "react-router-dom";

// Lazy load page components for better performance
const KubernetesPage = lazy(() => import("./pages/KubernetesPage"));
const InstancesPage = lazy(() => import("./pages/InstancesPage"));
const InstanceCreatePage = lazy(() => import("./pages/InstanceCreatePage"));
const InstanceDetailPage = lazy(() => import("./pages/InstanceDetailPage"));
const LoginPage = lazy(() => import("./pages/LoginPage"));
const StressTestSharePage = lazy(() => import("./pages/StressTestSharePage"));

// Loading fallback component
const PageLoadingFallback = () => (
  <div style={{
    display: 'flex',
    justifyContent: 'center',
    alignItems: 'center',
    height: '100%',
    minHeight: '400px',
  }}>
    <Spin size="large" />
  </div>
);
import LanguageSwitcher from "./components/LanguageSwitcher";
import { useTranslation } from "react-i18next";
import i18n from "./i18n";
import "./App.css";
import "./App.premium.css";

const { Header, Sider, Content } = Layout;

type MenuItem = Required<MenuProps>["items"][number];

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
  const [current, setCurrent] = useState("instances");
  const [collapsed, setCollapsed] = useState(false);
  const [isLoggedIn, setIsLoggedIn] = useState(false);
  const [user, setUser] = useState<{
    username: string;
    nickname: string;
    role: string;
  } | null>(null);
  const [userMenuOpen, setUserMenuOpen] = useState(false);
  const navigate = useNavigate();
  const location = useLocation();
  const { t } = useTranslation();

  // Menu items with i18n - new structure with Instance Management and Cluster Management
  const menuItems: MenuItem[] = useMemo(
    () => [
      getItem(t("menu.instances"), "instances", <CloudServerOutlined />),
      getItem(t("menu.kubernetes"), "kubernetes", <ClusterOutlined />),
    ],
    [t],
  );

  // Check login status on mount
  useEffect(() => {
    const token = localStorage.getItem("token");
    const username = localStorage.getItem("username");
    const nickname = localStorage.getItem("nickname");
    const role = localStorage.getItem("role");

    if (token && username) {
      setIsLoggedIn(true);
      setUser({
        username,
        nickname: nickname || username,
        role: role || "USER",
      });
    }
  }, []);

  // Update current menu selection based on URL
  useEffect(() => {
    const path = location.pathname;
    if (path.startsWith("/instances/")) {
      setCurrent("instances");
    } else if (path === "/instances") {
      setCurrent("instances");
    } else if (path === "/kubernetes") {
      setCurrent("kubernetes");
    }
  }, [location.pathname]);

  const onClick: MenuProps["onClick"] = (e) => {
    setCurrent(e.key);
    navigate(`/${e.key}`);
  };

  const handleLogout = useCallback(() => {
    localStorage.removeItem("token");
    localStorage.removeItem("username");
    localStorage.removeItem("nickname");
    localStorage.removeItem("role");
    setIsLoggedIn(false);
    setUser(null);
    message.success("Logout successful");
    window.location.href = "/login";
  }, []);

  // User dropdown menu items
  const userMenuItems: MenuItem[] = useMemo(
    () => [
      {
        key: "logout",
        icon: <LogoutOutlined />,
        label: t("common.logout"),
        onClick: () => {
          setUserMenuOpen(false);
          handleLogout();
        },
      },
    ],
    [t, handleLogout],
  );

  // If not logged in, show login page only
  if (!isLoggedIn) {
    return (
      <Suspense fallback={<PageLoadingFallback />}>
        <LoginPage />
      </Suspense>
    );
  }

  // Check if we're in instance detail page
  const isInstanceDetail = location.pathname.startsWith("/instances/") && location.pathname !== "/instances";

  // Check if we're in share page (no login required)
  const isSharePage = location.pathname.startsWith("/share/");

  // Share page - no login required, no sidebar
  if (isSharePage) {
    const shareId = location.pathname.replace("/share/", "");
    return (
      <ConfigProvider
        theme={customTheme}
        locale={i18n.language?.startsWith("zh") ? zhCN : enUS}
      >
        <Suspense fallback={<PageLoadingFallback />}>
          <StressTestSharePage shareId={shareId} />
        </Suspense>
      </ConfigProvider>
    );
  }

  return (
    <ConfigProvider
      theme={customTheme}
      locale={i18n.language?.startsWith("zh") ? zhCN : enUS}
      getPopupContainer={(triggerNode) =>
        triggerNode?.parentNode instanceof HTMLElement
          ? triggerNode.parentNode
          : document.body
      }
    >
      <Layout style={{ minHeight: "100vh" }}>
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
            overflow: "auto",
            height: "100vh",
            position: "fixed",
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
                title={collapsed ? t("common.expand") : t("common.collapse")}
                placement="right"
              >
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
        <Layout
          style={{
            marginLeft: collapsed ? 80 : 220,
            transition: "margin-left 0.2s",
          }}
        >
          {/* Show header only for non-instance-detail pages */}
          {!isInstanceDetail && (
            <Header className="main-header">
              <div className="header-left">
                <h1 className="header-title">{t("app.console_title")}</h1>
                <span className="header-subtitle">{t("app.subtitle")}</span>
              </div>
              <div className="header-right">
                <LanguageSwitcher />
                {isLoggedIn && user && (
                  <Dropdown
                    menu={{ items: userMenuItems }}
                    placement="bottomRight"
                    arrow
                    trigger={["click"]}
                    open={userMenuOpen}
                    onOpenChange={setUserMenuOpen}
                  >
                    <Space className="user-info">
                      <Avatar
                        style={{ backgroundColor: "#165DFF" }}
                        icon={<UserOutlined />}
                        size="small"
                      />
                      <span className="username">
                        {user.nickname || user.username}
                      </span>
                    </Space>
                  </Dropdown>
                )}
              </div>
            </Header>
          )}
          <Content className="main-content" style={{ overflow: "auto" }}>
            <Suspense fallback={<PageLoadingFallback />}>
              <Routes>
                <Route path="/" element={<InstancesPage />} />
                <Route path="/instances" element={<InstancesPage />} />
                <Route path="/instances/create" element={<InstanceCreatePage />} />
                <Route path="/instances/:instanceId" element={<InstanceDetailPage />}>
                  <Route index element={null} />
                  <Route path="services" element={null} />
                  <Route path="routes" element={null} />
                  <Route path="strategies" element={null} />
                  <Route path="authentication" element={null} />
                  <Route path="certificates" element={null} />
                  <Route path="trace" element={null} />
                  <Route path="monitor" element={null} />
                  <Route path="alerts" element={null} />
                  <Route path="access_log" element={null} />
                  <Route path="audit_logs" element={null} />
                  <Route path="diagnostic" element={null} />
                  <Route path="topology" element={null} />
                  <Route path="filter_chain" element={null} />
                  <Route path="request_replay" element={null} />
                  <Route path="copilot" element={null} />
                  <Route path="stress_test" element={null} />
                </Route>
                <Route path="/kubernetes" element={<KubernetesPage />} />
              </Routes>
            </Suspense>
          </Content>

          {/* Page Footer - only show for non-instance-detail pages */}
          {!isInstanceDetail && (
            <footer className="page-footer">
              <p className="footer-text">
                © {new Date().getFullYear()} leoli. All rights reserved.
              </p>
            </footer>
          )}
        </Layout>
      </Layout>
    </ConfigProvider>
  );
};

export default App;