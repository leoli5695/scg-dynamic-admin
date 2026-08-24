/**
 * Legacy tab adapters — bridge the existing src/pages components into the
 * new route-based console. Each adapter pulls the instance from context and
 * passes the UUID, mirroring the old InstanceDetailPage switch cases.
 * Each wrapper disappears when its page is rewritten into a feature.
 */
import React, { lazy } from 'react';
import ErrorBoundary from '../../layout/components/ErrorBoundary';

const ServicesPage = lazy(() => import('../../services'));
const RoutesPage = lazy(() => import('../../routes'));
const PluginsPage = lazy(() => import('../../plugins'));
const RoutePluginBindingsPage = lazy(() => import('../../plugin-bindings'));
const StrategiesPage = lazy(() => import('../../strategies'));
const AuthPoliciesPage = lazy(() => import('../../auth-policies'));
const CertificatePage = lazy(() => import('../../certificates'));
const TracePage = lazy(() => import('../../trace'));
const MonitorPage = lazy(() => import('../../monitor'));
const AlertPage = lazy(() => import('../../alert'));
const AccessLogPage = lazy(() => import('../../access-log'));
const AuditLogsPage = lazy(() => import('../../audit-logs'));
const DiagnosticPage = lazy(() => import('../../diagnostic'));
const TrafficTopologyPage = lazy(() => import('../../topology'));
const FilterChainPage = lazy(() => import('../../filter-chain'));
const RequestReplayPage = lazy(() => import('../../request-replay'));
const AiCopilotPage = lazy(() => import('../../copilot'));
const StressTestPage = lazy(() => import('../../stress-test'));
const ServiceMiddlewarePage = lazy(() => import('../../middleware'));

// Services page rewritten as a feature; reads instanceId from the route.
export const ServicesTab: React.FC = () => (
  <ErrorBoundary><ServicesPage /></ErrorBoundary>
);

// Routes page rewritten as a feature; reads instanceId from the route.
export const RoutesTab: React.FC = () => (
  <ErrorBoundary><RoutesPage /></ErrorBoundary>
);

// Plugins page rewritten as a feature; reads instanceId from the route.
export const PluginsTab: React.FC = () => (
  <ErrorBoundary><PluginsPage /></ErrorBoundary>
);

// Plugin bindings page rewritten as a feature; reads instanceId from the route.
export const PluginBindingsTab: React.FC = () => (
  <ErrorBoundary><RoutePluginBindingsPage /></ErrorBoundary>
);

// Strategies page rewritten as a feature; reads instanceId from the route.
export const StrategiesTab: React.FC = () => (
  <ErrorBoundary><StrategiesPage /></ErrorBoundary>
);

// Auth policies page rewritten as a feature; reads instanceId from the route.
export const AuthenticationTab: React.FC = () => (
  <ErrorBoundary><AuthPoliciesPage /></ErrorBoundary>
);

// Certificates page rewritten as a feature; reads instanceId from the route.
export const CertificatesTab: React.FC = () => (
  <ErrorBoundary><CertificatePage /></ErrorBoundary>
);

// Trace page rewritten as a feature; reads the instance from context.
export const TraceTab: React.FC = () => (
  <ErrorBoundary><TracePage /></ErrorBoundary>
);

// Monitor page rewritten as a feature; reads the instance from context.
export const MonitorTab: React.FC = () => (
  <ErrorBoundary><MonitorPage /></ErrorBoundary>
);

// Alert page rewritten as a feature; reads the instance from context.
export const AlertsTab: React.FC = () => (
  <ErrorBoundary><AlertPage /></ErrorBoundary>
);

// Access log page rewritten as a feature; reads the instance from context.
export const AccessLogTab: React.FC = () => (
  <ErrorBoundary><AccessLogPage /></ErrorBoundary>
);

// Audit logs page rewritten as a feature; reads the instance from context.
export const AuditLogsTab: React.FC = () => (
  <ErrorBoundary><AuditLogsPage /></ErrorBoundary>
);

// Diagnostic page rewritten as a feature; reads the instance from context.
export const DiagnosticTab: React.FC = () => (
  <ErrorBoundary><DiagnosticPage /></ErrorBoundary>
);

// Topology page rewritten as a feature; reads the instance from context.
export const TopologyTab: React.FC = () => (
  <ErrorBoundary><TrafficTopologyPage /></ErrorBoundary>
);

// Filter chain page rewritten as a feature; reads the instance from context.
export const FilterChainTab: React.FC = () => (
  <ErrorBoundary><FilterChainPage /></ErrorBoundary>
);

// Request replay page rewritten as a feature; reads the instance from context.
export const RequestReplayTab: React.FC = () => (
  <ErrorBoundary><RequestReplayPage /></ErrorBoundary>
);

// AI Copilot page rewritten as a feature; reads the instance from context.
export const CopilotTab: React.FC = () => (
  <ErrorBoundary><AiCopilotPage /></ErrorBoundary>
);

// Stress test page rewritten as a feature; reads the instance from context.
export const StressTestTab: React.FC = () => (
  <ErrorBoundary><StressTestPage /></ErrorBoundary>
);

// Middleware page rewritten as a feature; middleware data is global
// (backend GET /services ignores instanceId), so no instance props needed.
export const MiddlewareTab: React.FC = () => (
  <ErrorBoundary><ServiceMiddlewarePage /></ErrorBoundary>
);
