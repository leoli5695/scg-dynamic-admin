/**
 * Global feedback service (replaces the legacy utils/antdStatic bridge).
 *
 * ant-design-vue warns when `message` / `Modal.confirm` / `notification` are used
 * statically because they cannot consume the ConfigProvider theme context. This
 * module exposes stable `message` / `modal` / `notification` proxies backed by the
 * context-aware instances from `App.useApp()` (registered by <AppBridge />), and
 * falls back to the static APIs when called before the bridge mounts.
 *
 * Call sites import { message } / { modal } from here — same shape as the legacy
 * antdStatic module, so the 57 legacy call sites map 1:1 (only the import path changes).
 */
import {
  message as staticMessage,
  notification as staticNotification,
  Modal as staticModal,
} from 'ant-design-vue'

type MessageInstance = typeof staticMessage
type NotificationInstance = typeof staticNotification
type ModalInstance = typeof staticModal

let messageInstance: MessageInstance | null = null
let notificationInstance: NotificationInstance | null = null
let modalInstance: ModalInstance | null = null

/** Register the context-aware instances (called once by <AppBridge />). */
export function registerFeedbackInstances(inst: {
  message: MessageInstance
  notification: NotificationInstance
  modal: ModalInstance
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
function delegate<T extends object>(getContext: () => T | null, fallback: T): T {
  return new Proxy({} as T, {
    get(_target, prop) {
      const source = (getContext() ?? fallback) as unknown as Record<string | symbol, unknown>
      const value = source[prop]
      return typeof value === 'function'
        ? (value as (...args: unknown[]) => unknown).bind(source)
        : value
    },
  })
}

export const message = delegate<MessageInstance>(() => messageInstance, staticMessage)
export const notification = delegate<NotificationInstance>(
  () => notificationInstance,
  staticNotification,
)
export const modal = delegate<ModalInstance>(() => modalInstance, staticModal)
