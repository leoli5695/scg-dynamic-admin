export const INSTANCE_NAV_GROUPS = [
  { key: 'overview', pages: ['overview', 'monitor'] },
  { key: 'services', pages: ['services'] },
  { key: 'routes', pages: ['routes', 'strategies'] },
  { key: 'requests', pages: ['requests', 'access_log'] },
  // Advanced evidence views stay reachable by their old URLs, but the primary path is a request.
  { key: 'diagnostics', pages: ['trace', 'filter_chain', 'request_replay', 'topology', 'diagnostic'] },
  { key: 'usage', pages: ['token_usage'] },
  { key: 'security', pages: ['authentication', 'token_revocation', 'certificates'] },
  { key: 'operations', pages: ['audit_logs', 'alerts', 'events', 'resources'] },
  { key: 'tools', pages: ['copilot', 'plugins', 'plugin_bindings', 'stress_test', 'middleware'] },
] as const;

export type InstancePageKey = typeof INSTANCE_NAV_GROUPS[number]['pages'][number];

export interface FeatureVisibility {
  stressTest?: boolean;
  middleware?: boolean;
  plugins?: boolean;
}

/** Pages stay registered; switched-off features simply disappear from the console nav. */
export function getInstanceNavigation(isExternal: boolean, features: FeatureVisibility = {}) {
  const enabled = { stressTest: true, middleware: true, plugins: true, ...features };
  return INSTANCE_NAV_GROUPS.map((group) => ({
    key: group.key,
    pages: group.pages.filter((page) => {
      if (isExternal && (page === 'events' || page === 'resources')) return false;
      if (!enabled.stressTest && page === 'stress_test') return false;
      if (!enabled.middleware && page === 'middleware') return false;
      if (!enabled.plugins && (page === 'plugins' || page === 'plugin_bindings')) return false;
      return true;
    }),
  }));
}
