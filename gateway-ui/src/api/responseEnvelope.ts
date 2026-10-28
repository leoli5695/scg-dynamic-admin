/** The backend omits data when a successful nullable result is empty. */
export function unwrapApiResponse<T>(
  body: unknown,
  onError: (message: string, code: number, payload: unknown) => never,
): T {
  if (typeof body === 'object' && body !== null && !Array.isArray(body)) {
    const envelope = body as Record<string, unknown>;
    if (typeof envelope.code === 'number'
      && ('data' in envelope || envelope.code !== 200
        || (typeof envelope.message === 'string' && typeof envelope.timestamp === 'number'))) {
      if (envelope.code !== 200) {
        onError(typeof envelope.message === 'string' ? envelope.message : `Business error ${envelope.code}`, envelope.code, body);
      }
      return (envelope.data ?? null) as T;
    }
  }
  return body as T;
}
