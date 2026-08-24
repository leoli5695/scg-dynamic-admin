/**
 * Instance detail context.
 * The layout loads the instance once (by UUID) and shares it with all tabs,
 * replacing per-tab prop drilling.
 */
import React, { createContext, useContext } from 'react';
import type { GatewayInstance } from '../../types/instance';

export interface InstanceDetailContextValue {
  instance: GatewayInstance;
  /** Global nacos address from GET /api/instances/config (fallback for links). */
  globalNacosAddr: string;
  /** Re-fetch the instance (after start/stop/scale...). */
  refresh: () => void;
}

const InstanceDetailContext = createContext<InstanceDetailContextValue | null>(null);

export const InstanceDetailProvider: React.FC<{
  value: InstanceDetailContextValue;
  children: React.ReactNode;
}> = ({ value, children }) => (
  <InstanceDetailContext.Provider value={value}>
    {children}
  </InstanceDetailContext.Provider>
);

// Hook co-located with its provider by design.
// eslint-disable-next-line react-refresh/only-export-components
export const useInstanceDetail = (): InstanceDetailContextValue => {
  const ctx = useContext(InstanceDetailContext);
  if (!ctx) {
    throw new Error('useInstanceDetail must be used within InstanceDetailProvider');
  }
  return ctx;
};
