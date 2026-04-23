import React, { useState, useEffect } from 'react';
import { Drawer, Descriptions, Tag, Progress, Statistic, Card, Row, Col, Alert, Spin, Empty } from 'antd';
import { CheckCircleOutlined, WarningOutlined, ClockCircleOutlined, FilterOutlined, ThunderboltOutlined } from '@ant-design/icons';
import { useTranslation } from 'react-i18next';
import api from '../utils/api';

interface FilterExecutionDetail {
  filterName: string;
  order: number;
  startTimeOffset: number;
  selfTimeMs: number;
  totalTimeMs: number;
  downstreamMs: number;
  success: boolean;
  errorMessage?: string;
  timePercentage: number;
}

interface FilterStats {
  totalCount: number;
  successCount: number;
  failureCount: number;
  avgSelfTimeMs: number;
  avgTotalTimeMs: number;
  selfP50Ms: number;
  selfP95Ms: number;
  selfP99Ms: number;
  maxSelfTimeMs: number;
  successRate: number;
}

interface FilterExecutionDetailDrawerProps {
  visible: boolean;
  execution: FilterExecutionDetail | null;
  onClose: () => void;
  instanceId?: string;
}

/**
 * Filter 执行详情抽屉组件
 * 
 * 点击瀑布图中的 Filter 条形后，显示详细的执行信息和历史统计
 */
