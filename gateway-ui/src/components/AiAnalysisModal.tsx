import { useState, useEffect } from 'react';
import {
  Modal, Tabs, Card, Input, Button, Spin, message, Tag, Space, Divider,
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
        const errorMsg = res.data.message || t('ai.validate_failed') || 'API Key验证失败';
        message.error(errorMsg);
        console.error('Validation failed:', res.data);
      }
    } catch (e: any) {
      setValidated(false);
      const errorMsg = e.response?.data?.message || e.message || t('ai.validate_failed') || 'API Key验证失败';
      message.error(errorMsg);
      console.error('Validation error:', e);
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
                <label style={{ display: 'block', marginBottom: 8 }}>
                  {t('ai.select_model') || '选择模型'}
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
                  placeholder={t('ai.api_key_placeholder') || '请输入API Key'}
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
                  autoComplete="off"
                  data-lpignore="true"
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
                {!validated ? (t('ai.please_validate_first') || '请先验证API Key') :
                 !selectedModel ? (t('ai.please_select_model') || '请选择模型') :
                 (t('ai.start_analysis') || '开始分析')}
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
        /* 防止 LastPass 等密码管理器注入的元素干扰表单 */
        [data-lastpass-icon-root] {
          display: none !important;
          visibility: hidden !important;
          pointer-events: none !important;
        }
        [data-lastpass-icon-root] * {
          display: none !important;
        }

        .ai-analysis-result {
          display: flex;
          flex-direction: column;
        }

        /* 结果容器 - 添加更好的背景和边框 */
        .ai-result-container {
          background: linear-gradient(135deg, #0f172a 0%, #1a2338 100%);
          border: 1px solid rgba(59, 130, 246, 0.2);
          border-radius: 12px;
          padding: 24px;
          max-height: 500px;
          overflow: auto;
          box-shadow: 0 4px 20px rgba(0, 0, 0, 0.3);
        }

        .ai-result-content {
          font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, 'Helvetica Neue', Arial, sans-serif;
          line-height: 1.8;
        }

        /* 主标题 - 添加醒目的卡片样式 */
        .ai-result-content h1 {
          font-size: 20px;
          font-weight: 700;
          color: #f1f5f9;
          background: linear-gradient(90deg, rgba(59, 130, 246, 0.15) 0%, transparent 100%);
          border-left: 4px solid #3b82f6;
          padding: 12px 16px;
          margin: 0 0 20px 0;
          border-radius: 0 8px 8px 0;
        }

        /* 二级标题 - 添加明显的分隔背景 */
        .ai-result-content h2 {
          font-size: 17px;
          font-weight: 600;
          color: #e2e8f0;
          background: rgba(16, 185, 129, 0.1);
          border-left: 3px solid #10b981;
          padding: 10px 14px;
          margin: 24px 0 16px 0;
          border-radius: 0 6px 6px 0;
        }

        /* 三级标题 */
        .ai-result-content h3 {
          font-size: 15px;
          font-weight: 500;
          color: #94a3b8;
          border-bottom: 1px dashed rgba(148, 163, 184, 0.3);
          padding-bottom: 6px;
          margin: 16px 0 12px 0;
        }

        /* 段落 */
        .ai-result-content p {
          color: #cbd5e1;
          margin-bottom: 16px;
          line-height: 1.8;
          padding: 0 4px;
        }

        /* 无序列表 - 添加彩色圆点和卡片效果 */
        .ai-result-content ul {
          list-style: none;
          padding-left: 0;
          margin-bottom: 20px;
        }

        .ai-result-content ul li {
          position: relative;
          padding: 10px 12px 10px 24px;
          margin-bottom: 8px;
          background: rgba(30, 41, 59, 0.5);
          border-radius: 6px;
          border: 1px solid rgba(71, 85, 105, 0.3);
          color: #e2e8f0;
          line-height: 1.7;
          transition: background 0.2s ease;
        }

        .ai-result-content ul li:hover {
          background: rgba(30, 41, 59, 0.8);
        }

        .ai-result-content ul li::before {
          content: '';
          position: absolute;
          left: 8px;
          top: 14px;
          width: 8px;
          height: 8px;
          background: #3b82f6;
          border-radius: 50%;
          box-shadow: 0 0 6px rgba(59, 130, 246, 0.5);
        }

        /* 有序列表 - 添加编号和卡片效果 */
        .ai-result-content ol {
          list-style: none;
          padding-left: 0;
          margin-bottom: 20px;
          counter-reset: item;
        }

        .ai-result-content ol li {
          position: relative;
          counter-increment: item;
          padding: 10px 12px 10px 36px;
          margin-bottom: 8px;
          background: rgba(30, 41, 59, 0.5);
          border-radius: 6px;
          border: 1px solid rgba(71, 85, 105, 0.3);
          color: #e2e8f0;
          line-height: 1.7;
        }

        .ai-result-content ol li::before {
          content: counter(item);
          position: absolute;
          left: 10px;
          top: 10px;
          width: 20px;
          height: 20px;
          background: linear-gradient(135deg, #3b82f6, #8b5cf6);
          border-radius: 50%;
          color: white;
          font-size: 11px;
          font-weight: 600;
          display: flex;
          align-items: center;
          justify-content: center;
          text-align: center;
          line-height: 20px;
        }

        /* 代码块 */
        .ai-result-content code {
          background: #020617;
          padding: 4px 10px;
          border-radius: 6px;
          font-family: 'JetBrains Mono', 'SF Mono', 'Monaco', 'Consolas', monospace;
          font-size: 13px;
          color: #a5f3fc;
          border: 1px solid rgba(71, 85, 105, 0.4);
        }

        .ai-result-content pre {
          background: #020617;
          padding: 16px;
          border-radius: 8px;
          overflow: auto;
          margin: 16px 0;
          border: 1px solid rgba(59, 130, 246, 0.2);
          box-shadow: inset 0 2px 8px rgba(0, 0, 0, 0.3);
        }

        .ai-result-content pre code {
          background: transparent;
          padding: 0;
          border: none;
          color: #e2e8f0;
        }

        /* 引用块 */
        .ai-result-content blockquote {
          border-left: 3px solid #f59e0b;
          padding: 12px 16px;
          margin: 16px 0;
          color: #94a3b8;
          background: rgba(245, 158, 11, 0.08);
          border-radius: 0 6px 6px 0;
          font-style: italic;
        }

        /* 强调文字 */
        .ai-result-content strong {
          font-weight: 600;
          color: #f1f5f9;
          background: rgba(59, 130, 246, 0.1);
          padding: 2px 6px;
          border-radius: 4px;
        }

        /* 表格 */
        .ai-result-content table {
          width: 100%;
          border-collapse: collapse;
          margin: 16px 0;
          background: #1a2338;
          border-radius: 8px;
          overflow: hidden;
          border: 1px solid rgba(71, 85, 105, 0.3);
        }

        .ai-result-content th {
          background: linear-gradient(90deg, #0f172a, #1e293b);
          padding: 12px 16px;
          text-align: left;
          font-weight: 600;
          color: #f1f5f9;
          border-bottom: 2px solid #3b82f6;
        }

        .ai-result-content td {
          padding: 12px 16px;
          color: #cbd5e1;
          border-bottom: 1px solid rgba(71, 85, 105, 0.2);
        }

        .ai-result-content tr:last-child td {
          border-bottom: none;
        }

        .ai-result-content tr:hover td {
          background: rgba(30, 41, 59, 0.5);
        }

        /* 分隔线 */
        .ai-result-content hr {
          border: none;
          height: 1px;
          background: linear-gradient(90deg, transparent, rgba(59, 130, 246, 0.3), transparent);
          margin: 24px 0;
        }
      `}</style>
    </Modal>
  );
};

export default AiAnalysisModal;