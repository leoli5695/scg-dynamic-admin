import React, { useState, useEffect, useCallback, useRef } from 'react';
import {
  Card, Table, Spin, Alert, Typography, Space, Button, Tag,
  Statistic, Row, Col, Progress, Tooltip, Empty, Input,
  Switch, Select, Modal, Form, InputNumber, message, Divider
} from 'antd';
import {
  ApiOutlined, ReloadOutlined, SearchOutlined,
  CheckCircleOutlined, CloseCircleOutlined, ClockCircleOutlined,
  SettingOutlined, DownloadOutlined, ThunderboltOutlined, PictureOutlined
} from '@ant-design/icons';
import axios from 'axios';
import { useTranslation } from 'react-i18next';
import html2canvas from 'html2canvas';

const { Title, Text } = Typography;
const { Option } = Select;

// ==================== Waterfall Chart Component ====================
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

const WaterfallChart = React.forwardRef<HTMLDivElement, WaterfallChartProps>(
  ({ executions, totalDuration }, ref) => {
  // Tooltip state for mouse-following tooltip
  const [tooltip, setTooltip] = useState<TooltipState>({
    visible: false,
    x: 0,
    y: 0,
    content: null
  });

  // Sort by order
  const sortedExecutions = [...executions].sort((a, b) => a.order - b.order);

  // Helper: Convert microseconds to milliseconds with decimal precision
  const microsToMs = (micros: number | undefined): number => {
    if (micros === undefined || micros === null) return 0;
    return micros / 1000;  // Convert μs to ms with decimal precision
  };

  // Helper: Format time display with proper precision
  const formatTimeDisplay = (micros: number | undefined, ms: number): string => {
    const preciseMs = microsToMs(micros);
    // If we have microsecond precision, use it; otherwise use ms
    const value = preciseMs > 0 ? preciseMs : ms;
    if (value < 0.01) {
      // Less than 0.01ms, show in microseconds
      const microsValue = micros ?? ms * 1000;
      return `${microsValue.toFixed(0)}μs`;
    } else if (value < 1) {
      // Less than 1ms, show with 3 decimal places
      return `${value.toFixed(3)}ms`;
    } else {
      return `${value.toFixed(2)}ms`;
    }
  };

  // Calculate cumulative start time for each filter (waterfall effect)
  const calculateStartTime = (index: number) => {
    let startTime = 0;
    for (let i = 0; i < index; i++) {
      startTime += microsToMs(sortedExecutions[i].selfTimeMicros ?? sortedExecutions[i].selfTimeMs * 1000);
    }
    return startTime;
  };

  // Generate time axis markers (0%, 25%, 50%, 75%, 100%)
  const timeMarkers = [0, 25, 50, 75, 100];
  
  // Color palette for different filter types
  const getFilterColor = (filterName: string, selfTime: number) => {
    // Critical filters (auth, security) - red shades
    if (filterName.toLowerCase().includes('auth') || filterName.toLowerCase().includes('security')) {
      return selfTime > 5 ? '#ff7875' : '#ffa39e';
    }
    // Rate limiter - orange
    if (filterName.toLowerCase().includes('rate') || filterName.toLowerCase().includes('limit')) {
      return selfTime > 3 ? '#fa8c16' : '#ffc53d';
    }
    // Routing filters - blue
    if (filterName.toLowerCase().includes('route') || filterName.toLowerCase().includes('load')) {
      return '#1890ff';
    }
    // Transform filters - cyan
    if (filterName.toLowerCase().includes('transform') || filterName.toLowerCase().includes('header')) {
      return '#13c2c2';
    }
    // Cache - purple
    if (filterName.toLowerCase().includes('cache')) {
      return '#722ed1';
    }
    // Default - green (fast) to yellow (medium)
    return selfTime > 10 ? '#faad14' : selfTime > 5 ? '#52c41a' : '#73d13d';
  };

  return (
    <div ref={ref} data-waterfall-chart style={{ width: '100%', background: '#141414', padding: '8px', borderRadius: '4px' }}>
      {/* Time Axis Header */}
      <div style={{ 
        display: 'flex', 
        justifyContent: 'space-between',
        padding: '4px 120px 8px 120px',
        fontSize: 10,
        color: 'rgba(255,255,255,0.45)',
        borderBottom: '1px solid rgba(255,255,255,0.1)'
      }}>
        {timeMarkers.map(percent => (
          <Text key={percent} style={{ fontSize: 10, color: 'rgba(255,255,255,0.45)' }}>
            {((totalDuration * percent) / 100).toFixed(1)}ms
          </Text>
        ))}
      </div>

      {/* Waterfall Bars */}
      <div className="waterfall-scroll-container" style={{ maxHeight: 350, overflowY: 'auto', overflowX: 'hidden' }}>
        {sortedExecutions.map((exec, idx) => {
          // Use microseconds for precise calculation, fallback to ms
          const selfTimeMicros = exec.selfTimeMicros ?? (exec.selfTimeMs ?? 0) * 1000;
          const selfTimeMsPrecise = microsToMs(exec.selfTimeMicros) || (exec.selfTimeMs ?? 0);
          const downstreamTime = exec.downstreamMs ?? 0;
          const startTime = calculateStartTime(idx);

          // Calculate percentages relative to total duration
          const startPercent = totalDuration > 0 ? (startTime / totalDuration * 100) : 0;
          const selfPercent = totalDuration > 0 ? (selfTimeMsPrecise / totalDuration * 100) : 0;
          const downstreamPercent = totalDuration > 0 ? (downstreamTime / totalDuration * 100) : 0;

          const filterColor = getFilterColor(exec.filter, selfTimeMsPrecise);
          const isSuccess = exec.success;
          const timeDisplay = formatTimeDisplay(exec.selfTimeMicros, exec.selfTimeMs ?? 0);

          return (
            <div 
              key={idx} 
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
              {/* Filter Name Column */}
              <div style={{ width: 120, paddingRight: 8, flexShrink: 0 }}>
                <Tooltip title={`Order: ${exec.order}`}>
                  <Text 
                    code 
                    style={{ 
                      fontSize: 11, 
                      display: 'block',
                      overflow: 'hidden',
                      textOverflow: 'ellipsis',
                      whiteSpace: 'nowrap'
                    }}
                  >
                    {exec.filter}
                  </Text>
                </Tooltip>
              </div>

              {/* Waterfall Bar Column */}
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
                  pointerEvents: 'none'
                }}>
                  {[25, 50, 75].map(p => (
                    <div key={p} style={{
                      width: 1,
                      height: '100%',
                      background: 'rgba(255,255,255,0.05)'
                    }} />
                  ))}
                </div>

                {/* The actual waterfall bar - with mouse-following tooltip */}
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
                    boxShadow: isSuccess ? 'none' : '0 0 0 1px #ff4d4f'
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
                      )
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
                    fontWeight: 500
                  }}>
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
                      borderLeft: '1px solid rgba(255,255,255,0.3)'
                    }} />
                  )}
                </div>
              </div>

              {/* Time Value Column */}
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
        alignItems: 'center'
      }}>
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

      {/* Mouse-following Tooltip */}
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
            transition: 'left 0.05s ease-out, top 0.05s ease-out'
          }}
        >
          {tooltip.content}
        </div>
      )}
    </div>
  );
});

