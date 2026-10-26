/**
 * History tab — live view of the running test (progress + charts)
 * plus result cards of finished tests.
 */
import React from 'react';
import {
  Alert,
  Button,
  Card,
  Col,
  Divider,
  Progress,
  Row,
  Space,
  Spin,
  Statistic,
  Tag,
  Typography
} from 'antd';
import {
  CheckCircleOutlined,
  ClockCircleOutlined,
  CloseCircleOutlined,
  DashboardOutlined,
  DeleteOutlined,
  DownloadOutlined,
  RobotOutlined,
  ShareAltOutlined,
  StopOutlined,
  ThunderboltOutlined,
} from '@ant-design/icons';
import { useTranslation } from 'react-i18next';
import type { StressTest, StressTestLiveStatus, StressTestMetrics } from '../../../types/stressTest';
import { parseServerTime } from '../../../utils/serverTime';
import StressTestCharts from './Charts';

const { Title, Text } = Typography;

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

const getStatusColor = (status: string) => {
  switch (status) {
    case 'RUNNING': return 'blue';
    case 'COMPLETED': return 'green';
    case 'STOPPED': return 'orange';
    case 'FAILED': return 'red';
    default: return 'default';
  }
};

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

    return (
      <div>
        <Card
          title={
            <Space>
              <ThunderboltOutlined style={{ color: '#165DFF' }} />
              <Title level={5} style={{ margin: 0, color: '#fff' }}>
                Test #{runningTestId} {t('stress_test.running')}
              </Title>
              <Tag color="blue">RUNNING</Tag>
            </Space>
          }
          style={{ background: 'rgba(255,255,255,0.05)', marginBottom: '24px' }}
          extra={
            <Button danger icon={<StopOutlined />} onClick={() => onStop(runningTestId)}>
              {t('stress_test.stop_test')}
            </Button>
          }
        >
          <Spin spinning={statusLoading}>
            <Row gutter={[24, 24]}>
              <Col span={24}>
                <Progress
                  percent={Math.round(runningStatus.progress * 100)}
                  status="active"
                  strokeColor={{ from: '#165DFF', to: '#52c41a' }}
                />
              </Col>

              <Col xs={12} sm={6}>
                <Statistic
                  title={t('stress_test.requests_sent')}
                  value={runningStatus.actualRequests || 0}
                  prefix={<ThunderboltOutlined />}
                  valueStyle={{ color: '#165DFF' }}
                />
              </Col>

              <Col xs={12} sm={6}>
                <Statistic
                  title={t('stress_test.live_rps')}
                  value={runningStatus.liveRps?.toFixed(2) || '0'}
                  suffix="req/s"
                  prefix={<DashboardOutlined />}
                  valueStyle={{ color: '#52c41a' }}
                />
              </Col>

              <Col xs={12} sm={6}>
                <Statistic
                  title={t('stress_test.avg_response_time')}
                  value={runningStatus.avgResponseTimeMs?.toFixed(2) || '0'}
                  suffix="ms"
                  prefix={<ClockCircleOutlined />}
                  valueStyle={{ color: '#fff' }}
                />
              </Col>

              <Col xs={12} sm={6}>
                <Statistic
                  title={t('stress_test.error_rate')}
                  value={runningStatus.errorRate?.toFixed(2) || '0'}
                  suffix="%"
                  prefix={<CloseCircleOutlined />}
                  valueStyle={{ color: runningStatus.errorRate > 5 ? '#f5222d' : '#52c41a' }}
                />
              </Col>
            </Row>
          </Spin>
        </Card>

        {/* Real-time monitoring charts */}
        <StressTestCharts metrics={testMetrics} />
      </div>
    );
  };

  const renderCompletedTest = (test: StressTest) => (
    <Card
      key={test.id}
      title={
        <Space>
          <Title level={5} style={{ margin: 0, color: '#fff' }}>
            {test.testName || `Test #${test.id}`}
          </Title>
          <Tag color={getStatusColor(test.status)} icon={getStatusIcon(test.status)}>
            {test.status}
          </Tag>
        </Space>
      }
      style={{ background: 'rgba(255,255,255,0.05)', marginBottom: '16px' }}
      extra={
        <Space>
          {test.status === 'COMPLETED' && (
            <>
              <Button icon={<RobotOutlined />} onClick={() => onAnalyze(test.id)}>
                {t('stress_test.ai_analysis')}
              </Button>
              <Button icon={<DownloadOutlined />} onClick={() => onExport(test.id)}>
                {t('stress_test.export')}
              </Button>
              <Button icon={<ShareAltOutlined />} onClick={() => onShare(test.id)}>
                {t('stress_test.share')}
              </Button>
            </>
          )}
          {test.status !== 'RUNNING' && (
            <Button danger icon={<DeleteOutlined />} onClick={() => onDelete(test.id)}>
              {t('stress_test.delete_test')}
            </Button>
          )}
        </Space>
      }
    >
      <Row gutter={[24, 24]}>
        <Col xs={24} sm={12}>
          <Statistic
            title={t('stress_test.total_requests')}
            value={test.actualRequests}
            suffix={test.totalRequests ? `/ ${test.totalRequests}` : ''}
            valueStyle={{ color: '#fff' }}
          />
          <Space style={{ marginTop: '8px' }}>
            <Tag color="green">{test.successfulRequests} {t('stress_test.success')}</Tag>
            <Tag color="red">{test.failedRequests} {t('stress_test.failed')}</Tag>
          </Space>
        </Col>

        <Col xs={24} sm={12}>
          <Statistic
            title={t('stress_test.req_per_sec')}
            value={test.requestsPerSecond?.toFixed(2) || '0'}
            suffix="req/s"
            valueStyle={{ color: '#52c41a' }}
          />
        </Col>

        <Col xs={24} sm={12}>
          <Statistic
            title={t('stress_test.error_rate')}
            value={test.errorRate?.toFixed(2) || '0'}
            suffix="%"
            valueStyle={{ color: test.errorRate > 5 ? '#f5222d' : '#52c41a' }}
          />
        </Col>

        <Col xs={24} sm={12}>
          <Statistic
            title={t('stress_test.avg_response_time')}
            value={test.avgResponseTimeMs?.toFixed(2) || '0'}
            suffix="ms"
            valueStyle={{ color: '#fff' }}
          />
        </Col>

        <Col span={24}>
          <Divider>{t('stress_test.response_time_dist')}</Divider>
          <Row gutter={[16, 16]}>
            <Col xs={12} sm={6}>
              <Statistic title={t('stress_test.min')} value={test.minResponseTimeMs?.toFixed(2) || '0'} suffix="ms" />
            </Col>
            <Col xs={12} sm={6}>
              <Statistic title="P50" value={test.p50ResponseTimeMs?.toFixed(2) || '0'} suffix="ms" />
            </Col>
            <Col xs={12} sm={6}>
              <Statistic title="P90" value={test.p90ResponseTimeMs?.toFixed(2) || '0'} suffix="ms" />
            </Col>
            <Col xs={12} sm={6}>
              <Statistic title="P99" value={test.p99ResponseTimeMs?.toFixed(2) || '0'} suffix="ms" />
            </Col>
          </Row>
        </Col>

        <Col span={24}>
          <Text type="secondary">{t('stress_test.target')}: {test.targetUrl}</Text>
          <br />
          <Text type="secondary">{t('stress_test.method')}: {test.method} | {t('stress_test.concurrent')}: {test.concurrentUsers}</Text>
          <br />
          <Text type="secondary">
            {test.startTime && `${t('stress_test.started')}: ${parseServerTime(test.startTime).toLocaleString()}`}
            {test.endTime && ` | ${t('stress_test.ended')}: ${parseServerTime(test.endTime).toLocaleString()}`}
          </Text>
        </Col>
      </Row>
    </Card>
  );

  return (
    <div>
      {/* Running test display */}
      {renderRunningTest()}

      {/* Completed tests */}
      <Spin spinning={loading}>
        {tests.length === 0 ? (
          <Alert
            type="info"
            message={t('stress_test.no_tests')}
            description={t('stress_test.no_tests_desc')}
            showIcon
          />
        ) : (
          tests.filter((test) => test.status !== 'RUNNING').map(renderCompletedTest)
        )}
      </Spin>
    </div>
  );
};

export default HistoryTab;
