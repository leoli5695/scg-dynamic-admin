import { buildRoutePayload } from '../src/features/routes/utils.ts';

// These are actual form values, passed through the production request builder.
const common = {
  routeName: 'contract-route', order: '12', description: '路由契约测试',
  predicates: [
    { name: 'Path', args: '/contract/**' },
    { name: 'Host', args: '*.example.com' },
    { name: 'Method', args: ['GET', 'POST'] },
    { name: 'Header', args: 'X-Tenant:team:.*' },
  ],
};
export const routeContractFixtures = {
  discovery: buildRoutePayload({ ...common, serviceId: 'lb://orders?ns=staging&grp=BLUE' }, 'SINGLE', false, {
    services: [], existing: { id: 'existing',
      filters: [{ name: 'StripPrefix', args: { parts: '1' } }],
      metadata: { 'response-timeout': 3000, custom: { owner: 'team' } },
    },
  }),
  publicDiscovery: buildRoutePayload({ ...common, serviceId: 'lb://orders' }, 'SINGLE', false, { services: [] }),
  static: buildRoutePayload({ ...common, serviceId: 'static://abcd1234-1234-1234-1234-abcd12345678' }, 'SINGLE', false, { services: [] }),
  multi: buildRoutePayload({
    ...common,
    services: [
      { serviceId: 'static://abcd1234-1234-1234-1234-abcd12345678', weight: 100, enabled: true },
      { serviceId: 'lb://orders?ns=staging&grp=BLUE', weight: 0, enabled: true, version: 'canary' },
    ],
    grayRules: [{ type: 'HEADER', name: 'X-Canary', value: 'yes', targetVersion: 'orders' }],
  }, 'MULTI', true, { services: [] }),
  ai: buildRoutePayload({
    ...common, category: 'AI',
    aiUpstreams: [{ provider: 'test-ai-service', weight: 100, modelMappingPairs: [{ key: 'chat', value: 'upstream-chat' }] }],
    modelPredicates: [{ matchType: 'PREFIX', value: 'chat' }],
  }, 'SINGLE', false, { services: [], existing: { id: 'existing', aiFallback: {
    enabled: true, fallbackStrategy: 'SEQ', upstreams: [{ provider: 'backup-ai-service', weight: 100 }],
  } } }),
};
