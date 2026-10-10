/**
 * Unified route create/edit modal.
 * Mounted only while open, so initial values come straight from props.
 * Payload follows model/RouteDefinition.java; enable/disable is NOT part
 * of the body (backend uses dedicated /enable and /disable endpoints).
 */
import React, { useState } from 'react';
import {
  Alert, Button, Form, Input, Modal, Segmented, Typography  } from 'antd';
import { message } from '../../../utils/antdStatic';
import {
  ApiOutlined, ClusterOutlined, EditOutlined, FileTextOutlined, LinkOutlined,
  PlusOutlined, RobotOutlined, RocketOutlined, ThunderboltOutlined,
} from '@ant-design/icons';
import { useTranslation } from 'react-i18next';
import { useNavigate, useParams } from 'react-router-dom';
import type {
  RouteCategory, RouteDefinition, RouteResponse, RoutingMode,
} from '../../../types/route';
import type { NacosDiscoveryServiceInfo, ServiceDefinition } from '../../../types/service';
import {
  buildRoutePayload, stringifyPredicateArgs, sumWeights, toFormBinding,
} from '../utils';
import type { RouteFormValues } from '../utils';
import PredicateItem from './PredicateItem';
import ServiceBindingForm from './ServiceBindingForm';
import AiUpstreamsForm from './AiUpstreamsForm';
import GrayRulesForm from './GrayRulesForm';

const { Text } = Typography;
const { TextArea } = Input;

interface RouteFormModalProps {
  mode: 'create' | 'edit';
  /** Required in edit mode. */
  route?: RouteResponse;
  services: ServiceDefinition[];
  nacosServices: NacosDiscoveryServiceInfo[];
  onCancel: () => void;
  /** Return value signals success so the modal can close itself. */
  onSubmit: (body: RouteDefinition) => Promise<boolean>;
}

