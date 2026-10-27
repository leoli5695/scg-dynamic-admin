/**
 * Unified route create/edit modal.
 * Mounted only while open, so initial values come straight from props.
 * Payload follows model/RouteDefinition.java; enable/disable is NOT part
 * of the body (backend uses dedicated /enable and /disable endpoints).
 */
import React, { useState } from 'react';
import EditorNavigation from '../../instance-detail/EditorNavigation';
import EditorCategorySwitch from '../../instance-detail/EditorCategorySwitch';
import { useEditorStep } from '../../instance-detail/useEditorStep';
import {
  Alert, Button, Form, Input, Modal, Typography  } from 'antd';
import { message } from '../../../utils/antdStatic';
import {
  ApiOutlined, EditOutlined, FileTextOutlined, LinkOutlined,
  PlusOutlined, RobotOutlined, RocketOutlined, ThunderboltOutlined,
} from '@ant-design/icons';
import { useTranslation } from 'react-i18next';
import { useNavigate, useParams } from 'react-router-dom';
import type {
  RouteCategory, RouteDefinition, RouteResponse, RoutingMode,
} from '../../../types/route';
import type { NacosDiscoveryServiceInfo, ServiceDefinition } from '../../../types/service';
import {
  buildRoutePayload, modelMappingToPairs, splitServiceUri, stringifyPredicateArgs, sumWeights,
  toFormBinding, toFormServiceId,
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

  const [activeStep, setActiveStep, stepDirection] = useEditorStep();
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
  const configuredServices = watchedServices ?? initialBindings;

  const initialValues: Partial<RouteFormValues> = isEdit ? {
    routeName: route?.routeName,
    order: route?.order,
    description: route?.description,
    category: route?.category || 'NORMAL',
    routingMode: route?.mode || 'SINGLE',
    // Re-qualify the stored uri so it matches the option the operator originally
    // picked - the same serviceName can exist in several namespaces/groups.
    serviceId: route?.uri
      ? toFormServiceId(...splitServiceUri(route.uri), route.serviceNamespace, route.serviceGroup)
      : undefined,
    uri: route?.uri,
    services: initialBindings,
    predicates: (route?.predicates ?? []).map((p) => ({
      name: p.name,
      args: stringifyPredicateArgs(p.name, p.args as Record<string, unknown>),
    })),
    grayRules: route?.grayRules?.rules ?? [],
    aiUpstreams: (route?.aiUpstreams ?? []).map((u) => ({
      provider: u.provider, weight: u.weight ?? 100, modelMappingPairs: modelMappingToPairs(u.modelMapping),
    })),
    modelPredicates: (route?.modelPredicates ?? []).map((p) => ({
      matchType: p.matchType || 'PREFIX', value: p.value,
    })),
  } : { order: 0, routingMode: 'SINGLE', category: 'NORMAL', predicates: [{ name: 'Path', args: '' }] };

  const stepLabels = [t('routes.basic_info'), t('routes.target_configuration'), t('routes.predicates_section')];
  const nextStep = async () => {
    try {
      await form.validateFields(activeStep === 0 ? ['category', 'routeName', 'order', 'description'] : isAi ? ['aiUpstreams', 'modelPredicates'] : ['serviceId', 'services', 'routingMode'], { recursive: true });
      setActiveStep((step) => Math.min(step + 1, stepLabels.length - 1));
    } catch { /* Ant Design shows the field errors in the current section. */ }
  };

  const handleFinish = async (values: RouteFormValues) => {
    const predicateList = values.predicates ?? [];
    if (predicateList.length === 0) {
      setActiveStep(2);
      message.error(t('routes.predicate_required'));
      return;
    }
    if (!predicateList.some((p) => p?.name === 'Path')) {
      setActiveStep(2);
      message.error(t('routes.path_predicate_required'));
      return;
    }
    if (isAi) {
      const ups = (values.aiUpstreams ?? []).filter((u) => u && u.provider);
      if (ups.length === 0) {
        setActiveStep(1);
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
          setActiveStep(1);
          message.error(t('routes.at_least_one_service'));
          return;
        }
        const total = sumWeights(values.services);
        if (total !== 100) {
          setActiveStep(1);
          message.error(t('routes.weight_must_be_100', { total }));
          return;
        }
      }
    }

    const body = buildRoutePayload(values, routingMode, grayRulesEnabled, {
      services,
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
    onCancel();
    navigate(`/instances/${instanceId}/plugin_bindings`);
  };

  return (
    <Modal
      title={(
        <div className="modal-header-modern">
          <div className="modal-title-wrapper" key={category}>
            <div className={`modal-icon-wrapper ${isEdit ? 'edit' : ''}`}>
              {isAi ? <RobotOutlined /> : <ApiOutlined />}
            </div>
            <div className="modal-title-text">
              <div className="modal-title">{t(`routes.${isEdit ? 'edit' : 'create'}_${isAi ? 'ai' : 'normal'}`)}</div>
              <div className="modal-subtitle">{t('routes.' + (isAi ? 'ai_editor_subtitle' : 'normal_editor_subtitle'))}</div>
            </div>
          </div>
        </div>
      )}
      open
      onCancel={onCancel}
      footer={null}
      width={1060}
      className={`console-editor route-editor${isAi ? ' is-ai' : ''}`}
      destroyOnHidden
      mask={{ closable: false }}
      closable={!submitting}
      keyboard={!submitting}
      focusTriggerAfterClose
    >
      <Form
        form={form}
        layout="vertical"
        scrollToFirstError={{ behavior: 'smooth', focus: true }}
        disabled={submitting}
        onFinish={handleFinish}
        onFinishFailed={({ errorFields }) => {
          const field = String(errorFields[0]?.name[0] ?? '');
          setActiveStep(['routeName', 'category', 'order', 'description'].includes(field) ? 0 : ['predicates'].includes(field) ? 2 : 1);
        }}
        initialValues={initialValues}
      >
        <div className="editor-workspace">
        <EditorNavigation steps={stepLabels} active={activeStep} onChange={setActiveStep} />
        <div className="editor-scroll" data-direction={stepDirection}>
        {/* Basic info */}
        <div className="form-section-modern" hidden={activeStep !== 0}>
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
              <EditorCategorySwitch
                disabled={isEdit}
                label={t('routes.category')}
                normalLabel={t('routes.category_normal')}
                aiLabel={t('routes.category_ai')}
              />
            </Form.Item>
            <p className="editor-type-hint" key={category}>{t('routes.' + (isAi ? 'ai_type_hint' : 'normal_type_hint'))}</p>
            <div className="form-row">
            <Form.Item
              className="form-item-half"
              name="routeName"
              label={t('routes.route_name')}
              rules={isEdit ? undefined : [{ required: true, whitespace: true, message: t('routes.name_required') }]}
            >
              <Input
                placeholder={isEdit ? undefined : t('routes.route_name_placeholder')}
                disabled={isEdit}
                size="large"
              />
            </Form.Item>
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
        <div className="form-section-modern" hidden={activeStep !== 1}>
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
        <div className="form-section-modern" hidden={activeStep !== 2}>
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
        <div className="form-section-modern editor-plugin-note" hidden={activeStep !== 2}>
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
        </div>
        </div>
        <div className="modal-footer-modern">
          <span className="editor-progress">{activeStep + 1} / {stepLabels.length}</span>
          <Button onClick={onCancel} disabled={submitting}>{t('common.cancel')}</Button>
          {activeStep > 0 && <Button onClick={() => setActiveStep(activeStep - 1)} disabled={submitting}>{t('routes.previous_step')}</Button>}
          {activeStep < stepLabels.length - 1 ? (
            <Button key="next" type="primary" htmlType="button" onClick={nextStep}>{t('routes.next_step')}</Button>
          ) : (
            <Button key="submit" type="primary" htmlType="submit" loading={submitting} icon={isEdit ? <EditOutlined /> : <PlusOutlined />}>
              {t(isEdit ? 'routes.save_changes' : 'routes.create')}
            </Button>
          )}
        </div>
      </Form>
    </Modal>
  );
};

export default RouteFormModal;
