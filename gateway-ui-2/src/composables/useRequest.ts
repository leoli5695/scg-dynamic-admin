/**
 * Data-fetching composables built on AbortController.
 *
 * Faithful Vue 3 port of the legacy hooks/useRequest.ts. The returned contract is
 * identical — { data, loading, error, run, mutate } — except the three state fields
 * are refs instead of React state. ~29 call sites depend on this shape.
 *
 * Differences from the React version (all intentional, none change the contract):
 *  - No stale-closure guard needed: the fetcher is invoked fresh on every run, so it
 *    always reads the latest reactive values.
 *  - `deps` are Vue watch sources (refs / getters / reactive objects) instead of a
 *    value array.
 *  - Cleanup uses onScopeDispose so it works in components and standalone effect scopes.
 */
import {
  ref,
  watch,
  getCurrentScope,
  onScopeDispose,
  toValue,
  type Ref,
  type WatchSource,
  type MaybeRefOrGetter,
} from 'vue'
import { ApiError, isAbortError } from '@/api/http'
import logger from '@/utils/logger'

export interface UseRequestOptions<T> {
  /** Watch sources that trigger a re-fetch when the request is immediate. */
  deps?: WatchSource[]
  /** Fire the request immediately on setup / dependency change. Default: true. */
  immediate?: boolean
  /** Called with the error on failure (abort errors are skipped). */
  onError?: (error: ApiError) => void
  /** Called with the payload on success. */
  onSuccess?: (data: T) => void
}

export interface UseRequestResult<T> {
  data: Ref<T | undefined>
  loading: Ref<boolean>
  error: Ref<ApiError | undefined>
  /** Manually (re)run the request. */
  run: () => void
  /** Replace data locally without refetching. */
  mutate: (updater: T | ((prev: T | undefined) => T)) => void
}

/**
 * Declarative async request composable.
 * Aborts the in-flight request when dependencies change or on scope dispose, and
 * guards against state updates after dispose via a monotonic sequence id.
 */
export function useRequest<T>(
  fetcher: (signal: AbortSignal) => Promise<T>,
  options: UseRequestOptions<T> = {},
): UseRequestResult<T> {
  const { deps = [], immediate = true, onError, onSuccess } = options

  const data = ref(undefined) as Ref<T | undefined>
  const loading = ref(immediate)
  const error = ref(undefined) as Ref<ApiError | undefined>

  let abortController: AbortController | null = null
  let seq = 0
  let active = true

  const run = (): void => {
    abortController?.abort()
    const controller = new AbortController()
    abortController = controller
    const currentSeq = ++seq

    loading.value = true
    error.value = undefined

    fetcher(controller.signal)
      .then((result) => {
        if (!active || currentSeq !== seq) return
        data.value = result
        loading.value = false
        onSuccess?.(result)
      })
      .catch((err: unknown) => {
        if (!active || currentSeq !== seq) return
        if (isAbortError(err)) return
        const apiError = err instanceof ApiError
          ? err
          : new ApiError(err instanceof Error ? err.message : String(err), 0)
        logger.error('Request failed:', apiError.message)
        error.value = apiError
        loading.value = false
        onError?.(apiError)
      })
  }

  const mutate = (updater: T | ((prev: T | undefined) => T)): void => {
    data.value = typeof updater === 'function'
      ? (updater as (prev: T | undefined) => T)(data.value)
      : updater
  }

  if (immediate) {
    run()
  }

  if (deps.length) {
    watch(deps, () => {
      if (immediate) run()
    }, { flush: 'post' })
  }

  if (getCurrentScope()) {
    onScopeDispose(() => {
      active = false
      abortController?.abort()
    })
  }

  return { data, loading, error, run, mutate }
}

export interface UsePollingOptions {
  /** Polling interval in milliseconds (may be reactive). */
  intervalMs: MaybeRefOrGetter<number>
  /** Pause polling (e.g. when a modal is open). May be reactive. Default: false. */
  paused?: MaybeRefOrGetter<boolean>
  /** Watch sources that restart the polling loop. */
  deps?: WatchSource[]
}

/**
 * Interval-based polling composable wrapping useRequest.
 * Skips a tick when the previous request is still in flight, and pauses while the
 * document is hidden.
 */
export function usePolling<T>(
  fetcher: (signal: AbortSignal) => Promise<T>,
  options: UsePollingOptions & UseRequestOptions<T>,
): UseRequestResult<T> {
  const { intervalMs, paused = false, deps = [], ...rest } = options
  const result = useRequest(fetcher, { ...rest, deps })

  let inFlight = false
  watch(result.loading, (v) => { inFlight = v }, { immediate: true })

  let timer: ReturnType<typeof setInterval> | undefined
  const clear = (): void => {
    if (timer !== undefined) {
      clearInterval(timer)
      timer = undefined
    }
  }
  const setupTimer = (): void => {
    clear()
    if (toValue(paused)) return
    timer = setInterval(() => {
      if (!inFlight && !document.hidden && !toValue(paused)) {
        result.run()
      }
    }, toValue(intervalMs))
  }

  watch(
    [() => toValue(paused), () => toValue(intervalMs), ...deps],
    () => setupTimer(),
    { immediate: true, flush: 'post' },
  )

  if (getCurrentScope()) {
    onScopeDispose(clear)
  }

  return result
}
