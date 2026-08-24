import React, { useCallback, useState } from 'react';
import { Alert, Button, Form, Input, Modal, Space } from 'antd';
import { message } from '../../../utils/antdStatic';
import { ApiOutlined } from '@ant-design/icons';
import { useTranslation } from 'react-i18next';
import { importCluster, testKubeconfigConnection } from '../../../api/kubernetes';
import { ApiError } from '../../../api/http';
import type { ConnectionTestResult } from '../../../types/kubernetes';

const { TextArea } = Input;

interface ImportClusterModalProps {
  open: boolean;
  onClose: () => void;
  onImported: () => void;
}

export const ImportClusterModal: React.FC<ImportClusterModalProps> = ({
  open,
  onClose,
  onImported,
}) => {
  const { t } = useTranslation();
  const [form] = Form.useForm();
  const [testingConnection, setTestingConnection] = useState(false);
  const [importing, setImporting] = useState(false);
  const [testResult, setTestResult] = useState<ConnectionTestResult | null>(null);

  const errorMessage = useCallback((e: unknown, fallback: string) => (
    e instanceof ApiError && e.message ? e.message : fallback
  ), []);

  const handleTestConnection = useCallback(async () => {
    const kubeconfig = form.getFieldValue('kubeconfig') as string | undefined;
    if (!kubeconfig) {
      message.warning(t('k8s.kubeconfig_required'));
      return;
    }

    setTestingConnection(true);
    setTestResult(null);
    try {
      const result = await testKubeconfigConnection(kubeconfig);
      setTestResult(result);
      if (result.success) {
        message.success(t('k8s.test_success'));
      } else {
        message.error(`${t('k8s.test_failed')}: ${result.error ?? ''}`);
      }
    } catch (e) {
      message.error(errorMessage(e, t('k8s.test_failed')));
    } finally {
      setTestingConnection(false);
    }
  }, [form, t, errorMessage]);

  const handleImport = useCallback(async () => {
    let values: { clusterName: string; kubeconfig: string; description?: string };
    try {
      values = await form.validateFields();
    } catch {
      return;
    }

    setImporting(true);
    try {
      await importCluster({
        clusterName: values.clusterName,
        kubeconfig: values.kubeconfig,
        description: values.description,
      });
      message.success(t('k8s.import_success'));
      form.resetFields();
      setTestResult(null);
      onImported();
      onClose();
    } catch (e) {
      message.error(errorMessage(e, t('k8s.import_failed')));
    } finally {
      setImporting(false);
    }
  }, [form, t, errorMessage, onImported, onClose]);

  const handleCancel = useCallback(() => {
    setTestResult(null);
    onClose();
  }, [onClose]);

  return (
    <Modal
      title={t('k8s.import_title')}
      open={open}
      onCancel={handleCancel}
      onOk={handleImport}
      confirmLoading={importing}
      width={700}
      okText={t('common.confirm')}
      cancelText={t('common.cancel')}
      destroyOnHidden
    >
      <Alert
        type="info"
        showIcon
        message={t('k8s.import_info')}
        style={{ marginBottom: 16 }}
      />
      <Form form={form} layout="vertical">
        <Form.Item
          name="clusterName"
          label={t('k8s.cluster_name')}
          rules={[{ required: true, message: t('k8s.cluster_name_required') }]}
        >
          <Input placeholder={t('k8s.cluster_name_placeholder')} />
        </Form.Item>

        <Form.Item
          name="kubeconfig"
          label={
            <Space>
              {t('k8s.kubeconfig')}
              <Button
                type="link"
                size="small"
                icon={<ApiOutlined />}
                onClick={handleTestConnection}
                loading={testingConnection}
              >
                {t('k8s.test_connection')}
              </Button>
            </Space>
          }
          rules={[{ required: true, message: t('k8s.kubeconfig_required') }]}
        >
          <TextArea
            rows={10}
            placeholder={t('k8s.kubeconfig_placeholder')}
            style={{ fontFamily: 'monospace' }}
            onChange={() => setTestResult(null)}
          />
        </Form.Item>

        {testResult && (
          <Alert
            type={testResult.success ? 'success' : 'error'}
            showIcon
            style={{ marginBottom: 16 }}
            message={testResult.success ? t('k8s.test_success') : t('k8s.test_failed')}
            description={
              testResult.success ? (
                <div>
                  <p><strong>{t('k8s.server_url')}:</strong> {testResult.serverUrl}</p>
                  <p><strong>{t('k8s.context')}:</strong> {testResult.contextName}</p>
                  {testResult.clusterVersion && (
                    <p><strong>{t('k8s.cluster_version')}:</strong> {testResult.clusterVersion}</p>
                  )}
                  {testResult.nodeCount !== undefined && (
                    <p><strong>{t('k8s.nodes')}:</strong> {testResult.nodeCount}</p>
                  )}
                  {testResult.namespaceCount !== undefined && (
                    <p><strong>{t('k8s.namespace')}:</strong> {testResult.namespaceCount}</p>
                  )}
                </div>
              ) : (
                <div>
                  <p>{testResult.serverUrl && <><strong>{t('k8s.server_url')}:</strong> {testResult.serverUrl}<br /></>}</p>
                  <p style={{ color: '#ff4d4f' }}>{testResult.error}</p>
                </div>
              )
            }
          />
        )}

        <Form.Item
          name="description"
          label={t('common.description')}
        >
          <Input placeholder={t('k8s.description_placeholder')} />
        </Form.Item>
      </Form>
    </Modal>
  );
};
