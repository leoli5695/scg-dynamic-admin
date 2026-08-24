/**
 * Stress test feature root — test history (with live progress polling),
 * template-based test creation, quick test, AI analysis, export and share.
 */
import React, { useCallback, useEffect, useState } from 'react';
import {
  Button,
  Input,
  Select,
  Space,
  Tabs,
  Typography } from 'antd';
import { message, modal } from '../../utils/antdStatic';
import {
  CopyOutlined,
  LineChartOutlined,
  ReloadOutlined,
  ThunderboltOutlined,
} from '@ant-design/icons';
import { useTranslation } from 'react-i18next';
import { ApiError } from '../../api/http';
import {
  createStressTestShareLink,
  deleteStressTest,
  exportStressTestReport,
  getStressTestAnalysis,
  getStressTestMetrics,
  getStressTestStatus,
  getStressTestsByInstance,
  quickStressTest,
  startStressTest,
  stopStressTest,
} from '../../api/stressTest';
import type {
  StressTest,
  StressTestConfig,
  StressTestLiveStatus,
  StressTestMetrics,
} from '../../types/stressTest';
import { useInstanceDetail } from '../instance-detail/InstanceDetailContext';
import HistoryTab from './components/HistoryTab';
import CreateTab from './components/CreateTab';
import QuickTestModal from './components/QuickTestModal';
import AnalysisModal from './components/AnalysisModal';

const { Title, Text, Paragraph } = Typography;

const errorMessage = (e: unknown, fallback: string) =>
  (e instanceof ApiError && e.message ? e.message : fallback);

