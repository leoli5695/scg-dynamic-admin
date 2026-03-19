import { useState, useEffect } from 'react';
import {
  Card, Button, Space, Modal, message, Spin, Tag, Drawer, Form, Input, Select,
  Empty, Dropdown, Tooltip, Badge, Divider, Typography, Switch, Radio
} from 'antd';
import {
  PlusOutlined, DeleteOutlined, EditOutlined, MoreOutlined,
  ThunderboltOutlined, StopOutlined, PlayCircleOutlined, GlobalOutlined,
  ApiOutlined, SafetyOutlined, ClockCircleOutlined, KeyOutlined
} from '@ant-design/icons';
import type { MenuProps } from 'antd';
import api from '../utils/api';
import { useTranslation } from 'react-i18next';
import './StrategiesPage.premium.css';

const { Text, Title } = Typography;

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
  const [createDrawerVisible, setCreateDrawerVisible] = useState(false);
  const [editDrawerVisible, setEditDrawerVisible] = useState(false);
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
    };
    return colors[type] || '#1890ff';
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
        config: values.config || {},
        description: values.description,
      };

      // Map form values to config based on strategy type
      switch (values.strategyType) {
        case 'RATE_LIMITER':
          strategy.config = {
            qps: parseInt(values.qps) || 100,
            burstCapacity: parseInt(values.burstCapacity) || 200,
            timeUnit: values.timeUnit || 'second',
            keyResolver: values.keyResolver || 'ip',
          };
          break;
        case 'IP_FILTER':
          strategy.config = {
            mode: values.mode || 'blacklist',
            ipList: values.ipList ? values.ipList.split(',').map((ip: string) => ip.trim()) : [],
          };
          break;
        case 'TIMEOUT':
          strategy.config = {
            connectTimeout: parseInt(values.connectTimeout) || 5000,
            responseTimeout: parseInt(values.responseTimeout) || 30000,
          };
          break;
        case 'CIRCUIT_BREAKER':
          strategy.config = {
            failureRateThreshold: parseFloat(values.failureRateThreshold) || 50,
            slowCallDurationThreshold: parseInt(values.slowCallDurationThreshold) || 60000,
            waitDurationInOpenState: parseInt(values.waitDurationInOpenState) || 30000,
            slidingWindowSize: parseInt(values.slidingWindowSize) || 10,
            minimumNumberOfCalls: parseInt(values.minimumNumberOfCalls) || 5,
          };
          break;
        case 'AUTH':
          strategy.config = {
            authType: values.authType || 'JWT',
            secretKey: values.secretKey,
            apiKey: values.apiKey,
            clientId: values.clientId,
            clientSecret: values.clientSecret,
          };
          break;
      }

      const response = await api.post('/api/strategies', strategy);
      if (response.data.code === 200) {
        message.success(t('message.create_success'));
        createForm.resetFields();
        setCreateDrawerVisible(false);
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
        config: {},
      };

      // Map form values to config based on strategy type
      switch (selectedStrategy.strategyType) {
        case 'RATE_LIMITER':
          strategy.config = {
            qps: parseInt(values.qps) || 100,
            burstCapacity: parseInt(values.burstCapacity) || 200,
            timeUnit: values.timeUnit || 'second',
            keyResolver: values.keyResolver || 'ip',
          };
          break;
        case 'IP_FILTER':
          strategy.config = {
            mode: values.mode || 'blacklist',
            ipList: values.ipList ? values.ipList.split(',').map((ip: string) => ip.trim()) : [],
          };
          break;
        case 'TIMEOUT':
          strategy.config = {
            connectTimeout: parseInt(values.connectTimeout) || 5000,
            responseTimeout: parseInt(values.responseTimeout) || 30000,
          };
          break;
        case 'CIRCUIT_BREAKER':
          strategy.config = {
            failureRateThreshold: parseFloat(values.failureRateThreshold) || 50,
            slowCallDurationThreshold: parseInt(values.slowCallDurationThreshold) || 60000,
            waitDurationInOpenState: parseInt(values.waitDurationInOpenState) || 30000,
            slidingWindowSize: parseInt(values.slidingWindowSize) || 10,
            minimumNumberOfCalls: parseInt(values.minimumNumberOfCalls) || 5,
          };
          break;
        case 'AUTH':
          strategy.config = {
            authType: values.authType || 'JWT',
            secretKey: values.secretKey,
            apiKey: values.apiKey,
            clientId: values.clientId,
            clientSecret: values.clientSecret,
          };
          break;
      }

      const response = await api.put(`/api/strategies/${selectedStrategy.strategyId}`, strategy);
      if (response.data.code === 200) {
        message.success(t('message.update_success'));
        editForm.resetFields();
        setEditDrawerVisible(false);
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

  const openEditDrawer = (strategy: StrategyDefinition) => {
    setSelectedStrategy(strategy);
    editForm.setFieldsValue({
      strategyName: strategy.strategyName,
      scope: strategy.scope,
      routeId: strategy.routeId,
      priority: strategy.priority,
      enabled: strategy.enabled,
      description: strategy.description,
      ...strategy.config,
    });
    setEditDrawerVisible(true);
  };

  const getActionMenu = (strategy: StrategyDefinition): MenuProps['items'] => [
    { key: 'edit', icon: <EditOutlined />, label: t('common.edit'), onClick: () => openEditDrawer(strategy) },
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
          <>
            <Form.Item name="qps" label={t('strategy.config.qps')} initialValue={100} extra={t('strategy.config.qps_desc')}>
              <Input type="number" min={1} />
            </Form.Item>
            <Form.Item name="burstCapacity" label={t('strategy.config.burst_capacity')} initialValue={200} extra={t('strategy.config.burst_desc')}>
              <Input type="number" min={1} />
            </Form.Item>
            <Form.Item name="timeUnit" label={t('strategy.config.time_unit')} initialValue="second">
              <Select>
                <Select.Option value="second">{t('strategy.config.second')}</Select.Option>
                <Select.Option value="minute">{t('strategy.config.minute')}</Select.Option>
                <Select.Option value="hour">{t('strategy.config.hour')}</Select.Option>
              </Select>
            </Form.Item>
            <Form.Item name="keyResolver" label={t('strategy.config.key_resolver')} initialValue="ip" extra={t('strategy.config.key_resolver_desc')}>
              <Select>
                <Select.Option value="ip">{t('strategy.config.key_resolver_ip')}</Select.Option>
                <Select.Option value="user">{t('strategy.config.key_resolver_user')}</Select.Option>
                <Select.Option value="header">{t('strategy.config.key_resolver_header')}</Select.Option>
                <Select.Option value="global">{t('strategy.config.key_resolver_global')}</Select.Option>
              </Select>
            </Form.Item>
            <Form.Item noStyle shouldUpdate>
              {({ getFieldValue }) => {
                const keyResolver = getFieldValue('keyResolver');
                if (keyResolver === 'header') {
                  return (
                    <Form.Item name="headerName" label={t('strategy.config.header_name')} rules={[{ required: true }]}>
                      <Input placeholder="X-Request-Id" />
                    </Form.Item>
                  );
                }
                return null;
              }}
            </Form.Item>
          </>
        );
      case 'IP_FILTER':
        return (
          <>
            <Form.Item name="mode" label={t('strategy.config.filter_mode')} initialValue="blacklist">
              <Select>
                <Select.Option value="blacklist">{t('strategy.config.blacklist')}</Select.Option>
                <Select.Option value="whitelist">{t('strategy.config.whitelist')}</Select.Option>
              </Select>
            </Form.Item>
            <Form.Item name="ipList" label={t('strategy.config.ip_list')}>
              <Input.TextArea rows={3} placeholder="192.168.1.1, 10.0.0.0/24" />
            </Form.Item>
          </>
        );
      case 'TIMEOUT':
        return (
          <>
            <Form.Item name="connectTimeout" label={t('strategy.config.connect_timeout')} initialValue={5000}>
              <Input type="number" addonAfter="ms" />
            </Form.Item>
            <Form.Item name="responseTimeout" label={t('strategy.config.response_timeout')} initialValue={30000}>
              <Input type="number" addonAfter="ms" />
            </Form.Item>
          </>
        );
      case 'CIRCUIT_BREAKER':
        return (
          <>
            <Form.Item name="failureRateThreshold" label={t('strategy.config.failure_rate')} initialValue={50}>
              <Input type="number" addonAfter="%" />
            </Form.Item>
            <Form.Item name="waitDurationInOpenState" label={t('strategy.config.wait_duration')} initialValue={30000}>
              <Input type="number" addonAfter="ms" />
            </Form.Item>
            <Form.Item name="slidingWindowSize" label={t('strategy.config.sliding_window')} initialValue={10}>
              <Input type="number" />
            </Form.Item>
          </>
        );
      case 'AUTH':
        return (
          <>
            <Form.Item name="authType" label={t('strategy.config.auth_type')} initialValue="JWT">
              <Select>
                <Select.Option value="JWT">JWT</Select.Option>
                <Select.Option value="API_KEY">API Key</Select.Option>
                <Select.Option value="OAUTH2">OAuth2</Select.Option>
              </Select>
            </Form.Item>
            <Form.Item name="secretKey" label={t('strategy.config.secret_key')}>
              <Input.Password />
            </Form.Item>
          </>
        );
      default:
        return null;
    }
  };

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
          <Select value={filterType} onChange={setFilterType} style={{ width: 140 }}>
            <Select.Option value="all">{t('common.all_types')}</Select.Option>
            <Select.Option value="RATE_LIMITER">{t('strategy.type.rate_limiter')}</Select.Option>
            <Select.Option value="IP_FILTER">{t('strategy.type.ip_filter')}</Select.Option>
            <Select.Option value="TIMEOUT">{t('strategy.type.timeout')}</Select.Option>
            <Select.Option value="CIRCUIT_BREAKER">{t('strategy.type.circuit_breaker')}</Select.Option>
            <Select.Option value="AUTH">{t('strategy.type.auth')}</Select.Option>
          </Select>
          <Select value={filterScope} onChange={setFilterScope} style={{ width: 140 }}>
            <Select.Option value="all">{t('common.all_scopes')}</Select.Option>
            <Select.Option value="GLOBAL">{t('strategy.scope_global')}</Select.Option>
            <Select.Option value="ROUTE">{t('strategy.scope_route')}</Select.Option>
          </Select>
          <Button type="primary" icon={<PlusOutlined />} onClick={() => setCreateDrawerVisible(true)} className="create-btn">
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
              <Button type="primary" icon={<PlusOutlined />} onClick={() => setCreateDrawerVisible(true)}>
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

      {/* Create Drawer */}
      <Drawer
        title={t('strategy.create')}
        placement="right"
        width={480}
        open={createDrawerVisible}
        onClose={() => { setCreateDrawerVisible(false); createForm.resetFields(); }}
      >
        <Form form={createForm} layout="vertical" onFinish={handleCreate}>
          <Form.Item name="strategyName" label={t('strategy.name')} rules={[{ required: true }]}>
            <Input placeholder={t('strategy.name_placeholder')} />
          </Form.Item>
          <Form.Item name="strategyType" label={t('strategy.type')} rules={[{ required: true }]}>
            <Select placeholder={t('strategy.type_placeholder')}>
              <Select.Option value="RATE_LIMITER">{t('strategy.type.rate_limiter')}</Select.Option>
              <Select.Option value="IP_FILTER">{t('strategy.type.ip_filter')}</Select.Option>
              <Select.Option value="TIMEOUT">{t('strategy.type.timeout')}</Select.Option>
              <Select.Option value="CIRCUIT_BREAKER">{t('strategy.type.circuit_breaker')}</Select.Option>
              <Select.Option value="AUTH">{t('strategy.type.auth')}</Select.Option>
            </Select>
          </Form.Item>
          <Form.Item name="scope" label={t('strategy.scope')} initialValue="GLOBAL">
            <Radio.Group>
              <Radio.Button value="GLOBAL"><GlobalOutlined /> {t('strategy.scope_global')}</Radio.Button>
              <Radio.Button value="ROUTE"><ApiOutlined /> {t('strategy.scope_route')}</Radio.Button>
            </Radio.Group>
          </Form.Item>
          <Form.Item noStyle shouldUpdate>
            {({ getFieldValue }) => {
              const scope = getFieldValue('scope');
              if (scope === 'ROUTE') {
                return (
                  <Form.Item name="routeId" label={t('strategy.route_id')} rules={[{ required: true }]}>
                    <Input placeholder={t('strategy.route_id_placeholder')} />
                  </Form.Item>
                );
              }
              return null;
            }}
          </Form.Item>
          <Form.Item noStyle shouldUpdate>
            {({ getFieldValue }) => renderConfigFields(getFieldValue('strategyType'), createForm)}
          </Form.Item>
          <Form.Item name="priority" label={t('strategy.priority')} initialValue={100}>
            <Input type="number" />
          </Form.Item>
          <Form.Item name="description" label={t('strategy.description_label')}>
            <Input.TextArea rows={2} />
          </Form.Item>
          <Form.Item name="enabled" label={t('strategy.enabled')} valuePropName="checked" initialValue={true}>
            <Switch checkedChildren={t('common.enabled')} unCheckedChildren={t('common.disabled')} />
          </Form.Item>
          <div className="drawer-footer">
            <Button onClick={() => setCreateDrawerVisible(false)}>{t('common.cancel')}</Button>
            <Button type="primary" htmlType="submit">{t('common.create')}</Button>
          </div>
        </Form>
      </Drawer>

      {/* Edit Drawer */}
      <Drawer
        title={t('strategy.edit')}
        placement="right"
        width={480}
        open={editDrawerVisible}
        onClose={() => { setEditDrawerVisible(false); editForm.resetFields(); setSelectedStrategy(null); }}
      >
        <Form form={editForm} layout="vertical" onFinish={handleUpdate}>
          <Form.Item name="strategyName" label={t('strategy.name')} rules={[{ required: true }]}>
            <Input />
          </Form.Item>
          <Form.Item name="scope" label={t('strategy.scope')}>
            <Radio.Group>
              <Radio.Button value="GLOBAL"><GlobalOutlined /> {t('strategy.scope_global')}</Radio.Button>
              <Radio.Button value="ROUTE"><ApiOutlined /> {t('strategy.scope_route')}</Radio.Button>
            </Radio.Group>
          </Form.Item>
          <Form.Item noStyle shouldUpdate>
            {({ getFieldValue }) => {
              const scope = getFieldValue('scope');
              if (scope === 'ROUTE') {
                return (
                  <Form.Item name="routeId" label={t('strategy.route_id')} rules={[{ required: true }]}>
                    <Input />
                  </Form.Item>
                );
              }
              return null;
            }}
          </Form.Item>
          {selectedStrategy && renderConfigFields(selectedStrategy.strategyType, editForm)}
          <Form.Item name="priority" label={t('strategy.priority')}>
            <Input type="number" />
          </Form.Item>
          <Form.Item name="description" label={t('strategy.description_label')}>
            <Input.TextArea rows={2} />
          </Form.Item>
          <Form.Item name="enabled" label={t('strategy.enabled')} valuePropName="checked">
            <Switch checkedChildren={t('common.enabled')} unCheckedChildren={t('common.disabled')} />
          </Form.Item>
          <div className="drawer-footer">
            <Button onClick={() => setEditDrawerVisible(false)}>{t('common.cancel')}</Button>
            <Button type="primary" htmlType="submit">{t('common.update')}</Button>
          </div>
        </Form>
      </Drawer>
    </div>
  );
};

export default StrategiesPage;