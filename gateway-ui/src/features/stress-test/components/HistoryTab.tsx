/**
 * History tab — live view of the running test (progress + charts)
 * plus result cards of finished tests.
 */
import React from 'react';
import {
  Button,
  Card,
  Progress,
  Space,
  Spin,
  Typography,
} from 'antd';
import {
  CheckCircleOutlined,
  ClockCircleOutlined,
  CloseCircleOutlined,
  DeleteOutlined,
  DownloadOutlined,
  LineChartOutlined,
  RobotOutlined,
  ShareAltOutlined,
  StopOutlined,
} from '@ant-design/icons';
import { useTranslation } from 'react-i18next';
import type { StressTest, StressTestLiveStatus, StressTestMetrics } from '../../../types/stressTest';
import { parseServerTime } from '../../../utils/serverTime';
import StressTestCharts from './Charts';
import { palette } from '../../../styles/palette';
import '../stress-test.css';

const { Text } = Typography;

interface Props {
  tests: StressTest[];
  loading: boolean;
  runningTestId: number | null;
  runningStatus: StressTestLiveStatus | null;
  statusLoading: boolean;
  testMetrics: StressTestMetrics | null;
  onStop: (testId: number) => void;
  onDelete: (testId: number) => void;
  onAnalyze: (testId: number) => void;
  onExport: (testId: number) => void;
  onShare: (testId: number) => void;
}

const getStatusIcon = (status: string) => {
  switch (status) {
    case 'RUNNING': return <ClockCircleOutlined />;
    case 'COMPLETED': return <CheckCircleOutlined />;
    case 'STOPPED': return <StopOutlined />;
    case 'FAILED': return <CloseCircleOutlined />;
    default: return null;
  }
};

