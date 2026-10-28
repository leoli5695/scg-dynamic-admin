export function copilotSessionStorageKey(operator: string, instanceId: string): string {
  return `copilot.sessionId:${encodeURIComponent(operator)}:${encodeURIComponent(instanceId)}`;
}

export function createCopilotSessionId(): string {
  // getRandomValues also works on HTTP intranet deployments where randomUUID is unavailable.
  return Array.from(crypto.getRandomValues(new Uint8Array(16)), byte => byte.toString(16).padStart(2, '0')).join('');
}
