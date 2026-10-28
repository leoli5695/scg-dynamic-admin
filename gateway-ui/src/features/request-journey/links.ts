export const requestJourneyUrl = (instanceId: string, traceId: string, routeId?: string | null) =>
  `/instances/${encodeURIComponent(instanceId)}/trace/${encodeURIComponent(traceId)}${routeId ? `?routeId=${encodeURIComponent(routeId)}` : ''}`;

export const routeConfigurationUrl = (instanceId: string, routeId: string) =>
  `/instances/${encodeURIComponent(instanceId)}/routes?highlight=${encodeURIComponent(routeId)}`;

export function requestDiagnosis(status: number | undefined, usage: { usageStatus?: string; settlementStatus?: string }[], ai: boolean) {
  if (status === 401 || status === 403) return 'authentication';
  if (status === 429) return 'rateLimit';
  if (status === 404) return 'routing';
  if (status !== undefined && status >= 500) return 'upstream';
  if (status !== undefined && status >= 400) return 'request';
  if (usage.some(row => ['FAILED', 'PENDING'].includes(row.settlementStatus ?? ''))) return 'settlement';
  if (usage.some(row => ['UNKNOWN', 'ESTIMATED'].includes(row.usageStatus ?? ''))) return 'usageUnknown';
  if (status === undefined) return usage.length ? 'traceMissing' : 'pending';
  if (ai && !usage.length) return 'usageMissing';
  return 'ok';
}
