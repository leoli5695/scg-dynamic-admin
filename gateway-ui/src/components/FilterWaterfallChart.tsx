import React, { useMemo } from 'react';
import { Tooltip, Tag, Badge, Space, Progress, Typography } from 'antd';
import { CheckCircleOutlined, WarningOutlined, ClockCircleOutlined, FilterOutlined } from '@ant-design/icons';

const { Text } = Typography;
import { useTranslation } from 'react-i18next';

interface FilterExecutionBar {
  filterName: string;
  order: number;
  startTimeOffset: number;   // 相对于请求开始的时间偏移（毫秒）
  selfTimeMs: number;        // Filter 自身逻辑耗时
  totalTimeMs: number;       // 包含下游的总耗时
  downstreamMs: number;      // downstreamTime = totalTime - selfTime
  success: boolean;
  errorMessage?: string;
  timePercentage: number;
}

interface FilterWaterfallChartProps {
  executions: FilterExecutionBar[];
  totalDurationMs: number;
  onFilterClick?: (execution: FilterExecutionBar) => void;
  slowThreshold?: number;  // 慢 Filter 阈值（毫秒），默认 50ms
}

/**
 * Filter 执行时间瀑布图组件
 * 
 * 参考 TracePage.tsx 中 Jaeger Span Timeline 的实现方式
 * 使用纯 CSS/HTML 渲染时间线，区分 selfTime 和 downstreamTime
 */
