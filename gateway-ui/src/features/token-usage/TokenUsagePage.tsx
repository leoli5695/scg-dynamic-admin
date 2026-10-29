/**
 * Token 计量可观测页：总量卡 + 时序/租户/路由/模型图 + 明细分页表。
 * 数据源 = 网关结算后上报的 token_usage_history（/api/token-usage 聚合）。
 */
import React, { useEffect, useMemo, useState } from 'react';
import {
  Alert,
  Button,
  Card,
  Col,
  DatePicker,
  Input,
  Row,
  Select,
  Space,
  Spin,
  Table,
  Tabs,
  Tag,
} from 'antd';
import type { Dayjs } from 'dayjs';
import dayjs from 'dayjs';
import { useSearchParams } from 'react-router-dom';
import { isUsageUnresolved, usageTokenValue } from './usagePresentation';
import { readUsageQuery, updateUsageQuery } from './usageQuery';
import ReactECharts from 'echarts-for-react';
import { useTranslation } from 'react-i18next';
import { useRequest } from '../../hooks/useRequest';
import { listInstances } from '../../api/instances';
import {
  getTokenUsageBreakdown,
  getTokenUsageEvents,
  getTokenUsageOverview,
  getTokenUsageTimeseries,
  getTokenQuotaTenants,
} from '../../api/tokenUsage';
import type { TokenUsageBucket, TokenUsageFilters, TokenUsageEvent } from '../../api/tokenUsage';
import TokenUsageReconciliationDrawer from './TokenUsageReconciliationDrawer';
import {
  ApiOutlined,
  BarChartOutlined,
  ClockCircleOutlined,
  ReloadOutlined,
  TeamOutlined,
  ThunderboltOutlined,
} from '@ant-design/icons';
import { ManagementMetric, ManagementPageHeader } from '../layout/components/ManagementPage';
import './token-usage.css';

const { RangePicker } = DatePicker;

const PALETTE = ['#7fa8f5', '#a89beb', '#68cbb4', '#d3b67c', '#7bc3d3', '#d99bbf'];
const AXIS_TEXT = 'rgba(255,255,255,0.65)';
const SPLIT_LINE = 'rgba(255,255,255,0.12)';

// Stable identity for "no data yet". An inline `?? []` below produced a fresh array on every render,
// which made the timeseries useMemo dependency change every time and defeated the memo entirely.
const EMPTY_SERIES: TokenUsageBucket[] = [];

const baseOption = () => ({
  animation: false,
  backgroundColor: 'transparent',
  color: PALETTE,
  textStyle: { color: AXIS_TEXT },
  tooltip: {
    trigger: 'axis',
    backgroundColor: 'rgba(20,20,20,0.9)',
    borderColor: SPLIT_LINE,
    textStyle: { color: '#fff' },
  },
  grid: { left: 48, right: 24, top: 32, bottom: 28 },
});

function useDebouncedFilter(value: string) {
  const [committed, setCommitted] = useState(value);
  useEffect(() => {
    const timer = setTimeout(() => setCommitted(value.trim()), 300);
    return () => clearTimeout(timer);
  }, [value]);
  return committed;
}
const CHART_OPTIONS = { renderer: 'svg' as const };

const barOption = (rows: { key: string; totalTokens: number }[], horizontal: boolean) => ({
  ...baseOption(),
  xAxis: horizontal
    ? {
        type: 'value',
        splitLine: { lineStyle: { color: SPLIT_LINE } },
        axisLabel: { color: AXIS_TEXT },
      }
    : {
        type: 'category',
        data: rows.map((r) => r.key),
        axisLine: { lineStyle: { color: SPLIT_LINE } },
        axisLabel: { color: AXIS_TEXT, interval: 0, rotate: rows.length > 5 ? 24 : 0 },
      },
  yAxis: horizontal
    ? {
        type: 'category',
        data: rows.map((r) => r.key).reverse(),
        axisLabel: { color: AXIS_TEXT },
      }
    : {
        type: 'value',
        splitLine: { lineStyle: { color: SPLIT_LINE } },
        axisLabel: { color: AXIS_TEXT },
      },
  series: [
    {
      type: 'bar',
      data: horizontal ? rows.map((r) => r.totalTokens).reverse() : rows.map((r) => r.totalTokens),
      itemStyle: { borderRadius: horizontal ? [0, 4, 4, 0] : [4, 4, 0, 0] },
    },
  ],
  grid: { left: horizontal ? 120 : 48, right: 24, top: 16, bottom: 28 },
});

