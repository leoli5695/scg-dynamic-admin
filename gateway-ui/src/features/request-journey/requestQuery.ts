export function readRequestQuery(params: URLSearchParams, now = new Date()) {
  const localTime = (date: Date) => new Date(date.getTime() - date.getTimezoneOffset() * 60000).toISOString().slice(0, 19);
  const from = params.get('from') ?? localTime(new Date(now.getTime() - 3600000));
  const to = params.get('to') ?? localTime(now);
  const rawStatus = params.get('statusCode') ?? '';
  const rawPage = params.get('page') ?? '0';
  const statusCode = rawStatus ? Number(rawStatus) : undefined;
  const page = Number(rawPage);
  const validDate = (value: string) => /^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}$/.test(value)
    && Number.isFinite(new Date(value).getTime()) && localTime(new Date(value)) === value;
  const error = !validDate(from) || !validDate(to) || new Date(from) > new Date(to)
    ? 'range'
    : rawStatus && (!/^\d{3}$/.test(rawStatus) || statusCode! < 100 || statusCode! > 599)
      ? 'status'
      : !/^\d+$/.test(rawPage) || !Number.isSafeInteger(page) || page > 1000000
        ? 'page' : undefined;
  return {
    source: params.get('source') === 'logs' ? 'logs' as const : 'traces' as const,
    from, to, routeId: params.get('routeId')?.trim() || undefined,
    traceId: params.get('traceId')?.trim() || undefined,
    statusCode, page, error,
  };
}

/** The log collector stores the UTC clock without an offset. Keep UI/URL times local,
 * but send UTC instants to the legacy log API (which parses their UTC wall clock). */
export function requestLogRange(from: string, to: string) {
  return { startTime: new Date(from).toISOString(), endTime: new Date(to).toISOString() };
}

export function requestLogLocalTime(timestamp: string) {
  const instant = new Date(/(Z|[+-]\d{2}:?\d{2})$/i.test(timestamp) ? timestamp : `${timestamp}Z`);
  return Number.isNaN(instant.getTime()) ? '—'
    : new Date(instant.getTime() - instant.getTimezoneOffset() * 60000).toISOString().slice(0, 19);
}
