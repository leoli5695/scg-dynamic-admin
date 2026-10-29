import { parseServerTime } from '../../utils/serverTime.ts';
import { defaultUsageRange, isUsageTime, normalizeUsageQuery, usageLocalTime } from '../token-usage/usageQuery.ts';
import { requestJourneyUrl } from './links.ts';
import { readRequestQuery, requestLogLocalTime } from './requestQuery.ts';

const instancePath = (instanceId: string) => `/instances/${encodeURIComponent(instanceId)}`;
const isLocalTarget = (value: string | null): value is string => !!value
  && !value.includes('\\') && !value.includes('#')
  && !Array.from(value).some(char => char.charCodeAt(0) <= 32 || char.charCodeAt(0) === 127);
const hasExactPath = (value: string, path: string) => value === path || value.startsWith(`${path}?`);
const queryOf = (value: string) => new URLSearchParams(value.includes('?') ? value.slice(value.indexOf('?') + 1) : '');
const hasCompatibleScope = (params: URLSearchParams, instanceId: string) =>
  params.getAll('instanceId').every(value => !value || value === instanceId);

/** Global usage can contain this instance's event only when unscoped or filtered to this instance. */
export function isUsageReturnUrl(instanceId: string, returnTo: string | null): returnTo is string {
  return !!instanceId && isLocalTarget(returnTo)
    && (hasExactPath(returnTo, `${instancePath(instanceId)}/token_usage`) || hasExactPath(returnTo, '/token-usage'))
    && hasCompatibleScope(queryOf(returnTo), instanceId);
}

export function requestCenterReturnUrl(instanceId: string, returnTo: string | null): string {
  const fallback = `${instancePath(instanceId)}/requests`;
  if (!isLocalTarget(returnTo)) return fallback;
  if (hasExactPath(returnTo, fallback) && hasCompatibleScope(queryOf(returnTo), instanceId)) return returnTo;
  return isUsageReturnUrl(instanceId, returnTo) ? returnTo : fallback;
}

/** Usage may return only to a request detail in its effective instance, never a different console route. */
export function usageSourceReturnUrl(instanceId: string, returnTo: string | null): string | undefined {
  if (!instanceId || !isLocalTarget(returnTo)) return undefined;
  const path = returnTo.split('?')[0];
  const prefix = `${instancePath(instanceId)}/trace/`;
  if (!path.startsWith(prefix)) return undefined;
  const segment = path.slice(prefix.length);
  try {
    const traceId = decodeURIComponent(segment);
    if (!traceId || traceId === '.' || traceId === '..' || !isLocalTarget(traceId)
      || encodeURIComponent(traceId) !== segment) return undefined;
  } catch { return undefined; }
  return hasCompatibleScope(queryOf(returnTo), instanceId) ? returnTo : undefined;
}

export function usageRequestJourneyUrl(instanceId: string, traceId: string, routeId: string | null | undefined, usageUrl: string): string {
  const target = requestJourneyUrl(instanceId, traceId, routeId);
  return isUsageReturnUrl(instanceId, usageUrl)
    ? `${target}${target.includes('?') ? '&' : '?'}${new URLSearchParams({ returnTo: usageUrl })}`
    : target;
}

interface JourneyUsageContext {
  instanceId: string;
  traceId: string;
  routeId: string;
  params: URLSearchParams;
  traceTime?: string | number | null;
  accessLogTime?: string | null;
  usageRequestTime?: string | number | null;
}

export function journeyUsageUrl(context: JourneyUsageContext, now = new Date()): string {
  const { instanceId, traceId, routeId, params } = context;
  const safeOrigin = requestCenterReturnUrl(instanceId, params.get('returnTo'));
  const originParams = queryOf(safeOrigin);
  // Request search URLs already contain local wall clocks (including source=logs); do not ISO-convert them.
  const requestQuery = readRequestQuery(originParams, now);
  let range = originParams.has('from') && originParams.has('to') && requestQuery.error !== 'range'
    && isUsageTime(requestQuery.from) && isUsageTime(requestQuery.to)
    ? { from: requestQuery.from, to: requestQuery.to } : undefined;
  if (!range) {
    const times = [
      parseServerTime(context.traceTime),
      new Date(context.accessLogTime ? requestLogLocalTime(context.accessLogTime) : NaN),
      parseServerTime(context.usageRequestTime),
    ];
    const time = times.find(value => Number.isFinite(value.getTime()));
    range = time ? {
      from: usageLocalTime(new Date(time.getTime() - 1800000)),
      to: usageLocalTime(new Date(time.getTime() + 1800000)),
    } : defaultUsageRange(now);
  }
  const detailParams = new URLSearchParams(params);
  if (detailParams.has('returnTo')) detailParams.set('returnTo', safeOrigin);
  const detailPath = requestJourneyUrl(instanceId, traceId);
  const returnTo = `${detailPath}${detailParams.size ? `?${detailParams}` : ''}`;
  const query = normalizeUsageQuery(new URLSearchParams({ ...range, routeId, returnTo }), instanceId, now);
  return `${instancePath(instanceId)}/token_usage?${query}`;
}
