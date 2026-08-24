import React, { useCallback, useEffect, useState } from 'react';
import { Button, Card, Space, Tabs, Tag, Typography } from 'antd';
import { message } from '../../utils/antdStatic';
import {
  BookOutlined,
  CheckCircleOutlined,
  RobotOutlined,
  SettingOutlined,
  ThunderboltOutlined,
} from '@ant-design/icons';
import { useTranslation } from 'react-i18next';
import {
  getAiProviderModels,
  getAiProviders,
  saveAiConfig,
  validateApiKey,
} from '../../api/copilot';
import { ApiError } from '../../api/http';
import type { AiProvider } from '../../types/copilot';
import { useInstanceDetail } from '../instance-detail/InstanceDetailContext';
import ChatTab from './components/ChatTab';
import ToolsTab from './components/ToolsTab';
import LearnTab from './components/LearnTab';
import ConfigModal from './components/ConfigModal';

const { Text } = Typography;

const AiCopilotPage: React.FC = () => {
  const { t } = useTranslation();
  const { instance } = useInstanceDetail();
  const instanceId = instance.instanceId;

  const [activeTab, setActiveTab] = useState('chat');

  // AI Provider configuration
  const [providers, setProviders] = useState<AiProvider[]>([]);
  const [selectedProvider, setSelectedProvider] = useState('');
  const [availableModels, setAvailableModels] = useState<string[]>([]);
  const [selectedModel, setSelectedModel] = useState('');
  const [apiKey, setApiKey] = useState('');
  const [baseUrl, setBaseUrl] = useState('');
  const [validating, setValidating] = useState(false);
  const [validated, setValidated] = useState(false);
  const [configModalVisible, setConfigModalVisible] = useState(false);

  // Load models for a provider
  const loadModels = useCallback(async (provider: string) => {
    try {
      const models = await getAiProviderModels(provider);
      setAvailableModels(models || []);
    } catch (e) {
      console.error('Failed to load models', e);
    }
  }, []);

  // Load providers and auto-select the first valid one
  const loadProviders = useCallback(async () => {
    try {
      const data = await getAiProviders();
      setProviders(data || []);

      const validProvider = (data || []).find(p => p.isValid);
      if (validProvider) {
        setSelectedProvider(validProvider.provider);
        setSelectedModel(validProvider.model);
        setValidated(true);
        await loadModels(validProvider.provider);
      }
    } catch (e) {
      console.error('Failed to load providers', e);
    }
  }, [loadModels]);

  useEffect(() => {
    (async () => {
      await loadProviders();
    })();
  }, [loadProviders]);

  // Handle provider change inside the config modal
  const handleProviderChange = (provider: string) => {
    setSelectedProvider(provider);
    setSelectedModel('');
    setApiKey('');
    setBaseUrl('');
    setValidated(false);
    loadModels(provider);

    // Load saved config if exists
    const savedProvider = providers.find(p => p.provider === provider);
    if (savedProvider?.isValid && savedProvider.model) {
      setSelectedModel(savedProvider.model);
      setValidated(true);
    }
  };

  // Validate API Key
  const handleValidate = async () => {
    if (!apiKey) {
      message.warning(t('ai.please_input_api_key'));
      return;
    }

    setValidating(true);
    try {
      const result = await validateApiKey({
        provider: selectedProvider,
        apiKey,
        baseUrl: baseUrl || null,
      });

      if (result.valid) {
        setValidated(true);
        message.success(t('ai.validate_success'));
      } else {
        setValidated(false);
        message.error(t('ai.validate_failed'));
      }
    } catch (e) {
      setValidated(false);
      message.error(e instanceof ApiError && e.message ? e.message : t('ai.validate_failed'));
    } finally {
      setValidating(false);
    }
  };

  // Save configuration
  const handleSaveConfig = async () => {
    if (!validated || !selectedModel) {
      message.warning(t('ai.please_validate_first'));
      return;
    }

    try {
      await saveAiConfig({
        provider: selectedProvider,
        model: selectedModel,
        apiKey,
        baseUrl: baseUrl || null,
      });
      message.success(t('common.success'));
      setConfigModalVisible(false);
      loadProviders();
    } catch {
      message.error(t('common.error'));
    }
  };

  const tabItems = [
    {
      key: 'chat',
      label: (
        <span>
          <RobotOutlined />
          {t('copilot.tab_chat')}
        </span>
      ),
      children: <ChatTab instanceId={instanceId} provider={selectedProvider} model={selectedModel} />,
    },
    {
      key: 'tools',
      label: (
        <span>
          <ThunderboltOutlined />
          {t('copilot.tab_tools')}
        </span>
      ),
      children: <ToolsTab instanceId={instanceId} provider={selectedProvider} model={selectedModel} />,
    },
    {
      key: 'learn',
      label: (
        <span>
          <BookOutlined />
          {t('copilot.tab_learn')}
        </span>
      ),
      children: <LearnTab instanceId={instanceId} provider={selectedProvider} model={selectedModel} />,
    },
  ];

  return (
    <div style={{ padding: '24px' }}>
      <Card
        title={
          <span>
            <RobotOutlined style={{ marginRight: '8px', color: '#52c41a' }} />
            {t('copilot.title')}
          </span>
        }
        style={{ background: 'rgba(255,255,255,0.05)' }}
      >
        {/* Model Configuration */}
        <Card
          size="small"
          title={
            <Space>
              <SettingOutlined />
              {t('copilot.model_config')}
            </Space>
          }
          extra={
            <Button
              size="small"
              icon={<SettingOutlined />}
              onClick={() => setConfigModalVisible(true)}
            >
              {t('copilot.configure')}
            </Button>
          }
          style={{ marginBottom: '16px', background: 'rgba(255,255,255,0.05)' }}
        >
          <Space wrap>
            <Text type="secondary">{t('copilot.current_model')}:</Text>
            {selectedProvider && selectedModel ? (
              <Tag color="blue" icon={<CheckCircleOutlined />}>
                {providers.find(p => p.provider === selectedProvider)?.providerName} - {selectedModel}
              </Tag>
            ) : (
              <Tag color="orange">{t('copilot.not_configured')}</Tag>
            )}
          </Space>
        </Card>

        <Tabs
          activeKey={activeTab}
          onChange={setActiveTab}
          items={tabItems}
          tabBarStyle={{ marginBottom: '16px' }}
        />
      </Card>

      {/* Config Modal */}
      <ConfigModal
        open={configModalVisible}
        providers={providers}
        selectedProvider={selectedProvider}
        selectedModel={selectedModel}
        models={availableModels}
        apiKey={apiKey}
        baseUrl={baseUrl}
        validated={validated}
        validating={validating}
        onProviderChange={handleProviderChange}
        onModelChange={setSelectedModel}
        onApiKeyChange={value => { setApiKey(value); setValidated(false); }}
        onBaseUrlChange={setBaseUrl}
        onValidate={handleValidate}
        onSave={handleSaveConfig}
        onClose={() => setConfigModalVisible(false)}
      />
    </div>
  );
};

export default AiCopilotPage;
