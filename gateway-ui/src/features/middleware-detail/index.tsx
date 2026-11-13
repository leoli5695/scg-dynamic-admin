import '../instance-detail/operations-ui.css';
/**
 * Middleware detail page (opened in a new tab from the middleware drawer).
 * Reads serviceName / middlewareType / exporterUrl from the query string,
 * loads instant + trend metrics and supports optional auto-refresh.
 */
import React, { useMemo, useState } from 'react';
import { useTranslation } from 'react-i18next';
import {
  Button, Col, Empty, Row, Select, Space, Spin, Tabs, Tag, Typography  } from 'antd';
import { message } from '../../utils/antdStatic';
import {
  ArrowLeftOutlined, DashboardOutlined, InfoCircleOutlined,
  LineChartOutlined, ReloadOutlined,
} from '@ant-design/icons';
import { useNavigate, useSearchParams } from 'react-router-dom';
import { usePolling } from '../../hooks/useRequest';
import { getMiddlewareDetail } from '../../api/serviceMiddleware';
import type { MiddlewareDetailData } from '../../types/middleware';
import { buildTrendItems, getMiddlewareTypeConfig } from './utils';
import HealthPanel from './components/HealthPanel';
import InstantMetrics from './components/InstantMetrics';
import TrendCharts from './components/TrendCharts';
import SupportedMetricsInfo from './components/SupportedMetricsInfo';

const { Title } = Typography;

const TIME_RANGE_SECONDS: Record<string, number> = {
  '15m': 900,
  '30m': 1800,
  '1h': 3600,
  '2h': 7200,
  '6h': 21600,
  '24h': 86400,
};

const MiddlewareDetailPage: React.FC = () => {
  const { t } = useTranslation();
  const [searchParams] = useSearchParams();
  const navigate = useNavigate();

  // 自动刷新选项（在组件内构建，保证切换语言时即时刷新）
  const REFRESH_OPTIONS = [
    { value: 0, label: t('middleware.refresh_off') },
    { value: 10, label: t('middleware.refresh_10s') },
    { value: 30, label: t('middleware.refresh_30s') },
    { value: 60, label: t('middleware.refresh_1m') },
  ];

  // URL 参数
  const serviceName = searchParams.get('serviceName') || '';
  const middlewareType = searchParams.get('middlewareType') || '';
  const exporterUrl = searchParams.get('exporterUrl') || '';

  const [timeRange, setTimeRange] = useState<string>('1h');
  // 自动刷新间隔（秒），0 = off
  const [refreshInterval, setRefreshInterval] = useState<number>(0);

  const ready = Boolean(serviceName && middlewareType);
  const typeConfig = getMiddlewareTypeConfig(middlewareType);

  const detailRequest = usePolling<MiddlewareDetailData>(
    async (signal) => {
      const now = Math.floor(Date.now() / 1000);
      const start = now - (TIME_RANGE_SECONDS[timeRange] ?? 3600);
      // httpClient unwraps the {code,data,message} envelope and throws ApiError on
      // code!=200 (e.g. 500 internal error / 404 unknown exporterUrl), so the
      // resolved value is already MiddlewareDetailData.
      const res = await getMiddlewareDetail(
        serviceName,
        middlewareType,
        { exporterUrl: exporterUrl || undefined, start, end: now },
        { signal },
      );
      if (!res) {
        throw new Error(t('middleware.load_metrics_failed'));
      }
      return res;
    },
    {
      immediate: ready,
      paused: !ready || refreshInterval === 0,
      intervalMs: refreshInterval > 0 ? refreshInterval * 1000 : 60_000,
      deps: [serviceName, middlewareType, exporterUrl, timeRange],
      onError: (err) => message.error(t('middleware.load_metrics_failed_error', { error: err.message })),
    },
  );

  const data = detailRequest.data;

  const trendItems = useMemo(
    () => buildTrendItems(t, data?.trend?.timeSeries, data?.trend?.summary, middlewareType),
    [data, middlewareType, t],
  );

  return (
    <div className="middleware-detail-page console-workspace-page console-resource-page">
      {/* 页面头部 */}
      <div className="page-header console-page-header" style={{ marginBottom: 24 }}>
        <Row align="middle" gutter={16}>
          <Col>
            <Button icon={<ArrowLeftOutlined />} onClick={() => navigate(-1)}>
              {t('common.back')}
            </Button>
          </Col>
          <Col>
            <Tag color={typeConfig.color} icon={typeConfig.icon} style={{ fontSize: 16, padding: '4px 12px' }}>
              {typeConfig.label}
            </Tag>
          </Col>
          <Col>
            <Title level={4} style={{ margin: 0 }}>{serviceName}</Title>
          </Col>
          <Col flex="auto" />
          <Col>
            <Space>
              <Select
                value={refreshInterval}
                onChange={setRefreshInterval}
                options={REFRESH_OPTIONS}
                size="small"
                style={{ width: 120 }}
              />
              <Button
                icon={<ReloadOutlined />}
                onClick={detailRequest.run}
                loading={detailRequest.loading}
              >
                {t('middleware.refresh')}
              </Button>
            </Space>
          </Col>
        </Row>
      </div>

      {/* 主内容 */}
      <Spin spinning={detailRequest.loading}>
        {!ready ? (
          <Empty description={t('middleware.missing_params')} />
        ) : !data ? (
          <Empty description={t('middleware.no_data')} />
        ) : (
          <>
            {/* 健康状态面板 */}
            {data.healthStatus && (
              <HealthPanel
                healthStatus={data.healthStatus}
                serviceName={serviceName}
                exporterUrl={exporterUrl}
                typeConfig={typeConfig}
                timeRange={timeRange}
                onTimeRangeChange={setTimeRange}
              />
            )}

            {/* 标签页切换 */}
            <Tabs
              defaultActiveKey="instant"
              items={[
                {
                  key: 'instant',
                  label: <Space><DashboardOutlined />{t('middleware.instant_metrics')}</Space>,
                  children: <InstantMetrics instant={data.instant} />,
                },
                {
                  key: 'trend',
                  label: <Space><LineChartOutlined />{t('middleware.time_trend')}</Space>,
                  children: (
                    <TrendCharts
                      trendItems={trendItems}
                      middlewareType={middlewareType}
                      trend={data.trend}
                    />
                  ),
                },
                {
                  key: 'info',
                  label: <Space><InfoCircleOutlined />{t('middleware.metrics_info')}</Space>,
                  children: data.supportedMetrics
                    ? <SupportedMetricsInfo info={data.supportedMetrics} />
                    : <Empty description={t('middleware.no_metrics_info')} />,
                },
              ]}
            />
          </>
        )}
      </Spin>
    </div>
  );
};

export default MiddlewareDetailPage;
