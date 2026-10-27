/**
 * AI analysis modal — renders the markdown analysis of a finished test.
 */
import React from 'react';
import { Alert, Button, Modal, Space, Spin } from 'antd';
import { RobotOutlined } from '@ant-design/icons';
import { useTranslation } from 'react-i18next';
import AiReportRenderer from '../../../components/ai/AiReportRenderer';
import '../../../styles/ai-report.css';

interface Props {
  open: boolean;
  testId: number | null;
  loading: boolean;
  content: string;
  onClose: () => void;
}

const AnalysisModal: React.FC<Props> = ({ open, testId, loading, content, onClose }) => {
  const { t } = useTranslation();

  return (
    <Modal className="console-dialog"
      title={
        <Space>
          <RobotOutlined style={{ color: '#52c41a' }} />
          {t('stress_test.ai_analysis_title')} - Test #{testId}
        </Space>
      }
      open={open}
      onCancel={onClose}
      width={700}
      footer={[
        <Button key="close" onClick={onClose}>
          {t('common.cancel')}
        </Button>,
      ]}
    >
      <Spin spinning={loading}>
        {content ? (
          <div style={{ color: 'rgba(255,255,255,0.85)' }}>
            <AiReportRenderer content={content} />
          </div>
        ) : (
          <Alert type="info" message={t('stress_test.waiting_analysis')} showIcon />
        )}
      </Spin>
    </Modal>
  );
};

export default AnalysisModal;