const StressTestPage: React.FC = () => {
  const { t } = useTranslation();
  const { instance } = useInstanceDetail();
  const instanceId = instance.instanceId;

  const [tests, setTests] = useState<StressTest[]>([]);
  const [loading, setLoading] = useState(true);
  const [activeTab, setActiveTab] = useState('history');
  const [creating, setCreating] = useState(false);

  // Running test
  const [runningTestId, setRunningTestId] = useState<number | null>(null);
  const [runningStatus, setRunningStatus] = useState<StressTestLiveStatus | null>(null);
  const [statusLoading, setStatusLoading] = useState(false);
  const [testMetrics, setTestMetrics] = useState<StressTestMetrics | null>(null);

  // Quick test modal
  const [quickModalVisible, setQuickModalVisible] = useState(false);

  // Analysis modal
  const [analysisModalVisible, setAnalysisModalVisible] = useState(false);
  const [analysisResult, setAnalysisResult] = useState('');
  const [analysisLoading, setAnalysisLoading] = useState(false);
  const [analysisTestId, setAnalysisTestId] = useState<number | null>(null);

  const loadTests = useCallback(async () => {
    setLoading(true);
    try {
      const list = await getStressTestsByInstance(instanceId);
      const data = Array.isArray(list) ? list : [];
      setTests(data);

      // Resume live view when a test is still running.
      const runningTest = data.find((item) => item.status === 'RUNNING');
      if (runningTest) {
        setRunningTestId(runningTest.id);
      }
    } catch {
      message.error(t('stress_test.load_failed'));
    } finally {
      setLoading(false);
    }
  }, [instanceId, t]);

  useEffect(() => {
    (async () => {
      await loadTests();
    })();
  }, [loadTests]);

  const fetchTestStatus = useCallback(async (testId: number) => {
    setStatusLoading(true);
    try {
      // Fetch both status and metrics in parallel; timeline only exists on metrics.
      const [status, metrics] = await Promise.all([
        getStressTestStatus(testId),
        getStressTestMetrics(testId).catch(() => null),
      ]);

      setRunningStatus(status);
      if (metrics && metrics.timeline && metrics.timeline.length > 0) {
        setTestMetrics(metrics);
      }

      if (status.status !== 'RUNNING') {
        setRunningTestId(null);
        setRunningStatus(null);
        setTestMetrics(null);
        await loadTests();
      }
    } catch {
      message.error(t('stress_test.load_failed'));
    } finally {
      setStatusLoading(false);
    }
  }, [loadTests, t]);

  // Poll running test status every 2s.
  useEffect(() => {
    if (!runningTestId) return undefined;
    const currentId = runningTestId;
    (async () => {
      await fetchTestStatus(currentId);
    })();
    const interval = setInterval(() => {
      void fetchTestStatus(currentId);
    }, 2000);
    return () => clearInterval(interval);
  }, [runningTestId, fetchTestStatus]);

  const handleCreateTest = useCallback(async (config: StressTestConfig): Promise<boolean> => {
    setCreating(true);
    try {
      const result = await startStressTest(config, instanceId);
      message.success(t('stress_test.test_started'));
      setRunningTestId(result.testId);
      setActiveTab('history');
      void loadTests();
      return true;
    } catch (e) {
      message.error(errorMessage(e, t('stress_test.start_failed')));
      return false;
    } finally {
      setCreating(false);
    }
  }, [instanceId, loadTests, t]);

  const handleQuickTest = useCallback(async (requests: number, concurrent: number) => {
    try {
      const result = await quickStressTest({ instanceId, requests, concurrent });
      message.success(t('stress_test.test_started'));
      setQuickModalVisible(false);
      setRunningTestId(result.testId);
      setActiveTab('history');
      void loadTests();
    } catch (e) {
      message.error(errorMessage(e, t('stress_test.start_failed')));
    }
  }, [instanceId, loadTests, t]);

  const handleStopTest = useCallback(async (testId: number) => {
    try {
      await stopStressTest(testId);
      message.success(t('stress_test.test_stopped'));
      setRunningTestId(null);
      setRunningStatus(null);
      setTestMetrics(null);
      void loadTests();
    } catch (e) {
      message.error(errorMessage(e, t('stress_test.stop_failed')));
    }
  }, [loadTests, t]);

  const handleDeleteTest = useCallback((testId: number) => {
    modal.confirm({
      title: t('stress_test.delete_confirm_title'),
      content: t('stress_test.delete_confirm_content'),
      onOk: async () => {
        try {
          await deleteStressTest(testId);
          message.success(t('stress_test.test_deleted'));
          await loadTests();
        } catch {
          message.error(t('stress_test.delete_failed'));
        }
      },
    });
  }, [loadTests, t]);

  const handleAnalyzeTest = useCallback(async (testId: number) => {
    setAnalysisTestId(testId);
    setAnalysisModalVisible(true);
    setAnalysisLoading(true);
    setAnalysisResult('');

    try {
      const result = await getStressTestAnalysis(testId);
      setAnalysisResult(result.analysis);
    } catch (e) {
      message.error(errorMessage(e, t('ai.analysis_failed')));
    } finally {
      setAnalysisLoading(false);
    }
  }, [t]);

  const handleExportReport = useCallback(async (testId: number) => {
    try {
      const blob = await exportStressTestReport(testId);
      const url = window.URL.createObjectURL(new Blob([blob]));
      const link = document.createElement('a');
      link.href = url;
      link.setAttribute('download', `stress-test-report-${testId}.md`);
      document.body.appendChild(link);
      link.click();
      link.remove();
      window.URL.revokeObjectURL(url);
      message.success(t('stress_test.export_success'));
    } catch (e) {
      message.error(errorMessage(e, t('stress_test.export_failed')));
    }
  }, [t]);

  const handleShareReport = useCallback(async (testId: number, expiresIn?: number) => {
    try {
      const link = await createStressTestShareLink(testId, expiresIn);
      const shareUrl = `${window.location.origin}/share/${link.shareId}`;

      modal.success({
        title: t('stress_test.share_created'),
        content: (
          <div>
            <Paragraph>{t('stress_test.share_url_label')}</Paragraph>
            <Input
              value={shareUrl}
              readOnly
              addonAfter={
                <CopyOutlined
                  onClick={() => {
                    navigator.clipboard.writeText(shareUrl);
                    message.success(t('stress_test.copied'));
                  }}
                />
              }
            />
            <Paragraph type="secondary" style={{ marginTop: 8 }}>
              {t('stress_test.expires_in')}: {link.expiresIn}
            </Paragraph>
          </div>
        ),
      });
    } catch (e) {
      message.error(errorMessage(e, t('stress_test.share_failed')));
    }
  }, [t]);

  const showShareModal = useCallback((testId: number) => {
    let selectedValue = 24;

    modal.confirm({
      title: t('stress_test.create_share_link'),
      content: (
        <div>
          <Paragraph style={{ color: '#fff' }}>{t('stress_test.select_expiration')}</Paragraph>
          <Select
            style={{ width: '100%' }}
            className="dark-select"
            defaultValue={24}
            popupClassName="dark-select-dropdown"
            options={[
              { value: 1, label: t('stress_test.expires_1h') },
              { value: 24, label: t('stress_test.expires_24h') },
              { value: 168, label: t('stress_test.expires_7d') },
              { value: 0, label: t('stress_test.expires_permanent') },
            ]}
            onChange={(value) => {
              selectedValue = value;
            }}
          />
        </div>
      ),
      onOk: () => handleShareReport(testId, selectedValue === 0 ? undefined : selectedValue),
      okText: t('common.confirm'),
      cancelText: t('common.cancel'),
    });
  }, [handleShareReport, t]);

  const tabItems = [
    {
      key: 'history',
      label: (
        <span>
          <LineChartOutlined />
          {t('stress_test.history')}
        </span>
      ),
    },
    {
      key: 'create',
      label: (
        <span>
          <ThunderboltOutlined />
          {t('stress_test.create_test')}
        </span>
      ),
    },
  ];

  return (
    <div style={{ padding: '24px' }}>
      {/* Header */}
      <div style={{ marginBottom: '24px' }}>
        <Space style={{ width: '100%', justifyContent: 'space-between' }}>
          <Title level={3} style={{ color: '#fff' }}>
            {t('stress_test.title')}
          </Title>
          <Space>
            <Button
              type="primary"
              icon={<ThunderboltOutlined />}
              onClick={() => setQuickModalVisible(true)}
            >
              {t('stress_test.quick_test')}
            </Button>
            <Button icon={<ReloadOutlined />} onClick={loadTests}>
              {t('stress_test.refresh')}
            </Button>
          </Space>
        </Space>
        <Text style={{ color: 'rgba(255,255,255,0.65)' }}>
          {t('stress_test.subtitle')}
        </Text>
      </div>

      {/* Tabs */}
      <Tabs
        activeKey={activeTab}
        onChange={setActiveTab}
        items={tabItems}
        tabBarStyle={{ marginBottom: '16px' }}
      />

      {activeTab === 'history' && (
        <HistoryTab
          tests={tests}
          loading={loading}
          runningTestId={runningTestId}
          runningStatus={runningStatus}
          statusLoading={statusLoading}
          testMetrics={testMetrics}
          onStop={handleStopTest}
          onDelete={handleDeleteTest}
          onAnalyze={handleAnalyzeTest}
          onExport={handleExportReport}
          onShare={showShareModal}
        />
      )}
      {activeTab === 'create' && (
        <CreateTab creating={creating} onStart={handleCreateTest} />
      )}

      {/* Modals */}
      <QuickTestModal
        open={quickModalVisible}
        onClose={() => setQuickModalVisible(false)}
        onStart={handleQuickTest}
      />
      <AnalysisModal
        open={analysisModalVisible}
        testId={analysisTestId}
        loading={analysisLoading}
        content={analysisResult}
        onClose={() => setAnalysisModalVisible(false)}
      />
    </div>
  );
};

export default StressTestPage;
