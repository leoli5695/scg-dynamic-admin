import { useState, useEffect } from 'react';
import {
  Card, Button, Space, Modal, message, Spin, Tag, Form, Input, Select,
  Empty, Dropdown, Tooltip, Badge, Divider, Typography, Switch, Radio, InputNumber, Row, Col, Collapse
} from 'antd';
import {
  PlusOutlined, DeleteOutlined, EditOutlined, MoreOutlined,
  ThunderboltOutlined, StopOutlined, PlayCircleOutlined, GlobalOutlined,
  ApiOutlined, SafetyOutlined, ClockCircleOutlined, KeyOutlined,
  SyncOutlined, FileTextOutlined, SettingOutlined,
  CloudOutlined, SecurityScanOutlined, NumberOutlined, LockOutlined, UserOutlined
} from '@ant-design/icons';
import type { MenuProps } from 'antd';
import api from '../utils/api';
import { useTranslation } from 'react-i18next';
import './StrategiesPage.premium.css';

const { Text, Title } = Typography;
const { Panel } = Collapse;

interface StrategyDefinition {
  strategyId: string;
  strategyName: string;
  strategyType: string;
  scope: 'GLOBAL' | 'ROUTE';
  routeId?: string;
  priority: number;
  enabled: boolean;
  config: Record<string, any>;
  description?: string;
}

