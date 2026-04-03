import { useState, useEffect, useCallback, memo, useMemo } from 'react';
import {
  Card, Button, Space, Modal, message, Spin, Tag, Form, Input, Select,
  Empty, Dropdown, Tooltip, Badge, Typography, Switch, InputNumber, Row, Col, Statistic, Popconfirm
} from 'antd';
import {
  PlusOutlined, DeleteOutlined, EditOutlined, MoreOutlined,
  StopOutlined, PlayCircleOutlined, KeyOutlined, ApiOutlined,
  SafetyOutlined, LinkOutlined, CopyOutlined, EyeOutlined, EyeInvisibleOutlined
} from '@ant-design/icons';
import type { MenuProps } from 'antd';
import api from '../utils/api';
import { useTranslation } from 'react-i18next';
import './StrategiesPage.premium.css';

const { Text, Title } = Typography;

interface AuthPolicy {
  policyId: string;
  policyName: string;
  authType: 'JWT' | 'API_KEY' | 'OAUTH2' | 'BASIC' | 'HMAC';
  enabled: boolean;
  description?: string;
  secretKey?: string;
  jwtIssuer?: string;
  jwtAudience?: string;
  jwtAlgorithm?: string;
  jwtClockSkewSeconds?: number;
  apiKey?: string;
  apiKeyHeader?: string;
  apiKeyPrefix?: string;
  clientId?: string;
  clientSecret?: string;
  tokenEndpoint?: string;
  basicUsername?: string;
  basicPassword?: string;
  realm?: string;
  accessKey?: string;
  signatureAlgorithm?: string;
}

interface RouteBinding {
  bindingId: string;
  policyId: string;
  routeId: string;
  priority: number;
  enabled: boolean;
}

interface Route {
  id: string;
  routeName: string;
  enabled: boolean;
}

// Separate component for auth policy action dropdown to prevent re-renders
interface AuthPolicyActionDropdownProps {
  policy: AuthPolicy;
  onEdit: (policy: AuthPolicy) => void;
  onBind: (policy: AuthPolicy) => void;
  onToggle: (policy: AuthPolicy) => void;
  onDelete: (policy: AuthPolicy) => void;
}

