import httpClient, { type RequestOptions } from './http';
import type { RequestTrace } from '../types/trace';
import type { TokenUsageEvent } from './tokenUsage';

export interface RequestJourney {
  instanceId: string;
  traceId: string;
  trace: RequestTrace | null;
  usage: TokenUsageEvent[];
}
export const getRequestJourney = (instanceId: string, traceId: string, options?: RequestOptions) =>
  httpClient.get<RequestJourney>(`/api/request-journeys/${encodeURIComponent(traceId)}`,
    { ...options, params: { instanceId } });
