/**
 * Pure helpers for the strategies feature:
 * - buildStrategyConfig: antd form values -> StrategyDefinition.config
 * - strategyToFormValues: StrategyDefinition -> antd form values (edit refill)
 * - display helpers for the strategy card config preview
 */
import type { StrategyConfig, StrategyDefinition } from '../../types/strategy';

/** Form values are dynamic (schema-driven config fields + base fields). */
export type StrategyFormValues = { [key: string]: unknown };

/**
 * Global strategy types with uniqueness constraint:
 * only one GLOBAL strategy per type is allowed (frontend guard mirroring
 * the product rule; backend still accepts duplicates).
 */
export const GLOBAL_UNIQUE_STRATEGY_TYPES = [
  'RATE_LIMITER',
  'MULTI_DIM_RATE_LIMITER',
  'TOKEN_RATE_LIMITER',
  'IP_FILTER',
  'TIMEOUT',
  'CIRCUIT_BREAKER',
  'AUTH',
  'RETRY',
  'CORS',
  'CACHE',
  'SECURITY',
  'API_VERSION',
];

export const requiresGlobalUniqueness = (strategyType: string): boolean =>
  GLOBAL_UNIQUE_STRATEGY_TYPES.includes(strategyType);

export const findGlobalStrategy = (
  strategies: StrategyDefinition[],
  strategyType: string,
): StrategyDefinition | undefined => {
  if (!requiresGlobalUniqueness(strategyType)) return undefined;
  return strategies.find(
    (s) => s.strategyType === strategyType && s.scope === 'GLOBAL',
  );
};

/** Form base fields that never belong to StrategyDefinition.config. */
const BASE_FORM_KEYS = new Set([
  'strategyName', 'strategyType', 'scope', 'routeId',
  'priority', 'enabled', 'description',
]);

const toInt = (value: unknown, fallback: number): number => {
  const parsed = parseInt(String(value), 10);
  return Number.isNaN(parsed) || parsed <= 0 ? fallback : parsed;
};

const toFloat = (value: unknown, fallback: number): number => {
  const parsed = parseFloat(String(value));
  return Number.isNaN(parsed) || parsed <= 0 ? fallback : parsed;
};

const splitCsv = (value: unknown): string[] =>
  typeof value === 'string' && value.trim()
    ? value.split(',').map((item) => item.trim()).filter(Boolean)
    : [];

const timeUnitToWindowSizeMs = (timeUnit: string): number => {
  switch (timeUnit) {
    case 'minute': return 60000;
    case 'hour': return 3600000;
    default: return 1000;
  }
};

interface MultiDimFormDimension {
  type?: string;
  qps?: unknown;
  burstCapacity?: unknown;
  timeUnit?: string;
  userIdSource?: string;
  userIdHeader?: string;
  keySource?: string;
  apiKeyHeader?: string;
  headerName?: string;
}

interface QuotaConfig {
  enabled: boolean;
  qps: number;
  burstCapacity: number;
  windowSizeMs: number;
}

/** MULTI_DIM_RATE_LIMITER dimensions (Form.List) -> quota objects. */
function buildMultiDimConfig(values: StrategyFormValues): StrategyConfig {
  const dimensions = (values.dimensions ?? []) as MultiDimFormDimension[];

  const config: StrategyConfig = {
    enabled: true,
    globalQuota: { enabled: false, qps: 10000, burstCapacity: 20000, windowSizeMs: 1000 },
    tenantQuota: { enabled: false, qps: 1000, burstCapacity: 2000, windowSizeMs: 1000 },
    userQuota: { enabled: false, qps: 100, burstCapacity: 200, windowSizeMs: 1000 },
    ipQuota: { enabled: false, qps: 50, burstCapacity: 100, windowSizeMs: 1000 },
    rejectStrategy: values.rejectStrategy || 'FIRST_HIT',
    headerNames: {
      tenantIdHeader: 'X-Tenant-Id',
      userIdHeader: 'X-User-Id',
      apiKeyHeader: 'X-Api-Key',
    },
    keyPrefix: 'multi_rate:',
    windowSizeMs: 1000,
  };
  const headerNames = config.headerNames as Record<string, string>;

  dimensions.forEach((dim) => {
    const qps = toInt(dim.qps, 100);
    const burstCapacity = toInt(dim.burstCapacity, 200);
    const windowSizeMs = timeUnitToWindowSizeMs(dim.timeUnit || 'second');
    const quota: QuotaConfig = { enabled: true, qps, burstCapacity, windowSizeMs };

    switch (dim.type) {
      case 'GLOBAL':
        config.globalQuota = quota;
        break;
      case 'IP':
        config.ipQuota = quota;
        break;
      case 'USER':
        config.userQuota = quota;
        if (dim.userIdSource) config.userIdSource = dim.userIdSource;
        if (dim.userIdHeader) headerNames.userIdHeader = dim.userIdHeader;
        break;
      case 'CLIENT':
        config.tenantQuota = quota;
        if (dim.keySource) config.keySource = dim.keySource;
        if (dim.apiKeyHeader) headerNames.apiKeyHeader = dim.apiKeyHeader;
        break;
      case 'HEADER':
        config.tenantQuota = quota;
        config.keySource = 'header';
        if (dim.headerName) headerNames.tenantIdHeader = dim.headerName;
        break;
      default:
        break;
    }
  });

  return config;
}

