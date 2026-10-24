import React, { useMemo } from 'react';
import { Tooltip, Tag, Badge, Typography } from 'antd';
import {
  CheckCircleOutlined, WarningOutlined, FilterOutlined,
  CloudServerOutlined, DatabaseOutlined, ApiOutlined,
  ThunderboltOutlined, CodeOutlined, BranchesOutlined
} from '@ant-design/icons';

const { Text } = Typography;
import { useTranslation } from 'react-i18next';

// Span 类型枚举 (对应后端 SpanType.java)
type SpanType = 'SERVICE' | 'REDIS' | 'DB' | 'MQ' | 'HTTP' | 'INTERNAL' | 'FILTER';

// Span Kind 枚举 (对应后端 SpanKind.java)
type SpanKind = 'CLIENT' | 'SERVER' | 'PRODUCER' | 'CONSUMER' | 'INTERNAL';

// Span 数据结构 (对应后端 ServiceSpan.java)
interface SpanNode {
  spanId?: string;
  parentSpanId?: string;
  operation: string;
  startTimeMs?: number;
  durationMs: number;
  spanType?: SpanType;
  kind?: SpanKind;
  tags?: Record<string, string>;
  baggage?: Record<string, string>;
  success: boolean;
  errorMessage?: string;
  metadata?: Record<string, unknown>;
  serviceName?: string;
  depth?: number; // 计算得出的层级深度
}

// Gateway Filter 执行数据 (对应 FilterExecutionBar)
interface FilterExecutionBar {
  filterName: string;
  order: number;
  startTimeOffset: number;
  selfTimeMs: number;
  totalTimeMs: number;
  downstreamMs: number;
  success: boolean;
  errorMessage?: string;
  timePercentage?: number;
}

interface FullChainWaterfallChartProps {
  /** Gateway Filter 执行数据 */
  filterExecutions?: FilterExecutionBar[];
  /** 下游服务 Spans 数据 */
  serviceSpans?: SpanNode[];
  /** 网关层总耗时 (用于计算百分比) */
  gatewayTotalDurationMs?: number;
  /** 服务名 (用于分组展示) */
  serviceName?: string;
  /** 点击 Span 事件的回调 */
  onSpanClick?: (span: SpanNode | FilterExecutionBar, type: 'filter' | 'span') => void;
  /** 慢 Span 阈值 (毫秒) */
  slowThreshold?: number;
  /** 是否显示层级缩进 */
  showHierarchy?: boolean;
}

/**
 * 全链路瀑布图组件
 *
 * 整合 Gateway Filter 执行 + 下游服务 Spans，展示完整的请求链路时间线：
 * - Gateway Filters (顺序执行)
 * - Service Spans (层级嵌套)
 * - Redis/DB/MQ 操作 (嵌套在 Service Spans 中)
 *
 * 特性：
 * 1. spanId/parentSpanId 层级关系可视化
 * 2. spanType 颜色编码 (FILTER/SERVICE/REDIS/DB/MQ/HTTP)
 * 3. 时间对齐展示 (基于 startTimeMs)
 * 4. selfTime vs downstreamTime 区分
 */
