import React, { useState, useEffect, useRef } from 'react';
import {
  Card, Row, Col, Progress, Spin, Alert, Tag, Typography, Space,
  Button, Collapse, Descriptions, Badge, Statistic, Divider, Tooltip, message,
  Switch, Select, Tabs
} from 'antd';
import {
  MedicineBoxOutlined, CheckCircleOutlined, WarningOutlined,
  CloseCircleOutlined, ReloadOutlined, DashboardOutlined,
  DatabaseOutlined, CloudServerOutlined, LockOutlined,
  ApiOutlined, ThunderboltOutlined, RocketOutlined,
  HistoryOutlined, SyncOutlined, LineChartOutlined
} from '@ant-design/icons';
import axios from 'axios';
import { useTranslation } from 'react-i18next';
import ReactECharts from 'echarts-for-react';

const { Text, Title, Paragraph } = Typography;
const { Panel } = Collapse;

interface ComponentDiagnostic {
  name: string;
  type: string;
  status: string;
  metrics: Record<string, any>;
  warnings?: string[];
  errors?: string[];
}

interface DiagnosticReport {
  startTime: number;
  endTime: number;
  duration: string;
  overallScore: number;
  status: string;
  database?: ComponentDiagnostic;
  redis?: ComponentDiagnostic;
  configCenter?: ComponentDiagnostic;
  routes?: ComponentDiagnostic;
  auth?: ComponentDiagnostic;
  gatewayInstances?: ComponentDiagnostic;
  performance?: ComponentDiagnostic;
  recommendations?: string[];
}

interface HistoryRecord {
  id: number;
  createdAt: string;
  type: string;
  score: number;
  status: string;
  durationMs: number;
  gatewayQps?: number;
  gatewayErrorRate?: number;
  gatewayAvgLatencyMs?: number;
  gatewayHeapUsagePercent?: number;
  gatewayCpuUsagePercent?: number;
}

interface TrendData {
  timestamps: number[];
  scores: number[];
  avgScore?: number;
  minScore?: number;
  maxScore?: number;
}

interface DiagnosticPageProps {
  instanceId?: string;
}

