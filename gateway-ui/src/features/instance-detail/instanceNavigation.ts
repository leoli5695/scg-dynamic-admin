/** Stable route keys shared by grouped navigation and direct URL handling. */
export const INSTANCE_NAV_GROUPS = [
  { key: 'overview', pages: ['overview'] },
  { key: 'traffic', pages: ['services', 'routes', 'strategies', 'plugins', 'plugin_bindings', 'middleware'] },
  { key: 'security', pages: ['authentication', 'token_revocation', 'certificates'] },
  { key: 'observability', pages: ['monitor', 'alerts', 'trace', 'access_log', 'audit_logs', 'events', 'resources'] },
  { key: 'diagnostics', pages: ['diagnostic', 'topology', 'filter_chain', 'request_replay'] },
  { key: 'tools', pages: ['copilot', 'stress_test'] },
] as const;

export type InstancePageKey = typeof INSTANCE_NAV_GROUPS[number]['pages'][number];

export function getInstanceNavigation(isExternal: boolean) {
  return INSTANCE_NAV_GROUPS.map((group) => ({
    key: group.key,
    pages: group.pages.filter((page) => !isExternal || (page !== 'events' && page !== 'resources')),
  }));
}