interface TokenQuotaFormRow {
  tenantId?: string;
  monthlyQuota?: unknown;
  dailyQuota?: unknown;
  burstQuota?: unknown;
  quotaPeriod?: string;
  windowTokenPerMinute?: unknown;
  windowTokenPerHour?: unknown;
}

/**
 * TOKEN_RATE_LIMITER form values -> config aligned with
 * my-gateway model/TokenQuotaConfig.java (tenantQuotas is a map keyed by
 * tenantId; all other schema fields are flat).
 */
function buildTokenRateLimiterConfig(values: StrategyFormValues): StrategyConfig {
  const config: StrategyConfig = { enabled: true };

  Object.entries(values).forEach(([key, value]) => {
    if (!BASE_FORM_KEYS.has(key) && value !== undefined && key !== 'tenantQuotas') {
      config[key] = value;
    }
  });

  const rows = (values.tenantQuotas ?? []) as TokenQuotaFormRow[];
  const tenantQuotas: Record<string, unknown> = {};
  rows.forEach((row) => {
    if (!row.tenantId) return;
    tenantQuotas[row.tenantId] = {
      monthlyQuota: toInt(row.monthlyQuota, 1000000),
      dailyQuota: toInt(row.dailyQuota, 50000),
      burstQuota: row.burstQuota ? toInt(row.burstQuota, 0) : 0,
      quotaPeriod: row.quotaPeriod || 'BOTH',
      ...(row.windowTokenPerMinute ? { windowTokenPerMinute: toInt(row.windowTokenPerMinute, 0) } : {}),
      ...(row.windowTokenPerHour ? { windowTokenPerHour: toInt(row.windowTokenPerHour, 0) } : {}),
    };
  });
  config.tenantQuotas = tenantQuotas;

  return config;
}

interface AiProxyCredentialRow { provider?: string; credentialRef?: string; }
interface AiProxyModelRow { model?: string; provider?: string; }
interface AiProxyPoolRow { provider?: string; strategy?: string; credentialRefs?: string[]; }

/**
 * AI_PROXY form values -> config aligned with my-gateway model/AiProxyConfig.java.
 * providerCredentials / modelRouting Form.Lists become maps.
 */
