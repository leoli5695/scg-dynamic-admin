import { useState, useEffect, useMemo, useCallback, memo } from 'react';
import {
  Card, Button, Space, Modal, message, Spin, Tag, Form, Input, Select,
  Empty, Dropdown, Tooltip, Badge, Divider, Typography, Alert, Drawer,
  Row, Col, Statistic, Popconfirm
} from 'antd';
import {
  PlusOutlined, DeleteOutlined, EditOutlined, MoreOutlined,
  ClusterOutlined, MinusCircleOutlined, CloudServerOutlined, ApiOutlined,
  WarningOutlined, ExclamationCircleOutlined, CloseOutlined, EyeOutlined,
  CopyOutlined, CheckCircleOutlined, CloseCircleOutlined, SearchOutlined,
  ReloadOutlined, ThunderboltOutlined, LinkOutlined, TrophyOutlined,
  SwapOutlined, AimOutlined, ForkOutlined
} from '@ant-design/icons';
import type { MenuProps } from 'antd';
import api from '../utils/api';
import { useTranslation } from 'react-i18next';

const { Text, Title } = Typography;

interface ServiceInstance {
  ip: string;
  port: number;
  weight?: number;
  healthy?: boolean;
  enabled?: boolean;
}

interface Service {
  name: string;
  serviceId?: string;
  description?: string;
  loadBalancer?: string;
  instances?: ServiceInstance[];
}

const IPV4_REGEX = /^(\d{1,3}\.){3}\d{1,3}$/;
const isValidIP = (ip: string) => ip && IPV4_REGEX.test(ip) && ip.split('.').map(Number).every(o => o >= 0 && o <= 255);
const isValidPort = (port: number) => Number.isInteger(port) && port > 0 && port < 65536;

// Separate component for action dropdown to prevent re-renders
interface ServiceActionDropdownProps {
  service: Service;
  onDetail: (service: Service) => void;
  onEdit: (service: Service) => void;
  onDelete: (service: Service) => void;
}

