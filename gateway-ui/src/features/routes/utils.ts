/**
 * Routes feature helpers — predicate/filter args conversion and payload
 * building. Payload shape follows model/RouteDefinition.java.
 */
import type {
  AiModelPredicate,
  AiUpstream,
  GrayRule,
  GrayRules,
  RouteCategory,
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

/** Drop the "?ns=&grp=" qualifier an "lb://" Select option value may carry. */
export const stripServiceQualifier = (serviceId?: string): string => {
  if (!serviceId) return '';
  // Only lb:// option values are qualified. An http(s) target may legitimately
  // carry a query string, so leave anything else byte-for-byte alone.
  if (!serviceId.startsWith('lb://')) return serviceId;
  const qIndex = serviceId.indexOf('?');
  return qIndex >= 0 ? serviceId.slice(0, qIndex) : serviceId;
};

/** Strip "static://" / "lb://" prefixes and return [protocol, pureId]. */
export const splitServiceUri = (serviceId: string): [string, string] => {
  if (!serviceId) return ['static', ''];
  const bare = stripServiceQualifier(serviceId);
  if (bare.includes('://')) {
    const [protocol, id] = bare.split('://');
    return [protocol || 'static', id || bare];
  }
  return ['static', bare];
};

/**
 * Option value for a Nacos ("lb://") service in the target Select.
 *
 * A serviceName is only unique together with its namespace and group, and the
 * gateway routes on all three. Encoding the qualifier in the value is what lets
 * the form keep the operator's choice: re-deriving namespace/group later from
 * the name alone resolves to whichever registry entry the list happened to hold
 * first, so picking prod/svc published dev/svc.
 *
 * The qualifier is UI-only - stripServiceQualifier removes it before anything
 * is published to Nacos.
 */
export const lbOptionValue = (
  serviceName: string,
  namespace?: string,
  group?: string,
): string => {
  const params = new URLSearchParams();
  if (namespace) params.set('ns', namespace);
  if (group && group !== 'DEFAULT_GROUP') params.set('grp', group);
  const query = params.toString();
  return query ? `lb://${serviceName}?${query}` : `lb://${serviceName}`;
};

/** Inverse of lbOptionValue; defaults to the public namespace / DEFAULT_GROUP. */
export const parseServiceQualifier = (
  serviceId?: string,
): { namespace: string; group: string } => {
  const raw = serviceId ?? '';
  const qIndex = raw.indexOf('?');
  const params = new URLSearchParams(qIndex >= 0 ? raw.slice(qIndex + 1) : '');
  return {
    namespace: params.get('ns') || '',
    group: params.get('grp') || 'DEFAULT_GROUP',
  };
};

/** Stored uri (+ namespace/group) -> the Select option value that matches it. */
export const toFormServiceId = (
  protocol: string,
  pureServiceId: string,
  namespace?: string,
  group?: string,
): string => (
  protocol === 'lb'
    ? lbOptionValue(pureServiceId, namespace, group)
    : `${protocol}://${pureServiceId}`
);

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
): RouteServiceBinding => {
  const [protocol, serviceId] = splitServiceUri(formBinding.serviceId || '');
  const bindingType: ServiceBindingType = protocol === 'lb' ? 'DISCOVERY' : 'STATIC';

  let serviceName = formBinding.serviceName;
  let serviceNamespace: string | undefined;
  let serviceGroup: string | undefined;

  if (protocol === 'lb') {
    serviceName = serviceId;
    const qualifier = parseServiceQualifier(formBinding.serviceId);
    serviceNamespace = qualifier.namespace;
    serviceGroup = qualifier.group;
  } else if (!serviceName || UUID_PATTERN.test(serviceName)) {
    const found = services.find((svc) => svc.serviceId === serviceId);
    serviceName = found?.name || serviceName || serviceId;
  }

  return {
    serviceId,
    serviceName,
    weight: formBinding.weight ?? 100,
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
  } else if (binding.type) {
    protocol = binding.type === 'STATIC' ? 'static' : 'lb';
  } else {
    // Legacy bindings without a type can be inferred from the current registry.
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
    serviceId: toFormServiceId(
      protocol, pureServiceId, binding.serviceNamespace, binding.serviceGroup,
    ),
    serviceName,
  };
};

// ============================================================================
// Payload building (form values -> RouteDefinition body)
// ============================================================================

/** A single model-mapping row in the AI route form (requested -> upstream model). */
export interface AiModelMappingPair { key?: string; value?: string; }

/** AI upstream row as edited in the form (modelMapping held as ordered pairs). */
export interface AiUpstreamFormRow {
  provider?: string;
  weight?: number;
  modelMappingPairs?: AiModelMappingPair[];
}

/** Record<string,string> -> ordered [{key,value}] pairs, for editing in a Form.List. */
export const modelMappingToPairs = (mm?: Record<string, string>): AiModelMappingPair[] => (
  mm ? Object.entries(mm).map(([key, value]) => ({ key, value })) : []
);

