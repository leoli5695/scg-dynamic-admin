/**
 * Real-time charts for a running stress test (RPS / response time /
 * error rate / cumulative requests).
 */
import React from 'react';
import ReactECharts from 'echarts-for-react';
import { Card, Row, Col } from 'antd';
import type { StressTestMetrics } from '../../../types/stressTest';
import { palette, withAlpha } from '../../../styles/palette';
import { chartCategoryAxis, chartLegend, chartTitle, chartTooltip, chartValueAxis } from '../../../styles/chartTheme';

interface Props {
  metrics: StressTestMetrics | null;
}

const chartCardStyle: React.CSSProperties = { background: palette.bgCard };

const axisCommon = {
  tooltip: { trigger: 'axis' as const, ...chartTooltip },
  xAxis: {
    ...chartCategoryAxis,
    axisLabel: { ...chartCategoryAxis.axisLabel, rotate: 45 },
  },
  yAxis: chartValueAxis,
};

const titleStyle = chartTitle;

const StressTestCharts: React.FC<Props> = ({ metrics }) => {
  if (!metrics || !metrics.timeline || metrics.timeline.length === 0) {
    return null;
  }

  const formatTime = (timestamp: number) => {
    const date = new Date(timestamp);
    return date.toLocaleTimeString('en-US', { hour12: false, hour: '2-digit', minute: '2-digit', second: '2-digit' });
  };

  const timeLabels = metrics.timeline.map((p) => formatTime(p.timestamp));

  // RPS Chart
  const rpsOption = {
    title: { text: 'Requests Per Second', left: 'center', textStyle: titleStyle },
    tooltip: { ...axisCommon.tooltip, borderColor: palette.primary },
    grid: { left: '3%', right: '4%', bottom: '3%', top: '15%', containLabel: true },
    xAxis: { type: 'category' as const, data: timeLabels, ...axisCommon.xAxis },
    yAxis: { type: 'value' as const, name: 'RPS', ...axisCommon.yAxis },
    series: [{
      name: 'RPS',
      type: 'line',
      smooth: true,
      data: metrics.timeline.map((p) => p.rps.toFixed(2)),
      itemStyle: { color: palette.success },
      areaStyle: {
        color: {
          type: 'linear',
          x: 0, y: 0, x2: 0, y2: 1,
          colorStops: [
            { offset: 0, color: 'rgba(61, 220, 151, 0.3)' },
            { offset: 1, color: 'rgba(61, 220, 151, 0.05)' },
          ],
        },
      },
    }],
  };

  // Response Time Chart
  const rtOption = {
    title: { text: 'Response Time (ms)', left: 'center', textStyle: titleStyle },
    tooltip: { ...axisCommon.tooltip, borderColor: palette.primary },
    legend: {
      data: ['Avg', 'P95', 'P99'],
      top: 25,
      textStyle: chartLegend.textStyle,
    },
    grid: { left: '3%', right: '4%', bottom: '3%', top: '20%', containLabel: true },
    xAxis: { type: 'category' as const, data: timeLabels, ...axisCommon.xAxis },
    yAxis: { type: 'value' as const, name: 'ms', ...axisCommon.yAxis },
    series: [
      {
        name: 'Avg',
        type: 'line',
        smooth: true,
        data: metrics.timeline.map((p) => p.avgResponseTime.toFixed(2)),
        itemStyle: { color: palette.primary },
      },
      {
        name: 'P95',
        type: 'line',
        smooth: true,
        data: metrics.timeline.map((p) => p.p95ResponseTime.toFixed(2)),
        itemStyle: { color: palette.warning },
      },
      {
        name: 'P99',
        type: 'line',
        smooth: true,
        data: metrics.timeline.map((p) => p.p99ResponseTime.toFixed(2)),
        itemStyle: { color: palette.error },
      },
    ],
  };

  // Error Rate Chart
  const errorOption = {
    title: { text: 'Error Rate (%)', left: 'center', textStyle: titleStyle },
    tooltip: {
      ...axisCommon.tooltip,
      borderColor: palette.error,
      formatter: (params: unknown) => {
        const point = (params as Array<{ name: string; value: string }>)[0];
        return `${point.name}<br/>Error Rate: ${point.value}%`;
      },
    },
    grid: { left: '3%', right: '4%', bottom: '3%', top: '15%', containLabel: true },
    xAxis: { type: 'category' as const, data: timeLabels, ...axisCommon.xAxis },
    yAxis: { type: 'value' as const, name: '%', max: 100, ...axisCommon.yAxis },
    series: [{
      name: 'Error Rate',
      type: 'line',
      smooth: true,
      data: metrics.timeline.map((p) => p.errorRate.toFixed(2)),
      itemStyle: {
        color: (params: { value?: string }) => Number(params.value) > 5 ? palette.error : palette.success,
      },
      areaStyle: {
        color: {
          type: 'linear',
          x: 0, y: 0, x2: 0, y2: 1,
          colorStops: [
            { offset: 0, color: withAlpha(palette.error, 0.3) },
            { offset: 1, color: withAlpha(palette.error, 0.05) },
          ],
        },
      },
      markLine: {
        silent: true,
        data: [{ yAxis: 5, label: { formatter: 'Warning: 5%' } }],
        lineStyle: { color: palette.warning, type: 'dashed' },
      },
    }],
  };

  // Cumulative Requests Chart
  const cumulativeOption = {
    title: { text: 'Cumulative Requests', left: 'center', textStyle: titleStyle },
    tooltip: { ...axisCommon.tooltip, borderColor: palette.primary },
    legend: {
      data: ['Total', 'Success', 'Failed'],
      top: 25,
      textStyle: chartLegend.textStyle,
    },
    grid: { left: '3%', right: '4%', bottom: '3%', top: '20%', containLabel: true },
    xAxis: { type: 'category' as const, data: timeLabels, ...axisCommon.xAxis },
    yAxis: { type: 'value' as const, ...axisCommon.yAxis },
    series: [
      {
        name: 'Total',
        type: 'line',
        smooth: true,
        data: metrics.timeline.map((p) => p.totalRequests),
        itemStyle: { color: palette.primary },
      },
      {
        name: 'Success',
        type: 'line',
        smooth: true,
        data: metrics.timeline.map((p) => p.successRequests),
        itemStyle: { color: palette.success },
      },
      {
        name: 'Failed',
        type: 'line',
        smooth: true,
        data: metrics.timeline.map((p) => p.failedRequests),
        itemStyle: { color: palette.error },
      },
    ],
  };

  return (
    <div style={{ marginTop: 24 }}>
      <Row gutter={[16, 16]}>
        <Col span={24}>
          <Card style={chartCardStyle}>
            <ReactECharts option={rpsOption} style={{ height: 300 }} />
          </Card>
        </Col>
        <Col span={24}>
          <Card style={chartCardStyle}>
            <ReactECharts option={rtOption} style={{ height: 300 }} />
          </Card>
        </Col>
        <Col xs={24} lg={12}>
          <Card style={chartCardStyle}>
            <ReactECharts option={errorOption} style={{ height: 300 }} />
          </Card>
        </Col>
        <Col xs={24} lg={12}>
          <Card style={chartCardStyle}>
            <ReactECharts option={cumulativeOption} style={{ height: 300 }} />
          </Card>
        </Col>
      </Row>
    </div>
  );
};

export default StressTestCharts;