WaterfallChart.displayName = 'WaterfallChart';

// ==================== End Waterfall Chart Component ====================

interface FilterStats {
  filterName: string;
  order: number;
  totalCount: number;
  successCount: number;
  failureCount: number;
  successRate: string;

  // Cumulative duration (includes downstream time)
  avgDurationMs: string;
  avgDurationMsRaw: number;
  avgDurationMicros: string;
  avgDurationMicrosRaw: number;
  maxDurationMicros: number;
  minDurationMicros: number;

  // Cumulative duration percentiles
  p50Micros: number;
  p95Micros: number;
  p99Micros: number;
  p50Ms: string;
  p95Ms: string;
  p99Ms: string;

  // Self/independent duration (filter's own logic time) - KEY METRIC
  avgSelfTimeMs: string;
  avgSelfTimeMsRaw: number;
  avgSelfTimeMicros: string;
  avgSelfTimeMicrosRaw: number;
  maxSelfTimeMicros: number;
  minSelfTimeMicros: number;

  // Self duration percentiles - actual filter performance
  selfP50Micros: number;
  selfP95Micros: number;
  selfP99Micros: number;
  selfP50Ms: string;
  selfP95Ms: string;
  selfP99Ms: string;
}

interface FilterExecution {
  filter: string;
  order: number;
  totalDurationMs: number;  // Cumulative time (includes downstream)
  selfTimeMs: number;       // Filter's own logic time (ms)
  selfTimeMicros?: number;  // Filter's own logic time (μs) for precision
  downstreamMs: number;     // Time spent in downstream chain
  success: boolean;
  error?: string;
}

interface FilterChainRecord {
  traceId: string;
  createdAt: number;
  totalDurationMs: number;
  successCount: number;
  failureCount: number;
  filterCount: number;
  executions: FilterExecution[];
}

interface FilterChainPageProps {
  instanceId?: string;
}

