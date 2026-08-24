/**
 * Routes feature helpers — predicate/filter args conversion and payload
 * building. Payload shape follows model/RouteDefinition.java.
 */
import type {
  GrayRule,
  GrayRules,
  RouteDefinition,
  RoutePredicate,
  RouteResponse,
  RouteServiceBinding,
  RoutingMode,
  ServiceBindingType,
} from '../../types/route';
import type { NacosDiscoveryServiceInfo, ServiceDefinition } from '../../types/service';

export const UUID_PATTERN = /^[a-f0-9-]{36}$/i;

// ============================================================================
// Predicate / filter args parsing (form string -> Map<String,String>)
// ============================================================================

export const parsePredicateArgs = (type: string, args: unknown): Record<string, string> => {
  if (!args || args === 'undefined' || args === 'null') {
    switch (type) {
      case 'Path':
        return { pattern: '/**' };
      case 'Host':
        return { patterns: '' }; // SCG expects "patterns" (plural)
      case 'Method':
        return { 'methods.0': 'GET' }; // indexed key for GATHER_LIST
      case 'Header':
        return { header: '', regexp: '.*' };
      default:
        return { value: '' };
    }
  }

  if (typeof args === 'object' && args !== null && !Array.isArray(args)) {
    // Method multi-select stores string[]; convert to indexed keys below.
    const obj = args as Record<string, unknown>;
    if (type === 'Method' && Array.isArray(obj.methods)) {
      return buildMethodArgs((obj.methods as unknown[]).map(String));
    }
    if (Array.isArray(obj)) {
      return buildMethodArgs((obj as unknown[]).map(String));
    }
    return obj as Record<string, string>;
  }

  const argsStr = String(args);
  switch (type) {
    case 'Path':
      return { pattern: argsStr || '/**' };
    case 'Host':
      return { patterns: argsStr };
    case 'Method': {
      const methods = argsStr.split(',').map((m) => m.trim()).filter(Boolean);
      return buildMethodArgs(methods);
    }
    case 'Header': {
      // indexOf handles regexp values that contain colons
      const colonIndex = argsStr.indexOf(':');
      if (colonIndex === -1) {
        return { header: argsStr, regexp: '.*' };
      }
      return { header: argsStr.substring(0, colonIndex), regexp: argsStr.substring(colonIndex + 1) || '.*' };
    }
    default:
      return { value: argsStr };
  }
};

export const buildMethodArgs = (methods: string[]): Record<string, string> => {
  const args: Record<string, string> = {};
  methods.forEach((m, i) => {
    args[`methods.${i}`] = m;
  });
  return args;
};

/** Extract HTTP methods from predicate args (indexed keys / array / string). */
export const extractMethods = (args: Record<string, unknown> | string | undefined): string[] => {
  if (!args) return [];
  if (typeof args === 'string') return args.split(',').map((m) => m.trim());
  if (Array.isArray(args.methods)) return (args.methods as unknown[]).map(String);
  if (typeof args.methods === 'string') return args.methods.split(',').map((m) => m.trim());
  const methodKeys = Object.keys(args).filter((k) => k.startsWith('methods.'));
  if (methodKeys.length > 0) {
    return methodKeys
      .sort((a, b) => parseInt(a.split('.')[1], 10) - parseInt(b.split('.')[1], 10))
      .map((k) => String(args[k] ?? ''));
  }
  return [];
};

export const stringifyPredicateArgs = (
  type: string,
  args: Record<string, unknown> | string | undefined,
): string => {
  if (!args) return '';
  if (typeof args === 'string') return args;
  switch (type) {
    case 'Path':
      return String(args.pattern || '/**');
    case 'Host':
      // also support legacy singular "pattern"
      return String(args.patterns || args.pattern || '');
    case 'Method':
      return extractMethods(args).join(', ');
    case 'Header':
      return `${args.header || args.name || ''}:${args.regexp || '.*'}`;
    default:
      return String(args.value || '');
  }
};

