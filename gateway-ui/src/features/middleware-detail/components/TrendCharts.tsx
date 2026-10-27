/**
 * Trend charts panel — per-type time series charts, with the RocketMQ
 * Console API special case (no history, realtime consumer group lag table).
 */
import React from 'react';
import {
  Alert, Card, Col, Descriptions, Empty, Row, Space, Table, Tag
} from 'antd';
import { LineChartOutlined } from '@ant-design/icons';
import ReactECharts from 'echarts-for-react';
import type { MiddlewareTrend } from '../../../types/middleware';
import { buildTrendChartOption, formatMetricValue, type TrendItem } from '../utils';

interface TrendChartsProps {
  trendItems: TrendItem[];
  middlewareType: string;
  trend: MiddlewareTrend | undefined;
}

const TrendCharts: React.FC<TrendChartsProps> = ({ trendItems, middlewareType, trend }) => {
  // RocketMQ Console API 特殊处理：不支持历史数据，显示实时消费组信息
  if (middlewareType.toLowerCase() === 'rocketmq' && trend?.note) {
    const consumerGroups = trend.consumerGroups ?? [];
    return (
      <div>
        <Alert
          type="info"
          showIcon
          message="RocketMQ Console API 不支持历史趋势数据"
          description="Console API 仅提供实时数据，无法获取历史时间序列。下方显示当前各消费组的实时堆积情况。"
          style={{ marginBottom: 16 }}
        />
        {consumerGroups.length > 0 ? (
          <Table scroll={{ x: 'max-content' }}
            dataSource={consumerGroups}
            rowKey="group"
            pagination={false}
            size="small"
            columns={[
              { title: '消费组', dataIndex: 'group', key: 'group' },
              {
                title: '消息堆积',
                dataIndex: 'diffTotal',
                key: 'diffTotal',
                render: (val: number) => (
                  <span style={{ color: val > 100 ? '#ff4d4f' : val > 0 ? '#faad14' : '#52c41a' }}>
                    {val || 0}
                  </span>
                ),
              },
              {
                title: '消费 TPS',
                dataIndex: 'consumeTps',
                key: 'consumeTps',
                render: (val?: number) => (val ? `${val.toFixed(2)}/s` : '-'),
              },
              { title: '消费者数', dataIndex: 'count', key: 'count' },
            ]}
          />
        ) : (
          <Empty description="暂无消费组数据" />
        )}
      </div>
    );
  }

  if (trendItems.length === 0) {
    return <Empty description="暂无趋势数据" />;
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
                  <Tag color="blue">
                    平均: {formatMetricValue(item.summary.avg, item.unit)}
                  </Tag>
                ) : null}
              </Space>
            }
            size="small"
          >
            <ReactECharts
              option={buildTrendChartOption(item.seriesName, item.data, item.unit)}
              style={{ height: 200 }}
              notMerge
            />
            {item.summary && (
              <Descriptions column={4} size="small" style={{ marginTop: 8 }}>
                <Descriptions.Item label="最小">{formatMetricValue(item.summary.min, item.unit)}</Descriptions.Item>
                <Descriptions.Item label="最大">{formatMetricValue(item.summary.max, item.unit)}</Descriptions.Item>
                <Descriptions.Item label="平均">{formatMetricValue(item.summary.avg, item.unit)}</Descriptions.Item>
                <Descriptions.Item label="当前">{formatMetricValue(item.summary.last, item.unit)}</Descriptions.Item>
              </Descriptions>
            )}
          </Card>
        </Col>
      ))}
    </Row>
  );
};

export default TrendCharts;
