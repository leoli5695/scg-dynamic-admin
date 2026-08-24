/**
 * Waterfall (Gantt-style) visualization of a single trace's filter
 * executions. Ported from the legacy FilterChainPage inline component;
 * bars show self time + downstream time relative to the total duration.
 */
import React, { useState } from 'react';
import { Space, Tag, Tooltip, Typography } from 'antd';
import { CheckCircleOutlined, CloseCircleOutlined } from '@ant-design/icons';
import type { FilterExecution } from '../../../types/filterChain';

const { Text } = Typography;

interface WaterfallChartProps {
  executions: FilterExecution[];
  totalDuration: number;
}

interface TooltipState {
  visible: boolean;
  x: number;
  y: number;
  content: React.ReactNode;
}

/** Convert microseconds to milliseconds with decimal precision. */
const microsToMs = (micros: number | undefined): number => {
  if (micros === undefined || micros === null) return 0;
  return micros / 1000;
};

/** Format a time value for display, picking μs/ms precision as needed. */
const formatTimeDisplay = (micros: number | undefined, ms: number): string => {
  const preciseMs = microsToMs(micros);
  const value = preciseMs > 0 ? preciseMs : ms;
  if (value < 0.01) {
    const microsValue = micros ?? ms * 1000;
    return `${microsValue.toFixed(0)}μs`;
  }
  if (value < 1) {
    return `${value.toFixed(3)}ms`;
  }
  return `${value.toFixed(2)}ms`;
};

/** Color palette by filter category. */
const getFilterColor = (filterName: string, selfTime: number): string => {
  const name = filterName.toLowerCase();
  // Critical filters (auth, security) — red shades
  if (name.includes('auth') || name.includes('security')) {
    return selfTime > 5 ? '#ff7875' : '#ffa39e';
  }
  // Rate limiter — orange
  if (name.includes('rate') || name.includes('limit')) {
    return selfTime > 3 ? '#fa8c16' : '#ffc53d';
  }
  // Routing filters — blue
  if (name.includes('route') || name.includes('load')) {
    return '#1890ff';
  }
  // Transform filters — cyan
  if (name.includes('transform') || name.includes('header')) {
    return '#13c2c2';
  }
  // Cache — purple
  if (name.includes('cache')) {
    return '#722ed1';
  }
  // Default — green (fast) to yellow (medium)
  return selfTime > 10 ? '#faad14' : selfTime > 5 ? '#52c41a' : '#73d13d';
};

