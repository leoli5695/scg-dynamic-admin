/**
 * One subsystem diagnostic card: status badge, metrics grid, warnings/errors.
 */
import React from 'react';
import { Alert, Card, Descriptions, Space, Tag, Typography } from 'antd';
import { useTranslation } from 'react-i18next';
import type { DiagnosticComponent } from '../../../types/diagnostic';
import { getStatusColor, getStatusIcon } from '../utils';

const { Text } = Typography;

interface ComponentCardProps {
  title: string;
  icon: React.ReactNode;
  diagnostic?: DiagnosticComponent;
}

const ComponentCard: React.FC<ComponentCardProps> = ({ title, icon, diagnostic }) => {
  const { t } = useTranslation();

  if (!diagnostic) {
    return (
      <Card className="diagnostic-card">
        <div style={{ textAlign: 'center', padding: 20 }}>
          <Text type="secondary">{t('diagnostic.no_data')}</Text>
        </div>
      </Card>
    );
  }

  const renderMetricValue = (value: unknown) => {
    if (typeof value === 'boolean') {
      return <Tag color={value ? 'green' : 'default'}>{value ? t('common.yes') : t('common.no')}</Tag>;
    }
    if (typeof value === 'string' && value.startsWith('PASS')) {
      return <Tag color="green">{t('diagnostic.metric.PASS')}</Tag>;
    }
    if (typeof value === 'string' && value.startsWith('FAIL')) {
      return <Tag color="red">{t('diagnostic.metric.FAIL')}</Tag>;
    }
    if (value === null || value === undefined) {
      return <Text>-</Text>;
    }
    return <Text>{String(value)}</Text>;
  };

  return (
    <Card
      className="diagnostic-card"
      title={
        <Space>
          {icon}
          <Text strong>{title}</Text>
        </Space>
      }
      extra={
        <Space>
          {getStatusIcon(diagnostic.status)}
          <Tag color={getStatusColor(diagnostic.status)}>{diagnostic.status}</Tag>
        </Space>
      }
    >
      <Descriptions column={2} size="small">
        {Object.entries(diagnostic.metrics || {}).map(([key, value]) => (
          <Descriptions.Item key={key} label={t(`diagnostic.metric.${key}`) || key}>
            {renderMetricValue(value)}
          </Descriptions.Item>
        ))}
      </Descriptions>

      {diagnostic.warnings && diagnostic.warnings.length > 0 && (
        <div style={{ marginTop: 12 }}>
          <Alert
            type="warning"
            showIcon
            message={t('diagnostic.warnings')}
            description={
              <ul style={{ margin: 0, paddingLeft: 20 }}>
                {diagnostic.warnings.map((w, i) => (
                  <li key={i}>{w}</li>
                ))}
              </ul>
            }
          />
        </div>
      )}

      {diagnostic.errors && diagnostic.errors.length > 0 && (
        <div style={{ marginTop: 12 }}>
          <Alert
            type="error"
            showIcon
            message={t('diagnostic.errors')}
            description={
              <ul style={{ margin: 0, paddingLeft: 20 }}>
                {diagnostic.errors.map((e, i) => (
                  <li key={i}>{e}</li>
                ))}
              </ul>
            }
          />
        </div>
      )}
    </Card>
  );
};

export default ComponentCard;
