import React from 'react';
import {
  Button, Card, Divider, Form, Input, Modal, Radio, Space, Tabs, Tag
} from 'antd';
import { CheckCircleOutlined, SettingOutlined } from '@ant-design/icons';
import { useTranslation } from 'react-i18next';
import type { AiProvider } from '../../../types/copilot';

interface ConfigModalProps {
  open: boolean;
  providers: AiProvider[];
  selectedProvider: string;
  selectedModel: string;
  models: string[];
  apiKey: string;
  baseUrl: string;
  validated: boolean;
  validating: boolean;
  onProviderChange: (provider: string) => void;
  onModelChange: (model: string) => void;
  onApiKeyChange: (apiKey: string) => void;
  onBaseUrlChange: (baseUrl: string) => void;
  onValidate: () => void;
  onSave: () => void;
  onClose: () => void;
}

const ConfigModal: React.FC<ConfigModalProps> = ({
  open,
  providers,
  selectedProvider,
  selectedModel,
  models,
  apiKey,
  baseUrl,
  validated,
  validating,
  onProviderChange,
  onModelChange,
  onApiKeyChange,
  onBaseUrlChange,
  onValidate,
  onSave,
  onClose,
}) => {
  const { t } = useTranslation();

  const domesticProviders = providers.filter(p => p.region === 'DOMESTIC');
  const overseasProviders = providers.filter(p => p.region === 'OVERSEAS');

  const renderProviderCard = (p: AiProvider) => (
    <Card
      key={p.provider}
      hoverable
      size="small"
      style={{
        marginBottom: 8,
        borderColor: selectedProvider === p.provider ? '#1890ff' : undefined,
      }}
      onClick={() => onProviderChange(p.provider)}
    >
      <Space>
        <Tag color={p.isValid ? 'success' : 'default'}>{p.providerName}</Tag>
        {p.isValid && <CheckCircleOutlined style={{ color: '#52c41a' }} />}
      </Space>
    </Card>
  );

  return (
    <Modal
      title={
        <Space>
          <SettingOutlined />
          {t('copilot.model_config')}
        </Space>
      }
      open={open}
      onCancel={onClose}
      onOk={onSave}
      okText={t('common.save')}
      cancelText={t('common.cancel')}
      width={600}
    >
      <Tabs
        items={[
          {
            key: 'domestic',
            label: t('ai.domestic_models'),
            children: (
              <div style={{ maxHeight: 200, overflow: 'auto' }}>
                {domesticProviders.map(renderProviderCard)}
              </div>
            ),
          },
          {
            key: 'overseas',
            label: t('ai.overseas_models'),
            children: (
              <div style={{ maxHeight: 200, overflow: 'auto' }}>
                {overseasProviders.map(renderProviderCard)}
              </div>
            ),
          },
        ]}
      />

      {selectedProvider && (
        <div style={{ marginTop: 16 }}>
          <Divider>{t('ai.config')}</Divider>

          <Form layout="vertical">
            <Form.Item label={t('ai.select_model')}>
              <Radio.Group
                value={selectedModel}
                onChange={e => onModelChange(e.target.value)}
                style={{ width: '100%' }}
              >
                <Space direction="vertical" style={{ width: '100%' }}>
                  {models.map(m => (
                    <Radio key={m} value={m}>
                      {m}
                    </Radio>
                  ))}
                </Space>
              </Radio.Group>
            </Form.Item>

            <Form.Item label="API Key">
              <Input.Password
                value={apiKey}
                onChange={e => onApiKeyChange(e.target.value)}
                placeholder={t('ai.api_key_placeholder')}
                autoComplete="new-password"
                suffix={
                  validated ? (
                    <CheckCircleOutlined style={{ color: '#52c41a' }} />
                  ) : (
                    <Button
                      type="link"
                      size="small"
                      loading={validating}
                      onClick={onValidate}
                    >
                      {t('ai.validate')}
                    </Button>
                  )
                }
              />
            </Form.Item>

            <Form.Item label={t('ai.base_url')}>
              <Input
                value={baseUrl}
                onChange={e => onBaseUrlChange(e.target.value)}
                placeholder={t('ai.base_url_placeholder')}
                autoComplete="off"
              />
            </Form.Item>
          </Form>
        </div>
      )}
    </Modal>
  );
};

export default ConfigModal;
