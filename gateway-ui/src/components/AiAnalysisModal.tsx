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
        <div>
          <div style={{ marginBottom: 16 }}>
            <Button onClick={() => setResult('')}>
              {t('ai.reanalyze') || '重新分析'}
            </Button>
          </div>
          <div className="ai-result" style={{
            padding: 16,
            background: '#f5f5f5',
            borderRadius: 8,
            maxHeight: 400,
            overflow: 'auto'
          }}>
            <ReactMarkdown>{result}</ReactMarkdown>
          </div>
        </div>
      )}

      <style>{`
        .ai-result h1 { font-size: 18px; border-bottom: 1px solid #ddd; padding-bottom: 8px; }
        .ai-result h2 { font-size: 16px; color: #1890ff; margin-top: 16px; }
        .ai-result h3 { font-size: 14px; color: #52c41a; }
        .ai-result ul, .ai-result ol { padding-left: 20px; }
        .ai-result li { margin-bottom: 4px; }
        .ai-result code { background: #e8e8e8; padding: 2px 6px; border-radius: 4px; }
      `}</style>
    </Modal>
  );
};

export default AiAnalysisModal;