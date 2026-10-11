import { inject, reactive, type InjectionKey } from 'vue'
import type { GatewayInstance } from '@/types/instance'

/**
 * Instance detail context — the layout loads the instance once (by UUID) and
 * shares it with all tabs via provide/inject, replacing per-tab prop drilling.
 * Implemented as a reactive object so tabs see updates after refresh/scale.
 */
export interface InstanceDetailContextValue {
  instance: GatewayInstance
  /** Global nacos address from GET /api/instances/config (fallback for links). */
  globalNacosAddr: string
  /** Re-fetch the instance (after start/stop/scale...). */
  refresh: () => void
}

export const INSTANCE_DETAIL_KEY: InjectionKey<InstanceDetailContextValue> = Symbol('instance-detail')

/** Create the (initially empty) context object; the layout fills it as data loads. */
export function createInstanceDetailContext(refresh: () => void): InstanceDetailContextValue {
  return reactive({
    instance: null as unknown as GatewayInstance,
    globalNacosAddr: '127.0.0.1:8848',
    refresh,
  }) as InstanceDetailContextValue
}

export function useInstanceDetail(): InstanceDetailContextValue {
  const ctx = inject(INSTANCE_DETAIL_KEY)
  if (!ctx) {
    throw new Error('useInstanceDetail must be used within InstanceDetailLayout')
  }
  return ctx
}
