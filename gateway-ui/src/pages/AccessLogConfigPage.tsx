import { useState, useEffect } from 'react';
import {
  Card, Row, Col, Form, Input, Select, Button, Switch, InputNumber,
  Space, message, Spin, Divider, Typography, Tag, Alert, Tooltip,
  Tabs, Table, DatePicker, Statistic, Badge, Tooltip as AntTooltip,
  Descriptions, Radio
} from 'antd';
import type { TabsProps } from 'antd';
import {
  FileTextOutlined, SettingOutlined, FolderOutlined,
  CloudServerOutlined, DesktopOutlined, ContainerOutlined,
  ReloadOutlined, SaveOutlined, InfoCircleOutlined,
  EyeOutlined, BarChartOutlined, FilterOutlined,
  SearchOutlined, ClockCircleOutlined
} from '@ant-design/icons';
import api from '../utils/api';
import { useTranslation } from 'react-i18next';
import dayjs from 'dayjs';
import utc from 'dayjs/plugin/utc';

dayjs.extend(utc);

const { Title, Text } = Typography;
const { RangePicker } = DatePicker;

interface AccessLogConfig {
  enabled: boolean;
  deployMode: string;
  logDirectory: string;
  fileNamePattern: string;
  logFormat: string;
  logLevel: string;
  logRequestHeaders: boolean;
  logResponseHeaders: boolean;
  logRequestBody: boolean;
  logResponseBody: boolean;
  maxBodyLength: number;
  samplingRate: number;
  sensitiveFields: string[];
  maxFileSizeMb: number;
  maxBackupFiles: number;
  logToConsole: boolean;
  includeAuthInfo: boolean;
}

interface DeployMode {
  mode: string;
  description: string;
  defaultPath: string;
}

interface LogEntry {
  '@timestamp': string;
  traceId?: string;
  requestId?: string;
  routeId?: string;
  serviceId?: string;
  method: string;
  path: string;
  query?: string;
  clientIp?: string;
  userAgent?: string;
  statusCode: number;
  durationMs: number;
  authType?: string;
  authPolicy?: string;
  authUser?: string;
  requestHeaders?: Record<string, string>;
  responseHeaders?: Record<string, string>;
  requestBody?: string;
  responseBody?: string;
  errorMessage?: string;
}

interface LogStats {
  totalRequests: number;
  avgDuration: number;
  statusCodes: Record<string, number>;
  topPaths: Array<{ path: string; count: number }>;
  methods: Record<string, number>;
  file?: string;
}

interface AccessLogConfigPageProps {
  instanceId?: string;
}

