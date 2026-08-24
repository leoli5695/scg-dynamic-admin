import React from 'react';
import { Card, Col, Row, Space, Spin, Typography } from 'antd';
import { AlertOutlined } from '@ant-design/icons';
import { useTranslation } from 'react-i18next';
import type { AlertThresholdMetrics } from '../../../types/alert';

const { Text } = Typography;

/** Live gateway metrics shown next to the email settings for threshold tuning. */
const CurrentMetricsCard: React.FC<{ metrics?: AlertThresholdMetrics }> = ({ metrics }) => {
  const { t } = useTranslation();

  return (
    <div className="settings-card-wrapper">
      <Card title={<Space><AlertOutlined />{t('alert.current_metrics')}</Space>} className="settings-card metrics-card">
        {metrics ? (
          <Row gutter={[16, 16]}>
            <Col span={12}>
              <div className="metric-item">
                <Text type="secondary">{t('monitor.process_cpu')}</Text>
                <div><Text strong style={{ fontSize: 20 }}>{metrics.processCpuUsage?.toFixed(1)}%</Text></div>
              </div>
            </Col>
            <Col span={12}>
              <div className="metric-item">
                <Text type="secondary">{t('monitor.system_cpu')}</Text>
                <div><Text strong style={{ fontSize: 20 }}>{metrics.systemCpuUsage?.toFixed(1)}%</Text></div>
              </div>
            </Col>
            <Col span={12}>
              <div className="metric-item">
                <Text type="secondary">{t('monitor.heap_usage')}</Text>
                <div><Text strong style={{ fontSize: 20 }}>{metrics.heapUsagePercent?.toFixed(1)}%</Text></div>
              </div>
            </Col>
            <Col span={12}>
              <div className="metric-item">
                <Text type="secondary">{t('monitor.error_rate')}</Text>
                <div><Text strong style={{ fontSize: 20 }}>{metrics.errorRate?.toFixed(2)}%</Text></div>
              </div>
            </Col>
            <Col span={12}>
              <div className="metric-item">
                <Text type="secondary">{t('alert.unhealthy_instances')}</Text>
                <div>
                  <Text strong style={{ fontSize: 20 }}>
                    {metrics.unhealthyInstances ?? 0}
                    /
                    {metrics.totalInstances ?? 0}
                  </Text>
                </div>
              </div>
            </Col>
            <Col span={12}>
              <div className="metric-item">
                <Text type="secondary">{t('alert.thread_usage')}</Text>
                <div><Text strong style={{ fontSize: 20 }}>{metrics.threadUsagePercent?.toFixed(1)}%</Text></div>
              </div>
            </Col>
          </Row>
        ) : <Spin />}
      </Card>
    </div>
  );
};

export default CurrentMetricsCard;
