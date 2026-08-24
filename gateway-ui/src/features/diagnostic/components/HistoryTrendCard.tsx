/**
 * History trend card: score trend + gateway metric charts and the
 * compare-with-previous summary. Hidden when there is no trend data
 * (same behaviour as the legacy page).
 */
import React, { useState } from 'react';
import {
  Alert, Card, Col, Divider, Row, Select, Space, Statistic, Tabs, Typography
} from 'antd';
import { LineChartOutlined } from '@ant-design/icons';
import ReactECharts from 'echarts-for-react';
import { useTranslation } from 'react-i18next';
import { useRequest } from '../../../hooks/useRequest';
import { compareWithPrevious, getDiagnosticHistory } from '../../../api/diagnostic';
import type { DiagnosticReport } from '../../../types/diagnostic';
import { getScoreColor } from '../utils';

const { Text } = Typography;

interface HistoryTrendCardProps {
  instanceId: string;
  report: DiagnosticReport | null;
  /** Bumped after each diagnostic run so history + comparison refresh. */
  reloadKey: number;
}

const HistoryTrendCard: React.FC<HistoryTrendCardProps> = ({ instanceId, report, reloadKey }) => {
  const { t } = useTranslation();
  const [hours, setHours] = useState(24);

  const historyRequest = useRequest(
    (signal) => getDiagnosticHistory({ hours, instanceId }, { signal }),
    { deps: [instanceId, hours, reloadKey] },
  );
  const comparisonRequest = useRequest(
    (signal) => compareWithPrevious({ signal }),
    { deps: [reloadKey] },
  );

  const history = historyRequest.data?.history ?? [];
  const trend = historyRequest.data?.trend;
  const comparison = comparisonRequest.data;

  if (!trend || !trend.timestamps || trend.timestamps.length === 0) {
    return null;
  }

  const scoreTrendOption = {
    title: {
      text: t('diagnostic.score_trend') || '健康评分趋势',
      left: 'center',
      textStyle: { fontSize: 14 },
    },
    tooltip: {
      trigger: 'axis',
      formatter: (params: Array<{ value: [string, number] }>) => {
        const date = new Date(params[0].value[0]).toLocaleString();
        const score = params[0].value[1];
        return `${date}<br/>评分: ${score}`;
      },
    },
    xAxis: {
      type: 'time',
      axisLabel: {
        formatter: (value: number) => new Date(value).toLocaleTimeString(),
      },
    },
    yAxis: {
      type: 'value',
      min: 0,
      max: 100,
      axisLabel: { formatter: '{value}' },
    },
    series: [{
      type: 'line',
      data: trend.timestamps.map((ts, i) => [ts, trend.scores[i]]),
      smooth: true,
      lineStyle: { width: 2 },
      areaStyle: {
        color: {
          type: 'linear',
          x: 0, y: 0, x2: 0, y2: 1,
          colorStops: [
            { offset: 0, color: 'rgba(82, 196, 26, 0.3)' },
            { offset: 1, color: 'rgba(82, 196, 26, 0.05)' },
          ],
        },
      },
      markLine: {
        silent: true,
        data: [
          { yAxis: 80, lineStyle: { color: '#52c41a' }, label: { formatter: '健康线' } },
          { yAxis: 50, lineStyle: { color: '#faad14' }, label: { formatter: '警告线' } },
        ],
      },
    }],
    grid: { left: 50, right: 20, top: 40, bottom: 30 },
  };

  const gatewayMetricsTab = () => {
    // Historical metric samples collected with previous diagnostics
    const hasValidData = history.some((h) => (h.gatewayQps && h.gatewayQps > 0)
      || (h.gatewayErrorRate && h.gatewayErrorRate > 0)
      || (h.gatewayHeapUsagePercent && h.gatewayHeapUsagePercent > 0));
    // Live metrics inside the current report
    const hasRealTimeData = report?.performance?.metrics?.prometheusAvailable === true;

    if (!hasValidData && !hasRealTimeData) {
      return (
        <div style={{ textAlign: 'center', padding: 40 }}>
          <Alert
            type="info"
            showIcon
            message={t('diagnostic.no_gateway_metrics_title') || '暂无网关指标数据'}
            description={
              <div>
                <p>{t('diagnostic.no_gateway_metrics_desc') || '请点击"完整诊断"按钮以获取网关实时指标（QPS、错误率、延迟等）'}</p>
                <p style={{ marginTop: 8, color: '#8c8c8c' }}>
                  {t('diagnostic.prometheus_hint') || '提示：网关指标需要 Prometheus 或直接从网关实例获取'}
                </p>
              </div>
            }
          />
        </div>
      );
    }

    return (
      <ReactECharts
        option={{
          tooltip: { trigger: 'axis' },
          legend: {
            data: ['QPS', '错误率(%)', '堆内存(%)'],
            bottom: 0,
          },
          xAxis: {
            type: 'time',
            data: history.map((h) => new Date(h.createdAt).getTime()),
            axisLabel: {
              formatter: (value: number) => new Date(value).toLocaleTimeString(),
            },
          },
          yAxis: { type: 'value' },
          series: [
            {
              name: 'QPS',
              type: 'line',
              data: history.map((h) => h.gatewayQps || 0),
              smooth: true,
            },
            {
              name: '错误率(%)',
              type: 'line',
              data: history.map((h) => h.gatewayErrorRate || 0),
              smooth: true,
              lineStyle: { color: '#ff4d4f' },
            },
            {
              name: '堆内存(%)',
              type: 'line',
              data: history.map((h) => h.gatewayHeapUsagePercent || 0),
              smooth: true,
              lineStyle: { color: '#1890ff' },
            },
          ],
          grid: { left: 60, right: 20, top: 20, bottom: 50 },
        }}
        style={{ height: 300 }}
        notMerge
      />
    );
  };

  return (
    <Card
      style={{ marginTop: 16 }}
      title={
        <Space>
          <LineChartOutlined />
          <Text strong>{t('diagnostic.history_trend') || '历史趋势'}</Text>
          <Select
            value={hours}
            onChange={setHours}
            style={{ width: 120, marginLeft: 16 }}
            options={[
              { value: 1, label: '最近1小时' },
              { value: 6, label: '最近6小时' },
              { value: 12, label: '最近12小时' },
              { value: 24, label: '最近24小时' },
              { value: 72, label: '最近3天' },
            ]}
          />
        </Space>
      }
    >
      <Tabs
        items={[
          {
            key: 'score',
            label: t('diagnostic.score_trend') || '健康评分',
            children: (
              <ReactECharts
                option={scoreTrendOption}
                style={{ height: 300 }}
                notMerge
              />
            ),
          },
          {
            key: 'metrics',
            label: t('diagnostic.gateway_metrics') || '网关指标',
            children: gatewayMetricsTab(),
          },
        ]}
      />

      {/* Comparison with previous */}
      {comparison && comparison.previousScore !== undefined && (
        <>
          <Divider />
          <Row gutter={16}>
            <Col span={6}>
              <Statistic
                title={t('diagnostic.previous_score') || '上次评分'}
                value={comparison.previousScore}
                suffix={comparison.previousScore >= 80 ? '(健康)' : comparison.previousScore >= 50 ? '(警告)' : '(危险)'}
                valueStyle={{ color: getScoreColor(comparison.previousScore) }}
              />
            </Col>
            <Col span={6}>
              <Statistic
                title={t('diagnostic.score_change') || '评分变化'}
                value={comparison.scoreChange || 0}
                prefix={(comparison.scoreChange ?? 0) > 0 ? '+' : ''}
                valueStyle={{ color: (comparison.scoreChange ?? 0) > 0 ? '#52c41a' : (comparison.scoreChange ?? 0) < 0 ? '#ff4d4f' : '#8c8c8c' }}
              />
            </Col>
            <Col span={12}>
              <Alert
                type={(comparison.scoreChange ?? 0) > 0 ? 'success' : (comparison.scoreChange ?? 0) < -5 ? 'warning' : 'info'}
                message={'系统健康度保持稳定'}
                showIcon
              />
            </Col>
          </Row>
        </>
      )}
    </Card>
  );
};

export default HistoryTrendCard;
