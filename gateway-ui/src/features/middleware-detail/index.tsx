/**
 * Middleware detail page (opened in a new tab from the middleware drawer).
 * Reads serviceName / middlewareType / exporterUrl from the query string,
 * loads instant + trend metrics and supports optional auto-refresh.
 * Strings stay in Chinese to match the legacy page (it was never i18n-ed).
 */
import React, { useMemo, useState } from 'react';
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

const REFRESH_OPTIONS = [
  { value: 0, label: '不自动刷新' },
  { value: 10, label: '每10秒' },
  { value: 30, label: '每30秒' },
  { value: 60, label: '每1分钟' },
];

const MiddlewareDetailPage: React.FC = () => {
  const [searchParams] = useSearchParams();
  const navigate = useNavigate();

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
        throw new Error('加载指标失败');
      }
      return res;
    },
    {
      immediate: ready,
      paused: !ready || refreshInterval === 0,
      intervalMs: refreshInterval > 0 ? refreshInterval * 1000 : 60_000,
      deps: [serviceName, middlewareType, exporterUrl, timeRange],
      onError: (err) => message.error(`加载指标失败: ${err.message}`),
    },
  );

  const data = detailRequest.data;

  const trendItems = useMemo(
    () => buildTrendItems(data?.trend?.timeSeries, data?.trend?.summary, middlewareType),
    [data, middlewareType],
  );

  return (
    <div className="middleware-detail-page" style={{ padding: 24 }}>
      {/* 页面头部 */}
      <div className="page-header" style={{ marginBottom: 24 }}>
        <Row align="middle" gutter={16}>
          <Col>
            <Button icon={<ArrowLeftOutlined />} onClick={() => navigate(-1)}>
              返回
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
                刷新
              </Button>
            </Space>
          </Col>
        </Row>
      </div>

      {/* 主内容 */}
      <Spin spinning={detailRequest.loading}>
        {!ready ? (
          <Empty description="缺少必要参数，请从中间件列表页面进入" />
        ) : !data ? (
          <Empty description="暂无数据" />
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
                  label: <Space><DashboardOutlined />即时指标</Space>,
                  children: <InstantMetrics instant={data.instant} />,
                },
                {
                  key: 'trend',
                  label: <Space><LineChartOutlined />时间趋势</Space>,
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
                  label: <Space><InfoCircleOutlined />指标说明</Space>,
                  children: data.supportedMetrics
                    ? <SupportedMetricsInfo info={data.supportedMetrics} />
                    : <Empty description="暂无指标说明" />,
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
