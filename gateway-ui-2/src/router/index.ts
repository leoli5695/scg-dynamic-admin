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
      { path: 'token-usage', component: Placeholder, meta: { title: 'Token Usage (Stage 4)' } },
      {
        path: 'instances/:instanceId',
        component: InstanceDetailLayout,
        children: [
          { path: '', component: OverviewTab },
          { path: 'events', component: EventsTab },
          { path: 'resources', component: ResourcesTab },
          // Remaining console tabs land in Stage 1 (services/routes) and later stages.
          { path: 'services', component: ServicesPage },
          { path: 'routes', component: Placeholder, meta: { title: 'Routes (Stage 1)' } },
          { path: 'plugins', component: Placeholder, meta: { title: 'Plugins (Stage 2)' } },
          { path: 'plugin_bindings', component: Placeholder, meta: { title: 'Plugin Bindings (Stage 2)' } },
          { path: 'strategies', component: Placeholder, meta: { title: 'Strategies (Stage 2)' } },
          { path: 'authentication', component: Placeholder, meta: { title: 'Auth Policies (Stage 2)' } },
          { path: 'token_revocation', component: Placeholder, meta: { title: 'Token Revocation (Stage 2)' } },
          { path: 'certificates', component: Placeholder, meta: { title: 'Certificates (Stage 2)' } },
          { path: 'trace', component: Placeholder, meta: { title: 'Trace (Stage 3)' } },
          { path: 'monitor', component: Placeholder, meta: { title: 'Monitor (Stage 3)' } },
          { path: 'alerts', component: Placeholder, meta: { title: 'Alerts (Stage 3)' } },
          { path: 'access_log', component: Placeholder, meta: { title: 'Access Log (Stage 3)' } },
          { path: 'audit_logs', component: Placeholder, meta: { title: 'Audit Logs (Stage 3)' } },
          { path: 'diagnostic', component: Placeholder, meta: { title: 'Diagnostic (Stage 3)' } },
          { path: 'topology', component: Placeholder, meta: { title: 'Topology (Stage 3)' } },
          { path: 'filter_chain', component: Placeholder, meta: { title: 'Filter Chain (Stage 3)' } },
          { path: 'request_replay', component: Placeholder, meta: { title: 'Request Replay (Stage 3)' } },
          { path: 'copilot', component: Placeholder, meta: { title: 'Copilot (Stage 4)' } },
          { path: 'stress_test', component: Placeholder, meta: { title: 'Stress Test (Stage 4)' } },
          { path: 'middleware', component: Placeholder, meta: { title: 'Middleware (Stage 3)' } },
        ],
      },
      { path: 'kubernetes', component: Placeholder, meta: { title: 'Kubernetes (Stage 4)' } },
      { path: 'license', component: Placeholder, meta: { title: 'License (Stage 4)' } },
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