const AccessLogConfigPage: React.FC<AccessLogConfigPageProps> = ({ instanceId }) => {
  const [loading, setLoading] = useState(false);
  const [saving, setSaving] = useState(false);
  const [config, setConfig] = useState<AccessLogConfig | null>(null);
  const [deployModes, setDeployModes] = useState<DeployMode[]>([]);
  const [form] = Form.useForm();
  const { t } = useTranslation();

  // Log viewing state
  const [logLoading, setLogLoading] = useState(false);
  const [logEntries, setLogEntries] = useState<LogEntry[]>([]);
  const [logTotal, setLogTotal] = useState(0);
  const [logPage, setLogPage] = useState(0);
  const [logPageSize, setLogPageSize] = useState(20);
  const [logStats, setLogStats] = useState<LogStats | null>(null);
  const [selectedDate, setSelectedDate] = useState<string>('');
  const [filters, setFilters] = useState<{ method?: string; statusCode?: number; path?: string; traceId?: string }>({});
  const [bodyViewMode, setBodyViewMode] = useState<'raw' | 'formatted'>('formatted');

  // Load config
  const loadConfig = async () => {
    try {
      setLoading(true);
      const params = instanceId ? `?instanceId=${instanceId}` : '';
      const res = await api.get(`/api/access-log/config${params}`);
      if (res.data.code === 200) {
        setConfig(res.data.data);
        form.setFieldsValue(res.data.data);
      }
    } catch (e) {
      console.error('Failed to load config:', e);
      message.error(t('access_log.load_failed') || 'Failed to load configuration');
    } finally {
      setLoading(false);
    }
  };

  // Load deploy modes
  const loadDeployModes = async () => {
    try {
      const res = await api.get('/api/access-log/deploy-modes');
      if (res.data.code === 200) {
        setDeployModes(res.data.data);
      }
    } catch (e) {
      console.error('Failed to load deploy modes:', e);
    }
  };

  // Load log entries
  const loadLogEntries = async () => {
    try {
      setLogLoading(true);
      const params = new URLSearchParams();
      if (selectedDate) params.append('date', selectedDate);
      params.append('page', logPage.toString());
      params.append('size', logPageSize.toString());
      if (filters.method) params.append('method', filters.method);
      if (filters.statusCode) params.append('statusCode', filters.statusCode.toString());
      if (filters.path) params.append('path', filters.path);
      if (filters.traceId) params.append('traceId', filters.traceId);

      const res = await api.get(`/api/access-log/entries?${params.toString()}`);
      if (res.data.code === 200) {
        setLogEntries(res.data.data.entries);
        setLogTotal(res.data.data.total);
      }
    } catch (e) {
      console.error('Failed to load log entries:', e);
      message.error('Failed to load logs');
    } finally {
      setLogLoading(false);
    }
  };

  // Load log stats
  const loadLogStats = async () => {
    try {
      const params = selectedDate ? `?date=${selectedDate}` : '';
      const res = await api.get(`/api/access-log/stats${params}`);
      if (res.data.code === 200) {
        setLogStats(res.data.data);
      }
    } catch (e) {
      console.error('Failed to load log stats:', e);
    }
  };

  useEffect(() => {
    loadConfig();
    loadDeployModes();
  }, []);

  useEffect(() => {
    loadLogEntries();
    loadLogStats();
  }, [logPage, logPageSize, selectedDate, filters]);

  // Handle deploy mode change - auto-fill default path but allow customization
  const handleDeployModeChange = (mode: string) => {
    const selectedMode = deployModes.find(m => m.mode === mode);
    if (selectedMode) {
      // Auto-fill default path, but user can still modify it
      form.setFieldsValue({ logDirectory: selectedMode.defaultPath });
    }
  };

  // Save config
  const handleSave = async () => {
    try {
      setSaving(true);
      const values = await form.validateFields();

      const payload = {
        ...values,
        instanceId: instanceId,
        sensitiveFields: config?.sensitiveFields || [
          'password', 'token', 'authorization', 'secret',
          'apiKey', 'api_key', 'accessKey', 'access_key',
          'clientSecret', 'client_secret', 'cookie'
        ]
      };

      const res = await api.post('/api/access-log/config', payload);
      if (res.data.code === 200) {
        message.success(t('access_log.save_success') || 'Configuration saved successfully');
        setConfig(payload);
      } else {
        message.error(res.data.message || t('access_log.save_failed'));
      }
    } catch (e: any) {
      message.error(e.message || t('access_log.save_failed'));
    } finally {
      setSaving(false);
    }
  };

  // Reset to default
  const handleReset = async () => {
    try {
      const params = instanceId ? `?instanceId=${instanceId}` : '';
      const res = await api.post(`/api/access-log/reset${params}`);
      if (res.data.code === 200) {
        message.success(t('access_log.reset_success') || 'Configuration reset to default');
        loadConfig();
      } else {
        message.error(res.data.message || t('access_log.reset_failed'));
      }
    } catch (e: any) {
      message.error(e.message || t('access_log.reset_failed'));
    }
  };

  // Get deploy mode icon
  const getDeployModeIcon = (mode: string) => {
    switch (mode) {
      case 'LOCAL': return <DesktopOutlined />;
      case 'DOCKER': return <ContainerOutlined />;
      case 'K8S': return <CloudServerOutlined />;
      case 'CUSTOM': return <FolderOutlined />;
      default: return <FolderOutlined />;
    }
  };

  // Get status code color
  const getStatusColor = (code: number) => {
    if (code < 200) return 'default';
    if (code < 300) return 'success';
    if (code < 400) return 'processing';
    if (code < 500) return 'warning';
    return 'error';
  };

  // Log table columns
  const logColumns = [
    {
      title: 'Time',
      dataIndex: '@timestamp',
      key: 'timestamp',
      width: 180,
      render: (ts: string) => (
        <Text style={{ fontSize: 12 }}>
          <ClockCircleOutlined style={{ marginRight: 4 }} />
          {dayjs(ts).format('HH:mm:ss.SSS')}
        </Text>
      ),
    },
    {
      title: 'Method',
      dataIndex: 'method',
      key: 'method',
      width: 80,
      render: (method: string) => (
        <Tag color={method === 'GET' ? 'green' : method === 'POST' ? 'blue' : method === 'DELETE' ? 'red' : 'default'}>
          {method}
        </Tag>
      ),
    },
    {
      title: 'Path',
      dataIndex: 'path',
      key: 'path',
      ellipsis: true,
      render: (path: string) => <Text code style={{ fontSize: 12 }}>{path}</Text>,
    },
    {
      title: 'Status',
      dataIndex: 'statusCode',
      key: 'statusCode',
      width: 80,
      render: (code: number) => <Badge status={getStatusColor(code) as any} text={code} />,
    },
    {
      title: 'Duration',
      dataIndex: 'durationMs',
      key: 'durationMs',
      width: 100,
      render: (ms: number) => (
        <Text style={{ fontSize: 12, color: ms > 1000 ? '#ff4d4f' : ms > 500 ? '#faad14' : '#52c41a' }}>
          {ms}ms
        </Text>
      ),
    },
    {
      title: 'Client IP',
      dataIndex: 'clientIp',
      key: 'clientIp',
      width: 140,
      ellipsis: true,
    },
    {
      title: 'Auth',
      key: 'auth',
      width: 120,
      render: (_: any, record: LogEntry) => record.authType ? (
        <AntTooltip title={`Policy: ${record.authPolicy || '-'}`}>
          <Tag color="purple" style={{ fontSize: 11 }}>{record.authType}</Tag>
        </AntTooltip>
      ) : '-',
    },
    {
      title: 'Trace ID',
      dataIndex: 'traceId',
      key: 'traceId',
      width: 120,
      ellipsis: true,
      render: (id: string) => id ? <Text copyable style={{ fontSize: 11 }}>{id}</Text> : '-',
    },
  ];

  // Config Tab
  const ConfigTab = (
    <div style={{ padding: '16px 0' }}>
      <Form form={form} layout="vertical">
        <Row gutter={24}>
          {/* Basic Settings */}
          <Col span={12}>
            <Card
              title={<Space><SettingOutlined />{t('access_log.basic_settings') || 'Basic Settings'}</Space>}
              className="config-card"
            >
              <Form.Item
                name="enabled"
                label={t('access_log.enabled') || 'Enable Access Log'}
                valuePropName="checked"
              >
                <Switch
                  checkedChildren={t('common.enabled') || 'ON'}
                  unCheckedChildren={t('common.disabled') || 'OFF'}
                />
              </Form.Item>

              <Form.Item
                name="deployMode"
                label={
                  <Space>
                    {t('access_log.deploy_mode') || 'Deployment Mode'}
                    <Tooltip title={t('access_log.deploy_mode_tip') || 'Select deployment environment'}>
                      <InfoCircleOutlined style={{ color: '#8c8c8c' }} />
                    </Tooltip>
                  </Space>
                }
              >
                <Select onChange={handleDeployModeChange}>
                  {deployModes.map(mode => (
                    <Select.Option key={mode.mode} value={mode.mode}>
                      <Space>
                        {getDeployModeIcon(mode.mode)}
                        {mode.description}
                      </Space>
                    </Select.Option>
                  ))}
                </Select>
              </Form.Item>

              <Form.Item
                name="logDirectory"
                label={t('access_log.log_directory') || 'Log Directory'}
                extra={t('access_log.directory_hint') || 'Default path based on mode, you can customize'}
              >
                <Input
                  placeholder={t('access_log.directory_placeholder') || 'Enter log directory path'}
                  prefix={<FolderOutlined />}
                />
              </Form.Item>

              <Form.Item
                name="fileNamePattern"
                label={t('access_log.file_pattern') || 'File Name Pattern'}
                extra={t('access_log.pattern_extra') || 'Supports {yyyy-MM-dd} placeholder'}
              >
                <Input placeholder="access-{yyyy-MM-dd}.log" />
              </Form.Item>

              <Form.Item
                name="logFormat"
                label={t('access_log.log_format') || 'Log Format'}
              >
                <Select>
                  <Select.Option value="JSON">
                    <Tag color="blue">JSON</Tag>
                    {t('access_log.json_desc') || 'Recommended for log aggregation'}
                  </Select.Option>
                  <Select.Option value="TEXT">
                    <Tag color="green">TEXT</Tag>
                    {t('access_log.text_desc') || 'Human-readable format'}
                  </Select.Option>
                </Select>
              </Form.Item>

              <Form.Item
                name="logLevel"
                label={t('access_log.log_level') || 'Log Level'}
              >
                <Select>
                  <Select.Option value="MINIMAL">
                    <Tag color="default">MINIMAL</Tag>
                    {t('access_log.level_minimal') || 'Request line, status, duration'}
                  </Select.Option>
                  <Select.Option value="NORMAL">
                    <Tag color="blue">NORMAL</Tag>
                    {t('access_log.level_normal') || '+ headers, auth info'}
                  </Select.Option>
                  <Select.Option value="VERBOSE">
                    <Tag color="purple">VERBOSE</Tag>
                    {t('access_log.level_verbose') || '+ request/response body'}
                  </Select.Option>
                </Select>
              </Form.Item>
            </Card>
          </Col>

          {/* Log Content Settings */}
          <Col span={12}>
            <Card
              title={<Space><FileTextOutlined />{t('access_log.content_settings') || 'Log Content Settings'}</Space>}
              className="config-card"
            >
              <Form.Item
                name="logRequestHeaders"
                label={t('access_log.log_request_headers') || 'Log Request Headers'}
                valuePropName="checked"
              >
                <Switch size="small" />
              </Form.Item>

              <Form.Item
                name="logResponseHeaders"
                label={t('access_log.log_response_headers') || 'Log Response Headers'}
                valuePropName="checked"
              >
                <Switch size="small" />
              </Form.Item>

              <Form.Item
                name="logRequestBody"
                label={t('access_log.log_request_body') || 'Log Request Body'}
                valuePropName="checked"
              >
                <Switch size="small" />
              </Form.Item>

              <Form.Item
                name="logResponseBody"
                label={t('access_log.log_response_body') || 'Log Response Body'}
                valuePropName="checked"
              >
                <Switch size="small" />
              </Form.Item>

              <Form.Item
                name="includeAuthInfo"
                label={t('access_log.include_auth_info') || 'Include Auth Info'}
                valuePropName="checked"
                extra={t('access_log.auth_info_extra') || 'Include authType, authPolicy, authUser'}
              >
                <Switch size="small" />
              </Form.Item>

              <Divider />

              <Form.Item
                name="maxBodyLength"
                label={t('access_log.max_body_length') || 'Max Body Length'}
                extra={t('access_log.max_body_extra') || 'Characters to log (truncated if exceeded)'}
              >
                <InputNumber min={0} max={10000} style={{ width: '100%' }} />
              </Form.Item>

              <Form.Item
                name="samplingRate"
                label={t('access_log.sampling_rate') || 'Sampling Rate (%)'}
                extra={t('access_log.sampling_extra') || '100 means log all requests'}
              >
                <InputNumber min={1} max={100} style={{ width: '100%' }} />
              </Form.Item>
            </Card>
          </Col>
        </Row>

        {/* File Settings */}
        <Card
          title={<Space><FolderOutlined />{t('access_log.file_settings') || 'File Settings'}</Space>}
          className="config-card"
          style={{ marginTop: 16 }}
        >
          <Row gutter={24}>
            <Col span={8}>
              <Form.Item
                name="maxFileSizeMb"
                label={t('access_log.max_file_size') || 'Max File Size (MB)'}
              >
                <InputNumber min={1} max={1000} style={{ width: '100%' }} />
              </Form.Item>
            </Col>
            <Col span={8}>
              <Form.Item
                name="maxBackupFiles"
                label={t('access_log.max_backup_files') || 'Max Backup Files'}
              >
                <InputNumber min={0} max={100} style={{ width: '100%' }} />
              </Form.Item>
            </Col>
            <Col span={8}>
              <Form.Item
                name="logToConsole"
                label={t('access_log.log_to_console') || 'Log to Console'}
                valuePropName="checked"
                extra={t('access_log.console_extra') || 'Also output to console'}
              >
                <Switch />
              </Form.Item>
            </Col>
          </Row>
        </Card>
      </Form>
    </div>
  );

  // Log Viewer Tab
  const LogViewerTab = (
    <div style={{ padding: '16px 0' }}>
      {/* Stats */}
      {logStats && (
        <Row gutter={16} style={{ marginBottom: 16 }}>
          <Col span={4}>
            <Card size="small">
              <Statistic title="Total Requests" value={logStats.totalRequests} />
            </Card>
          </Col>
          <Col span={4}>
            <Card size="small">
              <Statistic title="Avg Duration" value={logStats.avgDuration} suffix="ms" />
            </Card>
          </Col>
          <Col span={8}>
            <Card size="small" title="Status Codes">
              <Space>
                {Object.entries(logStats.statusCodes).map(([code, count]) => (
                  <Tag key={code} color={getStatusColor(parseInt(code)) as any}>
                    {code}: {count}
                  </Tag>
                ))}
              </Space>
            </Card>
          </Col>
          <Col span={8}>
            <Card size="small" title="Methods">
              <Space>
                {Object.entries(logStats.methods).map(([method, count]) => (
                  <Tag key={method} color={method === 'GET' ? 'green' : method === 'POST' ? 'blue' : 'default'}>
                    {method}: {count}
                  </Tag>
                ))}
              </Space>
            </Card>
          </Col>
        </Row>
      )}

      {/* Filters */}
      <Card size="small" style={{ marginBottom: 16 }}>
        <Space wrap>
          <DatePicker
            placeholder="Select Date"
            onChange={(date) => setSelectedDate(date ? date.format('YYYY-MM-DD') : '')}
            allowClear
          />
          <Input
            placeholder="Path contains..."
            prefix={<SearchOutlined />}
            style={{ width: 200 }}
            onPressEnter={(e) => setFilters({ ...filters, path: (e.target as HTMLInputElement).value })}
            allowClear
          />
          <Select
            placeholder="Method"
            style={{ width: 100 }}
            allowClear
            onChange={(v) => setFilters({ ...filters, method: v || undefined })}
          >
            <Select.Option value="GET">GET</Select.Option>
            <Select.Option value="POST">POST</Select.Option>
            <Select.Option value="PUT">PUT</Select.Option>
            <Select.Option value="DELETE">DELETE</Select.Option>
          </Select>
          <Select
            placeholder="Status"
            style={{ width: 100 }}
            allowClear
            onChange={(v) => setFilters({ ...filters, statusCode: v || undefined })}
          >
            <Select.Option value={200}>2xx</Select.Option>
            <Select.Option value={400}>4xx</Select.Option>
            <Select.Option value={500}>5xx</Select.Option>
          </Select>
          <Input
            placeholder="Trace ID"
            prefix={<SearchOutlined />}
            style={{ width: 150 }}
            onPressEnter={(e) => setFilters({ ...filters, traceId: (e.target as HTMLInputElement).value })}
            allowClear
          />
          <Button icon={<ReloadOutlined />} onClick={() => { loadLogEntries(); loadLogStats(); }}>
            Refresh
          </Button>
        </Space>
      </Card>

      {/* Log Table */}
      <Table
        dataSource={logEntries}
        columns={logColumns}
        rowKey={(record) => record.requestId || record['@timestamp']}
        loading={logLoading}
        size="small"
        pagination={{
          current: logPage + 1,
          pageSize: logPageSize,
          total: logTotal,
          showSizeChanger: true,
          showTotal: (total) => `Total ${total} entries`,
          onChange: (page, size) => {
            setLogPage(page - 1);
            setLogPageSize(size);
          },
        }}
        scroll={{ x: 1200 }}
        expandable={{
          expandedRowRender: (record) => {
            const formatTime = (ts: string) => {
              if (!ts) return '-';
              return dayjs(ts).local().format('YYYY-MM-DD HH:mm:ss.SSS');
            };

            // Raw JSON display (one line, no formatting)
            const renderRawJson = (str: string | undefined) => {
              if (!str) return null;
              try {
                const parsed = JSON.parse(str);
                return JSON.stringify(parsed);  // Compact single line
              } catch {
                return str;
              }
            };

            // Formatted JSON with syntax highlighting (compact 1-space indent)
            const renderFormattedJson = (str: string | undefined) => {
              if (!str) return null;
              try {
                const parsed = JSON.parse(str);
                const formatted = JSON.stringify(parsed, null, 1);  // 只用1个空格缩进，更紧凑
                // Apply syntax highlighting colors
                return formatted
                  .replace(/"([^"]+)":/g, '<span style="color: #4ec9b0">"$1"</span>:')
                  .replace(/: "([^"]*)"/g, ': <span style="color: #ce9178">"$1"</span>')
                  .replace(/: (\d+\.?\d*)/g, ': <span style="color: #b5cea8">$1</span>')
                  .replace(/: (true|false|null)/g, ': <span style="color: #569cd6">$1</span>')
                  .replace(/\{/g, '<span style="color: #ffd700">{</span>')
                  .replace(/\}/g, '<span style="color: #ffd700">}</span>')
                  .replace(/\[/g, '<span style="color: #ffd700">[</span>')
                  .replace(/\]/g, '<span style="color: #ffd700">]</span>');
              } catch {
                return str;
              }
            };

            const reqSize = record.requestHeaders?.['content-length'] || record.requestHeaders?.['Content-Length'] || (record.requestBody ? `${record.requestBody.length}` : '-');
            const resSize = record.responseHeaders?.['content-length'] || record.responseHeaders?.['Content-Length'] || (record.responseBody ? `${record.responseBody.length}` : '-');
            const contentType = record.requestHeaders?.['content-type'] || record.requestHeaders?.['Content-Type'] || '-';

            return (
              <div style={{ padding: 16, background: '#fafafa', borderRadius: 4 }}>
                {/* 第一行：基本信息 */}
                <Descriptions 
                  size="small" 
                  column={4}
                  labelStyle={{ width: 80, color: '#666' }}
                  style={{ marginBottom: 8 }}
                >
                  <Descriptions.Item label="时间">{formatTime(record['@timestamp'])}</Descriptions.Item>
                  <Descriptions.Item label="状态码">
                    <Badge status={getStatusColor(record.statusCode) as any} text={<Text strong>{record.statusCode}</Text>} />
                  </Descriptions.Item>
                  <Descriptions.Item label="耗时">
                    <Text style={{ color: record.durationMs > 1000 ? '#ff4d4f' : record.durationMs > 500 ? '#faad14' : '#52c41a', fontWeight: 'bold' }}>
                      {record.durationMs} ms
                    </Text>
                  </Descriptions.Item>
                  <Descriptions.Item label="客户端IP">{record.clientIp || '-'}</Descriptions.Item>
                </Descriptions>

                {/* 第二行：请求信息 */}
                <Descriptions 
                  size="small" 
                  column={3}
                  labelStyle={{ width: 80, color: '#666' }}
                  style={{ marginBottom: 8 }}
                >
                  <Descriptions.Item label="方法">
                    <Tag color={record.method === 'GET' ? 'green' : record.method === 'POST' ? 'blue' : record.method === 'DELETE' ? 'red' : 'default'}>
                      {record.method}
                    </Tag>
                  </Descriptions.Item>
                  <Descriptions.Item label="路径">
                    <Text code style={{ fontSize: 12, wordBreak: 'break-all' }}>{record.path}</Text>
                  </Descriptions.Item>
                  <Descriptions.Item label="查询参数">
                    {record.query ? <Text code style={{ fontSize: 12, wordBreak: 'break-all' }}>{record.query}</Text> : '-'}
                  </Descriptions.Item>
                </Descriptions>

                {/* 第三行：ID信息 */}
                <Descriptions 
                  size="small" 
                  column={4}
                  labelStyle={{ width: 80, color: '#666' }}
                  style={{ marginBottom: 8 }}
                >
                  <Descriptions.Item label="请求ID">
                    <Text copyable code style={{ fontSize: 11 }}>{record.requestId}</Text>
                  </Descriptions.Item>
                  <Descriptions.Item label="Trace ID">
                    {record.traceId ? <Text copyable code style={{ fontSize: 11 }}>{record.traceId}</Text> : '-'}
                  </Descriptions.Item>
                  <Descriptions.Item label="请求大小">{reqSize}</Descriptions.Item>
                  <Descriptions.Item label="响应大小">{resSize}</Descriptions.Item>
                </Descriptions>

                {/* 第四行：认证和路由信息 */}
                {(record.authType || record.routeId || record.serviceId) && (
                  <Descriptions 
                    size="small" 
                    column={3}
                    labelStyle={{ width: 80, color: '#666' }}
                    style={{ marginBottom: 8 }}
                  >
                    {record.authType && <Descriptions.Item label="认证"><Tag color="purple">{record.authType}</Tag></Descriptions.Item>}
                    {record.authPolicy && <Descriptions.Item label="策略"><Text code style={{ fontSize: 11 }}>{record.authPolicy}</Text></Descriptions.Item>}
                    {record.authUser && <Descriptions.Item label="用户">{record.authUser}</Descriptions.Item>}
                  </Descriptions>
                )}
                
                {/* 第五行：路由和服务信息（单独一行，更宽的显示） */}
                {(record.routeId || record.serviceId) && (
                  <Descriptions 
                    size="small" 
                    column={2}
                    labelStyle={{ width: 80, color: '#666' }}
                    style={{ marginBottom: 8 }}
                  >
                    {record.routeId && <Descriptions.Item label="路由ID">
                      <Text code copyable style={{ fontSize: 11, wordBreak: 'break-all' }}>{record.routeId}</Text>
                    </Descriptions.Item>}
                    {record.serviceId && <Descriptions.Item label="服务ID">
                      <Text code copyable style={{ fontSize: 11 }}>{record.serviceId}</Text>
                    </Descriptions.Item>}
                  </Descriptions>
                )}

                {/* 请求体 */}
                <div style={{ marginTop: 12 }}>
                  <div style={{ 
                    padding: '6px 12px', 
                    background: '#e6f7ff', 
                    border: '1px solid #91d5ff',
                    borderRadius: '4px 4px 0 0',
                    fontWeight: 500,
                    fontSize: 13,
                    color: '#1890ff'
                  }}>
                    📤 请求体 (Request Body) {record.requestBody && <span style={{ fontWeight: 400, color: '#666' }}>({record.requestBody.length} bytes)</span>}
                    <Radio.Group 
                      size="small" 
                      value={bodyViewMode} 
                      onChange={(e) => setBodyViewMode(e.target.value)}
                      style={{ marginLeft: 16 }}
                    >
                      <Radio.Button value="formatted">格式化</Radio.Button>
                      <Radio.Button value="raw">原始</Radio.Button>
                    </Radio.Group>
                  </div>
                  <div style={{ 
                    padding: 12, 
                    background: '#1e1e1e', 
                    borderRadius: '0 0 4px 4px',
                    maxHeight: 250,
                    overflow: 'auto'
                  }}>
                    {record.requestBody ? (
                      bodyViewMode === 'formatted' ? (
                        <pre 
                          style={{ 
                            margin: 0, 
                            fontSize: 12, 
                            color: '#d4d4d4',
                            whiteSpace: 'pre',
                            fontFamily: 'Consolas, Monaco, "Courier New", monospace',
                            lineHeight: 1.5,
                            padding: 0
                          }}
                          dangerouslySetInnerHTML={{ __html: renderFormattedJson(record.requestBody) || '' }}
                        />
                      ) : (
                        <Text style={{ color: '#ce9178', fontSize: 12, fontFamily: 'Consolas, Monaco, "Courier New", monospace', wordBreak: 'break-all' }}>
                          {renderRawJson(record.requestBody)}
                        </Text>
                      )
                    ) : <Text style={{ color: '#666' }}>未记录</Text>}
                  </div>
                </div>

                {/* 响应体 */}
                <div style={{ marginTop: 12 }}>
                  <div style={{ 
                    padding: '6px 12px', 
                    background: '#f6ffed', 
                    border: '1px solid #b7eb8f',
                    borderRadius: '4px 4px 0 0',
                    fontWeight: 500,
                    fontSize: 13,
                    color: '#52c41a'
                  }}>
                    📥 响应体 (Response Body) {record.responseBody && <span style={{ fontWeight: 400, color: '#666' }}>({record.responseBody.length} bytes)</span>}
                  </div>
                  <div style={{ 
                    padding: 12, 
                    background: '#1e1e1e', 
                    borderRadius: '0 0 4px 4px',
                    maxHeight: 300,
                    overflow: 'auto'
                  }}>
                    {record.responseBody ? (
                      bodyViewMode === 'formatted' ? (
                        <pre 
                          style={{ 
                            margin: 0, 
                            fontSize: 12, 
                            color: '#d4d4d4',
                            whiteSpace: 'pre',
                            fontFamily: 'Consolas, Monaco, "Courier New", monospace',
                            lineHeight: 1.5,
                            padding: 0
                          }}
                          dangerouslySetInnerHTML={{ __html: renderFormattedJson(record.responseBody) || '' }}
                        />
                      ) : (
                        <Text style={{ color: '#ce9178', fontSize: 12, fontFamily: 'Consolas, Monaco, "Courier New", monospace', wordBreak: 'break-all' }}>
                          {renderRawJson(record.responseBody)}
                        </Text>
                      )
                    ) : <Text style={{ color: '#666' }}>未记录</Text>}
                  </div>
                </div>

                {/* 错误信息 */}
                {record.errorMessage && (
                  <Alert 
                    message="错误信息" 
                    description={record.errorMessage} 
                    type="error" 
                    showIcon 
                    style={{ marginTop: 12 }} 
                  />
                )}
              </div>
            );
          },
        }}
      />
    </div>
  );

  const tabItems: TabsProps['items'] = [
    {
      key: 'config',
      label: (
        <Space>
          <SettingOutlined />
          {t('access_log.config_tab') || 'Configuration'}
        </Space>
      ),
      children: ConfigTab,
    },
    {
      key: 'logs',
      label: (
        <Space>
          <EyeOutlined />
          {t('access_log.viewer_tab') || 'View Logs'}
          {logTotal > 0 && <Badge count={logTotal} style={{ backgroundColor: '#52c41a' }} />}
        </Space>
      ),
      children: LogViewerTab,
    },
  ];

  if (loading) {
    return <div style={{ textAlign: 'center', padding: 100 }}><Spin size="large" /></div>;
  }

  return (
    <div className="access-log-config-page">
      <div className="page-header">
        <div className="header-title">
          <FileTextOutlined className="header-icon" />
          <Title level={3} style={{ margin: 0 }}>{t('access_log.title') || 'Access Log'}</Title>
        </div>
        <Space className="header-actions">
          <Button icon={<ReloadOutlined />} onClick={loadConfig}>
            {t('common.refresh') || 'Refresh'}
          </Button>
          <Button onClick={handleReset}>
            {t('access_log.reset') || 'Reset Default'}
          </Button>
          <Button type="primary" icon={<SaveOutlined />} onClick={handleSave} loading={saving}>
            {t('common.save') || 'Save'}
          </Button>
        </Space>
      </div>

      <Tabs defaultActiveKey="config" items={tabItems} />

      <style>{`
        .access-log-config-page { padding: 0 }
        .page-header {
          display: flex;
          justify-content: space-between;
          align-items: center;
          margin-bottom: 8px;
          padding: 16px 0;
        }
        .header-title {
          display: flex;
          align-items: center;
          gap: 12px;
        }
        .header-icon {
          font-size: 24px;
          color: #165DFF;
        }
        .config-card {
          height: 100%;
        }
        .config-card .ant-card-head-title {
          color: #e2e8f0;
        }
        .ant-tabs-tab {
          padding: 8px 16px !important;
        }
      `}</style>
    </div>
  );
};

export default AccessLogConfigPage;