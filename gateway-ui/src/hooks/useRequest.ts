/**
 * Data fetching hooks built on AbortController (no deprecated CancelToken).
 */
import { useCallback, useEffect, useRef, useState } from 'react';
import { ApiError, isAbortError } from '../api/http';
import logger from '../utils/logger';

export interface UseRequestOptions<T> {
  /** Dependencies that trigger a re-fetch when the request is immediate. */
  deps?: unknown[];
  /** Fire the request on mount / dependency change. Default: true. */
  immediate?: boolean;
  /** Called with the error on failure (abort errors are skipped). */
  onError?: (error: ApiError) => void;
  /** Called with the payload on success. */
  onSuccess?: (data: T) => void;
}

export interface UseRequestResult<T> {
  data: T | undefined;
  loading: boolean;
  error: ApiError | undefined;
  /** Manually (re)run the request. */
  run: () => void;
  /** Replace data locally without refetching. */
  mutate: (updater: T | ((prev: T | undefined) => T)) => void;
}

/**
 * Declarative async request hook.
 * Aborts the in-flight request when dependencies change or on unmount,
 * and guards against state updates after unmount.
 */
export function useRequest<T>(
  fetcher: (signal: AbortSignal) => Promise<T>,
  options?: UseRequestOptions<T>,
): UseRequestResult<T> {
  const { deps = [], immediate = true, onError, onSuccess } = options ?? {};
  const [data, setData] = useState<T | undefined>(undefined);
  const [loading, setLoading] = useState(immediate);
  const [error, setError] = useState<ApiError | undefined>(undefined);

  const fetcherRef = useRef(fetcher);
  const callbacksRef = useRef({ onError, onSuccess });
  // Keep the latest fetcher/callbacks without retriggering the request.
  useEffect(() => {
    fetcherRef.current = fetcher;
    callbacksRef.current = { onError, onSuccess };
  });
  const abortRef = useRef<AbortController | null>(null);
  const mountedRef = useRef(true);
  // Monotonic id so stale responses never overwrite fresh ones.
  const seqRef = useRef(0);

  const run = useCallback(() => {
    abortRef.current?.abort();
    const controller = new AbortController();
    abortRef.current = controller;
    const seq = ++seqRef.current;

    setLoading(true);
    setError(undefined);

    fetcherRef.current(controller.signal)
      .then((result) => {
        if (!mountedRef.current || seq !== seqRef.current) return;
        setData(result);
        setLoading(false);
        callbacksRef.current.onSuccess?.(result);
      })
      .catch((err: unknown) => {
        if (!mountedRef.current || seq !== seqRef.current) return;
        if (isAbortError(err)) return;
        const apiError = err instanceof ApiError
          ? err
          : new ApiError(err instanceof Error ? err.message : String(err), 0);
        logger.error('Request failed:', apiError.message);
        setError(apiError);
        setLoading(false);
        callbacksRef.current.onError?.(apiError);
      });
  }, []);

  useEffect(() => {
    mountedRef.current = true;
    return () => {
      mountedRef.current = false;
      abortRef.current?.abort();
    };
  }, []);

  useEffect(() => {
    if (immediate) {
      // Intentional: this effect IS the declarative fetch trigger.
      // eslint-disable-next-line react-hooks/set-state-in-effect
      run();
    }
    return () => {
      abortRef.current?.abort();
    };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [immediate, run, ...deps]);

  const mutate = useCallback((updater: T | ((prev: T | undefined) => T)) => {
    setData((prev) => (typeof updater === 'function'
      ? (updater as (p: T | undefined) => T)(prev)
      : updater));
  }, []);

  return { data, loading, error, run, mutate };
}

export interface UsePollingOptions {
  /** Polling interval in milliseconds. */
  intervalMs: number;
  /** Pause polling (e.g. when a modal is open or data is stale). Default: false. */
  paused?: boolean;
  /** Dependencies that restart the polling loop. */
  deps?: unknown[];
}

/**
 * Interval-based polling hook wrapping useRequest.
 * Skips a tick when the previous request is still in flight.
 */
export function usePolling<T>(
  fetcher: (signal: AbortSignal) => Promise<T>,
  options: UsePollingOptions & UseRequestOptions<T>,
): UseRequestResult<T> {
  const { intervalMs, paused = false, deps = [], ...rest } = options;
  const result = useRequest(fetcher, { ...rest, deps });
  const inFlightRef = useRef(false);

  useEffect(() => {
    inFlightRef.current = result.loading;
  }, [result.loading]);

  useEffect(() => {
    if (paused) return undefined;
    const timer = window.setInterval(() => {
      if (!inFlightRef.current && !document.hidden) {
        result.run();
      }
    }, intervalMs);
    return () => window.clearInterval(timer);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [intervalMs, paused, result.run, ...deps]);

  return result;
}