const DiagnosticPage: React.FC<DiagnosticPageProps> = ({ instanceId }) => {
  const [loading, setLoading] = useState(false);
  const [report, setReport] = useState<DiagnosticReport | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [history, setHistory] = useState<HistoryRecord[]>([]);
  const [trend, setTrend] = useState<TrendData | null>(null);
  const [comparison, setComparison] = useState<any>(null);
  
  // Auto refresh settings
  const [autoRefresh, setAutoRefresh] = useState(false);
  const [refreshInterval, setRefreshInterval] = useState(60); // seconds
  const refreshTimerRef = useRef<NodeJS.Timeout | null>(null);
  
  // History time range
  const [historyHours, setHistoryHours] = useState(24);
  
  const { t } = useTranslation();

  const runDiagnostic = async (type: 'full' | 'quick' = 'full') => {
    setLoading(true);
    setError(null);
    try {
      const params = instanceId ? `?instanceId=${instanceId}` : '';
      const response = await axios.get(`/api/diagnostic/${type}${params}`);
      if (response.data) {
        setReport(response.data);
        // Fetch comparison after diagnostic
        fetchComparison();
        // Refresh history after diagnostic
        fetchHistory();
      } else {
        setError('Failed to get diagnostic report');
      }
    } catch (e: any) {
      setError(e.message || 'Failed to run diagnostic');
      message.error(t('diagnostic.run_failed') || '诊断运行失败');
    } finally {
      setLoading(false);
    }
  };

  const fetchHistory = async () => {
    try {
      const params = new URLSearchParams({
        hours: historyHours.toString()
      });
      if (instanceId) params.append('instanceId', instanceId);
      
      const response = await axios.get(`/api/diagnostic/history?${params}`);
      if (response.data) {
        setHistory(response.data.history || []);
        setTrend(response.data.trend || null);
      }
    } catch (e: any) {
      console.error('Failed to fetch history:', e);
    }
  };

  const fetchComparison = async () => {
    try {
      const response = await axios.get('/api/diagnostic/compare');
      if (response.data) {
        setComparison(response.data);
      }
    } catch (e: any) {
      console.error('Failed to fetch comparison:', e);
    }
  };

  // Auto refresh effect
  useEffect(() => {
    if (autoRefresh) {
      refreshTimerRef.current = setInterval(() => {
        runDiagnostic('quick');
      }, refreshInterval * 1000);
    } else {
      if (refreshTimerRef.current) {
        clearInterval(refreshTimerRef.current);
        refreshTimerRef.current = null;
      }
    }
    
    return () => {
      if (refreshTimerRef.current) {
        clearInterval(refreshTimerRef.current);
      }
    };
  }, [autoRefresh, refreshInterval, instanceId]);

  // Fetch history when hours change
  useEffect(() => {
    fetchHistory();
  }, [historyHours, instanceId]);

  useEffect(() => {
    runDiagnostic('quick');
    fetchHistory();
  }, [instanceId]);

  const getStatusIcon = (status: string) => {
    switch (status) {
      case 'HEALTHY':
        return <CheckCircleOutlined style={{ color: '#52c41a', fontSize: 20 }} />;
      case 'WARNING':
        return <WarningOutlined style={{ color: '#faad14', fontSize: 20 }} />;
      case 'CRITICAL':
        return <CloseCircleOutlined style={{ color: '#ff4d4f', fontSize: 20 }} />;
      case 'NOT_CONFIGURED':
        return <WarningOutlined style={{ color: '#8c8c8c', fontSize: 20 }} />;
      default:
        return <WarningOutlined style={{ color: '#8c8c8c', fontSize: 20 }} />;
    }
  };

  const getStatusColor = (status: string) => {
    switch (status) {
      case 'HEALTHY':
        return 'success';
      case 'WARNING':
        return 'warning';
      case 'CRITICAL':
        return 'error';
      default:
        return 'default';
    }
  };

  const getScoreColor = (score: number) => {
    if (score >= 80) return '#52c41a';
    if (score >= 50) return '#faad14';
    return '#ff4d4f';
  };

  const getScoreStatus = (score: number): 'success' | 'normal' | 'exception' => {
    if (score >= 80) return 'success';
    if (score >= 50) return 'normal';
    return 'exception';
  };

  // Score trend chart options
  const getScoreTrendChartOptions = () => {
    if (!trend || !trend.timestamps || trend.timestamps.length === 0) {
      return {};
    }

    return {
      title: {
        text: t('diagnostic.score_trend') || '健康评分趋势',
        left: 'center',
        textStyle: { fontSize: 14 }
      },
      tooltip: {
        trigger: 'axis',
        formatter: (params: any) => {
          const date = new Date(params[0].value[0]).toLocaleString();
          const score = params[0].value[1];
          return `${date}<br/>评分: ${score}`;
        }
      },
      xAxis: {
        type: 'time',
        axisLabel: {
          formatter: (value: number) => new Date(value).toLocaleTimeString()
        }
      },
      yAxis: {
        type: 'value',
        min: 0,
        max: 100,
        axisLabel: { formatter: '{value}' }
      },
      series: [{
        type: 'line',
        data: trend.timestamps.map((t, i) => [t, trend.scores[i]]),
        smooth: true,
        lineStyle: { width: 2 },
        areaStyle: {
          color: {
            type: 'linear',
            x: 0, y: 0, x2: 0, y2: 1,
            colorStops: [
              { offset: 0, color: 'rgba(82, 196, 26, 0.3)' },
              { offset: 1, color: 'rgba(82, 196, 26, 0.05)' }
            ]
          }
        },
        markLine: {
          silent: true,
          data: [
            { yAxis: 80, lineStyle: { color: '#52c41a' }, label: { formatter: '健康线' } },
            { yAxis: 50, lineStyle: { color: '#faad14' }, label: { formatter: '警告线' } }
          ]
        }
      }],
      grid: { left: 50, right: 20, top: 40, bottom: 30 }
    };
  };

  const renderComponentCard = (
    title: string,
    icon: React.ReactNode,
    diagnostic?: ComponentDiagnostic
  ) => {
    if (!diagnostic) {
      return (
        <Card className="diagnostic-card">
          <div style={{ textAlign: 'center', padding: 20 }}>
            <Text type="secondary">{t('diagnostic.no_data') || '无数据'}</Text>
          </div>
        </Card>
      );
    }

    const metricsEntries = Object.entries(diagnostic.metrics || {});
    const hasWarnings = diagnostic.warnings && diagnostic.warnings.length > 0;
    const hasErrors = diagnostic.errors && diagnostic.errors.length > 0;

    return (
      <Card 
        className="diagnostic-card"
        title={
          <Space>
            {icon}
            <Text strong>{title}</Text>
          </Space>
        }
        extra={
          <Space>
            {getStatusIcon(diagnostic.status)}
            <Tag color={getStatusColor(diagnostic.status)}>{diagnostic.status}</Tag>
          </Space>
        }
      >
        <Descriptions column={2} size="small">
          {metricsEntries.map(([key, value]) => (
            <Descriptions.Item key={key} label={t(`diagnostic.metric.${key}`) || key}>
              {typeof value === 'boolean' ? (
                <Tag color={value ? 'green' : 'default'}>{value ? t('common.yes') : t('common.no')}</Tag>
              ) : typeof value === 'string' && (value === 'PASS' || value.startsWith('PASS')) ? (
                <Tag color="green">{t('diagnostic.metric.PASS')}</Tag>
              ) : typeof value === 'string' && (value === 'FAIL' || value.startsWith('FAIL')) ? (
                <Tag color="red">{t('diagnostic.metric.FAIL')}</Tag>
              ) : (
                <Text>{value}</Text>
              )}
            </Descriptions.Item>
          ))}
        </Descriptions>

        {hasWarnings && (
          <div style={{ marginTop: 12 }}>
            <Alert
              type="warning"
              showIcon
              message={t('diagnostic.warnings') || '警告'}
              description={
                <ul style={{ margin: 0, paddingLeft: 20 }}>
                  {diagnostic.warnings!.map((w, i) => (
                    <li key={i}>{w}</li>
                  ))}
                </ul>
              }
            />
          </div>
        )}

        {hasErrors && (
          <div style={{ marginTop: 12 }}>
            <Alert
              type="error"
              showIcon
              message={t('diagnostic.errors') || '错误'}
              description={
                <ul style={{ margin: 0, paddingLeft: 20 }}>
                  {diagnostic.errors!.map((e, i) => (
                    <li key={i}>{e}</li>
                  ))}
                </ul>
              }
            />
          </div>
        )}
      </Card>
    );
  };

  if (loading && !report) {
    return (
      <div style={{ textAlign: 'center', padding: 100 }}>
        <Spin size="large" />
        <div style={{ marginTop: 16 }}>
          <Text type="secondary">{t('diagnostic.running') || '正在运行诊断...'}</Text>
        </div>
      </div>
    );
  }

  return (
    <div className="diagnostic-page">
      <div className="page-header" style={{
        display: 'flex',
        justifyContent: 'space-between',
        alignItems: 'center',
        marginBottom: 16
      }}>
        <Title level={4} style={{ margin: 0 }}>
          <MedicineBoxOutlined style={{ marginRight: 8 }} />
          {t('diagnostic.title') || '系统诊断'}
        </Title>
        <Space>
          {/* Auto refresh controls */}
          <Space>
            <Text type="secondary">{t('diagnostic.auto_refresh') || '自动刷新'}</Text>
            <Switch 
              checked={autoRefresh} 
              onChange={setAutoRefresh}
              checkedChildren={<SyncOutlined spin />}
              unCheckedChildren={<SyncOutlined />}
            />
            {autoRefresh && (
              <Select 
                value={refreshInterval}
                onChange={setRefreshInterval}
                style={{ width: 100 }}
                options={[
                  { value: 30, label: '30s' },
                  { value: 60, label: '1min' },
                  { value: 120, label: '2min' },
                  { value: 300, label: '5min' }
                ]}
              />
            )}
          </Space>
          <Button
            type="primary"
            icon={<RocketOutlined />}
            onClick={() => runDiagnostic('full')}
            loading={loading}
          >
            {t('diagnostic.full_check') || '完整诊断'}
          </Button>
          <Button
            icon={<ThunderboltOutlined />}
            onClick={() => runDiagnostic('quick')}
            loading={loading}
          >
            {t('diagnostic.quick_check') || '快速诊断'}
          </Button>
        </Space>
      </div>

      {error && (
        <Alert
          message={t('common.error') || '错误'}
          description={error}
          type="error"
          showIcon
          closable
          style={{ marginBottom: 16 }}
          onClose={() => setError(null)}
        />
      )}

      {/* Overall Score Card */}
      {report && (
        <Card style={{ marginBottom: 16 }}>
          <Row gutter={24} align="middle">
            <Col xs={24} sm={8} md={6}>
              <div style={{ textAlign: 'center' }}>
                <Progress
                  type="circle"
                  percent={report.overallScore}
                  strokeColor={getScoreColor(report.overallScore)}
                  status={getScoreStatus(report.overallScore)}
                  format={(percent) => (
                    <div>
                      <div style={{ fontSize: 24, fontWeight: 'bold' }}>{percent}</div>
                      <div style={{ fontSize: 12 }}>{t('diagnostic.score') || '健康评分'}</div>
                    </div>
                  )}
                />
              </div>
            </Col>
            <Col xs={24} sm={16} md={18}>
              <Row gutter={16}>
                <Col span={8}>
                  <Statistic
                    title={t('diagnostic.status') || '状态'}
                    value={report.status}
                    valueStyle={{ 
                      color: getScoreColor(report.overallScore),
                      fontSize: 18
                    }}
                    prefix={getStatusIcon(report.status)}
                  />
                </Col>
                <Col span={8}>
                  <Statistic
                    title={t('diagnostic.duration') || '耗时'}
                    value={report.duration}
                    suffix=""
                  />
                </Col>
                <Col span={8}>
                  <Statistic
                    title={t('diagnostic.timestamp') || '时间'}
                    value={new Date(report.endTime).toLocaleTimeString()}
                  />
                </Col>
              </Row>
              
              {/* Recommendations */}
              {report.recommendations && report.recommendations.length > 0 && (
                <div style={{ marginTop: 16 }}>
                  <Text strong>{t('diagnostic.recommendations') || '建议措施'}:</Text>
                  <ul style={{ margin: '8px 0 0 20px' }}>
                    {report.recommendations.map((rec, i) => (
                      <li key={i}>
                        <Text>{rec}</Text>
                      </li>
                    ))}
                  </ul>
                </div>
              )}
            </Col>
          </Row>
        </Card>
      )}

      {/* Component Diagnostics */}
      {report && (
        <Row gutter={[16, 16]}>
          <Col xs={24} lg={12}>
            {renderComponentCard(
              t('diagnostic.database') || '数据库',
              <DatabaseOutlined />,
              report.database
            )}
          </Col>
          <Col xs={24} lg={12}>
            {renderComponentCard(
              t('diagnostic.redis') || 'Redis',
              <CloudServerOutlined />,
              report.redis
            )}
          </Col>
          <Col xs={24} lg={12}>
            {renderComponentCard(
              t('diagnostic.config_center') || '配置中心',
              <ApiOutlined />,
              report.configCenter
            )}
          </Col>
          <Col xs={24} lg={12}>
            {renderComponentCard(
              t('diagnostic.routes') || '路由配置',
              <DashboardOutlined />,
              report.routes
            )}
          </Col>
          <Col xs={24} lg={12}>
            {renderComponentCard(
              t('diagnostic.auth') || '认证配置',
              <LockOutlined />,
              report.auth
            )}
          </Col>
          <Col xs={24} lg={12}>
            {renderComponentCard(
              t('diagnostic.gateway_instances') || '网关实例',
              <CloudServerOutlined />,
              report.gatewayInstances
            )}
          </Col>
          <Col xs={24}>
            {renderComponentCard(
              t('diagnostic.performance') || '性能指标',
              <DashboardOutlined />,
              report.performance
            )}
          </Col>
        </Row>
      )}

      {/* History Trend Charts */}
      {trend && trend.timestamps && trend.timestamps.length > 0 && (
        <Card 
          style={{ marginTop: 16 }}
          title={
            <Space>
              <LineChartOutlined />
              <Text strong>{t('diagnostic.history_trend') || '历史趋势'}</Text>
              <Select
                value={historyHours}
                onChange={setHistoryHours}
                style={{ width: 120, marginLeft: 16 }}
                options={[
                  { value: 1, label: '最近1小时' },
                  { value: 6, label: '最近6小时' },
                  { value: 12, label: '最近12小时' },
                  { value: 24, label: '最近24小时' },
                  { value: 72, label: '最近3天' }
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
                    option={getScoreTrendChartOptions()}
                    style={{ height: 300 }}
                    notMerge={true}
                  />
                )
              },
              {
                key: 'metrics',
                label: t('diagnostic.gateway_metrics') || '网关指标',
                children: (() => {
                  // 检查是否有实际的网关指标数据
                  const hasValidData = history.some(h => 
                    (h.gatewayQps && h.gatewayQps > 0) ||
                    (h.gatewayErrorRate && h.gatewayErrorRate > 0) ||
                    (h.gatewayHeapUsagePercent && h.gatewayHeapUsagePercent > 0)
                  );

                  // 同时检查当前诊断结果中的实时指标
                  const perfMetrics = report?.performance?.metrics || {};
                  const hasRealTimeData = perfMetrics.prometheusAvailable === true;

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
                          bottom: 0
                        },
                        xAxis: {
                          type: 'time',
                          data: history.map(h => new Date(h.createdAt).getTime()),
                          axisLabel: {
                            formatter: (value: number) => new Date(value).toLocaleTimeString()
                          }
                        },
                        yAxis: { type: 'value' },
                        series: [
                          {
                            name: 'QPS',
                            type: 'line',
                            data: history.map(h => h.gatewayQps || 0),
                            smooth: true
                          },
                          {
                            name: '错误率(%)',
                            type: 'line',
                            data: history.map(h => h.gatewayErrorRate || 0),
                            smooth: true,
                            lineStyle: { color: '#ff4d4f' }
                          },
                          {
                            name: '堆内存(%)',
                            type: 'line',
                            data: history.map(h => h.gatewayHeapUsagePercent || 0),
                            smooth: true,
                            lineStyle: { color: '#1890ff' }
                          }
                        ],
                        grid: { left: 60, right: 20, top: 20, bottom: 50 }
                      }}
                      style={{ height: 300 }}
                      notMerge={true}
                    />
                  );
                })()
              }
            ]}
          />

          {/* Comparison with previous */}
          {comparison && comparison.previousScore && (
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
                    prefix={comparison.scoreChange > 0 ? '+' : ''}
                    valueStyle={{ color: comparison.scoreChange > 0 ? '#52c41a' : comparison.scoreChange < 0 ? '#ff4d4f' : '#8c8c8c' }}
                  />
                </Col>
                <Col span={12}>
                  <Alert
                    type={comparison.scoreChange > 0 ? 'success' : comparison.scoreChange < -5 ? 'warning' : 'info'}
                    message={comparison.summary || '系统健康度保持稳定'}
                    showIcon
                  />
                </Col>
              </Row>
            </>
          )}
        </Card>
      )}

      {!loading && !report && (
        <Card>
          <div style={{ textAlign: 'center', padding: 40 }}>
            <MedicineBoxOutlined style={{ fontSize: 48, color: '#8c8c8c' }} />
            <Paragraph style={{ marginTop: 16, color: '#8c8c8c' }}>
              {t('diagnostic.click_to_run') || '点击上方按钮运行诊断'}
            </Paragraph>
          </div>
        </Card>
      )}

      <style>{`
        .diagnostic-page { padding: 0 }
        .diagnostic-card { height: 100% }
        .diagnostic-card .ant-card-head {
          background: rgba(255,255,255,0.02);
        }
      `}</style>
    </div>
  );
};

export default DiagnosticPage;