const StrategiesPage: React.FC = () => {
  const [strategies, setStrategies] = useState<StrategyDefinition[]>([]);
  const [loading, setLoading] = useState(false);
  const [createModalVisible, setCreateModalVisible] = useState(false);
  const [editModalVisible, setEditModalVisible] = useState(false);
  const [selectedStrategy, setSelectedStrategy] = useState<StrategyDefinition | null>(null);
  const [filterType, setFilterType] = useState<string>('all');
  const [filterScope, setFilterScope] = useState<string>('all');
  const [createForm] = Form.useForm();
  const [editForm] = Form.useForm();
  const { t } = useTranslation();

  useEffect(() => {
    loadStrategies();
  }, []);

  const loadStrategies = async () => {
    try {
      setLoading(true);
      const response = await api.get('/api/strategies');
      if (response.data.code === 200) {
        setStrategies(response.data.data || []);
      }
    } catch (error: any) {
      message.error(t('message.load_strategies_failed', { error: error.message }));
    } finally {
      setLoading(false);
    }
  };

  const filteredStrategies = strategies.filter(s => {
    const matchesType = filterType === 'all' || s.strategyType === filterType;
    const matchesScope = filterScope === 'all' || s.scope === filterScope;
    return matchesType && matchesScope;
  });

  const totalStrategies = strategies.length;
  const enabledStrategies = strategies.filter(s => s.enabled).length;
  const globalStrategies = strategies.filter(s => s.scope === 'GLOBAL').length;

  const getStrategyTypeLabel = (type: string) => {
    const labels: Record<string, string> = {
      'RATE_LIMITER': t('strategy.type.rate_limiter'),
      'IP_FILTER': t('strategy.type.ip_filter'),
      'TIMEOUT': t('strategy.type.timeout'),
      'CIRCUIT_BREAKER': t('strategy.type.circuit_breaker'),
      'AUTH': t('strategy.type.auth'),
      'RETRY': t('strategy.type.retry'),
      'CORS': t('strategy.type.cors'),
      'ACCESS_LOG': t('strategy.type.access_log'),
      'HEADER_OP': t('strategy.type.header_op'),
      'CACHE': t('strategy.type.cache'),
      'SECURITY': t('strategy.type.security'),
      'API_VERSION': t('strategy.type.api_version'),
    };
    return labels[type] || type;
  };

  const getStrategyTypeIcon = (type: string) => {
    const icons: Record<string, React.ReactNode> = {
      'RATE_LIMITER': <ThunderboltOutlined />,
      'IP_FILTER': <SafetyOutlined />,
      'TIMEOUT': <ClockCircleOutlined />,
      'CIRCUIT_BREAKER': <StopOutlined />,
      'AUTH': <KeyOutlined />,
      'RETRY': <SyncOutlined />,
      'CORS': <GlobalOutlined />,
      'ACCESS_LOG': <FileTextOutlined />,
      'HEADER_OP': <SettingOutlined />,
      'CACHE': <CloudOutlined />,
      'SECURITY': <SecurityScanOutlined />,
      'API_VERSION': <NumberOutlined />,
    };
    return icons[type] || <ThunderboltOutlined />;
  };

  const getStrategyTypeColor = (type: string) => {
    const colors: Record<string, string> = {
      'RATE_LIMITER': '#722ed1',
      'IP_FILTER': '#13c2c2',
      'TIMEOUT': '#fa8c16',
      'CIRCUIT_BREAKER': '#f5222d',
      'AUTH': '#2f54eb',
      'RETRY': '#52c41a',
      'CORS': '#1890ff',
      'ACCESS_LOG': '#722ed1',
      'HEADER_OP': '#13c2c2',
      'CACHE': '#faad14',
      'SECURITY': '#eb2f96',
      'API_VERSION': '#52c41a',
    };
    return colors[type] || '#1890ff';
  };

  const buildConfig = (values: any, strategyType: string) => {
    switch (strategyType) {
      case 'RATE_LIMITER':
        return {
          qps: parseInt(values.qps) || 100,
          burstCapacity: parseInt(values.burstCapacity) || 200,
          timeUnit: values.timeUnit || 'second',
          keyResolver: values.keyResolver || 'ip',
          keyType: 'combined',
          headerName: values.headerName,
        };
      case 'IP_FILTER':
        return {
          mode: values.mode || 'blacklist',
          ipList: values.ipList ? values.ipList.split(',').map((ip: string) => ip.trim()) : [],
        };
      case 'TIMEOUT':
        return {
          connectTimeout: parseInt(values.connectTimeout) || 5000,
          responseTimeout: parseInt(values.responseTimeout) || 30000,
        };
      case 'CIRCUIT_BREAKER':
        return {
          failureRateThreshold: parseFloat(values.failureRateThreshold) || 50,
          slowCallDurationThreshold: parseInt(values.slowCallDurationThreshold) || 60000,
          slowCallRateThreshold: 80.0,
          waitDurationInOpenState: parseInt(values.waitDurationInOpenState) || 30000,
          slidingWindowSize: parseInt(values.slidingWindowSize) || 10,
          minimumNumberOfCalls: parseInt(values.minimumNumberOfCalls) || 5,
          automaticTransitionFromOpenToHalfOpenEnabled: true,
        };
      case 'AUTH':
        return {
          authType: values.authType || 'JWT',
          secretKey: values.secretKey,
          apiKey: values.apiKey,
          clientId: values.clientId,
          clientSecret: values.clientSecret,
          tokenEndpoint: values.tokenEndpoint,
        };
      case 'RETRY':
        return {
          maxAttempts: parseInt(values.maxAttempts) || 3,
          retryIntervalMs: parseInt(values.retryIntervalMs) || 1000,
          retryOnStatusCodes: values.retryOnStatusCodes ? values.retryOnStatusCodes.split(',').map((s: string) => parseInt(s.trim())) : [500, 502, 503, 504],
        };
      case 'CORS':
        return {
          allowedOrigins: values.allowedOrigins ? values.allowedOrigins.split(',').map((s: string) => s.trim()) : ['*'],
          allowedMethods: values.allowedMethods ? values.allowedMethods.split(',').map((s: string) => s.trim()) : ['GET', 'POST', 'PUT', 'DELETE'],
          allowedHeaders: values.allowedHeaders ? values.allowedHeaders.split(',').map((s: string) => s.trim()) : ['*'],
          allowCredentials: values.allowCredentials || false,
          maxAge: parseInt(values.maxAge) || 3600,
        };
      case 'ACCESS_LOG':
        return {
          logLevel: values.logLevel || 'NORMAL',
          logRequestHeaders: values.logRequestHeaders !== false,
          logResponseHeaders: values.logResponseHeaders !== false,
          logRequestBody: values.logRequestBody || false,
          logResponseBody: values.logResponseBody || false,
          maxBodyLength: parseInt(values.maxBodyLength) || 1000,
          samplingRate: parseInt(values.samplingRate) || 100,
        };
      case 'HEADER_OP':
        return {
          addRequestHeaders: values.addRequestHeaders ? JSON.parse(values.addRequestHeaders) : {},
          addResponseHeaders: values.addResponseHeaders ? JSON.parse(values.addResponseHeaders) : {},
          enableTraceId: values.enableTraceId !== false,
          traceIdHeader: values.traceIdHeader || 'X-Trace-Id',
        };
      case 'CACHE':
        return {
          ttlSeconds: parseInt(values.ttlSeconds) || 60,
          maxSize: parseInt(values.maxSize) || 10000,
          cacheMethods: values.cacheMethods ? values.cacheMethods.split(',').map((s: string) => s.trim()) : ['GET', 'HEAD'],
        };
      case 'SECURITY':
        return {
          enableSqlInjectionProtection: values.enableSqlInjectionProtection !== false,
          enableXssProtection: values.enableXssProtection !== false,
          mode: values.mode || 'BLOCK',
          checkParameters: values.checkParameters !== false,
          checkBody: values.checkBody !== false,
        };
      case 'API_VERSION':
        return {
          versionMode: values.versionMode || 'PATH',
          defaultVersion: values.defaultVersion || 'v1',
          versionHeader: values.versionHeader || 'X-API-Version',
          versionLocation: values.versionLocation || 'HEADER',
        };
      default:
        return {};
    }
  };

  const handleCreate = async (values: any) => {
    try {
      const strategy: StrategyDefinition = {
        strategyId: '',
        strategyName: values.strategyName,
        strategyType: values.strategyType,
        scope: values.scope,
        routeId: values.scope === 'ROUTE' ? values.routeId : undefined,
        priority: values.priority || 100,
        enabled: values.enabled !== false,
        config: buildConfig(values, values.strategyType),
        description: values.description,
      };

      const response = await api.post('/api/strategies', strategy);
      if (response.data.code === 200) {
        message.success(t('message.create_success'));
        createForm.resetFields();
        setCreateModalVisible(false);
        loadStrategies();
      } else {
        message.error(response.data.message);
      }
    } catch (error: any) {
      message.error(error.response?.data?.message || error.message);
    }
  };

  const handleUpdate = async (values: any) => {
    if (!selectedStrategy) return;

    try {
      const strategy: StrategyDefinition = {
        ...selectedStrategy,
        strategyName: values.strategyName,
        scope: values.scope,
        routeId: values.scope === 'ROUTE' ? values.routeId : undefined,
        priority: values.priority || 100,
        enabled: values.enabled !== false,
        description: values.description,
        config: buildConfig(values, selectedStrategy.strategyType),
      };

      const response = await api.put(`/api/strategies/${selectedStrategy.strategyId}`, strategy);
      if (response.data.code === 200) {
        message.success(t('message.update_success'));
        editForm.resetFields();
        setEditModalVisible(false);
        setSelectedStrategy(null);
        loadStrategies();
      } else {
        message.error(response.data.message);
      }
    } catch (error: any) {
      message.error(error.response?.data?.message || error.message);
    }
  };

  const handleDelete = (strategy: StrategyDefinition) => {
    Modal.confirm({
      title: t('common.confirm'),
      content: t('message.confirm_delete_strategy', { name: strategy.strategyName }),
      okText: t('common.delete'),
      okType: 'danger',
      cancelText: t('common.cancel'),
      onOk: async () => {
        try {
          const response = await api.delete(`/api/strategies/${strategy.strategyId}`);
          if (response.data.code === 200) {
            message.success(t('message.delete_success'));
            loadStrategies();
          } else {
            message.error(response.data.message);
          }
        } catch (error: any) {
          message.error(error.response?.data?.message || error.message);
        }
      },
    });
  };

  const handleEnable = async (strategy: StrategyDefinition) => {
    try {
      const response = await api.post(`/api/strategies/${strategy.strategyId}/enable`);
      if (response.data.code === 200) {
        message.success(t('message.enable_success'));
        loadStrategies();
      } else {
        message.error(response.data.message);
      }
    } catch (error: any) {
      message.error(error.response?.data?.message || error.message);
    }
  };

  const handleDisable = async (strategy: StrategyDefinition) => {
    try {
      const response = await api.post(`/api/strategies/${strategy.strategyId}/disable`);
      if (response.data.code === 200) {
        message.success(t('message.disable_success'));
        loadStrategies();
      } else {
        message.error(response.data.message);
      }
    } catch (error: any) {
      message.error(error.response?.data?.message || error.message);
    }
  };

  const openEditModal = (strategy: StrategyDefinition) => {
    setSelectedStrategy(strategy);
    editForm.setFieldsValue({
      strategyName: strategy.strategyName,
      scope: strategy.scope,
      routeId: strategy.routeId,
      priority: strategy.priority,
      enabled: strategy.enabled,
      description: strategy.description,
      ...strategy.config,
      // Handle array fields
      ipList: strategy.config?.ipList?.join(', '),
      retryOnStatusCodes: strategy.config?.retryOnStatusCodes?.join(', '),
      allowedOrigins: strategy.config?.allowedOrigins?.join(', '),
      allowedMethods: strategy.config?.allowedMethods?.join(', '),
      allowedHeaders: strategy.config?.allowedHeaders?.join(', '),
      cacheMethods: strategy.config?.cacheMethods?.join(', '),
      addRequestHeaders: strategy.config?.addRequestHeaders ? JSON.stringify(strategy.config.addRequestHeaders) : '',
      addResponseHeaders: strategy.config?.addResponseHeaders ? JSON.stringify(strategy.config.addResponseHeaders) : '',
    });
    setEditModalVisible(true);
  };

  const getActionMenu = (strategy: StrategyDefinition): MenuProps['items'] => [
    { key: 'edit', icon: <EditOutlined />, label: t('common.edit'), onClick: () => openEditModal(strategy) },
    { type: 'divider' },
    strategy.enabled
      ? { key: 'disable', icon: <StopOutlined />, label: t('common.disable'), onClick: () => handleDisable(strategy) }
      : { key: 'enable', icon: <PlayCircleOutlined />, label: t('common.enable'), onClick: () => handleEnable(strategy) },
    { type: 'divider' },
    { key: 'delete', icon: <DeleteOutlined />, label: t('common.delete'), danger: true, onClick: () => handleDelete(strategy) },
  ];

  const renderConfigFields = (strategyType: string, form: any) => {
    switch (strategyType) {
      case 'RATE_LIMITER':
        return (
          <Row gutter={16}>
            <Col span={12}>
              <Form.Item name="qps" label={t('strategy.config.qps')} initialValue={100}>
                <InputNumber min={1} max={100000} style={{ width: '100%' }} />
              </Form.Item>
            </Col>
            <Col span={12}>
              <Form.Item name="burstCapacity" label={t('strategy.config.burst_capacity')} initialValue={200}>
                <InputNumber min={0} style={{ width: '100%' }} />
              </Form.Item>
            </Col>
            <Col span={12}>
              <Form.Item name="timeUnit" label={t('strategy.config.time_unit')} initialValue="second">
                <Select>
                  <Select.Option value="second">{t('strategy.config.second')}</Select.Option>
                  <Select.Option value="minute">{t('strategy.config.minute')}</Select.Option>
                  <Select.Option value="hour">{t('strategy.config.hour')}</Select.Option>
                </Select>
              </Form.Item>
            </Col>
            <Col span={12}>
              <Form.Item name="keyResolver" label={t('strategy.config.key_resolver')} initialValue="ip">
                <Select>
                  <Select.Option value="ip">{t('strategy.config.key_resolver_ip')}</Select.Option>
                  <Select.Option value="user">{t('strategy.config.key_resolver_user')}</Select.Option>
                  <Select.Option value="header">{t('strategy.config.key_resolver_header')}</Select.Option>
                  <Select.Option value="global">{t('strategy.config.key_resolver_global')}</Select.Option>
                </Select>
              </Form.Item>
            </Col>
          </Row>
        );
      case 'IP_FILTER':
        return (
          <Row gutter={16}>
            <Col span={12}>
              <Form.Item name="mode" label={t('strategy.config.filter_mode')} initialValue="blacklist">
                <Select>
                  <Select.Option value="blacklist">{t('strategy.config.blacklist')}</Select.Option>
                  <Select.Option value="whitelist">{t('strategy.config.whitelist')}</Select.Option>
                </Select>
              </Form.Item>
            </Col>
            <Col span={24}>
              <Form.Item name="ipList" label={t('strategy.config.ip_list')}>
                <Input.TextArea rows={2} placeholder="192.168.1.1, 10.0.0.0/24" />
              </Form.Item>
            </Col>
          </Row>
        );
      case 'TIMEOUT':
        return (
          <Row gutter={16}>
            <Col span={12}>
              <Form.Item name="connectTimeout" label={t('strategy.config.connect_timeout')} initialValue={5000}>
                <InputNumber addonAfter="ms" style={{ width: '100%' }} />
              </Form.Item>
            </Col>
            <Col span={12}>
              <Form.Item name="responseTimeout" label={t('strategy.config.response_timeout')} initialValue={30000}>
                <InputNumber addonAfter="ms" style={{ width: '100%' }} />
              </Form.Item>
            </Col>
          </Row>
        );
      case 'CIRCUIT_BREAKER':
        return (
          <Row gutter={16}>
            <Col span={12}>
              <Form.Item name="failureRateThreshold" label={t('strategy.config.failure_rate')} initialValue={50}>
                <InputNumber min={0} max={100} addonAfter="%" style={{ width: '100%' }} />
              </Form.Item>
            </Col>
            <Col span={12}>
              <Form.Item name="waitDurationInOpenState" label={t('strategy.config.wait_duration')} initialValue={30000}>
                <InputNumber addonAfter="ms" style={{ width: '100%' }} />
              </Form.Item>
            </Col>
            <Col span={12}>
              <Form.Item name="slidingWindowSize" label={t('strategy.config.sliding_window')} initialValue={10}>
                <InputNumber min={1} style={{ width: '100%' }} />
              </Form.Item>
            </Col>
          </Row>
        );
      case 'AUTH':
        return (
          <>
            <Row gutter={16}>
              <Col span={12}>
                <Form.Item name="authType" label={t('strategy.config.auth_type')} initialValue="JWT">
                  <Select>
                    <Select.Option value="JWT"><KeyOutlined /> JWT</Select.Option>
                    <Select.Option value="API_KEY"><ApiOutlined /> API Key</Select.Option>
                    <Select.Option value="OAUTH2"><GlobalOutlined /> OAuth2</Select.Option>
                    <Select.Option value="BASIC"><LockOutlined /> Basic Auth</Select.Option>
                    <Select.Option value="HMAC"><SafetyOutlined /> HMAC Signature</Select.Option>
                  </Select>
                </Form.Item>
              </Col>
            </Row>

            {/* Dynamic fields based on auth type */}
            <Form.Item noStyle shouldUpdate>
              {({ getFieldValue }) => {
                const authType = getFieldValue('authType');

                switch (authType) {
                  case 'JWT':
                    return (
                      <>
                        <Row gutter={16}>
                          <Col span={12}>
                            <Form.Item name="secretKey" label={t('strategy.config.secret_key')}>
                              <Input.Password placeholder="your-secret-key" />
                            </Form.Item>
                          </Col>
                          <Col span={12}>
                            <Form.Item name="jwtAlgorithm" label={t('strategy.config.jwt_algorithm')} initialValue="HS256">
                              <Select>
                                <Select.Option value="HS256">HS256</Select.Option>
                                <Select.Option value="HS512">HS512</Select.Option>
                                <Select.Option value="RS256">RS256</Select.Option>
                              </Select>
                            </Form.Item>
                          </Col>
                        </Row>
                        <Row gutter={16}>
                          <Col span={12}>
                            <Form.Item name="jwtIssuer" label={t('strategy.config.jwt_issuer')}>
                              <Input placeholder="my-app" />
                            </Form.Item>
                          </Col>
                          <Col span={12}>
                            <Form.Item name="jwtAudience" label={t('strategy.config.jwt_audience')}>
                              <Input placeholder="my-api" />
                            </Form.Item>
                          </Col>
                        </Row>
                        <Row gutter={16}>
                          <Col span={12}>
                            <Form.Item name="jwtClockSkewSeconds" label={t('strategy.config.jwt_clock_skew')} initialValue={60}>
                              <InputNumber addonAfter="s" min={0} style={{ width: '100%' }} />
                            </Form.Item>
                          </Col>
                        </Row>
                      </>
                    );

                  case 'API_KEY':
                    return (
                      <>
                        <Row gutter={16}>
                          <Col span={12}>
                            <Form.Item name="apiKey" label={t('strategy.config.api_key')}>
                              <Input.Password placeholder="your-api-key" />
                            </Form.Item>
                          </Col>
                          <Col span={12}>
                            <Form.Item name="apiKeyHeader" label={t('strategy.config.api_key_header')} initialValue="X-API-Key">
                              <Input placeholder="X-API-Key" />
                            </Form.Item>
                          </Col>
                        </Row>
                        <Row gutter={16}>
                          <Col span={12}>
                            <Form.Item name="apiKeyPrefix" label={t('strategy.config.api_key_prefix')}>
                              <Input placeholder="sk_live_" />
                            </Form.Item>
                          </Col>
                          <Col span={12}>
                            <Form.Item name="apiKeyQueryParam" label={t('strategy.config.api_key_query_param')}>
                              <Input placeholder="api_key" />
                            </Form.Item>
                          </Col>
                        </Row>
                      </>
                    );

                  case 'OAUTH2':
                    return (
                      <>
                        <Row gutter={16}>
                          <Col span={12}>
                            <Form.Item name="clientId" label={t('strategy.config.client_id')}>
                              <Input placeholder="client-id" />
                            </Form.Item>
                          </Col>
                          <Col span={12}>
                            <Form.Item name="clientSecret" label={t('strategy.config.client_secret')}>
                              <Input.Password placeholder="client-secret" />
                            </Form.Item>
                          </Col>
                        </Row>
                        <Row gutter={16}>
                          <Col span={24}>
                            <Form.Item name="tokenEndpoint" label={t('strategy.config.token_endpoint')}>
                              <Input placeholder="https://auth.example.com/oauth/introspect" />
                            </Form.Item>
                          </Col>
                        </Row>
                        <Row gutter={16}>
                          <Col span={24}>
                            <Form.Item name="requiredScopes" label={t('strategy.config.required_scopes')}>
                              <Input placeholder="read, write" />
                            </Form.Item>
                          </Col>
                        </Row>
                      </>
                    );

                  case 'BASIC':
                    return (
                      <>
                        <Row gutter={16}>
                          <Col span={12}>
                            <Form.Item name="basicUsername" label={t('strategy.config.basic_username')}>
                              <Input prefix={<UserOutlined />} placeholder="admin" />
                            </Form.Item>
                          </Col>
                          <Col span={12}>
                            <Form.Item name="basicPassword" label={t('strategy.config.basic_password')}>
                              <Input.Password placeholder="password" />
                            </Form.Item>
                          </Col>
                        </Row>
                        <Row gutter={16}>
                          <Col span={12}>
                            <Form.Item name="realm" label={t('strategy.config.realm')}>
                              <Input placeholder="API Gateway" />
                            </Form.Item>
                          </Col>
                          <Col span={12}>
                            <Form.Item name="passwordHashAlgorithm" label={t('strategy.config.password_hash')} initialValue="PLAIN">
                              <Select>
                                <Select.Option value="PLAIN">Plain Text</Select.Option>
                                <Select.Option value="MD5">MD5</Select.Option>
                                <Select.Option value="SHA256">SHA-256</Select.Option>
                                <Select.Option value="BCRYPT">BCrypt</Select.Option>
                              </Select>
                            </Form.Item>
                          </Col>
                        </Row>
                        <Row gutter={16}>
                          <Col span={24}>
                            <Form.Item name="basicUsersJson" label={t('strategy.config.basic_users_json')} extra={t('strategy.config.basic_users_json_help')}>
                              <Input.TextArea rows={3} placeholder='{"user1": "pass1", "user2": "pass2"}' />
                            </Form.Item>
                          </Col>
                        </Row>
                      </>
                    );

                  case 'HMAC':
                    return (
                      <>
                        <Row gutter={16}>
                          <Col span={12}>
                            <Form.Item name="accessKey" label={t('strategy.config.access_key')}>
                              <Input placeholder="AK123456789" />
                            </Form.Item>
                          </Col>
                          <Col span={12}>
                            <Form.Item name="secretKey" label={t('strategy.config.secret_key')}>
                              <Input.Password placeholder="your-secret-key" />
                            </Form.Item>
                          </Col>
                        </Row>
                        <Row gutter={16}>
                          <Col span={12}>
                            <Form.Item name="signatureAlgorithm" label={t('strategy.config.signature_algorithm')} initialValue="HMAC-SHA256">
                              <Select>
                                <Select.Option value="HMAC-SHA256">HMAC-SHA256</Select.Option>
                                <Select.Option value="HMAC-SHA512">HMAC-SHA512</Select.Option>
                                <Select.Option value="HMAC-SHA1">HMAC-SHA1</Select.Option>
                              </Select>
                            </Form.Item>
                          </Col>
                          <Col span={12}>
                            <Form.Item name="clockSkewMinutes" label={t('strategy.config.clock_skew_minutes')} initialValue={5}>
                              <InputNumber addonAfter="min" min={1} style={{ width: '100%' }} />
                            </Form.Item>
                          </Col>
                        </Row>
                        <Row gutter={16}>
                          <Col span={8}>
                            <Form.Item name="requireNonce" label={t('strategy.config.require_nonce')} valuePropName="checked" initialValue={true}>
                              <Switch />
                            </Form.Item>
                          </Col>
                          <Col span={8}>
                            <Form.Item name="validateContentMd5" label={t('strategy.config.validate_content_md5')} valuePropName="checked" initialValue={false}>
                              <Switch />
                            </Form.Item>
                          </Col>
                        </Row>
                        <Row gutter={16}>
                          <Col span={24}>
                            <Form.Item name="accessKeySecretsJson" label={t('strategy.config.access_key_secrets_json')} extra={t('strategy.config.access_key_secrets_json_help')}>
                              <Input.TextArea rows={3} placeholder='{"AK1": "secret1", "AK2": "secret2"}' />
                            </Form.Item>
                          </Col>
                        </Row>
                      </>
                    );

                  default:
                    return null;
                }
              }}
            </Form.Item>
          </>
        );
      case 'RETRY':
        return (
          <Row gutter={16}>
            <Col span={8}>
              <Form.Item name="maxAttempts" label={t('strategy.config.max_attempts')} initialValue={3}>
                <InputNumber min={1} max={10} style={{ width: '100%' }} />
              </Form.Item>
            </Col>
            <Col span={8}>
              <Form.Item name="retryIntervalMs" label={t('strategy.config.retry_interval')} initialValue={1000}>
                <InputNumber addonAfter="ms" style={{ width: '100%' }} />
              </Form.Item>
            </Col>
            <Col span={8}>
              <Form.Item name="retryOnStatusCodes" label={t('strategy.config.retry_status_codes')} initialValue="500, 502, 503, 504">
                <Input placeholder="500,502" />
              </Form.Item>
            </Col>
          </Row>
        );
      case 'CORS':
        return (
          <Row gutter={16}>
            <Col span={12}>
              <Form.Item name="allowedOrigins" label={t('strategy.config.allowed_origins')} initialValue="*">
                <Input placeholder="http://localhost:3000" />
              </Form.Item>
            </Col>
            <Col span={12}>
              <Form.Item name="allowedMethods" label={t('strategy.config.allowed_methods')} initialValue="GET, POST, PUT, DELETE">
                <Input placeholder="GET, POST" />
              </Form.Item>
            </Col>
            <Col span={12}>
              <Form.Item name="allowedHeaders" label={t('strategy.config.allowed_headers')} initialValue="*">
                <Input placeholder="Content-Type" />
              </Form.Item>
            </Col>
            <Col span={6}>
              <Form.Item name="allowCredentials" label={t('strategy.config.allow_credentials')} valuePropName="checked" initialValue={false}>
                <Switch />
              </Form.Item>
            </Col>
            <Col span={6}>
              <Form.Item name="maxAge" label={t('strategy.config.max_age')} initialValue={3600}>
                <InputNumber addonAfter="s" style={{ width: '100%' }} />
              </Form.Item>
            </Col>
          </Row>
        );
      case 'ACCESS_LOG':
        return (
          <Row gutter={16}>
            <Col span={8}>
              <Form.Item name="logLevel" label={t('strategy.config.log_level')} initialValue="NORMAL">
                <Select>
                  <Select.Option value="MINIMAL">MINIMAL</Select.Option>
                  <Select.Option value="NORMAL">NORMAL</Select.Option>
                  <Select.Option value="VERBOSE">VERBOSE</Select.Option>
                </Select>
              </Form.Item>
            </Col>
            <Col span={8}>
              <Form.Item name="samplingRate" label={t('strategy.config.sampling_rate')} initialValue={100}>
                <InputNumber min={1} max={100} addonAfter="%" style={{ width: '100%' }} />
              </Form.Item>
            </Col>
            <Col span={8}>
              <Form.Item name="logRequestHeaders" label={t('strategy.config.log_request_headers')} valuePropName="checked" initialValue={true}>
                <Switch />
              </Form.Item>
            </Col>
            <Col span={8}>
              <Form.Item name="logResponseHeaders" label={t('strategy.config.log_response_headers')} valuePropName="checked" initialValue={true}>
                <Switch />
              </Form.Item>
            </Col>
            <Col span={8}>
              <Form.Item name="logRequestBody" label={t('strategy.config.log_request_body')} valuePropName="checked" initialValue={false}>
                <Switch />
              </Form.Item>
            </Col>
            <Col span={8}>
              <Form.Item name="logResponseBody" label={t('strategy.config.log_response_body')} valuePropName="checked" initialValue={false}>
                <Switch />
              </Form.Item>
            </Col>
          </Row>
        );
      case 'HEADER_OP':
        return (
          <Row gutter={16}>
            <Col span={12}>
              <Form.Item name="enableTraceId" label={t('strategy.config.enable_trace_id')} valuePropName="checked" initialValue={true}>
                <Switch />
              </Form.Item>
            </Col>
            <Col span={12}>
              <Form.Item name="traceIdHeader" label={t('strategy.config.trace_id_header')} initialValue="X-Trace-Id">
                <Input />
              </Form.Item>
            </Col>
            <Col span={12}>
              <Form.Item name="addRequestHeaders" label={t('strategy.config.add_request_headers')}>
                <Input.TextArea rows={2} placeholder='{"X-Custom": "value"}' />
              </Form.Item>
            </Col>
            <Col span={12}>
              <Form.Item name="addResponseHeaders" label={t('strategy.config.add_response_headers')}>
                <Input.TextArea rows={2} placeholder='{"X-Response": "value"}' />
              </Form.Item>
            </Col>
          </Row>
        );
      case 'CACHE':
        return (
          <Row gutter={16}>
            <Col span={8}>
              <Form.Item name="ttlSeconds" label={t('strategy.config.ttl_seconds')} initialValue={60}>
                <InputNumber min={1} style={{ width: '100%' }} />
              </Form.Item>
            </Col>
            <Col span={8}>
              <Form.Item name="maxSize" label={t('strategy.config.max_size')} initialValue={10000}>
                <InputNumber min={1} style={{ width: '100%' }} />
              </Form.Item>
            </Col>
            <Col span={8}>
              <Form.Item name="cacheMethods" label={t('strategy.config.cache_methods')} initialValue="GET, HEAD">
                <Input placeholder="GET, HEAD" />
              </Form.Item>
            </Col>
          </Row>
        );
      case 'SECURITY':
        return (
          <Row gutter={16}>
            <Col span={12}>
              <Form.Item name="mode" label={t('strategy.config.security_mode')} initialValue="BLOCK">
                <Select>
                  <Select.Option value="DETECT">{t('strategy.config.detect_mode')}</Select.Option>
                  <Select.Option value="BLOCK">{t('strategy.config.block_mode')}</Select.Option>
                </Select>
              </Form.Item>
            </Col>
            <Col span={6}>
              <Form.Item name="enableSqlInjectionProtection" label={t('strategy.config.sql_injection')} valuePropName="checked" initialValue={true}>
                <Switch />
              </Form.Item>
            </Col>
            <Col span={6}>
              <Form.Item name="enableXssProtection" label={t('strategy.config.xss_protection')} valuePropName="checked" initialValue={true}>
                <Switch />
              </Form.Item>
            </Col>
            <Col span={6}>
              <Form.Item name="checkParameters" label={t('strategy.config.check_params')} valuePropName="checked" initialValue={true}>
                <Switch />
              </Form.Item>
            </Col>
            <Col span={6}>
              <Form.Item name="checkBody" label={t('strategy.config.check_body')} valuePropName="checked" initialValue={true}>
                <Switch />
              </Form.Item>
            </Col>
          </Row>
        );
      case 'API_VERSION':
        return (
          <Row gutter={16}>
            <Col span={12}>
              <Form.Item name="versionMode" label={t('strategy.config.version_mode')} initialValue="PATH">
                <Select>
                  <Select.Option value="PATH">PATH</Select.Option>
                  <Select.Option value="HEADER">HEADER</Select.Option>
                  <Select.Option value="QUERY">QUERY</Select.Option>
                </Select>
              </Form.Item>
            </Col>
            <Col span={12}>
              <Form.Item name="defaultVersion" label={t('strategy.config.default_version')} initialValue="v1">
                <Input placeholder="v1" />
              </Form.Item>
            </Col>
            <Col span={12}>
              <Form.Item name="versionHeader" label={t('strategy.config.version_header')} initialValue="X-API-Version">
                <Input />
              </Form.Item>
            </Col>
          </Row>
        );
      default:
        return null;
    }
  };

  // Strategy type options for select dropdown
  const strategyTypeOptions = [
    { value: 'RATE_LIMITER', label: t('strategy.type.rate_limiter'), icon: <ThunderboltOutlined /> },
    { value: 'IP_FILTER', label: t('strategy.type.ip_filter'), icon: <SafetyOutlined /> },
    { value: 'TIMEOUT', label: t('strategy.type.timeout'), icon: <ClockCircleOutlined /> },
    { value: 'CIRCUIT_BREAKER', label: t('strategy.type.circuit_breaker'), icon: <StopOutlined /> },
    { value: 'AUTH', label: t('strategy.type.auth'), icon: <KeyOutlined /> },
    { value: 'RETRY', label: t('strategy.type.retry'), icon: <SyncOutlined /> },
    { value: 'CORS', label: t('strategy.type.cors'), icon: <GlobalOutlined /> },
    { value: 'ACCESS_LOG', label: t('strategy.type.access_log'), icon: <FileTextOutlined /> },
    { value: 'HEADER_OP', label: t('strategy.type.header_op'), icon: <SettingOutlined /> },
    { value: 'CACHE', label: t('strategy.type.cache'), icon: <CloudOutlined /> },
    { value: 'SECURITY', label: t('strategy.type.security'), icon: <SecurityScanOutlined /> },
    { value: 'API_VERSION', label: t('strategy.type.api_version'), icon: <NumberOutlined /> },
  ];

  return (
    <div className="strategies-page">
      {/* Stats Bar */}
      <div className="stats-bar">
        <div className="stat-item">
          <div className="stat-value">{totalStrategies}</div>
          <div className="stat-label">{t('strategy.stats_total')}</div>
        </div>
        <Divider type="vertical" className="stat-divider" />
        <div className="stat-item">
          <div className="stat-value text-green-600">{enabledStrategies}</div>
          <div className="stat-label">{t('strategy.stats_enabled')}</div>
        </div>
        <Divider type="vertical" className="stat-divider" />
        <div className="stat-item">
          <div className="stat-value text-blue-600">{globalStrategies}</div>
          <div className="stat-label">{t('strategy.stats_global')}</div>
        </div>
      </div>

      {/* Header */}
      <div className="page-header-modern">
        <div className="page-header-left">
          <Title level={3} className="page-title-main">{t('strategy.title')}</Title>
          <Text type="secondary">{t('strategy.description')}</Text>
        </div>
        <div className="page-header-right">
          <Select value={filterType} onChange={setFilterType} style={{ width: 160 }}>
            <Select.Option value="all">{t('common.all_types')}</Select.Option>
            {strategyTypeOptions.map(opt => (
              <Select.Option key={opt.value} value={opt.value}>{opt.label}</Select.Option>
            ))}
          </Select>
          <Select value={filterScope} onChange={setFilterScope} style={{ width: 140 }}>
            <Select.Option value="all">{t('common.all_scopes')}</Select.Option>
            <Select.Option value="GLOBAL">{t('strategy.scope_global')}</Select.Option>
            <Select.Option value="ROUTE">{t('strategy.scope_route')}</Select.Option>
          </Select>
          <Button type="primary" icon={<PlusOutlined />} onClick={() => setCreateModalVisible(true)} className="create-btn">
            {t('strategy.create')}
          </Button>
        </div>
      </div>

      {/* Strategies Grid */}
      <Spin spinning={loading}>
        {filteredStrategies.length === 0 ? (
          <Card className="empty-card">
            <Empty
              image={<ThunderboltOutlined className="empty-icon" />}
              description={<span className="empty-text">{t('strategy.empty')}</span>}
            >
              <Button type="primary" icon={<PlusOutlined />} onClick={() => setCreateModalVisible(true)}>
                {t('strategy.create_first')}
              </Button>
            </Empty>
          </Card>
        ) : (
          <div className="strategies-grid">
            {filteredStrategies.map((strategy) => (
              <Card key={strategy.strategyId} className={`strategy-card ${!strategy.enabled ? 'strategy-card-disabled' : ''}`} hoverable>
                <div className="strategy-card-header">
                  <div className="strategy-icon" style={{ background: `${getStrategyTypeColor(strategy.strategyType)}20`, color: getStrategyTypeColor(strategy.strategyType) }}>
                    {getStrategyTypeIcon(strategy.strategyType)}
                  </div>
                  <div className="strategy-info">
                    <Text strong className="strategy-name">{strategy.strategyName}</Text>
                    <Tag color={getStrategyTypeColor(strategy.strategyType)} className="strategy-type-tag">
                      {getStrategyTypeLabel(strategy.strategyType)}
                    </Tag>
                  </div>
                  <Dropdown menu={{ items: getActionMenu(strategy) }} trigger={['click']} placement="bottomRight">
                    <Button type="text" icon={<MoreOutlined />} className="action-btn" />
                  </Dropdown>
                </div>

                <div className="strategy-meta">
                  <Badge status={strategy.enabled ? 'success' : 'default'} text={strategy.enabled ? t('common.enabled') : t('common.disabled')} />
                  <Tag icon={strategy.scope === 'GLOBAL' ? <GlobalOutlined /> : <ApiOutlined />} color={strategy.scope === 'GLOBAL' ? 'blue' : 'green'}>
                    {strategy.scope === 'GLOBAL' ? t('strategy.scope_global') : t('strategy.scope_route')}
                  </Tag>
                </div>

                {strategy.scope === 'ROUTE' && strategy.routeId && (
                  <div className="strategy-route">
                    <Text type="secondary">{t('strategy.bound_route')}:</Text>
                    <Text code className="route-id">{strategy.routeId.substring(0, 16)}...</Text>
                  </div>
                )}

                <div className="strategy-config">
                  {Object.entries(strategy.config || {}).slice(0, 3).map(([key, value]) => (
                    <div key={key} className="config-item">
                      <Text type="secondary" className="config-key">{key}:</Text>
                      <Text className="config-value">{String(value).substring(0, 20)}{String(value).length > 20 ? '...' : ''}</Text>
                    </div>
                  ))}
                </div>

                {strategy.description && (
                  <div className="strategy-description">
                    <Text type="secondary" ellipsis>{strategy.description}</Text>
                  </div>
                )}
              </Card>
            ))}
          </div>
        )}
      </Spin>

      {/* Create Modal */}
      <Modal 
        title={
          <div className="modal-header-modern">
            <div className="modal-title-wrapper">
              <div className="modal-icon-wrapper">
                <PlusOutlined />
              </div>
              <div className="modal-title-text">
                <div className="modal-title">{t('strategy.create')}</div>
                <div className="modal-subtitle">{t('strategy.create_description')}</div>
              </div>
            </div>
          </div>
        }
        open={createModalVisible}
        onCancel={() => { setCreateModalVisible(false); createForm.resetFields(); }}
        footer={null}
        width={720}
        className="strategy-modal strategy-create-modal"
      >
        <Form form={createForm} layout="vertical" onFinish={handleCreate}>
          {/* Basic Info Section */}
          <div className="form-section-modern">
            <div className="section-header">
              <ThunderboltOutlined className="section-icon" />
              <span className="section-title">{t('strategy.basic_info')}</span>
            </div>
            <div className="section-content">
              <Row gutter={16}>
                <Col span={12}>
                  <Form.Item 
                    name="strategyName" 
                    label={t('strategy.name')} 
                    rules={[{ required: true }]}
                    extra={t('strategy.name_helper')}
                  >
                    <Input placeholder={t('strategy.name_placeholder')} size="large" />
                  </Form.Item>
                </Col>
                <Col span={12}>
                  <Form.Item 
                    name="strategyType" 
                    label={t('strategy.type')} 
                    rules={[{ required: true }]}
                    extra={t('strategy.type_helper')}
                  >
                    <Select placeholder={t('strategy.type_placeholder')} size="large">
                      {strategyTypeOptions.map(opt => (
                        <Select.Option key={opt.value} value={opt.value}>
                          <Space>{opt.icon}{opt.label}</Space>
                        </Select.Option>
                      ))}
                    </Select>
                  </Form.Item>
                </Col>
              </Row>
              <Row gutter={16}>
                <Col span={12}>
                  <Form.Item 
                    name="scope" 
                    label={t('strategy.scope')} 
                    initialValue="GLOBAL"
                    extra={t('strategy.scope_helper')}
                  >
                    <Radio.Group size="large">
                      <Radio.Button value="GLOBAL"><GlobalOutlined /> {t('strategy.scope_global')}</Radio.Button>
                      <Radio.Button value="ROUTE"><ApiOutlined /> {t('strategy.scope_route')}</Radio.Button>
                    </Radio.Group>
                  </Form.Item>
                </Col>
                <Col span={12}>
                  <Form.Item noStyle shouldUpdate>
                    {({ getFieldValue }) => {
                      const scope = getFieldValue('scope');
                      if (scope === 'ROUTE') {
                        return (
                          <Form.Item 
                            name="routeId" 
                            label={t('strategy.route_id')} 
                            rules={[{ required: true }]}
                            extra={t('strategy.route_id_helper')}
                          >
                            <Input placeholder={t('strategy.route_id_placeholder')} size="large" />
                          </Form.Item>
                        );
                      }
                      return null;
                    }}
                  </Form.Item>
                </Col>
              </Row>
            </div>
          </div>

          {/* Configuration Section */}
          <div className="form-section-modern">
            <div className="section-header">
              <SettingOutlined className="section-icon" />
              <span className="section-title">{t('strategy.config')}</span>
              <span className="section-subtitle">{t('strategy.config_helper')}</span>
            </div>
            <div className="section-content">
              <Form.Item noStyle shouldUpdate>
                {({ getFieldValue }) => renderConfigFields(getFieldValue('strategyType'), createForm)}
              </Form.Item>
            </div>
          </div>

          {/* Advanced Settings Section */}
          <div className="form-section-modern">
            <div className="section-header">
              <SettingOutlined className="section-icon" />
              <span className="section-title">{t('strategy.advanced_settings')}</span>
            </div>
            <div className="section-content">
              <Row gutter={16}>
                <Col span={8}>
                  <Form.Item 
                    name="priority" 
                    label={t('strategy.priority')} 
                    initialValue={100}
                    extra={t('strategy.priority_helper')}
                  >
                    <InputNumber min={1} max={1000} style={{ width: '100%' }} size="large" />
                  </Form.Item>
                </Col>
                <Col span={8}>
                  <Form.Item 
                    name="enabled" 
                    label={t('strategy.enabled')} 
                    valuePropName="checked" 
                    initialValue={true}
                  >
                    <Switch checkedChildren={t('common.enabled')} unCheckedChildren={t('common.disabled')} />
                  </Form.Item>
                </Col>
              </Row>
              <Form.Item 
                name="description" 
                label={t('strategy.description_label')}
                extra={t('strategy.description_helper')}
              >
                <Input.TextArea rows={2} size="large" showCount maxLength={500} />
              </Form.Item>
            </div>
          </div>

          {/* Modal Footer */}
          <div className="modal-footer-modern">
            <Button onClick={() => setCreateModalVisible(false)} size="large">{t('common.cancel')}</Button>
            <Button type="primary" htmlType="submit" size="large" icon={<PlusOutlined />}>{t('common.create')}</Button>
          </div>
        </Form>
      </Modal>

      {/* Edit Modal */}
      <Modal 
        title={
          <div className="modal-header-modern">
            <div className="modal-title-wrapper">
              <div className="modal-icon-wrapper edit">
                <EditOutlined />
              </div>
              <div className="modal-title-text">
                <div className="modal-title">{t('strategy.edit')}</div>
                <div className="modal-subtitle">{t('strategy.edit_description')}</div>
              </div>
            </div>
          </div>
        }
        open={editModalVisible}
        onCancel={() => { setEditModalVisible(false); editForm.resetFields(); setSelectedStrategy(null); }}
        footer={null}
        width={720}
        className="strategy-modal strategy-edit-modal"
      >
        <Form form={editForm} layout="vertical" onFinish={handleUpdate}>
          {/* Basic Info Section */}
          <div className="form-section-modern">
            <div className="section-header">
              <ThunderboltOutlined className="section-icon" />
              <span className="section-title">{t('strategy.basic_info')}</span>
            </div>
            <div className="section-content">
              <Row gutter={16}>
                <Col span={12}>
                  <Form.Item 
                    name="strategyName" 
                    label={t('strategy.name')} 
                    rules={[{ required: true }]}
                    extra={t('strategy.name_helper')}
                  >
                    <Input size="large" />
                  </Form.Item>
                </Col>
                <Col span={12}>
                  <Form.Item 
                    name="scope" 
                    label={t('strategy.scope')}
                    extra={t('strategy.scope_helper')}
                  >
                    <Radio.Group size="large">
                      <Radio.Button value="GLOBAL"><GlobalOutlined /> {t('strategy.scope_global')}</Radio.Button>
                      <Radio.Button value="ROUTE"><ApiOutlined /> {t('strategy.scope_route')}</Radio.Button>
                    </Radio.Group>
                  </Form.Item>
                </Col>
              </Row>
              <Form.Item noStyle shouldUpdate>
                {({ getFieldValue }) => {
                  const scope = getFieldValue('scope');
                  if (scope === 'ROUTE') {
                    return (
                      <Form.Item 
                        name="routeId" 
                        label={t('strategy.route_id')} 
                        rules={[{ required: true }]}
                        extra={t('strategy.route_id_helper')}
                      >
                        <Input size="large" />
                      </Form.Item>
                    );
                  }
                  return null;
                }}
              </Form.Item>
            </div>
          </div>

          {/* Configuration Section */}
          <div className="form-section-modern">
            <div className="section-header">
              <SettingOutlined className="section-icon" />
              <span className="section-title">{t('strategy.config')}</span>
              <span className="section-subtitle">{t('strategy.config_helper')}</span>
            </div>
            <div className="section-content">
              {selectedStrategy && renderConfigFields(selectedStrategy.strategyType, editForm)}
            </div>
          </div>

          {/* Advanced Settings Section */}
          <div className="form-section-modern">
            <div className="section-header">
              <SettingOutlined className="section-icon" />
              <span className="section-title">{t('strategy.advanced_settings')}</span>
            </div>
            <div className="section-content">
              <Row gutter={16}>
                <Col span={8}>
                  <Form.Item 
                    name="priority" 
                    label={t('strategy.priority')}
                    extra={t('strategy.priority_helper')}
                  >
                    <InputNumber min={1} max={1000} style={{ width: '100%' }} size="large" />
                  </Form.Item>
                </Col>
                <Col span={8}>
                  <Form.Item 
                    name="enabled" 
                    label={t('strategy.enabled')} 
                    valuePropName="checked"
                  >
                    <Switch checkedChildren={t('common.enabled')} unCheckedChildren={t('common.disabled')} />
                  </Form.Item>
                </Col>
              </Row>
              <Form.Item 
                name="description" 
                label={t('strategy.description_label')}
                extra={t('strategy.description_helper')}
              >
                <Input.TextArea rows={2} size="large" showCount maxLength={500} />
              </Form.Item>
            </div>
          </div>

          {/* Modal Footer */}
          <div className="modal-footer-modern">
            <Button onClick={() => setEditModalVisible(false)} size="large">{t('common.cancel')}</Button>
            <Button type="primary" htmlType="submit" size="large" icon={<EditOutlined />}>{t('common.update')}</Button>
          </div>
        </Form>
      </Modal>
    </div>
  );
};

export default StrategiesPage;