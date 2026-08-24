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
      message.success('License 激活成功');
      form.resetFields();
      onClose();
      onActivated();
    } catch (e) {
      message.error(e instanceof ApiError && e.message ? e.message : '激活失败');
    } finally {
      setSubmitting(false);
    }
  }, [form, onClose, onActivated]);

  return (
    <Modal
      title="激活 License"
      open={open}
      onCancel={onClose}
      footer={null}
      width={600}
      destroyOnHidden
    >
      <Form form={form} onFinish={handleActivate} layout="vertical">
        <Form.Item
          name="licenseCode"
          label="License Code"
          rules={[{ required: true, message: '请输入 License Code' }]}
        >
          <TextArea
            rows={6}
            placeholder="粘贴 License Code（Base64 格式）"
            autoSize={{ minRows: 4, maxRows: 10 }}
          />
        </Form.Item>
        <div className="activate-hint">
          <Text type="secondary">
            License Code 格式类似 JetBrains 激活码，为 Base64 编码的 JSON 字串。
            请将完整的 License Code 粘贴到上方输入框中。
          </Text>
        </div>
        <Form.Item>
          <Space>
            <Button type="primary" htmlType="submit" loading={submitting}>
              激活
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
