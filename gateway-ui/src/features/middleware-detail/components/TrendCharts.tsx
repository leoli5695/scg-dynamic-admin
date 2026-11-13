/**
 * Trend charts panel — per-type time series charts, with the RocketMQ
 * Console API special case (no history, realtime consumer group lag table).
 */
import React from 'react';
import { useTranslation } from 'react-i18next';
import {
  Alert, Card, Col, Descriptions, Empty, Row, Space, Table, Tag
} from 'antd';
import { LineChartOutlined } from '@ant-design/icons';
import ReactECharts from 'echarts-for-react';
import type { MiddlewareTrend } from '../../../types/middleware';
import { buildTrendChartOption, formatMetricValue, type TrendItem } from '../utils';
import { palette } from '../../../styles/palette';

interface TrendChartsProps {
  trendItems: TrendItem[];
  middlewareType: string;
  trend: MiddlewareTrend | undefined;
}

const TrendCharts: React.FC<TrendChartsProps> = ({ trendItems, middlewareType, trend }) => {
  const { t } = useTranslation();
  // RocketMQ Console API 特殊处理：不支持历史数据，显示实时消费组信息
  if (middlewareType.toLowerCase() === 'rocketmq' && trend?.note) {
    const consumerGroups = trend.consumerGroups ?? [];
    return (
      <div>
        <Alert
          type="info"
          showIcon
          title={t('middleware.rocketmq_no_history_title')}
          description={t('middleware.rocketmq_no_history_desc')}
          style={{ marginBottom: 16 }}
        />
        {consumerGroups.length > 0 ? (
          <Table scroll={{ x: 'max-content' }}
            dataSource={consumerGroups}
            rowKey="group"
            pagination={false}
            size="small"
            columns={[
              { title: t('middleware.consumer_group'), dataIndex: 'group', key: 'group' },
              {
                title: t('middleware.message_lag'),
                dataIndex: 'diffTotal',
                key: 'diffTotal',
                render: (val: number) => (
                  <span style={{ color: val > 100 ? palette.error : val > 0 ? palette.warning : palette.success }}>
                    {val || 0}
                  </span>
                ),
              },
              {
                title: t('middleware.consume_tps'),
                dataIndex: 'consumeTps',
                key: 'consumeTps',
                render: (val?: number) => (val ? `${val.toFixed(2)}/s` : '-'),
              },
              { title: t('middleware.consumer_count'), dataIndex: 'count', key: 'count' },
            ]}
          />
        ) : (
          <Empty description={t('middleware.no_consumer_groups')} />
        )}
      </div>
    );
  }

  if (trendItems.length === 0) {
    return <Empty description={t('middleware.no_trend_data')} />;
  }

  return (
    <Row gutter={[16, 16]}>
      {trendItems.map((item) => (
        <Col key={item.key} span={12}>
          <Card
            title={
              <Space>
                <LineChartOutlined />
                <span>{item.label}</span>
                {item.summary?.avg ? (
                  <Tag color={palette.primary}>
                    {t('middleware.summary_avg')}: {formatMetricValue(item.summary.avg, item.unit)}
                  </Tag>
                ) : null}
              </Space>
            }
            size="small"
          >
            <ReactECharts
              option={buildTrendChartOption(t, item.seriesName, item.data, item.unit)}
              style={{ height: 200 }}
              notMerge
            />
            {item.summary && (
              <Descriptions column={4} size="small" style={{ marginTop: 8 }}>
                <Descriptions.Item label={t('middleware.summary_min')}>{formatMetricValue(item.summary.min, item.unit)}</Descriptions.Item>
                <Descriptions.Item label={t('middleware.summary_max')}>{formatMetricValue(item.summary.max, item.unit)}</Descriptions.Item>
                <Descriptions.Item label={t('middleware.summary_avg')}>{formatMetricValue(item.summary.avg, item.unit)}</Descriptions.Item>
                <Descriptions.Item label={t('middleware.summary_last')}>{formatMetricValue(item.summary.last, item.unit)}</Descriptions.Item>
              </Descriptions>
            )}
          </Card>
        </Col>
      ))}
    </Row>
  );
};

export default TrendCharts;