function buildAiProxyConfig(values: StrategyFormValues): StrategyConfig {
  const providerApiKeyRefs: Record<string, string> = {};
  ((values.providerCredentials ?? []) as AiProxyCredentialRow[]).forEach((row) => {
    if (row.provider && row.credentialRef) providerApiKeyRefs[row.provider] = row.credentialRef;
  });
  const modelRouting: Record<string, string> = {};
  ((values.modelRouting ?? []) as AiProxyModelRow[]).forEach((row) => {
    if (row.model && row.provider) modelRouting[row.model] = row.provider;
  });
  const providerKeyPools: Record<string, { credentialRefs: string[]; strategy: string }> = {};
  ((values.keyPools ?? []) as AiProxyPoolRow[]).forEach((row) => {
    if (row.provider && (row.credentialRefs ?? []).length > 0) {
      providerKeyPools[row.provider] = {
        credentialRefs: row.credentialRefs ?? [],
        strategy: row.strategy || 'ROUND_ROBIN',
      };
    }
  });
  return {
    enabled: true,
    providerSelection: values.providerSelection || 'MODEL',
    fixedProvider: values.fixedProvider,
    providerHeader: values.providerHeader || 'X-Provider',
    defaultModel: values.defaultModel,
    responseFormat: values.responseFormat || 'OPENAI',
    stripClientAuth: values.stripClientAuth !== false,
    maxBodySize: toInt(values.maxBodySize, 1048576),
    providerApiKeyRefs,
    modelRouting,
    providerKeyPools,
    // Service bindings are generated by the route editor; preserve them when editing policy fields.
    ...(values.upstreams !== undefined ? { upstreams: values.upstreams } : {}),
    ...(values.fallbacks !== undefined ? { fallbacks: values.fallbacks } : {}),
    ...(values.modelMapping !== undefined ? { modelMapping: values.modelMapping } : {}),
    ...(values.providerBaseUrlOverride !== undefined ? { providerBaseUrlOverride: values.providerBaseUrlOverride } : {}),
  };
}