const FullChainWaterfallChart: React.FC<FullChainWaterfallChartProps> = ({
  filterExecutions = [],
  serviceSpans = [],
  gatewayTotalDurationMs = 0,
  serviceName,
  onSpanClick,
  slowThreshold = 50,
  showHierarchy = true
}) => {
  const { t } = useTranslation();

  // 获取 SpanType 对应的颜色
  const getSpanTypeColor = (spanType?: SpanType): string => {
    switch (spanType) {
      case 'FILTER': return '#1890ff';  // 蓝色 - Gateway Filter
      case 'SERVICE': return '#52c41a'; // 绿色 - 服务层
      case 'REDIS': return '#faad14';   // 橙色 - Redis 操作
      case 'DB': return '#722ed1';      // 紫色 - 数据库操作
      case 'MQ': return '#13c2c2';      // 青色 - 消息队列
      case 'HTTP': return '#eb2f96';    // 粉色 - HTTP 调用
      case 'INTERNAL': return '#8c8c8c';// 灰色 - 内部操作
      default: return '#52c41a';        // 默认绿色
    }
  };

  // 获取 SpanType 对应的图标
  const getSpanTypeIcon = (spanType?: SpanType): React.ReactNode => {
    switch (spanType) {
      case 'FILTER': return <FilterOutlined style={{ fontSize: 12 }} />;
      case 'SERVICE': return <CloudServerOutlined style={{ fontSize: 12 }} />;
      case 'REDIS': return <ThunderboltOutlined style={{ fontSize: 12 }} />;
      case 'DB': return <DatabaseOutlined style={{ fontSize: 12 }} />;
      case 'MQ': return <ApiOutlined style={{ fontSize: 12 }} />;
      case 'HTTP': return <ApiOutlined style={{ fontSize: 12 }} />;
      case 'INTERNAL': return <CodeOutlined style={{ fontSize: 12 }} />;
      default: return <BranchesOutlined style={{ fontSize: 12 }} />;
    }
  };

  // 计算 Span 层级深度 (基于 parentSpanId)
  const processedSpans = useMemo(() => {
    if (!showHierarchy || !serviceSpans.length) {
      return serviceSpans.map(s => ({ ...s, depth: 0 }));
    }

    // 构建 spanId -> span 的映射
    const spanMap = new Map<string, SpanNode>();
    serviceSpans.forEach(s => {
      if (s.spanId) spanMap.set(s.spanId, s);
    });

    // 计算每个 span 的深度
    const calculateDepth = (span: SpanNode, visited: Set<string>): number => {
      if (!span.spanId) return 0;
      if (visited.has(span.spanId)) return 0; // 防止循环
      visited.add(span.spanId);

      if (!span.parentSpanId) return 0; // Root span

      const parent = spanMap.get(span.parentSpanId);
      if (!parent) return 1; // parent 不存在，视为第一层

      return calculateDepth(parent, visited) + 1;
    };

    return serviceSpans.map(s => ({
      ...s,
      depth: calculateDepth(s, new Set())
    }));
  }, [serviceSpans, showHierarchy]);

  // 计算总时间范围 (用于对齐展示)
  const timeRange = useMemo(() => {
    const filterMax = filterExecutions.reduce((max, f) =>
      Math.max(max, f.startTimeOffset + f.totalTimeMs), 0);
    const spanMax = processedSpans.reduce((max, s) =>
      Math.max(max, (s.startTimeMs || 0) + s.durationMs), 0);
    return Math.max(filterMax, spanMax, gatewayTotalDurationMs, 1);
  }, [filterExecutions, processedSpans, gatewayTotalDurationMs]);

  // 如果没有数据，显示空状态
  if (!filterExecutions.length && !serviceSpans.length) {
    return (
      <div style={{ textAlign: 'center', padding: 24, color: '#999' }}>
        <BranchesOutlined style={{ fontSize: 24, marginBottom: 8 }} />
        <Text type="secondary">{t('trace.no_spans')}</Text>
      </div>
    );
  }

  // 渲染单个 Span 条
  const renderSpanBar = (span: SpanNode, index: number) => {
    const startPercent = timeRange > 0
      ? ((span.startTimeMs || 0) / timeRange) * 100
      : 0;
    const widthPercent = timeRange > 0
      ? Math.max((span.durationMs / timeRange) * 100, 1)
      : 1;

    const barColor = !span.success ? '#ff4d4f' :
      span.durationMs > slowThreshold ? '#faad14' :
      getSpanTypeColor(span.spanType);

    const depth = span.depth || 0;
    const indentWidth = depth * 20;

    // 解析 tags 显示关键信息
    const tagSummary = span.tags ? Object.entries(span.tags)
      .slice(0, 3)
      .map(([k, v]) => `${k}: ${v}`)
      .join(', ') : '';

    return (
      <div
        key={`span_${span.spanId || index}`}
        style={{
          marginBottom: 3,
          display: 'flex',
          alignItems: 'center',
          backgroundColor: !span.success ? 'rgba(255, 77, 79, 0.08)' : 'transparent',
          padding: '2px 0',
          borderRadius: 2,
          cursor: onSpanClick ? 'pointer' : 'default',
          paddingLeft: indentWidth
        }}
        onClick={() => onSpanClick?.(span, 'span')}
      >
        {/* Span 名称和类型 */}
        <div style={{ width: 180 - indentWidth, display: 'flex', alignItems: 'center', gap: 4 }}>
          {span.spanId && (
            <Badge
              count={depth + 1}
              style={{ backgroundColor: getSpanTypeColor(span.spanType), fontSize: 10, minWidth: 18 }}
            />
          )}
          <Tooltip title={span.operation}>
            <Tag
              color={!span.success ? 'error' : span.spanType?.toLowerCase() || 'default'}
              style={{ fontSize: 11, maxWidth: 120, overflow: 'hidden', textOverflow: 'ellipsis' }}
            >
              {getSpanTypeIcon(span.spanType)}
              <span style={{ marginLeft: 4 }}>{span.operation}</span>
            </Tag>
          </Tooltip>
        </div>

        {/* 时间条 */}
        <div style={{
          flex: 1,
          position: 'relative',
          height: 20,
          background: '#fafafa',
          borderRadius: 2,
          border: '1px solid #f0f0f0',
          marginRight: 60
        }}>
          <Tooltip title={
            <div>
              <div><strong>{span.operation}</strong></div>
              {span.spanId && <div>Span ID: {span.spanId}</div>}
              {span.parentSpanId && <div>Parent: {span.parentSpanId}</div>}
              <div>耗时: {span.durationMs}ms</div>
              <div>类型: {span.spanType || 'SERVICE'}</div>
              {span.kind && <div>Kind: {span.kind}</div>}
              {tagSummary && <div>Tags: {tagSummary}</div>}
              {span.errorMessage && <div style={{ color: '#ffa39e' }}>错误: {span.errorMessage}</div>}
            </div>
          }>
            <div style={{
              position: 'absolute',
              left: `${Math.max(0, startPercent)}%`,
              width: `${widthPercent}%`,
              height: '100%',
              borderRadius: 2,
              background: barColor,
              display: 'flex',
              alignItems: 'center',
              paddingLeft: 4,
              fontSize: 10,
              color: '#fff',
              minWidth: 24,
              overflow: 'hidden'
            }}>
              {widthPercent > 15 && span.durationMs > 0 && `${span.durationMs}ms`}
            </div>
          </Tooltip>
        </div>

        {/* 状态图标 */}
        <div style={{ width: 60, textAlign: 'center' }}>
          {span.success ? (
            span.durationMs > slowThreshold ? (
              <WarningOutlined style={{ color: '#faad14', fontSize: 12 }} />
            ) : (
              <CheckCircleOutlined style={{ color: '#52c41a', fontSize: 12 }} />
            )
          ) : (
            <WarningOutlined style={{ color: '#ff4d4f', fontSize: 12 }} />
          )}
        </div>
      </div>
    );
  };

  // 渲染 Filter 执行条 (沿用 FilterWaterfallChart 的逻辑)
  const renderFilterBar = (exec: FilterExecutionBar, index: number) => {
    const startPercent = timeRange > 0
      ? (exec.startTimeOffset / timeRange) * 100
      : 0;
    const totalWidthPercent = timeRange > 0
      ? Math.max((exec.totalTimeMs / timeRange) * 100, 1)
      : 1;
    const selfWidthPercent = exec.totalTimeMs > 0
      ? (exec.selfTimeMs / exec.totalTimeMs) * 100
      : 100;
    const downstreamWidthPercent = 100 - selfWidthPercent;

    const healthStatus: 'normal' | 'warning' | 'error' = !exec.success ? 'error' :
      exec.selfTimeMs > slowThreshold ? 'warning' : 'normal';

    const barColor = healthStatus === 'error' ? '#ff4d4f' :
      healthStatus === 'warning' ? '#faad14' : '#1890ff';
    const downstreamColor = '#52c41a'; // 下游时间用绿色表示服务处理

    return (
      <div
        key={`filter_${exec.filterName}_${exec.order}_${index}`}
        style={{
          marginBottom: 4,
          display: 'flex',
          alignItems: 'center',
          backgroundColor: healthStatus === 'error' ? 'rgba(255, 77, 79, 0.08)' : 'transparent',
          padding: '4px 0',
          borderRadius: 4,
          cursor: onSpanClick ? 'pointer' : 'default'
        }}
        onClick={() => onSpanClick?.(exec, 'filter')}
      >
        {/* Filter 名称和顺序 */}
        <div style={{ width: 180, display: 'flex', alignItems: 'center', gap: 8 }}>
          <Badge count={exec.order} style={{ backgroundColor: '#1890ff', fontSize: 10 }} />
          <Tooltip title={exec.filterName}>
            <Tag
              color={healthStatus === 'error' ? 'error' : healthStatus === 'warning' ? 'warning' : 'blue'}
              style={{ fontSize: 11, maxWidth: 120, overflow: 'hidden', textOverflow: 'ellipsis' }}
            >
              <FilterOutlined style={{ fontSize: 10, marginRight: 4 }} />
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
          marginRight: 60
        }}>
          <Tooltip title={
            <div>
              <div><strong>{exec.filterName}</strong></div>
              <div>自身耗时: {exec.selfTimeMs.toFixed(2)}ms</div>
              <div>总耗时: {exec.totalTimeMs.toFixed(2)}ms</div>
              <div>下游耗时: {exec.downstreamMs.toFixed(2)}ms</div>
              {exec.timePercentage && <div>占比: {exec.timePercentage.toFixed(1)}%</div>}
              {exec.errorMessage && <div style={{ color: '#ffa39e' }}>错误: {exec.errorMessage}</div>}
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
                background: barColor,
                borderRadius: '2px 0 0 2px',
                display: 'flex',
                alignItems: 'center',
                justifyContent: 'center',
                fontSize: 10,
                color: '#fff',
                overflow: 'hidden'
              }}>
                {selfWidthPercent > 30 && exec.selfTimeMs > 1 && `${exec.selfTimeMs.toFixed(1)}ms`}
              </div>

              {/* downstreamTime 部分 */}
              {downstreamWidthPercent > 0 && (
                <div style={{
                  width: `${downstreamWidthPercent}%`,
                  height: '100%',
                  background: downstreamColor,
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
        <div style={{ width: 60, textAlign: 'center' }}>
          {exec.success ? (
            healthStatus === 'warning' ? (
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
  };

  return (
    <div style={{ overflowX: 'auto', minWidth: 600 }}>
      {/* 图例 */}
      <div style={{ marginBottom: 12, display: 'flex', gap: 12, fontSize: 11, alignItems: 'center', flexWrap: 'wrap' }}>
        <span>
          <span style={{ display: 'inline-block', width: 10, height: 10, background: '#1890ff', marginRight: 4, borderRadius: 2 }}></span>
          <FilterOutlined style={{ fontSize: 10, marginRight: 2 }} />
          Filter
        </span>
        <span>
          <span style={{ display: 'inline-block', width: 10, height: 10, background: '#52c41a', marginRight: 4, borderRadius: 2 }}></span>
          <CloudServerOutlined style={{ fontSize: 10, marginRight: 2 }} />
          Service
        </span>
        <span>
          <span style={{ display: 'inline-block', width: 10, height: 10, background: '#faad14', marginRight: 4, borderRadius: 2 }}></span>
          <ThunderboltOutlined style={{ fontSize: 10, marginRight: 2 }} />
          Redis
        </span>
        <span>
          <span style={{ display: 'inline-block', width: 10, height: 10, background: '#722ed1', marginRight: 4, borderRadius: 2 }}></span>
          <DatabaseOutlined style={{ fontSize: 10, marginRight: 2 }} />
          DB
        </span>
        <span>
          <span style={{ display: 'inline-block', width: 10, height: 10, background: '#13c2c2', marginRight: 4, borderRadius: 2 }}></span>
          <ApiOutlined style={{ fontSize: 10, marginRight: 2 }} />
          MQ
        </span>
        <span>
          <span style={{ display: 'inline-block', width: 10, height: 10, background: '#ff4d4f', marginRight: 4, borderRadius: 2 }}></span>
          Error
        </span>
        <span style={{ marginLeft: 8 }}>
          <span style={{ display: 'inline-block', width: 10, height: 10, background: '#52c41a', marginRight: 4, borderRadius: 2, opacity: 0.5 }}></span>
          下游耗时
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
        paddingRight: 60
      }}>
        <span>0ms</span>
        <span>{Math.round(timeRange / 4)}ms</span>
        <span>{Math.round(timeRange / 2)}ms</span>
        <span>{Math.round(timeRange * 3 / 4)}ms</span>
        <span>{timeRange}ms</span>
      </div>

      {/* Gateway Filters 区域 */}
      {filterExecutions.length > 0 && (
        <div style={{ marginBottom: 16 }}>
          <div style={{
            display: 'flex',
            alignItems: 'center',
            marginBottom: 8,
            padding: '4px 8px',
            background: '#e6f7ff',
            borderRadius: 4
          }}>
            <ApiOutlined style={{ color: '#1890ff', marginRight: 8 }} />
            <Text strong style={{ fontSize: 12 }}>Gateway Filters</Text>
            <Badge
              count={filterExecutions.length}
              style={{ marginLeft: 8, backgroundColor: '#1890ff' }}
              showZero
            />
            {gatewayTotalDurationMs > 0 && (
              <Text type="secondary" style={{ marginLeft: 12, fontSize: 11 }}>
                总耗时: {gatewayTotalDurationMs}ms
              </Text>
            )}
          </div>
          {filterExecutions.map((exec, idx) => renderFilterBar(exec, idx))}
        </div>
      )}

      {/* Service Spans 区域 */}
      {processedSpans.length > 0 && (
        <div>
          <div style={{
            display: 'flex',
            alignItems: 'center',
            marginBottom: 8,
            padding: '4px 8px',
            background: '#f6ffed',
            borderRadius: 4
          }}>
            <CloudServerOutlined style={{ color: '#52c41a', marginRight: 8 }} />
            <Text strong style={{ fontSize: 12 }}>
              {serviceName ? `Service: ${serviceName}` : 'Service Spans'}
            </Text>
            <Badge
              count={processedSpans.length}
              style={{ marginLeft: 8, backgroundColor: '#52c41a' }}
              showZero
            />
            {/* 层级深度提示 */}
            {showHierarchy && (
              <Tooltip title="基于 spanId/parentSpanId 计算的层级深度">
                <Tag color="default" style={{ marginLeft: 8, fontSize: 10 }}>
                  <BranchesOutlined style={{ marginRight: 4 }} />
                  层级视图
                </Tag>
              </Tooltip>
            )}
          </div>
          {processedSpans.map((span, idx) => renderSpanBar(span, idx))}
        </div>
      )}
    </div>
  );
};

export default FullChainWaterfallChart;