import httpClient, { type RequestOptions } from './http';

export interface DebugAuthOption {
  policyId: string;
  policyName: string;
  authType: string;
  bound: boolean;
  automatic: boolean;
  credentials: { id: string; label: string }[];
}

export const getDebugAuthOptions = (instanceId: string, routeId: string, options?: RequestOptions) =>
  httpClient.get<DebugAuthOption[]>(`/api/routes/${encodeURIComponent(routeId)}/debug/auth`, { ...options, params: { instanceId } });


export interface DebugEvent {
  status?: number;
  headers?: Record<string, string[]>;
  url?: string;
  firstByteMs?: number;
  text?: string;
  durationMs?: number;
  truncated?: boolean;
  message?: string;
}

export async function debugRoute(
  instanceId: string, routeId: string,
  request: { method: string; path: string; headers: Record<string, string>; body: string; timeoutSeconds: number;
    authPolicyId?: string; credential?: string; credentialId?: string },
  signal: AbortSignal, onEvent: (name: string, event: DebugEvent) => void,
) {
  const token = localStorage.getItem('token');
  const response = await fetch(`${import.meta.env.VITE_API_BASE_URL || ''}/api/routes/${encodeURIComponent(routeId)}/debug?instanceId=${encodeURIComponent(instanceId)}`, {
    method: 'POST', signal,
    headers: { 'Content-Type': 'application/json', Accept: 'text/event-stream', ...(token ? { Authorization: `Bearer ${token}` } : {}) },
    body: JSON.stringify(request),
  });
  if (!response.ok) {
    const detail = await response.json().catch(() => ({}));
    throw new Error(detail.message || detail.detail || `HTTP ${response.status}`);
  }
  if (!response.body) throw new Error('Empty debug response');
  const reader = response.body.getReader();
  const decoder = new TextDecoder();
  let buffer = '';
  let finished = false;
  try {
    while (true) {
      const { value, done } = await reader.read();
      buffer += decoder.decode(value, { stream: !done }).replace(/\r/g, '');
      let boundary: number;
      while ((boundary = buffer.indexOf('\n\n')) !== -1) {
        const block = buffer.slice(0, boundary);
        buffer = buffer.slice(boundary + 2);
        const name = block.split('\n').find((line) => line.startsWith('event:'))?.slice(6).trim();
        const data = block.split('\n').filter((line) => line.startsWith('data:')).map((line) => line.slice(5).trimStart()).join('\n');
        if (name && data) {
          onEvent(name, JSON.parse(data) as DebugEvent);
          if (name === 'complete' || name === 'failure') finished = true;
        }
      }
      if (done) break;
    }
    if (!finished && !signal.aborted) throw new Error('Debug connection ended before completion');
  } finally { await reader.cancel().catch(() => {}); reader.releaseLock(); }
}