/** Ordered pairs -> Record<string,string>, dropping blank keys; undefined when empty. */
export const pairsToModelMapping = (
  pairs?: AiModelMappingPair[],
): Record<string, string> | undefined => {
  const out: Record<string, string> = {};
  (pairs ?? []).forEach((p) => {
    if (p && p.key && p.key.trim()) out[p.key.trim()] = p.value ?? '';
  });
  return Object.keys(out).length > 0 ? out : undefined;
};

export interface RouteFormValues {
  routeName: string;
  order?: number | string;
  description?: string;
  routingMode?: RoutingMode;
  /**
   * SINGLE mode: the target Select's option value - "static://uuid", or
   * "lb://name" with an optional "?ns=&grp=" qualifier (see lbOptionValue).
   */
  serviceId?: string;
  uri?: string;
  /** MULTI mode form rows. */
  services?: Array<Partial<RouteServiceBinding>>;
  predicates?: Array<{ name: string; args?: unknown }>;
  filters?: Array<{ name: string; args?: unknown }>;
  grayRules?: GrayRule[];
  /** Route category: NORMAL (default) or AI. */
  category?: RouteCategory;
  /** AI mode: bound AI services (upstreams), modelMapping edited as pairs. */
  aiUpstreams?: AiUpstreamFormRow[];
  /** AI mode: model match predicates. */
  modelPredicates?: Array<Partial<AiModelPredicate>>;
}

export interface PayloadBuildContext {
  services: ServiceDefinition[];
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

  // AI route: bind AI services (upstreams) instead of a normal service target.
  // The upstream URL is resolved by the data plane from the bound providers, so the
  // uri is a placeholder (the backend also normalizes it). A Path predicate is still
  // required - it is the northbound OpenAI-compatible path, e.g. /v1/chat/completions.
  if (values.category === 'AI') {
    const aiUpstreams: AiUpstream[] = (values.aiUpstreams || [])
      .filter((u) => u && u.provider)
      .map((u) => ({
        provider: u.provider as string,
        weight: u.weight ?? 100,
        modelMapping: pairsToModelMapping(u.modelMappingPairs),
      }));
    if (aiUpstreams.length === 0) return null;

    const modelPredicates: AiModelPredicate[] = (values.modelPredicates || [])
      .filter((p) => p && p.value)
      .map((p) => ({ matchType: p.matchType || 'PREFIX', value: p.value as string }));

    return {
      routeName: values.routeName,
      order: values.order !== undefined && values.order !== '' ? Number(values.order) : 0,
      description: values.description,
      predicates,
      filters,
      metadata: ctx.existing?.metadata,
      mode: 'SINGLE',
      category: 'AI',
      uri: 'https://ai-gateway.invalid',
      aiUpstreams,
      modelPredicates,
      // The form does not edit the fallback chain, so carry it over like filters/metadata.
      // Omitting it made every edit of an AI route PUT a body without aiFallback, and the
      // backend persists the submitted definition verbatim - the configured failover was
      // silently deleted on the first save.
      aiFallback: ctx.existing?.aiFallback,
    };
  }

  const body: RouteDefinition = {
    routeName: values.routeName,
    order: values.order !== undefined && values.order !== '' ? Number(values.order) : 0,
    description: values.description,
    predicates,
    filters,
    metadata: ctx.existing?.metadata,
    mode,
    category: 'NORMAL',
  };

  if (mode === 'SINGLE') {
    const finalUri = values.serviceId || values.uri;
    if (!finalUri) return null;
    // The qualifier is a UI-only disambiguator: SCG resolves "lb://name" plus
    // the namespace/group fields, so publishing it verbatim would break routing.
    body.uri = stripServiceQualifier(finalUri);
    const [protocol, pureId] = splitServiceUri(finalUri);
    body.serviceId = pureId;
    if (protocol === 'lb') {
      const qualifier = parseServiceQualifier(finalUri);
      body.serviceNamespace = qualifier.namespace;
      body.serviceGroup = qualifier.group;
    }
    return body;
  }

  // MULTI mode
  const formBindings = values.services || [];
  if (formBindings.length === 0) return null;

  const totalWeight = formBindings.reduce((sum, s) => sum + (s.weight || 0), 0);
  if (totalWeight !== 100) return null;

  const bindings = formBindings.map((s) => toBackendBinding(s, ctx.services));
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
  body.uri = stripServiceQualifier(formBindings[0]?.serviceId) || 'static://multi-service';
  return body;
};

/** Weight sum of MULTI-mode form rows (for the live indicator). */
export const sumWeights = (bindings: Array<{ weight?: number }> | undefined): number => (
  (bindings ?? []).reduce((sum, s) => sum + (s?.weight || 0), 0)
);
