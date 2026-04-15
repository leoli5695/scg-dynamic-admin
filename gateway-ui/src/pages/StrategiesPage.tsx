import { useState, useEffect, useCallback, memo, useMemo } from 'react';
import {
  Card, Button, Space, Modal, message, Spin, Tag, Form, Input, Select,
  Empty, Dropdown, Tooltip, Badge, Divider, Typography, Switch, Radio, InputNumber, Row, Col, Collapse, Popconfirm
} from 'antd';
import {
  PlusOutlined, DeleteOutlined, EditOutlined, MoreOutlined,
  ThunderboltOutlined, StopOutlined, PlayCircleOutlined, GlobalOutlined,
  ApiOutlined, SafetyOutlined, ClockCircleOutlined, KeyOutlined,
  SyncOutlined, FileTextOutlined, SettingOutlined,
  CloudOutlined, SecurityScanOutlined, NumberOutlined, LockOutlined, UserOutlined,
  CopyOutlined
} from '@ant-design/icons';
import type { MenuProps } from 'antd';
import api from '../utils/api';
import { useTranslation } from 'react-i18next';
import { useStrategyTypes, StrategyType } from '../hooks/useStrategyTypes';
import DynamicConfigFields, { AuthSubSchemaFields } from '../components/DynamicConfigFields';
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

// Separate component for strategy action dropdown to prevent re-renders
interface StrategyActionDropdownProps {
  strategy: StrategyDefinition;
  onEdit: (strategy: StrategyDefinition) => void;
  onToggle: (strategy: StrategyDefinition) => void;
  onDelete: (strategy: StrategyDefinition) => void;
}

const StrategyActionDropdown = memo(({ strategy, onEdit, onToggle, onDelete }: StrategyActionDropdownProps) => {
  const { t } = useTranslation();

  const handleEdit = useCallback(() => onEdit(strategy), [onEdit, strategy]);
  const handleToggle = useCallback(() => onToggle(strategy), [onToggle, strategy]);
  const handleDelete = useCallback(() => onDelete(strategy), [onDelete, strategy]);

  return (
    <Space>
      <Tooltip title={t('common.edit')}>
        <Button type="text" size="small" icon={<EditOutlined />} className="action-btn" onClick={handleEdit} />
      </Tooltip>
      <Tooltip title={strategy.enabled ? t('common.disable') : t('common.enable')}>
        <Button type="text" size="small" icon={strategy.enabled ? <StopOutlined /> : <PlayCircleOutlined />} className="action-btn" onClick={handleToggle} />
      </Tooltip>
      <Popconfirm
        title={`${t('common.delete')}: ${strategy.strategyName}`}
        onConfirm={handleDelete}
        okText={t('common.confirm')}
        cancelText={t('common.cancel')}
      >
        <Tooltip title={t('common.delete')}>
          <Button type="text" size="small" danger icon={<DeleteOutlined />} className="action-btn" />
        </Tooltip>
      </Popconfirm>
    </Space>
  );
});

interface StrategiesPageProps {
  instanceId?: string;
}

