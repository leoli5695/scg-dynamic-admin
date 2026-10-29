import React, { useState } from 'react';
import { Alert, Button, Card, Space, Spin, Switch, Typography } from 'antd';
import { useTranslation } from 'react-i18next';
import { updateSystemSettings, type SystemSettings } from '../../api/systemSettings';
import { useSystemSettings } from './SystemSettingsContext';
import { useAuth } from '../auth/AuthContext';
import { message } from '../../utils/antdStatic';

const FIELDS: { key: keyof SystemSettings; label: string; hint: string }[] = [
  { key: 'stressTestEnabled', label: 'settings.stress_test', hint: 'settings.stress_test_hint' },
  { key: 'middlewareEnabled', label: 'settings.middleware', hint: 'settings.middleware_hint' },
  { key: 'pluginsEnabled', label: 'settings.plugins', hint: 'settings.plugins_hint' },
  { key: 'kubernetesEnabled', label: 'settings.kubernetes', hint: 'settings.kubernetes_hint' },
];

const SystemSettingsPage: React.FC = () => {
  const { t } = useTranslation();
  const { settings, ready, hasClusterDeployment, refresh } = useSystemSettings();
  const { user } = useAuth();
  const canWrite = user?.role === 'ADMIN';
  // Unedited fields follow the server value; edits live in the draft until saved.
  const [draft, setDraft] = useState<Partial<SystemSettings>>({});
  const [saving, setSaving] = useState(false);

  if (!ready) {
    return <div style={{ padding: 48, textAlign: 'center' }}><Spin /></div>;
  }

  const save = async () => {
    setSaving(true);
    try {
      await updateSystemSettings(draft);
      message.success(t('settings.saved'));
      setDraft({});
      refresh();
    } catch (error) {
      message.error(error instanceof Error ? error.message : t('settings.save_failed'));
    } finally {
      setSaving(false);
    }
  };

  return <div className="console-workspace-page" style={{ display: 'grid', gap: 16 }}>
    <Typography.Title level={3} style={{ margin: 0 }}>{t('settings.title')}</Typography.Title>
    <Typography.Text type="secondary">{t('settings.subtitle')}</Typography.Text>
    {!canWrite && <Alert type="info" showIcon message={t('settings.readonly')} />}
    {settings?.kubernetesEnabled && !hasClusterDeployment && (
      <Alert type="warning" showIcon message={t('settings.kubernetes_no_cluster')} />
    )}
    <Card>
      <Space direction="vertical" size="large" style={{ width: '100%' }}>
        {FIELDS.map((field) => <div key={field.key}>
          <Space align="start">
            <Switch
              checked={(draft[field.key] ?? settings?.[field.key]) === true}
              disabled={!canWrite}
              onChange={(checked) => setDraft((previous) => ({ ...previous, [field.key]: checked }))}
            />
            <div>
              <Typography.Text strong>{t(field.label)}</Typography.Text>
              <div><Typography.Text type="secondary">{t(field.hint)}</Typography.Text></div>
            </div>
          </Space>
        </div>)}
      </Space>
      <div style={{ marginTop: 24 }}>
        <Button type="primary" loading={saving} disabled={!canWrite || !ready} onClick={save}>
          {t('settings.save')}
        </Button>
      </div>
      {settings?.updatedBy && (
        <Typography.Paragraph type="secondary" style={{ marginTop: 12, marginBottom: 0 }}>
          {t('settings.updated_by', { operator: settings.updatedBy })}
        </Typography.Paragraph>
      )}
    </Card>
  </div>;
};

export default SystemSettingsPage;