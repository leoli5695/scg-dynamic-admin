interface UsageQuery {
  instanceId: string;
  routeId: string;
  unresolvedOnly: boolean;
}

/** URL is the source of truth, including browser back/forward and route deep links. */
export function readUsageQuery(params: URLSearchParams, scopeInstanceId?: string): UsageQuery {
  return {
    instanceId: scopeInstanceId ?? params.get('instanceId') ?? '',
    routeId: params.get('routeId') ?? '',
    unresolvedOnly: params.get('unresolvedOnly') === 'true',
  };
}

export function updateUsageQuery(
  params: URLSearchParams,
  patch: Partial<UsageQuery>,
  scopeInstanceId?: string,
): URLSearchParams {
  const next = new URLSearchParams(params);
  const values = scopeInstanceId === undefined ? patch : { ...patch, instanceId: scopeInstanceId };
  Object.entries(values).forEach(([key, value]) => {
    if (value === '' || value === false) next.delete(key);
    else next.set(key, String(value));
  });
  return next;
}