const FilterWaterfallChart: React.FC<FilterWaterfallChartProps> = ({
  executions,
  totalDurationMs,
  onFilterClick,
  slowThreshold = 50
}) => {
  const { t } = useTranslation();

  // 计算每个 Filter 的健康状态
  const processedExecutions = useMemo(() => {
    return executions.map(exec => {
      let healthStatus: 'normal' | 'warning' | 'error' = 'normal';
      
      if (!exec.success) {
        healthStatus = 'error';
      } else if (exec.selfTimeMs > slowThreshold) {
        healthStatus = 'warning';
      }
      
      return {
        ...exec,
        healthStatus,
        barColor: healthStatus === 'error' ? '#ff4d4f' :
                  healthStatus === 'warning' ? '#faad14' : '#52c41a',
        downstreamColor: '#1890ff'  // 下游时间用蓝色区分
      };
    });
  }, [executions, slowThreshold]);

  // 如果没有数据，显示空状态
  if (!executions || executions.length === 0) {
    return (
      <div style={{ textAlign: 'center', padding: 24, color: '#999' }}>
        <FilterOutlined style={{ fontSize: 24, marginBottom: 8 }} />
        <Text type="secondary">{t('filter_waterfall.no_data')}</Text>
      </div>
    );
  }

  return (
    <div style={{ overflowX: 'auto', minWidth: 600 }}>
      {/* 图例 */}
      <div style={{ marginBottom: 12, display: 'flex', gap: 16, fontSize: 12, alignItems: 'center' }}>
        <span>
          <span style={{ display: 'inline-block', width: 12, height: 12, background: '#52c41a', marginRight: 4, borderRadius: 2 }}></span>
          {t('filter_waterfall.legend_normal')}
        </span>
        <span>
          <span style={{ display: 'inline-block', width: 12, height: 12, background: '#faad14', marginRight: 4, borderRadius: 2 }}></span>
          {t('filter_waterfall.legend_slow')} ({`>${slowThreshold}ms`})
        </span>
        <span>
          <span style={{ display: 'inline-block', width: 12, height: 12, background: '#ff4d4f', marginRight: 4, borderRadius: 2 }}></span>
          {t('filter_waterfall.legend_error')}
        </span>
        <span>
          <span style={{ display: 'inline-block', width: 12, height: 12, background: '#1890ff', marginRight: 4, borderRadius: 2, opacity: 0.5 }}></span>
          {t('filter_waterfall.legend_downstream')}
        </span>
      </div>

      {/* 时间刻度轴 */}
      <div style={{ 
        display: 'flex', 
        justifyContent: 'space-between', 
        marginBottom: 8, 
        fontSize: 11, 
        color: '#666', 
        paddingLeft: 180,
        paddingRight: 80
      }}>
        <span>0ms</span>
        <span>{Math.round(totalDurationMs / 4)}ms</span>
        <span>{Math.round(totalDurationMs / 2)}ms</span>
        <span>{Math.round(totalDurationMs * 3 / 4)}ms</span>
        <span>{totalDurationMs}ms</span>
      </div>

      {/* Filter 时间线 */}
      {processedExecutions.map((exec, index) => {
        const startPercent = totalDurationMs > 0 
          ? (exec.startTimeOffset / totalDurationMs) * 100 
          : 0;
        const totalWidthPercent = totalDurationMs > 0 
          ? Math.max((exec.totalTimeMs / totalDurationMs) * 100, 1) 
          : 1;
        const selfWidthPercent = exec.totalTimeMs > 0 
          ? (exec.selfTimeMs / exec.totalTimeMs) * 100 
          : 100;
        const downstreamWidthPercent = 100 - selfWidthPercent;

        return (
          <div
            key={`${exec.filterName}_${exec.order}_${index}`}
            style={{
              marginBottom: 4,
              display: 'flex',
              alignItems: 'center',
              backgroundColor: exec.healthStatus === 'error' ? 'rgba(255, 77, 79, 0.08)' : 'transparent',
              padding: '4px 0',
              borderRadius: 4,
              cursor: onFilterClick ? 'pointer' : 'default'
            }}
            onClick={() => onFilterClick?.(exec)}
          >
            {/* Filter 名称和顺序 */}
            <div style={{ width: 180, display: 'flex', alignItems: 'center', gap: 8 }}>
              <Badge count={exec.order} style={{ backgroundColor: '#1890ff', fontSize: 10 }} />
              <Tooltip title={exec.filterName}>
                <Tag 
                  color={exec.healthStatus === 'error' ? 'error' : 
                         exec.healthStatus === 'warning' ? 'warning' : 'default'}
                  style={{ fontSize: 11, maxWidth: 120, overflow: 'hidden', textOverflow: 'ellipsis' }}
                >
                  {exec.filterName}
                </Tag>
              </Tooltip>
            </div>

            {/* 时间条 */}
            <div style={{ 
              flex: 1, 
              position: 'relative', 
              height: 24, 
              background: '#fafafa', 
              borderRadius: 2, 
              border: '1px solid #f0f0f0',
              marginRight: 80
            }}>
              <Tooltip title={
                <div>
                  <div><strong>{exec.filterName}</strong></div>
                  <div>{t('filter_waterfall.self_time')}: {exec.selfTimeMs.toFixed(2)}ms</div>
                  <div>{t('filter_waterfall.total_time')}: {exec.totalTimeMs.toFixed(2)}ms</div>
                  <div>{t('filter_waterfall.downstream_time')}: {exec.downstreamMs.toFixed(2)}ms</div>
                  <div>{t('filter_waterfall.percentage')}: {exec.timePercentage?.toFixed(1) || 0}%</div>
                  {exec.errorMessage && <div style={{ color: '#ffa39e' }}>{t('filter_waterfall.error')}: {exec.errorMessage}</div>}
                </div>
              }>
                <div style={{
                  position: 'absolute',
                  left: `${Math.max(0, startPercent)}%`,
                  width: `${totalWidthPercent}%`,
                  height: '100%',
                  borderRadius: 2,
                  display: 'flex',
                  alignItems: 'center',
                  overflow: 'hidden'
                }}>
                  {/* selfTime 部分 */}
                  <div style={{
                    width: `${selfWidthPercent}%`,
                    height: '100%',
                    background: exec.barColor,
                    borderRadius: '2px 0 0 2px',
                    display: 'flex',
                    alignItems: 'center',
                    justifyContent: 'center',
                    fontSize: 10,
                    color: '#fff',
                    overflow: 'hidden',
                    fontWeight: exec.healthStatus === 'error' ? 'bold' : 'normal'
                  }}>
                    {selfWidthPercent > 30 && exec.selfTimeMs > 1 && `${exec.selfTimeMs.toFixed(1)}ms`}
                  </div>
                  
                  {/* downstreamTime 部分 */}
                  {downstreamWidthPercent > 0 && (
                    <div style={{
                      width: `${downstreamWidthPercent}%`,
                      height: '100%',
                      background: exec.downstreamColor,
                      opacity: 0.5,
                      borderRadius: '0 2px 2px 0',
                      display: 'flex',
                      alignItems: 'center',
                      justifyContent: 'center',
                      fontSize: 10,
                      color: '#fff',
                      overflow: 'hidden'
                    }}>
                      {downstreamWidthPercent > 30 && exec.downstreamMs > 1 && `${exec.downstreamMs.toFixed(1)}ms`}
                    </div>
                  )}
                </div>
              </Tooltip>
            </div>

            {/* 状态图标 */}
            <div style={{ width: 80, textAlign: 'center' }}>
              {exec.success ? (
                exec.healthStatus === 'warning' ? (
                  <WarningOutlined style={{ color: '#faad14' }} />
                ) : (
                  <CheckCircleOutlined style={{ color: '#52c41a' }} />
                )
              ) : (
                <WarningOutlined style={{ color: '#ff4d4f' }} />
              )}
            </div>
          </div>
        );
      })}
    </div>
  );
};

export default FilterWaterfallChart;