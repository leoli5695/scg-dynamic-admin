/**
 * Certificate expiry status tag (VALID / EXPIRING_SOON / EXPIRED).
 */
import React from 'react';
import { Tag } from 'antd';
import { CheckCircleOutlined, ClockCircleOutlined, ExclamationCircleOutlined } from '@ant-design/icons';
import { useTranslation } from 'react-i18next';

interface StatusTagProps {
  status?: string;
  daysToExpiry?: number;
}

const StatusTag: React.FC<StatusTagProps> = ({ status, daysToExpiry }) => {
  const { t } = useTranslation();
  switch (status) {
    case 'VALID':
      return <Tag icon={<CheckCircleOutlined />} color="success">{t('cert.status_valid')}</Tag>;
    case 'EXPIRING_SOON':
      return (
        <Tag icon={<ClockCircleOutlined />} color="warning">
          {t('cert.status_expiring')} ({daysToExpiry} {t('cert.days')})
        </Tag>
      );
    case 'EXPIRED':
      return <Tag icon={<ExclamationCircleOutlined />} color="error">{t('cert.status_expired')}</Tag>;
    default:
      return <Tag>{status}</Tag>;
  }
};

export default StatusTag;
