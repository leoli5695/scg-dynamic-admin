/**
 * Services page (rewritten as a feature).
 * Fields aligned with ServiceController + model/ServiceDefinition.java.
 */
import React, { useEffect, useMemo, useState } from 'react';
import {
  Button, Card, Divider, Empty, Input, Spin, Tabs, Typography  } from 'antd';
import { message, modal } from '../../utils/antdStatic';
import { ClusterOutlined, PlusOutlined, RobotOutlined, SearchOutlined } from '@ant-design/icons';
import { useParams, useSearchParams } from 'react-router-dom';
import { useTranslation } from 'react-i18next';
import { useRequest } from '../../hooks/useRequest';
import { ApiError } from '../../api/http';
import {
  checkServiceUsage, createService, deleteService, listServices, updateService,
} from '../../api/services';
import type { ServiceCategory, ServiceDefinition, ServiceInstanceItem } from '../../types/service';
import ServiceCard from './components/ServiceCard';
import ServiceDetailDrawer from './components/ServiceDetailDrawer';
import ServiceFormModal from './components/ServiceFormModal';
import './services-premium.css';

const { Text, Title } = Typography;

interface ServiceFormValues {
  name: string;
  loadBalancer?: string;
  description?: string;
  category?: ServiceCategory;
  providerType?: string;
  baseUrl?: string;
  apiKey?: string;
}

type HealthFilter = 'all' | 'healthy' | 'unhealthy';

const errorMessage = (error: unknown, fallbackKey: string, t: (k: string, o?: Record<string, unknown>) => string): string => (
  error instanceof ApiError && error.message ? error.message : t(fallbackKey)
);