export const parseFilterArgs = (type: string, args: unknown): Record<string, string> => {
  if (!args || args === 'undefined' || args === 'null') {
    switch (type) {
      case 'StripPrefix':
        return { parts: '1' };
      case 'PrefixPath':
        return { prefix: '' };
      case 'AddRequestHeader':
      case 'SetRequestHeader':
      case 'AddResponseHeader':
      case 'SetResponseHeader':
      case 'AddRequestParameter':
        return { name: '', value: '' };
      case 'RemoveRequestHeader':
      case 'RemoveResponseHeader':
      case 'RemoveRequestParameter':
        return { name: '' };
      case 'SetPath':
        return { template: '' };
      case 'RewritePath':
        return { regexp: '', replacement: '' };
      case 'SetStatus':
        return { status: '' };
      case 'RequestSize':
        return { maxSize: '' };
      case 'RedirectTo':
        return { status: '302', url: '' };
      default:
        return { value: '' };
    }
  }

  if (typeof args === 'object' && args !== null && !Array.isArray(args)) {
    const obj = args as Record<string, unknown>;
    return Object.fromEntries(Object.entries(obj).map(([k, v]) => [k, String(v ?? '')]));
  }

  const argsStr = String(args);
  const splitOn = (sep: string): [string, string] => {
    const idx = argsStr.indexOf(sep);
    if (idx === -1) return [argsStr, ''];
    return [argsStr.substring(0, idx), argsStr.substring(idx + 1)];
  };

  switch (type) {
    case 'StripPrefix':
      return { parts: String(parseInt(argsStr, 10) || 1) };
    case 'PrefixPath':
      return { prefix: argsStr };
    case 'AddRequestHeader':
    case 'SetRequestHeader':
    case 'AddResponseHeader':
    case 'SetResponseHeader':
    case 'AddRequestParameter': {
      const [name, value] = splitOn(':');
      return { name, value };
    }
    case 'RemoveRequestHeader':
    case 'RemoveResponseHeader':
    case 'RemoveRequestParameter':
      return { name: argsStr };
    case 'SetPath':
      return { template: argsStr };
    case 'RewritePath': {
      const [regexp, replacement] = splitOn(',');
      return { regexp, replacement };
    }
    case 'SetStatus':
      return { status: argsStr };
    case 'RequestSize':
      return { maxSize: argsStr };
    case 'RedirectTo': {
      const [status, url] = splitOn(',');
      return { status: status || '302', url };
    }
    default:
      return { value: argsStr };
  }
};

export const stringifyFilterArgs = (
  type: string,
  args: Record<string, unknown> | string | undefined,
): string => {
  if (!args) return '';
  if (typeof args === 'string') return args;
  switch (type) {
    case 'StripPrefix':
      return String(args.parts || 1);
    case 'PrefixPath':
      return String(args.prefix || '');
    case 'AddRequestHeader':
    case 'SetRequestHeader':
    case 'AddResponseHeader':
    case 'SetResponseHeader':
    case 'AddRequestParameter':
      return `${args.name || ''}:${args.value || ''}`;
    case 'RemoveRequestHeader':
    case 'RemoveResponseHeader':
    case 'RemoveRequestParameter':
      return String(args.name || '');
    case 'SetPath':
      return String(args.template || '');
    case 'RewritePath':
      return `${args.regexp || ''},${args.replacement || ''}`;
    case 'SetStatus':
      return String(args.status || '');
    case 'RequestSize':
      return String(args.maxSize || args.value || '');
    case 'RedirectTo':
      return `${args.status || '302'},${args.url || ''}`;
    default:
      return String(args.value || '');
  }
};

// ============================================================================
// Service binding helpers
// ============================================================================

/** Strip "static://" / "lb://" prefixes and return [protocol, pureId]. */
export const splitServiceUri = (serviceId: string): [string, string] => {
  if (!serviceId) return ['static', ''];
  if (serviceId.includes('://')) {
    const [protocol, id] = serviceId.split('://');
    return [protocol || 'static', id || serviceId];
  }
  return ['static', serviceId];
};

/** Resolve a display name for a static service (UUID -> human name). */
export const resolveServiceDisplayName = (
  displayName: string | undefined,
  serviceId: string,
  services: ServiceDefinition[],
): string => {
  if (displayName && !UUID_PATTERN.test(displayName)) return displayName;
  const found = services.find((svc) => svc.serviceId === serviceId);
  return found?.name || displayName || serviceId;
};

/** Form binding row (serviceId carries protocol prefix) -> backend binding. */
export const toBackendBinding = (
  formBinding: Partial<RouteServiceBinding>,
  services: ServiceDefinition[],
  nacosServices: NacosDiscoveryServiceInfo[],
): RouteServiceBinding => {
  const [protocol, serviceId] = splitServiceUri(formBinding.serviceId || '');
  const bindingType: ServiceBindingType = protocol === 'lb' ? 'DISCOVERY' : 'STATIC';

  let serviceName = formBinding.serviceName;
  let serviceNamespace: string | undefined;
  let serviceGroup: string | undefined;

  if (protocol === 'lb') {
    serviceName = serviceId;
    const nacosService = nacosServices.find((ns) => ns.serviceName === serviceId);
    if (nacosService) {
      serviceNamespace = nacosService.namespace || '';
      serviceGroup = nacosService.group || 'DEFAULT_GROUP';
    }
  } else if (!serviceName || UUID_PATTERN.test(serviceName)) {
    const found = services.find((svc) => svc.serviceId === serviceId);
    serviceName = found?.name || serviceName || serviceId;
  }

  return {
    serviceId,
    serviceName,
    weight: formBinding.weight || 100,
    version: formBinding.version,
    enabled: formBinding.enabled !== false,
    type: bindingType,
    serviceNamespace,
    serviceGroup,
  };
};

