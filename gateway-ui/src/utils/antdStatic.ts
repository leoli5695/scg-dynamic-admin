/**
 * Context-aware antd static API bridge.
 *
 * antd warns when `message` / `Modal.confirm` etc. are called statically
 * because they cannot consume the ConfigProvider context (theme, locale).
 * This module exposes `message` and `modal` proxies backed by instances from
 * `App.useApp()` (injected via <AntdStaticBridge /> mounted inside <App>),
 * falling back to the static APIs if called before the bridge mounts.
 */
import React, { useEffect } from 'react';
import { App } from 'antd';
import type { MessageInstance } from 'antd/es/message/interface';
import type { HookAPI as ModalHookAPI } from 'antd/es/modal/useModal';

let messageInstance: MessageInstance | null = null;
let modalInstance: ModalHookAPI | null = null;

/** Message proxy — prefers the context instance, falls back to static API. */
export const message = new Proxy({} as MessageInstance, {
  get: (_target, prop) => {
    const staticImpl = (App as unknown as Record<string | symbol, unknown>).message as MessageInstance;
    const source = messageInstance ?? staticImpl;
    return (source as unknown as Record<string | symbol, unknown>)[prop];
  },
});

/** Modal hook-API proxy (confirm/info/success/error/warning). */
export const modal = new Proxy({} as ModalHookAPI, {
  get: (_target, prop) => {
    const staticImpl = (App as unknown as Record<string | symbol, unknown>).modal as ModalHookAPI;
    const source = modalInstance ?? staticImpl;
    return (source as unknown as Record<string | symbol, unknown>)[prop];
  },
});

/** Mount once inside antd <App> to inject context-aware instances. */
export const AntdStaticBridge: React.FC = () => {
  const { message: ctxMessage, modal: ctxModal } = App.useApp();
  useEffect(() => {
    messageInstance = ctxMessage;
    modalInstance = ctxModal;
  }, [ctxMessage, ctxModal]);
  return null;
};