const FilterChainPage: React.FC<FilterChainPageProps> = ({ instanceId }) => {
  const [loading, setLoading] = useState(false);
  const [filterStats, setFilterStats] = useState<FilterStats[]>([]);
  const [records, setRecords] = useState<FilterChainRecord[]>([]);
  const [error, setError] = useState<string | null>(null);
  const [searchTraceId, setSearchTraceId] = useState('');
  const [selectedRecord, setSelectedRecord] = useState<FilterChainRecord | null>(null);
  const { t } = useTranslation();

  // ✅ New: Auto refresh
  const [autoRefresh, setAutoRefresh] = useState(false);
  const [refreshInterval, setRefreshInterval] = useState(5000);

  // ✅ New: Performance thresholds
  const [thresholds, setThresholds] = useState({
    selfTime: 10,
    p95: 20,
    p99: 50,
    successRate: 95
  });
  const [showThresholdModal, setShowThresholdModal] = useState(false);

  // ✅ New: Export modal
  const [showExportModal, setShowExportModal] = useState(false);

  // Ref for waterfall chart export
  const waterfallChartRef = useRef<HTMLDivElement>(null);

  // ✅ New: Historical data
  const [historicalData, setHistoricalData] = useState([]);
  const [showHistoryChart, setShowHistoryChart] = useState(false);

  const loadData = useCallback(async () => {
    if (!instanceId) return;
    
    setLoading(true);
    setError(null);
    
    try {
      const [statsRes, recordsRes, historicalRes] = await Promise.all([
        axios.get(`/api/filter-chain/${instanceId}/stats`),
        axios.get(`/api/filter-chain/${instanceId}/records?limit=50`),
        axios.get(`/api/filter-chain/${instanceId}/historical?minutes=30`)
      ]);

      if (statsRes.data.code === 200 && statsRes.data.data) {
        setFilterStats(statsRes.data.data.filters || []);
      }

      if (recordsRes.data.code === 200 && recordsRes.data.data) {
        setRecords(recordsRes.data.data.records || []);
      }

      if (historicalRes.data.code === 200 && historicalRes.data.data) {
        setHistoricalData(historicalRes.data.data.historicalData || []);
      }
    } catch (e: any) {
      setError(e.message || 'Failed to load filter chain data');
    } finally {
      setLoading(false);
    }
  }, [instanceId]);

  useEffect(() => {
    loadData();
  }, [loadData]);

  // ✅ Auto refresh timer
  useEffect(() => {
    if (autoRefresh && instanceId) {
      const timer = setInterval(loadData, refreshInterval);
      return () => clearInterval(timer);
    }
  }, [autoRefresh, instanceId, refreshInterval, loadData]);

  // ✅ Keyboard shortcuts
  useEffect(() => {
    const handleKeyPress = (e: KeyboardEvent) => {
      // Check Ctrl (Windows) or Cmd (Mac)
      if (e.ctrlKey || e.metaKey) {
        const key = e.key.toLowerCase();

        // Ctrl+R: Refresh (browser default, but we override)
        if (key === 'r') {
          e.preventDefault();
          loadData();
          message.success('数据已刷新');
          return;
        }

        // Ctrl+E: Export - use Alt+E instead because Ctrl+E is browser search shortcut on Windows
        // Actually handle both Ctrl+E and Alt+E for flexibility
        if (key === 'e') {
          // Ctrl+E might be intercepted by browser, so also support Alt+E
          e.preventDefault();
          setShowExportModal(true);
          return;
        }

        // Ctrl+A: Toggle auto refresh (browser default select all)
        if (key === 'a') {
          e.preventDefault();
          setAutoRefresh(!autoRefresh);
          message.info(autoRefresh ? '自动刷新已关闭' : '自动刷新已开启');
          return;
        }
      }

      // Alt+E as fallback for Export (Ctrl+E is browser search shortcut on Windows)
      if (e.altKey && e.key.toLowerCase() === 'e') {
        e.preventDefault();
        setShowExportModal(true);
      }
    };

    document.addEventListener('keydown', handleKeyPress);
    return () => document.removeEventListener('keydown', handleKeyPress);
  }, [autoRefresh, loadData]);

  const searchTrace = async () => {
    if (!instanceId || !searchTraceId.trim()) return;
    
    setLoading(true);
    try {
      const response = await axios.get(`/api/filter-chain/${instanceId}/trace/${searchTraceId.trim()}`);
      if (response.data.code === 200 && response.data.data) {
        setSelectedRecord(response.data.data);
      } else {
        setError('Trace not found');
      }
    } catch (e: any) {
      setError(e.message || 'Failed to search trace');
    } finally {
      setLoading(false);
    }
  };

  const clearStats = async () => {
    if (!instanceId) return;

    try {
      await axios.delete(`/api/filter-chain/${instanceId}/stats`);
      loadData();
      message.success('统计数据已清空');
    } catch (e: any) {
      setError(e.message || 'Failed to clear stats');
      message.error('清空失败');
    }
  };

  // ✅ Export functions
  const exportData = (format: 'csv' | 'json') => {
    const data = {
      exportTime: new Date().toISOString(),
      instanceId: instanceId,
      thresholds: thresholds,
      filters: filterStats,
      records: records.slice(0, 100)
    };

    if (format === 'json') {
      const filename = `filter-chain-stats-${Date.now()}.json`;
      const blob = new Blob([JSON.stringify(data, null, 2)], { type: 'application/json' });
      downloadBlob(blob, filename);
      message.success(`JSON文件已导出 (${filename})，请查看浏览器下载目录`);
    } else {
      const filename = `filter-chain-stats-${Date.now()}.csv`;
      const csvContent = convertToCSV(filterStats);
      const blob = new Blob([csvContent], { type: 'text/csv;charset=utf-8;' });
      downloadBlob(blob, filename);
      message.success(`CSV文件已导出 (${filename})，请查看浏览器下载目录`);
    }

    setShowExportModal(false);
  };

  // ✅ Export waterfall chart as PNG image
  const exportChartAsImage = async () => {
    if (!waterfallChartRef.current) {
      message.error('瀑布图组件未加载，请先点击详情查看瀑布图');
      return;
    }

    try {
      message.loading({ content: '正在生成图片...', key: 'exportImage' });

      const canvas = await html2canvas(waterfallChartRef.current, {
        backgroundColor: '#141414',
        scale: 2, // Higher quality
        logging: false,
        useCORS: true,
        scrollY: -window.scrollY, // Handle page scroll offset
        scrollX: -window.scrollX,
        windowHeight: waterfallChartRef.current.scrollHeight + 100, // Full height
        windowWidth: waterfallChartRef.current.scrollWidth,
        onclone: (clonedDoc) => {
          // Find the cloned waterfall chart and remove scroll limits
          const clonedChart = clonedDoc.body.querySelector('[data-waterfall-chart]') as HTMLElement;
          if (clonedChart) {
            const scrollContainer = clonedChart.querySelector('.waterfall-scroll-container') as HTMLElement;
            if (scrollContainer) {
              scrollContainer.style.maxHeight = 'none';
              scrollContainer.style.overflow = 'visible';
              scrollContainer.style.height = 'auto';
            }
          }
        }
      });

      const imageBlob = await new Promise<Blob>((resolve) => {
        canvas.toBlob((blob) => {
          resolve(blob!);
        }, 'image/png', 1.0);
      });

      const filename = `filter-waterfall-chart-${Date.now()}.png`;
      downloadBlob(imageBlob, filename);
      message.success({ content: `瀑布图已导出 (${filename})，请查看浏览器下载目录`, key: 'exportImage' });
      setShowExportModal(false);
    } catch (error) {
      message.error({ content: '导出图片失败', key: 'exportImage' });
      console.error('Export image error:', error);
    }
  };

  const downloadBlob = (blob: Blob, filename: string) => {
    try {
      // Standard download approach
      const url = URL.createObjectURL(blob);
      const link = document.createElement('a');
      link.style.display = 'none';
      link.href = url;
      link.download = filename;
      document.body.appendChild(link);

      // Trigger download directly
      link.click();

      // Cleanup after a delay to ensure download started
      setTimeout(() => {
        document.body.removeChild(link);
        URL.revokeObjectURL(url);
      }, 100);
    } catch (e) {
      console.error('Download error:', e);
      // Fallback: open in new window for manual save
      const url = URL.createObjectURL(blob);
      window.open(url, '_blank');
      message.warning('请在新窗口中右键保存文件');
    }
  };

  const convertToCSV = (stats: FilterStats[]): string => {
    const headers = [
      'Filter Name',
      'Order',
      'Total Count',
      'Success Rate',
      'Avg Self Time (ms)',
      'P95 (ms)',
      'P99 (ms)',
      'Min Self Time (μs)',
      'Max Self Time (μs)'
    ];

    const rows = stats.map(stat => [
      stat.filterName,
      stat.order,
      stat.totalCount,
      stat.successRate,
      stat.avgSelfTimeMsRaw.toFixed(3),
      (stat.selfP95Micros / 1000).toFixed(2),
      (stat.selfP99Micros / 1000).toFixed(2),
      stat.minSelfTimeMicros,
      stat.maxSelfTimeMicros
    ]);

    return [headers.join(','), ...rows.map(row => row.join(','))].join('\n');
  };

  const filterColumns = [
    {
      title: t('filterChain.filter_name'),
      dataIndex: 'filterName',
      key: 'filterName',
      width: 150,
      fixed: 'left' as const,
      render: (text: string) => <Text code>{text}</Text>
    },
    {
      title: t('filterChain.order'),
      dataIndex: 'order',
      key: 'order',
      width: 100,
      sorter: (a: FilterStats, b: FilterStats) => a.order - b.order,
      render: (order: number) => <Tag color={order < 0 ? 'blue' : 'green'}>{order}</Tag>
    },
    {
      title: t('filterChain.total_count'),
      dataIndex: 'totalCount',
      key: 'totalCount',
      width: 100,
      sorter: (a: FilterStats, b: FilterStats) => a.totalCount - b.totalCount,
      render: (count: number) => count.toLocaleString()
    },
    {
      title: t('filterChain.success_rate'),
      dataIndex: 'successRate',
      key: 'successRate',
      width: 100,
      sorter: (a: FilterStats, b: FilterStats) => parseFloat(a.successRate) - parseFloat(b.successRate),
      render: (rate: string) => {
        const numRate = parseFloat(rate);
        const color = numRate >= 99 ? 'green' : numRate >= 95 ? 'orange' : 'red';
        return <Tag color={color}>{rate}</Tag>;
      }
    },
    {
      title: t('filterChain.avg_latency'),
      dataIndex: 'avgDurationMicros',
      key: 'avgDurationMicros',
      width: 120,
      sorter: (a: FilterStats, b: FilterStats) => a.avgDurationMicrosRaw - b.avgDurationMicrosRaw,
      render: (micros: string) => {
        const value = parseFloat(micros);
        return value > 1000 ? `${(value / 1000).toFixed(2)}ms` : `${value.toFixed(2)}μs`;
      }
    },
    {
      title: '自身耗时',
      dataIndex: 'avgSelfTimeMs',
      key: 'avgSelfTimeMs',
      width: 120,
      sorter: (a: FilterStats, b: FilterStats) => a.avgSelfTimeMsRaw - b.avgSelfTimeMsRaw,
      render: (ms: string) => {
        const value = parseFloat(ms);
        const color = value < 1 ? 'green' : value < 10 ? 'orange' : 'red';
        return <Tag color={color}>{value.toFixed(3)}ms</Tag>;
      }
    },
    {
      title: 'P95耗时',
      dataIndex: 'selfP95Ms',
      key: 'selfP95Ms',
      width: 120,
      sorter: (a: FilterStats, b: FilterStats) => a.selfP95Micros - b.selfP95Micros,
      render: (ms: string) => {
        const value = parseFloat(ms);
        const color = value < 5 ? 'green' : value < 20 ? 'orange' : 'red';
        return <Tag color={color}>{value.toFixed(2)}ms</Tag>;
      }
    },
    {
      title: 'P99耗时',
      dataIndex: 'selfP99Ms',
      key: 'selfP99Ms',
      width: 120,
      sorter: (a: FilterStats, b: FilterStats) => a.selfP99Micros - b.selfP99Micros,
      render: (ms: string) => {
        const value = parseFloat(ms);
        const color = value < 10 ? 'green' : value < 50 ? 'orange' : 'red';
        return (
          <Tooltip title="99%请求的性能上限">
            <Tag color={color}>{value.toFixed(2)}ms</Tag>
          </Tooltip>
        );
      }
    },
    {
      title: '最佳性能',
      dataIndex: 'minSelfTimeMicros',
      key: 'minSelfTimeMicros',
      width: 120,
      render: (micros: number) =>
        micros > 1000 ? `${(micros / 1000).toFixed(2)}ms` : `${micros.toFixed(2)}μs`
    },
    {
      title: t('filterChain.max_latency'),
      dataIndex: 'maxDurationMicros',
      key: 'maxDurationMicros',
      width: 120,
      render: (micros: number) =>
        micros > 1000 ? `${(micros / 1000).toFixed(2)}ms` : `${micros.toFixed(2)}μs`
    }
  ];

  const recordColumns = [
    {
      title: t('filterChain.trace_id'),
      dataIndex: 'traceId',
      key: 'traceId',
      render: (text: string) => <Text copyable style={{ fontSize: 12 }}>{text.substring(0, 16)}...</Text>
    },
    {
      title: t('filterChain.duration'),
      dataIndex: 'totalDurationMs',
      key: 'totalDurationMs',
      render: (ms: number) => <Tag color={ms > 1000 ? 'orange' : 'green'}>{ms}ms</Tag>
    },
    {
      title: t('filterChain.filters'),
      dataIndex: 'filterCount',
      key: 'filterCount',
      render: (count: number) => t('filterChain.filters_count', { count })
    },
    {
      title: t('filterChain.status'),
      key: 'status',
      render: (_: any, record: FilterChainRecord) => (
        <Space>
          <Tooltip title={t('filterChain.success_count', { count: record.successCount })}>
            <Tag color="green" icon={<CheckCircleOutlined />}>
              {record.successCount}
            </Tag>
          </Tooltip>
          {record.failureCount > 0 && (
            <Tooltip title={t('filterChain.failed_count', { count: record.failureCount })}>
              <Tag color="red" icon={<CloseCircleOutlined />}>
                {record.failureCount}
              </Tag>
            </Tooltip>
          )}
        </Space>
      )
    },
    {
      title: t('filterChain.time'),
      dataIndex: 'createdAt',
      key: 'createdAt',
      render: (ts: number) => new Date(ts).toLocaleTimeString()
    },
    {
      title: t('common.actions'),
      key: 'actions',
      render: (_: any, record: FilterChainRecord) => (
        <Button size="small" onClick={() => setSelectedRecord(record)}>
          {t('common.detail')}
        </Button>
      )
    }
  ];

  const executionColumns = [
    {
      title: t('filterChain.filter'),
      dataIndex: 'filter',
      key: 'filter',
      width: 150,
      fixed: 'left' as const,
      render: (text: string) => <Text code>{text}</Text>
    },
    {
      title: t('filterChain.order'),
      dataIndex: 'order',
      key: 'order',
      width: 80,
      render: (order: number) => <Tag>{order}</Tag>
    },
    {
      title: '自身耗时',
      dataIndex: 'selfTimeMs',
      key: 'selfTimeMs',
      width: 120,
      render: (ms: number, record: FilterExecution) => {
        const micros = record.selfTimeMicros;
        // Use microseconds for precise display if available
        const preciseMs = micros != null ? micros / 1000 : ms;
        if (ms == null && micros == null) return <Tag color="default">N/A</Tag>;

        const color = preciseMs < 1 ? 'green' : preciseMs < 10 ? 'orange' : 'red';

        // Format display based on precision
        if (preciseMs < 0.01 && micros != null) {
          // Very small values, show in microseconds
          return <Tag color={color}>{micros.toFixed(0)}μs</Tag>;
        } else if (preciseMs < 1) {
          return <Tag color={color}>{preciseMs.toFixed(3)}ms</Tag>;
        } else {
          return <Tag color={color}>{preciseMs.toFixed(2)}ms</Tag>;
        }
      }
    },
    {
      title: '下游耗时',
      dataIndex: 'downstreamMs',
      key: 'downstreamMs',
      width: 120,
      render: (ms: number) => {
        if (ms == null) return <Text type="secondary">N/A</Text>;
        return <Text type="secondary">{ms.toFixed(2)}ms</Text>;
      }
    },
    {
      title: t('filterChain.duration'),
      key: 'duration',
      width: 120,
      render: (_: any, record: FilterExecution) => {
        const micros = record.selfTimeMicros;
        const selfTime = micros != null ? micros / 1000 : (record.selfTimeMs ?? 0);
        const downstreamTime = record.downstreamMs ?? 0;
        const totalTime = record.totalDurationMs ?? 0;

        // Format self time display
        const selfTimeDisplay = micros != null && selfTime < 0.01
          ? `${micros.toFixed(0)}μs`
          : `${selfTime.toFixed(3)}ms`;

        return (
          <Tooltip title={`自身: ${selfTimeDisplay} | 下游: ${downstreamTime.toFixed(2)}ms`}>
            <Text>
              {totalTime.toFixed(2)}ms
            </Text>
          </Tooltip>
        );
      }
    },
    {
      title: t('filterChain.status'),
      dataIndex: 'success',
      key: 'success',
      width: 80,
      render: (success: boolean) => success
        ? <CheckCircleOutlined style={{ color: '#52c41a' }} />
        : <CloseCircleOutlined style={{ color: '#ff4d4f' }} />
    },
    {
      title: t('filterChain.error') || 'Error',
      dataIndex: 'error',
      key: 'error',
      width: 150,
      render: (error: string) => error ? <Text type="danger">{error}</Text> : '-'
    }
  ];

  // Calculate totals
  const totalExecutions = filterStats.reduce((sum, f) => sum + f.totalCount, 0);
  const totalSuccess = filterStats.reduce((sum, f) => sum + f.successCount, 0);
  const totalFailures = filterStats.reduce((sum, f) => sum + f.failureCount, 0);
  const overallSuccessRate = totalExecutions > 0 ? (totalSuccess / totalExecutions * 100).toFixed(2) : '0';

  // Calculate performance metrics
  const avgSelfTime = filterStats.length > 0
    ? (filterStats.reduce((sum, f) => sum + f.avgSelfTimeMsRaw, 0) / filterStats.length).toFixed(3)
    : '0';
  const avgP95 = filterStats.length > 0
    ? (filterStats.reduce((sum, f) => sum + f.selfP95Micros, 0) / filterStats.length / 1000).toFixed(2)
    : '0';
  const avgP99 = filterStats.length > 0
    ? (filterStats.reduce((sum, f) => sum + f.selfP99Micros, 0) / filterStats.length / 1000).toFixed(2)
    : '0';

  // Get slowest filters by selfTime
  const slowestFilters = [...filterStats]
    .sort((a, b) => b.avgSelfTimeMsRaw - a.avgSelfTimeMsRaw)
    .slice(0, 5);

  if (!instanceId) {
    return (
      <Card>
        <Empty description={t('filterChain.select_instance') || 'Please select an instance'} />
      </Card>
    );
  }

  return (
    <div className="filter-chain-page">
      <div className="page-header" style={{
        display: 'flex',
        justifyContent: 'space-between',
        alignItems: 'center',
        marginBottom: 16
      }}>
        <Title level={4} style={{ margin: 0 }}>
          <ApiOutlined style={{ marginRight: 8 }} />
          {t('filterChain.title') || 'Filter Chain'}
        </Title>
        <Space>
          {/* ✅ Auto refresh control */}
          <Tooltip title="Ctrl+A快捷键">
            <Space>
              <Switch
                checkedChildren="自动"
                unCheckedChildren="手动"
                checked={autoRefresh}
                onChange={setAutoRefresh}
              />
              {autoRefresh && (
                <Select value={refreshInterval} onChange={setRefreshInterval} style={{ width: 80 }}>
                  <Option value={3000}>3秒</Option>
                  <Option value={5000}>5秒</Option>
                  <Option value={10000}>10秒</Option>
                  <Option value={30000}>30秒</Option>
                </Select>
              )}
            </Space>
          </Tooltip>

          {/* ✅ Threshold config */}
          <Tooltip title="性能阈值配置">
            <Button icon={<SettingOutlined />} onClick={() => setShowThresholdModal(true)}>
              阈值
            </Button>
          </Tooltip>

          {/* ✅ Export */}
          <Button icon={<DownloadOutlined />} onClick={() => setShowExportModal(true)}>
            导出
          </Button>

          {/* Search */}
          <Input
            placeholder={t('filterChain.search_trace') || 'Search trace ID...'}
            value={searchTraceId}
            onChange={e => setSearchTraceId(e.target.value)}
            onPressEnter={searchTrace}
            prefix={<SearchOutlined />}
            style={{ width: 250 }}
          />

          {/* Refresh */}
          <Tooltip title="Ctrl+R快捷键">
            <Button icon={<ReloadOutlined />} onClick={loadData} loading={loading}>
              {t('common.refresh') || 'Refresh'}
            </Button>
          </Tooltip>
        </Space>
      </div>

      {error && (
        <Alert
          message={t('common.error')}
          description={error}
          type="error"
          showIcon
          closable
          style={{ marginBottom: 16 }}
          onClose={() => setError(null)}
        />
      )}

      {/* 数据来源说明 */}
      <Alert
        message="数据来源说明"
        description={
          <Space direction="vertical" size={0}>
            <Text style={{ fontSize: 12 }}>• 数据来源：网关实例内存实时数据，不持久化到数据库</Text>
            <Text style={{ fontSize: 12 }}>• 历史窗口：保留最近60分钟的性能快照（每分钟采集一次）</Text>
            <Text style={{ fontSize: 12 }}>• 重启影响：网关实例重启后，统计数据和历史记录将清空</Text>
          </Space>
        }
        type="info"
        showIcon
        style={{ marginBottom: 16, background: 'rgba(24,144,255,0.1)' }}
      />

      {/* Summary Stats */}
      <Row gutter={16} style={{ marginBottom: 16 }}>
        <Col xs={12} sm={6}>
          <Card size="small" style={{ background: 'rgba(255,255,255,0.05)' }}>
            <Statistic
              title={<span style={{ color: 'rgba(255,255,255,0.65)' }}>{t('filterChain.total_executions')}</span>}
              value={totalExecutions}
              valueStyle={{ fontSize: 20 }}
            />
          </Card>
        </Col>
        <Col xs={12} sm={6}>
          <Card size="small" style={{ background: 'rgba(255,255,255,0.05)' }}>
            <Statistic
              title={<span style={{ color: 'rgba(255,255,255,0.65)' }}>{t('filterChain.success_rate')}</span>}
              value={overallSuccessRate}
              suffix="%"
              valueStyle={{ fontSize: 20, color: parseFloat(overallSuccessRate) >= 99 ? '#52c41a' : '#faad14' }}
            />
          </Card>
        </Col>
        <Col xs={12} sm={6}>
          <Card size="small" style={{ background: 'rgba(255,255,255,0.05)' }}>
            <Statistic
              title={<span style={{ color: 'rgba(255,255,255,0.65)' }}>{t('filterChain.failures')}</span>}
              value={totalFailures}
              valueStyle={{ fontSize: 20, color: totalFailures > 0 ? '#ff4d4f' : '#52c41a' }}
            />
          </Card>
        </Col>
        <Col xs={12} sm={6}>
          <Card size="small" style={{ background: 'rgba(255,255,255,0.05)' }}>
            <Statistic
              title={<span style={{ color: 'rgba(255,255,255,0.65)' }}>{t('filterChain.filter_count')}</span>}
              value={filterStats.length}
              valueStyle={{ fontSize: 20 }}
            />
          </Card>
        </Col>
      </Row>

      {/* Performance Analysis Cards */}
      <Row gutter={16} style={{ marginBottom: 16 }}>
        <Col xs={24} sm={12}>
          <Card
            title="性能分布分析"
            size="small"
            style={{ background: 'rgba(255,255,255,0.05)' }}
            extra={
              <Space>
                <Tooltip title="历史趋势图">
                  <Button 
                    size="small" 
                    icon={<ClockCircleOutlined />}
                    onClick={() => setShowHistoryChart(!showHistoryChart)}
                    type={showHistoryChart ? 'primary' : 'default'}
                  >
                    历史趋势
                  </Button>
                </Tooltip>
              </Space>
            }
          >
            <Row gutter={16}>
              <Col span={8}>
                <Statistic
                  title="平均自身耗时"
                  value={avgSelfTime}
                  suffix="ms"
                  valueStyle={{ fontSize: 16, color: parseFloat(avgSelfTime) < 1 ? '#52c41a' : '#faad14' }}
                />
              </Col>
              <Col span={8}>
                <Statistic
                  title="P95耗时"
                  value={avgP95}
                  suffix="ms"
                  valueStyle={{ fontSize: 16, color: parseFloat(avgP95) < 5 ? '#52c41a' : '#faad14' }}
                />
              </Col>
              <Col span={8}>
                <Statistic
                  title="P99耗时"
                  value={avgP99}
                  suffix="ms"
                  valueStyle={{ fontSize: 16, color: parseFloat(avgP99) < 10 ? '#52c41a' : '#faad14' }}
                />
              </Col>
            </Row>
            <div style={{ marginTop: 12, paddingTop: 12, borderTop: '1px solid rgba(255,255,255,0.1)' }}>
              <Text type="secondary" style={{ fontSize: 12 }}>
                自身耗时 = Filter自身逻辑执行时间（不含下游服务）
              </Text>
            </div>
          </Card>
        </Col>

        <Col xs={24} sm={12}>
          <Card
            title="最慢Filter排名"
            size="small"
            style={{ background: 'rgba(255,255,255,0.05)' }}
          >
            {slowestFilters.length > 0 ? (
              <div style={{ maxHeight: 150, overflow: 'auto' }}>
                {slowestFilters.map((filter, index) => (
                  <div key={filter.filterName} style={{
                    display: 'flex',
                    justifyContent: 'space-between',
                    alignItems: 'center',
                    padding: '8px 0',
                    borderBottom: index < slowestFilters.length - 1 ? '1px solid rgba(255,255,255,0.1)' : 'none'
                  }}>
                    <Space>
                      <Tag color={index === 0 ? 'red' : index < 3 ? 'orange' : 'blue'}>
                        #{index + 1}
                      </Tag>
                      <Text code style={{ fontSize: 12 }}>{filter.filterName}</Text>
                    </Space>
                    <Space>
                      <Text type={index === 0 ? 'danger' : 'warning'} style={{ fontSize: 13 }}>
                        {filter.avgSelfTimeMsRaw.toFixed(3)}ms
                      </Text>
                      <Tooltip title={`P95: ${filter.selfP95Ms}ms | P99: ${filter.selfP99Ms}ms`}>
                        <ClockCircleOutlined style={{ color: 'rgba(255,255,255,0.45)', fontSize: 12 }} />
                      </Tooltip>
                    </Space>
                  </div>
                ))}
              </div>
            ) : (
              <Empty description="暂无数据" image={Empty.PRESENTED_IMAGE_SIMPLE} />
            )}
          </Card>
        </Col>
      </Row>

      {/* Filter Statistics Table */}
      <Card
        title={t('filterChain.filter_stats')}
        style={{ marginBottom: 16 }}
        extra={
          <Button size="small" danger onClick={clearStats}>
            {t('common.clear')}
          </Button>
        }
      >
        <Table
          dataSource={filterStats}
          columns={filterColumns}
          rowKey="filterName"
          pagination={false}
          size="small"
          loading={loading}
        />
      </Card>

      {/* Recent Records */}
      <Card title={t('filterChain.recent_records')}>
        <Table
          dataSource={records}
          columns={recordColumns}
          rowKey="traceId"
          pagination={{ pageSize: 10 }}
          size="small"
          loading={loading}
        />
      </Card>

      {/* Record Detail Modal */}
      {selectedRecord && (
        <Card
          title={`${t('filterChain.trace_detail')}: ${selectedRecord.traceId}`}
          style={{ marginTop: 16 }}
          extra={
            <Button onClick={() => setSelectedRecord(null)}>
              {t('common.close')}
            </Button>
          }
        >
          <Row gutter={16} style={{ marginBottom: 16 }}>
            <Col span={6}>
              <Statistic title={t('filterChain.duration')} value={selectedRecord.totalDurationMs} suffix="ms" />
            </Col>
            <Col span={6}>
              <Statistic title={t('filterChain.filters')} value={selectedRecord.filterCount} />
            </Col>
            <Col span={6}>
              <Statistic
                title={t('filterChain.success')}
                value={selectedRecord.successCount}
                valueStyle={{ color: '#52c41a' }}
              />
            </Col>
            <Col span={6}>
              <Statistic
                title={t('filterChain.failures')}
                value={selectedRecord.failureCount}
                valueStyle={{ color: selectedRecord.failureCount > 0 ? '#ff4d4f' : '#52c41a' }}
              />
            </Col>
          </Row>

          {/* Professional Waterfall/Gantt Visualization */}
          <Card
            title="Filter执行瀑布图"
            size="small"
            style={{ marginBottom: 16, background: 'rgba(255,255,255,0.05)' }}
            extra={
              <Button
                size="small"
                type="primary"
                icon={<PictureOutlined />}
                onClick={exportChartAsImage}
              >
                导出PNG
              </Button>
            }
          >
            <WaterfallChart ref={waterfallChartRef} executions={selectedRecord.executions || []} totalDuration={selectedRecord.totalDurationMs} />
          </Card>

          <Table
            dataSource={selectedRecord.executions}
            columns={executionColumns}
            rowKey="filter"
            pagination={false}
            size="small"
          />
        </Card>
      )}

      {/* ✅ Threshold Configuration Modal */}
      <Modal
        title="性能阈值配置"
        open={showThresholdModal}
        onCancel={() => setShowThresholdModal(false)}
        onOk={() => {
          setShowThresholdModal(false);
          message.success('阈值配置已保存');
        }}
      >
        <Form layout="vertical">
          <Form.Item label="自身耗时阈值 (ms)">
            <InputNumber
              value={thresholds.selfTime}
              onChange={(v) => setThresholds({ ...thresholds, selfTime: v || 10 })}
              min={1}
              max={100}
              step={1}
            />
            <Text type="secondary" style={{ marginLeft: 8 }}>超过此值将触发告警</Text>
          </Form.Item>

          <Form.Item label="P95耗时阈值 (ms)">
            <InputNumber
              value={thresholds.p95}
              onChange={(v) => setThresholds({ ...thresholds, p95: v || 20 })}
              min={5}
              max={200}
              step={5}
            />
            <Text type="secondary" style={{ marginLeft: 8 }}>95%请求的性能阈值</Text>
          </Form.Item>

          <Form.Item label="P99耗时阈值 (ms)">
            <InputNumber
              value={thresholds.p99}
              onChange={(v) => setThresholds({ ...thresholds, p99: v || 50 })}
              min={10}
              max={500}
              step={10}
            />
            <Text type="secondary" style={{ marginLeft: 8 }}>99%请求的性能上限</Text>
          </Form.Item>

          <Form.Item label="成功率阈值 (%)">
            <InputNumber
              value={thresholds.successRate}
              onChange={(v) => setThresholds({ ...thresholds, successRate: v || 95 })}
              min={80}
              max={100}
              step={1}
            />
            <Text type="secondary" style={{ marginLeft: 8 }}>低于此值将触发告警</Text>
          </Form.Item>
        </Form>
      </Modal>

      {/* ✅ Export Modal */}
      <Modal
        title="导出数据"
        open={showExportModal}
        onCancel={() => setShowExportModal(false)}
        footer={null}
      >
        <Space direction="vertical" style={{ width: '100%' }}>
          <Button block icon={<DownloadOutlined />} onClick={() => exportData('json')}>
            导出JSON文件 (完整数据)
          </Button>
          <Button block icon={<DownloadOutlined />} onClick={() => exportData('csv')}>
            导出CSV文件 (表格数据)
          </Button>
          <Button block icon={<PictureOutlined />} onClick={exportChartAsImage} style={{ borderColor: '#1890ff' }}>
            导出瀑布图 (PNG图片)
          </Button>
          <Divider />
          <Text type="secondary" style={{ fontSize: 12 }}>
            • JSON/CSV：Filter统计数据、最近100条记录<br/>
            • PNG图片：需要先点击详情查看瀑布图后才能导出
          </Text>
        </Space>
      </Modal>

      {/* ✅ Keyboard shortcuts hint */}
      <div style={{
        marginTop: 16,
        padding: 12,
        background: 'rgba(255,255,255,0.05)',
        borderRadius: 6
      }}>
        <Space>
          <ThunderboltOutlined />
          <Text type="secondary">键盘快捷键：</Text>
          <Tag>Ctrl+R 刷新</Tag>
          <Tooltip title="Ctrl+E 在Windows上会被浏览器拦截，可用Alt+E替代">
            <Tag color="orange">Ctrl+E / Alt+E 导出</Tag>
          </Tooltip>
          <Tag>Ctrl+A 自动刷新</Tag>
        </Space>
      </div>

      <style>{`
        .filter-chain-page { padding: 0 }
      `}</style>
    </div>
  );
};

export default FilterChainPage;