/** Backend binding -> form row (serviceId re-prefixed with protocol). */
export const toFormBinding = (
  binding: RouteServiceBinding,
  services: ServiceDefinition[],
  nacosServices: NacosDiscoveryServiceInfo[],
): RouteServiceBinding => {
  let pureServiceId = binding.serviceId || '';
  let protocol: string;

  if (pureServiceId.includes('://')) {
    [protocol, pureServiceId] = splitServiceUri(pureServiceId);
  } else {
    // Infer protocol from the Nacos registry; stored type may be stale.
    const isNacosService = nacosServices.some((ns) => ns.serviceName === pureServiceId);
    protocol = isNacosService ? 'lb' : 'static';
  }

  let serviceName = binding.serviceName;
  if (!serviceName || UUID_PATTERN.test(serviceName)) {
    const found = services.find((svc) => svc.serviceId === pureServiceId);
    if (found?.name) {
      serviceName = found.name;
    }
  }

  return {
    ...binding,
    serviceId: `${protocol}://${pureServiceId}`,
    serviceName,
  };
};

// ============================================================================
// Payload building (form values -> RouteDefinition body)
// ============================================================================

export interface RouteFormValues {
  routeName: string;
  order?: number | string;
  description?: string;
  routingMode?: RoutingMode;
  /** SINGLE mode: full URI ("static://uuid" or "lb://name"). */
  serviceId?: string;
  serviceNamespace?: string;
  serviceGroup?: string;
  uri?: string;
  /** MULTI mode form rows. */
  services?: Array<Partial<RouteServiceBinding>>;
  predicates?: Array<{ name: string; args?: unknown }>;
  filters?: Array<{ name: string; args?: unknown }>;
  grayRules?: GrayRule[];
}

export interface PayloadBuildContext {
  services: ServiceDefinition[];
  nacosServices: NacosDiscoveryServiceInfo[];
  /** Carried over from the existing route on edit (filters/metadata). */
  existing?: RouteResponse;
}

/**
 * Build the RouteDefinition body for create/update.
 * Returns null (after caller shows validation errors) when invalid.
 */
export const buildRoutePayload = (
  values: RouteFormValues,
  mode: RoutingMode,
  grayRulesEnabled: boolean,
  ctx: PayloadBuildContext,
): RouteDefinition | null => {
  const predicates: RoutePredicate[] = (values.predicates || [])
    .filter((p) => p && p.name)
    .map((p) => ({ name: p.name, args: parsePredicateArgs(p.name, p.args) }));

  if (predicates.length === 0) return null;
  if (!predicates.some((p) => p.name === 'Path')) return null;

  // The form no longer edits filters; preserve whatever the route has.
  const filters = (ctx.existing?.filters ?? []).map((f) => ({
    name: f.name,
    args: { ...(f.args ?? {}) },
  }));

  const body: RouteDefinition = {
    routeName: values.routeName,
    order: values.order !== undefined && values.order !== '' ? Number(values.order) : 0,
    description: values.description,
    predicates,
    filters,
    metadata: ctx.existing?.metadata,
    mode,
  };

  if (mode === 'SINGLE') {
    const finalUri = values.serviceId || values.uri;
    if (!finalUri) return null;
    body.uri = finalUri;
    const [protocol, pureId] = splitServiceUri(finalUri);
    body.serviceId = pureId;
    if (protocol === 'lb') {
      body.serviceNamespace = values.serviceNamespace || '';
      body.serviceGroup = values.serviceGroup || 'DEFAULT_GROUP';
    }
    return body;
  }

  // MULTI mode
  const formBindings = values.services || [];
  if (formBindings.length === 0) return null;

  const totalWeight = formBindings.reduce((sum, s) => sum + (s.weight || 0), 0);
  if (totalWeight !== 100) return null;

  const bindings = formBindings.map((s) => toBackendBinding(s, ctx.services, ctx.nacosServices));
  body.services = bindings;

  if (grayRulesEnabled && values.grayRules && values.grayRules.length > 0) {
    const rules: GrayRules = {
      enabled: true,
      rules: values.grayRules.map((r) => ({
        type: r.type,
        name: r.name,
        value: String(r.value),
        targetVersion: r.targetVersion,
      })),
    };
    body.grayRules = rules;
  }

  // Backward compatibility: uri mirrors the first binding.
  body.uri = formBindings[0]?.serviceId || 'static://multi-service';
  return body;
};

/** Weight sum of MULTI-mode form rows (for the live indicator). */
export const sumWeights = (bindings: Array<{ weight?: number }> | undefined): number => (
  (bindings ?? []).reduce((sum, s) => sum + (s?.weight || 0), 0)
);
