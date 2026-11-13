import '../instance-detail/operations-ui.css';
/**
 * Public stress-test share page — rendered at /share/:shareId without
 * auth or the app shell. Loads the shared report and offers markdown download.
 */
import React, { useCallback, useEffect, useState } from 'react';
import { useParams } from 'react-router-dom';
import { useTranslation } from 'react-i18next';
import {
  Alert,
  Button,
  Card,
  Descriptions,
  Divider,
  Space,
  Spin,
  Tag,
  Typography
} from 'antd';
import {
  CheckCircleOutlined,
  ClockCircleOutlined,
  CloseCircleOutlined,
  DownloadOutlined,
  ThunderboltOutlined,
} from '@ant-design/icons';
import { getSharedStressTestReport } from '../../api/stressTest';
import type { StressTest } from '../../types/stressTest';
import AiReportRenderer from '../../components/ai/AiReportRenderer';
import '../../styles/ai-report.css';
import { palette } from '../../styles/palette';

const { Title, Text } = Typography;

const getStatusColor = (status: string) => {
  switch (status) {
    case 'COMPLETED': return 'success';
    case 'STOPPED': return 'warning';
    case 'FAILED': return 'error';
    default: return 'default';
  }
};

const formatDate = (dateStr?: string | null) => {
  if (!dateStr) return 'N/A';
  return new Date(dateStr).toLocaleString();
};