/** Build the backend config object from antd form values per strategy type. */
export function buildStrategyConfig(
  values: StrategyFormValues,
  strategyType: string,
): StrategyConfig {
  switch (strategyType) {
    case 'RATE_LIMITER':
      return {
        qps: toInt(values.qps, 100),
        burstCapacity: toInt(values.burstCapacity, 200),
        timeUnit: values.timeUnit || 'second',
        keyResolver: 'ip',
        keyType: 'combined',
      };
    case 'MULTI_DIM_RATE_LIMITER':
      return buildMultiDimConfig(values);
    case 'TOKEN_RATE_LIMITER':
      return buildTokenRateLimiterConfig(values);
    case 'AI_PROXY':
      return buildAiProxyConfig(values);
    case 'IP_FILTER':
      return {
        mode: values.mode || 'blacklist',
        ipList: splitCsv(values.ipList),
      };
    case 'TIMEOUT':
      return {
        connectTimeout: toInt(values.connectTimeout, 5000),
        responseTimeout: toInt(values.responseTimeout, 30000),
      };
    case 'CIRCUIT_BREAKER':
      return {
        failureRateThreshold: toFloat(values.failureRateThreshold, 50),
        slowCallDurationThreshold: toInt(values.slowCallDurationThreshold, 60000),
        slowCallRateThreshold: 80.0,
        waitDurationInOpenState: toInt(values.waitDurationInOpenState, 30000),
        slidingWindowSize: toInt(values.slidingWindowSize, 10),
        minimumNumberOfCalls: toInt(values.minimumNumberOfCalls, 5),
        automaticTransitionFromOpenToHalfOpenEnabled: true,
      };
    case 'AUTH':
      // Flat pass-through of all auth fields (JWT / API_KEY / OAUTH2 / BASIC / HMAC).
      return {
        authType: values.authType || 'JWT',
        secretKey: values.secretKey,
        jwtAlgorithm: values.jwtAlgorithm,
        jwtIssuer: values.jwtIssuer,
        jwtAudience: values.jwtAudience,
        jwtClockSkewSeconds: values.jwtClockSkewSeconds ? toInt(values.jwtClockSkewSeconds, 0) : undefined,
        apiKey: values.apiKey,
        apiKeyHeader: values.apiKeyHeader,
        apiKeyPrefix: values.apiKeyPrefix,
        apiKeyQueryParam: values.apiKeyQueryParam,
        clientId: values.clientId,
        clientSecret: values.clientSecret,
        tokenEndpoint: values.tokenEndpoint,
        requiredScopes: values.requiredScopes,
        basicUsername: values.basicUsername,
        basicPassword: values.basicPassword,
        realm: values.realm,
        passwordHashAlgorithm: values.passwordHashAlgorithm,
        basicUsersJson: values.basicUsersJson,
        accessKey: values.accessKey,
        signatureAlgorithm: values.signatureAlgorithm,
        clockSkewMinutes: values.clockSkewMinutes ? toInt(values.clockSkewMinutes, 0) : undefined,
        requireNonce: values.requireNonce,
        validateContentMd5: values.validateContentMd5,
        accessKeySecretsJson: values.accessKeySecretsJson,
      };
    case 'RETRY':
      return {
        maxAttempts: toInt(values.maxAttempts, 3),
        retryIntervalMs: toInt(values.retryIntervalMs, 1000),
        retryOnStatusCodes: splitCsv(values.retryOnStatusCodes).length > 0
          ? splitCsv(values.retryOnStatusCodes).map((s) => parseInt(s, 10))
          : [500, 502, 503, 504],
      };
    case 'CORS':
      return {
        allowedOrigins: splitCsv(values.allowedOrigins).length > 0 ? splitCsv(values.allowedOrigins) : ['*'],
        allowedMethods: splitCsv(values.allowedMethods).length > 0 ? splitCsv(values.allowedMethods) : ['GET', 'POST', 'PUT', 'DELETE'],
        allowedHeaders: splitCsv(values.allowedHeaders).length > 0 ? splitCsv(values.allowedHeaders) : ['*'],
        allowCredentials: values.allowCredentials || false,
        maxAge: toInt(values.maxAge, 3600),
      };
    case 'ACCESS_LOG':
      return {
        logLevel: values.logLevel || 'NORMAL',
        logRequestHeaders: values.logRequestHeaders !== false,
        logResponseHeaders: values.logResponseHeaders !== false,
        logRequestBody: values.logRequestBody || false,
        logResponseBody: values.logResponseBody || false,
        maxBodyLength: toInt(values.maxBodyLength, 1000),
        samplingRate: toInt(values.samplingRate, 100),
      };
    case 'HEADER_OP': {
      let addRequestHeaders: Record<string, unknown> = {};
      let addResponseHeaders: Record<string, unknown> = {};
      try {
        addRequestHeaders = values.addRequestHeaders ? JSON.parse(String(values.addRequestHeaders)) : {};
      } catch { /* keep empty on invalid JSON */ }
      try {
        addResponseHeaders = values.addResponseHeaders ? JSON.parse(String(values.addResponseHeaders)) : {};
      } catch { /* keep empty on invalid JSON */ }
      return {
        addRequestHeaders,
        addResponseHeaders,
        enableTraceId: values.enableTraceId !== false,
        traceIdHeader: values.traceIdHeader || 'X-Trace-Id',
      };
    }
    case 'CACHE':
      return {
        ttlSeconds: toInt(values.ttlSeconds, 60),
        maxSize: toInt(values.maxSize, 10000),
        cacheMethods: splitCsv(values.cacheMethods).length > 0 ? splitCsv(values.cacheMethods) : ['GET', 'HEAD'],
      };
    case 'SECURITY':
      return {
        enableSqlInjectionProtection: values.enableSqlInjectionProtection !== false,
        enableXssProtection: values.enableXssProtection !== false,
        mode: values.mode || 'BLOCK',
        checkParameters: values.checkParameters !== false,
        checkBody: values.checkBody !== false,
      };
    case 'API_VERSION':
      return {
        versionMode: values.versionMode || 'PATH',
        defaultVersion: values.defaultVersion || 'v1',
        versionHeader: values.versionHeader || 'X-API-Version',
        versionLocation: values.versionLocation || 'HEADER',
      };
    case 'REQUEST_TRANSFORM':
      return {
        enabled: true,
        maxBodySize: toInt(values.maxBodySize, 1048576),
        validateAfterTransform: values.validateAfterTransform || false,
        protocolTransform: values.protocolTransform || { enabled: false, sourceFormat: 'JSON', targetFormat: 'JSON' },
        fieldMapping: {
          enabled: Array.isArray(values.fieldMappingRules) && values.fieldMappingRules.length > 0,
          mappings: values.fieldMappingRules || [],
          removeFields: [],
          addFields: {},
        },
        dataMasking: {
          enabled: Array.isArray(values.dataMaskingRules) && values.dataMaskingRules.length > 0,
          rules: values.dataMaskingRules || [],
        },
      };
    case 'RESPONSE_TRANSFORM':
      return {
        enabled: true,
        maxBodySize: toInt(values.maxBodySize, 10485760),
        errorHandling: values.errorHandling || 'RETURN_ORIGINAL',
        protocolTransform: values.protocolTransform || { enabled: false, sourceFormat: 'AUTO', targetFormat: 'JSON' },
        fieldMapping: {
          enabled: Array.isArray(values.fieldMappingRules) && values.fieldMappingRules.length > 0,
          mappings: values.fieldMappingRules || [],
          removeFields: [],
          addFields: {},
        },
        dataMasking: {
          enabled: Array.isArray(values.dataMaskingRules) && values.dataMaskingRules.length > 0,
          rules: values.dataMaskingRules || [],
        },
      };
    case 'MOCK_RESPONSE': {
      const mockMode = values.mockMode || 'STATIC';
      const staticMock = values.staticMock as Record<string, unknown> | undefined;
      const templateMock = values.templateMock as Record<string, unknown> | undefined;
      const delay = values.delay as Record<string, unknown> | undefined;
      const errorSimulation = values.errorSimulation as Record<string, unknown> | undefined;
      return {
        enabled: true,
        mockMode,
        staticMock: mockMode === 'STATIC'
          ? {
            statusCode: staticMock?.statusCode || 200,
            contentType: staticMock?.contentType || 'application/json',
            body: staticMock?.body || '',
            bodyFile: staticMock?.bodyFile || '',
          }
          : { statusCode: 200, contentType: 'application/json' },
        dynamicMock: mockMode === 'DYNAMIC'
          ? {
            conditions: values.dynamicMockConditions || [],
            defaultResponse: { statusCode: 200, body: '{}' },
          }
          : { conditions: [], defaultResponse: { statusCode: 200, body: '{}' } },
        templateMock: mockMode === 'TEMPLATE'
          ? {
            templateEngine: templateMock?.templateEngine || 'HANDLEBARS',
            template: templateMock?.template || '',
            templateFile: templateMock?.templateFile || '',
          }
          : { templateEngine: 'HANDLEBARS' },
        delay: {
          enabled: delay?.enabled || false,
          fixedDelayMs: delay?.fixedDelayMs || 0,
          networkConditions: delay?.networkConditions || 'FAST',
        },
        errorSimulation: {
          enabled: errorSimulation?.enabled || false,
          errorRate: errorSimulation?.errorRate || 0,
          errorStatusCodes: typeof errorSimulation?.errorStatusCodes === 'string'
            ? splitCsv(errorSimulation.errorStatusCodes).map((s) => parseInt(s, 10))
            : [500, 503, 504],
        },
      };
    }
    default:
      // Unknown / new schema-driven types: keep everything except base fields.
      return Object.fromEntries(
        Object.entries(values)
          .filter(([key, value]) => !BASE_FORM_KEYS.has(key) && value !== undefined),
      );
  }
}

