import React, { useCallback, useState } from 'react';
import { Button, Form, Input, Modal, Space, Typography } from 'antd';
import { message } from '../../../utils/antdStatic';
import { useTranslation } from 'react-i18next';
import { importLicense } from '../../../api/license';
import { ApiError } from '../../../api/http';

const { Text } = Typography;
const { TextArea } = Input;

interface ActivateLicenseModalProps {
  open: boolean;
  onClose: () => void;
  onActivated: () => void;
}

export const ActivateLicenseModal: React.FC<ActivateLicenseModalProps> = ({
  open,
  onClose,
  onActivated,
}) => {
  const { t } = useTranslation();
  const [form] = Form.useForm<{ licenseCode: string }>();
  const [submitting, setSubmitting] = useState(false);

  const handleActivate = useCallback(async (values: { licenseCode: string }) => {
    setSubmitting(true);
    try {
      await importLicense(values.licenseCode);
      message.success(t('licenseUI.activate_success'));
      form.resetFields();
      onClose();
      onActivated();
    } catch (e) {
      message.error(e instanceof ApiError && e.message ? e.message : t('licenseUI.activate_failed'));
    } finally {
      setSubmitting(false);
    }
  }, [form, onClose, onActivated, t]);

  return (
    <Modal
      title={t('licenseUI.activate_modal_title')}
      open={open}
      onCancel={onClose}
      footer={null}
      width={600}
      destroyOnHidden
    >
      <Form form={form} onFinish={handleActivate} layout="vertical">
        <Form.Item
          name="licenseCode"
          label={t('licenseUI.code_label')}
          rules={[{ required: true, message: t('licenseUI.code_required') }]}
        >
          <TextArea
            rows={6}
            placeholder={t('licenseUI.code_placeholder')}
            autoSize={{ minRows: 4, maxRows: 10 }}
          />
        </Form.Item>
        <div className="activate-hint">
          <Text type="secondary">
            {t('licenseUI.code_hint')}
          </Text>
        </div>
        <Form.Item>
          <Space>
            <Button type="primary" htmlType="submit" loading={submitting}>
              {t('licenseUI.activate_submit')}
            </Button>
            <Button onClick={onClose}>
              {t('common.cancel')}
            </Button>
          </Space>
        </Form.Item>
      </Form>
    </Modal>
  );
};
