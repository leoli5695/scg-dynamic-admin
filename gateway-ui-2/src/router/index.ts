import { createRouter, createWebHistory, type RouteRecordRaw } from 'vue-router'
import { useAuthStore } from '@/stores/auth'

// Lazy-loaded so the login page and shell stay in small initial chunks.
const LoginPage = () => import('@/features/auth/LoginPage.vue')
const AppShell = () => import('@/features/layout/AppShell.vue')
const InstancesListPage = () => import('@/features/instances/InstancesListPage.vue')
const InstanceCreatePage = () => import('@/features/instances/InstanceCreatePage.vue')
const InstanceDetailLayout = () => import('@/features/instance-detail/InstanceDetailLayout.vue')
const OverviewTab = () => import('@/features/instance-detail/tabs/OverviewTab.vue')
const EventsTab = () => import('@/features/instance-detail/tabs/EventsTab.vue')
const ResourcesTab = () => import('@/features/instance-detail/tabs/ResourcesTab.vue')
const ServicesPage = () => import('@/features/services/index.vue')
const RoutesPage = () => import('@/features/routes/index.vue')
const PluginsPage = () => import('@/features/plugins/index.vue')
const CertificatesPage = () => import('@/features/certificates/index.vue')
const StrategiesPage = () => import('@/features/strategies/index.vue')
const TokenUsagePage = () => import('@/features/token-usage/TokenUsagePage.vue')
const KubernetesPage = () => import('@/features/kubernetes/index.vue')
const TracePage = () => import('@/features/trace/TracePage.vue')
const AlertsPage = () => import('@/features/alert/index.vue')
// Temporary stand-in for feature modules not yet ported (replaced in later stages).
const Placeholder = () => import('@/components/PlaceholderPage.vue')

const routes: RouteRecordRaw[] = [
  { path: '/login', name: 'login', component: LoginPage, meta: { public: true } },
  {
    path: '/share/:shareId',
    name: 'share',
    component: Placeholder,
    meta: { public: true, title: 'Stress Test Share (Stage 4)' },
  },
  {
    path: '/',
    component: AppShell,
    children: [
      { path: '', redirect: '/instances' },
      { path: 'instances', component: InstancesListPage },
      { path: 'instances/create', component: InstanceCreatePage },
      { path: 'token-usage', component: TokenUsagePage },
      {
        path: 'instances/:instanceId',
        component: InstanceDetailLayout,
        children: [
          { path: '', component: OverviewTab },
          { path: 'events', component: EventsTab },
          { path: 'resources', component: ResourcesTab },
          // Remaining console tabs land in Stage 1 (services/routes) and later stages.
          { path: 'services', component: ServicesPage },
          { path: 'routes', component: RoutesPage },
          { path: 'plugins', component: PluginsPage },
          { path: 'plugin_bindings', component: () => import('@/features/plugin-bindings/index.vue') },
          { path: 'strategies', component: StrategiesPage },
          { path: 'authentication', component: () => import('@/features/auth-policies/index.vue') },
          { path: 'token_revocation', component: () => import('@/features/token-revocation/index.vue') },
          { path: 'certificates', component: CertificatesPage },
          { path: 'trace', component: TracePage },
          { path: 'monitor', component: () => import('@/features/monitor/MonitorPage.vue') },
          { path: 'alerts', component: AlertsPage },
          { path: 'access_log', component: () => import('@/features/access-log/index.vue') },
          { path: 'audit_logs', component: () => import('@/features/audit-logs/AuditLogsPage.vue') },
          { path: 'diagnostic', component: () => import('@/features/diagnostic/DiagnosticPage.vue') },
          { path: 'topology', component: () => import('@/features/topology/TopologyPage.vue') },
          { path: 'filter_chain', component: () => import('@/features/filter-chain/FilterChainPage.vue') },
          { path: 'request_replay', component: () => import('@/features/request-replay/index.vue') },
          { path: 'copilot', component: Placeholder, meta: { title: 'Copilot (Stage 4)' } },
          { path: 'stress_test', component: Placeholder, meta: { title: 'Stress Test (Stage 4)' } },
          { path: 'middleware', component: Placeholder, meta: { title: 'Middleware (Stage 3)' } },
        ],
      },
      { path: 'kubernetes', component: KubernetesPage },
      { path: 'license', component: () => import('@/features/license/index.vue') },
      { path: 'middleware-detail', component: Placeholder, meta: { title: 'Middleware Detail (Stage 3)' } },
    ],
  },
  { path: '/:pathMatch(.*)*', redirect: '/' },
]

const router = createRouter({
  history: createWebHistory(),
  routes,
})

router.beforeEach((to) => {
  const auth = useAuthStore()
  if (to.meta.public) {
    // Already-authenticated users skip the login page.
    if (to.path === '/login' && auth.isLoggedIn) return { path: '/' }
    return true
  }
  if (!auth.isLoggedIn) return { path: '/login' }
  return true
})

export default router
