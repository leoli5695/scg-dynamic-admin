/**
 * Create/edit service modal (shared form).
 * Mounted only while open, so initial values come straight from props.
 * Body aligns with model/ServiceDefinition.java.
 */
import React, { useCallback, useState } from 'react';
import EditorNavigation from '../../instance-detail/EditorNavigation';
import EditorCategorySwitch from '../../instance-detail/EditorCategorySwitch';
import { useEditorStep } from '../../instance-detail/useEditorStep';
import {
  Alert, Button, Collapse, Form, Input, Modal, Select, Tag, Typography  } from 'antd';
import { message } from '../../../utils/antdStatic';
import {
  CloudServerOutlined, DeploymentUnitOutlined, EditOutlined, ExclamationCircleOutlined,
  PlusOutlined, RobotOutlined,
} from '@ant-design/icons';
import { useTranslation } from 'react-i18next';
import type { ServiceCategory, ServiceDefinition, ServiceInstanceItem } from '../../../types/service';
import { AI_PROVIDER_TYPES } from '../../../types/service';
import { AI_PROVIDERS, getProviderMeta, hasCustomBaseUrl } from '../providers';
import { isValidIP, isValidPort } from '../utils';
import InstanceRow from './InstanceRow';
import LoadBalancerSelect from './LoadBalancerSelect';

interface ServiceFormValues {
  name: string;
  loadBalancer?: string;
  description?: string;
  category?: ServiceCategory;
  providerType?: string;
  baseUrl?: string;
  apiKey?: string;
}

interface ServiceFormModalProps {
  mode: 'create' | 'edit';
  /** Required for edit mode; name is locked there. */
  service?: ServiceDefinition;
  onCancel: () => void;
  /** Return value signals success so the modal can close itself. */
  onSubmit: (values: ServiceFormValues, instances: ServiceInstanceItem[]) => Promise<boolean>;
}

const emptyInstance = (): ServiceInstanceItem => ({
  ip: '', port: 8080, weight: 1, healthy: true, enabled: true,
});