const StrategiesPage: React.FC<StrategiesPageProps> = ({ instanceId }) => {
  const [strategies, setStrategies] = useState<StrategyDefinition[]>([]);
  const [routes, setRoutes] = useState<any[]>([]);
  const [loading, setLoading] = useState(false);
  const [createModalVisible, setCreateModalVisible] = useState(false);
  const [editModalVisible, setEditModalVisible] = useState(false);
  const [selectedStrategy, setSelectedStrategy] = useState<StrategyDefinition | null>(null);
  const [filterType, setFilterType] = useState<string>('all');
  const [filterScope, setFilterScope] = useState<string>('all');
  const [statusFilter, setStatusFilter] = useState<string>('all');
  const [createForm] = Form.useForm();
  const [editForm] = Form.useForm();
  const { t } = useTranslation();

  const loadRoutes = useCallback(async () => {
    try {
      const params = instanceId ? { instanceId } : {};
      const response = await api.get('/api/routes', { params });
      if (response.data.code === 200) {
        setRoutes(response.data.data || []);
      }
    } catch (error: any) {
      console.error('Load routes error:', error);
    }
  }, [instanceId]);

  const loadStrategies = useCallback(async () => {
    try {
      setLoading(true);
      const params = instanceId ? { instanceId } : {};
      const response = await api.get('/api/strategies', { params });
      if (response.data.code === 200) {
        setStrategies(response.data.data || []);
      }
    } catch (error: any) {
      message.error(t('message.load_strategies_failed', { error: error.message }));
    } finally {
      setLoading(false);
    }
  }, [t, instanceId]);

  useEffect(() => {
    loadStrategies();
    loadRoutes();
  }, [loadStrategies, loadRoutes]);

  // Memoize filtered strategies to avoid recalculation on every render
  const filteredStrategies = useMemo(() => strategies.filter(s => {
    const matchesType = filterType === 'all' || s.strategyType === filterType;
    const matchesScope = filterScope === 'all' || s.scope === filterScope;
    const matchesStatus = statusFilter === 'all' ||
      (statusFilter === 'enabled' && s.enabled) ||
      (statusFilter === 'disabled' && !s.enabled);
    return matchesType && matchesScope && matchesStatus;
  }), [strategies, filterType, filterScope, statusFilter]);

  // Memoize strategy statistics
  const strategyStats = useMemo(() => ({
    total: strategies.length,
    enabled: strategies.filter(s => s.enabled).length,
    global: strategies.filter(s => s.scope === 'GLOBAL').length,
  }), [strategies]);

  const totalStrategies = strategyStats.total;
  const enabledStrategies = strategyStats.enabled;
  const globalStrategies = strategyStats.global;

  // Use dynamic strategy types from API
  const { strategyTypes, loading: typesLoading, getStrategyType } = useStrategyTypes();

  const getStrategyTypeLabel = (type: string) => {
    const strategyType = getStrategyType(type);
    if (strategyType) {
      // Use Chinese name for Chinese locale, English name for English locale
      const locale = localStorage.getItem('i18nextLng') || 'zh';
      return locale === 'en' ? strategyType.typeNameEn : strategyType.typeName;
    }
    return type;
  };

  const getStrategyTypeIcon = (type: string) => {
    const strategyType = getStrategyType(type);
    if (strategyType?.icon) {
      // Map icon names to actual icon components
      const iconMap: Record<string, React.ReactNode> = {
        'ThunderboltOutlined': <ThunderboltOutlined />,
        'SafetyOutlined': <SafetyOutlined />,
        'ClockCircleOutlined': <ClockCircleOutlined />,
        'StopOutlined': <StopOutlined />,
        'KeyOutlined': <KeyOutlined />,
        'SyncOutlined': <SyncOutlined />,
        'GlobalOutlined': <GlobalOutlined />,
        'FileTextOutlined': <FileTextOutlined />,
        'SettingOutlined': <SettingOutlined />,
        'CloudOutlined': <CloudOutlined />,
        'SecurityScanOutlined': <SecurityScanOutlined />,
        'NumberOutlined': <NumberOutlined />,
        'PlayCircleOutlined': <PlayCircleOutlined />,
      };
      return iconMap[strategyType.icon] || <ThunderboltOutlined />;
    }
    return <ThunderboltOutlined />;
  };

  const getStrategyTypeColor = (type: string) => {
    const strategyType = getStrategyType(type);
    if (strategyType?.category) {
      // Map categories to semantic colors
      const categoryColors: Record<string, string> = {
        'traffic': 'rate-limiter',
        'resilience': 'timeout-retry',
        'security': 'enabled-global',
        'transform': 'enabled-global',
        'observability': 'enabled-global',
        'misc': 'enabled-global',
      };
      return categoryColors[strategyType.category] || 'rate-limiter';
    }
    return 'rate-limiter';
  };

  const getStrategyTypeColorValue = (type: string) => {
    const strategyType = getStrategyType(type);
    return strategyType?.color || '#3b82f6';
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
          // JWT fields
          secretKey: values.secretKey,
          jwtAlgorithm: values.jwtAlgorithm,
          jwtIssuer: values.jwtIssuer,
          jwtAudience: values.jwtAudience,
          jwtClockSkewSeconds: values.jwtClockSkewSeconds ? parseInt(values.jwtClockSkewSeconds) : undefined,
          // API_KEY fields
          apiKey: values.apiKey,
          apiKeyHeader: values.apiKeyHeader,
          apiKeyPrefix: values.apiKeyPrefix,
          apiKeyQueryParam: values.apiKeyQueryParam,
          // OAUTH2 fields
          clientId: values.clientId,
          clientSecret: values.clientSecret,
          tokenEndpoint: values.tokenEndpoint,
          requiredScopes: values.requiredScopes,
          // BASIC fields
          basicUsername: values.basicUsername,
          basicPassword: values.basicPassword,
          realm: values.realm,
          passwordHashAlgorithm: values.passwordHashAlgorithm,
          basicUsersJson: values.basicUsersJson,
          // HMAC fields
          accessKey: values.accessKey,
          signatureAlgorithm: values.signatureAlgorithm,
          clockSkewMinutes: values.clockSkewMinutes ? parseInt(values.clockSkewMinutes) : undefined,
          requireNonce: values.requireNonce,
          validateContentMd5: values.validateContentMd5,
          accessKeySecretsJson: values.accessKeySecretsJson,
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

      const params = instanceId ? { instanceId } : {};
      const response = await api.post('/api/strategies', strategy, { params });
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

  const handleDelete = useCallback((strategy: StrategyDefinition) => {
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
  }, [t, loadStrategies]);

  const handleEnable = useCallback(async (strategy: StrategyDefinition) => {
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
  }, [t, loadStrategies]);

  const handleDisable = useCallback(async (strategy: StrategyDefinition) => {
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
  }, [t, loadStrategies]);

  // Combined toggle handler
  const handleToggle = useCallback((strategy: StrategyDefinition) => {
    if (strategy.enabled) {
      handleDisable(strategy);
    } else {
      handleEnable(strategy);
    }
  }, [handleEnable, handleDisable]);

  const openEditModal = useCallback((strategy: StrategyDefinition) => {
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
  }, [editForm]);

  // State for strategy type selection modal
  const [typeSelectModalVisible, setTypeSelectModalVisible] = useState(false);
  const [selectedStrategyType, setSelectedStrategyType] = useState<StrategyType | null>(null);

  // Render config fields dynamically based on schema
  const renderConfigFields = (strategyType: string, form: any) => {
    const strategyTypeData = getStrategyType(strategyType);
    const schema = strategyTypeData?.configSchema;

    if (!schema) {
      // Fallback to empty for unknown types
      return null;
    }

    // Special handling for AUTH with subSchemas
    if (strategyType === 'AUTH' && schema.subSchemas) {
      return (
        <>
          <Row gutter={16}>
            <Col span={12}>
              <Form.Item name="authType" label={t('strategy.config.auth_type')} initialValue="JWT">
                <Select onChange={() => form.resetFields(['secretKey', 'jwtAlgorithm', 'jwtIssuer', 'jwtAudience', 'jwtClockSkewSeconds', 'apiKey', 'apiKeyHeader', 'apiKeyPrefix', 'apiKeyQueryParam', 'clientId', 'clientSecret', 'tokenEndpoint', 'requiredScopes', 'basicUsername', 'basicPassword', 'realm', 'passwordHashAlgorithm', 'basicUsersJson', 'accessKey', 'signatureAlgorithm', 'clockSkewMinutes', 'requireNonce', 'validateContentMd5', 'accessKeySecretsJson'])}>
                  <Select.Option value="JWT"><KeyOutlined /> JWT</Select.Option>
                  <Select.Option value="API_KEY"><ApiOutlined /> API Key</Select.Option>
                  <Select.Option value="OAUTH2"><GlobalOutlined /> OAuth2</Select.Option>
                  <Select.Option value="BASIC"><LockOutlined /> Basic</Select.Option>
                  <Select.Option value="HMAC"><SafetyOutlined /> HMAC</Select.Option>
                </Select>
              </Form.Item>
            </Col>
          </Row>
          <Form.Item noStyle shouldUpdate>
            {({ getFieldValue }) => {
              const authType = getFieldValue('authType') || 'JWT';
              const subSchema = schema.subSchemas?.[authType];
              if (!subSchema) return null;
              return <AuthSubSchemaFields schema={{ ...subSchema }} form={form} t={t} />;
            }}
          </Form.Item>
        </>
      );
    }

    // Default dynamic rendering for other types
    return <DynamicConfigFields schema={schema} form={form} t={t} />;
  };

  // Handle strategy type selection
  const handleSelectStrategyType = (type: StrategyType) => {
    setSelectedStrategyType(type);
    setTypeSelectModalVisible(false);
    setCreateModalVisible(true);
    createForm.resetFields();
    createForm.setFieldsValue({ strategyType: type.typeCode });
  };

  // Handle back to type selection
  const handleBackToTypeSelect = () => {
    setCreateModalVisible(false);
    setSelectedStrategyType(null);
    setTypeSelectModalVisible(true);
  };

  // Group strategy types by category (exclude ACCESS_LOG - now in instance Access Log config)
  const strategyTypesByCategory = useMemo(() => {
    const grouped: Record<string, StrategyType[]> = {};
    strategyTypes
      .filter(st => st.typeCode !== 'ACCESS_LOG')  // ACCESS_LOG moved to instance-level config
      .forEach(st => {
        if (!grouped[st.category]) {
          grouped[st.category] = [];
        }
        grouped[st.category].push(st);
      });
    return grouped;
  }, [strategyTypes]);

  // Category labels
  const categoryLabels: Record<string, string> = {
    'traffic': '流量控制',
    'security': '安全防护',
    'resilience': '弹性容错',
    'transform': '数据转换',
    'observability': '可观测性',
    'misc': '其他',
  };

  // Strategy type options from dynamic API data
  const strategyTypeOptions = useMemo(() => {
    return strategyTypes.map(st => ({
      value: st.typeCode,
      label: st.typeName,
      icon: getStrategyTypeIcon(st.typeCode),
    }));
  }, [strategyTypes]);

  return (
    <div className="strategies-page">
      {/* Stats Bar */}
      <div className="stats-bar">
        <div
          className={`stat-item ${statusFilter === 'all' ? 'stat-item-active' : ''}`}
          onClick={() => setStatusFilter('all')}
          style={{ cursor: 'pointer' }}
        >
          <div className="stat-value">{totalStrategies}</div>
          <div className="stat-label">{t('strategy.stats_total')}</div>
        </div>
        <Divider type="vertical" className="stat-divider" />
        <div
          className={`stat-item ${statusFilter === 'enabled' ? 'stat-item-active' : ''}`}
          onClick={() => setStatusFilter('enabled')}
          style={{ cursor: 'pointer' }}
        >
          <div className="stat-value text-green-600">{enabledStrategies}</div>
          <div className="stat-label">{t('strategy.stats_enabled')}</div>
        </div>
        <Divider type="vertical" className="stat-divider" />
        <div
          className={`stat-item ${statusFilter === 'disabled' ? 'stat-item-active' : ''}`}
          onClick={() => setStatusFilter('disabled')}
          style={{ cursor: 'pointer' }}
        >
          <div className="stat-value text-red-600">{totalStrategies - enabledStrategies}</div>
          <div className="stat-label">{t('strategy.stats_disabled')}</div>
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
          <Button type="primary" icon={<PlusOutlined />} onClick={() => setTypeSelectModalVisible(true)} className="create-btn">
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
              <Button type="primary" icon={<PlusOutlined />} onClick={() => setTypeSelectModalVisible(true)}>
                {t('strategy.create_first')}
              </Button>
            </Empty>
          </Card>
        ) : (
          <div className="strategies-grid">
            {filteredStrategies.map((strategy) => (
              <Card key={strategy.strategyId} className={`strategy-card ${!strategy.enabled ? 'strategy-card-disabled' : ''}`} hoverable>
                <div className="strategy-card-header">
                  <div className="strategy-icon" style={{ background: `${getStrategyTypeColorValue(strategy.strategyType)}20`, color: getStrategyTypeColorValue(strategy.strategyType) }}>
                    {getStrategyTypeIcon(strategy.strategyType)}
                  </div>
                  <div className="strategy-info">
                    <Text strong className="strategy-name">{strategy.strategyName}</Text>
                    <div className="strategy-id-row">
                      <Tag className={`strategy-type-tag ${getStrategyTypeColor(strategy.strategyType)}`}>
                        {getStrategyTypeLabel(strategy.strategyType)}
                      </Tag>
                      <Tooltip title={t('common.copy_id')}>
                        <Button
                          type="text"
                          size="small"
                          icon={<CopyOutlined />}
                          className="copy-btn"
                          onClick={(e) => {
                            e.stopPropagation();
                            navigator.clipboard.writeText(strategy.strategyId);
                            message.success(t('message.copy_success'));
                          }}
                        />
                      </Tooltip>
                    </div>
                  </div>
                  <StrategyActionDropdown
                    strategy={strategy}
                    onEdit={openEditModal}
                    onToggle={handleToggle}
                    onDelete={handleDelete}
                  />
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
                  {Object.entries(strategy.config || {}).slice(0, 2).map(([key, value]) => {
                    const displayValue = typeof value === 'object' ? JSON.stringify(value) : String(value);
                    return (
                      <div key={key} className="config-item">
                        <Text type="secondary" className="config-key">{key}:</Text>
                        <Text className="config-value" title={displayValue}>{displayValue.substring(0, 25)}{displayValue.length > 25 ? '...' : ''}</Text>
                      </div>
                    );
                  })}
                  {Object.keys(strategy.config || {}).length > 2 && (
                    <Text type="secondary" style={{ fontSize: '11px', marginTop: '2px' }}>
                      +{Object.keys(strategy.config || {}).length - 2} more
                    </Text>
                  )}
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

      {/* Strategy Type Selection Modal */}
      <Modal
        title={
          <div className="modal-header-modern">
            <div className="modal-title-wrapper">
              <div className="modal-icon-wrapper">
                <PlusOutlined />
              </div>
              <div className="modal-title-text">
                <div className="modal-title">{t('strategy.select_type')}</div>
                <div className="modal-subtitle">{t('strategy.select_type_description')}</div>
              </div>
            </div>
          </div>
        }
        open={typeSelectModalVisible}
        onCancel={() => setTypeSelectModalVisible(false)}
        footer={null}
        width={800}
        className="strategy-type-select-modal"
      >
        <Spin spinning={typesLoading}>
          {Object.entries(strategyTypesByCategory).map(([category, types]) => (
            <div key={category} style={{ marginBottom: 24 }}>
              <Divider style={{ margin: '0 0 16px 0' }}>
                <Tag color="blue">{categoryLabels[category] || category}</Tag>
              </Divider>
              <Row gutter={[16, 16]}>
                {types.map((type) => (
                  <Col span={6} key={type.typeCode}>
                    <Card
                      hoverable
                      className="strategy-type-card"
                      onClick={() => handleSelectStrategyType(type)}
                      style={{
                        textAlign: 'center',
                        borderRadius: 12,
                        border: '1px solid #f0f0f0',
                        transition: 'all 0.3s',
                      }}
                      styles={{ body: { padding: '20px 12px' } }}
                    >
                      <div
                        style={{
                          width: 48,
                          height: 48,
                          borderRadius: '50%',
                          background: `${type.color}20`,
                          display: 'flex',
                          alignItems: 'center',
                          justifyContent: 'center',
                          margin: '0 auto 12px',
                          fontSize: 24,
                          color: type.color,
                        }}
                      >
                        {getStrategyTypeIcon(type.typeCode)}
                      </div>
                      <Text strong style={{ display: 'block', marginBottom: 4 }}>
                        {type.typeName}
                      </Text>
                      <Text type="secondary" style={{ fontSize: 12 }}>
                        {type.typeNameEn}
                      </Text>
                      {type.description && (
                        <Text type="secondary" style={{ fontSize: 11, display: 'block', marginTop: 8 }}>
                          {type.description.length > 30 ? `${type.description.substring(0, 30)}...` : type.description}
                        </Text>
                      )}
                    </Card>
                  </Col>
                ))}
              </Row>
            </div>
          ))}
        </Spin>
      </Modal>

      {/* Create Modal */}
      <Modal
        title={
          <div className="modal-header-modern">
            <div className="modal-title-wrapper">
              <Button
                type="text"
                icon={<EditOutlined />}
                onClick={handleBackToTypeSelect}
                style={{ marginRight: 8 }}
              >
                {t('common.back')}
              </Button>
              <div className="modal-icon-wrapper">
                <PlusOutlined />
              </div>
              <div className="modal-title-text">
                <div className="modal-title">
                  {selectedStrategyType ? `${t('strategy.create')} - ${selectedStrategyType.typeName}` : t('strategy.create')}
                </div>
                <div className="modal-subtitle">{selectedStrategyType?.description || t('strategy.create_description')}</div>
              </div>
            </div>
          </div>
        }
        open={createModalVisible}
        onCancel={() => { setCreateModalVisible(false); createForm.resetFields(); setSelectedStrategyType(null); }}
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
                  <Form.Item name="strategyType" label={t('strategy.type')} hidden>
                    <Input />
                  </Form.Item>
                  <div style={{ padding: '8px 0' }}>
                    <Text type="secondary">{t('strategy.type')}: </Text>
                    <Tag color={selectedStrategyType?.color} style={{ fontSize: 14, padding: '4px 12px' }}>
                      {selectedStrategyType?.typeName}
                    </Tag>
                  </div>
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
                            <Select placeholder={t('strategy.route_id_placeholder')} size="large" showSearch optionFilterProp="children">
                              {routes.map(route => (
                                <Select.Option key={route.id} value={route.id}>
                                  {route.routeName || route.id}
                                </Select.Option>
                              ))}
                            </Select>
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
                <Input.TextArea rows={2} showCount maxLength={500} />
              </Form.Item>
            </div>
          </div>

          {/* Modal Footer */}
          <div className="modal-footer-modern">
            <Button onClick={() => { setCreateModalVisible(false); createForm.resetFields(); setSelectedStrategyType(null); }} size="large">{t('common.cancel')}</Button>
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
                        <Select size="large" showSearch optionFilterProp="children">
                          {routes.map(route => (
                            <Select.Option key={route.id} value={route.id}>
                              {route.routeName || route.id}
                            </Select.Option>
                          ))}
                        </Select>
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
                <Input.TextArea rows={2} showCount maxLength={500} />
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