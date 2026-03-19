import { useState, useEffect } from 'react';
import {
  Card, Button, Space, Modal, message, Spin, Tag, Form, Input, Select,
  Empty, Dropdown, Tooltip, Badge, Divider, Typography, Alert
} from 'antd';
import {
  PlusOutlined, DeleteOutlined, EditOutlined, MoreOutlined,
  ClusterOutlined, MinusCircleOutlined, CloudServerOutlined, ApiOutlined,
  WarningOutlined, ExclamationCircleOutlined
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

const ServicesPage: React.FC = () => {
  const [services, setServices] = useState<Service[]>([]);
  const [loading, setLoading] = useState(false);
  const [createModalVisible, setCreateModalVisible] = useState(false);
  const [editModalVisible, setEditModalVisible] = useState(false);
  const [editingService, setEditingService] = useState<Service | null>(null);
  const [searchTerm, setSearchTerm] = useState('');
  const [createForm] = Form.useForm();
  const [editForm] = Form.useForm();
  const [instances, setInstances] = useState<ServiceInstance[]>([]);
  const [editInstances, setEditInstances] = useState<ServiceInstance[]>([]);
  const { t } = useTranslation();

  useEffect(() => { loadServices(); }, []);

  const loadServices = async () => {
    try {
      setLoading(true);
      const res = await api.get('/api/services');
      if (res.data.code === 200) setServices(res.data.data || []);
    } catch (e: any) {
      message.error(t('message.load_services_failed', { error: e.message }));
    } finally {
      setLoading(false);
    }
  };

  const filteredServices = services.filter(s => !searchTerm || s.name.toLowerCase().includes(searchTerm.toLowerCase()));
  const totalServices = services.length;
  const healthyServices = services.filter(s => s.instances && s.instances.some(i => i.healthy !== false)).length;

  const handleCreate = async (vals: any) => {
    for (const inst of instances) {
      if (!isValidIP(inst.ip)) { message.error(t('message.invalid_ip_format', { index: 1, ip: inst.ip })); return; }
      if (!isValidPort(inst.port)) { message.error(t('message.invalid_port', { index: 1, port: inst.port })); return; }
    }
    try {
      const res = await api.post('/api/services', { ...vals, instances, loadBalancer: vals.loadBalancer || 'weighted' });
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
      } else { message.error(t('message.update_failed', { msg: res.data.message })); }
    } catch (e: any) { message.error(t('message.update_failed', { msg: e.response?.data?.message || e.message })); }
  };

  const handleDelete = async (service: Service) => {
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
      if (res.data.code === 200) { message.success(t('message.delete_success')); loadServices(); }
    } catch (e: any) { message.error(t('message.delete_failed', { msg: e.response?.data?.message || e.message })); }
  };

  const openEditModal = (service: Service) => {
    setEditingService(service);
    setEditInstances(service.instances || []);
    editForm.setFieldsValue({ name: service.name, loadBalancer: service.loadBalancer, description: service.description });
    setEditModalVisible(true);
  };

  const getStatus = (s: Service) => {
    const inst = s.instances || [];
    if (!inst.length) return { color: 'default', text: t('services.status_no_instances') };
    const healthy = inst.filter(i => i.healthy !== false && i.enabled !== false).length;
    if (healthy === inst.length) return { color: 'success', text: t('services.status_healthy') };
    if (!healthy) return { color: 'error', text: t('services.status_all_down') };
    return { color: 'warning', text: t('services.status_partial', { healthy, total: inst.length }) };
  };

  const getActionMenu = (service: Service): MenuProps['items'] => [
    { key: 'edit', icon: <EditOutlined />, label: t('common.edit'), onClick: () => openEditModal(service) },
    { type: 'divider' },
    { key: 'delete', icon: <DeleteOutlined />, label: t('common.delete'), danger: true, onClick: () => {
      Modal.confirm({ title: t('common.confirm'), content: t('message.confirm_delete_service', { name: service.name }), okText: t('common.delete'), okType: 'danger', cancelText: t('common.cancel'), onOk: () => handleDelete(service) });
    }},
  ];

  return (
    <div className="services-page">
      {/* Stats Bar */}
      <div className="stats-bar">
        <div className="stat-item">
          <div className="stat-value">{totalServices}</div>
          <div className="stat-label">{t('services.stats_services')}</div>
        </div>
        <Divider type="vertical" className="stat-divider" />
        <div className="stat-item">
          <div className="stat-value text-green-600">{healthyServices}</div>
          <div className="stat-label">{t('services.stats_healthy')}</div>
        </div>
      </div>

      {/* Header */}
      <div className="page-header-modern">
        <div className="page-header-left">
          <Title level={3} className="page-title-main">{t('services.title')}</Title>
          <Text type="secondary">{t('services.description_helper')}</Text>
        </div>
        <div className="page-header-right">
          <Input.Search placeholder={t('services.search_placeholder')} value={searchTerm} onChange={e => setSearchTerm(e.target.value)} allowClear className="search-input" />
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
            {filteredServices.map((service) => {
              const status = getStatus(service);
              const instanceCount = service.instances?.length || 0;
              // Check if there are any available instances
              const availableInstances = service.instances?.filter(inst => inst.enabled !== false) || [];
              const hasNoAvailableInstances = instanceCount === 0 || availableInstances.length === 0;

              return (
                <Card key={service.name} className={`service-card ${hasNoAvailableInstances ? 'service-card-warning' : ''}`} hoverable>
                  {/* Warning Alert */}
                  {hasNoAvailableInstances && (
                    <Alert
                      type="warning"
                      showIcon
                      icon={<ExclamationCircleOutlined />}
                      message={instanceCount === 0 ? t('services.no_instances_warning') : t('services.all_instances_disabled_warning')}
                      className="service-warning-alert"
                    />
                  )}
                  <div className="service-card-header">
                    <div className="service-info">
                      <div className="service-icon"><CloudServerOutlined /></div>
                      <div className="service-details">
                        <Text strong className="service-name">{service.name}</Text>
                        <Text type="secondary" className="service-id">{service.serviceId || service.name}</Text>
                      </div>
                    </div>
                    <Dropdown menu={{ items: getActionMenu(service) }} trigger={['click']} placement="bottomRight">
                      <Button type="text" icon={<MoreOutlined />} className="action-btn" />
                    </Dropdown>
                  </div>
                  <div className="service-meta">
                    <Badge status={status.color as any} text={status.text} />
                    <Tag className="lb-tag">{service.loadBalancer || 'weighted'}</Tag>
                  </div>
                  <div className="instances-section">
                    <div className="instances-header">
                      <Text type="secondary" className="instances-title">
                        <ApiOutlined /> {availableInstances.length}/{instanceCount} {t('services.available_instances')}
                      </Text>
                      <Button type="link" size="small" onClick={() => openEditModal(service)}><EditOutlined /> {t('common.edit')}</Button>
                    </div>
                    {instanceCount > 0 && (
                      <div className="instances-list">
                        {service.instances!.slice(0, 3).map((inst, idx) => (
                          <div key={idx} className={`instance-chip ${inst.healthy !== false && inst.enabled !== false ? 'healthy' : 'unhealthy'}`}>
                            <span className="instance-dot" />
                            <span className="instance-addr">{inst.ip}:{inst.port}</span>
                            {inst.enabled === false && <Tag color="red" className="disabled-tag">{t('common.disabled')}</Tag>}
                            {service.instances!.length > 1 && <span className="instance-weight">w:{inst.weight || 1}</span>}
                          </div>
                        ))}
                        {instanceCount > 3 && <div className="instance-chip more">+{instanceCount - 3} more</div>}
                      </div>
                    )}
                    {instanceCount === 0 && <Text type="secondary" className="no-instances">{t('services.no_instances_configured')}</Text>}
                  </div>
                </Card>
              );
            })}
          </div>
        )}
      </Spin>

      {/* Create Modal */}
      <Modal title={<div className="modal-header"><PlusOutlined className="modal-icon" /><span>{t('services.create')}</span></div>} open={createModalVisible} onCancel={() => { setCreateModalVisible(false); createForm.resetFields(); setInstances([]); }} footer={null} width={640} className="service-modal">
        <Form form={createForm} layout="vertical" onFinish={handleCreate}>
          <div className="form-row">
            <Form.Item name="name" label={t('services.name')} rules={[{ required: true }]} className="form-item-half"><Input placeholder={t('services.name_placeholder')} /></Form.Item>
            <Form.Item name="loadBalancer" label={t('services.loadBalancer')} initialValue="weighted" className="form-item-half">
              <Select>
                <Select.Option value="round-robin">{t('services.round_robin')}</Select.Option>
                <Select.Option value="random">{t('services.random')}</Select.Option>
                <Select.Option value="weighted">{t('services.weighted')}</Select.Option>
              </Select>
            </Form.Item>
          </div>
          <Form.Item name="description" label={t('services.description')}><Input.TextArea rows={2} placeholder={t('services.description_placeholder')} /></Form.Item>
          <Form.Item label={t('services.service_instances')} className="instances-form-item">
            {/* Warning for no available instances in create modal */}
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
                <div key={i} className="instance-row">
                  <Input placeholder="IP" value={inst.ip} onChange={e => { const d = [...instances]; d[i] = { ...d[i], ip: e.target.value }; setInstances(d); }} className="instance-input-ip" />
                  <Input type="number" placeholder="Port" value={inst.port} onChange={e => { const d = [...instances]; d[i] = { ...d[i], port: parseInt(e.target.value) || 8080 }; setInstances(d); }} className="instance-input-port" />
                  <Input type="number" placeholder="Weight" value={inst.weight} onChange={e => { const d = [...instances]; d[i] = { ...d[i], weight: parseInt(e.target.value) || 1 }; setInstances(d); }} className="instance-input-weight" />
                  <Select value={inst.enabled !== false ? 'enabled' : 'disabled'} onChange={v => { const d = [...instances]; d[i] = { ...d[i], enabled: v === 'enabled' }; setInstances(d); }} className="instance-input-status">
                    <Select.Option value="enabled">{t('common.enabled')}</Select.Option>
                    <Select.Option value="disabled">{t('common.disabled')}</Select.Option>
                  </Select>
                  <Button type="text" danger icon={<MinusCircleOutlined />} onClick={() => setInstances(instances.filter((_, idx) => idx !== i))} />
                </div>
              ))}
              <Button type="dashed" onClick={() => setInstances([...instances, { ip: '', port: 8080, weight: 1, healthy: true, enabled: true }])} icon={<PlusOutlined />} block className="add-instance-btn">{t('services.add_instance')}</Button>
            </div>
          </Form.Item>
          <div className="modal-footer">
            <Button onClick={() => setCreateModalVisible(false)}>{t('common.cancel')}</Button>
            <Button type="primary" htmlType="submit">{t('common.create')}</Button>
          </div>
        </Form>
      </Modal>

      {/* Edit Modal */}
      <Modal title={<div className="modal-header"><EditOutlined className="modal-icon" /><span>{t('services.edit')}</span></div>} open={editModalVisible} onCancel={() => { setEditModalVisible(false); editForm.resetFields(); setEditInstances([]); }} footer={null} width={640} className="service-modal">
        <Form form={editForm} layout="vertical" onFinish={handleUpdate}>
          <div className="form-row">
            <Form.Item name="name" label={t('services.name')} className="form-item-half"><Input disabled /></Form.Item>
            <Form.Item name="loadBalancer" label={t('services.loadBalancer')} className="form-item-half">
              <Select>
                <Select.Option value="round-robin">{t('services.round_robin')}</Select.Option>
                <Select.Option value="random">{t('services.random')}</Select.Option>
                <Select.Option value="weighted">{t('services.weighted')}</Select.Option>
              </Select>
            </Form.Item>
          </div>
          <Form.Item name="description" label={t('services.description')}><Input.TextArea rows={2} placeholder={t('services.description_placeholder')} /></Form.Item>
          <Form.Item label={t('services.service_instances')} className="instances-form-item">
            {/* Warning for no available instances in edit modal */}
            {(editInstances.length === 0 || editInstances.every(inst => inst.enabled === false)) && (
              <Alert
                type="warning"
                showIcon
                icon={<ExclamationCircleOutlined />}
                message={editInstances.length === 0 ? t('services.modal_no_instances_warning') : t('services.modal_all_disabled_warning')}
                className="modal-instances-warning"
              />
            )}
            <div className="instances-container">
              {editInstances.map((inst, i) => (
                <div key={i} className="instance-row">
                  <Input placeholder="IP" value={inst.ip} onChange={e => { const d = [...editInstances]; d[i] = { ...d[i], ip: e.target.value }; setEditInstances(d); }} className="instance-input-ip" />
                  <Input type="number" placeholder="Port" value={inst.port} onChange={e => { const d = [...editInstances]; d[i] = { ...d[i], port: parseInt(e.target.value) || 8080 }; setEditInstances(d); }} className="instance-input-port" />
                  <Input type="number" placeholder="Weight" value={inst.weight} onChange={e => { const d = [...editInstances]; d[i] = { ...d[i], weight: parseInt(e.target.value) || 1 }; setEditInstances(d); }} className="instance-input-weight" />
                  <Select value={inst.enabled !== false ? 'enabled' : 'disabled'} onChange={v => { const d = [...editInstances]; d[i] = { ...d[i], enabled: v === 'enabled' }; setEditInstances(d); }} className="instance-input-status">
                    <Select.Option value="enabled">{t('common.enabled')}</Select.Option>
                    <Select.Option value="disabled">{t('common.disabled')}</Select.Option>
                  </Select>
                  <Button type="text" danger icon={<MinusCircleOutlined />} onClick={() => setEditInstances(editInstances.filter((_, idx) => idx !== i))} />
                </div>
              ))}
              <Button type="dashed" onClick={() => setEditInstances([...editInstances, { ip: '', port: 8080, weight: 1, healthy: true, enabled: true }])} icon={<PlusOutlined />} block className="add-instance-btn">{t('services.add_instance')}</Button>
            </div>
          </Form.Item>
          <div className="modal-footer">
            <Button onClick={() => setEditModalVisible(false)}>{t('common.cancel')}</Button>
            <Button type="primary" htmlType="submit">{t('common.update')}</Button>
          </div>
        </Form>
      </Modal>
    </div>
  );
};

export default ServicesPage;