const ServiceFormModal: React.FC<ServiceFormModalProps> = ({
  mode, service, onCancel, onSubmit,
}) => {
  const { t } = useTranslation();
  const [form] = Form.useForm<ServiceFormValues>();
  const [instances, setInstances] = useState<ServiceInstanceItem[]>(
    mode === 'edit' ? [...(service?.instances ?? [])] : [emptyInstance()],
  );
  const [submitting, setSubmitting] = useState(false);

  const [activeStep, setActiveStep, stepDirection, visitedSteps] = useEditorStep();
  const isEdit = mode === 'edit';

  const category = (Form.useWatch('category', form) ?? (isEdit ? service?.category : 'NORMAL')) as ServiceCategory;
  const isAi = category === 'AI';

  // Provider type drives the AI connection: the official base URL is resolved by
  // the data plane from this type, so the URL input only exists as an advanced
  // override for relays / self-hosted backends.
  const providerType = Form.useWatch('providerType', form) ?? (isEdit ? service?.providerType : undefined);
  const providerMeta = getProviderMeta(providerType);
  const providerOptions = AI_PROVIDERS
    .filter((p) => (AI_PROVIDER_TYPES as readonly string[]).includes(p.id))
    .map((p) => ({ value: p.id, label: `${t(p.labelKey)}（${p.id}）` }));
  const providerLabel = providerMeta ? t(providerMeta.labelKey) : t('services.provider_unknown');
  const baseUrlAutoHint = providerMeta
    ? t('services.base_url_auto_hint', { provider: providerLabel, url: providerMeta.defaultBaseUrl })
    : t('services.base_url_helper');

  const handleInstanceChange = useCallback((index: number, patch: Partial<ServiceInstanceItem>) => {
    setInstances((prev) => prev.map((item, i) => (i === index ? { ...item, ...patch } : item)));
  }, []);

  const handleRemoveInstance = useCallback((index: number) => {
    setInstances((prev) => prev.filter((_, i) => i !== index));
  }, []);

  const stepLabels = [t('services.basic_config'), t(isAi ? 'services.ai_config' : 'services.service_instances')];
  const nextStep = async () => {
    try {
      await form.validateFields(['category', 'name', 'loadBalancer', 'description']);
      setActiveStep((step) => Math.min(step + 1, stepLabels.length - 1));
    } catch { /* Ant Design shows the field errors in the current section. */ }
  };

  const handleFinish = async (values: ServiceFormValues) => {
    if (!isAi) {
      for (const [index, inst] of instances.entries()) {
        if (!isValidIP(inst.ip)) {
          message.error(t('message.invalid_ip_format', { index: index + 1, ip: inst.ip }));
          return;
        }
        if (!isValidPort(inst.port)) {
          message.error(t('message.invalid_port', { index: index + 1, port: inst.port }));
          return;
        }
      }
    }
    setSubmitting(true);
    try {
      const ok = await onSubmit(values, instances);
      if (ok) {
        onCancel();
      }
    } finally {
      setSubmitting(false);
    }
  };

  const allDisabled = instances.length === 0 || instances.every((inst) => inst.enabled === false);

  return (
    <Modal
      title={(
        <div className="modal-header-modern">
          <div className="modal-title-wrapper" key={category}>
            <div className={`modal-icon-wrapper ${isEdit ? 'edit' : ''}`}>
              {isAi ? <RobotOutlined /> : <DeploymentUnitOutlined />}
            </div>
            <div className="modal-title-text">
              <div className="modal-title">{t(`services.${isEdit ? 'edit' : 'create'}_${isAi ? 'ai' : 'normal'}`)}</div>
              <div className="modal-subtitle">{t('services.' + (isAi ? 'ai_editor_subtitle' : 'normal_editor_subtitle'))}</div>
            </div>
          </div>
        </div>
      )}
      open
      onCancel={onCancel}
      footer={null}
      width={1000}
      className={`console-editor service-editor${isAi ? ' is-ai' : ''}`}
      destroyOnHidden
      mask={{ closable: false }}
      closable={!submitting}
      keyboard={!submitting}
      focusable={{ focusTriggerAfterClose: true }}
    >
      <Form
        form={form}
        layout="vertical"
        scrollToFirstError={{ behavior: 'smooth', focus: true }}
        disabled={submitting}
        onFinish={handleFinish}
        onFinishFailed={({ errorFields }) => {
          const field = String(errorFields[0]?.name[0] ?? '');
          setActiveStep(['name', 'category', 'loadBalancer', 'description'].includes(field) ? 0 : 1);
        }}
        initialValues={isEdit ? {
          name: service?.name,
          loadBalancer: service?.loadBalancer,
          description: service?.description,
          category: service?.category || 'NORMAL',
          providerType: service?.providerType,
          // Persisted provider-default URLs are data-plane details: normalize them
          // back to "unset" so the operator only sees a real custom override.
          baseUrl: hasCustomBaseUrl(service?.providerType, service?.baseUrl) ? service?.baseUrl : undefined,
        } : { loadBalancer: 'weighted', category: 'NORMAL' }}
      >
        <div className="editor-workspace">
        <EditorNavigation steps={stepLabels} active={activeStep} onChange={setActiveStep} />
        <div className="editor-scroll" data-direction={stepDirection}>
        <div className="form-section-modern" hidden={activeStep !== 0}>
          <div className="section-header">
            <DeploymentUnitOutlined className="section-icon" />
            <span className="section-title">{t('services.basic_config')}</span>
          </div>
          <div className="section-content">
            <Form.Item
              name="category"
              label={t('services.category')}
              tooltip={isEdit ? t('services.category_locked') : t('services.category_helper')}
            >
              <EditorCategorySwitch
                disabled={isEdit}
                label={t('services.category')}
                normalLabel={t('services.category_normal')}
                aiLabel={t('services.category_ai')}
                normalIcon={<DeploymentUnitOutlined />}
              />
            </Form.Item>
            <p className="editor-type-hint" key={category}>{t('services.' + (isAi ? 'ai_type_hint' : 'normal_type_hint'))}</p>
            <div className="form-row">
              <Form.Item
                name="name"
                label={t('services.name')}
                rules={isEdit ? undefined : [{ required: true, whitespace: true, message: t('services.name_required') }]}
                className="form-item-half"
              >
                <Input placeholder={isEdit ? undefined : t('services.name_placeholder')} disabled={isEdit} size="large" />
              </Form.Item>
              <Form.Item hidden={isAi} name="loadBalancer" label={t('services.loadBalancer')} className="form-item-half">
                <LoadBalancerSelect />
              </Form.Item>
            </div>
            <Form.Item name="description" label={t('services.description')}>
              <Input.TextArea
                rows={2}
                placeholder={t('services.description_placeholder')}
                showCount
                maxLength={500}
              />
            </Form.Item>
          </div>
        </div>

        {isAi && (
          <div className="form-section-modern" hidden={activeStep !== 1}>
            <div className="section-header">
              <RobotOutlined className="section-icon" />
              <span className="section-title">{t('services.ai_config')}</span>
              <span className="section-subtitle">{t('services.ai_config_helper')}</span>
            </div>
            <div className="section-content">
              <Form.Item
                name="providerType"
                label={t('services.provider_type')}
                rules={[{ required: true, message: t('services.provider_type_required') }]}
              >
                <Select
                  placeholder={t('services.provider_type_placeholder')}
                  size="large"
                  showSearch
                  options={providerOptions}
                />
              </Form.Item>
              <Form.Item
                name="apiKey"
                label={t('services.api_key')}
                rules={isEdit ? undefined : [{ required: true, message: t('services.api_key_required') }]}
                tooltip={isEdit && service?.credentialRef ? t('services.credential_configured') : t('services.credential_none')}
              >
                <Input.Password
                  size="large"
                  autoComplete="new-password"
                  placeholder={isEdit ? t('services.api_key_rotate_placeholder') : t('services.api_key_placeholder')}
                />
              </Form.Item>
              <Collapse
                ghost
                className="advanced-collapse"
                items={[{
                  key: 'advanced',
                  label: (
                    <span>
                      <span className="advanced-header-title">{t('services.advanced_options')}</span>
                      <span className="advanced-header-hint">{t('services.advanced_hint')}</span>
                    </span>
                  ),
                  children: (
                    <>
                      <Form.Item name="baseUrl" label={t('services.ai_custom_endpoint')}>
                        <Input
                          size="large"
                          placeholder={providerMeta?.defaultBaseUrl || t('services.base_url_placeholder')}
                        />
                      </Form.Item>
                      <Typography.Text type="secondary" className="form-extra-hint">{baseUrlAutoHint}</Typography.Text>
                    </>
                  ),
                }]}
              />
            </div>
          </div>
        )}

        {!isAi && visitedSteps.includes(1) && (
        <div className="form-section-modern" hidden={activeStep !== 1}>
          <div className="section-header">
            <CloudServerOutlined className="section-icon" />
            <span className="section-title">{t('services.service_instances')}</span>
            <span className="section-subtitle">{t('services.instances_helper')}</span>
          </div>
          <div className="section-content">
            {allDisabled && (
              <Alert
                type="warning"
                showIcon
                icon={<ExclamationCircleOutlined />}
                title={instances.length === 0 ? t('services.modal_no_instances_warning') : t('services.modal_all_disabled_warning')}
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
                />
              ))}
              <Button
                type="dashed"
                onClick={() => setInstances([...instances, emptyInstance()])}
                icon={<PlusOutlined />}
                block
                className="add-instance-btn-modern"
                size="large"
              >
                {t('services.add_instance')}
              </Button>
            </div>
          </div>
        </div>
        )}

        </div>
        </div>
        <div className="modal-footer-modern">
          <span className="editor-progress">{activeStep + 1} / {stepLabels.length}</span>
          <Button onClick={onCancel} disabled={submitting}>{t('common.cancel')}</Button>
          {activeStep > 0 && <Button onClick={() => setActiveStep(activeStep - 1)} disabled={submitting}>{t('services.previous_step')}</Button>}
          {activeStep < stepLabels.length - 1 ? (
            <Button key="next" type="primary" htmlType="button" onClick={nextStep}>{t('services.next_step')}</Button>
          ) : (
            <Button key="submit" type="primary" htmlType="submit" loading={submitting} icon={isEdit ? <EditOutlined /> : <PlusOutlined />}>
              {t(isEdit ? 'services.save_changes' : 'services.create')}
            </Button>
          )}
        </div>
      </Form>
    </Modal>
  );
};

export default ServiceFormModal;
