import type { StrategyDefinition } from '../../types/strategy';

export const WORKBENCH_TYPES = ['AUTH', 'RATE_LIMITER', 'MULTI_DIM_RATE_LIMITER', 'TIMEOUT', 'CIRCUIT_BREAKER', 'TOKEN_RATE_LIMITER'];

export function strategyRouteIds(strategy: StrategyDefinition): string[] {
  return [...new Set([strategy.routeId, ...(strategy.routeIds ?? [])].filter((id): id is string => !!id))];
}

export function effectiveStrategy(strategies: StrategyDefinition[], routeId: string, type: string) {
  return strategies.filter(s => s.enabled && s.strategyType === type
    && (s.scope === 'GLOBAL' || (s.scope === 'ROUTE' && strategyRouteIds(s).includes(routeId))))
    .sort((a, b) => Number(b.scope === 'ROUTE') - Number(a.scope === 'ROUTE')
      || b.priority - a.priority || (a.strategyId < b.strategyId ? -1 : a.strategyId > b.strategyId ? 1 : 0))[0];
}

const DISPLAY_FIELDS: Record<string, string[]> = {
  AUTH: ['authType'],
  RATE_LIMITER: ['enabled', 'qps', 'burstCapacity', 'timeUnit', 'keyResolver', 'keyType'],
  MULTI_DIM_RATE_LIMITER: ['enabled', 'globalQuota', 'tenantQuota', 'userQuota', 'ipQuota', 'rejectStrategy'],
  TIMEOUT: ['connectTimeout', 'responseTimeout'],
  CIRCUIT_BREAKER: ['enabled', 'failureRateThreshold', 'slowCallRateThreshold', 'slowCallDurationThreshold', 'waitDurationInOpenState', 'slidingWindowSize', 'minimumNumberOfCalls'],
  TOKEN_RATE_LIMITER: ['enabled', 'defaultMonthlyQuota', 'defaultDailyQuota', 'quotaPeriod', 'windowLimitEnabled', 'tokenPerMinute', 'tokenPerHour', 'globalTokenPerMinute', 'preDeductStrategy', 'tenantQuotas'],
};
const NESTED_FIELDS = new Set(['enabled', 'qps', 'burstCapacity', 'windowSizeMs', 'monthlyQuota', 'dailyQuota', 'burstQuota', 'quotaPeriod', 'windowTokenPerMinute', 'windowTokenPerHour']);

export function strategySummary(strategy: StrategyDefinition): Record<string, unknown> {
  return Object.fromEntries((DISPLAY_FIELDS[strategy.strategyType] ?? []).filter(key => strategy.config[key] !== undefined)
    .map(key => {
      const value = strategy.config[key];
      if (value === null || typeof value !== 'object') return [key, value];
      const quota = (row: unknown) => row && typeof row === 'object'
        ? Object.fromEntries(Object.entries(row).filter(([name, v]) => NESTED_FIELDS.has(name) && (v === null || typeof v !== 'object')))
        : {};
      return [key, key === 'tenantQuotas'
        ? Object.fromEntries(Object.entries(value).map(([tenant, row]) => [tenant, quota(row)])) : quota(value)];
    }));
}