const HistoryTab: React.FC<Props> = ({
  tests,
  loading,
  runningTestId,
  runningStatus,
  statusLoading,
  testMetrics,
  onStop,
  onDelete,
  onAnalyze,
  onExport,
  onShare,
}) => {
  const { t } = useTranslation();

  const renderRunningTest = () => {
    if (!runningTestId || !runningStatus) return null;
    const errorRate = runningStatus.errorRate ?? 0;

    return (
      <div>
        <Card
          className="stress-result-card stress-running-card"
          title={(
            <div className="stress-result-title">
              <span className="stress-result-name">
                {t('stress_test.test_number', { id: runningTestId })}
                {' '}
                {t('stress_test.running')}
              </span>
              <span className="stress-status stress-status-running">
                <ClockCircleOutlined />
                RUNNING
              </span>
            </div>
          )}
          extra={(
            <Button size="small" danger icon={<StopOutlined />} onClick={() => onStop(runningTestId)}>
              {t('stress_test.stop_test')}
            </Button>
          )}
        >
          <Spin spinning={statusLoading}>
            <Progress
              percent={Math.round(runningStatus.progress * 100)}
              status="active"
              strokeColor={{ from: palette.primary, to: palette.success }}
            />
            <div className="stress-kpis">
              <div className="stress-kpi">
                <span>{t('stress_test.requests_sent')}</span>
                <b>{(runningStatus.actualRequests || 0).toLocaleString()}</b>
                <em>req</em>
              </div>
              <div className="stress-kpi">
                <span>{t('stress_test.live_rps')}</span>
                <b>{(runningStatus.liveRps ?? 0).toFixed(2)}</b>
                <em>req/s</em>
              </div>
              <div className="stress-kpi">
                <span>{t('stress_test.avg_response_time')}</span>
                <b>{(runningStatus.avgResponseTimeMs ?? 0).toFixed(2)}</b>
                <em>ms</em>
              </div>
              <div className={`stress-kpi${errorRate > 5 ? ' is-bad' : ''}`}>
                <span>{t('stress_test.error_rate')}</span>
                <b>{errorRate.toFixed(2)}</b>
                <em>%</em>
              </div>
            </div>
          </Spin>
        </Card>

        {/* Real-time monitoring charts */}
        <StressTestCharts metrics={testMetrics} />
      </div>
    );
  };

  const renderCompletedTest = (test: StressTest) => {
    const total = Math.max(test.actualRequests || 0, 1);
    const failed = test.failedRequests || 0;
    const success = Math.max(test.successfulRequests ?? total - failed, 0);
    const errorRate = test.errorRate ?? 0;
    const maxLatency = Math.max(test.p99ResponseTimeMs || 0, 1);
    const latencyRows = [
      { key: 'min', label: t('stress_test.min'), value: test.minResponseTimeMs || 0 },
      { key: 'p50', label: 'P50', value: test.p50ResponseTimeMs || 0 },
      { key: 'p90', label: 'P90', value: test.p90ResponseTimeMs || 0 },
      { key: 'p99', label: 'P99', value: test.p99ResponseTimeMs || 0, max: true },
    ];

    return (
      <Card
        key={test.id}
        className="stress-result-card"
        title={(
          <div className="stress-result-title">
            <span className="stress-result-name">
              {test.testName || t('stress_test.test_number', { id: test.id })}
            </span>
            <span className={`stress-status stress-status-${(test.status || '').toLowerCase()}`}>
              {getStatusIcon(test.status)}
              {test.status}
            </span>
          </div>
        )}
        extra={(
          <Space size={4}>
            {test.status === 'COMPLETED' && (
              <>
                <Button size="small" icon={<RobotOutlined />} onClick={() => onAnalyze(test.id)}>
                  {t('stress_test.ai_analysis')}
                </Button>
                <Button size="small" icon={<DownloadOutlined />} onClick={() => onExport(test.id)}>
                  {t('stress_test.export')}
                </Button>
                <Button size="small" icon={<ShareAltOutlined />} onClick={() => onShare(test.id)}>
                  {t('stress_test.share')}
                </Button>
              </>
            )}
            {test.status !== 'RUNNING' && test.status !== 'CREATED' && (
              <Button size="small" danger icon={<DeleteOutlined />} onClick={() => onDelete(test.id)}>
                {t('stress_test.delete_test')}
              </Button>
            )}
          </Space>
        )}
      >
        <div className="stress-kpis">
          <div className="stress-kpi">
            <span>{t('stress_test.total_requests')}</span>
            <b>{(test.actualRequests ?? 0).toLocaleString()}</b>
            <em>{test.totalRequests ? `/ ${test.totalRequests.toLocaleString()}` : '—'}</em>
          </div>
          <div className="stress-kpi">
            <span>{t('stress_test.req_per_sec')}</span>
            <b>{(test.requestsPerSecond ?? 0).toFixed(2)}</b>
            <em>req/s</em>
          </div>
          <div className="stress-kpi">
            <span>{t('stress_test.avg_response_time')}</span>
            <b>{(test.avgResponseTimeMs ?? 0).toFixed(2)}</b>
            <em>ms</em>
          </div>
          <div className={`stress-kpi${errorRate > 5 ? ' is-bad' : ''}`}>
            <span>{t('stress_test.error_rate')}</span>
            <b>{errorRate.toFixed(2)}</b>
            <em>%</em>
          </div>
        </div>

        <div className="stress-split" aria-hidden="true">
          <i className="ok" style={{ width: `${(success / total) * 100}%` }} />
          <i className="bad" style={{ width: `${(failed / total) * 100}%` }} />
        </div>

        <div className="stress-latency">
          <div className="stress-latency-head">
            <span>{t('stress_test.response_time_dist')}</span>
            <span>
              {success.toLocaleString()}
              {' '}
              {t('stress_test.success')}
              {' · '}
              {failed.toLocaleString()}
              {' '}
              {t('stress_test.failed')}
            </span>
          </div>
          {latencyRows.map((row) => (
            <div key={row.key} className={`stress-latency-row${row.max ? ' is-max' : ''}`}>
              <span className="stress-latency-label">{row.label}</span>
              <span className="stress-latency-track">
                <i
                  className="stress-latency-fill"
                  style={{ display: 'block', width: `${Math.min(100, (row.value / maxLatency) * 100)}%` }}
                />
              </span>
              <span className="stress-latency-value">{row.value.toFixed(2)} ms</span>
            </div>
          ))}
        </div>

        <div className="stress-meta">
          <span>
            <span className="stress-meta-label">{t('stress_test.target')}:</span>
            {' '}
            <code>{test.targetUrl}</code>
          </span>
          <span>{t('stress_test.method')}: {test.method}</span>
          <span>{t('stress_test.concurrent')}: {test.concurrentUsers}</span>
          {test.startTime && (
            <span>{t('stress_test.started')}: {parseServerTime(test.startTime).toLocaleString()}</span>
          )}
          {test.endTime && (
            <span>{t('stress_test.ended')}: {parseServerTime(test.endTime).toLocaleString()}</span>
          )}
        </div>
      </Card>
    );
  };

  return (
    <div>
      {/* Running test display */}
      {renderRunningTest()}
      {tests.filter((test) => (test.status === 'RUNNING' || test.status === 'CREATED') && test.id !== runningTestId).map((test) => (
        <Card
          key={test.id}
          size="small"
          className="stress-result-card"
          title={<Space><span className={`stress-status stress-status-${(test.status || '').toLowerCase()}`}>{test.status}</span>{test.testName || t('stress_test.test_number', { id: test.id })}</Space>}
          extra={<Button size="small" danger icon={<StopOutlined />} onClick={() => onStop(test.id)}>{t('stress_test.stop_test')}</Button>}
        >
          <Text type="secondary">{test.targetUrl}</Text>
        </Card>
      ))}

      {/* Completed tests */}
      <Spin spinning={loading}>
        {tests.length === 0 ? (
          <div className="management-empty">
            <span className="management-empty-icon">
              <LineChartOutlined />
            </span>
            <h3>{t('stress_test.no_tests')}</h3>
            <p>{t('stress_test.no_tests_desc')}</p>
          </div>
        ) : (
          tests.filter((test) => test.status !== 'RUNNING' && test.status !== 'CREATED').map(renderCompletedTest)
        )}
      </Spin>
    </div>
  );
};

export default HistoryTab;