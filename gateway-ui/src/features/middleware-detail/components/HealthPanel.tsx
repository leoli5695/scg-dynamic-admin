/**
 * Health overview panel: score circle + basic info + issue alerts.
 */
import React from 'react';
import { useTranslation } from 'react-i18next';
import {
  Alert, Card, Col, Descriptions, Progress, Row, Select, Tag, Typography
} from 'antd';
import type { MiddlewareHealthStatus } from '../../../types/middleware';
import { getStatusColor, getStatusIcon, type MiddlewareTypeConfig } from '../utils';
import { palette } from '../../../styles/palette';

const { Text } = Typography;

interface HealthPanelProps {
  healthStatus: MiddlewareHealthStatus;
  serviceName: string;
  exporterUrl: string;
  typeConfig: MiddlewareTypeConfig;
  timeRange: string;
  onTimeRangeChange: (value: string) => void;
}

const HealthPanel: React.FC<HealthPanelProps> = ({
  healthStatus, serviceName, exporterUrl, typeConfig, timeRange, onTimeRangeChange,
}) => {
  const { t } = useTranslation();
  // 在组件内构建，保证切换语言时选项文案即时刷新
  const TIME_RANGE_OPTIONS = [
    { value: '15m', label: t('middleware.time_range_15m') },
    { value: '30m', label: t('middleware.time_range_30m') },
    { value: '1h', label: t('middleware.time_range_1h') },
    { value: '2h', label: t('middleware.time_range_2h') },
    { value: '6h', label: t('middleware.time_range_6h') },
    { value: '24h', label: t('middleware.time_range_24h') },
  ];
  const { overall, issues, score } = healthStatus;
  const statusColor = overall === 'healthy' ? palette.success
    : overall === 'warning' ? palette.warning : palette.error;

  return (
    <Card className="health-panel" style={{ marginBottom: 16 }}>
      <Row gutter={24}>
        <Col span={6}>
          <div style={{ textAlign: 'center' }}>
            <Progress
              type="circle"
              percent={score}
              strokeColor={statusColor}
              format={(percent) => (
                <span style={{ fontSize: 24, fontWeight: 'bold', color: statusColor }}>
                  {percent}
                </span>
              )}
            />
            <div style={{ marginTop: 8 }}>
              <Tag color={getStatusColor(overall)} icon={getStatusIcon(overall)}>
                {overall === 'healthy' ? t('middleware.status_healthy') : overall === 'warning' ? t('middleware.status_warning') : t('common.abnormal')}
              </Tag>
            </div>
          </div>
        </Col>
        <Col span={18}>
          <Descriptions column={1} size="small">
            <Descriptions.Item label={t('middleware.service_name')}>{serviceName}</Descriptions.Item>
            <Descriptions.Item label={t('middleware.middleware_type')}>
              <Tag color={typeConfig.color} icon={typeConfig.icon}>{typeConfig.label}</Tag>
            </Descriptions.Item>
            <Descriptions.Item label={t('middleware.monitor_url')}>
              <Text copyable>{exporterUrl || t('middleware.auto_match')}</Text>
            </Descriptions.Item>
            <Descriptions.Item label={t('middleware.time_range')}>
              <Select
                value={timeRange}
                onChange={onTimeRangeChange}
                options={TIME_RANGE_OPTIONS}
                size="small"
                style={{ width: 120 }}
              />
            </Descriptions.Item>
          </Descriptions>

          {issues.length > 0 && (
            <Alert
              type={overall === 'critical' ? 'error' : 'warning'}
              showIcon
              style={{ marginTop: 16 }}
              title={t('middleware.issues_found')}
              description={
                <ul style={{ margin: 0, paddingLeft: 20 }}>
                  {issues.map((issue, idx) => <li key={idx}>{issue}</li>)}
                </ul>
              }
            />
          )}
        </Col>
      </Row>
    </Card>
  );
};

export default HealthPanel;
