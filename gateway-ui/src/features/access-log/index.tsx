/**
 * Access log page — configuration form + log viewer tabs. The form instance
 * lives here so the header Save/Reset/Refresh actions can act on it; the
 * effective deploy mode (uncommitted edit wins) is passed to the viewer.
 */
import React, { useState } from 'react';
import { Badge, Button, Form, Space, Spin, Tabs, Typography } from 'antd';
import { message } from '../../utils/antdStatic';
import type { TabsProps } from 'antd';
import {
  EyeOutlined, FileTextOutlined, ReloadOutlined, SaveOutlined, SettingOutlined,
} from '@ant-design/icons';
import { useTranslation } from 'react-i18next';
import { useRequest } from '../../hooks/useRequest';
import {
  getAccessLogConfig, getDeployModes, resetAccessLogConfig, saveAccessLogConfig,
} from '../../api/accessLog';
import type { AccessLogConfig, DeployMode, OutputTarget } from '../../types/accessLog';
import { useInstanceDetail } from '../instance-detail/InstanceDetailContext';
import { DEFAULT_SENSITIVE_FIELDS } from './constants';
import ConfigTab from './components/ConfigTab';
import LogViewerTab from './components/LogViewerTab';
import './access-log.css';

const { Title } = Typography;

/** Infer the frontend-only output target from the persisted config. */
const inferOutputTarget = (config: AccessLogConfig): OutputTarget => {
  if (config.logToConsole && config.logDirectory) return 'both';
  if (config.logToConsole) return 'stdout';
  return 'file';
};

const AccessLogPage: React.FC = () => {
  const { instance } = useInstanceDetail();
  const instanceId = instance.instanceId;
  const { t } = useTranslation();
  const [form] = Form.useForm();

  const [outputTarget, setOutputTarget] = useState<OutputTarget>('file');
  const [showFileConfig, setShowFileConfig] = useState(true);
  const [saving, setSaving] = useState(false);
  const [logTotal, setLogTotal] = useState(0);

  const configRequest = useRequest(
    (signal) => getAccessLogConfig({ instanceId }, { signal }),
    {
      deps: [instanceId],
      onError: () => message.error(t('access_log.load_failed')),
      onSuccess: (data) => {
        if (!data) return;
        form.setFieldsValue(data);
        const target = inferOutputTarget(data);
        setOutputTarget(target);
        setShowFileConfig(target !== 'stdout');
      },
    },
  );

  const deployModesRequest = useRequest((signal) => getDeployModes({ signal }));
  const deployModes = deployModesRequest.data ?? [];

  // Uncommitted deploy mode edits drive the viewer and collection guide.
  const watchedDeployMode = Form.useWatch('deployMode', form);
  const savedConfig = configRequest.data ?? null;
  const effectiveDeployMode = watchedDeployMode || savedConfig?.deployMode || 'LOCAL';

  const handleDeployModeChange = (mode: DeployMode) => {
    const selectedMode = deployModes.find((m) => m.mode === mode);
    if (selectedMode) {
      form.setFieldsValue({ logDirectory: selectedMode.defaultPath });
    }

    let recommendedTarget: OutputTarget;
    let showFile = true;
    switch (mode) {
      case 'K8S':
        // Cloud-native best practice: stdout only, file options disabled.
        recommendedTarget = 'stdout';
        showFile = false;
        form.setFieldsValue({ logToConsole: true });
        message.info(t('access_log.k8s_auto_switch_msg'));
        break;
      case 'DOCKER':
        recommendedTarget = 'both';
        form.setFieldsValue({ logToConsole: true });
        break;
      case 'LOCAL':
        recommendedTarget = 'file';
        form.setFieldsValue({ logToConsole: false });
        break;
      default:
        // CUSTOM: keep the current selection.
        recommendedTarget = outputTarget;
        break;
    }
    setOutputTarget(recommendedTarget);
    setShowFileConfig(showFile);
  };

  const handleOutputTargetChange = (target: OutputTarget) => {
    setOutputTarget(target);
    form.setFieldsValue({ logToConsole: target !== 'file' });
    setShowFileConfig(target !== 'stdout');
  };

  const handleSave = async () => {
    try {
      setSaving(true);
      const values = await form.validateFields();
      const payload: Partial<AccessLogConfig> & { instanceId: string } = {
        ...values,
        logToConsole: outputTarget !== 'file',
        instanceId,
        sensitiveFields: savedConfig?.sensitiveFields ?? DEFAULT_SENSITIVE_FIELDS,
      };
      await saveAccessLogConfig(payload);
      message.success(t('access_log.save_success'));
      configRequest.run();
    } catch (e) {
      const err = e as { message?: string };
      message.error(err.message || t('access_log.save_failed'));
    } finally {
      setSaving(false);
    }
  };

  const handleReset = async () => {
    try {
      await resetAccessLogConfig({ instanceId });
      message.success(t('access_log.reset_success'));
      configRequest.run();
    } catch (e) {
      const err = e as { message?: string };
      message.error(err.message || t('access_log.reset_failed'));
    }
  };

  if (configRequest.loading && !configRequest.data) {
    return <div style={{ textAlign: 'center', padding: 100 }}><Spin size="large" /></div>;
  }

  const tabItems: TabsProps['items'] = [
    {
      key: 'config',
      label: (
        <Space>
          <SettingOutlined />
          {t('access_log.config_tab')}
        </Space>
      ),
      children: (
        <ConfigTab
          form={form}
          config={savedConfig}
          deployModes={deployModes}
          outputTarget={outputTarget}
          showFileConfig={showFileConfig}
          onDeployModeChange={handleDeployModeChange}
          onOutputTargetChange={handleOutputTargetChange}
        />
      ),
    },
    {
      key: 'logs',
      label: (
        <Space>
          <EyeOutlined />
          {t('access_log.viewer_tab')}
          {logTotal > 0 && <Badge count={logTotal} style={{ backgroundColor: '#52c41a' }} />}
        </Space>
      ),
      children: (
        <LogViewerTab
          instanceId={instanceId}
          deployMode={effectiveDeployMode}
          onTotalChange={setLogTotal}
        />
      ),
    },
  ];

  return (
    <div className="access-log-config-page">
      <div className="page-header">
        <div className="header-title">
          <FileTextOutlined className="header-icon" />
          <Title level={3} style={{ margin: 0 }}>{t('access_log.title')}</Title>
        </div>
        <Space className="header-actions">
          <Button icon={<ReloadOutlined />} onClick={configRequest.run}>
            {t('common.refresh')}
          </Button>
          <Button onClick={handleReset}>
            {t('access_log.reset')}
          </Button>
          <Button type="primary" icon={<SaveOutlined />} onClick={handleSave} loading={saving}>
            {t('common.save')}
          </Button>
        </Space>
      </div>

      <Tabs defaultActiveKey="config" items={tabItems} />
    </div>
  );
};

export default AccessLogPage;
