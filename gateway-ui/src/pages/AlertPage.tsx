import { useState, useEffect } from 'react';
import {
  Card, Row, Col, Form, Input, Select, Button, Switch, InputNumber,
  Table, Tag, Space, message, Spin, Divider, Typography, Badge, Tooltip, Popconfirm
} from 'antd';
import {
  BellOutlined, MailOutlined, SettingOutlined, HistoryOutlined,
  PlusOutlined, DeleteOutlined, SendOutlined, AlertOutlined, CloudServerOutlined
} from '@ant-design/icons';
import api from '../utils/api';
import { useTranslation } from 'react-i18next';

const { Title, Text } = Typography;
const { Password } = Input;

interface ThresholdConfig {
  cpu: { processThreshold: number; systemThreshold: number; enabled: boolean };
  memory: { heapThreshold: number; enabled: boolean };
  http: { errorRateThreshold: number; responseTimeThreshold: number; enabled: boolean };
  instance: { unhealthyThreshold: number; enabled: boolean };
  thread: { activeThreshold: number; enabled: boolean };
}

interface AlertConfig {
  id: number;
  configName: string;
  emailRecipients: string[];
  emailLanguage: string;
  thresholdConfig: ThresholdConfig;
  enabled: boolean;
}

interface AlertHistory {
  id: number;
  alertType: string;
  alertLevel: string;
  metricName: string;
  metricValue: number;
  thresholdValue: number;
  title: string;
  content: string;
  status: string;
  createdAt: string;
}

interface CurrentMetrics {
  processCpuUsage: number;
  systemCpuUsage: number;
  heapUsagePercent: number;
  errorRate: number;
  avgResponseTimeMs: number;
  unhealthyInstances: number;
  totalInstances: number;
  threadUsagePercent: number;
}

interface EmailConfig {
  id: number;
  configName: string;
  smtpHost: string;
  smtpPort: number;
  smtpUsername: string;
  smtpPassword: string;
  useSsl: boolean;
  useStartTls: boolean;
  fromEmail: string;
  fromName: string;
  enabled: boolean;
  testStatus: string;
  lastTestTime: string;
  lastTestError: string;
}

interface AlertPageProps {
  instanceId?: string;
}

