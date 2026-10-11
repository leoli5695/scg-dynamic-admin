/**
 * Global feedback service (replaces the legacy utils/antdStatic bridge).
 *
 * ant-design-vue warns when `message` / `Modal.confirm` / `notification` are used
 * statically because they cannot consume the ConfigProvider theme context. This
 * module exposes stable `message` / `modal` / `notification` proxies backed by the
 * context-aware instances from `App.useApp()` (registered by <AppBridge />), and
 * falls back to the static APIs when called before the bridge mounts.
 *
 * The context instances (MessageInstance) and the static APIs (MessageApi) have
 * slightly different surfaces, so both are held behind a permissive method-map type.
 * Call sites import { message } / { modal } from here — same shape as the legacy
 * antdStatic module, so the legacy call sites map 1:1 (only the import path changes).
 */
import {
  message as staticMessage,
  notification as staticNotification,
  Modal as staticModal,
} from 'ant-design-vue'

/** Permissive method-map covering both static and context feedback instances. */
export interface FeedbackApi {
  // eslint-disable-next-line @typescript-eslint/no-explicit-any
  [method: string]: (...args: any[]) => any
}

let messageInstance: FeedbackApi | null = null
let notificationInstance: FeedbackApi | null = null
let modalInstance: FeedbackApi | null = null

/** Register the context-aware instances (called once by <AppBridge />). */
export function registerFeedbackInstances(inst: {
  message: FeedbackApi
  notification: FeedbackApi
  modal: FeedbackApi
}): void {
  messageInstance = inst.message
  notificationInstance = inst.notification
  modalInstance = inst.modal
}

/**
 * Build a stable proxy that delegates every property access to the context
 * instance when available, otherwise to the static API. Methods are bound to
 * their source so `this` stays correct.
 */
function delegate(getContext: () => FeedbackApi | null, fallback: FeedbackApi): FeedbackApi {
  return new Proxy({} as FeedbackApi, {
    get(_target, prop) {
      const source = (getContext() ?? fallback) as unknown as Record<string | symbol, unknown>
      const value = source[prop]
      return typeof value === 'function'
        ? (value as (...args: unknown[]) => unknown).bind(source)
        : value
    },
  })
}

export const message = delegate(() => messageInstance, staticMessage as unknown as FeedbackApi)
export const notification = delegate(
  () => notificationInstance,
  staticNotification as unknown as FeedbackApi,
)
export const modal = delegate(() => modalInstance, staticModal as unknown as FeedbackApi)
