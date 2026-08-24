/**
 * Real-time charts for a running stress test (RPS / response time /
 * error rate / cumulative requests).
 */
import React from 'react';
import ReactECharts from 'echarts-for-react';
import { Card, Row, Col } from 'antd';
import type { StressTestMetrics } from '../../../types/stressTest';

interface Props {
  metrics: StressTestMetrics | null;
}

const chartCardStyle: React.CSSProperties = { background: 'rgba(255,255,255,0.05)' };

const axisCommon = {
  tooltip: {
    trigger: 'axis' as const,
    backgroundColor: 'rgba(0,0,0,0.8)',
    textStyle: { color: '#fff' },
  },
  xAxis: {
    axisLine: { lineStyle: { color: 'rgba(255,255,255,0.3)' } },
    axisLabel: { color: 'rgba(255,255,255,0.7)', rotate: 45 },
  },
  yAxis: {
    axisLine: { lineStyle: { color: 'rgba(255,255,255,0.3)' } },
    axisLabel: { color: 'rgba(255,255,255,0.7)' },
    splitLine: { lineStyle: { color: 'rgba(255,255,255,0.1)' } },
  },
};

const titleStyle = { color: '#fff', fontSize: 14 };

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
    tooltip: { ...axisCommon.tooltip, borderColor: '#165DFF' },
    grid: { left: '3%', right: '4%', bottom: '3%', top: '15%', containLabel: true },
    xAxis: { type: 'category' as const, data: timeLabels, ...axisCommon.xAxis },
    yAxis: { type: 'value' as const, name: 'RPS', ...axisCommon.yAxis },
    series: [{
      name: 'RPS',
      type: 'line',
      smooth: true,
      data: metrics.timeline.map((p) => p.rps.toFixed(2)),
      itemStyle: { color: '#52c41a' },
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
    }],
  };

  // Response Time Chart
  const rtOption = {
    title: { text: 'Response Time (ms)', left: 'center', textStyle: titleStyle },
    tooltip: { ...axisCommon.tooltip, borderColor: '#165DFF' },
    legend: {
      data: ['Avg', 'P95', 'P99'],
      top: 25,
      textStyle: { color: 'rgba(255,255,255,0.7)' },
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
        itemStyle: { color: '#165DFF' },
      },
      {
        name: 'P95',
        type: 'line',
        smooth: true,
        data: metrics.timeline.map((p) => p.p95ResponseTime.toFixed(2)),
        itemStyle: { color: '#faad14' },
      },
      {
        name: 'P99',
        type: 'line',
        smooth: true,
        data: metrics.timeline.map((p) => p.p99ResponseTime.toFixed(2)),
        itemStyle: { color: '#f5222d' },
      },
    ],
  };

  // Error Rate Chart
  const errorOption = {
    title: { text: 'Error Rate (%)', left: 'center', textStyle: titleStyle },
    tooltip: {
      ...axisCommon.tooltip,
      borderColor: '#f5222d',
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
        color: (params: { value?: string }) => Number(params.value) > 5 ? '#f5222d' : '#52c41a',
      },
      areaStyle: {
        color: {
          type: 'linear',
          x: 0, y: 0, x2: 0, y2: 1,
          colorStops: [
            { offset: 0, color: 'rgba(245, 34, 45, 0.3)' },
            { offset: 1, color: 'rgba(245, 34, 45, 0.05)' },
          ],
        },
      },
      markLine: {
        silent: true,
        data: [{ yAxis: 5, label: { formatter: 'Warning: 5%' } }],
        lineStyle: { color: '#faad14', type: 'dashed' },
      },
    }],
  };

  // Cumulative Requests Chart
  const cumulativeOption = {
    title: { text: 'Cumulative Requests', left: 'center', textStyle: titleStyle },
    tooltip: { ...axisCommon.tooltip, borderColor: '#165DFF' },
    legend: {
      data: ['Total', 'Success', 'Failed'],
      top: 25,
      textStyle: { color: 'rgba(255,255,255,0.7)' },
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
        itemStyle: { color: '#165DFF' },
      },
      {
        name: 'Success',
        type: 'line',
        smooth: true,
        data: metrics.timeline.map((p) => p.successRequests),
        itemStyle: { color: '#52c41a' },
      },
      {
        name: 'Failed',
        type: 'line',
        smooth: true,
        data: metrics.timeline.map((p) => p.failedRequests),
        itemStyle: { color: '#f5222d' },
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
