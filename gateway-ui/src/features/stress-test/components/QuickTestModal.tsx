/**
 * Quick test modal — fire a small test against the instance with
 * only request count and concurrency.
 */
import React, { useState } from 'react';
import { Form, InputNumber, Modal, Space, Typography } from 'antd';
import { ThunderboltOutlined } from '@ant-design/icons';
import { useTranslation } from 'react-i18next';

const { Paragraph } = Typography;

interface Props {
  open: boolean;
  onClose: () => void;
  onStart: (requests: number, concurrent: number) => void;
}

const QuickTestModal: React.FC<Props> = ({ open, onClose, onStart }) => {
  const { t } = useTranslation();
  const [requests, setRequests] = useState(100);
  const [concurrent, setConcurrent] = useState(10);

  return (
    <Modal className="console-dialog"
      title={
        <Space>
          <ThunderboltOutlined style={{ color: '#165DFF' }} />
          {t('stress_test.quick_test_title')}
        </Space>
      }
      open={open}
      onCancel={onClose}
      onOk={() => onStart(requests, concurrent)}
      okText={t('stress_test.start_quick_test')}
    >
      <Paragraph style={{ color: 'rgba(255,255,255,0.65)' }}>
        {t('stress_test.quick_test_desc')}
      </Paragraph>

      <Form layout="vertical">
        <Form.Item label={t('stress_test.num_requests')}>
          <InputNumber
            value={requests}
            onChange={(value) => setRequests(value || 100)}
            min={10}
            max={10000}
            style={{ width: '100%' }}
          />
        </Form.Item>

        <Form.Item label={t('stress_test.concurrent_users')}>
          <InputNumber
            value={concurrent}
            onChange={(value) => setConcurrent(value || 10)}
            min={1}
            max={50}
            style={{ width: '100%' }}
          />
        </Form.Item>
      </Form>
    </Modal>
  );
};

export default QuickTestModal;
