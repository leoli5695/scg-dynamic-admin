import React from 'react';
import { Button, Result, Spin } from 'antd';
import { useNavigate } from 'react-router-dom';
import { useTranslation } from 'react-i18next';
import { useSystemSettings } from './SystemSettingsContext';
import type { SystemSettings } from '../../api/systemSettings';

type FeatureKey = keyof Pick<SystemSettings,
  'stressTestEnabled' | 'middlewareEnabled' | 'pluginsEnabled' | 'kubernetesEnabled'>;

/** Keeps legacy URLs valid while making a switched-off feature visibly unavailable. */
export default function FeatureGate({ feature, requiresCluster = false, children }: {
  feature: FeatureKey;
  requiresCluster?: boolean;
  children: React.ReactNode;
}) {
  const { settings, ready, hasClusterDeployment } = useSystemSettings();
  const { t } = useTranslation();
  const navigate = useNavigate();

  if (!ready) {
    return <div style={{ padding: 48, textAlign: 'center' }}><Spin /></div>;
  }
  // A settings outage must not hide pages; backend write guards still apply.
  if (!settings) return <>{children}</>;
  const enabled = settings[feature] && (!requiresCluster || hasClusterDeployment);
  if (enabled) return <>{children}</>;
  return <Result
    status="info"
    title={t('settings.feature_disabled_title')}
    subTitle={requiresCluster && settings[feature] && !hasClusterDeployment
      ? t('settings.kubernetes_no_cluster')
      : t('settings.feature_disabled_hint')}
    extra={<Button type="primary" onClick={() => navigate('/settings')}>{t('settings.open')}</Button>}
  />;
}