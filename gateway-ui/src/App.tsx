/**
 * App root — ConfigProvider + AuthProvider + declarative route table.
 * Authenticated pages live under AppShell; the instance console uses its own
 * layout with URL-driven tab sub-routes.
 */
import React, { lazy, Suspense } from 'react';
import {
  Navigate, Route, Routes,
} from 'react-router-dom';
import { ConfigProvider, Spin } from 'antd';
import { App as AntdApp } from 'antd';
import zhCN from 'antd/locale/zh_CN';
import enUS from 'antd/locale/en_US';
import { useTranslation } from 'react-i18next';
import customTheme from './theme';
import './i18n';
import { AuthProvider, useAuth } from './features/auth/AuthContext';
import AppShell from './features/layout/AppShell';
import OverviewTab from './features/instance-detail/tabs/OverviewTab';
import EventsTab from './features/instance-detail/tabs/EventsTab';
import ResourcesTab from './features/instance-detail/tabs/ResourcesTab';
import {
  AccessLogTab, AlertsTab, AuditLogsTab, AuthenticationTab, CertificatesTab,
  CopilotTab, DiagnosticTab, FilterChainTab, MiddlewareTab, MonitorTab,
  PluginBindingsTab, PluginsTab, RequestReplayTab, RoutesTab, ServicesTab,
  StrategiesTab, StressTestTab, TokenRevocationTab, TopologyTab, TraceTab, TokenUsageTab,
} from './features/instance-detail/tabs/legacyTabs';
import './App.css';
import './App.premium.css';
import './styles/console-shell.css';
import { AntdStaticBridge } from './utils/antdStatic';

const RouteDebugPage = lazy(() => import('./features/routes/debug'));
const RequestJourneyPage = lazy(() => import('./features/request-journey'));
const RequestQueryPage = lazy(() => import('./features/request-journey/RequestQueryPage'));
const LoginPage = lazy(() => import('./features/auth/LoginPage'));
const InstancesListPage = lazy(() => import('./features/instances/InstancesListPage'));
const InstanceCreatePage = lazy(() => import('./features/instances/InstanceCreatePage'));
const TokenUsagePage = lazy(() => import('./features/token-usage/TokenUsagePage'));
const InstanceDetailLayout = lazy(() => import('./features/instance-detail/InstanceDetailLayout'));
// Legacy top-level pages (rewritten in later phases)
const KubernetesPage = lazy(() => import('./features/kubernetes'));
const LicensePage = lazy(() => import('./features/license'));
const StressTestSharePage = lazy(() => import('./features/stress-test/SharePage'));
const MiddlewareDetailPage = lazy(() => import('./features/middleware-detail'));

const PageLoadingFallback: React.FC = () => (
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
);

/** Login route — already-authenticated users go straight to the console. */
const LoginRoute: React.FC = () => {
  const { isLoggedIn } = useAuth();
  if (isLoggedIn) {
    return <Navigate to="/" replace />;
  }
  return (
    <Suspense fallback={<PageLoadingFallback />}>
      <LoginPage />
    </Suspense>
  );
};

/** Guard for authenticated routes. */
const RequireAuth: React.FC<{ children: React.ReactElement }> = ({ children }) => {
  const { isLoggedIn } = useAuth();
  if (!isLoggedIn) {
    return <Navigate to="/login" replace />;
  }
  return children;
};

/** Public stress-test share page (no auth, no shell). */
const SharePageRoute: React.FC = () => (
  <Suspense fallback={<PageLoadingFallback />}>
    <StressTestSharePage />
  </Suspense>
);

const AppRoutes: React.FC = () => (
  <Suspense fallback={<PageLoadingFallback />}>
    <Routes>
      <Route path="/login" element={<LoginRoute />} />
      <Route path="/share/:shareId" element={<SharePageRoute />} />
      <Route element={<RequireAuth><AppShell /></RequireAuth>}>
        <Route path="/" element={<Navigate to="/instances" replace />} />
        <Route path="/instances" element={<InstancesListPage />} />
        <Route path="/instances/create" element={<InstanceCreatePage />} />
        <Route path="/token-usage" element={<TokenUsagePage />} />
        <Route path="/instances/:instanceId" element={<InstanceDetailLayout />}>
          <Route index element={<OverviewTab />} />
          <Route path="services" element={<ServicesTab />} />
          <Route path="routes" element={<RoutesTab />} />
          <Route path="routes/:routeId/debug" element={<RouteDebugPage />} />
          <Route path="trace/:traceId" element={<RequestJourneyPage />} />
          <Route path="requests" element={<RequestQueryPage />} />
          <Route path="token_usage" element={<TokenUsageTab />} />
          <Route path="plugins" element={<PluginsTab />} />
          <Route path="plugin_bindings" element={<PluginBindingsTab />} />
          <Route path="strategies" element={<StrategiesTab />} />
          <Route path="authentication" element={<AuthenticationTab />} />
          <Route path="token_revocation" element={<TokenRevocationTab />} />
          <Route path="certificates" element={<CertificatesTab />} />
          <Route path="trace" element={<TraceTab />} />
          <Route path="monitor" element={<MonitorTab />} />
          <Route path="alerts" element={<AlertsTab />} />
          <Route path="access_log" element={<AccessLogTab />} />
          <Route path="audit_logs" element={<AuditLogsTab />} />
          <Route path="diagnostic" element={<DiagnosticTab />} />
          <Route path="topology" element={<TopologyTab />} />
          <Route path="filter_chain" element={<FilterChainTab />} />
          <Route path="request_replay" element={<RequestReplayTab />} />
          <Route path="copilot" element={<CopilotTab />} />
          <Route path="stress_test" element={<StressTestTab />} />
          <Route path="events" element={<EventsTab />} />
          <Route path="resources" element={<ResourcesTab />} />
          <Route path="middleware" element={<MiddlewareTab />} />
        </Route>
        <Route path="/kubernetes" element={<KubernetesPage />} />
        <Route path="/license" element={<LicensePage />} />
        <Route path="/middleware-detail" element={<MiddlewareDetailPage />} />
      </Route>
      <Route path="*" element={<Navigate to="/" replace />} />
    </Routes>
  </Suspense>
);

const App: React.FC = () => {
  const { i18n } = useTranslation();

  return (
    <ConfigProvider
      theme={customTheme}
      locale={i18n.language?.startsWith('zh') ? zhCN : enUS}
      getPopupContainer={(triggerNode) => (
        triggerNode?.parentNode instanceof HTMLElement
          ? triggerNode.parentNode
          : document.body
      )}
    >
      <AuthProvider>
        <AntdApp>
          <AntdStaticBridge />
          <AppRoutes />
        </AntdApp>
      </AuthProvider>
    </ConfigProvider>
  );
};

export default App;
