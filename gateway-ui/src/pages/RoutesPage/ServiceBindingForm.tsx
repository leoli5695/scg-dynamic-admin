import React from 'react';
import { Form, Select, Input, Switch, Button, Tag, InputNumber, Divider } from 'antd';
import type { FormInstance } from 'antd';
import { CloudOutlined, GlobalOutlined, PlusOutlined, DeleteOutlined } from '@ant-design/icons';
import { useTranslation } from 'react-i18next';
import type { RoutingMode, Service, NacosService, ServiceBindingType, RouteServiceBinding } from './types';

interface ServiceBindingFormProps {
  form: FormInstance;
  routingMode: RoutingMode;
  onRoutingModeChange: (mode: RoutingMode) => void;
  services: Service[];
  nacosServices: NacosService[];
  servicesRef: React.MutableRefObject<Service[]>;
  /** For multi-service mode in edit form: externally managed services list */
  configuredServices?: RouteServiceBinding[];
  /** Form field path prefix for watching services (e.g. 'services' for create form) */
  servicesWatchName?: string;
}

/**
 * Service binding configuration form - shared between create and edit modals
 * Handles both SINGLE and MULTI routing modes
 */
const ServiceBindingForm: React.FC<ServiceBindingFormProps> = ({
  form,
  routingMode,
  onRoutingModeChange,
  services,
  nacosServices,
  servicesRef,
  configuredServices,
  servicesWatchName,
}) => {
  const { t } = useTranslation();

  // Watch services list from form for gray rules dropdown
  const formServices = servicesWatchName ? Form.useWatch(servicesWatchName, form) : configuredServices || [];

  const handleServiceIdChange = (value: string) => {
    form.setFieldValue('uri', value || '');
    if (value && value.startsWith('lb://')) {
      const serviceName = value.replace('lb://', '');
      const nacosService = nacosServices.find(s => s.serviceName === serviceName);
      if (nacosService) {
        form.setFieldValue('serviceNamespace', nacosService.namespace || '');
        form.setFieldValue('serviceGroup', nacosService.group || 'DEFAULT_GROUP');
      }
    } else {
      form.setFieldValue('serviceNamespace', undefined);
      form.setFieldValue('serviceGroup', undefined);
    }
  };

  const handleMultiServiceIdChange = (value: string, name: number) => {
    let serviceName = '';
    let serviceNamespace = undefined;
    let serviceGroup = undefined;
    if (value?.startsWith('static://')) {
      const id = value.replace('static://', '');
      const found = servicesRef.current.find(s => s.serviceId === id);
      serviceName = found?.name || id;
    } else if (value?.startsWith('lb://')) {
      serviceName = value.replace('lb://', '');
      const nacosService = nacosServices.find(s => s.serviceName === serviceName);
      if (nacosService) {
        serviceNamespace = nacosService.namespace || '';
        serviceGroup = nacosService.group || 'DEFAULT_GROUP';
      }
    }
    const currentServices = form.getFieldValue('services') || [];
    currentServices[name] = {
      ...currentServices[name],
      serviceName,
      serviceNamespace,
      serviceGroup
    };
    form.setFieldsValue({ services: currentServices });
  };

  const resolveServiceName = (displayName: string, serviceId: string): string => {
    if (/^[a-f0-9-]{36}$/i.test(displayName)) {
      const found = servicesRef.current.find(svc => svc.serviceId === serviceId);
      if (found?.name) return found.name;
    }
    return displayName;
  };

  // Render the service selector dropdown options (shared for single and multi)
  const renderServiceOptions = () => (
    <>
      <Select.OptGroup label={<><CloudOutlined style={{ marginRight: 6 }} /> {t('routes.static_services')}</>}>
        {services.map(s => (
          <Select.Option key={`static-${s.serviceId}`} value={`static://${s.serviceId}`}>
            <span>{s.name || s.serviceId}</span>
            <span className="service-option-tag static">Static</span>
          </Select.Option>
        ))}
      </Select.OptGroup>
      <Select.OptGroup label={<><GlobalOutlined style={{ marginRight: 6 }} /> {t('routes.nacos_services')}</>}>
        {nacosServices.map(s => (
          <Select.Option key={`lb-${s.serviceName}-${s.namespace || 'public'}-${s.group || 'DEFAULT_GROUP'}`} value={`lb://${s.serviceName}`}>
            <span>{s.displayName || `${s.serviceName} (${s.namespace || 'public'}/${s.group || 'DEFAULT_GROUP'})`}</span>
            <span className="service-option-tag nacos">Nacos</span>
          </Select.Option>
        ))}
      </Select.OptGroup>
    </>
  );

  return (
    <>
      {/* Routing Mode Selection */}
      <Form.Item name="routingMode" label={t('routes.routing_mode_label')} rules={[{ required: true }]}>
        <Select
          placeholder={t('routes.routing_mode_label')}
          style={{ width: '100%' }}
          size="large"
          onChange={(value) => onRoutingModeChange(value)}
        >
          <Select.Option value="SINGLE"><CloudOutlined /> {t('routes.single_service')}</Select.Option>
          <Select.Option value="MULTI"><CloudOutlined /> {t('routes.multi_service')}</Select.Option>
        </Select>
      </Form.Item>

      {/* Single Service Mode */}
      {routingMode === 'SINGLE' && (
        <>
          <Form.Item name="serviceId" label={t('routes.target_service')} rules={[{ required: true }]}>
            <Select placeholder={t('routes.select_service')} size="large" onChange={handleServiceIdChange} showSearch>
              {renderServiceOptions()}
            </Select>
          </Form.Item>
          <Form.Item name="serviceNamespace" hidden>
            <Input type="hidden" />
          </Form.Item>
          <Form.Item name="serviceGroup" hidden>
            <Input type="hidden" />
          </Form.Item>
        </>
      )}

      {/* Multi-Service Mode */}
      {routingMode === 'MULTI' && (
        <>
          {/* Real-time weight total indicator */}
          <Form.Item shouldUpdate noStyle>
            {({ getFieldValue }) => {
              const serviceList = getFieldValue('services') || [];
              const totalWeight = serviceList.reduce((sum: number, s: { weight?: number }) => sum + (s?.weight || 0), 0);
              const isValid = totalWeight === 100;
              return (
                <div style={{
                  marginBottom: '12px',
                  padding: '8px 12px',
                  background: isValid ? '#1a3a1a' : '#2a2520',
                  border: `1px solid ${isValid ? '#3d8b3d' : '#8b5a2b'}`,
                  borderRadius: '4px',
                  display: 'flex',
                  alignItems: 'center',
                  gap: '8px'
                }}>
                  {isValid ? (
                    <Tag color="success">{t('routes.weight_total')}: {totalWeight}% ✓</Tag>
                  ) : (
                    <Tag color="warning">{t('routes.weight_total')}: {totalWeight}% (需为100%)</Tag>
                  )}
                </div>
              );
            }}
          </Form.Item>
          <Form.List name="services" initialValue={routingMode === 'MULTI' && !form.getFieldValue('services') ? [{ weight: 100, enabled: true }] : undefined}>
            {(fields, { add, remove }) => (
              <>
                {fields.map(({ key, name, ...restField }) => (
                  <div key={key} style={{
                    padding: '8px 10px',
                    marginBottom: '6px',
                    background: '#1a2338',
                    borderRadius: '6px',
                    border: '1px solid rgba(148, 163, 184, 0.15)'
                  }}>
                    <div style={{ display: 'flex', gap: '8px', alignItems: 'center', flexWrap: 'nowrap' }}>
                      <Form.Item {...restField} name={[name, 'serviceId']} noStyle rules={[{ required: true }]}>
                        <Select
                          placeholder={t('routes.select_service')}
                          style={{ width: 220, flexShrink: 0 }}
                          size="middle"
                          showSearch
                          onChange={(value) => handleMultiServiceIdChange(value, name)}
                        >
                          {renderServiceOptions()}
                        </Select>
                      </Form.Item>
                      <Form.Item {...restField} name={[name, 'weight']} noStyle initialValue={100}>
                        <InputNumber
                          min={1} max={100}
                          placeholder="Weight"
                          size="middle"
                          style={{ width: 90, flexShrink: 0 }}
                          addonAfter="%"
                        />
                      </Form.Item>
                      <Form.Item {...restField} name={[name, 'enabled']} noStyle valuePropName="checked" initialValue={true}>
                        <Switch size="small" checkedChildren="ON" unCheckedChildren="OFF" />
                      </Form.Item>
                      {fields.length > 1 && (
                        <Button type="text" danger onClick={() => remove(name)} icon={<DeleteOutlined />} style={{ flexShrink: 0, padding: '4px' }} />
                      )}
                    </div>
                  </div>
                ))}
                <Button type="dashed" onClick={() => add({ weight: 100, enabled: true })} block icon={<PlusOutlined />}>
                  {t('routes.add_service_binding')}
                </Button>
              </>
            )}
          </Form.List>
        </>
      )}

      {/* URI Display (for backward compatibility) */}
      {routingMode === 'SINGLE' && (
        <Form.Item name="uri" label={t('routes.target_uri')}>
          <Input placeholder={t('routes.uri_placeholder')} disabled size="large" />
        </Form.Item>
      )}
    </>
  );
};

export default ServiceBindingForm;