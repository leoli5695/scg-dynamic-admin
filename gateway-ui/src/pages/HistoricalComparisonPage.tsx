import React, { useState, useEffect } from 'react';
import {
  Card, Row, Col, Select, Button, Statistic, Tag, Table, Alert,
  Space, Typography, DatePicker, Spin, Segmented, Tooltip, Progress
} from 'antd';
import {
  ClockCircleOutlined, WarningOutlined, CheckCircleOutlined,
  SyncOutlined, ArrowDownOutlined, ArrowUpOutlined, MinusOutlined,
  ThunderboltOutlined, FilterOutlined, CalendarOutlined
} from '@ant-design/icons';
import type { ColumnsType } from 'antd/es/table';
import dayjs from 'dayjs';
import ReactECharts from 'echarts-for-react';
import api from '../utils/api';
import { useTranslation } from 'react-i18next';

const { Title, Text } = Typography;
const { RangePicker } = DatePicker;

interface FilterComparison {
  filterName: string;
  currentMetrics: {
    avgSelfTimeMs: number;
    avgP95Ms: number;
    successRate: number;
    totalExecutions: number;
  };
  historicalMetrics: {
    avgSelfTimeMs: number;
    avgP95Ms: number;
    successRate: number;
    totalExecutions: number;
  };
  selfTimeRegression: number;
  p95Regression: number;
  successRateRegression: number;
  regressionLevel: 'critical' | 'warning' | 'normal';
  trendDirection: 'improving' | 'stable' | 'degrading';
  hasRegression: boolean;
}

interface RegressionAlert {
  alertType: string;
  severity: string;
  filterName: string;
  metricName: string;
  regressionPercent: number;
  currentValue: number;
  historicalValue: number;
  message: string;
  recommendation: string;
}

interface ComparisonResult {
  comparisonType: string;
  currentPeriod: { start: string; end: string };
  historicalPeriod: { start: string; end: string };
  overallComparison: {
    avgSelfTimeRegression: number;
    avgP95Regression: number;
    avgSuccessRateRegression: number;
    overallHealthScoreChange: number;
  };
  filterComparisons: FilterComparison[];
  regressionAlerts: RegressionAlert[];
  regressionThreshold: number;
  status: string;
}

/**
 * Historical Comparison Page
 * 
 * Compare filter performance between different time periods
 * to detect performance regression and degradation patterns.
 */
