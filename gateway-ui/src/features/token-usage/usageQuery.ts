export interface UsageQuery {
  instanceId: string;
  routeId: string;
  unresolvedOnly: boolean;
  from: string;
  to: string;
  tenantId: string;
  model: string;
  granularity: 'hour' | 'day';
  page: number;
  size: number;
}

/** UI and API filters use a local wall clock, not an ISO UTC instant. */
export function usageLocalTime(date: Date): string {
  return new Date(date.getTime() - date.getTimezoneOffset() * 60000).toISOString().slice(0, 19);
}

export function isUsageTime(value: string | null): value is string {
  if (!value || !/^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}$/.test(value) || value.startsWith('0000')) return false;
  const date = new Date(value);
  return Number.isFinite(date.getTime()) && usageLocalTime(date) === value;
}

export function defaultUsageRange(now = new Date()) {
  return { from: usageLocalTime(new Date(now.getTime() - 86400000)), to: usageLocalTime(now) };
}

/** URL is the source of truth, including browser back/forward and route deep links. */
export function readUsageQuery(params: URLSearchParams, scopeInstanceId?: string, now = new Date()): UsageQuery {
  const from = params.get('from');
  const to = params.get('to');
  const range = isUsageTime(from) && isUsageTime(to) && from <= to ? { from, to } : defaultUsageRange(now);
  const rawPage = params.get('page') ?? '0';
  const page = /^\d+$/.test(rawPage) ? Number(rawPage) : NaN;
  const rawSize = params.get('size') ?? '20';
  return {
    instanceId: scopeInstanceId ?? params.get('instanceId') ?? '',
    routeId: params.get('routeId')?.trim() ?? '',
    unresolvedOnly: params.get('unresolvedOnly') === 'true',
    ...range,
    tenantId: params.get('tenantId') ?? '',
    model: params.get('model')?.trim() ?? '',
    granularity: params.get('granularity') === 'day' ? 'day' : 'hour',
    page: Number.isSafeInteger(page) && page >= 0 && page <= 1000000 ? page : 0,
    size: ['20', '50', '100'].includes(rawSize) ? Number(rawSize) : 20,
  };
}

/** Replace (not push) this result on entry: defaults become fixed absolute times. */
export function normalizeUsageQuery(params: URLSearchParams, scopeInstanceId?: string, now = new Date()): URLSearchParams {
  const next = new URLSearchParams(params);
  Object.entries(readUsageQuery(params, scopeInstanceId, now)).forEach(([key, value]) => {
    if (value === '' || value === false) next.delete(key);
    else next.set(key, String(value));
  });
  return next;
}

export function updateUsageQuery(
  params: URLSearchParams,
  patch: Partial<UsageQuery>,
  scopeInstanceId?: string,
  now = new Date(),
): URLSearchParams {
  const next = normalizeUsageQuery(params, scopeInstanceId, now);
  const previous = readUsageQuery(next, scopeInstanceId, now);
  Object.entries(patch).forEach(([key, value]) => {
    if (value === undefined) return;
    if (value === '' || value === false) next.delete(key);
    else next.set(key, String(value));
  });
  const result = normalizeUsageQuery(next, scopeInstanceId, now);
  const current = readUsageQuery(result, scopeInstanceId, now);
  // A filter/size change and its page reset form one history entry. No-op submissions keep the page.
  if ((Object.keys(previous) as (keyof UsageQuery)[]).some(key => key !== 'page' && previous[key] !== current[key])) {
    result.set('page', '0');
  }
  return result;
}