const windowSizeMsToTimeUnit = (windowSizeMs: number): string => {
  if (windowSizeMs >= 3600000) return 'hour';
  if (windowSizeMs >= 60000) return 'minute';
  return 'second';
};

const joinCsv = (value: unknown): string | undefined =>
  Array.isArray(value) ? value.join(', ') : undefined;

/** Reverse conversion: MULTI_DIM_RATE_LIMITER quotas -> dimensions Form.List. */
function multiDimToDimensions(config: StrategyConfig): MultiDimFormDimension[] {
  const dimensions: MultiDimFormDimension[] = [];
  const headerNames = (config.headerNames ?? {}) as Record<string, string>;
  const quotaToDim = (
    type: string,
    quota: QuotaConfig | undefined,
  ): void => {
    if (!quota?.enabled) return;
    dimensions.push({
      type,
      qps: quota.qps,
      burstCapacity: quota.burstCapacity,
      timeUnit: windowSizeMsToTimeUnit(quota.windowSizeMs || 1000),
    });
  };

  quotaToDim('GLOBAL', config.globalQuota as QuotaConfig | undefined);
  quotaToDim('IP', config.ipQuota as QuotaConfig | undefined);

  const userQuota = config.userQuota as QuotaConfig | undefined;
  if (userQuota?.enabled) {
    dimensions.push({
      type: 'USER',
      qps: userQuota.qps,
      burstCapacity: userQuota.burstCapacity,
      timeUnit: windowSizeMsToTimeUnit(userQuota.windowSizeMs || 1000),
      userIdSource: config.userIdSource as string | undefined,
      userIdHeader: headerNames.userIdHeader,
    });
  }

  const tenantQuota = config.tenantQuota as QuotaConfig | undefined;
  if (tenantQuota?.enabled) {
    const timeUnit = windowSizeMsToTimeUnit(tenantQuota.windowSizeMs || 1000);
    if (config.keySource === 'header') {
      dimensions.push({
        type: 'HEADER',
        qps: tenantQuota.qps,
        burstCapacity: tenantQuota.burstCapacity,
        timeUnit,
        headerName: headerNames.tenantIdHeader,
      });
    } else {
      dimensions.push({
        type: 'CLIENT',
        qps: tenantQuota.qps,
        burstCapacity: tenantQuota.burstCapacity,
        timeUnit,
        keySource: config.keySource as string | undefined,
        apiKeyHeader: headerNames.apiKeyHeader,
      });
    }
  }

  return dimensions;
}