const AuthPolicyActionDropdown = memo(({ policy, onEdit, onBind, onToggle, onDelete }: AuthPolicyActionDropdownProps) => {
  const { t } = useTranslation();

  const handleEdit = useCallback(() => onEdit(policy), [onEdit, policy]);
  const handleBind = useCallback(() => onBind(policy), [onBind, policy]);
  const handleToggle = useCallback(() => onToggle(policy), [onToggle, policy]);
  const handleDelete = useCallback(() => onDelete(policy), [onDelete, policy]);

  return (
    <Space>
      <Tooltip title={t('common.edit')}>
        <Button type="text" size="small" icon={<EditOutlined />} className="action-btn" onClick={handleEdit} />
      </Tooltip>
      <Tooltip title={t('auth_policy.bind_routes')}>
        <Button type="text" size="small" icon={<LinkOutlined />} className="action-btn" onClick={handleBind} />
      </Tooltip>
      <Tooltip title={policy.enabled ? t('common.disable') : t('common.enable')}>
        <Button type="text" size="small" icon={policy.enabled ? <StopOutlined /> : <PlayCircleOutlined />} className="action-btn" onClick={handleToggle} />
      </Tooltip>
      <Popconfirm
        title={`${t('common.delete')}: ${policy.policyName}`}
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

interface AuthPoliciesPageProps {
  instanceId?: string;
}

const AuthPoliciesPage: React.FC<AuthPoliciesPageProps> = ({ instanceId }) => {
  const { t } = useTranslation();
  const [policies, setPolicies] = useState<AuthPolicy[]>([]);
  const [routes, setRoutes] = useState<Route[]>([]);
  const [bindings, setBindings] = useState<RouteBinding[]>([]);
  const [loading, setLoading] = useState(false);
  const [createModalVisible, setCreateModalVisible] = useState(false);
  const [editModalVisible, setEditModalVisible] = useState(false);
  const [bindingModalVisible, setBindingModalVisible] = useState(false);
  const [selectedPolicy, setSelectedPolicy] = useState<AuthPolicy | null>(null);
  const [filterType, setFilterType] = useState<string>('all');
  const [createForm] = Form.useForm();
  const [editForm] = Form.useForm();
  const [bindingForm] = Form.useForm();

  useEffect(() => {
    loadData();
  }, [instanceId]);

  const loadData = useCallback(async () => {
    setLoading(true);
    try {
      const params = instanceId ? { instanceId } : {};
      const [policiesRes, routesRes, bindingsRes] = await Promise.all([
        api.get('/api/auth/policies', { params }),
        api.get('/api/routes', { params }),
        api.get('/api/auth/bindings', { params }),
      ]);

      if (policiesRes.data.code === 200) setPolicies(policiesRes.data.data || []);
      if (routesRes.data.code === 200) setRoutes(routesRes.data.data || []);
      if (bindingsRes.data.code === 200) setBindings(bindingsRes.data.data || []);
    } catch (error: any) {
      message.error(t('message.load_failed'));
    } finally {
      setLoading(false);
    }
  }, [t]);

  const filteredPolicies = policies.filter(p => {
    return filterType === 'all' || p.authType === filterType;
  });

  const totalPolicies = policies.length;
  const enabledPolicies = policies.filter(p => p.enabled).length;
  const boundRoutes = new Set(bindings.filter(b => b.enabled).map(b => b.routeId)).size;

  const getAuthTypeLabel = (type: string) => {
    const labels: Record<string, string> = {
      'JWT': 'JWT',
      'API_KEY': t('auth_policy.type_api_key'),
      'OAUTH2': 'OAuth2',
      'BASIC': t('auth_policy.type_basic'),
      'HMAC': 'HMAC',
    };
    return labels[type] || type;
  };

  const getAuthTypeColor = (type: string) => {
    const colors: Record<string, string> = {
      'JWT': '#3b82f6',
      'API_KEY': '#10b981',
      'OAUTH2': '#8b5cf6',
      'BASIC': '#f59e0b',
      'HMAC': '#ef4444',
    };
    return colors[type] || '#6b7280';
  };

  const getBindingsForPolicy = (policyId: string) => {
    return bindings.filter(b => b.policyId === policyId);
  };

  const getRouteName = (routeId: string) => {
    const route = routes.find(r => r.id === routeId);
    return route?.routeName || routeId?.substring(0, 12) || 'Unknown';
  };

  const handleCreate = async (values: any) => {
    try {
      const params = instanceId ? { instanceId } : {};
      const response = await api.post('/api/auth/policies', values, { params });
      if (response.data.code === 200) {
        message.success(t('message.create_success'));
        setCreateModalVisible(false);
        createForm.resetFields();
        loadData();
      } else {
        message.error(response.data.message);
      }
    } catch (error: any) {
      message.error(error.response?.data?.message || t('message.create_failed'));
    }
  };

  const handleUpdate = async (values: any) => {
    if (!selectedPolicy) return;
    try {
      const params = instanceId ? { instanceId } : {};
      const response = await api.put(`/api/auth/policies/${selectedPolicy.policyId}`, values, { params });
      if (response.data.code === 200) {
        message.success(t('message.update_success'));
        setEditModalVisible(false);
        editForm.resetFields();
        setSelectedPolicy(null);
        loadData();
      } else {
        message.error(response.data.message);
      }
    } catch (error: any) {
      message.error(error.response?.data?.message || t('message.update_failed'));
    }
  };

  const handleDelete = useCallback(async (policyId: string) => {
    Modal.confirm({
      title: t('auth_policy.delete_confirm_title'),
      content: t('auth_policy.delete_confirm_content'),
      okText: t('common.delete'),
      okButtonProps: { danger: true },
      cancelText: t('common.cancel'),
      onOk: async () => {
        try {
          const params = instanceId ? { instanceId } : {};
          const response = await api.delete(`/api/auth/policies/${policyId}`, { params });
          if (response.data.code === 200) {
            message.success(t('message.delete_success'));
            loadData();
          } else {
            message.error(response.data.message);
          }
        } catch (error: any) {
          message.error(error.response?.data?.message || t('message.delete_failed'));
        }
      },
    });
  }, [t, loadData, instanceId]);

  const handleToggle = useCallback(async (policyId: string, enabled: boolean) => {
    try {
      const endpoint = enabled ? 'enable' : 'disable';
      const params = instanceId ? { instanceId } : {};
      const response = await api.post(`/api/auth/policies/${policyId}/${endpoint}`, null, { params });
      if (response.data.code === 200) {
        message.success(enabled ? t('auth_policy.enabled') : t('auth_policy.disabled'));
        loadData();
      }
    } catch (error: any) {
      message.error(error.response?.data?.message);
    }
  }, [t, loadData, instanceId]);

  // Wrapper for dropdown to accept policy object
  const handleTogglePolicy = useCallback((policy: AuthPolicy) => {
    handleToggle(policy.policyId, !policy.enabled);
  }, [handleToggle]);

  const handleDeletePolicy = useCallback((policy: AuthPolicy) => {
    handleDelete(policy.policyId);
  }, [handleDelete]);

  const handleBind = async (values: any) => {
    if (!selectedPolicy) return;
    try {
      const params = instanceId ? { instanceId } : {};
      const response = await api.post('/api/auth/bindings', {
        policyId: selectedPolicy.policyId,
        routeId: values.routeId,
        priority: values.priority || 100,
      }, { params });
      if (response.data.code === 200) {
        message.success(t('auth_policy.binding_created'));
        loadData();
      } else {
        message.error(response.data.message);
      }
    } catch (error: any) {
      message.error(error.response?.data?.message || t('auth_policy.binding_failed'));
    }
  };

  const handleUnbind = async (bindingId: string) => {
    try {
      const params = instanceId ? { instanceId } : {};
      const response = await api.delete(`/api/auth/bindings/${bindingId}`, { params });
      if (response.data.code === 200) {
        message.success(t('auth_policy.binding_removed'));
        loadData();
      }
    } catch (error: any) {
      message.error(error.response?.data?.message);
    }
  };

  const openEditModal = useCallback((policy: AuthPolicy) => {
    setSelectedPolicy(policy);
    editForm.setFieldsValue(policy);
    setEditModalVisible(true);
  }, [editForm]);

  const openBindingModal = useCallback((policy: AuthPolicy) => {
    setSelectedPolicy(policy);
    bindingForm.resetFields();
    setBindingModalVisible(true);
  }, [bindingForm]);

  const renderAuthConfigFields = (formInstance: typeof createForm, authType: string) => {
    switch (authType) {
      case 'JWT':
        return (
          <>
            <Form.Item name="secretKey" label={t('auth_policy.jwt_secret')} rules={[{ required: true }]}>
              <Input.Password />
            </Form.Item>
            <Row gutter={16}>
              <Col span={12}>
                <Form.Item name="jwtAlgorithm" label={t('auth_policy.jwt_algorithm')}>
                  <Select defaultValue="HS256">
                    <Select.Option value="HS256">HS256</Select.Option>
                    <Select.Option value="HS512">HS512</Select.Option>
                    <Select.Option value="RS256">RS256</Select.Option>
                  </Select>
                </Form.Item>
              </Col>
              <Col span={12}>
                <Form.Item name="jwtClockSkewSeconds" label={t('auth_policy.jwt_clock_skew')}>
                  <InputNumber min={0} defaultValue={60} style={{ width: '100%' }} />
                </Form.Item>
              </Col>
            </Row>
            <Form.Item name="jwtIssuer" label={t('auth_policy.jwt_issuer')}>
              <Input />
            </Form.Item>
            <Form.Item name="jwtAudience" label={t('auth_policy.jwt_audience')}>
              <Input />
            </Form.Item>
          </>
        );
      case 'API_KEY':
        return (
          <>
            <Form.Item name="apiKey" label={t('auth_policy.api_key')} rules={[{ required: true }]}>
              <Input.Password />
            </Form.Item>
            <Form.Item name="apiKeyHeader" label={t('auth_policy.api_key_header')}>
              <Input defaultValue="X-API-Key" />
            </Form.Item>
            <Form.Item name="apiKeyPrefix" label={t('auth_policy.api_key_prefix')}>
              <Input placeholder="e.g., sk_live_" />
            </Form.Item>
          </>
        );
      case 'OAUTH2':
        return (
          <>
            <Form.Item name="clientId" label={t('auth_policy.client_id')} rules={[{ required: true }]}>
              <Input />
            </Form.Item>
            <Form.Item name="clientSecret" label={t('auth_policy.client_secret')} rules={[{ required: true }]}>
              <Input.Password />
            </Form.Item>
            <Form.Item name="tokenEndpoint" label={t('auth_policy.token_endpoint')} rules={[{ required: true }]}>
              <Input placeholder="https://auth.example.com/introspect" />
            </Form.Item>
            <Form.Item name="requiredScopes" label={t('auth_policy.required_scopes')}>
              <Input placeholder="read,write" />
            </Form.Item>
          </>
        );
      case 'BASIC':
        return (
          <>
            <Form.Item name="basicUsername" label={t('auth_policy.username')} rules={[{ required: true }]}>
              <Input />
            </Form.Item>
            <Form.Item name="basicPassword" label={t('auth_policy.password')} rules={[{ required: true }]}>
              <Input.Password />
            </Form.Item>
            <Form.Item name="realm" label={t('auth_policy.realm')}>
              <Input defaultValue="API Gateway" />
            </Form.Item>
          </>
        );
      case 'HMAC':
        return (
          <>
            <Form.Item name="accessKey" label={t('auth_policy.access_key')} rules={[{ required: true }]}>
              <Input />
            </Form.Item>
            <Form.Item name="signatureAlgorithm" label={t('auth_policy.signature_algorithm')}>
              <Select defaultValue="HMAC-SHA256">
                <Select.Option value="HMAC-SHA256">HMAC-SHA256</Select.Option>
                <Select.Option value="HMAC-SHA512">HMAC-SHA512</Select.Option>
              </Select>
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
        <Card className="stats-card">
          <Statistic title={t('auth_policy.stats_total')} value={totalPolicies} prefix={<KeyOutlined />} />
        </Card>
        <Card className="stats-card">
          <Statistic title={t('auth_policy.stats_enabled')} value={enabledPolicies} prefix={<SafetyOutlined />} valueStyle={{ color: '#10b981' }} />
        </Card>
        <Card className="stats-card">
          <Statistic title={t('auth_policy.stats_bound')} value={boundRoutes} prefix={<LinkOutlined />} valueStyle={{ color: '#3b82f6' }} />
        </Card>
      </div>

      {/* Header */}
      <div className="page-header-modern">
        <div className="page-header-left">
          <KeyOutlined className="page-icon" />
          <Title level={2}>{t('auth_policy.title')}</Title>
        </div>
        <div className="page-header-right">
          <Select value={filterType} onChange={setFilterType} style={{ width: 150 }}>
            <Select.Option value="all">{t('auth_policy.all_types')}</Select.Option>
            <Select.Option value="JWT">JWT</Select.Option>
            <Select.Option value="API_KEY">{t('auth_policy.type_api_key')}</Select.Option>
            <Select.Option value="OAUTH2">OAuth2</Select.Option>
            <Select.Option value="BASIC">{t('auth_policy.type_basic')}</Select.Option>
            <Select.Option value="HMAC">HMAC</Select.Option>
          </Select>
          <Button type="primary" icon={<PlusOutlined />} onClick={() => setCreateModalVisible(true)}>
            {t('auth_policy.create')}
          </Button>
        </div>
      </div>

      {/* Content */}
      <Spin spinning={loading}>
        {filteredPolicies.length === 0 ? (
          <Empty description={t('auth_policy.empty')} />
        ) : (
          <div className="strategies-grid">
            {filteredPolicies.map(policy => {
              const policyBindings = getBindingsForPolicy(policy.policyId);
              return (
                <Card key={policy.policyId} className="strategy-card">
                  <div className="strategy-card-header">
                    <div className="strategy-info">
                      <div className="strategy-icon" style={{ background: getAuthTypeColor(policy.authType) }}>
                        <KeyOutlined />
                      </div>
                      <div className="strategy-details">
                        <Text strong className="strategy-name">{policy.policyName}</Text>
                        <Text type="secondary" className="strategy-id">
                          {policy.policyId?.substring(0, 12)}...
                        </Text>
                      </div>
                    </div>
                    <Badge status={policy.enabled ? 'success' : 'default'} />
                    <AuthPolicyActionDropdown
                      policy={policy}
                      onEdit={openEditModal}
                      onBind={openBindingModal}
                      onToggle={handleTogglePolicy}
                      onDelete={handleDeletePolicy}
                    />
                  </div>

                  <div className="strategy-section">
                    <Text type="secondary" className="section-label">
                      {t('auth_policy.auth_type')}
                    </Text>
                    <Tag color={getAuthTypeColor(policy.authType)}>{getAuthTypeLabel(policy.authType)}</Tag>
                  </div>

                  <div className="strategy-section">
                    <Text type="secondary" className="section-label">
                      {t('auth_policy.bound_routes')}
                    </Text>
                    {policyBindings.length > 0 ? (
                      <div className="tags-container">
                        {policyBindings.slice(0, 3).map(binding => (
                          <Tag key={binding.bindingId} color="blue">
                            {getRouteName(binding.routeId)}
                          </Tag>
                        ))}
                        {policyBindings.length > 3 && (
                          <Tag>+{policyBindings.length - 3}</Tag>
                        )}
                      </div>
                    ) : (
                      <Text type="secondary">{t('auth_policy.no_bindings')}</Text>
                    )}
                  </div>

                  {policy.description && (
                    <div className="strategy-section">
                      <Text type="secondary" className="section-label">{t('common.description')}</Text>
                      <Text className="strategy-description">{policy.description}</Text>
                    </div>
                  )}
                </Card>
              );
            })}
          </div>
        )}
      </Spin>

      {/* Create Modal */}
      <Modal
        title={
          <div className="modal-header-modern">
            <div className="modal-icon-wrapper">
              <PlusOutlined />
            </div>
            <div className="modal-title-text">
              <div className="modal-title">{t('auth_policy.create')}</div>
              <div className="modal-subtitle">{t('auth_policy.create_description')}</div>
            </div>
          </div>
        }
        open={createModalVisible}
        onCancel={() => setCreateModalVisible(false)}
        footer={null}
        width={600}
      >
        <Form form={createForm} layout="vertical" onFinish={handleCreate}>
          <div className="form-section-modern">
            <Form.Item name="policyName" label={t('auth_policy.policy_name')} rules={[{ required: true }]}>
              <Input placeholder={t('auth_policy.policy_name_placeholder')} />
            </Form.Item>
            <Form.Item name="authType" label={t('auth_policy.auth_type')} rules={[{ required: true }]}>
              <Select onChange={() => createForm.resetFields(['secretKey', 'apiKey', 'clientId', 'basicUsername', 'accessKey'])}>
                <Select.Option value="JWT">JWT</Select.Option>
                <Select.Option value="API_KEY">{t('auth_policy.type_api_key')}</Select.Option>
                <Select.Option value="OAUTH2">OAuth2</Select.Option>
                <Select.Option value="BASIC">{t('auth_policy.type_basic')}</Select.Option>
                <Select.Option value="HMAC">HMAC</Select.Option>
              </Select>
            </Form.Item>
          </div>
          <div className="form-section-modern">
            <div className="section-header">
              <SafetyOutlined className="section-icon" />
              <span className="section-title">{t('auth_policy.config_section')}</span>
            </div>
            <Form.Item noStyle shouldUpdate>
              {({ getFieldValue }) => renderAuthConfigFields(createForm, getFieldValue('authType'))}
            </Form.Item>
          </div>
          <Form.Item name="description" label={t('common.description')}>
            <Input.TextArea rows={2} />
          </Form.Item>
          <div className="modal-footer-modern">
            <Button onClick={() => setCreateModalVisible(false)}>{t('common.cancel')}</Button>
            <Button type="primary" htmlType="submit">{t('common.create')}</Button>
          </div>
        </Form>
      </Modal>

      {/* Edit Modal */}
      <Modal
        title={
          <div className="modal-header-modern">
            <div className="modal-icon-wrapper edit">
              <EditOutlined />
            </div>
            <div className="modal-title-text">
              <div className="modal-title">{t('auth_policy.edit')}</div>
            </div>
          </div>
        }
        open={editModalVisible}
        onCancel={() => { setEditModalVisible(false); setSelectedPolicy(null); }}
        footer={null}
        width={600}
      >
        <Form form={editForm} layout="vertical" onFinish={handleUpdate}>
          <div className="form-section-modern">
            <Form.Item name="policyName" label={t('auth_policy.policy_name')} rules={[{ required: true }]}>
              <Input />
            </Form.Item>
            <Form.Item name="authType" label={t('auth_policy.auth_type')}>
              <Select disabled>
                <Select.Option value="JWT">JWT</Select.Option>
                <Select.Option value="API_KEY">{t('auth_policy.type_api_key')}</Select.Option>
                <Select.Option value="OAUTH2">OAuth2</Select.Option>
                <Select.Option value="BASIC">{t('auth_policy.type_basic')}</Select.Option>
                <Select.Option value="HMAC">HMAC</Select.Option>
              </Select>
            </Form.Item>
          </div>
          <div className="form-section-modern">
            <div className="section-header">
              <SafetyOutlined className="section-icon" />
              <span className="section-title">{t('auth_policy.config_section')}</span>
            </div>
            <Form.Item noStyle shouldUpdate>
              {({ getFieldValue }) => renderAuthConfigFields(editForm, getFieldValue('authType'))}
            </Form.Item>
          </div>
          <Form.Item name="description" label={t('common.description')}>
            <Input.TextArea rows={2} />
          </Form.Item>
          <div className="modal-footer-modern">
            <Button onClick={() => setEditModalVisible(false)}>{t('common.cancel')}</Button>
            <Button type="primary" htmlType="submit">{t('common.update')}</Button>
          </div>
        </Form>
      </Modal>

      {/* Binding Modal */}
      <Modal
        title={
          <div className="modal-header-modern">
            <div className="modal-icon-wrapper" style={{ background: 'linear-gradient(135deg, #3b82f6, #1d4ed8)' }}>
              <LinkOutlined />
            </div>
            <div className="modal-title-text">
              <div className="modal-title">{t('auth_policy.bind_routes')}</div>
              <div className="modal-subtitle">{selectedPolicy?.policyName}</div>
            </div>
          </div>
        }
        open={bindingModalVisible}
        onCancel={() => { setBindingModalVisible(false); setSelectedPolicy(null); }}
        footer={null}
        width={600}
      >
        {/* Current Bindings */}
        {selectedPolicy && (
          <div className="form-section-modern">
            <div className="section-header">
              <LinkOutlined className="section-icon" />
              <span className="section-title">{t('auth_policy.current_bindings')}</span>
            </div>
            {getBindingsForPolicy(selectedPolicy.policyId).length > 0 ? (
              <div style={{ marginBottom: 16 }}>
                {getBindingsForPolicy(selectedPolicy.policyId).map(binding => (
                  <div key={binding.bindingId} style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', padding: '8px 0', borderBottom: '1px solid rgba(148, 163, 184, 0.2)' }}>
                    <div>
                      <Tag color="blue">{getRouteName(binding.routeId)}</Tag>
                      <Text type="secondary" style={{ marginLeft: 8 }}>Priority: {binding.priority}</Text>
                    </div>
                    <Button size="small" danger onClick={() => handleUnbind(binding.bindingId)}>
                      {t('auth_policy.unbind')}
                    </Button>
                  </div>
                ))}
              </div>
            ) : (
              <Text type="secondary">{t('auth_policy.no_bindings')}</Text>
            )}
          </div>
        )}

        {/* Add New Binding */}
        <div className="form-section-modern">
          <div className="section-header">
            <PlusOutlined className="section-icon" />
            <span className="section-title">{t('auth_policy.add_binding')}</span>
          </div>
          <Form form={bindingForm} layout="vertical" onFinish={handleBind}>
            <Form.Item name="routeId" label={t('auth_policy.select_route')} rules={[{ required: true }]}>
              <Select showSearch optionFilterProp="children">
                {routes.map(route => (
                  <Select.Option key={route.id} value={route.id}>
                    {route.routeName || route.id}
                  </Select.Option>
                ))}
              </Select>
            </Form.Item>
            <Form.Item name="priority" label={t('auth_policy.binding_priority')}>
              <InputNumber min={1} max={1000} defaultValue={100} style={{ width: '100%' }} />
            </Form.Item>
            <div className="modal-footer-modern">
              <Button onClick={() => setBindingModalVisible(false)}>{t('common.cancel')}</Button>
              <Button type="primary" htmlType="submit">{t('auth_policy.add_binding')}</Button>
            </div>
          </Form>
        </div>
      </Modal>
    </div>
  );
};

export default AuthPoliciesPage;