const ServicesPage: React.FC = () => {
  const { t } = useTranslation();
  const { instanceId } = useParams<{ instanceId: string }>();
  const [searchParams, setSearchParams] = useSearchParams();

  const [searchTerm, setSearchTerm] = useState('');
  const [healthFilter, setHealthFilter] = useState<HealthFilter>('all');
  // Category tab: ALL / NORMAL / AI (a service's category comes from the backend).
  const [categoryTab, setCategoryTab] = useState<'ALL' | 'NORMAL' | 'AI'>('ALL');
  const [selectedService, setSelectedService] = useState<ServiceDefinition | null>(null);
  const [editingService, setEditingService] = useState<ServiceDefinition | null>(null);
  const [createOpen, setCreateOpen] = useState(false);

  const servicesRequest = useRequest(
    (signal) => listServices(instanceId ? { instanceId } : undefined, { signal }),
    { deps: [instanceId] },
  );
  const services = useMemo(() => servicesRequest.data ?? [], [servicesRequest.data]);

  // Deep-link support: topology jumps here with ?highlight=<serviceId|name>
  useEffect(() => {
    const highlightId = searchParams.get('highlight');
    if (highlightId && services.length > 0) {
      const target = services.find((s) => s.serviceId === highlightId || s.name === highlightId);
      if (target) {
        // eslint-disable-next-line react-hooks/set-state-in-effect
        setSelectedService(target);
        // Clear the params so a refresh does not reopen the drawer.
        const next = new URLSearchParams(searchParams);
        next.delete('highlight');
        next.delete('from');
        setSearchParams(next, { replace: true });
      }
    }
  }, [services, searchParams, setSearchParams]);

  const isAiService = (s: ServiceDefinition): boolean => s.category === 'AI';

  const filteredServices = useMemo(() => services.filter((s) => {
    const matchesSearch = !searchTerm || s.name.toLowerCase().includes(searchTerm.toLowerCase());
    const matchesCategory = categoryTab === 'ALL'
      || (categoryTab === 'AI' ? isAiService(s) : !isAiService(s));
    // Health is an instance-level concept that only applies to normal services.
    const isHealthy = s.instances && s.instances.some((i) => i.healthy !== false);
    const matchesHealth = isAiService(s) || healthFilter === 'all'
      || (healthFilter === 'healthy' && isHealthy)
      || (healthFilter === 'unhealthy' && !isHealthy);
    return matchesSearch && matchesCategory && matchesHealth;
  }), [services, searchTerm, healthFilter, categoryTab]);

  const categoryCounts = useMemo(() => {
    const ai = services.filter(isAiService).length;
    return { all: services.length, ai, normal: services.length - ai };
  }, [services]);

  // Health stats are meaningful for normal (instance-backed) services only; the total
  // counts every service so it agrees with the "全部服务" tab.
  const normalServices = useMemo(() => services.filter((s) => !isAiService(s)), [services]);
  const totalServices = services.length;
  const healthyServices = useMemo(
    () => normalServices.filter((s) => s.instances && s.instances.some((i) => i.healthy !== false)).length,
    [normalServices],
  );

  const handleCreate = async (values: ServiceFormValues, instances: ServiceInstanceItem[]): Promise<boolean> => {
    try {
      const isAi = values.category === 'AI';
      await createService({
        name: values.name,
        description: values.description,
        loadBalancer: values.loadBalancer || 'weighted',
        instances: isAi ? [] : instances,
        category: values.category || 'NORMAL',
        ...(isAi ? {
          providerType: values.providerType,
          baseUrl: values.baseUrl,
          apiKey: values.apiKey,
        } : {}),
      }, instanceId);
      message.success(t('message.create_success'));
      servicesRequest.run();
      return true;
    } catch (error) {
      message.error(errorMessage(error, 'message.create_failed', t));
      return false;
    }
  };

  const handleUpdate = async (values: ServiceFormValues, instances: ServiceInstanceItem[]): Promise<boolean> => {
    if (!editingService) return false;
    try {
      const isAi = (values.category || editingService.category) === 'AI';
      await updateService(editingService.name, {
        name: editingService.name,
        description: values.description,
        loadBalancer: values.loadBalancer || 'weighted',
        instances: isAi ? [] : instances,
        category: values.category || editingService.category || 'NORMAL',
        ...(isAi ? {
          providerType: values.providerType ?? editingService.providerType,
          baseUrl: values.baseUrl ?? editingService.baseUrl,
          // apiKey is write-only: only sent when the operator rotated it.
          ...(values.apiKey ? { apiKey: values.apiKey } : {}),
          credentialRef: editingService.credentialRef,
        } : {}),
      }, instanceId);
      message.success(t('message.update_success'));
      servicesRequest.run();
      // Keep the drawer in sync if the edited service is shown there
      if (selectedService?.name === editingService.name) {
        setSelectedService({
          ...selectedService,
          ...values,
          name: editingService.name,
          instances,
        });
      }
      setEditingService(null);
      return true;
    } catch (error) {
      message.error(errorMessage(error, 'message.update_failed', t));
      return false;
    }
  };

  const handleDelete = async (service: ServiceDefinition) => {
    try {
      const usage = await checkServiceUsage(service.name);
      if ((usage ?? []).length > 0) {
        modal.error({
          title: t('common.error'),
          content: (
            <div>
              <p>{t('message.service_in_use')}</p>
              <ul style={{ marginTop: 8, paddingLeft: 20 }}>
                {usage.map((r) => <li key={r}>{r}</li>)}
              </ul>
            </div>
          ),
        });
        return;
      }
      await deleteService(service.name, instanceId);
      message.success(t('message.delete_success'));
      servicesRequest.run();
      if (selectedService?.name === service.name) {
        setSelectedService(null);
      }
    } catch (error) {
      message.error(errorMessage(error, 'message.delete_failed', t));
    }
  };

  const openEditModal = (service: ServiceDefinition) => {
    // Close the drawer first, then open the edit modal
    setSelectedService(null);
    setEditingService(service);
  };

  const statsItems: Array<{ filter: HealthFilter; value: number; labelKey: string; className?: string }> = [
    { filter: 'all', value: totalServices, labelKey: 'services.stats_services' },
    { filter: 'healthy', value: healthyServices, labelKey: 'services.stats_healthy', className: 'text-green-600' },
    { filter: 'unhealthy', value: normalServices.length - healthyServices, labelKey: 'services.stats_unhealthy', className: 'text-red-600' },
  ];

  return (
    <div className="services-page">
      {/* Stats bar (also acts as health filter) */}
      <div className="stats-bar">
        {statsItems.map((item, idx) => (
          <React.Fragment key={item.filter}>
            {idx > 0 && <Divider type="vertical" className="stat-divider" />}
            <div
              className={`stat-item ${healthFilter === item.filter ? 'stat-item-active' : ''}`}
              onClick={() => setHealthFilter(item.filter)}
              style={{ cursor: 'pointer' }}
            >
              <div className={`stat-value ${item.className ?? ''}`}>{item.value}</div>
              <div className="stat-label">{t(item.labelKey)}</div>
            </div>
          </React.Fragment>
        ))}
      </div>

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
            onChange={(e) => setSearchTerm(e.target.value)}
            allowClear
            className="search-input"
            suffix={(
              <Button
                type="text"
                icon={<SearchOutlined />}
                onClick={() => {}}
                style={{ color: '#94a3b8' }}
              />
            )}
          />
          <Button
            type="primary"
            icon={<PlusOutlined />}
            onClick={() => setCreateOpen(true)}
            className="create-btn"
          >
            {t('services.create')}
          </Button>
        </div>
      </div>

      {/* Category tabs: normal vs AI services */}
      <Tabs
        activeKey={categoryTab}
        onChange={(k) => setCategoryTab(k as 'ALL' | 'NORMAL' | 'AI')}
        className="service-category-tabs"
        items={[
          { key: 'ALL', label: `${t('services.tab_all')} (${categoryCounts.all})` },
          {
            key: 'NORMAL',
            label: (<span><ClusterOutlined /> {t('services.tab_normal')} ({categoryCounts.normal})</span>),
          },
          {
            key: 'AI',
            label: (<span><RobotOutlined /> {t('services.tab_ai')} ({categoryCounts.ai})</span>),
          },
        ]}
      />

      {/* Services grid */}
      <Spin spinning={servicesRequest.loading}>
        {filteredServices.length === 0 ? (
          <Card className="empty-card">
            <Empty
              image={<ClusterOutlined className="empty-icon" />}
              description={<span className="empty-text">{t('services.empty_description')}</span>}
            >
              <Button type="primary" icon={<PlusOutlined />} onClick={() => setCreateOpen(true)}>
                {t('services.create_first')}
              </Button>
            </Empty>
          </Card>
        ) : (
          <div className="services-grid">
            {filteredServices.map((service) => (
              <ServiceCard
                key={service.name}
                service={service}
                onDetail={setSelectedService}
                onEdit={openEditModal}
                onDelete={handleDelete}
              />
            ))}
          </div>
        )}
      </Spin>

      {/* Detail drawer */}
      {selectedService && (
        <ServiceDetailDrawer
          service={selectedService}
          onClose={() => setSelectedService(null)}
          onEdit={openEditModal}
          onDelete={handleDelete}
        />
      )}

      {/* Create modal */}
      {createOpen && (
        <ServiceFormModal
          mode="create"
          onCancel={() => setCreateOpen(false)}
          onSubmit={handleCreate}
        />
      )}

      {/* Edit modal */}
      {editingService && (
        <ServiceFormModal
          mode="edit"
          service={editingService}
          onCancel={() => setEditingService(null)}
          onSubmit={handleUpdate}
        />
      )}
    </div>
  );
};

export default ServicesPage;