const ServiceActionDropdown = memo(({ service, onDetail, onEdit, onDelete }: ServiceActionDropdownProps) => {
  const { t } = useTranslation();

  const handleDetailClick = useCallback(() => onDetail(service), [onDetail, service]);
  const handleEditClick = useCallback(() => onEdit(service), [onEdit, service]);
  const handleDeleteClick = useCallback(() => onDelete(service), [onDelete, service]);

  return (
    <Space>
      <Tooltip title={t('common.detail')}>
        <Button type="text" size="small" icon={<EyeOutlined />} className="action-btn" onClick={handleDetailClick} />
      </Tooltip>
      <Tooltip title={t('common.edit')}>
        <Button type="text" size="small" icon={<EditOutlined />} className="action-btn" onClick={handleEditClick} />
      </Tooltip>
      <Popconfirm
        title={`${t('common.delete')}: ${service.name}`}
        onConfirm={handleDeleteClick}
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

interface ServicesPageProps {
  instanceId?: string;
}

// Separate component for service card to prevent re-renders when drawer opens
interface ServiceCardProps {
  service: Service;
  onDetail: (service: Service) => void;
  onEdit: (service: Service) => void;
  onDelete: (service: Service) => void;
  getStatus: (s: Service) => { color: string; text: string };
  getLoadBalancerLabel: (lb: string) => string;
}

const ServiceCard = memo(({ service, onDetail, onEdit, onDelete, getStatus, getLoadBalancerLabel }: ServiceCardProps) => {
  const { t } = useTranslation();
  const status = useMemo(() => getStatus(service), [getStatus, service]);
  const instanceCount = service.instances?.length || 0;
  const instances = service.instances || [];
  const enabledInstances = useMemo(() => instances.filter(inst => inst.enabled !== false), [instances]);
  const healthyEnabled = useMemo(() => enabledInstances.filter(inst => inst.healthy !== false).length, [enabledInstances]);

  const warningMessage = useMemo(() => {
    if (instanceCount === 0) return t('services.no_instances_warning');
    if (enabledInstances.length === 0) return t('services.all_instances_disabled_warning');
    if (healthyEnabled === 0) return t('services.all_instances_down_warning');
    return null;
  }, [t, instanceCount, enabledInstances.length, healthyEnabled]);

  const handleCopyId = useCallback((e: React.MouseEvent) => {
    e.stopPropagation();
    navigator.clipboard.writeText(service.serviceId || service.name);
    message.success(t('message.copy_success'));
  }, [t, service.serviceId, service.name]);

  return (
    <Card
      className={`service-card ${warningMessage ? 'service-card-warning' : ''}`}
      hoverable
      style={{ minHeight: '260px', height: '100%' }}
    >
      {warningMessage && (
        <Alert
          type="warning"
          showIcon
          icon={<ExclamationCircleOutlined />}
          message={warningMessage}
          className="service-warning-alert"
          style={{ marginBottom: 16, background: 'rgba(245, 158, 11, 0.08)', border: '1px solid rgba(245, 158, 11, 0.2)' }}
        />
      )}
      <div className="service-card-header">
        <div className="service-info">
          <div className="service-icon"><CloudServerOutlined /></div>
          <div className="service-details">
            <Text strong className="service-name">{service.name}</Text>
            <div className="service-id-row">
              <Text type="secondary" className="service-id">{service.serviceId || service.name}</Text>
              <Tooltip title={t('common.copy')}>
                <Button
                  type="text"
                  size="small"
                  icon={<CopyOutlined />}
                  className="copy-btn"
                  onClick={handleCopyId}
                />
              </Tooltip>
            </div>
          </div>
        </div>
        <ServiceActionDropdown
          service={service}
          onDetail={onDetail}
          onEdit={onEdit}
          onDelete={onDelete}
        />
      </div>
      <div className="service-meta">
        <Badge status={status.color as any} text={status.text} />
        <Tag className="lb-tag">{getLoadBalancerLabel(service.loadBalancer || 'weighted')}</Tag>
      </div>
      <div className="instances-section" style={{ flex: 1 }}>
        <div className="instances-header">
          <Text type="secondary" className="instances-title">
            <ApiOutlined /> {healthyEnabled}/{instanceCount} {t('services.available_instances')}
          </Text>
        </div>
        {instanceCount > 0 && (
          <div className="instances-list">
            {instances.slice(0, 5).map((inst, idx) => (
              <Tag
                key={idx}
                className={`instance-tag ${inst.healthy !== false && inst.enabled !== false ? 'instance-tag-healthy' : 'instance-tag-unhealthy'}`}
                bordered={false}
              >
                <span className="instance-dot" style={{ background: inst.healthy !== false && inst.enabled !== false ? '#10b981' : '#ef4444' }} />
                <span className="instance-addr">{inst.ip}:{inst.port}</span>
                {inst.enabled === false && (
                  <Tag color="red" style={{ marginLeft: 4, fontSize: 12 }}>{t('common.disabled')}</Tag>
                )}
              </Tag>
            ))}
            {instanceCount > 5 && (
              <Tag className="instance-tag instance-tag-more">
                +{instanceCount - 5} more
              </Tag>
            )}
          </div>
        )}
        {instanceCount === 0 && (
          <Text type="secondary" className="no-instances">
            {t('services.no_instances_configured')}
          </Text>
        )}
      </div>
    </Card>
  );
});

// Separate component for instance input row to prevent full page re-render
interface InstanceRowProps {
  instance: ServiceInstance;
  index: number;
  onChange: (index: number, field: string, value: any) => void;
  onRemove: (index: number) => void;
  canRemove: boolean;
  t: (key: string) => string;
}

const InstanceRow = ({ instance, index, onChange, onRemove, canRemove, t }: InstanceRowProps) => {
  return (
    <div className="instance-row-modern">
      <div className="instance-row-header">
        <span className="instance-label">{t('services.instance')} {index + 1}</span>
        <Button
          type="text"
          danger
          size="small"
          icon={<MinusCircleOutlined />}
          onClick={() => onRemove(index)}
          disabled={!canRemove}
        />
      </div>
      <div className="instance-row-grid">
        <Form.Item label={t('services.ip_address')} className="instance-form-item">
          <Input 
            placeholder="192.168.1.100" 
            value={instance.ip} 
            onChange={e => onChange(index, 'ip', e.target.value)} 
            className="instance-input-ip" 
            size="large" 
          />
        </Form.Item>
        <Form.Item label={t('services.port')} className="instance-form-item">
          <Input 
            type="number" 
            placeholder="8080" 
            value={instance.port} 
            onChange={e => onChange(index, 'port', parseInt(e.target.value) || 8080)} 
            className="instance-input-port" 
            size="large" 
          />
        </Form.Item>
        <Form.Item label={t('services.weight')} className="instance-form-item">
          <Input 
            type="number" 
            placeholder="1" 
            value={instance.weight} 
            onChange={e => onChange(index, 'weight', parseInt(e.target.value) || 1)} 
            className="instance-input-weight" 
            size="large" 
            min={1} 
            max={100} 
          />
        </Form.Item>
        <Form.Item label={t('services.status')} className="instance-form-item">
          <Select 
            value={instance.enabled !== false ? 'enabled' : 'disabled'} 
            onChange={v => onChange(index, 'enabled', v === 'enabled')} 
            className="instance-input-status" 
            size="large"
          >
            <Select.Option value="enabled"><span className="status-dot status-dot-success"></span>{t('common.enabled')}</Select.Option>
            <Select.Option value="disabled"><span className="status-dot status-dot-default"></span>{t('common.disabled')}</Select.Option>
          </Select>
        </Form.Item>
      </div>
    </div>
  );
};

const ServicesPage: React.FC<ServicesPageProps> = ({ instanceId }) => {
  const [services, setServices] = useState<Service[]>([]);
  const [loading, setLoading] = useState(false);
  const [createModalVisible, setCreateModalVisible] = useState(false);
  const [editModalVisible, setEditModalVisible] = useState(false);
  const [detailDrawerVisible, setDetailDrawerVisible] = useState(false);
  const [selectedService, setSelectedService] = useState<Service | null>(null);
  const [editingService, setEditingService] = useState<Service | null>(null);
  const [searchTerm, setSearchTerm] = useState('');
  const [createForm] = Form.useForm();
  const [editForm] = Form.useForm();
  const [instances, setInstances] = useState<ServiceInstance[]>([]);
  const [editInstances, setEditInstances] = useState<ServiceInstance[]>([]);
  const { t } = useTranslation();

  useEffect(() => { loadServices(); }, [instanceId]);

  // Stable callback for closing drawer - prevents re-renders
  const handleCloseDrawer = useCallback(() => {
    setDetailDrawerVisible(false);
    setSelectedService(null);
  }, []);

  const loadServices = useCallback(async () => {
    try {
      setLoading(true);
      const params = instanceId ? { instanceId } : {};
      const res = await api.get('/api/services', { params });
      if (res.data.code === 200) setServices(res.data.data || []);
    } catch (e: any) {
      message.error(t('message.load_services_failed', { error: e.message }));
    } finally {
      setLoading(false);
    }
  }, [t, instanceId]);

  // Optimized instance update handlers
  const handleInstanceChange = useCallback((index: number, field: string, value: any) => {
    setInstances(prev => {
      const updated = [...prev];
      updated[index] = { ...updated[index], [field]: value };
      return updated;
    });
  }, []);

  const handleEditInstanceChange = useCallback((index: number, field: string, value: any) => {
    setEditInstances(prev => {
      const updated = [...prev];
      updated[index] = { ...updated[index], [field]: value };
      return updated;
    });
  }, []);

  const handleRemoveInstance = useCallback((index: number) => {
    setInstances(prev => prev.filter((_, i) => i !== index));
  }, []);

  const handleRemoveEditInstance = useCallback((index: number) => {
    setEditInstances(prev => prev.filter((_, i) => i !== index));
  }, []);

  // Memoize filtered services to avoid recalculation on every render
  const filteredServices = useMemo(() => 
    services.filter(s => !searchTerm || s.name.toLowerCase().includes(searchTerm.toLowerCase())),
    [services, searchTerm]
  );
  
  const totalServices = services.length;
  
  const healthyServices = useMemo(() => 
    services.filter(s => s.instances && s.instances.some(i => i.healthy !== false)).length,
    [services]
  );

  // Memoize status calculation
  const getStatus = useCallback((s: Service) => {
    const inst = s.instances || [];
    if (!inst.length) return { color: 'default', text: t('services.status_no_instances') };

    const enabledInstances = inst.filter(i => i.enabled !== false);
    const disabledCount = inst.filter(i => i.enabled === false).length;
    const healthyEnabled = enabledInstances.filter(i => i.healthy !== false).length;
    const unhealthyEnabled = enabledInstances.filter(i => i.healthy === false).length;

    if (disabledCount === inst.length) {
      return { color: 'default', text: t('services.status_all_disabled') };
    }

    if (enabledInstances.length > 0 && healthyEnabled === 0) {
      return { color: 'error', text: t('services.status_all_down') };
    }

    if (healthyEnabled === enabledInstances.length && disabledCount === 0) {
      return { color: 'success', text: t('services.status_healthy') };
    }

    return { color: 'warning', text: t('services.status_partial', { healthy: healthyEnabled, total: inst.length }) };
  }, [t]);

  const handleCreate = async (vals: any) => {
    for (const inst of instances) {
      if (!isValidIP(inst.ip)) { message.error(t('message.invalid_ip_format', { index: 1, ip: inst.ip })); return; }
      if (!isValidPort(inst.port)) { message.error(t('message.invalid_port', { index: 1, port: inst.port })); return; }
    }
    try {
      const params = instanceId ? { instanceId } : {};
      const res = await api.post('/api/services', { ...vals, instances, loadBalancer: vals.loadBalancer || 'weighted' }, { params });
      if (res.data.code === 200) {
        message.success(t('message.create_success'));
        createForm.resetFields(); setInstances([]); setCreateModalVisible(false); loadServices();
      } else { message.error(t('message.create_failed', { msg: res.data.message })); }
    } catch (e: any) { message.error(t('message.create_failed', { msg: e.response?.data?.message || e.message })); }
  };

  const handleUpdate = async (vals: any) => {
    if (!editingService) return;
    for (const inst of editInstances) {
      if (!isValidIP(inst.ip)) { message.error(t('message.invalid_ip_format', { index: 1, ip: inst.ip })); return; }
      if (!isValidPort(inst.port)) { message.error(t('message.invalid_port', { index: 1, port: inst.port })); return; }
    }
    try {
      const res = await api.put(`/api/services/${editingService.name}`, { ...vals, instances: editInstances, loadBalancer: vals.loadBalancer || 'weighted' });
      if (res.data.code === 200) {
        message.success(t('message.update_success'));
        editForm.resetFields(); setEditInstances([]); setEditModalVisible(false); loadServices();
        // Update selected service if it's the one being edited
        if (selectedService?.name === editingService.name) {
          setSelectedService({ ...editingService, ...vals, instances: editInstances });
        }
      } else { message.error(t('message.update_failed', { msg: res.data.message })); }
    } catch (e: any) { message.error(t('message.update_failed', { msg: e.response?.data?.message || e.message })); }
  };

  const handleDelete = useCallback(async (service: Service) => {
    try {
      const usage = await api.get(`/api/services/${service.name}/usage`);
      if (usage.data.data?.length > 0) {
        Modal.error({
          title: t('common.error'),
          content: <div><p>{t('message.service_in_use')}</p><ul style={{ marginTop: 8, paddingLeft: 20 }}>{usage.data.data.map((r: string) => <li key={r}>{r}</li>)}</ul></div>,
        });
        return;
      }
      const res = await api.delete(`/api/services/${service.name}`);
      if (res.data.code === 200) {
        message.success(t('message.delete_success'));
        loadServices();
        // Close drawer if deleted service was selected
        if (selectedService?.name === service.name) {
          setDetailDrawerVisible(false);
          setSelectedService(null);
        }
      }
    } catch (e: any) { message.error(t('message.delete_failed', { msg: e.response?.data?.message || e.message })); }
  }, [t, selectedService, loadServices]);

  // Stable callbacks for opening modals/drawers
  const openServiceDetail = useCallback((service: Service) => {
    setSelectedService(service);
    setDetailDrawerVisible(true);
  }, []);

  const openEditModal = useCallback((service: Service) => {
    // 先关闭 Drawer
    setDetailDrawerVisible(false);
    setSelectedService(null);
    // 直接打开编辑弹框
    setEditingService(service);
    setEditInstances(service.instances || []);
    editForm.setFieldsValue({
      name: service.name,
      loadBalancer: service.loadBalancer,
      description: service.description
    });
    setEditModalVisible(true);
  }, [editForm]);

  const handleDeleteClick = useCallback((service: Service) => {
    Modal.confirm({
      title: t('common.confirm'),
      content: t('message.confirm_delete_service', { name: service.name }),
      okText: t('common.delete'),
      okType: 'danger',
      cancelText: t('common.cancel'),
      onOk: () => handleDelete(service)
    });
  }, [t, handleDelete]);

  const getLoadBalancerLabel = useCallback((lb: string) => {
    switch (lb) {
      case 'round-robin': return t('services.round_robin');
      case 'random': return t('services.random');
      case 'weighted': return t('services.weighted');
      case 'consistent-hash': return t('services.consistent_hash');
      default: return lb;
    }
  }, [t]);

  return (
    <div className="services-page">
      {/* Premium Stats Cards with gradients */}
      <Row gutter={[16, 16]} style={{ marginBottom: 32 }}>
        <Col xs={24} sm={8}>
          <Card className="stats-card stats-card-primary" size="small">
            <div className="stats-card-content">
              <div className="stats-icon-wrapper">
                <ClusterOutlined className="stats-icon" />
              </div>
              <div className="stats-info">
                <Statistic
                  title={<span className="stats-title">{t('services.stats_services')}</span>}
                  value={totalServices}
                  valueStyle={{ fontFamily: "'JetBrains Mono', monospace" }}
                  prefix={<span className="stats-value-prefix"></span>}
                />
                <span className="stats-subtitle">Total Services</span>
              </div>
            </div>
          </Card>
        </Col>
        <Col xs={24} sm={8}>
          <Card className="stats-card stats-card-success" size="small">
            <div className="stats-card-content">
              <div className="stats-icon-wrapper">
                <CheckCircleOutlined className="stats-icon" />
              </div>
              <div className="stats-info">
                <Statistic
                  title={<span className="stats-title">{t('services.stats_healthy')}</span>}
                  value={healthyServices}
                  valueStyle={{ fontFamily: "'JetBrains Mono', monospace" }}
                />
                <span className="stats-subtitle">Healthy Services</span>
              </div>
            </div>
          </Card>
        </Col>
        <Col xs={24} sm={8}>
          <Card className="stats-card stats-card-danger" size="small">
            <div className="stats-card-content">
              <div className="stats-icon-wrapper">
                <CloseCircleOutlined className="stats-icon" />
              </div>
              <div className="stats-info">
                <Statistic
                  title={<span className="stats-title">{t('services.stats_unhealthy')}</span>}
                  value={totalServices - healthyServices}
                  valueStyle={{ fontFamily: "'JetBrains Mono', monospace" }}
                />
                <span className="stats-subtitle">Unhealthy Services</span>
              </div>
            </div>
          </Card>
        </Col>
      </Row>

      {/* Header */}
      <div className="page-header-modern">
        <div className="page-header-left">
          <Title level={3} className="page-title-main">{t('services.title')}</Title>
          <Text type="secondary">{t('services.description_helper')}</Text>
        </div>
        <div className="page-header-right">
          <Input
            placeholder={t('services.search_placeholder')}
            value={searchTerm}
            onChange={e => setSearchTerm(e.target.value)}
            allowClear
            className="search-input"
            suffix={
              <Button 
                type="text" 
                icon={<SearchOutlined />} 
                onClick={() => {}}
                style={{ color: '#94a3b8' }}
              />
            }
          />
          <Button type="primary" icon={<PlusOutlined />} onClick={() => { setInstances([{ ip: '', port: 8080, weight: 1, healthy: true, enabled: true }]); setCreateModalVisible(true); }} className="create-btn">{t('services.create')}</Button>
        </div>
      </div>

      {/* Services Grid */}
      <Spin spinning={loading}>
        {filteredServices.length === 0 ? (
          <Card className="empty-card">
            <Empty image={<ClusterOutlined className="empty-icon" />} description={<span className="empty-text">{t('services.empty_description')}</span>}>
              <Button type="primary" icon={<PlusOutlined />} onClick={() => setCreateModalVisible(true)}>{t('services.create_first')}</Button>
            </Empty>
          </Card>
        ) : (
          <div className="services-grid">
            {filteredServices.map((service) => (
              <ServiceCard
                key={service.name}
                service={service}
                onDetail={openServiceDetail}
                onEdit={openEditModal}
                onDelete={handleDeleteClick}
                getStatus={getStatus}
                getLoadBalancerLabel={getLoadBalancerLabel}
              />
            ))}
          </div>
        )}
      </Spin>

      {/* Service Detail Drawer */}
      <Drawer
        placement="right"
        width={480}
        open={detailDrawerVisible}
        closable={false}
        onClose={handleCloseDrawer}
        className="service-detail-drawer"
        mask={true}
        maskClosable={true}
        destroyOnClose
        style={{ zIndex: 999 }}
      >
        {selectedService && (
          <div className="drawer-content">
            {/* Drawer Header */}
            <div className="drawer-header">
              <div className="drawer-header-left">
                <div className="drawer-icon"><CloudServerOutlined /></div>
                <div className="drawer-title-wrapper">
                  <Title level={4} className="drawer-title">{selectedService.name}</Title>
                  <div className="drawer-id-row">
                    <Text type="secondary">{selectedService.serviceId || selectedService.name}</Text>
                    <Tooltip title={t('common.copy')}>
                      <Button
                        type="text"
                        size="small"
                        icon={<CopyOutlined />}
                        className="copy-btn"
                        onClick={() => {
                          navigator.clipboard.writeText(selectedService.serviceId || selectedService.name);
                          message.success(t('message.copy_success'));
                        }}
                      />
                    </Tooltip>
                  </div>
                </div>
              </div>
              <Button
                type="text"
                icon={<CloseOutlined />}
                onClick={handleCloseDrawer}
                className="drawer-close-btn"
              />
            </div>

            {/* Status Bar */}
            <div className="drawer-status-bar">
              <Badge status={getStatus(selectedService).color as any} text={getStatus(selectedService).text} />
              <Tag color="blue">{getLoadBalancerLabel(selectedService.loadBalancer || 'weighted')}</Tag>
            </div>

            {/* Description */}
            {selectedService.description && (
              <div className="drawer-section">
                <Text type="secondary">{selectedService.description}</Text>
              </div>
            )}

            {/* Instances */}
            <div className="drawer-section">
              <div className="section-title">
                <ApiOutlined /> {t('services.instances')}
                <span className="count">{selectedService.instances?.length || 0}</span>
              </div>
              {selectedService.instances && selectedService.instances.length > 0 ? (
                <div className="instances-detail-list">
                  {selectedService.instances.map((inst, idx) => (
                    <div key={idx} className={`instance-detail-card ${inst.healthy !== false && inst.enabled !== false ? 'healthy' : 'unhealthy'}`}>
                      <div className="instance-main-info">
                        <div className="instance-address">
                          <span className="instance-dot" />
                          <Text strong>{inst.ip}:{inst.port}</Text>
                        </div>
                        <div className="instance-badges">
                          {inst.enabled === false && <Tag color="red">{t('common.disabled')}</Tag>}
                          {inst.healthy === false && <Tag color="orange">{t('services.unhealthy')}</Tag>}
                          {inst.enabled !== false && inst.healthy !== false && <Tag color="green">{t('services.healthy')}</Tag>}
                        </div>
                      </div>
                      <div className="instance-meta">
                        <span>{t('services.weight')}: {inst.weight || 1}</span>
                      </div>
                    </div>
                  ))}
                </div>
              ) : (
                <Empty description={t('services.no_instances_configured')} />
              )}
            </div>

            {/* Actions */}
            <div className="drawer-actions">
              <Button type="primary" icon={<EditOutlined />} onClick={() => openEditModal(selectedService)}>
                {t('common.edit')}
              </Button>
              <Button danger icon={<DeleteOutlined />} onClick={() => {
                Modal.confirm({
                  title: t('common.confirm'),
                  content: t('message.confirm_delete_service', { name: selectedService.name }),
                  okText: t('common.delete'),
                  okType: 'danger',
                  cancelText: t('common.cancel'),
                  onOk: () => handleDelete(selectedService)
                });
              }}>
                {t('common.delete')}
              </Button>
            </div>
          </div>
        )}
      </Drawer>

      {/* Create Modal */}
      <Modal
        title={
          <div className="modal-header-modern">
            <div className="modal-title-wrapper">
              <div className="modal-icon-wrapper">
                <PlusOutlined />
              </div>
              <div className="modal-title-text">
                <div className="modal-title">{t('services.create')}</div>
                <div className="modal-subtitle">{t('services.create_description')}</div>
              </div>
            </div>
          </div>
        }
        open={createModalVisible}
        onCancel={() => { setCreateModalVisible(false); createForm.resetFields(); setInstances([]); }}
        footer={null}
        width={600}
        className="service-modal service-create-modal"
        destroyOnClose
        maskClosable={true}
        focusTriggerAfterClose
      >
        <Form form={createForm} layout="vertical" onFinish={handleCreate}>
          <div className="form-section-modern">
            <div className="section-header">
              <ClusterOutlined className="section-icon" />
              <span className="section-title">{t('services.basic_config')}</span>
            </div>
            <div className="section-content">
              <div className="form-row">
                <Form.Item
                  name="name"
                  label={t('services.name')}
                  rules={[{ required: true, message: t('services.name_required') }]}
                  className="form-item-half"
                >
                  <Input placeholder={t('services.name_placeholder')} size="large" />
                </Form.Item>
                <Form.Item
                  name="loadBalancer"
                  label={t('services.loadBalancer')}
                  initialValue="weighted"
                  className="form-item-half"
                >
                  <Select size="large" className="select-with-icon" dropdownClassName="loadBalancer-dropdown" optionLabelProp="label" listHeight={280}>
                    <Select.Option value="weighted" label={<span className="custom-select-label"><TrophyOutlined className="option-icon" /><span className="option-title">{t('services.weighted')}</span></span>}>
                      <span className="option-wrapper">
                        <TrophyOutlined className="option-icon option-icon-weighted" />
                        <span className="option-text">
                          <span className="option-title">{t('services.weighted')}</span>
                          <span className="option-desc">平滑加权轮询，推荐</span>
                        </span>
                      </span>
                    </Select.Option>
                    <Select.Option value="round-robin" label={<span className="custom-select-label"><ReloadOutlined className="option-icon" /><span className="option-title">{t('services.round_robin')}</span></span>}>
                      <span className="option-wrapper">
                        <SwapOutlined className="option-icon option-icon-roundrobin" />
                        <span className="option-text">
                          <span className="option-title">{t('services.round_robin')}</span>
                          <span className="option-desc">简单轮询分发</span>
                        </span>
                      </span>
                    </Select.Option>
                    <Select.Option value="random" label={<span className="custom-select-label"><ThunderboltOutlined className="option-icon" /><span className="option-title">{t('services.random')}</span></span>}>
                      <span className="option-wrapper">
                        <AimOutlined className="option-icon option-icon-random" />
                        <span className="option-text">
                          <span className="option-title">{t('services.random')}</span>
                          <span className="option-desc">随机选择</span>
                        </span>
                      </span>
                    </Select.Option>
                    <Select.Option value="consistent-hash" label={<span className="custom-select-label"><LinkOutlined className="option-icon" /><span className="option-title">{t('services.consistent_hash')}</span></span>}>
                      <span className="option-wrapper">
                        <ForkOutlined className="option-icon option-icon-hash" />
                        <span className="option-text">
                          <span className="option-title">{t('services.consistent_hash')}</span>
                          <span className="option-desc">基于哈希值，适合会话保持</span>
                        </span>
                      </span>
                    </Select.Option>
                  </Select>
                </Form.Item>
              </div>
              <Form.Item
                name="description"
                label={t('services.description')}
              >
                <Input.TextArea
                  rows={2}
                  placeholder={t('services.description_placeholder')}
                  showCount
                  maxLength={500}
                />
              </Form.Item>
            </div>
          </div>

          <div className="form-section-modern">
            <div className="section-header">
              <ApiOutlined className="section-icon" />
              <span className="section-title">{t('services.service_instances')}</span>
              <span className="section-subtitle">{t('services.instances_helper')}</span>
            </div>
            <div className="section-content">
              {(instances.length === 0 || instances.every(inst => inst.enabled === false)) && (
                <Alert
                  type="warning"
                  showIcon
                  icon={<ExclamationCircleOutlined />}
                  message={instances.length === 0 ? t('services.modal_no_instances_warning') : t('services.modal_all_disabled_warning')}
                  className="modal-instances-warning"
                />
              )}
              <div className="instances-container">
                {instances.map((inst, i) => (
                  <InstanceRow
                    key={i}
                    instance={inst}
                    index={i}
                    onChange={handleInstanceChange}
                    onRemove={handleRemoveInstance}
                    canRemove={instances.length > 1}
                    t={t}
                  />
                ))}
                <Button type="dashed" onClick={() => setInstances([...instances, { ip: '', port: 8080, weight: 1, healthy: true, enabled: true }])} icon={<PlusOutlined />} block className="add-instance-btn-modern" size="large">
                  {t('services.add_instance')}
                </Button>
              </div>
            </div>
          </div>

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
                <div className="modal-title">{t('services.edit')}</div>
                <div className="modal-subtitle">{t('services.edit_description')}</div>
              </div>
            </div>
          </div>
        }
        open={editModalVisible}
        onCancel={() => {
          setEditModalVisible(false);
          editForm.resetFields();
          setEditInstances([]);
        }}
        footer={null}
        width={600}
        className="service-modal service-edit-modal"
        destroyOnClose
        maskClosable={true}
        focusTriggerAfterClose
      >
        <Form form={editForm} layout="vertical" onFinish={handleUpdate}>
          <div className="form-section-modern">
            <div className="section-header">
              <ClusterOutlined className="section-icon" />
              <span className="section-title">{t('services.basic_config')}</span>
            </div>
            <div className="section-content">
              <div className="form-row">
                <Form.Item name="name" label={t('services.name')} className="form-item-half">
                  <Input disabled size="large" />
                </Form.Item>
                <Form.Item name="loadBalancer" label={t('services.loadBalancer')} className="form-item-half">
                  <Select size="large" className="select-with-icon" dropdownClassName="loadBalancer-dropdown" optionLabelProp="label" listHeight={280}>
                    <Select.Option value="weighted" label={<span className="custom-select-label"><TrophyOutlined className="option-icon" /><span className="option-title">{t('services.weighted')}</span></span>}>
                      <span className="option-wrapper">
                        <TrophyOutlined className="option-icon option-icon-weighted" />
                        <span className="option-text">
                          <span className="option-title">{t('services.weighted')}</span>
                          <span className="option-desc">平滑加权轮询，推荐</span>
                        </span>
                      </span>
                    </Select.Option>
                    <Select.Option value="round-robin" label={<span className="custom-select-label"><ReloadOutlined className="option-icon" /><span className="option-title">{t('services.round_robin')}</span></span>}>
                      <span className="option-wrapper">
                        <SwapOutlined className="option-icon option-icon-roundrobin" />
                        <span className="option-text">
                          <span className="option-title">{t('services.round_robin')}</span>
                          <span className="option-desc">简单轮询分发</span>
                        </span>
                      </span>
                    </Select.Option>
                    <Select.Option value="random" label={<span className="custom-select-label"><ThunderboltOutlined className="option-icon" /><span className="option-title">{t('services.random')}</span></span>}>
                      <span className="option-wrapper">
                        <AimOutlined className="option-icon option-icon-random" />
                        <span className="option-text">
                          <span className="option-title">{t('services.random')}</span>
                          <span className="option-desc">随机选择</span>
                        </span>
                      </span>
                    </Select.Option>
                    <Select.Option value="consistent-hash" label={<span className="custom-select-label"><LinkOutlined className="option-icon" /><span className="option-title">{t('services.consistent_hash')}</span></span>}>
                      <span className="option-wrapper">
                        <ForkOutlined className="option-icon option-icon-hash" />
                        <span className="option-text">
                          <span className="option-title">{t('services.consistent_hash')}</span>
                          <span className="option-desc">基于哈希值，适合会话保持</span>
                        </span>
                      </span>
                    </Select.Option>
                  </Select>
                </Form.Item>
              </div>
              <Form.Item name="description" label={t('services.description')}>
                <Input.TextArea rows={2} placeholder={t('services.description_placeholder')} showCount maxLength={500} />
              </Form.Item>
            </div>
          </div>

          <div className="form-section-modern">
            <div className="section-header">
              <ApiOutlined className="section-icon" />
              <span className="section-title">{t('services.service_instances')}</span>
              <span className="section-subtitle">{t('services.instances_helper')}</span>
            </div>
            <div className="section-content">
              {(editInstances.length === 0 || editInstances.every(inst => inst.enabled === false)) && (
                <Alert type="warning" showIcon icon={<ExclamationCircleOutlined />} message={editInstances.length === 0 ? t('services.modal_no_instances_warning') : t('services.modal_all_disabled_warning')} className="modal-instances-warning" />
              )}
              <div className="instances-container">
                {editInstances.map((inst, i) => (
                  <InstanceRow
                    key={i}
                    instance={inst}
                    index={i}
                    onChange={handleEditInstanceChange}
                    onRemove={handleRemoveEditInstance}
                    canRemove={editInstances.length > 1}
                    t={t}
                  />
                ))}
                <Button type="dashed" onClick={() => setEditInstances([...editInstances, { ip: '', port: 8080, weight: 1, healthy: true, enabled: true }])} icon={<PlusOutlined />} block className="add-instance-btn-modern" size="large">
                  {t('services.add_instance')}
                </Button>
              </div>
            </div>
          </div>

          <div className="modal-footer-modern">
            <Button onClick={() => setEditModalVisible(false)} size="large">{t('common.cancel')}</Button>
            <Button type="primary" htmlType="submit" size="large" icon={<EditOutlined />}>{t('common.update')}</Button>
          </div>
        </Form>
      </Modal>
    </div>
  );
};

export default ServicesPage;