const StressTestSharePage: React.FC = () => {
  const { t } = useTranslation();
  const { shareId } = useParams<{ shareId: string }>();
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [test, setTest] = useState<StressTest | null>(null);
  const [markdown, setMarkdown] = useState('');

  const loadSharedReport = useCallback(async (id: string) => {
    setLoading(true);
    setError(null);
    try {
      const report = await getSharedStressTestReport(id);
      setTest(report.test);
      setMarkdown(report.markdown);
    } catch (e) {
      // Empty string = no server message; the translated fallback is rendered at display time
      // so it follows the current UI language.
      setError(e instanceof Error && e.message ? e.message : '');
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => {
    if (shareId) {
      const id = shareId;
      (async () => {
        await loadSharedReport(id);
      })();
    }
  }, [shareId, loadSharedReport]);

  const handleDownload = () => {
    if (!markdown) return;

    const blob = new Blob([markdown], { type: 'text/markdown' });
    const url = window.URL.createObjectURL(blob);
    const link = document.createElement('a');
    link.href = url;
    link.setAttribute('download', `stress-test-report-${shareId}.md`);
    document.body.appendChild(link);
    link.click();
    link.remove();
    window.URL.revokeObjectURL(url);
  };

  if (loading) {
    return (
      <div style={{
        display: 'flex',
        justifyContent: 'center',
        alignItems: 'center',
        minHeight: '100vh',
        background: palette.bgSecondary,
      }}
      >
        <Spin size="large" />
      </div>
    );
  }

  if (error !== null) {
    return (
      <div style={{
        padding: '48px',
        maxWidth: '600px',
        margin: '0 auto',
        background: palette.bgSecondary,
        minHeight: '100vh',
      }}
      >
        <Alert
          type="error"
          title={t('stress_test.report_unavailable')}
          description={error || t('stress_test.share_link_invalid')}
          showIcon
        />
      </div>
    );
  }

  return (
    <div className="console-workspace-page console-shared-report" style={{
      padding: '24px',
      maxWidth: '1200px',
      margin: '0 auto',
      background: palette.bgSecondary,
      minHeight: '100vh',
    }}
    >
      <Card
        style={{
          background: 'rgba(16, 24, 40, 0.8)',
          border: `1px solid rgba(145, 169, 222, 0.2)`,
          marginBottom: '24px',
        }}
      >
        <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: '16px' }}>
          <Title level={3} style={{ margin: 0, color: palette.textPrimary }}>
            <ThunderboltOutlined style={{ marginRight: '8px', color: palette.primary }} />
            {test?.testName || t('stress_test.report_title')}
          </Title>
          <Space>
            <Tag color={getStatusColor(test?.status || '')}>{test?.status}</Tag>
            <Button icon={<DownloadOutlined />} onClick={handleDownload}>
              {t('stress_test.download')}
            </Button>
          </Space>
        </div>

        <Descriptions
          column={2}
          bordered
          size="small"
          styles={{
            label: { background: 'rgba(10, 16, 32, 0.6)', color: palette.textTertiary },
            content: { background: 'transparent', color: palette.textPrimary },
          }}
        >
          <Descriptions.Item label={t('stress_test.target_url')}>{test?.targetUrl}</Descriptions.Item>
          <Descriptions.Item label={t('stress_test.method')}>{test?.method}</Descriptions.Item>
          <Descriptions.Item label={t('stress_test.start_time')}>{formatDate(test?.startTime)}</Descriptions.Item>
          <Descriptions.Item label={t('stress_test.end_time')}>{formatDate(test?.endTime)}</Descriptions.Item>
          <Descriptions.Item label={t('stress_test.concurrent_users')}>{test?.concurrentUsers}</Descriptions.Item>
          <Descriptions.Item label={t('stress_test.duration')}>{test?.durationSeconds ? `${test.durationSeconds}s` : test?.totalRequests ? t('stress_test.requests_value', { n: test.totalRequests }) : 'N/A'}</Descriptions.Item>
        </Descriptions>

        <Divider style={{ borderColor: 'rgba(145, 169, 222, 0.2)' }} />

        <Title level={5} style={{ color: palette.textPrimary, marginBottom: '16px' }}>{t('stress_test.test_results')}</Title>

        <div className="console-report-metrics" style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fit, minmax(200px, 1fr))', gap: '16px' }}>
          <Card size="small" style={{ background: 'rgba(10, 16, 32, 0.6)', border: 'none' }}>
            <div style={{ textAlign: 'center' }}>
              <Text type="secondary">{t('stress_test.total_requests')}</Text>
              <div style={{ fontSize: '24px', fontWeight: 'bold', color: palette.textPrimary }}>{test?.actualRequests}</div>
              <Space style={{ marginTop: '8px' }}>
                <Tag color="success" icon={<CheckCircleOutlined />}>{test?.successfulRequests} {t('stress_test.success')}</Tag>
                <Tag color="error" icon={<CloseCircleOutlined />}>{test?.failedRequests} {t('stress_test.failed')}</Tag>
              </Space>
            </div>
          </Card>

          <Card size="small" style={{ background: 'rgba(10, 16, 32, 0.6)', border: 'none' }}>
            <div style={{ textAlign: 'center' }}>
              <Text type="secondary">{t('stress_test.req_per_sec')}</Text>
              <div style={{ fontSize: '24px', fontWeight: 'bold', color: palette.success }}>{test?.requestsPerSecond?.toFixed(2)}</div>
              <Text type="secondary" style={{ fontSize: '12px' }}>req/s</Text>
            </div>
          </Card>

          <Card size="small" style={{ background: 'rgba(10, 16, 32, 0.6)', border: 'none' }}>
            <div style={{ textAlign: 'center' }}>
              <Text type="secondary">{t('stress_test.error_rate')}</Text>
              <div style={{ fontSize: '24px', fontWeight: 'bold', color: (test?.errorRate ?? 0) > 5 ? palette.error : palette.success }}>
                {test?.errorRate?.toFixed(2)}%
              </div>
            </div>
          </Card>

          <Card size="small" style={{ background: 'rgba(10, 16, 32, 0.6)', border: 'none' }}>
            <div style={{ textAlign: 'center' }}>
              <Text type="secondary">{t('stress_test.avg_response_time')}</Text>
              <div style={{ fontSize: '24px', fontWeight: 'bold', color: palette.textPrimary }}>{test?.avgResponseTimeMs?.toFixed(2)}</div>
              <Text type="secondary" style={{ fontSize: '12px' }}>ms</Text>
            </div>
          </Card>
        </div>

        <Divider style={{ borderColor: 'rgba(145, 169, 222, 0.2)', margin: '24px 0' }} />

        <Title level={5} style={{ color: palette.textPrimary, marginBottom: '16px' }}>{t('stress_test.response_time_dist')}</Title>

        <div className="console-report-metrics" style={{ display: 'grid', gridTemplateColumns: 'repeat(4, 1fr)', gap: '12px' }}>
          {[
            { label: t('stress_test.min'), value: test?.minResponseTimeMs },
            { label: 'P50', value: test?.p50ResponseTimeMs },
            { label: 'P90', value: test?.p90ResponseTimeMs },
            { label: 'P95', value: test?.p95ResponseTimeMs },
            { label: 'P99', value: test?.p99ResponseTimeMs },
            { label: t('stress_test.max'), value: test?.maxResponseTimeMs },
          ].map((item) => (
            <div key={item.label} style={{ textAlign: 'center', padding: '12px', background: 'rgba(10, 16, 32, 0.6)', borderRadius: '8px' }}>
              <Text type="secondary">{item.label}</Text>
              <div style={{ fontSize: '18px', fontWeight: 'bold', color: palette.textPrimary }}>
                {item.value?.toFixed(2) || '0'}
              </div>
              <Text type="secondary" style={{ fontSize: '12px' }}>ms</Text>
            </div>
          ))}
        </div>
      </Card>

      {test?.aiAnalysisResult && (
        <Card
          title={
            <Space>
              <ClockCircleOutlined style={{ color: palette.primary }} />
              <Title level={4} style={{ margin: 0, color: palette.textPrimary }}>{t('stress_test.ai_analysis')}</Title>
            </Space>
          }
          style={{
            background: 'rgba(16, 24, 40, 0.8)',
            border: `1px solid rgba(145, 169, 222, 0.2)`,
          }}
        >
          <AiReportRenderer content={test.aiAnalysisResult} />
        </Card>
      )}

      <div style={{ textAlign: 'center', marginTop: '24px', color: palette.textMuted }}>
        <Text type="secondary">
          {t('stress_test.share_generated_by')} • {new Date().toLocaleString()}
        </Text>
      </div>
    </div>
  );
};

export default StressTestSharePage;
