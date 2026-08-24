/**
 * AI analysis API.
 * Endpoints aligned with controller/AiConfigController.java (/api/ai).
 */
import httpClient, { RequestOptions } from './http';
import type {
  AiTimeRangeAnalysisRequest,
  AiTimeRangeAnalysisResult,
} from '../types/copilot';

const BASE = '/api/ai';

/** POST /analyze/timerange — time-range metrics analysis (envelope response). */
export const analyzeTimeRange = (
  body: AiTimeRangeAnalysisRequest,
  options?: RequestOptions,
) => httpClient.post<AiTimeRangeAnalysisResult>(`${BASE}/analyze/timerange`, body, options);
