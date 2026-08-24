import React from 'react';
import {
  Alert, Card, Col, Divider, Progress, Row, Space, Statistic, Tag, Typography
} from 'antd';
import {
  ApiOutlined, ClockCircleOutlined, DashboardFilled, DatabaseOutlined,
  FundOutlined, ThunderboltOutlined, WarningOutlined,
} from '@ant-design/icons';
import { useTranslation } from 'react-i18next';
import type { MonitorMetricsData } from '../../../types/monitor';
import { formatBytes } from '../utils';

const { Text } = Typography;

const bodyStyle = { padding: 16 };

/** Snapshot metric cards: two rows of four cards each. */
const MetricCards: React.FC<{ metrics?: MonitorMetricsData }> = ({ metrics }) => {
  const { t } = useTranslation();
  const gc = metrics?.gc;
  const oldGcCount = gc?.gcByType?.oldGC?.count ?? 0;

  return (
    <>
      <Row gutter={[16, 16]}>
        <Col xs={24} sm={12} lg={6}>
          <Card title={<Space><FundOutlined />{t('monitor.jvm_memory')}</Space>} className="metric-card" styles={{ body: bodyStyle }}>
            <div style={{ marginBottom: 12 }}>
              <Text type="secondary">{t('monitor.heap_usage')}</Text>
              <Progress
                percent={metrics?.jvmMemory?.heapUsagePercent || 0}
                status={(metrics?.jvmMemory?.heapUsagePercent || 0) > 80 ? 'exception' : 'normal'}
                format={(p) => `${p?.toFixed(1)}%`}
              />
            </div>
            <Row gutter={8}>
              <Col span={12}>
                <Statistic
                  title={t('monitor.heap_used')}
                  value={formatBytes(metrics?.jvmMemory?.heapUsed || 0).value}
                  suffix={formatBytes(metrics?.jvmMemory?.heapUsed || 0).unit}
                  precision={2}
                  valueStyle={{ fontSize: 14 }}
                />
              </Col>
              <Col span={12}>
                <Statistic
                  title={t('monitor.heap_max')}
                  value={formatBytes(metrics?.jvmMemory?.heapMax || 0).value}
                  suffix={formatBytes(metrics?.jvmMemory?.heapMax || 0).unit}
                  precision={2}
                  valueStyle={{ fontSize: 14 }}
                />
              </Col>
            </Row>
            {gc?.memoryRegions && (
              <>
                <Divider style={{ margin: '12px 0' }} />
                <Text type="secondary" style={{ fontSize: 12, marginBottom: 8, display: 'block' }}>
                  {t('monitor.memory_regions')}
                </Text>
                <Row gutter={[8, 8]}>
                  <Col span={24}>
                    <div style={{ marginBottom: 4 }}>
                      <Text style={{ fontSize: 12 }}>Eden Space</Text>
                      <Progress
                        percent={gc.memoryRegions.eden?.usagePercent || 0}
                        size="small"
                        strokeColor="#1890ff"
                        format={(p) => `${p?.toFixed(1)}%`}
                      />
                    </div>
                    <Text type="secondary" style={{ fontSize: 11 }}>
                      {formatBytes(gc.memoryRegions.eden?.usedBytes || 0).display}
                      {' / '}
                      {formatBytes(gc.memoryRegions.eden?.maxBytes || 0).display}
                    </Text>
                  </Col>
                  <Col span={24}>
                    <div style={{ marginBottom: 4 }}>
                      <Text style={{ fontSize: 12 }}>Survivor Space</Text>
                      <Progress
                        percent={gc.memoryRegions.survivor?.usagePercent || 0}
                        size="small"
                        strokeColor="#52c41a"
                        format={(p) => `${p?.toFixed(1)}%`}
                      />
                    </div>
                    <Text type="secondary" style={{ fontSize: 11 }}>
                      {formatBytes(gc.memoryRegions.survivor?.usedBytes || 0).display}
                      {' / '}
                      {formatBytes(gc.memoryRegions.survivor?.maxBytes || 0).display}
                    </Text>
                  </Col>
                  <Col span={24}>
                    <div style={{ marginBottom: 4 }}>
                      <Text style={{ fontSize: 12 }}>Old Gen</Text>
                      <Progress
                        percent={gc.memoryRegions.oldGen?.usagePercent || 0}
                        size="small"
                        strokeColor={(gc.memoryRegions.oldGen?.usagePercent || 0) > 70 ? '#ff4d4f' : '#722ed1'}
                        format={(p) => `${p?.toFixed(1)}%`}
                      />
                    </div>
                    <Text type="secondary" style={{ fontSize: 11 }}>
                      {formatBytes(gc.memoryRegions.oldGen?.usedBytes || 0).display}
                      {' / '}
                      {formatBytes(gc.memoryRegions.oldGen?.maxBytes || 0).display}
                    </Text>
                  </Col>
                </Row>
              </>
            )}
          </Card>
        </Col>

        <Col xs={24} sm={12} lg={6}>
          <Card title={<Space><ThunderboltOutlined />{t('monitor.gc_status')}</Space>} className="metric-card" styles={{ body: bodyStyle }}>
            <div style={{ marginBottom: 12 }}>
              <Tag color={gc?.healthStatus === 'HEALTHY' ? 'success' : gc?.healthStatus === 'WARNING' ? 'warning' : 'error'}>
                {gc?.healthStatus || 'UNKNOWN'}
              </Tag>
              {gc?.healthStatus !== 'HEALTHY' && gc?.healthReason && (
                <Alert
                  message={gc.healthReason}
                  type={gc.healthStatus === 'WARNING' ? 'warning' : 'error'}
                  showIcon
                  style={{ marginTop: 8, fontSize: 12 }}
                />
              )}
              {gc?.healthStatus === 'HEALTHY' && gc?.healthReason && (
                <Text type="secondary" style={{ fontSize: 12, marginTop: 4 }}>{gc.healthReason}</Text>
              )}
            </div>
            <Row gutter={8}>
              <Col span={12}>
                <Statistic title={t('monitor.gc_count')} value={gc?.gcCount || 0} suffix={t('monitor.times')} valueStyle={{ fontSize: 14 }} />
              </Col>
              <Col span={12}>
                <Statistic title={t('monitor.gc_time')} value={gc?.gcTimeSeconds?.toFixed(2) || '0'} suffix="s" valueStyle={{ fontSize: 14 }} />
              </Col>
            </Row>
            <Divider style={{ margin: '8px 0' }} />
            <Statistic
              title={t('monitor.gc_overhead')}
              value={gc?.gcOverheadPercent?.toFixed(2) || '0'}
              suffix="%"
              valueStyle={{ fontSize: 14, color: (gc?.gcOverheadPercent || 0) > 10 ? '#ff4d4f' : '#52c41a' }}
            />
            {gc?.gcByType && (
              <>
                <Divider style={{ margin: '12px 0' }} />
                <Text type="secondary" style={{ fontSize: 12, marginBottom: 8, display: 'block' }}>
                  {t('monitor.gc_breakdown')}
                </Text>
                <Row gutter={[8, 8]}>
                  <Col span={24}>
                    <div style={{ padding: '8px 12px', background: 'rgba(59, 130, 246, 0.12)', borderRadius: 4, border: '1px solid rgba(59, 130, 246, 0.2)' }}>
                      <Text strong style={{ fontSize: 12, color: '#60a5fa' }}>Young GC</Text>
                      <Row gutter={8} style={{ marginTop: 4 }}>
                        <Col span={8}>
                          <Text type="secondary" style={{ fontSize: 11 }}>{t('monitor.count_short')}</Text>
                          <div style={{ fontSize: 13, fontWeight: 500, color: '#fafafa' }}>{gc.gcByType.youngGC?.count || 0}</div>
                        </Col>
                        <Col span={8}>
                          <Text type="secondary" style={{ fontSize: 11 }}>{t('monitor.total_time')}</Text>
                          <div style={{ fontSize: 13, fontWeight: 500, color: '#fafafa' }}>
                            {gc.gcByType.youngGC?.totalTimeSeconds?.toFixed(2) || '0'}
                            s
                          </div>
                        </Col>
                        <Col span={8}>
                          <Text type="secondary" style={{ fontSize: 11 }}>{t('monitor.avg_time')}</Text>
                          <div style={{ fontSize: 13, fontWeight: 500, color: '#fafafa' }}>
                            {gc.gcByType.youngGC?.avgTimeMs?.toFixed(1) || '0'}
                            ms
                          </div>
                        </Col>
                      </Row>
                    </div>
                  </Col>
                  <Col span={24}>
                    <div style={{
                      padding: '8px 12px',
                      background: oldGcCount > 0 ? 'rgba(239, 68, 68, 0.12)' : 'rgba(255, 255, 255, 0.06)',
                      borderRadius: 4,
                      border: oldGcCount > 0 ? '1px solid rgba(239, 68, 68, 0.2)' : '1px solid rgba(255, 255, 255, 0.1)',
                    }}
                    >
                      <Text strong style={{ fontSize: 12, color: oldGcCount > 0 ? '#f87171' : '#a1a1aa' }}>Old/Full GC</Text>
                      <Row gutter={8} style={{ marginTop: 4 }}>
                        <Col span={8}>
                          <Text type="secondary" style={{ fontSize: 11 }}>{t('monitor.count_short')}</Text>
                          <div style={{ fontSize: 13, fontWeight: 500, color: oldGcCount > 0 ? '#f87171' : '#fafafa' }}>{oldGcCount}</div>
                        </Col>
                        <Col span={8}>
                          <Text type="secondary" style={{ fontSize: 11 }}>{t('monitor.total_time')}</Text>
                          <div style={{ fontSize: 13, fontWeight: 500, color: '#fafafa' }}>
                            {oldGcCount > 0 ? (gc.gcByType.oldGC?.totalTimeSeconds?.toFixed(2) || '0') : '0'}
                            s
                          </div>
                        </Col>
                        <Col span={8}>
                          <Text type="secondary" style={{ fontSize: 11 }}>{t('monitor.avg_time')}</Text>
                          <div style={{ fontSize: 13, fontWeight: 500, color: '#fafafa' }}>
                            {oldGcCount > 0 ? (gc.gcByType.oldGC?.avgTimeMs?.toFixed(1) || '0') : '0'}
                            ms
                          </div>
                        </Col>
                      </Row>
                    </div>
                  </Col>
                </Row>
              </>
            )}
            {gc?.allocationRateMBPerSec && (
              <>
                <Divider style={{ margin: '12px 0' }} />
                <Statistic
                  title={t('monitor.allocation_rate')}
                  value={gc.allocationRateMBPerSec.toFixed(2)}
                  suffix="MB/s"
                  valueStyle={{ fontSize: 14 }}
                />
              </>
            )}
            {gc?.promotionRateMBPerSec !== undefined && gc.promotionRateMBPerSec > 0 && (
              <>
                <Divider style={{ margin: '8px 0' }} />
                <Row gutter={8}>
                  <Col span={12}>
                    <Statistic
                      title={t('monitor.promotion_rate')}
                      value={gc.promotionRateMBPerSec.toFixed(2)}
                      suffix="MB/s"
                      valueStyle={{ fontSize: 14, color: gc.promotionRateMBPerSec > 10 ? '#faad14' : '#52c41a' }}
                    />
                  </Col>
                  <Col span={12}>
                    <Statistic
                      title={t('monitor.promotion_ratio')}
                      value={gc.promotionRatio?.toFixed(1) || '0'}
                      suffix="%"
                      valueStyle={{ fontSize: 14, color: (gc.promotionRatio || 0) > 30 ? '#faad14' : '#52c41a' }}
                    />
                  </Col>
                </Row>
              </>
            )}
          </Card>
        </Col>

        <Col xs={24} sm={12} lg={6}>
          <Card title={<Space><ApiOutlined />{t('monitor.http_requests')}</Space>} className="metric-card" styles={{ body: bodyStyle }}>
            <Statistic
              title={t('monitor.requests_per_second')}
              value={Number(metrics?.httpRequests?.requestsPerSecond || 0).toFixed(2)}
              suffix={t('monitor.per_second')}
              valueStyle={{ color: '#1890ff', fontSize: 20 }}
            />
            <Divider style={{ margin: '8px 0' }} />
            <Row gutter={8}>
              <Col span={12}>
                <Statistic title={t('monitor.avg_response_time')} value={metrics?.httpRequests?.avgResponseTimeMs || 0} suffix="ms" valueStyle={{ fontSize: 14 }} />
              </Col>
              <Col span={12}>
                <Statistic
                  title={t('monitor.error_rate')}
                  value={metrics?.httpRequests?.errorRate?.toFixed(2) || '0.00'}
                  suffix="%"
                  valueStyle={{ fontSize: 14, color: (metrics?.httpRequests?.errorRate || 0) > 1 ? '#ff4d4f' : '#52c41a' }}
                />
              </Col>
            </Row>
          </Card>
        </Col>

        <Col xs={24} sm={12} lg={6}>
          <Card title={<Space><DashboardFilled />{t('monitor.cpu_usage')}</Space>} className="metric-card" styles={{ body: bodyStyle }}>
            <div style={{ marginBottom: 8 }}>
              <Text type="secondary">{t('monitor.process_cpu')}</Text>
              <Progress
                percent={metrics?.cpu?.processUsage || 0}
                status={(metrics?.cpu?.processUsage || 0) > 80 ? 'exception' : 'normal'}
              />
            </div>
            <div style={{ marginBottom: 8 }}>
              <Text type="secondary">{t('monitor.system_load')}</Text>
              <Text strong style={{ fontSize: 18, display: 'block', marginTop: 4 }}>
                {metrics?.cpu?.systemLoadAverage || 0}
              </Text>
            </div>
            <Statistic title={t('monitor.available_processors')} value={metrics?.cpu?.availableProcessors || 0} valueStyle={{ fontSize: 14 }} />
          </Card>
        </Col>
      </Row>

      <Row gutter={[16, 16]} style={{ marginTop: 16 }}>
        <Col xs={24} sm={12} lg={6}>
          <Card title={<Space><DatabaseOutlined />{t('monitor.threads')}</Space>} className="metric-card" styles={{ body: bodyStyle }}>
            <Statistic
              title={t('monitor.live_threads')}
              value={metrics?.threads?.liveThreads || 0}
              valueStyle={{ fontSize: 16, color: '#1890ff' }}
            />
            <Divider style={{ margin: '8px 0' }} />
            <Row gutter={8}>
              <Col span={12}>
                <Statistic title={t('monitor.daemon_threads')} value={metrics?.threads?.daemonThreads || 0} valueStyle={{ fontSize: 14 }} />
              </Col>
              <Col span={12}>
                <Statistic title={t('monitor.peak_threads')} value={metrics?.threads?.peakThreads || 0} valueStyle={{ fontSize: 14 }} />
              </Col>
            </Row>
            {metrics?.threads && (
              <div style={{ marginTop: 8 }}>
                <Text type="secondary" style={{ fontSize: 12 }}>
                  {t('monitor.user_threads')}
                  : {(metrics.threads.liveThreads || 0) - (metrics.threads.daemonThreads || 0)}
                </Text>
              </div>
            )}
          </Card>
        </Col>

        <Col xs={24} sm={12} lg={6}>
          <Card title={<Space><ClockCircleOutlined />{t('monitor.process_info')}</Space>} className="metric-card" styles={{ body: bodyStyle }}>
            <Statistic
              title={t('monitor.uptime')}
              value={metrics?.process?.uptimeFormatted || '0h 0m 0s'}
              valueStyle={{ fontSize: 16 }}
            />
            <Divider style={{ margin: '8px 0' }} />
            <Statistic
              title={t('monitor.uptime_seconds')}
              value={metrics?.process?.uptimeSeconds || 0}
              suffix="s"
              valueStyle={{ fontSize: 14 }}
            />
          </Card>
        </Col>

        <Col xs={24} sm={12} lg={6}>
          <Card title={<Space><DatabaseOutlined />{t('monitor.non_heap_memory')}</Space>} className="metric-card" styles={{ body: bodyStyle }}>
            <Statistic
              title={t('monitor.non_heap_used')}
              value={formatBytes(metrics?.jvmMemory?.nonHeapUsed || 0).value}
              suffix={formatBytes(metrics?.jvmMemory?.nonHeapUsed || 0).unit}
              precision={2}
              valueStyle={{ fontSize: 16 }}
            />
            <Divider style={{ margin: '8px 0' }} />
            <Text type="secondary" style={{ fontSize: 12 }}>
              {t('monitor.non_heap_desc')}
            </Text>
          </Card>
        </Col>

        <Col xs={24} sm={12} lg={6}>
          <Card title={<Space><WarningOutlined />{t('monitor.log_events')}</Space>} className="metric-card" styles={{ body: bodyStyle }}>
            <Statistic
              title={t('monitor.error_count')}
              value={metrics?.logEvents?.errorCount || 0}
              valueStyle={{ fontSize: 16, color: (metrics?.logEvents?.errorCount || 0) > 100 ? '#ff4d4f' : '#52c41a' }}
            />
            <Divider style={{ margin: '8px 0' }} />
            <Row gutter={8}>
              <Col span={8}>
                <Statistic
                  title={t('monitor.warn_count')}
                  value={metrics?.logEvents?.warnCount || 0}
                  valueStyle={{ fontSize: 14, color: (metrics?.logEvents?.warnCount || 0) > 50 ? '#faad14' : 'inherit' }}
                />
              </Col>
              <Col span={8}>
                <Statistic title={t('monitor.info_count')} value={metrics?.logEvents?.infoCount || 0} valueStyle={{ fontSize: 14 }} />
              </Col>
              <Col span={8}>
                <Statistic title={t('monitor.uptime')} value={metrics?.process?.uptimeFormatted || '0h 0m'} valueStyle={{ fontSize: 14 }} />
              </Col>
            </Row>
          </Card>
        </Col>
      </Row>
    </>
  );
};

export default MetricCards;