export interface TokenUsagePageProps {
  scopeInstanceId?: string;
}

// A different instance must not inherit data, pagination or an open drawer from the previous scope.
const TokenUsagePage: React.FC<TokenUsagePageProps> = (props) => (
  <TokenUsageView key={props.scopeInstanceId ?? '__global__'} {...props} />
);

const TokenUsageView: React.FC<TokenUsagePageProps> = ({ scopeInstanceId }) => {
  const { t } = useTranslation();
  const [searchParams, setSearchParams] = useSearchParams();
  const { instanceId, routeId, unresolvedOnly } = useMemo(
    () => readUsageQuery(searchParams, scopeInstanceId),
    [searchParams, scopeInstanceId],
  );
  const setQuery = (patch: Parameters<typeof updateUsageQuery>[1], replace = false) => {
    setSearchParams((current) => updateUsageQuery(current, patch, scopeInstanceId), { replace });
  };
  const [range, setRange] = useState<[Dayjs, Dayjs]>([dayjs().subtract(24, 'hour'), dayjs()]);
  const [granularity, setGranularity] = useState<'hour' | 'day'>('hour');
  const [tenantId, setTenantId] = useState('');
  const [model, setModel] = useState('');
  const [reconciling, setReconciling] = useState<TokenUsageEvent | null>(null);
  const committedModel = useDebouncedFilter(model);

  const filters: TokenUsageFilters = useMemo(
    () => ({
      from: range[0].format('YYYY-MM-DDTHH:mm:ss'),
      to: range[1].format('YYYY-MM-DDTHH:mm:ss'),
      instanceId,
      tenantId,
      routeId: routeId.trim(),
      model: committedModel,
    }),
    [range, instanceId, tenantId, routeId, committedModel],
  );
  const pageContext = JSON.stringify([filters, unresolvedOnly]);
  const [pagination, setPagination] = useState({ context: pageContext, page: 0 });
  const page = pagination.context === pageContext ? pagination.page : 0;
  const setPage = (value: number) => setPagination({ context: pageContext, page: value });
  const deps = [filters, page, unresolvedOnly];

  const instancesReq = useRequest((signal) => listInstances({ signal }), {
    immediate: scopeInstanceId === undefined,
  });
  const tenantsReq = useRequest((signal) => getTokenQuotaTenants({ signal }), {});
  const overviewReq = useRequest((signal) => getTokenUsageOverview(filters, { signal }), {
    deps: [filters],
  });
  const seriesReq = useRequest(
    (signal) => getTokenUsageTimeseries(filters, granularity, { signal }),
    { deps: [filters, granularity] },
  );
  const tenantReq = useRequest(
    (signal) => getTokenUsageBreakdown(filters, 'tenant', 8, { signal }),
    { deps: [filters] },
  );
  const routeReq = useRequest(
    (signal) => getTokenUsageBreakdown(filters, 'route', 10, { signal }),
    { deps: [filters] },
  );
  const modelReq = useRequest((signal) => getTokenUsageBreakdown(filters, 'model', 8, { signal }), {
    deps: [filters],
  });
  const eventsReq = useRequest(
    (signal) => getTokenUsageEvents({ ...filters, unresolvedOnly }, page, 20, { signal }),
    {
      deps,
    },
  );

  const overview = overviewReq.data;
  const series = seriesReq.data?.series ?? EMPTY_SERIES;
  const hasData = (overview?.requests ?? 0) > 0;

  const timeseriesOption = useMemo(
    () => ({
      ...baseOption(),
      legend: { textStyle: { color: AXIS_TEXT }, top: 0 },
      xAxis: {
        type: 'category',
        data: series.map((s) => s.bucket),
        axisLine: { lineStyle: { color: SPLIT_LINE } },
        axisLabel: { color: AXIS_TEXT },
      },
      yAxis: {
        type: 'value',
        splitLine: { lineStyle: { color: SPLIT_LINE } },
        axisLabel: { color: AXIS_TEXT },
      },
      series: [
        {
          name: t('tokenUsage.chart.prompt'),
          type: 'bar',
          stack: 'tokens',
          data: series.map((s) => s.promptTokens),
        },
        {
          name: t('tokenUsage.chart.completion'),
          type: 'bar',
          stack: 'tokens',
          data: series.map((s) => s.completionTokens),
        },
        {
          name: t('tokenUsage.chart.total'),
          type: 'line',
          smooth: true,
          data: series.map((s) => s.totalTokens),
        },
      ],
    }),
    [series, t],
  );

  const pieOption = useMemo(
    () => ({
      ...baseOption(),
      tooltip: {
        trigger: 'item',
        backgroundColor: 'rgba(20,20,20,0.9)',
        borderColor: SPLIT_LINE,
        textStyle: { color: '#fff' },
      },
      series: [
        {
          type: 'pie',
          radius: ['42%', '70%'],
          label: { color: AXIS_TEXT, formatter: '{b}: {d}%' },
          data: (tenantReq.data?.rows ?? []).map((r) => ({ name: r.key, value: r.totalTokens })),
        },
      ],
    }),
    [tenantReq.data],
  );

  const routeBarOption = useMemo(() => barOption(routeReq.data?.rows ?? [], true), [routeReq.data]);
  const modelBarOption = useMemo(
    () => barOption(modelReq.data?.rows ?? [], false),
    [modelReq.data],
  );
  const events = eventsReq.data?.content ?? [];
  const requests = [overviewReq, seriesReq, tenantReq, routeReq, modelReq, eventsReq];
  const refreshing = requests.some((request) => request.loading);
  const requestError = requests.find((request) => request.error)?.error;
  const refresh = () => requests.forEach((request) => request.run());
  const resetFilters = () => {
    setRange([dayjs().subtract(24, 'hour'), dayjs()]);
    setGranularity('hour');
    setTenantId('');
    setModel('');
    setQuery({ instanceId: '', routeId: '', unresolvedOnly: false });
    setPage(0);
  };

  return (
    <div className="token-usage-page management-page">
      {((overview?.unknownUsageRequests ?? 0) > 0 ||
        (overview?.failedSettlementRequests ?? 0) > 0) && (
        <Alert
          type="warning"
          showIcon
          message={t('tokenUsage.pendingWarning', {
            unknown: overview?.unknownUsageRequests ?? 0,
            failed: overview?.failedSettlementRequests ?? 0,
          })}
          action={
            <Button size="small" onClick={() => setQuery({ unresolvedOnly: true })}>
              {t('tokenUsage.viewUnresolved')}
            </Button>
          }
        />
      )}
      <ManagementPageHeader
        title={t('tokenUsage.title')}
        subtitle={t('tokenUsage.subtitle')}
        eyebrow={t('tokenUsage.workspace_label')}
        actions={
          <Button
            icon={<ReloadOutlined spin={refreshing} />}
            onClick={refresh}
            disabled={refreshing}
          >
            {t('common.refresh')}
          </Button>
        }
      />

      <div className="management-metrics">
        <ManagementMetric
          title={t('tokenUsage.card.requests')}
          value={overview?.requests ?? '—'}
          icon={<ApiOutlined />}
          subtitle={t('tokenUsage.requests_hint')}
        />
        <ManagementMetric
          title={t('tokenUsage.card.totalTokens')}
          value={overview?.totalTokens ?? '—'}
          icon={<ThunderboltOutlined />}
          tone="purple"
          subtitle={
            overview
              ? t('tokenUsage.card.totalTokens.sub', {
                  prompt: overview.promptTokens,
                  completion: overview.completionTokens,
                })
              : t('tokenUsage.tokens_hint')
          }
        />
        <ManagementMetric
          title={t('tokenUsage.card.avgLatency')}
          value={
            <>
              {hasData ? (overview?.avgLatencyMs ?? '—') : '—'}
              <span className="metric-unit">ms</span>
            </>
          }
          icon={<ClockCircleOutlined />}
          tone="amber"
          subtitle={t('tokenUsage.latency_hint')}
        />
        <ManagementMetric
          title={t('tokenUsage.card.active')}
          value={overview?.activeTenants ?? '—'}
          icon={<TeamOutlined />}
          tone="green"
          subtitle={
            overview
              ? t('tokenUsage.card.active.sub', {
                  tenants: overview.activeTenants,
                  routes: overview.activeRoutes,
                  models: overview.activeModels,
                })
              : t('tokenUsage.active_hint')
          }
        />
      </div>
      <Alert type="info" showIcon message={t('tokenUsage.recordedTokensNote')} />

      <Card
        className="token-filter-card"
        size="small"
        title={t('tokenUsage.filters_title')}
        extra={
          <Button type="link" size="small" onClick={resetFilters}>
            {t('tokenUsage.reset')}
          </Button>
        }
      >
        <div className="token-filters-grid">
          <div className="token-filter-field token-filter-wide">
            <span>{t('tokenUsage.filter.range')}</span>
            <RangePicker
              aria-label={t('tokenUsage.filter.range')}
              showTime
              value={range}
              onChange={(v) => {
                if (v && v[0] && v[1]) {
                  setRange([v[0], v[1]]);
                  setPage(0);
                }
              }}
              presets={[
                {
                  label: t('tokenUsage.filter.preset.24h'),
                  value: [dayjs().subtract(24, 'hour'), dayjs()],
                },
                {
                  label: t('tokenUsage.filter.preset.7d'),
                  value: [dayjs().subtract(7, 'day'), dayjs()],
                },
                {
                  label: t('tokenUsage.filter.preset.30d'),
                  value: [dayjs().subtract(30, 'day'), dayjs()],
                },
              ]}
            />
          </div>
          <div className="token-filter-field">
            <span>{t('tokenUsage.filter.granularity')}</span>
            <Select
              aria-label={t('tokenUsage.filter.granularity')}
              style={{ width: 100 }}
              value={granularity}
              onChange={(v) => setGranularity(v)}
              options={[
                { value: 'hour', label: t('tokenUsage.filter.granularity.hour') },
                { value: 'day', label: t('tokenUsage.filter.granularity.day') },
              ]}
            />
          </div>
          {scopeInstanceId === undefined ? (
            <div className="token-filter-field">
              <span>{t('tokenUsage.filter.instance')}</span>
              <Select
                aria-label={t('tokenUsage.filter.instance')}
                style={{ width: 200 }}
                allowClear
                showSearch
                placeholder={t('tokenUsage.filter.instance.all')}
                value={instanceId || undefined}
                onChange={(v) => setQuery({ instanceId: v ?? '' })}
                optionFilterProp="label"
                options={(instancesReq.data ?? []).map((i) => ({
                  value: i.instanceId,
                  label: `${i.instanceName} · ${i.instanceId}`,
                }))}
              />
            </div>
          ) : (
            <div className="token-filter-field">
              <span>{t('tokenUsage.filter.scopedInstance')}</span>
              <Input
                aria-label={t('tokenUsage.filter.scopedInstance')}
                value={scopeInstanceId}
                readOnly
              />
            </div>
          )}
          <div className="token-filter-field">
            <span>{t('tokenUsage.filter.tenant')}</span>
            <Select
              aria-label={t('tokenUsage.filter.tenant')}
              style={{ width: 180 }}
              allowClear
              showSearch
              placeholder={t('tokenUsage.filter.tenant.all')}
              value={tenantId || undefined}
              onChange={(v) => {
                setTenantId(v ?? '');
                setPage(0);
              }}
              options={(tenantsReq.data ?? []).map((q) => ({
                value: q.tenantId,
                label: q.tenantId,
              }))}
            />
          </div>
          <div className="token-filter-field">
            <span>{t('tokenUsage.filter.route')}</span>
            <Input
              aria-label={t('tokenUsage.filter.route')}
              style={{ width: 160 }}
              allowClear
              placeholder={t('tokenUsage.filter.route.ph')}
              value={routeId}
              onChange={(e) => setQuery({ routeId: e.target.value }, true)}
            />
          </div>
          <div className="token-filter-field token-filter-wide">
            <span>{t('tokenUsage.filter.model')}</span>
            <Input
              aria-label={t('tokenUsage.filter.model')}
              style={{ width: 160 }}
              allowClear
              placeholder={t('tokenUsage.filter.model.ph')}
              value={model}
              onChange={(e) => {
                setModel(e.target.value);
                setPage(0);
              }}
            />
          </div>
        </div>
      </Card>

      {requestError && (
        <Alert
          showIcon
          type="error"
          message={t('tokenUsage.load_failed')}
          description={requestError.message}
        />
      )}

      {!hasData ? (
        <Spin spinning={overviewReq.loading}>
          <div className="management-empty">
            <span className="management-empty-icon">
              <BarChartOutlined />
            </span>
            <h3>{t(requestError ? 'tokenUsage.load_failed' : 'tokenUsage.empty_title')}</h3>
            <p>{requestError ? t('tokenUsage.error_hint') : t('tokenUsage.empty')}</p>
            <Button onClick={requestError ? refresh : resetFilters}>
              {requestError ? t('common.refresh') : t('tokenUsage.reset')}
            </Button>
          </div>
        </Spin>
      ) : (
        <>
          <Row gutter={[16, 16]} style={{ marginBottom: 16 }}>
            <Col xs={24} lg={16}>
              <Card size="small" title={t('tokenUsage.chart.timeseries')}>
                <ReactECharts
                  opts={CHART_OPTIONS}
                  option={timeseriesOption}
                  style={{ height: 260 }}
                  notMerge
                />
              </Card>
            </Col>
            <Col xs={24} lg={8}>
              <Card size="small" title={t('tokenUsage.chart.tenantPie')}>
                <ReactECharts
                  opts={CHART_OPTIONS}
                  option={pieOption}
                  style={{ height: 260 }}
                  notMerge
                />
              </Card>
            </Col>
          </Row>
          <Row gutter={[16, 16]} style={{ marginBottom: 16 }}>
            <Col xs={24} lg={12}>
              <Card size="small" title={t('tokenUsage.chart.routeBar')}>
                <ReactECharts
                  opts={CHART_OPTIONS}
                  option={routeBarOption}
                  style={{ height: 240 }}
                  notMerge
                />
              </Card>
            </Col>
            <Col xs={24} lg={12}>
              <Card size="small" title={t('tokenUsage.chart.modelBar')}>
                <ReactECharts
                  opts={CHART_OPTIONS}
                  option={modelBarOption}
                  style={{ height: 240 }}
                  notMerge
                />
              </Card>
            </Col>
          </Row>
        </>
      )}

      <Card size="small" title={t('tokenUsage.table.title')}>
        <Tabs
          activeKey={unresolvedOnly ? 'unresolved' : 'daily'}
          onChange={(key) => setQuery({ unresolvedOnly: key === 'unresolved' })}
          items={[
            { key: 'daily', label: t('tokenUsage.tabs.daily') },
            { key: 'unresolved', label: t('tokenUsage.tabs.unresolved') },
          ]}
        />
        <Table
          scroll={{ x: scopeInstanceId === undefined ? 1200 : 1060 }}
          rowKey="id"
          size="small"
          loading={eventsReq.loading}
          dataSource={events}
          pagination={{
            current: page + 1,
            pageSize: 20,
            total: eventsReq.data?.total ?? 0,
            showSizeChanger: false,
            onChange: (p) => setPage(p - 1),
          }}
          locale={{
            emptyText: t(unresolvedOnly ? 'tokenUsage.emptyUnresolved' : 'tokenUsage.empty_title'),
          }}
          columns={[
            {
              title: t('tokenUsage.table.time'),
              dataIndex: 'requestTime',
              width: 170,
              render: (v: string) => (v ? v.replace('T', ' ').slice(0, 19) : '—'),
            },
            {
              title: t('tokenUsage.table.tenant'),
              dataIndex: 'tenantId',
              ellipsis: true,
              render: (v: string) =>
                v === '__unassigned__' ? t('tokenUsage.unassignedTenant') : v,
            },
            {
              title: t('tokenUsage.table.route'),
              dataIndex: 'routeId',
              ellipsis: true,
              render: (v: string | null) => v ?? '—',
            },
            {
              title: t('tokenUsage.table.model'),
              dataIndex: 'model',
              ellipsis: true,
              render: (v: string | null) => v ?? '—',
            },
            {
              title: t('tokenUsage.table.prompt'),
              dataIndex: 'promptTokens',
              render: (_: unknown, record: TokenUsageEvent) =>
                usageTokenValue(record, 'promptTokens'),
              width: 80,
              align: 'right',
            },
            {
              title: t('tokenUsage.table.completion'),
              dataIndex: 'completionTokens',
              render: (_: unknown, record: TokenUsageEvent) =>
                usageTokenValue(record, 'completionTokens'),
              width: 80,
              align: 'right',
            },
            {
              title: t('tokenUsage.table.total'),
              dataIndex: 'totalTokens',
              render: (_: unknown, record: TokenUsageEvent) =>
                usageTokenValue(record, 'totalTokens'),
              width: 80,
              align: 'right',
            },
            {
              title: t('tokenUsage.table.status'),
              key: 'status',
              width: 160,
              render: (_: unknown, record: TokenUsageEvent) => (
                <Space size={4} wrap>
                  <Tag color={isUsageUnresolved(record) ? 'gold' : undefined}>
                    {t(`tokenUsage.status.${record.usageStatus ?? 'LEGACY'}`, {
                      defaultValue: record.usageStatus ?? 'LEGACY',
                    })}
                  </Tag>
                  {['FAILED', 'PENDING'].includes(record.settlementStatus ?? '') && (
                    <Tag color="volcano">{t(`tokenUsage.status.${record.settlementStatus}`)}</Tag>
                  )}
                </Space>
              ),
            },
            ...(scopeInstanceId === undefined
              ? [
                  {
                    title: t('tokenUsage.table.instance'),
                    dataIndex: 'instanceId',
                    width: 140,
                    ellipsis: true,
                    render: (v: string | null) => v ?? '—',
                  },
                ]
              : []),
            {
              title: t('tokenUsage.details.action'),
              key: 'details',
              width: 90,
              fixed: 'right',
              render: (_: unknown, record: TokenUsageEvent) => (
                <Button type="link" onClick={() => setReconciling(record)}>
                  {t('tokenUsage.details.action')}
                </Button>
              ),
            },
          ]}
        />
      </Card>
      {reconciling && (
        <TokenUsageReconciliationDrawer
          key={reconciling.id}
          event={events.find((e) => e.id === reconciling.id) ?? reconciling}
          onClose={() => setReconciling(null)}
          onApplied={refresh}
        />
      )}
    </div>
  );
};

export default TokenUsagePage;