/** Reverse conversion: TOKEN_RATE_LIMITER tenantQuotas map -> Form.List rows. */
function tokenQuotasToRows(config: StrategyConfig): TokenQuotaFormRow[] {
  const quotas = (config.tenantQuotas ?? {}) as Record<string, TokenQuotaFormRow>;
  return Object.entries(quotas).map(([tenantId, quota]) => ({
    tenantId,
    monthlyQuota: quota?.monthlyQuota,
    dailyQuota: quota?.dailyQuota,
    burstQuota: quota?.burstQuota,
    quotaPeriod: quota?.quotaPeriod,
  }));
}

/** Build antd form values from a strategy for the edit modal. */
export function strategyToFormValues(strategy: StrategyDefinition): StrategyFormValues {
  const config = strategy.config || {};
  const formValues: StrategyFormValues = {
    strategyName: strategy.strategyName,
    scope: strategy.scope,
    routeId: strategy.routeId,
    priority: strategy.priority,
    enabled: strategy.enabled,
    description: strategy.description,
    ...config,
    // Flatten array/object fields into form-friendly representations.
    ipList: joinCsv(config.ipList),
    retryOnStatusCodes: joinCsv(config.retryOnStatusCodes),
    allowedOrigins: joinCsv(config.allowedOrigins),
    allowedMethods: joinCsv(config.allowedMethods),
    allowedHeaders: joinCsv(config.allowedHeaders),
    cacheMethods: joinCsv(config.cacheMethods),
    addRequestHeaders: config.addRequestHeaders ? JSON.stringify(config.addRequestHeaders) : '',
    addResponseHeaders: config.addResponseHeaders ? JSON.stringify(config.addResponseHeaders) : '',
  };

  if (strategy.strategyType === 'MULTI_DIM_RATE_LIMITER') {
    formValues.dimensions = multiDimToDimensions(config);
    ['globalQuota', 'tenantQuota', 'userQuota', 'ipQuota', 'headerNames', 'keySource', 'userIdSource']
      .forEach((key) => { delete formValues[key]; });
  }

  if (strategy.strategyType === 'TOKEN_RATE_LIMITER') {
    formValues.tenantQuotas = tokenQuotasToRows(config);
  }

  if (strategy.strategyType === 'AI_PROXY') {
    const refs = (config.providerApiKeyRefs ?? {}) as Record<string, string>;
    formValues.providerCredentials = Object.entries(refs).map(([provider, credentialRef]) => ({ provider, credentialRef }));
    const routing = (config.modelRouting ?? {}) as Record<string, string>;
    formValues.modelRouting = Object.entries(routing).map(([model, provider]) => ({ model, provider }));
    const pools = (config.providerKeyPools ?? {}) as Record<string, { credentialRefs?: string[]; strategy?: string }>;
    formValues.keyPools = Object.entries(pools).map(([provider, pool]) => ({
      provider,
      strategy: pool.strategy || 'ROUND_ROBIN',
      credentialRefs: pool.credentialRefs ?? [],
    }));
    delete formValues.providerApiKeyRefs;
  }

  if (strategy.strategyType === 'REQUEST_TRANSFORM') {
    formValues.maxBodySize = config.maxBodySize || 1048576;
    formValues.validateAfterTransform = config.validateAfterTransform || false;
    formValues.protocolTransform = config.protocolTransform || { enabled: false, sourceFormat: 'JSON', targetFormat: 'JSON' };
    formValues.fieldMappingRules = (config.fieldMapping as Record<string, unknown>)?.mappings || [];
    formValues.dataMaskingRules = (config.dataMasking as Record<string, unknown>)?.rules || [];
    delete formValues.fieldMapping;
    delete formValues.dataMasking;
  }

  if (strategy.strategyType === 'RESPONSE_TRANSFORM') {
    formValues.maxBodySize = config.maxBodySize || 10485760;
    formValues.errorHandling = config.errorHandling || 'RETURN_ORIGINAL';
    formValues.protocolTransform = config.protocolTransform || { enabled: false, sourceFormat: 'AUTO', targetFormat: 'JSON' };
    formValues.fieldMappingRules = (config.fieldMapping as Record<string, unknown>)?.mappings || [];
    formValues.dataMaskingRules = (config.dataMasking as Record<string, unknown>)?.rules || [];
    delete formValues.fieldMapping;
    delete formValues.dataMasking;
  }

  if (strategy.strategyType === 'MOCK_RESPONSE') {
    formValues.mockMode = config.mockMode || 'STATIC';
    formValues.staticMock = config.staticMock || { statusCode: 200, contentType: 'application/json' };
    formValues.dynamicMockConditions = (config.dynamicMock as Record<string, unknown>)?.conditions || [];
    formValues.templateMock = config.templateMock || { templateEngine: 'HANDLEBARS' };
    formValues.delay = config.delay || { enabled: false };
    const errorSimulation = (config.errorSimulation || { enabled: false }) as Record<string, unknown>;
    if (Array.isArray(errorSimulation.errorStatusCodes)) {
      formValues.errorSimulation = {
        ...errorSimulation,
        errorStatusCodes: errorSimulation.errorStatusCodes.join(','),
      };
    } else {
      formValues.errorSimulation = errorSimulation;
    }
    delete formValues.dynamicMock;
  }

  return formValues;
}

