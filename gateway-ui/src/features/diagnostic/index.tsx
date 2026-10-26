/**
 * System diagnostic page: runs full/quick diagnostics, shows per-component
 * health cards, score trend history and the compare-with-previous summary.
 * Runs on mount with a quick diagnostic; optional auto-refresh interval.
 */
import React, { useCallback, useEffect, useState } from 'react';
import {
  Alert, Button, Card, Col, Progress, Row, Select, Space, Spin, Statistic,
  Switch, Typography  } from 'antd';
import { message } from '../../utils/antdStatic';
import {
  ApiOutlined, CloudServerOutlined, DashboardOutlined, DatabaseOutlined,
  LockOutlined, MedicineBoxOutlined, RocketOutlined, SyncOutlined,
  ThunderboltOutlined,
} from '@ant-design/icons';
import { useTranslation } from 'react-i18next';
import { ApiError } from '../../api/http';
import { parseServerTime } from '../../utils/serverTime';
import { runFullDiagnostic, runQuickDiagnostic } from '../../api/diagnostic';
import type { DiagnosticReport } from '../../types/diagnostic';
import { useInstanceDetail } from '../instance-detail/InstanceDetailContext';
import ComponentCard from './components/ComponentCard';
import HistoryTrendCard from './components/HistoryTrendCard';
import { getScoreColor, getScoreStatus, getStatusIcon } from './utils';
import './diagnostic.css';

const { Title, Text, Paragraph } = Typography;

const DiagnosticPage: React.FC = () => {
  const { t } = useTranslation();
  const { instance } = useInstanceDetail();
  const instanceId = instance.instanceId;

  const [loading, setLoading] = useState(false);
  const [report, setReport] = useState<DiagnosticReport | null>(null);
  const [error, setError] = useState<string | null>(null);
  // Bumped after each successful run so history + comparison refresh.
  const [reloadKey, setReloadKey] = useState(0);

  // Auto refresh settings
  const [autoRefresh, setAutoRefresh] = useState(false);
  const [refreshInterval, setRefreshInterval] = useState(60); // seconds

  const runDiagnostic = useCallback(async (type: 'full' | 'quick') => {
    setLoading(true);
    setError(null);
    try {
      const data = type === 'full' ? await runFullDiagnostic() : await runQuickDiagnostic();
      setReport(data);
      setReloadKey((k) => k + 1);
    } catch (e) {
      setError(e instanceof ApiError ? e.message : 'Failed to run diagnostic');
      message.error(t('diagnostic.run_failed'));
    } finally {
      setLoading(false);
    }
  }, [t]);

  // Initial quick diagnostic on mount
  useEffect(() => {
    (async () => {
      await runDiagnostic('quick');
    })();
  }, [runDiagnostic]);

  // Auto refresh: rerun a quick diagnostic on the chosen interval
  useEffect(() => {
    if (!autoRefresh) return undefined;
    const timer = setInterval(() => {
      runDiagnostic('quick');
    }, refreshInterval * 1000);
    return () => clearInterval(timer);
  }, [autoRefresh, refreshInterval, runDiagnostic]);

  if (loading && !report) {
    return (
      <div style={{ textAlign: 'center', padding: 100 }}>
        <Spin size="large" />
        <div style={{ marginTop: 16 }}>
          <Text type="secondary">{t('diagnostic.running')}</Text>
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
        marginBottom: 16,
      }}>
        <Title level={4} style={{ margin: 0 }}>
          <MedicineBoxOutlined style={{ marginRight: 8 }} />
          {t('diagnostic.title')}
        </Title>
        <Space>
          {/* Auto refresh controls */}
          <Space>
            <Text type="secondary">{t('diagnostic.auto_refresh')}</Text>
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
                  { value: 300, label: '5min' },
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
            {t('diagnostic.full_check')}
          </Button>
          <Button
            icon={<ThunderboltOutlined />}
            onClick={() => runDiagnostic('quick')}
            loading={loading}
          >
            {t('diagnostic.quick_check')}
          </Button>
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

      {/* Overall score */}
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
                      <div style={{ fontSize: 12 }}>{t('diagnostic.score')}</div>
                    </div>
                  )}
                />
              </div>
            </Col>
            <Col xs={24} sm={16} md={18}>
              <Row gutter={16}>
                <Col span={8}>
                  <Statistic
                    title={t('diagnostic.status')}
                    value={report.status}
                    valueStyle={{
                      color: getScoreColor(report.overallScore),
                      fontSize: 18,
                    }}
                    prefix={getStatusIcon(report.status)}
                  />
                </Col>
                <Col span={8}>
                  <Statistic
                    title={t('diagnostic.duration')}
                    value={report.duration}
                  />
                </Col>
                <Col span={8}>
                  <Statistic
                    title={t('diagnostic.timestamp')}
                    value={parseServerTime(report.endTime).toLocaleTimeString()}
                  />
                </Col>
              </Row>

              {/* Recommendations */}
              {report.recommendations && report.recommendations.length > 0 && (
                <div style={{ marginTop: 16 }}>
                  <Text strong>{t('diagnostic.recommendations')}:</Text>
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

      {/* Component diagnostics */}
      {report && (
        <Row gutter={[16, 16]}>
          <Col xs={24} lg={12}>
            <ComponentCard
              title={t('diagnostic.database')}
              icon={<DatabaseOutlined />}
              diagnostic={report.database}
            />
          </Col>
          <Col xs={24} lg={12}>
            <ComponentCard
              title={t('diagnostic.redis')}
              icon={<CloudServerOutlined />}
              diagnostic={report.redis}
            />
          </Col>
          <Col xs={24} lg={12}>
            <ComponentCard
              title={t('diagnostic.config_center')}
              icon={<ApiOutlined />}
              diagnostic={report.configCenter}
            />
          </Col>
          <Col xs={24} lg={12}>
            <ComponentCard
              title={t('diagnostic.routes')}
              icon={<DashboardOutlined />}
              diagnostic={report.routes}
            />
          </Col>
          <Col xs={24} lg={12}>
            <ComponentCard
              title={t('diagnostic.auth')}
              icon={<LockOutlined />}
              diagnostic={report.auth}
            />
          </Col>
          <Col xs={24} lg={12}>
            <ComponentCard
              title={t('diagnostic.gateway_instances')}
              icon={<CloudServerOutlined />}
              diagnostic={report.gatewayInstances}
            />
          </Col>
          <Col xs={24}>
            <ComponentCard
              title={t('diagnostic.performance')}
              icon={<DashboardOutlined />}
              diagnostic={report.performance}
            />
          </Col>
        </Row>
      )}

      {/* History trend + comparison */}
      <HistoryTrendCard instanceId={instanceId} report={report} reloadKey={reloadKey} />

      {!loading && !report && (
        <Card>
          <div style={{ textAlign: 'center', padding: 40 }}>
            <MedicineBoxOutlined style={{ fontSize: 48, color: '#8c8c8c' }} />
            <Paragraph style={{ marginTop: 16, color: '#8c8c8c' }}>
              {t('diagnostic.click_to_run')}
            </Paragraph>
          </div>
        </Card>
      )}
    </div>
  );
};

export default DiagnosticPage;
