import React, { createContext, useCallback, useContext, useMemo } from 'react';
import { useRequest } from '../../hooks/useRequest';
import { getSystemSettings, type SystemSettings } from '../../api/systemSettings';
import { listInstances } from '../../api/instances';

interface SystemSettingsContextValue {
  settings: SystemSettings | null;
  /** True once both switches and instances have been resolved at least once. */
  ready: boolean;
  hasClusterDeployment: boolean;
  refresh: () => void;
}

const SystemSettingsContext = createContext<SystemSettingsContextValue | null>(null);

export const SystemSettingsProvider: React.FC<{ children: React.ReactNode }> = ({ children }) => {
  const { data: settings, loading: settingsLoading, run: runSettings } =
    useRequest((signal) => getSystemSettings({ signal }), {});
  // Kubernetes is only meaningful when the console manages at least one cluster-based instance.
  const { data: instances, loading: instancesLoading, run: runInstances } =
    useRequest((signal) => listInstances({ signal }), {});
  const hasClusterDeployment = (instances ?? []).some((instance) => instance.clusterId != null);
  const refresh = useCallback(() => {
    runSettings();
    runInstances();
  }, [runSettings, runInstances]);
  const value = useMemo<SystemSettingsContextValue>(() => ({
    settings: settings ?? null,
    ready: !settingsLoading && !instancesLoading,
    hasClusterDeployment,
    refresh,
  }), [settings, settingsLoading, instancesLoading, hasClusterDeployment, refresh]);

  return <SystemSettingsContext.Provider value={value}>{children}</SystemSettingsContext.Provider>;
};

// Hook co-located with its provider by design.
// eslint-disable-next-line react-refresh/only-export-components
export const useSystemSettings = (): SystemSettingsContextValue => {
  const context = useContext(SystemSettingsContext);
  if (!context) throw new Error('useSystemSettings must be used within SystemSettingsProvider');
  return context;
};