// ============================================================
// Card display helpers
// ============================================================

/** Friendly quota names for MULTI_DIM_RATE_LIMITER config preview. */
export function getQuotaDisplayName(key: string, config: StrategyConfig): string {
  const quotaNameMap: Record<string, string> = {
    globalQuota: '全局限流',
    ipQuota: 'IP限流',
    userQuota: '用户限流',
  };
  if (key === 'tenantQuota') {
    return config.keySource === 'header' ? 'Header限流' : '客户端限流';
  }
  return quotaNameMap[key] || key;
}

/** Friendly rejectStrategy names for MULTI_DIM_RATE_LIMITER. */
export function getRejectStrategyDisplayName(strategy: string): string {
  const strategyNameMap: Record<string, string> = {
    FIRST_HIT: '首次拒绝',
    LATEST_HIT: '最后拒绝',
    RANDOM: '随机拒绝',
    ALL_HIT: '全部拒绝',
  };
  return strategyNameMap[strategy] || strategy;
}

/** Format headerNames object for the config preview tooltip. */
export function formatHeaderNames(headerNames: Record<string, string>): string {
  const parts: string[] = [];
  if (headerNames.tenantIdHeader) parts.push(`租户Header: ${headerNames.tenantIdHeader}`);
  if (headerNames.userIdHeader) parts.push(`用户Header: ${headerNames.userIdHeader}`);
  if (headerNames.apiKeyHeader) parts.push(`API Key Header: ${headerNames.apiKeyHeader}`);
  return parts.join(', ');
}

/** Format windowSizeMs for the config preview tooltip. */
export function formatWindowSize(windowSizeMs: number): string {
  if (windowSizeMs >= 1000) return `${windowSizeMs / 1000}秒`;
  return `${windowSizeMs}毫秒`;
}

/** Category labels for the type selection modal. */
export const CATEGORY_LABELS: Record<string, string> = {
  traffic: '流量控制',
  security: '安全防护',
  resilience: '弹性容错',
  transform: '数据转换',
  observability: '可观测性',
  misc: '其他',
  ai: 'AI 能力',
};

/** CSS class mapping per strategy type category (StrategiesPage.premium.css). */
export function getStrategyTypeTagClass(category?: string): string {
  const categoryColors: Record<string, string> = {
    traffic: 'rate-limiter',
    resilience: 'timeout-retry',
    security: 'enabled-global',
    transform: 'enabled-global',
    observability: 'enabled-global',
    misc: 'enabled-global',
    ai: 'enabled-global',
  };
  return (category && categoryColors[category]) || 'rate-limiter';
}
