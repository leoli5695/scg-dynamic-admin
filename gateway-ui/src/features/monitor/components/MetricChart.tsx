import React from 'react';
import { Card, Typography } from 'antd';
import { ResponsiveLine } from '@nivo/line';
import type { HistoryPoint } from '../../../types/monitor';

const { Text } = Typography;

/** Palette keyed by color name; falls back to blue for unknown keys. */
const chartColors: Record<string, { main: string; gradient: string[] }> = {
  blue: { main: '#1890ff', gradient: ['#1890ff', '#69c0ff', '#e6f7ff'] },
  green: { main: '#52c41a', gradient: ['#52c41a', '#95de64', '#f6ffed'] },
  purple: { main: '#722ed1', gradient: ['#722ed1', '#b37feb', '#f9f0ff'] },
  orange: { main: '#fa8c16', gradient: ['#fa8c16', '#ffc069', '#fff7e6'] },
  magenta: { main: '#eb2f96', gradient: ['#eb2f96', '#ff85c0', '#fff0f6'] },
  cyan: { main: '#13c2c2', gradient: ['#13c2c2', '#5cdbd3', '#e6fffb'] },
  red: { main: '#ff4d4f', gradient: ['#ff4d4f', '#ff7875', '#fff1f0'] },
};

interface MetricChartProps {
  title: string;
  data: HistoryPoint[];
  colorKey: string;
  unit: string;
  yAxisLabel?: string;
  valueTransform?: (v: number) => number;
}

/** Single-series area line chart over history points (timestamps in ms). */
const MetricChart: React.FC<MetricChartProps> = ({
  title, data, colorKey, unit, yAxisLabel = '', valueTransform,
}) => {
  const color = chartColors[colorKey] || chartColors.blue;

  if (!data || data.length === 0) {
    return (
      <Card styles={{ body: { padding: '16px' } }}>
        <div style={{ height: 200, display: 'flex', alignItems: 'center', justifyContent: 'center' }}>
          <Text type="secondary">No data available</Text>
        </div>
      </Card>
    );
  }

  const chartData = [{
    id: title,
    data: data.map((p) => ({
      x: new Date(p.timestamp),
      y: valueTransform ? valueTransform(p.value) : p.value,
    })),
  }];

  return (
    <Card styles={{ body: { padding: '16px' } }} title={<Text strong style={{ fontSize: 13 }}>{title}</Text>}>
      <div style={{ height: 180 }}>
        <ResponsiveLine
          data={chartData}
          margin={{ top: 10, right: 20, bottom: 40, left: 60 }}
          xScale={{ type: 'time', format: 'native' }}
          yScale={{ type: 'linear', min: 'auto', max: 'auto', stacked: false }}
          curve="monotoneX"
          axisTop={null}
          axisRight={null}
          axisBottom={{
            format: '%H:%M',
            tickValues: 5,
            legend: '',
            legendOffset: 36,
            legendPosition: 'middle',
          }}
          axisLeft={{
            tickSize: 5,
            tickPadding: 8,
            tickRotation: 0,
            legend: yAxisLabel,
            legendOffset: -50,
            legendPosition: 'middle',
            format: (value: number) => {
              const absVal = Math.abs(value);
              if (absVal >= 1000000) return `${(value / 1000000).toFixed(1)}M`;
              if (absVal >= 1000) return `${(value / 1000).toFixed(1)}K`;
              if (absVal >= 1) return value.toFixed(1);
              return value.toFixed(2);
            },
          }}
          enableGridX={false}
          enableGridY
          gridYValues={4}
          colors={color.main}
          lineWidth={2}
          enablePoints={false}
          enableCrosshair
          crosshairType="bottom"
          pointSize={4}
          pointColor={{ theme: 'background' }}
          pointBorderWidth={2}
          pointBorderColor={{ from: 'serieColor' }}
          pointLabelYOffset={-12}
          useMesh
          legends={[]}
          defs={[{
            id: `gradient-${colorKey}`,
            type: 'linearGradient',
            colors: [
              { offset: 0, color: color.gradient[0] },
              { offset: 50, color: color.gradient[1] },
              { offset: 100, color: color.gradient[2] },
            ],
          }]}
          fill={[{ match: '*', id: `gradient-${colorKey}` }]}
          enableArea
          areaOpacity={0.3}
          animate
          motionConfig="gentle"
          tooltip={({ point }) => {
            const yVal = point.data.y as number;
            const trimmedUnit = unit.trim();
            const suffix = trimmedUnit ? ` ${trimmedUnit}` : '';
            let yStr: string;
            if (Math.abs(yVal) >= 1000000) yStr = `${(yVal / 1000000).toFixed(1)}M${suffix}`;
            else if (Math.abs(yVal) >= 1000) yStr = `${(yVal / 1000).toFixed(1)}K${suffix}`;
            else if (Math.abs(yVal) >= 1) yStr = `${yVal.toFixed(2)}${suffix}`;
            else yStr = `${yVal.toFixed(4)}${suffix}`;
            return (
              <div style={{
                background: '#1e293b', padding: '8px 12px', border: '1px solid rgba(148, 163, 184, 0.3)',
                borderRadius: 4, boxShadow: '0 4px 12px rgba(0, 0, 0, 0.5)', whiteSpace: 'nowrap',
              }}
              >
                <div style={{ fontSize: 12, fontWeight: 600, marginBottom: 4, color: '#f1f5f9' }}>
                  {(point.data.x as Date).toLocaleTimeString()}
                </div>
                <div style={{ fontSize: 13, color: color.main }}>{yStr}</div>
              </div>
            );
          }}
        />
      </div>
    </Card>
  );
};

export default MetricChart;
