/**
 * Fluent Bit collection guide — per-deploy-mode tabs with copyable config
 * templates, shown inside the configuration tab's "advanced" card.
 */
import React from 'react';
import { Alert, Button, Space, Tabs } from 'antd';
import { message } from '../../../utils/antdStatic';
import {
  CheckCircleOutlined, CloudServerOutlined, ContainerOutlined,
  CopyOutlined, DesktopOutlined, FolderOutlined,
} from '@ant-design/icons';
import { useTranslation } from 'react-i18next';
import type { DeployMode } from '../../../types/accessLog';
import {
  FLUENT_BIT_CUSTOM_CONFIG, FLUENT_BIT_DOCKER_CONFIG, FLUENT_BIT_K8S_CONFIG,
  FLUENT_BIT_K8S_CONFIGMAP, FLUENT_BIT_LOCAL_CONFIG,
} from '../constants';

interface CollectionGuideProps {
  activeDeployMode: DeployMode | string;
}

const CodeBlock: React.FC<{ children: string; extraPaddingRight?: boolean }> = ({
  children, extraPaddingRight,
}) => (
  <div style={{ background: '#1e1e1e', padding: 12, borderRadius: 4, paddingRight: extraPaddingRight ? 100 : undefined }}>
    <pre style={{ color: '#d4d4d4', fontSize: 12, margin: 0, whiteSpace: 'pre-wrap' }}>{children}</pre>
  </div>
);

const CollectionGuide: React.FC<CollectionGuideProps> = ({ activeDeployMode }) => {
  const { t } = useTranslation();

  const handleCopyConfig = (configText: string) => {
    navigator.clipboard.writeText(configText);
    message.success(t('access_log.config_copied'));
  };

  return (
    <Tabs activeKey={activeDeployMode || 'LOCAL'} items={[
      {
        key: 'K8S',
        label: <Space><CloudServerOutlined />{t('access_log.collection_k8s_title')}</Space>,
        children: (
          <div>
            <Alert
              type="success"
              showIcon
              icon={<CheckCircleOutlined />}
              message={t('access_log.collection_k8s_msg')}
              description={t('access_log.collection_k8s_desc')}
              style={{ marginBottom: 12 }}
            />
            <div style={{ marginBottom: 8 }}>
              <Space>
                <Button
                  size="small"
                  type="primary"
                  icon={<CopyOutlined />}
                  onClick={() => handleCopyConfig(FLUENT_BIT_K8S_CONFIG)}
                >
                  {t('access_log.collection_copy_config')}
                </Button>
                <Button
                  size="small"
                  icon={<CopyOutlined />}
                  onClick={() => handleCopyConfig(FLUENT_BIT_K8S_CONFIGMAP)}
                >
                  {t('access_log.collection_copy_configmap')}
                </Button>
              </Space>
            </div>
            <CodeBlock>{FLUENT_BIT_K8S_CONFIG}</CodeBlock>
          </div>
        ),
      },
      {
        key: 'DOCKER',
        label: <Space><ContainerOutlined />{t('access_log.collection_docker_title')}</Space>,
        children: (
          <div>
            <Alert
              type="info"
              showIcon
              message={t('access_log.collection_docker_msg')}
              description={
                <ul style={{ margin: 0, paddingLeft: 20 }}>
                  <li><strong>{t('access_log.collection_docker_sidecar')}</strong></li>
                  <li><strong>{t('access_log.collection_docker_host')}</strong></li>
                </ul>
              }
              style={{ marginBottom: 12 }}
            />
            <div style={{ position: 'relative' }}>
              <Button
                size="small"
                icon={<CopyOutlined />}
                style={{ position: 'absolute', top: 8, right: 8 }}
                onClick={() => handleCopyConfig(FLUENT_BIT_DOCKER_CONFIG)}
              >
                {t('access_log.collection_copy')}
              </Button>
              <CodeBlock extraPaddingRight>{FLUENT_BIT_DOCKER_CONFIG}</CodeBlock>
            </div>
          </div>
        ),
      },
      {
        key: 'LOCAL',
        label: <Space><DesktopOutlined />{t('access_log.collection_local_title')}</Space>,
        children: (
          <div>
            <Alert
              type="info"
              showIcon
              message={t('access_log.collection_local_msg')}
              description={t('access_log.collection_local_desc')}
              style={{ marginBottom: 12 }}
            />
            <div style={{ position: 'relative' }}>
              <Button
                size="small"
                icon={<CopyOutlined />}
                style={{ position: 'absolute', top: 8, right: 8 }}
                onClick={() => handleCopyConfig(FLUENT_BIT_LOCAL_CONFIG)}
              >
                {t('access_log.collection_copy')}
              </Button>
              <CodeBlock extraPaddingRight>{FLUENT_BIT_LOCAL_CONFIG}</CodeBlock>
            </div>
          </div>
        ),
      },
      {
        key: 'CUSTOM',
        label: <Space><FolderOutlined />{t('access_log.collection_custom_title')}</Space>,
        children: (
          <div>
            <Alert
              type="warning"
              showIcon
              message={t('access_log.collection_custom_msg')}
              description={t('access_log.collection_custom_desc')}
              style={{ marginBottom: 12 }}
            />
            <div style={{ position: 'relative' }}>
              <Button
                size="small"
                icon={<CopyOutlined />}
                style={{ position: 'absolute', top: 8, right: 8 }}
                onClick={() => handleCopyConfig(FLUENT_BIT_CUSTOM_CONFIG)}
              >
                {t('access_log.collection_copy')}
              </Button>
              <CodeBlock extraPaddingRight>{FLUENT_BIT_CUSTOM_CONFIG}</CodeBlock>
            </div>
          </div>
        ),
      },
    ]} />
  );
};

export default CollectionGuide;
