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

function targetKind(instanceId: string, value: string): 'requests' | 'usage' | 'detail' | undefined {
  if (hasExactPath(value, `${instancePath(instanceId)}/requests`)) return 'requests';
  if (hasExactPath(value, `${instancePath(instanceId)}/token_usage`) || hasExactPath(value, '/token-usage')) return 'usage';
  const prefix = `${instancePath(instanceId)}/trace/`;
  const path = value.split('?')[0];
  if (!path.startsWith(prefix)) return undefined;
  const segment = path.slice(prefix.length);
  try {
    const id = decodeURIComponent(segment);
    if (id && id !== '.' && id !== '..' && isLocalTarget(id) && encodeURIComponent(id) === segment) return 'detail';
  } catch { /* Malformed URL encoding is not a navigation target. */ }
  return undefined;
}

/** Allow usage -> detail -> original search/usage, but validate every layer and bound depth. */
function safeTarget(instanceId: string, value: string | null, depth = 2): value is string {
  if (!instanceId || !isLocalTarget(value)) return false;
  const kind = targetKind(instanceId, value);
  const params = queryOf(value);
  if (!kind || !hasCompatibleScope(params, instanceId)) return false;
  const nested = params.getAll('returnTo');
  if (!nested.length) return true;
  if (nested.length !== 1 || depth === 0 || kind === 'requests') return false;
  const nestedKind = targetKind(instanceId, nested[0]);
  if (kind === 'usage' ? nestedKind !== 'detail' : nestedKind !== 'requests' && nestedKind !== 'usage') return false;
  return safeTarget(instanceId, nested[0], depth - 1);
}

/** Global usage can contain this instance's event only when unscoped or filtered to this instance. */
export function isUsageReturnUrl(instanceId: string, returnTo: string | null): returnTo is string {
  return safeTarget(instanceId, returnTo) && targetKind(instanceId, returnTo) === 'usage';
}

export function requestCenterReturnUrl(instanceId: string, returnTo: string | null): string {
  const fallback = `${instancePath(instanceId)}/requests`;
  if (!safeTarget(instanceId, returnTo)) return fallback;
  if (targetKind(instanceId, returnTo) === 'requests') return returnTo;
  return isUsageReturnUrl(instanceId, returnTo) ? returnTo : fallback;
}

/** Usage may return only to a request detail in its effective instance, never a different console route. */
export function usageSourceReturnUrl(instanceId: string, returnTo: string | null): string | undefined {
  return safeTarget(instanceId, returnTo, 3) && targetKind(instanceId, returnTo) === 'detail' ? returnTo : undefined;
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
  // This route's usage view already exists. Reuse it, including its exact filters,
  // page and source link, instead of creating another returnTo wrapper on every visit.
  if (isUsageReturnUrl(instanceId, safeOrigin)
      && hasExactPath(safeOrigin, `${instancePath(instanceId)}/token_usage`)
      && originParams.get('routeId') === routeId) return safeOrigin;
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
  if (detailParams.has('returnTo')) {
    // A new route/global-to-scoped transition needs a new pair, not the old pair's history.
    if (isUsageReturnUrl(instanceId, safeOrigin)) originParams.delete('returnTo');
    const origin = `${safeOrigin.split('?')[0]}${originParams.size ? `?${originParams}` : ''}`;
    detailParams.set('returnTo', origin);
  }
  const detailPath = requestJourneyUrl(instanceId, traceId);
  const returnTo = `${detailPath}${detailParams.size ? `?${detailParams}` : ''}`;
  const query = normalizeUsageQuery(new URLSearchParams({ ...range, routeId, returnTo }), instanceId, now);
  return `${instancePath(instanceId)}/token_usage?${query}`;
}