const RouteFormModal: React.FC<RouteFormModalProps> = ({
  mode, route, services, nacosServices, onCancel, onSubmit,
}) => {
  const { t } = useTranslation();
  const navigate = useNavigate();
  const { instanceId } = useParams<{ instanceId: string }>();
  const [form] = Form.useForm<RouteFormValues>();

  const isEdit = mode === 'edit';

  // Route category drives the target-config section: AI routes bind AI services only.
  const category = (Form.useWatch('category', form)
    ?? (isEdit ? route?.category : 'NORMAL')) as RouteCategory;
  const isAi = category === 'AI';
  const aiServices = services.filter((s) => s.category === 'AI');

  // Edit-time bindings with protocol-prefixed serviceIds for the Select.
  const [initialBindings] = useState(() => (
    isEdit ? (route?.services ?? []).map((s) => toFormBinding(s, services, nacosServices)) : []
  ));

  const [routingMode, setRoutingMode] = useState<RoutingMode>(
    isEdit ? (route?.mode || 'SINGLE') : 'SINGLE',
  );
  const [grayRulesEnabled, setGrayRulesEnabled] = useState<boolean>(
    isEdit ? (route?.grayRules?.enabled || false) : false,
  );
  const [submitting, setSubmitting] = useState(false);

  // Live services list for the gray-rule target dropdown.
  const watchedServices = Form.useWatch('services', form);
  const configuredServices = isEdit ? initialBindings : (watchedServices ?? []);

  const initialValues: Partial<RouteFormValues> = isEdit ? {
    routeName: route?.routeName,
    order: route?.order,
    description: route?.description,
    category: route?.category || 'NORMAL',
    routingMode: route?.mode || 'SINGLE',
    serviceId: route?.uri || undefined,
    serviceNamespace: route?.serviceNamespace || '',
    serviceGroup: route?.serviceGroup || 'DEFAULT_GROUP',
    uri: route?.uri,
    services: initialBindings,
    predicates: (route?.predicates ?? []).map((p) => ({
      name: p.name,
      args: stringifyPredicateArgs(p.name, p.args as Record<string, unknown>),
    })),
    grayRules: route?.grayRules?.rules ?? [],
    aiUpstreams: (route?.aiUpstreams ?? []).map((u) => ({
      provider: u.provider, weight: u.weight ?? 100, modelMapping: u.modelMapping,
    })),
    modelPredicates: (route?.modelPredicates ?? []).map((p) => ({
      matchType: p.matchType || 'PREFIX', value: p.value,
    })),
  } : { order: 0, routingMode: 'SINGLE', category: 'NORMAL' };

  const handleFinish = async (values: RouteFormValues) => {
    const predicateList = values.predicates ?? [];
    if (predicateList.length === 0) {
      message.error(t('routes.predicate_required'));
      return;
    }
    if (!predicateList.some((p) => p?.name === 'Path')) {
      message.error(t('routes.path_predicate_required'));
      return;
    }
    if (isAi) {
      const ups = (values.aiUpstreams ?? []).filter((u) => u && u.provider);
      if (ups.length === 0) {
        message.error(t('routes.ai_at_least_one_upstream'));
        return;
      }
    } else {
      if (routingMode === 'SINGLE' && !values.serviceId && !values.uri) {
        message.error(t('routes.uri_required'));
        return;
      }
      if (routingMode === 'MULTI') {
        if (!values.services || values.services.length === 0) {
          message.error(t('routes.at_least_one_service'));
          return;
        }
        const total = sumWeights(values.services);
        if (total !== 100) {
          message.error(t('routes.weight_must_be_100', { total }));
          return;
        }
      }
    }

    const body = buildRoutePayload(values, routingMode, grayRulesEnabled, {
      services,
      nacosServices,
      existing: route,
    });
    if (!body) {
      message.error(t('routes.create_failed'));
      return;
    }

    setSubmitting(true);
    try {
      const ok = await onSubmit(body);
      if (ok) {
        onCancel();
      }
    } finally {
      setSubmitting(false);
    }
  };

  const gotoPluginBindings = () => {
    navigate(`/instances/${instanceId}/plugin_bindings`);
  };

  return (
    <Modal
      title={(
        <div className="modal-header-modern">
          <div className="modal-title-wrapper">
            <div className={`modal-icon-wrapper ${isEdit ? 'edit' : ''}`}>
              {isEdit ? <EditOutlined /> : <PlusOutlined />}
            </div>
            <div className="modal-title-text">
              <div className="modal-title">{t(isEdit ? 'routes.edit' : 'routes.create')}</div>
              <div className="modal-subtitle">{t(isEdit ? 'routes.edit_description' : 'routes.create_description')}</div>
            </div>
          </div>
        </div>
      )}
      open
      onCancel={onCancel}
      footer={null}
      width={720}
      className={`route-modal ${isEdit ? 'route-edit-modal' : 'route-create-modal'}`}
      destroyOnClose
      maskClosable
      focusTriggerAfterClose
    >
      <Form
        form={form}
        layout="vertical"
        onFinish={handleFinish}
        initialValues={initialValues}
      >
        {/* Basic info */}
        <div className="form-section-modern">
          <div className="section-header">
            <FileTextOutlined className="section-icon" />
            <span className="section-title">{t('routes.basic_info')}</span>
          </div>
          <div className="section-content">
            <Form.Item
              name="category"
              label={t('routes.category')}
              tooltip={isEdit ? t('routes.category_locked') : t('routes.category_helper')}
            >
              <Segmented
                disabled={isEdit}
                options={[
                  { label: (<span><ClusterOutlined /> {t('routes.category_normal')}</span>), value: 'NORMAL' },
                  { label: (<span><RobotOutlined /> {t('routes.category_ai')}</span>), value: 'AI' },
                ]}
              />
            </Form.Item>
            <Form.Item
              name="routeName"
              label={t('routes.route_name')}
              rules={isEdit ? undefined : [{ required: true }]}
            >
              <Input
                placeholder={isEdit ? undefined : t('routes.route_name_placeholder')}
                disabled={isEdit}
                size="large"
              />
            </Form.Item>
            <div className="form-row">
              <Form.Item name="order" label={t('routes.order')} className="form-item-half">
                <Input type="number" placeholder="0" size="large" />
              </Form.Item>
            </div>
            <Form.Item name="description" label={t('routes.description_label')}>
              <TextArea rows={2} placeholder={t('routes.description_placeholder')} showCount maxLength={500} />
            </Form.Item>
          </div>
        </div>

        {/* Target configuration */}
        <div className="form-section-modern">
          <div className="section-header">
            {isAi ? <RobotOutlined className="section-icon" /> : <ApiOutlined className="section-icon" />}
            <span className="section-title">{isAi ? t('routes.ai_target_configuration') : t('routes.target_configuration')}</span>
            {isAi && <span className="section-subtitle">{t('routes.ai_target_helper')}</span>}
          </div>
          <div className="section-content">
            {isAi ? (
              <AiUpstreamsForm form={form} aiServices={aiServices} />
            ) : (
              <>
                <ServiceBindingForm
                  form={form}
                  routingMode={routingMode}
                  onRoutingModeChange={(m) => {
                    setRoutingMode(m);
                    form.setFieldsValue({ serviceId: undefined, services: [], uri: '' });
                  }}
                  services={services}
                  nacosServices={nacosServices}
                />
                <GrayRulesForm
                  form={form}
                  grayRulesEnabled={grayRulesEnabled}
                  onGrayRulesToggle={setGrayRulesEnabled}
                  configuredServices={configuredServices}
                  services={services}
                />
              </>
            )}
          </div>
        </div>

        {/* Predicates */}
        <div className="form-section-modern">
          <div className="section-header">
            <ThunderboltOutlined className="section-icon" />
            <span className="section-title">{t('routes.predicates_section')}</span>
          </div>
          <div className="section-content">
            <Form.List name="predicates">
              {(fields, { add, remove }) => (
                <>
                  {fields.map(({ key, name, ...restField }) => (
                    <PredicateItem key={key} form={form} restField={restField} name={name} onRemove={remove} />
                  ))}
                  <Button type="dashed" onClick={() => add()} block icon={<PlusOutlined />} size="large" className="add-predicate-btn-modern">
                    {t('routes.add_predicate')}
                  </Button>
                </>
              )}
            </Form.List>
          </div>
        </div>

        {/* Plugins — managed on the plugin binding page */}
        <div className="form-section-modern">
          <div className="section-header">
            <RocketOutlined className="section-icon" />
            <span className="section-title">{t('routes.plugins_section')}</span>
          </div>
          <div className="section-content">
            <Alert
              type="info"
              showIcon
              message={t('routes.plugins_guide_title')}
              description={t('routes.plugins_guide_desc')}
              style={{ marginBottom: 16 }}
            />
            <Button type="dashed" block icon={<LinkOutlined />} size="large" onClick={gotoPluginBindings}>
              {t('routes.manage_plugin_bindings')}
            </Button>
            <Text type="secondary" style={{ display: 'block', marginTop: 8, fontSize: 12 }}>
              {t('routes.plugins_hint')}
            </Text>
          </div>
        </div>

        {/* Footer */}
        <div className="modal-footer-modern">
          <Button onClick={onCancel} size="large">{t('common.cancel')}</Button>
          <Button type="primary" htmlType="submit" size="large" loading={submitting} icon={isEdit ? <EditOutlined /> : <PlusOutlined />}>
            {t(isEdit ? 'common.update' : 'routes.create')}
          </Button>
        </div>
      </Form>
    </Modal>
  );
};

export default RouteFormModal;