const HistoricalComparisonPage: React.FC = () => {
  const { t } = useTranslation();

  const [loading, setLoading] = useState(false);
  const [comparisonType, setComparisonType] = useState<string>('HOUR_VS_HOUR');
  const [regressionThreshold, setRegressionThreshold] = useState<number>(20);
  const [customTimeRange, setCustomTimeRange] = useState<[dayjs.Dayjs, dayjs.Dayjs] | null>(null);
  const [customHistoricalRange, setCustomHistoricalRange] = useState<[dayjs.Dayjs, dayjs.Dayjs] | null>(null);
  const [comparisonResult, setComparisonResult] = useState<ComparisonResult | null>(null);

  // Load comparison data
  const loadComparison = async () => {
    try {
      setLoading(true);

      const params: any = {
        comparisonType,
        regressionThreshold
      };

      if (comparisonType === 'CUSTOM' && customTimeRange && customHistoricalRange) {
        params.currentStart = customTimeRange[0].format('YYYY-MM-DDTHH:mm:ss');
        params.currentEnd = customTimeRange[1].format('YYYY-MM-DDTHH:mm:ss');
        params.historicalStart = customHistoricalRange[0].format('YYYY-MM-DDTHH:mm:ss');
        params.historicalEnd = customHistoricalRange[1].format('YYYY-MM-DDTHH:mm:ss');
      }

      const res = await api.get('/internal/filter-chain/comparison', { params });
      setComparisonResult(res.data);
    } catch (e) {
      console.error('Failed to load comparison:', e);
      setComparisonResult(null);
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => {
    loadComparison();
  }, [comparisonType, regressionThreshold]);

  // Get regression level color
  const getRegressionColor = (level: string) => {
    if (level === 'critical') return '#cf1322';
    if (level === 'warning') return '#faad14';
    return '#52c41a';
  };

  // Get trend icon
  const getTrendIcon = (direction: string) => {
    if (direction === 'degrading') return <ArrowDownOutlined style={{ color: '#cf1322' }} />;
    if (direction === 'improving') return <ArrowUpOutlined style={{ color: '#52c41a' }} />;
    return <MinusOutlined style={{ color: '#8c8c8c' }} />;
  };

  // Table columns for filter comparisons
  const filterColumns: ColumnsType<FilterComparison> = [
    {
      title: t('comparison.filter_name'),
      dataIndex: 'filterName',
      key: 'filterName',
      width: 180,
      fixed: 'left',
      render: (name: string, record) => (
        <Space>
          {record.hasRegression && <WarningOutlined style={{ color: getRegressionColor(record.regressionLevel) }} />}
          <Text strong={record.hasRegression}>{name}</Text>
        </Space>
      )
    },
    {
      title: t('comparison.self_time_current'),
      dataIndex: ['currentMetrics', 'avgSelfTimeMs'],
      key: 'currentSelfTime',
      width: 100,
      render: (val: number) => <Text>{val.toFixed(2)}ms</Text>
    },
    {
      title: t('comparison.self_time_historical'),
      dataIndex: ['historicalMetrics', 'avgSelfTimeMs'],
      key: 'historicalSelfTime',
      width: 100,
      render: (val: number) => <Text>{val.toFixed(2)}ms</Text>
    },
    {
      title: t('comparison.self_time_regression'),
      dataIndex: 'selfTimeRegression',
      key: 'selfTimeRegression',
      width: 120,
      sorter: (a, b) => a.selfTimeRegression - b.selfTimeRegression,
      render: (val: number, record) => (
        <Tag color={getRegressionColor(record.regressionLevel)}>
          {val > 0 ? '+' : ''}{val.toFixed(1)}%
        </Tag>
      )
    },
    {
      title: t('comparison.p95_current'),
      dataIndex: ['currentMetrics', 'avgP95Ms'],
      key: 'currentP95',
      width: 90,
      render: (val: number) => <Text>{val.toFixed(2)}ms</Text>
    },
    {
      title: t('comparison.p95_historical'),
      dataIndex: ['historicalMetrics', 'avgP95Ms'],
      key: 'historicalP95',
      width: 90,
      render: (val: number) => <Text>{val.toFixed(2)}ms</Text>
    },
    {
      title: t('comparison.p95_regression'),
      dataIndex: 'p95Regression',
      key: 'p95Regression',
      width: 100,
      sorter: (a, b) => a.p95Regression - b.p95Regression,
      render: (val: number) => (
        <Tag color={val > 30 ? '#cf1322' : val > 10 ? '#faad14' : 'default'}>
          {val > 0 ? '+' : ''}{val.toFixed(1)}%
        </Tag>
      )
    },
    {
      title: t('comparison.success_rate_current'),
      dataIndex: ['currentMetrics', 'successRate'],
      key: 'currentSuccessRate',
      width: 90,
      render: (val: number) => (
        <Text type={val < 95 ? 'danger' : undefined}>{val.toFixed(1)}%</Text>
      )
    },
    {
      title: t('comparison.success_rate_historical'),
      dataIndex: ['historicalMetrics', 'successRate'],
      key: 'historicalSuccessRate',
      width: 90,
      render: (val: number) => <Text>{val.toFixed(1)}%</Text>
    },
    {
      title: t('comparison.trend'),
      dataIndex: 'trendDirection',
      key: 'trendDirection',
      width: 80,
      fixed: 'right',
      render: (direction: string) => (
        <Tooltip title={t(`comparison.trend_${direction}`)}>
          {getTrendIcon(direction)}
        </Tooltip>
      )
    }
  ];

  // Table columns for regression alerts
  const alertColumns: ColumnsType<RegressionAlert> = [
    {
      title: t('comparison.severity'),
      dataIndex: 'severity',
      key: 'severity',
      width: 80,
      render: (severity: string) => (
        <Tag color={severity === 'critical' ? 'error' : 'warning'}>
          {severity.toUpperCase()}
        </Tag>
      )
    },
    {
      title: t('comparison.filter_name'),
      dataIndex: 'filterName',
      key: 'filterName',
      width: 150
    },
    {
      title: t('comparison.metric'),
      dataIndex: 'metricName',
      key: 'metricName',
      width: 120
    },
    {
      title: t('comparison.change'),
      dataIndex: 'regressionPercent',
      key: 'regressionPercent',
      width: 100,
      render: (val: number) => (
        <Text type="danger">+{val.toFixed(1)}%</Text>
      )
    },
    {
      title: t('comparison.message'),
      dataIndex: 'message',
      key: 'message',
      ellipsis: true
    },
    {
      title: t('comparison.recommendation'),
      dataIndex: 'recommendation',
      key: 'recommendation',
      ellipsis: true,
      render: (rec: string) => (
        <Tooltip title={rec}>
          <Text type="secondary">{rec}</Text>
        </Tooltip>
      )
    }
  ];

  // ECharts option for comparison bar chart
  const getChartOption = () => {
    if (!comparisonResult || comparisonResult.filterComparisons.length === 0) return {};

    const filters = comparisonResult.filterComparisons.map(f => f.filterName);
    const currentData = comparisonResult.filterComparisons.map(f => f.currentMetrics.avgSelfTimeMs);
    const historicalData = comparisonResult.filterComparisons.map(f => f.historicalMetrics.avgSelfTimeMs);

    return {
      title: {
        text: t('comparison.chart_title'),
        left: 'center'
      },
      tooltip: {
        trigger: 'axis',
        axisPointer: { type: 'shadow' }
      },
      legend: {
        data: [t('comparison.current_period'), t('comparison.historical_period')],
        bottom: 0
      },
      grid: {
        left: '3%',
        right: '4%',
        bottom: '15%',
        containLabel: true
      },
      xAxis: {
        type: 'category',
        data: filters,
        axisLabel: {
          rotate: 30,
          fontSize: 10
        }
      },
      yAxis: {
        type: 'value',
        name: 'ms',
        axisLabel: {
          formatter: '{value}ms'
        }
      },
      series: [
        {
          name: t('comparison.current_period'),
          type: 'bar',
          data: currentData,
          itemStyle: {
            color: (params: any) => {
              const idx = params.dataIndex;
              const regression = comparisonResult.filterComparisons[idx].selfTimeRegression;
              return regression > 50 ? '#cf1322' : regression > 20 ? '#faad14' : '#1890ff';
            }
          }
        },
        {
          name: t('comparison.historical_period'),
          type: 'bar',
          data: historicalData,
          itemStyle: { color: '#52c41a' }
        }
      ]
    };
  };

  return (
    <div style={{ padding: 24 }}>
      {/* Header */}
      <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: 16 }}>
        <Title level={3} style={{ margin: 0 }}>
          <ClockCircleOutlined style={{ marginRight: 8 }} />
          {t('comparison.title')}
        </Title>
        <Button icon={<SyncOutlined />} onClick={loadComparison} loading={loading}>
          {t('common.refresh')}
        </Button>
      </div>

      {/* Control Panel */}
      <Card size="small" style={{ marginBottom: 16 }}>
        <Row gutter={16} align="middle">
          <Col>
            <Space>
              <Text strong>{t('comparison.type')}:</Text>
              <Select
                value={comparisonType}
                onChange={setComparisonType}
                options={[
                  { value: 'HOUR_VS_HOUR', label: t('comparison.hour_vs_hour') },
                  { value: 'DAY_VS_DAY', label: t('comparison.day_vs_day') },
                  { value: 'WEEK_VS_WEEK', label: t('comparison.week_vs_week') },
                  { value: 'CUSTOM', label: t('comparison.custom') }
                ]}
                style={{ width: 150 }}
              />
            </Space>
          </Col>
          <Col>
            <Space>
              <Text strong>{t('comparison.threshold')}:</Text>
              <Select
                value={regressionThreshold}
                onChange={setRegressionThreshold}
                options={[
                  { value: 10, label: '10%' },
                  { value: 20, label: '20%' },
                  { value: 30, label: '30%' },
                  { value: 50, label: '50%' }
                ]}
                style={{ width: 100 }}
              />
            </Space>
          </Col>
          {comparisonType === 'CUSTOM' && (
            <Col>
              <Space direction="vertical" size={4}>
                <Text type="secondary">{t('comparison.current_range')}:</Text>
                <RangePicker
                  showTime
                  value={customTimeRange}
                  onChange={(dates) => setCustomTimeRange(dates as [dayjs.Dayjs, dayjs.Dayjs] | null)}
                  format="YYYY-MM-DD HH:mm"
                  style={{ width: 300 }}
                />
              </Space>
            </Col>
          )}
          {comparisonType === 'CUSTOM' && (
            <Col>
              <Space direction="vertical" size={4}>
                <Text type="secondary">{t('comparison.historical_range')}:</Text>
                <RangePicker
                  showTime
                  value={customHistoricalRange}
                  onChange={(dates) => setCustomHistoricalRange(dates as [dayjs.Dayjs, dayjs.Dayjs] | null)}
                  format="YYYY-MM-DD HH:mm"
                  style={{ width: 300 }}
                />
              </Space>
            </Col>
          )}
          {comparisonType === 'CUSTOM' && (
            <Col>
              <Button type="primary" onClick={loadComparison} loading={loading}>
                {t('comparison.compare')}
              </Button>
            </Col>
          )}
        </Row>
      </Card>

      {loading && <Spin style={{ display: 'block', textAlign: 'center', padding: 50 }} />}

      {!loading && comparisonResult && (
        <>
          {/* Overall Summary */}
          <Row gutter={16} style={{ marginBottom: 16 }}>
            <Col span={6}>
              <Card size="small">
                <Statistic
                  title={t('comparison.avg_self_time_change')}
                  value={comparisonResult.overallComparison.avgSelfTimeRegression}
                  precision={1}
                  suffix="%"
                  valueStyle={{
                    color: comparisonResult.overallComparison.avgSelfTimeRegression > 20 ? '#cf1322' :
                           comparisonResult.overallComparison.avgSelfTimeRegression > 0 ? '#faad14' : '#52c41a'
                  }}
                  prefix={comparisonResult.overallComparison.avgSelfTimeRegression > 0 ? <ArrowDownOutlined /> : <ArrowUpOutlined />}
                />
              </Card>
            </Col>
            <Col span={6}>
              <Card size="small">
                <Statistic
                  title={t('comparison.avg_p95_change')}
                  value={comparisonResult.overallComparison.avgP95Regression}
                  precision={1}
                  suffix="%"
                  valueStyle={{
                    color: comparisonResult.overallComparison.avgP95Regression > 30 ? '#cf1322' :
                           comparisonResult.overallComparison.avgP95Regression > 0 ? '#faad14' : '#52c41a'
                  }}
                />
              </Card>
            </Col>
            <Col span={6}>
              <Card size="small">
                <Statistic
                  title={t('comparison.regressions_found')}
                  value={comparisonResult.regressionAlerts.length}
                  valueStyle={{ color: comparisonResult.regressionAlerts.length > 0 ? '#cf1322' : '#52c41a' }}
                  suffix={comparisonResult.regressionAlerts.length > 0 ? <WarningOutlined /> : <CheckCircleOutlined />}
                />
              </Card>
            </Col>
            <Col span={6}>
              <Card size="small">
                <Statistic
                  title={t('comparison.filters_analyzed')}
                  value={comparisonResult.filterComparisons.length}
                />
              </Card>
            </Col>
          </Row>

          {/* Time Period Info */}
          <Card size="small" style={{ marginBottom: 16 }}>
            <Row gutter={16}>
              <Col span={12}>
                <Text strong>{t('comparison.current_period')}: </Text>
                <Text>
                  {comparisonResult.currentPeriod.start} ~ {comparisonResult.currentPeriod.end}
                </Text>
              </Col>
              <Col span={12}>
                <Text strong>{t('comparison.historical_period')}: </Text>
                <Text>
                  {comparisonResult.historicalPeriod.start} ~ {comparisonResult.historicalPeriod.end}
                </Text>
              </Col>
            </Row>
          </Card>

          {/* Regression Alerts */}
          {comparisonResult.regressionAlerts.length > 0 && (
            <Alert
              type="warning"
              showIcon
              style={{ marginBottom: 16 }}
              message={t('comparison.regression_detected')}
              description={t('comparison.regression_desc', { count: comparisonResult.regressionAlerts.length })}
            />
          )}

          {/* Comparison Chart */}
          <Card size="small" title={<span><ThunderboltOutlined /> {t('comparison.chart')}</span>} style={{ marginBottom: 16 }}>
            <ReactECharts option={getChartOption()} style={{ height: 300 }} />
          </Card>

          {/* Regression Alerts Table */}
          {comparisonResult.regressionAlerts.length > 0 && (
            <Card size="small" title={<span><WarningOutlined /> {t('comparison.alerts')}</span>} style={{ marginBottom: 16 }}>
              <Table
                size="small"
                dataSource={comparisonResult.regressionAlerts}
                columns={alertColumns}
                rowKey={(record, idx) => `${record.filterName}_${record.metricName}_${idx}`}
                pagination={false}
              />
            </Card>
          )}

          {/* Filter Comparisons Table */}
          <Card size="small" title={<span><FilterOutlined /> {t('comparison.filter_details')}</span>}>
            <Table
              size="small"
              dataSource={comparisonResult.filterComparisons}
              columns={filterColumns}
              rowKey="filterName"
              scroll={{ x: 1000 }}
              pagination={{ pageSize: 10 }}
            />
          </Card>
        </>
      )}

      {!loading && !comparisonResult && (
        <Card>
          <Text type="secondary">{t('comparison.no_data')}</Text>
        </Card>
      )}
    </div>
  );
};

export default HistoricalComparisonPage;