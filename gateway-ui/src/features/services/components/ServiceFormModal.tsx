/**
 * Create/edit service modal (shared form).
 * Mounted only while open, so initial values come straight from props.
 * Body aligns with model/ServiceDefinition.java.
 */
import React, { useState } from 'react';
import {
  Alert, Button, Form, Input, Modal  } from 'antd';
import { message } from '../../../utils/antdStatic';
import {
  ApiOutlined, ClusterOutlined, EditOutlined, ExclamationCircleOutlined,
  PlusOutlined,
} from '@ant-design/icons';
import { useTranslation } from 'react-i18next';
import type { ServiceDefinition, ServiceInstanceItem } from '../../../types/service';
import { isValidIP, isValidPort } from '../utils';
import InstanceRow from './InstanceRow';
import LoadBalancerSelect from './LoadBalancerSelect';

interface ServiceFormValues {
  name: string;
  loadBalancer?: string;
  description?: string;
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

  const isEdit = mode === 'edit';

  const handleInstanceChange = (index: number, patch: Partial<ServiceInstanceItem>) => {
    setInstances((prev) => prev.map((item, i) => (i === index ? { ...item, ...patch } : item)));
  };

  const handleRemoveInstance = (index: number) => {
    setInstances((prev) => prev.filter((_, i) => i !== index));
  };

  const handleFinish = async (values: ServiceFormValues) => {
    for (const inst of instances) {
      if (!isValidIP(inst.ip)) {
        message.error(t('message.invalid_ip_format', { index: 1, ip: inst.ip }));
        return;
      }
      if (!isValidPort(inst.port)) {
        message.error(t('message.invalid_port', { index: 1, port: inst.port }));
        return;
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
          <div className="modal-title-wrapper">
            <div className={`modal-icon-wrapper ${isEdit ? 'edit' : ''}`}>
              {isEdit ? <EditOutlined /> : <PlusOutlined />}
            </div>
            <div className="modal-title-text">
              <div className="modal-title">{t(isEdit ? 'services.edit' : 'services.create')}</div>
              <div className="modal-subtitle">{t(isEdit ? 'services.edit_description' : 'services.create_description')}</div>
            </div>
          </div>
        </div>
      )}
      open
      onCancel={onCancel}
      footer={null}
      width={600}
      className={`service-modal ${isEdit ? 'service-edit-modal' : 'service-create-modal'}`}
      destroyOnClose
      maskClosable
      focusTriggerAfterClose
    >
      <Form
        form={form}
        layout="vertical"
        onFinish={handleFinish}
        initialValues={isEdit ? {
          name: service?.name,
          loadBalancer: service?.loadBalancer,
          description: service?.description,
        } : { loadBalancer: 'weighted' }}
      >
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
                rules={isEdit ? undefined : [{ required: true, message: t('services.name_required') }]}
                className="form-item-half"
              >
                <Input placeholder={isEdit ? undefined : t('services.name_placeholder')} disabled={isEdit} size="large" />
              </Form.Item>
              <Form.Item name="loadBalancer" label={t('services.loadBalancer')} className="form-item-half">
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

        <div className="form-section-modern">
          <div className="section-header">
            <ApiOutlined className="section-icon" />
            <span className="section-title">{t('services.service_instances')}</span>
            <span className="section-subtitle">{t('services.instances_helper')}</span>
          </div>
          <div className="section-content">
            {allDisabled && (
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

        <div className="modal-footer-modern">
          <Button onClick={onCancel} size="large">{t('common.cancel')}</Button>
          <Button type="primary" htmlType="submit" size="large" loading={submitting} icon={isEdit ? <EditOutlined /> : <PlusOutlined />}>
            {t(isEdit ? 'common.update' : 'common.create')}
          </Button>
        </div>
      </Form>
    </Modal>
  );
};

export default ServiceFormModal;
