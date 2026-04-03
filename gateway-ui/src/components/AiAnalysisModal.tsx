import { useState, useEffect } from 'react';
import {
  Modal, Tabs, Card, Select, Input, Button, Spin, message, Tag, Space, Divider,
  DatePicker, Radio
} from 'antd';
import {
  RobotOutlined, CheckCircleOutlined, CloseCircleOutlined,
  ApiOutlined, ThunderboltOutlined, ClockCircleOutlined
} from '@ant-design/icons';
import api from '../utils/api';
import { useTranslation } from 'react-i18next';
import ReactMarkdown from 'react-markdown';
import dayjs, { Dayjs } from 'dayjs';

interface AiProvider {
  id: number;
  provider: string;
  providerName: string;
  region: string;
  model: string;
  apiKey: string;
  baseUrl: string;
  isValid: boolean;
}

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
  const [analysisMode, setAnalysisMode] = useState<'realtime' | 'timerange'>('realtime');
  const [timeRange, setTimeRange] = useState<[Dayjs | null, Dayjs | null]>([null, null]);
  const { t } = useTranslation();

  useEffect(() => {
    if (visible) {
      loadProviders();
    }
  }, [visible]);

  useEffect(() => {
    if (selectedProvider) {
      loadModels(selectedProvider);
      loadSavedConfig(selectedProvider);
    }
  }, [selectedProvider]);

  const loadProviders = async () => {
    try {
      const res = await api.get('/api/ai/providers');
      if (res.data.code === 200) {
        setProviders(res.data.data);
      }
    } catch (e) {
      console.error('Failed to load providers:', e);
    }
  };

  const loadModels = async (provider: string) => {
    try {
      const res = await api.get(`/api/ai/providers/${provider}/models`);
      if (res.data.code === 200) {
        setModels(res.data.data);
      }
    } catch (e) {
      console.error('Failed to load models:', e);
    }
  };

  const loadSavedConfig = (provider: string) => {
    const config = providers.find(p => p.provider === provider);
    if (config) {
      setSelectedModel(config.model || '');
      setApiKey(config.apiKey || '');
      setBaseUrl(config.baseUrl || '');
      setValidated(config.isValid || false);
    }
  };

  const handleValidate = async () => {
    if (!apiKey) {
      message.warning(t('ai.please_input_api_key') || '请输入API Key');
      return;
    }

    setValidating(true);
    try {
      const res = await api.post('/api/ai/validate', {
        provider: selectedProvider,
        apiKey,
        baseUrl: baseUrl || null
      });
      if (res.data.code === 200 && res.data.data.valid) {
        setValidated(true);
        message.success(t('ai.validate_success') || 'API Key验证成功');
      } else {
        setValidated(false);
        message.error(t('ai.validate_failed') || 'API Key验证失败');
      }
    } catch (e) {
      setValidated(false);
      message.error(t('ai.validate_failed') || 'API Key验证失败');
    } finally {
      setValidating(false);
    }
  };

  const handleSaveAndAnalyze = async () => {
    if (!selectedModel) {
      message.warning(t('ai.please_select_model') || '请选择模型');
      return;
    }
    if (!validated) {
      message.warning(t('ai.please_validate_first') || '请先验证API Key');
      return;
    }

    // 时间段分析时检查时间
    if (analysisMode === 'timerange') {
      if (!timeRange[0] || !timeRange[1]) {
        message.warning(t('ai.please_select_time_range') || '请选择时间段');
        return;
      }
    }

    // 保存配置
    try {
      await api.post('/api/ai/config', {
        provider: selectedProvider,
        model: selectedModel,
        apiKey,
        baseUrl: baseUrl || null
      });
    } catch (e) {
      console.error('Failed to save config:', e);
    }

    // 开始分析
    setAnalyzing(true);
    setResult('');
    try {
      let res;
      if (analysisMode === 'timerange') {
        // 时间段分析
        res = await api.post('/api/ai/analyze/timerange', {
          provider: selectedProvider,
          startTime: timeRange[0]!.valueOf(),
          endTime: timeRange[1]!.valueOf(),
          language
        });
      } else {
        // 实时分析
        res = await api.post('/api/ai/analyze', {
          provider: selectedProvider,
          language
        });
      }
      if (res.data.code === 200) {
        setResult(res.data.data.result);
      } else {
        message.error(res.data.message || t('ai.analysis_failed') || 'AI分析失败');
      }
    } catch (e: any) {
      message.error(e.message || t('ai.analysis_failed') || 'AI分析失败');
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
        backgroundColor: selectedProvider === p.provider ? '#e6f7ff' : undefined
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
          {t('ai.ai_analysis') || 'AI智能分析'}
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
                label: t('ai.domestic_models') || '国内大模型',
                children: (
                  <div style={{ maxHeight: 200, overflow: 'auto' }}>
                    {domesticProviders.map(renderProviderCard)}
                  </div>
                )
              },
              {
                key: 'overseas',
                label: t('ai.overseas_models') || '国外大模型',
                children: (
                  <div style={{ maxHeight: 200, overflow: 'auto' }}>
                    {overseasProviders.map(renderProviderCard)}
                  </div>
                )
              }
            ]}
          />

          {selectedProvider && (
            <div style={{ marginTop: 16 }}>
              <Divider>{t('ai.config') || '配置'}</Divider>
              
              <div style={{ marginBottom: 12 }}>
                <label style={{ display: 'block', marginBottom: 4 }}>
                  {t('ai.select_model') || '选择模型'}
                </label>
                <Select
                  style={{ width: '100%' }}
                  value={selectedModel}
                  onChange={setSelectedModel}
                  placeholder={t('ai.select_model_placeholder') || '请选择模型'}
                  options={models.map(m => ({ value: m, label: m }))}
                />
              </div>

              <div style={{ marginBottom: 12 }}>
                <label style={{ display: 'block', marginBottom: 4 }}>
                  API Key
                </label>
                <Input.Password
                  value={apiKey}
                  onChange={e => { setApiKey(e.target.value); setValidated(false); }}
                  placeholder={t('ai.api_key_placeholder') || '请输入API Key'}
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
                        {t('ai.validate') || '验证'}
                      </Button>
                    )
                  }
                />
              </div>

              <div style={{ marginBottom: 12 }}>
                <label style={{ display: 'block', marginBottom: 4 }}>
                  {t('ai.base_url') || 'API地址（可选）'}
                </label>
                <Input
                  value={baseUrl}
                  onChange={e => setBaseUrl(e.target.value)}
                  placeholder={t('ai.base_url_placeholder') || '留空使用默认地址'}
                />
              </div>

              <Divider>{t('ai.analysis_mode') || '分析模式'}</Divider>

              <div style={{ marginBottom: 12 }}>
                <Radio.Group
                  value={analysisMode}
                  onChange={e => setAnalysisMode(e.target.value)}
                  optionType="button"
                  buttonStyle="solid"
                >
                  <Radio.Button value="realtime">
                    <ThunderboltOutlined style={{ marginRight: 4 }} />
                    {t('ai.realtime_analysis') || '实时分析'}
                  </Radio.Button>
                  <Radio.Button value="timerange">
                    <ClockCircleOutlined style={{ marginRight: 4 }} />
                    {t('ai.timerange_analysis') || '时间段分析'}
                  </Radio.Button>
                </Radio.Group>
              </div>

              {analysisMode === 'timerange' && (
                <div style={{ marginBottom: 12 }}>
                  <label style={{ display: 'block', marginBottom: 8 }}>
                    {t('ai.select_time_range') || '选择时间段'}
                  </label>
                  <Space direction="vertical" style={{ width: '100%' }} size="small">
                    <Space wrap>
                      <Button size="small" onClick={() => setTimeRange([dayjs().subtract(10, 'minute'), dayjs()])}>
                        {t('ai.last_10min') || '最近10分钟'}
                      </Button>
                      <Button size="small" onClick={() => setTimeRange([dayjs().subtract(30, 'minute'), dayjs()])}>
                        {t('ai.last_30min') || '最近30分钟'}
                      </Button>
                      <Button size="small" onClick={() => setTimeRange([dayjs().subtract(1, 'hour'), dayjs()])}>
                        {t('ai.last_1hour') || '最近1小时'}
                      </Button>
                      <Button size="small" onClick={() => setTimeRange([dayjs().subtract(6, 'hour'), dayjs()])}>
                        {t('ai.last_6hours') || '最近6小时'}
                      </Button>
                    </Space>
                    <DatePicker.RangePicker
                      showTime
                      value={timeRange}
                      onChange={(dates) => setTimeRange(dates as [Dayjs | null, Dayjs | null])}
                      style={{ width: '100%' }}
                      placeholder={[t('ai.start_time') || '开始时间', t('ai.end_time') || '结束时间']}
                    />
                  </Space>
                </div>
              )}

              <Button
                type="primary"
                icon={<ThunderboltOutlined />}
                loading={analyzing}
                disabled={!validated || !selectedModel || (analysisMode === 'timerange' && (!timeRange[0] || !timeRange[1]))}
                onClick={handleSaveAndAnalyze}
                block
              >
                {t('ai.start_analysis') || '开始分析'}
              </Button>
            </div>
          )}
        </>
      ) : analyzing ? (
        <div style={{ textAlign: 'center', padding: '60px 0' }}>
          <Spin size="large" />
          <div style={{ marginTop: 16, color: '#666' }}>
            {t('ai.analyzing') || 'AI分析中，请稍候...'}
          </div>
        </div>
      ) : (
        <div className="ai-analysis-result">
          <div style={{ marginBottom: 16, display: 'flex', justifyContent: 'space-between', alignItems: 'center' }}>
            <Space>
              <Button onClick={() => setResult('')} icon={<ThunderboltOutlined />}>
                {t('ai.reanalyze') || '重新分析'}
              </Button>
              <Tag color="blue" icon={<CheckCircleOutlined />}>Analysis Complete</Tag>
            </Space>
          </div>
          
          <div className="ai-result-container">
            <div className="ai-result-content">
              <ReactMarkdown>{result}</ReactMarkdown>
            </div>
          </div>
        </div>
      )}

      <style>{`
        .ai-analysis-result {
          display: flex;
          flex-direction: column;
        }
        
        .ai-result-container {
          background: var(--bg-section, #0f172a);
          border: 1px solid var(--border-color, #1e293b);
          border-radius: 12px;
          padding: 20px;
          max-height: 500px;
          overflow: auto;
        }
        
        .ai-result-content {
          font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, 'Helvetica Neue', Arial, sans-serif;
          line-height: 1.7;
        }
        
        .ai-result-content h1 {
          font-size: 18px;
          font-weight: 600;
          color: var(--text-primary, #e2e8f0);
          border-bottom: 2px solid var(--border-color, #1e293b);
          padding-bottom: 10px;
          margin-top: 24px;
          margin-bottom: 16px;
        }
        
        .ai-result-content h2 {
          font-size: 16px;
          font-weight: 600;
          color: var(--text-primary, #e2e8f0);
          margin-top: 20px;
          margin-bottom: 12px;
        }
        
        .ai-result-content h3 {
          font-size: 14px;
          font-weight: 500;
          color: var(--text-secondary, #94a3b8);
          margin-top: 16px;
          margin-bottom: 10px;
        }
        
        .ai-result-content p {
          color: var(--text-primary, #e2e8f0);
          margin-bottom: 12px;
          line-height: 1.7;
        }
        
        .ai-result-content ul,
        .ai-result-content ol {
          padding-left: 24px;
          margin-bottom: 12px;
          color: var(--text-primary, #e2e8f0);
        }
        
        .ai-result-content li {
          margin-bottom: 8px;
          line-height: 1.6;
        }
        
        .ai-result-content code {
          background: var(--bg-code, #020617);
          padding: 3px 8px;
          border-radius: 6px;
          font-family: 'JetBrains Mono', 'SF Mono', 'Monaco', 'Consolas', monospace;
          font-size: 12px;
          color: var(--text-primary, #e2e8f0);
          border: 1px solid var(--border-color, #1e293b);
        }
        
        .ai-result-content pre {
          background: var(--bg-code, #020617);
          padding: 12px;
          border-radius: 8px;
          overflow: auto;
          margin: 12px 0;
          border: 1px solid var(--border-color, #1e293b);
        }
        
        .ai-result-content pre code {
          background: transparent;
          padding: 0;
          border: none;
        }
        
        .ai-result-content blockquote {
          border-left: 3px solid var(--border-color, #1e293b);
          padding-left: 12px;
          margin: 12px 0;
          color: var(--text-secondary, #94a3b8);
          background: rgba(255, 255, 255, 0.02);
          padding: 10px 12px;
          border-radius: 4px;
        }
        
        .ai-result-content strong {
          font-weight: 600;
          color: var(--text-primary, #e2e8f0);
        }
        
        .ai-result-content table {
          width: 100%;
          border-collapse: collapse;
          margin: 12px 0;
          background: var(--bg-card, #1a2338);
          border-radius: 8px;
          overflow: hidden;
        }
        
        .ai-result-content th {
          background: var(--bg-section, #0f172a);
          padding: 10px 12px;
          text-align: left;
          font-weight: 600;
          color: var(--text-primary, #e2e8f0);
          border-bottom: 1px solid var(--border-color, #1e293b);
        }
        
        .ai-result-content td {
          padding: 10px 12px;
          color: var(--text-primary, #e2e8f0);
          border-bottom: 1px solid var(--border-divider, #1e293b);
        }
        
        .ai-result-content tr:last-child td {
          border-bottom: none;
        }
      `}</style>
    </Modal>
  );
};

export default AiAnalysisModal;