const AlertPage: React.FC<AlertPageProps> = ({ instanceId }) => {
  const [loading, setLoading] = useState(false);
  const [saving, setSaving] = useState(false);
  const [config, setConfig] = useState<AlertConfig | null>(null);
  const [history, setHistory] = useState<AlertHistory[]>([]);
  const [historyLoading, setHistoryLoading] = useState(false);
  const [currentMetrics, setCurrentMetrics] = useState<CurrentMetrics | null>(null);
  const [emailInput, setEmailInput] = useState('');
  const [emails, setEmails] = useState<string[]>([]);
  const [form] = Form.useForm();
  const [emailConfig, setEmailConfig] = useState<EmailConfig | null>(null);
  const [emailConfigLoading, setEmailConfigLoading] = useState(false);
  const [emailForm] = Form.useForm();
  const { t } = useTranslation();

  // Default threshold values
  const defaultThresholdConfig = {
    cpu: { processThreshold: 80, systemThreshold: 90, enabled: true },
    memory: { heapThreshold: 85, enabled: true },
    http: { errorRateThreshold: 5, responseTimeThreshold: 2000, enabled: true },
    instance: { unhealthyThreshold: 1, enabled: true },
    thread: { activeThreshold: 90, enabled: true }
  };

  // Load config
  const loadConfig = async () => {
    try {
      setLoading(true);
      const params = instanceId ? `?instanceId=${instanceId}` : '';
      const res = await api.get(`/api/alerts/config${params}`);
      if (res.data.code === 200) {
        setConfig(res.data.data);
        setEmails(res.data.data.emailRecipients || []);

        // Merge with default values to ensure all fields have values
        const serverThreshold = res.data.data.thresholdConfig || {};
        const mergedThreshold = {
          cpu: {
            processThreshold: serverThreshold.cpu?.processThreshold ?? defaultThresholdConfig.cpu.processThreshold,
            systemThreshold: serverThreshold.cpu?.systemThreshold ?? defaultThresholdConfig.cpu.systemThreshold,
            enabled: serverThreshold.cpu?.enabled ?? defaultThresholdConfig.cpu.enabled
          },
          memory: {
            heapThreshold: serverThreshold.memory?.heapThreshold ?? defaultThresholdConfig.memory.heapThreshold,
            enabled: serverThreshold.memory?.enabled ?? defaultThresholdConfig.memory.enabled
          },
          http: {
            errorRateThreshold: serverThreshold.http?.errorRateThreshold ?? defaultThresholdConfig.http.errorRateThreshold,
            responseTimeThreshold: serverThreshold.http?.responseTimeThreshold ?? defaultThresholdConfig.http.responseTimeThreshold,
            enabled: serverThreshold.http?.enabled ?? defaultThresholdConfig.http.enabled
          },
          instance: {
            unhealthyThreshold: serverThreshold.instance?.unhealthyThreshold ?? defaultThresholdConfig.instance.unhealthyThreshold,
            enabled: serverThreshold.instance?.enabled ?? defaultThresholdConfig.instance.enabled
          },
          thread: {
            activeThreshold: serverThreshold.thread?.activeThreshold ?? defaultThresholdConfig.thread.activeThreshold,
            enabled: serverThreshold.thread?.enabled ?? defaultThresholdConfig.thread.enabled
          }
        };

        form.setFieldsValue({
          emailLanguage: res.data.data.emailLanguage || 'zh',
          enabled: res.data.data.enabled,
          ...mergedThreshold
        });
      }
    } catch (e) {
      console.error('Failed to load config:', e);
    } finally {
      setLoading(false);
    }
  };

  // Load history
  const loadHistory = async () => {
    try {
      setHistoryLoading(true);
      const res = await api.get('/api/alerts/history');
      if (res.data.code === 200) {
        setHistory(res.data.data.content || []);
      }
    } catch (e) {
      console.error('Failed to load history:', e);
    } finally {
      setHistoryLoading(false);
    }
  };

  // Load current metrics
  const loadCurrentMetrics = async () => {
    try {
      const res = await api.get('/api/alerts/thresholds/metrics');
      if (res.data.code === 200) {
        setCurrentMetrics(res.data.data);
      }
    } catch (e) {
      console.error('Failed to load metrics:', e);
    }
  };

  useEffect(() => {
    loadConfig();
    loadHistory();
    loadCurrentMetrics();
  }, []);

  // Add email and auto-save
  const handleAddEmail = async () => {
    const email = emailInput.trim();
    if (!email) return;
    if (!/^[a-zA-Z0-9._%+-]+@[a-zA-Z0-9.-]+\.[a-zA-Z]{2,}$/.test(email)) {
      message.error(t('alert.invalid_email') || 'Invalid email format');
      return;
    }
    if (emails.includes(email)) {
      message.warning(t('alert.email_exists') || 'Email already exists');
      return;
    }
    
    const newEmails = [...emails, email];
    setEmails(newEmails);
    setEmailInput('');
    
    // Auto-save after adding
    await saveEmailRecipients(newEmails);
  };

  // Remove email and auto-save
  const handleRemoveEmail = async (email: string) => {
    const newEmails = emails.filter(e => e !== email);
    setEmails(newEmails);
    
    // Auto-save after removing
    await saveEmailRecipients(newEmails);
  };

  // Save email recipients only
  const saveEmailRecipients = async (emailList: string[]) => {
    try {
      const values = form.getFieldsValue();
      
      const payload = {
        id: config?.id,
        configName: config?.configName || 'Default Alert Config',
        emailRecipients: emailList,
        emailLanguage: values.emailLanguage || config?.emailLanguage || 'zh',
        enabled: values.enabled ?? config?.enabled ?? true,
        thresholdConfig: config?.thresholdConfig || {
          cpu: { processThreshold: 80, systemThreshold: 90, enabled: true },
          memory: { heapThreshold: 85, enabled: true },
          http: { errorRateThreshold: 5, responseTimeThreshold: 2000, enabled: true },
          instance: { unhealthyThreshold: 1, enabled: true },
          thread: { activeThreshold: 90, enabled: true }
        }
      };

      const res = await api.post('/api/alerts/config', payload);
      if (res.data.code === 200) {
        message.success(t('alert.save_success') || 'Saved successfully');
      } else {
        message.error(res.data.message || t('alert.save_failed'));
      }
    } catch (e: any) {
      message.error(e.message || t('alert.save_failed'));
    }
  };

  // Save config
  const handleSave = async () => {
    try {
      setSaving(true);
      const values = await form.validateFields();

      const payload = {
        id: config?.id,
        configName: config?.configName || 'Default Alert Config',
        emailRecipients: emails,
        emailLanguage: values.emailLanguage,
        enabled: values.enabled,
        thresholdConfig: {
          cpu: {
            processThreshold: values.cpu?.processThreshold ?? 80,
            systemThreshold: values.cpu?.systemThreshold ?? 90,
            enabled: values.cpu?.enabled ?? true
          },
          memory: {
            heapThreshold: values.memory?.heapThreshold ?? 85,
            enabled: values.memory?.enabled ?? true
          },
          http: {
            errorRateThreshold: values.http?.errorRateThreshold ?? 5,
            responseTimeThreshold: values.http?.responseTimeThreshold ?? 2000,
            enabled: values.http?.enabled ?? true
          },
          instance: {
            unhealthyThreshold: values.instance?.unhealthyThreshold ?? 1,
            enabled: values.instance?.enabled ?? true
          },
          thread: {
            activeThreshold: values.thread?.activeThreshold ?? 90,
            enabled: values.thread?.enabled ?? true
          }
        }
      };

      const res = await api.post('/api/alerts/config', payload);
      if (res.data.code === 200) {
        message.success(t('alert.save_success') || 'Configuration saved successfully');
        loadConfig();
      } else {
        message.error(res.data.message || t('alert.save_failed'));
      }
    } catch (e: any) {
      message.error(e.message || t('alert.save_failed'));
    } finally {
      setSaving(false);
    }
  };

  // Send test email
  const handleTestEmail = async () => {
    if (emails.length === 0) {
      message.warning(t('alert.no_recipients') || 'Please add at least one email recipient');
      return;
    }

    try {
      const res = await api.post('/api/alerts/test', {
        recipients: emails,
        language: form.getFieldValue('emailLanguage')
      });
      if (res.data.code === 200) {
        message.success(t('alert.test_email_sent') || 'Test email sent successfully');
      } else {
        message.error(res.data.message || t('alert.test_email_failed'));
      }
    } catch (e: any) {
      message.error(e.message || t('alert.test_email_failed'));
    }
  };

  // Clear history
  const handleClearHistory = async () => {
    try {
      const params = instanceId ? `?instanceId=${instanceId}` : '';
      const res = await api.delete(`/api/alerts/history${params}`);
      if (res.data.code === 200) {
        message.success(res.data.message || 'History cleared');
        loadHistory();
      } else {
        message.error(res.data.message || 'Failed to clear history');
      }
    } catch (e: any) {
      message.error(e.message || 'Failed to clear history');
    }
  };

  // Get level color
  const getLevelColor = (level: string) => {
    switch (level) {
      case 'CRITICAL': return 'red';
      case 'ERROR': return 'orange';
      case 'WARNING': return 'gold';
      default: return 'blue';
    }
  };

  // History columns - 自适应列宽
  const historyColumns = [
    {
      title: t('alert.time') || 'Time',
      dataIndex: 'createdAt',
      key: 'createdAt',
      render: (text: string) => (
        <span style={{ color: '#cbd5e1', fontFamily: 'JetBrains Mono, monospace', fontSize: 13.5 }}>
          {new Date(text).toLocaleString()}
        </span>
      )
    },
    {
      title: t('alert.type') || 'Type',
      dataIndex: 'alertType',
      key: 'alertType',
      render: (text: string) => (
        <Tag className="type-tag">
          {text}
        </Tag>
      )
    },
    {
      title: t('alert.level') || 'Level',
      dataIndex: 'alertLevel',
      key: 'alertLevel',
      render: (text: string) => {
        // Map level to specific tag class
        const tagClass = text === 'WARNING' ? 'warning-tag' : 
                        text === 'CRITICAL' ? 'critical-tag' : 
                        text === 'ERROR' ? 'error-tag' : 'info-tag';
        return (
          <Tag className={tagClass}>
            {text}
          </Tag>
        );
      }
    },
    {
      title: t('alert.title') || 'Title',
      dataIndex: 'title',
      key: 'title',
      ellipsis: true,
      render: (text: string) => (
        <span style={{ color: '#e2e8f0', fontSize: 14, fontWeight: 500 }}>
          {text}
        </span>
      )
    },
    {
      title: t('alert.status') || 'Status',
      dataIndex: 'status',
      key: 'status',
      render: (text: string) => (
        <Tag className={text === 'SENT' ? 'sent-tag' : 'failed-tag'}>
          {text === 'SENT' ? (t('alert.sent') || '已发送') : (t('alert.failed') || '发送失败')}
        </Tag>
      )
    }
  ];

  if (loading) {
    return <div style={{ textAlign: 'center', padding: 100 }}><Spin size="large" /></div>;
  }

  return (
    <div className="alert-settings-container">
      <div className="alert-page-header">
        <div className="alert-header-title">
          <BellOutlined className="alert-header-icon" />
          <Title level={3} style={{ margin: 0 }}>{t('alert.title') || 'Alert Configuration'}</Title>
        </div>
        <Space className="alert-header-actions">
          <Popconfirm
            title={t('alert.test_email_confirm') || 'Send test email to all recipients?'}
            onConfirm={handleTestEmail}
          >
            <Button icon={<SendOutlined />} disabled={emails.length === 0} className="test-email-btn">
              {t('alert.test_email') || 'Test Email'}
            </Button>
          </Popconfirm>
          <Button type="primary" onClick={handleSave} loading={saving} className="save-config-btn">
            {t('alert.save_config') || 'Save Configuration'}
          </Button>
        </Space>
      </div>

      <Form form={form} layout="vertical" className="alert-form">
        <div className="settings-grid">
          {/* Email Settings */}
          <div className="settings-card-wrapper">
            <Card title={<Space><MailOutlined />{t('alert.email_settings') || 'Email Settings'}</Space>} className="settings-card email-settings-card">
              <Form.Item label={t('alert.email_recipients') || 'Email Recipients'}>
                <Space.Compact style={{ width: '100%' }}>
                  <Input
                    placeholder={t('alert.email_placeholder') || 'Enter email address'}
                    value={emailInput}
                    onChange={e => setEmailInput(e.target.value)}
                    onPressEnter={handleAddEmail}
                  />
                  <Button type="primary" icon={<PlusOutlined />} onClick={handleAddEmail}>
                    {t('common.add') || 'Add'}
                  </Button>
                </Space.Compact>
              </Form.Item>

              <div style={{ marginBottom: 16 }}>
                {emails.map(email => (
                  <Tag key={email} closable onClose={() => handleRemoveEmail(email)} style={{ marginBottom: 4 }}>
                    <MailOutlined style={{ marginRight: 4 }} />{email}
                  </Tag>
                ))}
                {emails.length === 0 && (
                  <Text type="secondary">{t('alert.no_emails') || 'No email recipients configured'}</Text>
                )}
              </div>

              <Form.Item name="emailLanguage" label={t('alert.email_language') || 'Email Language'}>
                <Select style={{ width: 200 }}>
                  <Select.Option value="zh">{t('alert.chinese') || '中文'}</Select.Option>
                  <Select.Option value="en">{t('alert.english') || 'English'}</Select.Option>
                </Select>
              </Form.Item>

              <Form.Item name="enabled" label={t('alert.enabled') || 'Enable Alerts'} valuePropName="checked">
                <Switch checkedChildren={t('common.enabled') || 'ON'} unCheckedChildren={t('common.disabled') || 'OFF'} className="alert-switch" />
              </Form.Item>
            </Card>
          </div>

          {/* Current Metrics */}
          <div className="settings-card-wrapper">
            <Card title={<Space><AlertOutlined />{t('alert.current_metrics') || 'Current Metrics'}</Space>} className="settings-card metrics-card">
              {currentMetrics ? (
                <Row gutter={[16, 16]}>
                  <Col span={12}>
                    <div className="metric-item">
                      <Text type="secondary">{t('monitor.process_cpu') || 'Process CPU'}</Text>
                      <div><Text strong style={{ fontSize: 20 }}>{currentMetrics.processCpuUsage?.toFixed(1)}%</Text></div>
                    </div>
                  </Col>
                  <Col span={12}>
                    <div className="metric-item">
                      <Text type="secondary">{t('monitor.system_cpu') || 'System CPU'}</Text>
                      <div><Text strong style={{ fontSize: 20 }}>{currentMetrics.systemCpuUsage?.toFixed(1)}%</Text></div>
                    </div>
                  </Col>
                  <Col span={12}>
                    <div className="metric-item">
                      <Text type="secondary">{t('monitor.heap_usage') || 'Heap Usage'}</Text>
                      <div><Text strong style={{ fontSize: 20 }}>{currentMetrics.heapUsagePercent?.toFixed(1)}%</Text></div>
                    </div>
                  </Col>
                  <Col span={12}>
                    <div className="metric-item">
                      <Text type="secondary">{t('monitor.error_rate') || 'Error Rate'}</Text>
                      <div><Text strong style={{ fontSize: 20 }}>{currentMetrics.errorRate?.toFixed(2)}%</Text></div>
                    </div>
                  </Col>
                  <Col span={12}>
                    <div className="metric-item">
                      <Text type="secondary">{t('alert.unhealthy_instances') || 'Unhealthy Instances'}</Text>
                      <div><Text strong style={{ fontSize: 20 }}>{currentMetrics.unhealthyInstances}/{currentMetrics.totalInstances}</Text></div>
                    </div>
                  </Col>
                  <Col span={12}>
                    <div className="metric-item">
                      <Text type="secondary">{t('alert.thread_usage') || 'Thread Usage'}</Text>
                      <div><Text strong style={{ fontSize: 20 }}>{currentMetrics.threadUsagePercent?.toFixed(1)}%</Text></div>
                    </div>
                  </Col>
                </Row>
              ) : (
                <Spin />
              )}
            </Card>
          </div>
        </div>

        {/* Alert History */}

        {/* Threshold Configuration */}
        <Card title={<Space><SettingOutlined />{t('alert.threshold_config') || 'Threshold Configuration'}</Space>} className="threshold-config-card">
          <div className="threshold-grid">
            {/* CPU */}
            <Card size="small" className="threshold-card threshold-card-cpu" title={
                <div className="threshold-card-title">
                  <Form.Item name={['cpu', 'enabled']} valuePropName="checked" noStyle>
                    <Switch size="small" className="threshold-switch" />
                  </Form.Item>
                  <span>CPU {t('alert.threshold') || 'Threshold'}</span>
                </div>
              }>
                <div className="threshold-card-content">
                  <div className="threshold-input-row">
                    <div className="threshold-input-col">
                      <label className="threshold-label">{t('alert.process_cpu') || 'Process CPU (%)'}</label>
                      <Form.Item name={['cpu', 'processThreshold']} noStyle>
                        <InputNumber className="threshold-input" min={0} max={100} placeholder="80" />
                      </Form.Item>
                    </div>
                    <div className="threshold-input-col">
                      <label className="threshold-label">{t('alert.system_cpu') || 'System CPU (%)'}</label>
                      <Form.Item name={['cpu', 'systemThreshold']} noStyle>
                        <InputNumber className="threshold-input" min={0} max={100} placeholder="90" />
                      </Form.Item>
                    </div>
                  </div>
                </div>
              </Card>

            {/* Memory */}
            <Card size="small" className="threshold-card threshold-card-memory" title={
                <div className="threshold-card-title">
                  <Form.Item name={['memory', 'enabled']} valuePropName="checked" noStyle>
                    <Switch size="small" className="threshold-switch" />
                  </Form.Item>
                  <span>{t('monitor.jvm_memory') || 'Memory'} {t('alert.threshold') || 'Threshold'}</span>
                </div>
              }>
                <div className="threshold-card-content">
                  <div className="threshold-input-row">
                    <div className="threshold-input-col">
                      <label className="threshold-label">{t('alert.heap_threshold') || 'Heap Usage (%)'}</label>
                      <Form.Item name={['memory', 'heapThreshold']} noStyle>
                        <InputNumber className="threshold-input" min={0} max={100} placeholder="85" />
                      </Form.Item>
                    </div>
                  </div>
                </div>
              </Card>

            {/* HTTP */}
            <Card size="small" className="threshold-card threshold-card-http" title={
                <div className="threshold-card-title">
                  <Form.Item name={['http', 'enabled']} valuePropName="checked" noStyle>
                    <Switch size="small" className="threshold-switch" />
                  </Form.Item>
                  <span>HTTP {t('alert.threshold') || 'Threshold'}</span>
                </div>
              }>
                <div className="threshold-card-content">
                  <div className="threshold-input-row">
                    <div className="threshold-input-col">
                      <label className="threshold-label">{t('alert.error_rate') || 'Error Rate (%)'}</label>
                      <Form.Item name={['http', 'errorRateThreshold']} noStyle>
                        <InputNumber className="threshold-input" min={0} max={100} placeholder="5" />
                      </Form.Item>
                    </div>
                    <div className="threshold-input-col">
                      <label className="threshold-label">{t('alert.response_time') || 'Response (ms)'}</label>
                      <Form.Item name={['http', 'responseTimeThreshold']} noStyle>
                        <InputNumber className="threshold-input" min={0} placeholder="2000" />
                      </Form.Item>
                    </div>
                  </div>
                </div>
              </Card>

            {/* Instance */}
            <Card size="small" className="threshold-card threshold-card-instance" title={
                <div className="threshold-card-title">
                  <Form.Item name={['instance', 'enabled']} valuePropName="checked" noStyle>
                    <Switch size="small" className="threshold-switch" />
                  </Form.Item>
                  <span>{t('alert.instance_health') || 'Instance Health'}</span>
                </div>
              }>
                <div className="threshold-card-content">
                  <div className="threshold-input-row">
                    <div className="threshold-input-col">
                      <label className="threshold-label">{t('alert.unhealthy_threshold') || 'Unhealthy Count'}</label>
                      <Form.Item name={['instance', 'unhealthyThreshold']} noStyle>
                        <InputNumber className="threshold-input" min={0} placeholder="1" />
                      </Form.Item>
                    </div>
                  </div>
                </div>
              </Card>

            {/* Thread */}
            <Card size="small" className="threshold-card threshold-card-thread" title={
                <div className="threshold-card-title">
                  <Form.Item name={['thread', 'enabled']} valuePropName="checked" noStyle>
                    <Switch size="small" className="threshold-switch" />
                  </Form.Item>
                  <span>{t('alert.thread_usage') || 'Thread Usage'}</span>
                </div>
              }>
                <div className="threshold-card-content">
                  <div className="threshold-input-row">
                    <div className="threshold-input-col">
                      <label className="threshold-label">{t('alert.thread_threshold') || 'Usage (%)'}</label>
                      <Form.Item name={['thread', 'activeThreshold']} noStyle>
                        <InputNumber className="threshold-input" min={0} max={100} placeholder="90" />
                      </Form.Item>
                    </div>
                  </div>
                </div>
              </Card>
            </div>
        </Card>
      </Form>

      {/* Alert History */}
      <Card 
        title={<Space><HistoryOutlined />{t('alert.history') || 'Alert History'}</Space>} 
        style={{ marginTop: 16 }}
        extra={
          <Space>
            <Popconfirm
              title={t('alert.clear_history_confirm') || 'Are you sure to clear all alert history?'}
              onConfirm={handleClearHistory}
              okText={t('common.confirm') || 'Confirm'}
              cancelText={t('common.cancel') || 'Cancel'}
            >
              <Button danger icon={<DeleteOutlined />} disabled={history.length === 0}>
                {t('alert.clear_history') || 'Clear All'}
              </Button>
            </Popconfirm>
            <Button onClick={loadHistory}>{t('common.refresh') || 'Refresh'}</Button>
          </Space>
        }
      >
        <Table
          dataSource={history}
          columns={historyColumns}
          rowKey="id"
          loading={historyLoading}
          pagination={{ 
            pageSize: 10,
            showSizeChanger: true,
            showQuickJumper: true,
            showTotal: (total) => `共 ${total} 条`
          }}
          className="alert-history-table"
          expandable={{
            expandedRowRender: (record) => (
              <div className="alert-detail-content">
                <p style={{ whiteSpace: 'pre-wrap', wordBreak: 'break-word', lineHeight: '1.75', color: '#e2e8f0', margin: 0 }}>
                  {record.content}
                </p>
              </div>
            )
          }}
        />
      </Card>

      <style>{`
        .alert-page { padding: 0 }
        .page-header { display: flex; justify-content: space-between; align-items: center; margin-bottom: 16px }
        .settings-card, .metrics-card { height: 100% }
        .metric-item { text-align: center; padding: 8px 0 }
      `}</style>
    </div>
  );
};

export default AlertPage;