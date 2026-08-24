/**
 * Stress test API client (gateway-admin StressTestController, /api/stress-test).
 * All endpoints return the ApiResponse envelope except /export (raw blob).
 */
import { httpClient } from './http';
import type {
  SharedStressTestReport,
  StartStressTestResult,
  StopStressTestResult,
  StressTest,
  StressTestAnalysisResult,
  StressTestConfig,
  StressTestLiveStatus,
  StressTestMetrics,
  StressTestShareLink,
} from '../types/stressTest';

/** Start a configured stress test. */
export function startStressTest(config: StressTestConfig, instanceId: string): Promise<StartStressTestResult> {
  return httpClient.post<StartStressTestResult>('/api/stress-test/start', config, { params: { instanceId } });
}

/** Start a quick test against the instance itself (all params as query). */
export function quickStressTest(params: {
  instanceId: string;
  requests: number;
  concurrent: number;
  path?: string;
}): Promise<StartStressTestResult> {
  return httpClient.post<StartStressTestResult>('/api/stress-test/quick', undefined, { params });
}

/** Get a single test record. */
export function getStressTest(testId: number): Promise<StressTest> {
  return httpClient.get<StressTest>(`/api/stress-test/${testId}`);
}

/** List all tests of an instance. */
export function getStressTestsByInstance(instanceId: string): Promise<StressTest[]> {
  return httpClient.get<StressTest[]>(`/api/stress-test/instance/${instanceId}`);
}

/** Live status snapshot of a running test (no timeline). */
export function getStressTestStatus(testId: number): Promise<StressTestLiveStatus> {
  return httpClient.get<StressTestLiveStatus>(`/api/stress-test/${testId}/status`);
}

/** Timeline + summary metrics of a test. */
export function getStressTestMetrics(testId: number): Promise<StressTestMetrics> {
  return httpClient.get<StressTestMetrics>(`/api/stress-test/${testId}/metrics`);
}

/** Stop a running test. */
export function stopStressTest(testId: number): Promise<StopStressTestResult> {
  return httpClient.post<StopStressTestResult>(`/api/stress-test/${testId}/stop`);
}

/** AI analysis of a finished test (markdown). */
export function getStressTestAnalysis(testId: number): Promise<StressTestAnalysisResult> {
  return httpClient.get<StressTestAnalysisResult>(`/api/stress-test/${testId}/analyze`);
}

/** Delete a test record. */
export function deleteStressTest(testId: number): Promise<void> {
  return httpClient.delete<void>(`/api/stress-test/${testId}`);
}

/** Export the markdown report as a raw file download. */
export function exportStressTestReport(testId: number): Promise<Blob> {
  return httpClient.get<Blob>(`/api/stress-test/${testId}/export`, {
    params: { format: 'markdown' },
    responseType: 'blob',
  });
}

/** Create a public share link. expiresIn in hours; omit for permanent. */
export function createStressTestShareLink(testId: number, expiresIn?: number): Promise<StressTestShareLink> {
  return httpClient.post<StressTestShareLink>(`/api/stress-test/${testId}/share`, undefined, {
    params: expiresIn ? { expiresIn } : {},
  });
}

/** Load a shared report (public, no auth). */
export function getSharedStressTestReport(shareId: string): Promise<SharedStressTestReport> {
  return httpClient.get<SharedStressTestReport>(`/api/stress-test/share/${shareId}`);
}

/** Revoke a share link. */
export function deleteStressTestShareLink(shareId: string): Promise<void> {
  return httpClient.delete<void>(`/api/stress-test/share/${shareId}`);
}
