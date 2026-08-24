/**
 * Health overview panel: score circle + basic info + issue alerts.
 */
import React from 'react';
import {
  Alert, Card, Col, Descriptions, Progress, Row, Select, Tag, Typography
} from 'antd';
import type { MiddlewareHealthStatus } from '../../../types/middleware';
import { getStatusColor, getStatusIcon, type MiddlewareTypeConfig } from '../utils';

const { Text } = Typography;

const TIME_RANGE_OPTIONS = [
  { value: '15m', label: '15 分钟' },
  { value: '30m', label: '30 分钟' },
  { value: '1h', label: '1 小时' },
  { value: '2h', label: '2 小时' },
  { value: '6h', label: '6 小时' },
  { value: '24h', label: '24 小时' },
];

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
  const { overall, issues, score } = healthStatus;
  const statusColor = overall === 'healthy' ? '#52c41a'
    : overall === 'warning' ? '#faad14' : '#ff4d4f';

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
                {overall === 'healthy' ? '健康' : overall === 'warning' ? '警告' : '异常'}
              </Tag>
            </div>
          </div>
        </Col>
        <Col span={18}>
          <Descriptions column={1} size="small">
            <Descriptions.Item label="服务名称">{serviceName}</Descriptions.Item>
            <Descriptions.Item label="中间件类型">
              <Tag color={typeConfig.color} icon={typeConfig.icon}>{typeConfig.label}</Tag>
            </Descriptions.Item>
            <Descriptions.Item label="监控地址">
              <Text copyable>{exporterUrl || '自动匹配'}</Text>
            </Descriptions.Item>
            <Descriptions.Item label="时间范围">
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
              message="发现以下问题"
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
