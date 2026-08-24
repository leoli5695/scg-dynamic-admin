import React, { useEffect, useState } from 'react';
import {
  Modal, Tabs, Card, Input, Button, Spin, Tag, Space, Divider,
  DatePicker, Radio } from 'antd';
import { message } from '../../utils/antdStatic';
import {
  RobotOutlined, CheckCircleOutlined,
  ThunderboltOutlined, ClockCircleOutlined,
} from '@ant-design/icons';
import { useTranslation } from 'react-i18next';
import dayjs, { Dayjs } from 'dayjs';
import {
  getAiProviderModels,
  getAiProviders,
  saveAiConfig,
  validateApiKey,
} from '../../api/copilot';
import { analyzeTimeRange } from '../../api/ai';
import { ApiError } from '../../api/http';
import type { AiProvider } from '../../types/copilot';
import AiReportRenderer from './AiReportRenderer';
import '../../styles/ai-report.css';
import './ai-analysis.css';

interface AiAnalysisModalProps {
  visible: boolean;
  onClose: () => void;
  language: string;
}

const AiAnalysisModal: React.FC<AiAnalysisModalProps> = ({ visible, onClose, language }) => {
  const [providers, setProviders] = useState<AiProvider[]>([]);
  const [selectedProvider, setSelectedProvider] = useState<string>('');
  const [models, setModels] = useState<string[]>([]);
  const [selectedModel, setSelectedModel] = useState<string>('');
  const [apiKey, setApiKey] = useState('');
  const [baseUrl, setBaseUrl] = useState('');
  const [validating, setValidating] = useState(false);
  const [validated, setValidated] = useState(false);
  const [analyzing, setAnalyzing] = useState(false);
  const [result, setResult] = useState<string>('');
  const [timeRange, setTimeRange] = useState<[Dayjs | null, Dayjs | null]>([null, null]);
  const { t } = useTranslation();

  useEffect(() => {
    if (!visible) return;
    (async () => {
      try {
        const data = await getAiProviders();
        if (Array.isArray(data)) {
          setProviders(data);
        }
      } catch (e) {
        console.error('Failed to load providers:', e);
      }
    })();
  }, [visible]);

  useEffect(() => {
    if (!selectedProvider) return;
    (async () => {
      // Restore saved config for the selected provider
      const config = providers.find(p => p.provider === selectedProvider);
      if (config) {
        setSelectedModel(config.model || '');
        setValidated(config.isValid || false);
      }
      try {
        const data = await getAiProviderModels(selectedProvider);
        if (Array.isArray(data)) {
          setModels(data);
        }
      } catch (e) {
        console.error('Failed to load models:', e);
      }
    })();
  }, [selectedProvider, providers]);

  const handleValidate = async () => {
    if (!apiKey) {
      message.warning(t('ai.please_input_api_key'));
      return;
    }

    setValidating(true);
    try {
      const data = await validateApiKey({
        provider: selectedProvider,
        apiKey,
        baseUrl: baseUrl || null,
      });
      if (data.valid) {
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

  const handleSaveAndAnalyze = async () => {
    if (!selectedModel) {
      message.warning(t('ai.please_select_model'));
      return;
    }
    if (!validated) {
      message.warning(t('ai.please_validate_first'));
      return;
    }

    if (!timeRange[0] || !timeRange[1]) {
      message.warning(t('ai.please_select_time_range'));
      return;
    }

    // Save config via copilot endpoint
    try {
      await saveAiConfig({
        provider: selectedProvider,
        model: selectedModel,
        apiKey,
        baseUrl: baseUrl || null,
      });
    } catch (e) {
      console.error('Failed to save config:', e);
    }

    // Run time-range analysis
    setAnalyzing(true);
    setResult('');
    try {
      const data = await analyzeTimeRange({
        provider: selectedProvider,
        startTime: timeRange[0]!.valueOf(),
        endTime: timeRange[1]!.valueOf(),
        language,
      });
      setResult(data?.result || '');
    } catch (e) {
      message.error(e instanceof ApiError && e.message ? e.message : t('ai.analysis_failed'));
    } finally {
      setAnalyzing(false);
    }
  };

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
        backgroundColor: selectedProvider === p.provider ? '#e6f7ff' : undefined,
      }}
      onClick={() => {
        setSelectedProvider(p.provider);
        setResult('');
      }}
    >
      <Space>
        <Tag color={p.isValid ? 'success' : 'default'}>
          {p.providerName}
        </Tag>
        {p.isValid && <CheckCircleOutlined style={{ color: '#52c41a' }} />}
      </Space>
    </Card>
  );

  return (
    <Modal
      title={
        <Space>
          <RobotOutlined />
          {t('ai.ai_analysis')}
        </Space>
      }
      open={visible}
      onCancel={onClose}
      footer={null}
      width={800}
      destroyOnClose
    >
      {!result && !analyzing ? (
        <>
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

              <div style={{ marginBottom: 12 }}>
                <label style={{ display: 'block', marginBottom: 8 }}>
                  {t('ai.select_model')}
                </label>
                <Radio.Group
                  value={selectedModel}
                  onChange={e => setSelectedModel(e.target.value)}
                  style={{ width: '100%' }}
                >
                  <Space direction="vertical" style={{ width: '100%' }}>
                    {models.map(m => (
                      <Radio key={m} value={m} style={{ color: '#e2e8f0' }}>
                        {m}
                      </Radio>
                    ))}
                  </Space>
                </Radio.Group>
              </div>

              <div style={{ marginBottom: 12 }}>
                <label style={{ display: 'block', marginBottom: 4 }}>
                  API Key
                </label>
                <Input.Password
                  value={apiKey}
                  onChange={e => { setApiKey(e.target.value); setValidated(false); }}
                  placeholder={t('ai.api_key_placeholder')}
                  autoComplete="new-password"
                  data-lpignore="true"
                  suffix={
                    validated ? (
                      <CheckCircleOutlined style={{ color: '#52c41a' }} />
                    ) : (
                      <Button
                        type="link"
                        size="small"
                        loading={validating}
                        onClick={handleValidate}
                      >
                        {t('ai.validate')}
                      </Button>
                    )
                  }
                />
              </div>

              <div style={{ marginBottom: 12 }}>
                <label style={{ display: 'block', marginBottom: 4 }}>
                  {t('ai.base_url')}
                </label>
                <Input
                  value={baseUrl}
                  onChange={e => setBaseUrl(e.target.value)}
                  placeholder={t('ai.base_url_placeholder')}
                  autoComplete="off"
                  data-lpignore="true"
                />
              </div>

              <Divider>{t('ai.timerange_analysis')}</Divider>

              <div style={{ marginBottom: 12 }}>
                <label style={{ display: 'block', marginBottom: 8 }}>
                  {t('ai.select_time_range')}
                </label>
                <Space direction="vertical" style={{ width: '100%' }} size="small">
                  <Space wrap>
                    <Button size="small" onClick={() => setTimeRange([dayjs().subtract(10, 'minute'), dayjs()])}>
                      {t('ai.last_10min')}
                    </Button>
                    <Button size="small" onClick={() => setTimeRange([dayjs().subtract(30, 'minute'), dayjs()])}>
                      {t('ai.last_30min')}
                    </Button>
                    <Button size="small" onClick={() => setTimeRange([dayjs().subtract(1, 'hour'), dayjs()])}>
                      {t('ai.last_1hour')}
                    </Button>
                    <Button size="small" onClick={() => setTimeRange([dayjs().subtract(6, 'hour'), dayjs()])}>
                      {t('ai.last_6hours')}
                    </Button>
                  </Space>
                  <DatePicker.RangePicker
                    showTime
                    value={timeRange}
                    onChange={(dates) => setTimeRange(dates as [Dayjs | null, Dayjs | null])}
                    style={{ width: '100%' }}
                    placeholder={[t('ai.start_time'), t('ai.end_time')]}
                  />
                </Space>
              </div>

              <Button
                type="primary"
                icon={<ClockCircleOutlined />}
                loading={analyzing}
                disabled={!validated || !selectedModel || !timeRange[0] || !timeRange[1]}
                onClick={handleSaveAndAnalyze}
                block
              >
                {!validated ? t('ai.please_validate_first') :
                 !selectedModel ? t('ai.please_select_model') :
                 t('ai.start_analysis')}
              </Button>
            </div>
          )}
        </>
      ) : analyzing ? (
        <div style={{ textAlign: 'center', padding: '60px 0' }}>
          <Spin size="large" />
          <div style={{ marginTop: 16, color: '#666' }}>
            {t('ai.analyzing')}
          </div>
        </div>
      ) : (
        <div className="ai-analysis-result">
          <div style={{ marginBottom: 16, display: 'flex', justifyContent: 'space-between', alignItems: 'center' }}>
            <Space>
              <Button onClick={() => setResult('')} icon={<ThunderboltOutlined />}>
                {t('ai.reanalyze')}
              </Button>
              <Tag color="blue" icon={<CheckCircleOutlined />}>Analysis Complete</Tag>
            </Space>
          </div>

          <div className="ai-result-container">
            <div className="ai-result-content">
              <AiReportRenderer content={result} />
            </div>
          </div>
        </div>
      )}
    </Modal>
  );
};

export default AiAnalysisModal;