const WaterfallChart = React.forwardRef<HTMLDivElement, WaterfallChartProps>(
  ({ executions, totalDuration }, ref) => {
    const [tooltip, setTooltip] = useState<TooltipState>({
      visible: false,
      x: 0,
      y: 0,
      content: null,
    });

    const sortedExecutions = [...executions].sort((a, b) => a.order - b.order);

    // Cumulative start time of each filter (waterfall effect)
    const calculateStartTime = (index: number): number => {
      let startTime = 0;
      for (let i = 0; i < index; i += 1) {
        startTime += microsToMs(sortedExecutions[i].selfTimeMicros ?? (sortedExecutions[i].selfTimeMs ?? 0) * 1000);
      }
      return startTime;
    };

    const timeMarkers = [0, 25, 50, 75, 100];

    return (
      <div ref={ref} data-waterfall-chart style={{ width: '100%', background: '#141414', padding: '8px', borderRadius: '4px' }}>
        {/* Time axis header */}
        <div style={{
          display: 'flex',
          justifyContent: 'space-between',
          padding: '4px 120px 8px 120px',
          fontSize: 10,
          color: 'rgba(255,255,255,0.45)',
          borderBottom: '1px solid rgba(255,255,255,0.1)',
        }}
        >
          {timeMarkers.map((percent) => (
            <Text key={percent} style={{ fontSize: 10, color: 'rgba(255,255,255,0.45)' }}>
              {((totalDuration * percent) / 100).toFixed(1)}ms
            </Text>
          ))}
        </div>

        {/* Waterfall bars */}
        <div className="waterfall-scroll-container" style={{ maxHeight: 350, overflowY: 'auto', overflowX: 'hidden' }}>
          {sortedExecutions.map((exec, idx) => {
            const selfTimeMsPrecise = microsToMs(exec.selfTimeMicros) || (exec.selfTimeMs ?? 0);
            const downstreamTime = exec.downstreamMs ?? 0;
            const startTime = calculateStartTime(idx);

            const startPercent = totalDuration > 0 ? (startTime / totalDuration) * 100 : 0;
            const selfPercent = totalDuration > 0 ? (selfTimeMsPrecise / totalDuration) * 100 : 0;
            const downstreamPercent = totalDuration > 0 ? (downstreamTime / totalDuration) * 100 : 0;

            const filterColor = getFilterColor(exec.filter, selfTimeMsPrecise);
            const isSuccess = exec.success;
            const timeDisplay = formatTimeDisplay(exec.selfTimeMicros, exec.selfTimeMs ?? 0);

            return (
              <div
                key={`${exec.filter}-${idx}`}
                style={{
                  display: 'flex',
                  alignItems: 'center',
                  padding: '6px 0',
                  borderBottom: '1px solid rgba(255,255,255,0.05)',
                  transition: 'background 0.2s',
                }}
                onMouseEnter={(e) => {
                  e.currentTarget.style.background = 'rgba(255,255,255,0.08)';
                }}
                onMouseLeave={(e) => {
                  e.currentTarget.style.background = 'transparent';
                }}
              >
                {/* Filter name column */}
                <div style={{ width: 120, paddingRight: 8, flexShrink: 0 }}>
                  <Tooltip title={`Order: ${exec.order}`}>
                    <Text
                      code
                      style={{
                        fontSize: 11,
                        display: 'block',
                        overflow: 'hidden',
                        textOverflow: 'ellipsis',
                        whiteSpace: 'nowrap',
                      }}
                    >
                      {exec.filter}
                    </Text>
                  </Tooltip>
                </div>

                {/* Waterfall bar column */}
                <div style={{ flex: 1, position: 'relative', height: 20 }}>
                  {/* Background grid lines */}
                  <div style={{
                    position: 'absolute',
                    top: 0,
                    left: 0,
                    right: 0,
                    bottom: 0,
                    display: 'flex',
                    justifyContent: 'space-between',
                    pointerEvents: 'none',
                  }}
                  >
                    {[25, 50, 75].map((p) => (
                      <div key={p} style={{ width: 1, height: '100%', background: 'rgba(255,255,255,0.05)' }} />
                    ))}
                  </div>

                  {/* The actual waterfall bar with mouse-following tooltip */}
                  <div
                    style={{
                      position: 'absolute',
                      left: `${startPercent}%`,
                      width: `${Math.max(selfPercent + downstreamPercent, 0.5)}%`,
                      minWidth: 3,
                      height: 16,
                      top: 2,
                      display: 'flex',
                      borderRadius: 2,
                      overflow: 'hidden',
                      cursor: 'pointer',
                      boxShadow: isSuccess ? 'none' : '0 0 0 1px #ff4d4f',
                    }}
                    onMouseMove={(e) => {
                      setTooltip({
                        visible: true,
                        x: e.clientX + 15,
                        y: e.clientY + 10,
                        content: (
                          <div style={{ fontSize: 12 }}>
                            <div style={{ fontWeight: 600, marginBottom: 4 }}>{exec.filter}</div>
                            <div>自身耗时: {timeDisplay}</div>
                            <div>下游耗时: {downstreamTime.toFixed(2)}ms</div>
                            <div>开始时间: {startTime.toFixed(2)}ms</div>
                            <div>执行顺序: #{idx + 1} (order={exec.order})</div>
                            <div>状态: {isSuccess ? '✅ 成功' : '❌ 失败'}</div>
                          </div>
                        ),
                      });
                    }}
                    onMouseLeave={() => {
                      setTooltip({ visible: false, x: 0, y: 0, content: null });
                    }}
                  >
                    {/* Self time portion */}
                    <div style={{
                      width: selfPercent > 0 ? `${(selfPercent / (selfPercent + downstreamPercent)) * 100}%` : '100%',
                      minWidth: 2,
                      height: '100%',
                      background: filterColor,
                      display: 'flex',
                      alignItems: 'center',
                      justifyContent: 'center',
                      color: 'white',
                      fontSize: 9,
                      fontWeight: 500,
                    }}
                    >
                      {selfPercent > 3 && selfTimeMsPrecise > 0.5 && (
                        <span>{selfTimeMsPrecise.toFixed(1)}ms</span>
                      )}
                    </div>

                    {/* Downstream time portion */}
                    {downstreamPercent > 0 && (
                      <div style={{
                        width: `${(downstreamPercent / (selfPercent + downstreamPercent)) * 100}%`,
                        height: '100%',
                        background: 'rgba(255,255,255,0.15)',
                        borderLeft: '1px solid rgba(255,255,255,0.3)',
                      }}
                      />
                    )}
                  </div>
                </div>

                {/* Time value column */}
                <div style={{ width: 80, textAlign: 'right', flexShrink: 0 }}>
                  <Space size={2}>
                    <Tag
                      color={selfTimeMsPrecise < 1 ? 'green' : selfTimeMsPrecise < 5 ? 'orange' : 'red'}
                      style={{ fontSize: 10, padding: '0 4px' }}
                    >
                      {timeDisplay}
                    </Tag>
                    {isSuccess ? (
                      <CheckCircleOutlined style={{ color: '#52c41a', fontSize: 12 }} />
                    ) : (
                      <CloseCircleOutlined style={{ color: '#ff4d4f', fontSize: 12 }} />
                    )}
                  </Space>
                </div>
              </div>
            );
          })}
        </div>

        {/* Legend */}
        <div style={{
          marginTop: 12,
          padding: '8px 12px',
          background: 'rgba(255,255,255,0.03)',
          borderRadius: 4,
          display: 'flex',
          justifyContent: 'space-between',
          alignItems: 'center',
        }}
        >
          <Space size={16}>
            <Space size={4}>
              <div style={{ width: 14, height: 14, background: '#52c41a', borderRadius: 2 }} />
              <Text type="secondary" style={{ fontSize: 11 }}>自身耗时 (快)</Text>
            </Space>
            <Space size={4}>
              <div style={{ width: 14, height: 14, background: '#faad14', borderRadius: 2 }} />
              <Text type="secondary" style={{ fontSize: 11 }}>自身耗时 (慢)</Text>
            </Space>
            <Space size={4}>
              <div style={{ width: 14, height: 14, background: 'rgba(255,255,255,0.15)', borderRadius: 2 }} />
              <Text type="secondary" style={{ fontSize: 11 }}>下游耗时</Text>
            </Space>
          </Space>
          <Text type="secondary" style={{ fontSize: 11 }}>
            总耗时: {totalDuration.toFixed(2)}ms
          </Text>
        </div>

        {/* Mouse-following tooltip */}
        {tooltip.visible && (
          <div
            style={{
              position: 'fixed',
              left: tooltip.x,
              top: tooltip.y,
              zIndex: 9999,
              background: 'rgba(0, 0, 0, 0.85)',
              color: 'white',
              padding: '8px 12px',
              borderRadius: 6,
              boxShadow: '0 4px 12px rgba(0, 0, 0, 0.3)',
              maxWidth: 280,
              pointerEvents: 'none',
              transition: 'left 0.05s ease-out, top 0.05s ease-out',
            }}
          >
            {tooltip.content}
          </div>
        )}
      </div>
    );
  },
);

WaterfallChart.displayName = 'WaterfallChart';

export default WaterfallChart;
