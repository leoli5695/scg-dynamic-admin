/**
 * Service binding configuration form — shared between create and edit.
 * Handles both SINGLE and MULTI routing modes.
 */
import React, { useMemo } from 'react';
import {
  Button, Form, Input, InputNumber, Select, Switch, Tag
} from 'antd';
import type { FormInstance } from 'antd';
import type { DefaultOptionType } from 'antd/es/select';
import {
  CloudOutlined, DeleteOutlined, GlobalOutlined, PlusOutlined,
} from '@ant-design/icons';
import { useTranslation } from 'react-i18next';
import type { RoutingMode } from '../../../types/route';
import type { NacosDiscoveryServiceInfo, ServiceDefinition } from '../../../types/service';
import {
  lbOptionValue, splitServiceUri, stripServiceQualifier, sumWeights,
} from '../utils';

interface ServiceBindingFormProps {
  form: FormInstance;
  routingMode: RoutingMode;
  onRoutingModeChange: (mode: RoutingMode) => void;
  services: ServiceDefinition[];
  nacosServices: NacosDiscoveryServiceInfo[];
}

interface ServiceOption extends DefaultOptionType {
  kind?: 'static' | 'nacos';
  options?: ServiceOption[];
}

const ServiceBindingForm: React.FC<ServiceBindingFormProps> = ({
  form,
  routingMode,
  onRoutingModeChange,
  services,
  nacosServices,
}) => {
  const { t } = useTranslation();

  const handleServiceIdChange = (value: string) => {
    form.setFieldValue('uri', stripServiceQualifier(value));
  };

  const handleMultiServiceIdChange = (value: string, name: number) => {
    let serviceName = '';
    if (value?.startsWith('static://')) {
      const id = value.replace('static://', '');
      const found = services.find((s) => s.serviceId === id);
      serviceName = found?.name || id;
    } else if (value) {
      serviceName = splitServiceUri(value)[1];
    }
    const currentServices = form.getFieldValue('services') || [];
    currentServices[name] = { ...currentServices[name], serviceName };
    form.setFieldsValue({ services: currentServices });
  };

  // Share one option tree across every binding row. JSX Select.Option children
  // forced each Select to rebuild and parse the entire registry on every render.
  const serviceOptions = useMemo<ServiceOption[]>(() => [
    {
      label: <><CloudOutlined /> {t('routes.static_services')}</>,
      options: services.map((s) => ({
        value: `static://${s.serviceId}`,
        label: s.name || s.serviceId,
        kind: 'static',
      })),
    },
    {
      label: <><GlobalOutlined /> {t('routes.nacos_services')}</>,
      options: nacosServices.map((s) => ({
        value: lbOptionValue(s.serviceName, s.namespace, s.group),
        label: s.displayName || `${s.serviceName} (${s.namespace || 'public'}/${s.group || 'DEFAULT_GROUP'})`,
        kind: 'nacos',
      })),
    },
  ], [services, nacosServices, t]);

  return (
    <>
      {/* Routing mode selection */}
      <Form.Item name="routingMode" label={t('routes.routing_mode_label')} rules={[{ required: true }]}>
        <Select
          placeholder={t('routes.routing_mode_label')}
          style={{ width: '100%' }}
          size="large"
          onChange={(value) => onRoutingModeChange(value)}
        >
          <Select.Option value="SINGLE">
            <CloudOutlined />
            {' '}
            {t('routes.single_service')}
          </Select.Option>
          <Select.Option value="MULTI">
            <CloudOutlined />
            {' '}
            {t('routes.multi_service')}
          </Select.Option>
        </Select>
      </Form.Item>

      {/* Single service mode */}
      {routingMode === 'SINGLE' && (
        <>
          <Form.Item name="serviceId" label={t('routes.target_service')} rules={[{ required: true }]}>
            <Select<string, ServiceOption> placeholder={t('routes.select_service')} size="large" onChange={handleServiceIdChange}
              showSearch={{ optionFilterProp: 'label' }} options={serviceOptions}
              optionRender={(option) => <><span>{option.label}</span><span className={`service-option-tag ${option.data.kind}`}>{option.data.kind === 'static' ? 'Static' : 'Nacos'}</span></>}
            />
          </Form.Item>
        </>
      )}

      {/* Multi-service mode */}
      {routingMode === 'MULTI' && (
        <>
          {/* Real-time weight total indicator */}
          <Form.Item shouldUpdate={(previous, current) => previous.services !== current.services} noStyle>
            {({ getFieldValue }) => {
              const totalWeight = sumWeights(getFieldValue('services'));
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
                  gap: '8px',
                }}
                >
                  {isValid ? (
                    <Tag color="success">
                      {t('routes.weight_total')}
                      :
                      {' '}
                      {totalWeight}
                      % ✓
                    </Tag>
                  ) : (
                    <Tag color="warning">
                      {t('routes.weight_total')}
                      :
                      {' '}
                      {totalWeight}
                      % (
                      {t('routes.weight_must_be_100_hint')}
                      )
                    </Tag>
                  )}
                </div>
              );
            }}
          </Form.Item>
          <Form.List
            name="services"
            initialValue={routingMode === 'MULTI' && !form.getFieldValue('services')
              ? [{ weight: 100, enabled: true }] : undefined}
          >
            {(fields, { add, remove }) => (
              <>
                {fields.map(({ key, name, ...restField }) => (
                  <div key={key} style={{
                    padding: '8px 10px',
                    marginBottom: '6px',
                    background: '#1a2338',
                    borderRadius: '6px',
                    border: '1px solid rgba(148, 163, 184, 0.15)',
                  }}
                  >
                    <div style={{ display: 'flex', gap: '8px', alignItems: 'center', flexWrap: 'nowrap' }}>
                      <Form.Item {...restField} name={[name, 'serviceId']} noStyle rules={[{ required: true }]}>
                        <Select<string, ServiceOption>
                          placeholder={t('routes.select_service')}
                          style={{ width: 220, flexShrink: 0 }}
                          size="middle"
                          showSearch={{ optionFilterProp: 'label' }}
                          options={serviceOptions}
                          optionRender={(option) => <><span>{option.label}</span><span className={`service-option-tag ${option.data.kind}`}>{option.data.kind === 'static' ? 'Static' : 'Nacos'}</span></>}
                          onChange={(value) => handleMultiServiceIdChange(value, name)}
                        />
                      </Form.Item>
                      <Form.Item {...restField} name={[name, 'weight']} noStyle>
                        <InputNumber
                          min={1}
                          max={100}
                          placeholder="Weight"
                          size="middle"
                          style={{ width: 90, flexShrink: 0 }}
                          addonAfter="%"
                        />
                      </Form.Item>
                      <Form.Item {...restField} name={[name, 'enabled']} noStyle valuePropName="checked">
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

      {/* URI display (backward compatibility) */}
      {routingMode === 'SINGLE' && (
        <Form.Item name="uri" label={t('routes.target_uri')}>
          <Input placeholder={t('routes.uri_placeholder')} disabled size="large" />
        </Form.Item>
      )}
    </>
  );
};

export default ServiceBindingForm;
