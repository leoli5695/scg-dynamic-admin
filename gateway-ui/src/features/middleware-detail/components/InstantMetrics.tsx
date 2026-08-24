/**
 * Instant metrics panel — metrics grouped by category as statistic cards.
 */
import React, { useMemo } from 'react';
import {
  Card, Col, Empty, Row, Space, Statistic, Tag, Tooltip
} from 'antd';
import { InfoCircleOutlined } from '@ant-design/icons';
import type { MiddlewareDetailMetric } from '../../../types/middleware';
import { CATEGORY_CONFIG, formatMetricValue, getESHealthTagColor } from '../utils';

interface InstantMetricsProps {
  instant: MiddlewareDetailMetric[] | undefined;
}

const InstantMetrics: React.FC<InstantMetricsProps> = ({ instant }) => {
  const groupedMetrics = useMemo(() => {
    const grouped: Record<string, MiddlewareDetailMetric[]> = {};
    for (const metric of instant ?? []) {
      const category = metric.category || 'other';
      if (!grouped[category]) grouped[category] = [];
      grouped[category].push(metric);
    }
    return grouped;
  }, [instant]);

  if (!instant || instant.length === 0) {
    return <Empty description="暂无即时指标数据" />;
  }

  return (
    <div className="instant-metrics-grid">
      {Object.entries(groupedMetrics).map(([category, metrics]) => {
        const catConfig = CATEGORY_CONFIG[category] || CATEGORY_CONFIG.other;
        return (
          <Card
            key={category}
            className="metrics-category-card"
            title={
              <Space>
                {catConfig.icon}
                <span>{catConfig.label}</span>
                <Tag color="blue">{metrics.length}</Tag>
              </Space>
            }
            style={{ marginBottom: 16 }}
          >
            <Row gutter={[16, 16]}>
              {metrics.map((metric, idx) => {
                const title = (
                  <Tooltip title={metric.name}>
                    <span style={{ cursor: 'help' }}>
                      {metric.displayName}
                      <InfoCircleOutlined style={{ marginLeft: 4, fontSize: 12, color: '#999' }} />
                    </span>
                  </Tooltip>
                );

                // ES 集群健康状态特殊渲染
                if (metric.name === 'elasticsearch_cluster_health_status') {
                  return (
                    <Col key={idx} span={8}>
                      <Card size="small" styles={{ body: { background: '#fafafa' } }}>
                        <Statistic
                          title={title}
                          valueRender={() => (
                            <Tag
                              color={getESHealthTagColor(metric.value)}
                              style={{ fontSize: 16, padding: '4px 12px' }}
                            >
                              {formatMetricValue(metric.value, metric.unit, metric.name)}
                            </Tag>
                          )}
                        />
                      </Card>
                    </Col>
                  );
                }

                return (
                  <Col key={idx} span={8}>
                    <Card size="small" styles={{ body: { background: '#fafafa' } }}>
                      <Statistic
                        title={title}
                        value={formatMetricValue(metric.value, metric.unit, metric.name)}
                        valueStyle={{
                          color: metric.status === 'healthy' ? '#52c41a'
                            : metric.status === 'warning' ? '#faad14' : '#ff4d4f',
                          fontSize: 18,
                        }}
                      />
                    </Card>
                  </Col>
                );
              })}
            </Row>
          </Card>
        );
      })}
    </div>
  );
};

export default InstantMetrics;