const FilterExecutionDetailDrawer: React.FC<FilterExecutionDetailDrawerProps> = ({
  visible,
  execution,
  onClose,
  instanceId
}) => {
  const { t } = useTranslation();
  const [loading, setLoading] = useState(false);
  const [filterStats, setFilterStats] = useState<FilterStats | null>(null);

  // 加载该 Filter 的历史统计
  useEffect(() => {
    if (visible && execution) {
      loadFilterStats();
    }
  }, [visible, execution]);

  const loadFilterStats = async () => {
    if (!execution) return;
    
    try {
      setLoading(true);
      const params: any = { filterName: execution.filterName };
      if (instanceId) params.instanceId = instanceId;
      
      const res = await api.get('/internal/filter-chain/stats', { params });
      const stats = res.data?.filterStats?.[execution.filterName];
      
      if (stats) {
        setFilterStats({
          totalCount: stats.totalCount || 0,
          successCount: stats.successCount || 0,
          failureCount: stats.failureCount || 0,
          avgSelfTimeMs: stats.avgSelfTimeMicros ? stats.avgSelfTimeMicros / 1000 : 0,
          avgTotalTimeMs: stats.avgDurationMicros ? stats.avgDurationMicros / 1000 : 0,
          selfP50Ms: stats.selfP50Micros ? stats.selfP50Micros / 1000 : 0,
          selfP95Ms: stats.selfP95Micros ? stats.selfP95Micros / 1000 : 0,
          selfP99Ms: stats.selfP99Micros ? stats.selfP99Micros / 1000 : 0,
          maxSelfTimeMs: stats.maxSelfTimeMicros ? stats.maxSelfTimeMicros / 1000 : 0,
          successRate: stats.successRate || 0
        });
      }
    } catch (e) {
      console.error('Failed to load filter stats:', e);
      setFilterStats(null);
    } finally {
      setLoading(false);
    }
  };

  // 计算健康状态
  const getHealthStatus = (selfTimeMs: number): 'normal' | 'warning' | 'error' => {
    if (!execution?.success) return 'error';
    if (selfTimeMs > 50) return 'warning';
    return 'normal';
  };

  // 获取健康状态颜色
  const getHealthColor = (status: 'normal' | 'warning' | 'error') => {
    return status === 'error' ? '#ff4d4f' : status === 'warning' ? '#faad14' : '#52c41a';
  };

  if (!execution) return null;

  const healthStatus = getHealthStatus(execution.selfTimeMs);

  return (
    <Drawer
      title={
        <span>
          <FilterOutlined style={{ marginRight: 8 }} />
          {t('filter_detail.title')} - {execution.filterName}
        </span>
      }
      placement="right"
      width={500}
      onClose={onClose}
      open={visible}
      styles={{ body: { padding: 16 } }}
    >
      {/* 当前执行概览 */}
      <Card size="small" style={{ marginBottom: 16 }}>
        <Row gutter={16}>
          <Col span={6}>
            <Statistic 
              title={t('filter_detail.order')} 
              value={execution.order}
              prefix={<Tag color="blue">#</Tag>}
            />
          </Col>
          <Col span={6}>
            <Statistic 
              title={t('filter_detail.self_time')} 
              value={execution.selfTimeMs.toFixed(2)}
              suffix="ms"
              valueStyle={{ color: getHealthColor(healthStatus) }}
            />
          </Col>
          <Col span={6}>
            <Statistic 
              title={t('filter_detail.total_time')} 
              value={execution.totalTimeMs.toFixed(2)}
              suffix="ms"
            />
          </Col>
          <Col span={6}>
            <Statistic 
              title={t('filter_detail.status')} 
              valueStyle={{ color: getHealthColor(healthStatus) }}
              prefix={execution.success ? <CheckCircleOutlined /> : <WarningOutlined />}
              value={execution.success ? t('filter_detail.success') : t('filter_detail.failed')}
            />
          </Col>
        </Row>
      </Card>

      {/* 时间分解 */}
      <Card size="small" title={t('filter_detail.time_breakdown')} style={{ marginBottom: 16 }}>
        <Row gutter={16}>
          <Col span={12}>
            <div style={{ marginBottom: 8 }}>
              <span style={{ fontSize: 12 }}>{t('filter_detail.self_time')} (Filter自身逻辑)</span>
              <Progress 
                percent={Math.min(100, (execution.selfTimeMs / execution.totalTimeMs) * 100)}
                strokeColor={getHealthColor(healthStatus)}
                format={(p) => `${execution.selfTimeMs.toFixed(2)}ms (${p?.toFixed(1)}%)`}
              />
            </div>
          </Col>
          <Col span={12}>
            <div style={{ marginBottom: 8 }}>
              <span style={{ fontSize: 12 }}>{t('filter_detail.downstream_time')} (下游链耗时)</span>
              <Progress 
                percent={Math.min(100, (execution.downstreamMs / execution.totalTimeMs) * 100)}
                strokeColor="#1890ff"
                format={(p) => `${execution.downstreamMs.toFixed(2)}ms (${p?.toFixed(1)}%)`}
              />
            </div>
          </Col>
        </Row>
        
        {/* 时间占比 */}
        <div style={{ marginTop: 16 }}>
          <span style={{ fontSize: 12 }}>{t('filter_detail.request_percentage')}</span>
          <Progress 
            percent={execution.timePercentage || 0}
            strokeColor={execution.timePercentage > 30 ? '#cf1322' : '#52c41a'}
            format={(p) => `${p?.toFixed(1)}%`}
          />
        </div>
      </Card>

      {/* 错误信息 */}
      {!execution.success && execution.errorMessage && (
        <Alert
          type="error"
          showIcon
          style={{ marginBottom: 16 }}
          message={t('filter_detail.error_info')}
          description={execution.errorMessage}
        />
      )}

      {/* 历史统计 */}
      <Card size="small" title={<span><ClockCircleOutlined style={{ marginRight: 8 }} />{t('filter_detail.history_stats')}</span>}>
        {loading ? (
          <Spin style={{ display: 'block', textAlign: 'center', padding: 20 }} />
        ) : filterStats ? (
          <div>
            <Row gutter={16} style={{ marginBottom: 16 }}>
              <Col span={8}>
                <Statistic title={t('filter_detail.total_count')} value={filterStats.totalCount} />
              </Col>
              <Col span={8}>
                <Statistic 
                  title={t('filter_detail.success_rate')} 
                  value={filterStats.successRate}
                  suffix="%"
                  valueStyle={{ color: filterStats.successRate >= 95 ? '#52c41a' : '#faad14' }}
                />
              </Col>
              <Col span={8}>
                <Statistic 
                  title={t('filter_detail.avg_self_time')} 
                  value={filterStats.avgSelfTimeMs.toFixed(2)}
                  suffix="ms"
                />
              </Col>
            </Row>

            <Descriptions bordered size="small" column={2}>
              <Descriptions.Item label={t('filter_detail.p50')}>
                <Tag color="blue">{filterStats.selfP50Ms.toFixed(2)}ms</Tag>
              </Descriptions.Item>
              <Descriptions.Item label={t('filter_detail.p95')}>
                <Tag color={filterStats.selfP95Ms > 50 ? 'warning' : 'green'}>
                  {filterStats.selfP95Ms.toFixed(2)}ms
                </Tag>
              </Descriptions.Item>
              <Descriptions.Item label={t('filter_detail.p99')}>
                <Tag color={filterStats.selfP99Ms > 100 ? 'error' : filterStats.selfP99Ms > 50 ? 'warning' : 'green'}>
                  {filterStats.selfP99Ms.toFixed(2)}ms
                </Tag>
              </Descriptions.Item>
              <Descriptions.Item label={t('filter_detail.max_self_time')}>
                <Tag color={filterStats.maxSelfTimeMs > 100 ? 'error' : 'default'}>
                  {filterStats.maxSelfTimeMs.toFixed(2)}ms
                </Tag>
              </Descriptions.Item>
            </Descriptions>

            {/* 性能分析 */}
            <Alert
              type={filterStats.selfP95Ms > 50 ? 'warning' : 'info'}
              showIcon
              style={{ marginTop: 16 }}
              message={t('filter_detail.performance_analysis')}
              description={
                filterStats.selfP95Ms > 50 
                  ? t('filter_detail.p95_high_warning', { filter: execution.filterName, p95: filterStats.selfP95Ms.toFixed(2) })
                  : t('filter_detail.performance_good')
              }
            />
          </div>
        ) : (
          <Empty description={t('filter_detail.no_history')} />
        )}
      </Card>
    </Drawer>
  );
};

export default FilterExecutionDetailDrawer;