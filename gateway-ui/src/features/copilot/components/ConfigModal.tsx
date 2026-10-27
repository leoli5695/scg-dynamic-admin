import React, { useState } from 'react';
import EditorNavigation from '../../instance-detail/EditorNavigation';
import { useEditorStep } from '../../instance-detail/useEditorStep';
import { Button, Card, Divider, Form, Input, Modal, Select, Space, Tabs, Tag } from 'antd';
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
  const [active, setActive, direction] = useEditorStep();
  const [saving, setSaving] = useState(false);
  const busy = validating || saving;
  const steps = [t('ai.select_model'), t('ai.config')];

  const domesticProviders = providers.filter((p) => p.region === 'DOMESTIC');
  const overseasProviders = providers.filter((p) => p.region === 'OVERSEAS');

  const renderProviderCard = (p: AiProvider) => (
    <Card
      key={p.provider}
      hoverable
      role="radio"
      aria-checked={selectedProvider === p.provider}
      tabIndex={0}
      onKeyDown={(event) => {
        if (event.key === 'Enter' || event.key === ' ') {
          event.preventDefault();
          onProviderChange(p.provider);
        }
      }}
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
      className="console-editor resource-editor"
      title={
        <Space>
          <SettingOutlined />
          {t('copilot.model_config')}
        </Space>
      }
      open={open}
      onCancel={onClose}
      footer={null}
      mask={{ closable: false }}
      closable={!busy}
      keyboard={!busy}
      okText={t('common.save')}
      cancelText={t('common.cancel')}
      width={1060}
    >
      <div className="console-controlled-editor">
        <div className="editor-workspace">
          <EditorNavigation
            steps={steps}
            active={active}
            onChange={(step) => {
              if (!busy && (step === 0 || selectedProvider)) setActive(step);
            }}
          />
          <div className="editor-scroll" data-direction={direction}>
            <div hidden={active !== 0} className="form-section-modern">
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
            </div>
            <div hidden={active !== 1} className="form-section-modern">
              {selectedProvider && (
                <div style={{ marginTop: 16 }}>
                  <Divider>{t('ai.config')}</Divider>

                  <Form layout="vertical" disabled={busy}>
                    <Form.Item label={t('ai.select_model')}>
                      <Select
                        showSearch
                        value={selectedModel || undefined}
                        onChange={onModelChange}
                        placeholder={t('ai.select_model_placeholder')}
                        optionFilterProp="label"
                        options={models.map((model) => ({ value: model, label: model }))}
                      />
                    </Form.Item>

                    <Form.Item label="API Key">
                      <Input.Password
                        value={apiKey}
                        onChange={(e) => onApiKeyChange(e.target.value)}
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
                        onChange={(e) => onBaseUrlChange(e.target.value)}
                        placeholder={t('ai.base_url_placeholder')}
                        autoComplete="off"
                      />
                    </Form.Item>
                  </Form>
                </div>
              )}
            </div>
          </div>
        </div>
        <div className="modal-footer-modern">
          <span className="editor-progress">{active + 1} / 2</span>
          <Button disabled={busy} onClick={onClose}>
            {t('common.cancel')}
          </Button>
          {active === 0 ? (
            <Button
              key="next"
              type="primary"
              disabled={!selectedProvider || busy}
              onClick={() => setActive(1)}
            >
              {t('routes.next_step')}
            </Button>
          ) : (
            <>
              <Button disabled={busy} onClick={() => setActive(0)}>
                {t('routes.previous_step')}
              </Button>
              <Button
                key="save"
                type="primary"
                loading={saving}
                disabled={!validated || !selectedModel || validating}
                onClick={async () => {
                  setSaving(true);
                  try {
                    await onSave();
                  } finally {
                    setSaving(false);
                  }
                }}
              >
                {t('common.save')}
              </Button>
            </>
          )}
        </div>
      </div>
    </Modal>
  );
};

export default ConfigModal;
