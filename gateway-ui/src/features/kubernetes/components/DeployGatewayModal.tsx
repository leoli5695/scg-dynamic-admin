import React, { useCallback } from 'react';
import { Form, Input, InputNumber, Modal, Select } from 'antd';
import { message } from '../../../utils/antdStatic';
import { useTranslation } from 'react-i18next';
import { deployGateway } from '../../../api/kubernetes';
import { ApiError } from '../../../api/http';
import type { DeployGatewayRequest } from '../../../types/kubernetes';

interface DeployGatewayModalProps {
  open: boolean;
  clusterId?: number;
  namespaces: string[];
  onClose: () => void;
  onDeployed: (namespace: string) => void;
}

const DEFAULT_VALUES: Required<Omit<DeployGatewayRequest, 'envVars'>> = {
  namespace: 'gateway',
  appName: 'my-gateway',
  replicas: 1,
  image: 'my-gateway:latest',
  containerPort: 8080,
};

export const DeployGatewayModal: React.FC<DeployGatewayModalProps> = ({
  open,
  clusterId,
  namespaces,
  onClose,
  onDeployed,
}) => {
  const { t } = useTranslation();
  const [form] = Form.useForm<DeployGatewayRequest>();

  const handleDeploy = useCallback(async () => {
    if (clusterId === undefined) return;
    let values: DeployGatewayRequest;
    try {
      values = await form.validateFields();
    } catch {
      return;
    }

    message.loading({ content: t('k8s.deploying'), key: 'deploy', duration: 0 });
    try {
      await deployGateway(clusterId, {
        namespace: values.namespace,
        appName: values.appName,
        replicas: values.replicas,
        image: values.image,
        containerPort: values.containerPort,
      });
      message.success({ content: t('k8s.deploy_success'), key: 'deploy' });
      form.resetFields();
      onDeployed(values.namespace ?? 'gateway');
      onClose();
    } catch (e) {
      message.error({
        content: e instanceof ApiError && e.message ? e.message : t('k8s.deploy_failed'),
        key: 'deploy',
      });
    }
  }, [clusterId, form, t, onDeployed, onClose]);

  const handleCancel = useCallback(() => {
    form.resetFields();
    onClose();
  }, [form, onClose]);

  return (
    <Modal
      title={t('k8s.deploy_title')}
      open={open}
      onCancel={handleCancel}
      onOk={handleDeploy}
      width={600}
      okText={t('k8s.deploy_button')}
      cancelText={t('common.cancel')}
      destroyOnHidden
    >
      <Form form={form} layout="vertical" initialValues={DEFAULT_VALUES}>
        <Form.Item
          name="namespace"
          label={t('k8s.namespace')}
          rules={[{ required: true }]}
        >
          <Select
            options={[
              { label: 'gateway', value: 'gateway' },
              { label: 'default', value: 'default' },
              ...namespaces.map(ns => ({ label: ns, value: ns })),
            ]}
          />
        </Form.Item>

        <Form.Item
          name="appName"
          label={t('k8s.app_name')}
          rules={[{ required: true }]}
        >
          <Input placeholder="my-gateway" />
        </Form.Item>

        <Form.Item
          name="replicas"
          label={t('k8s.replicas')}
          rules={[{ required: true }]}
        >
          <InputNumber min={1} max={10} />
        </Form.Item>

        <Form.Item
          name="image"
          label={t('k8s.image')}
          rules={[{ required: true }]}
        >
          <Input placeholder="my-gateway:latest" />
        </Form.Item>

        <Form.Item
          name="containerPort"
          label={t('k8s.container_port')}
          rules={[{ required: true }]}
        >
          <InputNumber min={1} max={65535} />
        </Form.Item>
      </Form>
    </Modal>
  );
};
