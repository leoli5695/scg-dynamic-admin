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
import type { ColumnsType } from 'antd/es/table';
import type { Dayjs } from 'dayjs';
import dayjs from 'dayjs';
import { useLocation, useNavigate, useSearchParams } from 'react-router-dom';
import { isUsageUnresolved, usageTokenValue } from './usagePresentation';
import { defaultUsageRange, normalizeUsageQuery, readUsageQuery, updateUsageQuery } from './usageQuery';
import { usageSourceReturnUrl } from '../request-journey/usageNavigation';
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
  InfoCircleOutlined,
  ReloadOutlined,
  TeamOutlined,
  ThunderboltOutlined,
} from '@ant-design/icons';
import { ManagementMetric, ManagementPageHeader } from '../layout/components/ManagementPage';
import {
  chartAxis, chartColors, palette, withAlpha,
} from '../../styles/palette';
import { chartLegend, chartTooltip } from '../../styles/chartTheme';
import './token-usage.css';

const { RangePicker } = DatePicker;

const PALETTE = chartColors;
const AXIS_TEXT = chartAxis.label;
const SPLIT_LINE = chartAxis.grid;

/** Axis labels stay compact: 12.4k / 3.1M instead of seven-digit rows. */
const compact = (value: number): string => {
  if (value >= 1e9) return `${(value / 1e9).toFixed(value >= 1e10 ? 0 : 1)}B`;
  if (value >= 1e6) return `${(value / 1e6).toFixed(value >= 1e7 ? 0 : 1)}M`;
  if (value >= 1e3) return `${(value / 1e3).toFixed(value >= 1e4 ? 0 : 1)}k`;
  return String(value);
};

const verticalFade = (from: string, to: string) => ({
  type: 'linear' as const,
  x: 0,
  y: 0,
  x2: 0,
  y2: 1,
  colorStops: [{ offset: 0, color: from }, { offset: 1, color: to }],
});

const horizontalFade = (from: string, to: string) => ({
  type: 'linear' as const,
  x: 0,
  y: 0,
  x2: 1,
  y2: 0,
  colorStops: [{ offset: 0, color: from }, { offset: 1, color: to }],
});

// Stable identity for "no data yet". An inline `?? []` below produced a fresh array on every render,
// which made the timeseries useMemo dependency change every time and defeated the memo entirely.
const EMPTY_SERIES: TokenUsageBucket[] = [];

const baseOption = () => ({
  animation: false,
  backgroundColor: 'transparent',
  color: PALETTE,
  textStyle: { color: AXIS_TEXT, fontSize: 11 },
  tooltip: {
    trigger: 'axis',
    ...chartTooltip,
    axisPointer: { type: 'shadow', shadowStyle: { color: 'rgba(121, 157, 249, 0.06)' } },
  },
  grid: { left: 48, right: 24, top: 32, bottom: 28 },
});

const CHART_OPTIONS = { renderer: 'svg' as const };

const barOption = (rows: { key: string; totalTokens: number }[], horizontal: boolean) => {
  const max = Math.max(...rows.map((r) => r.totalTokens), 1);
  const gradient = horizontal
    ? horizontalFade(withAlpha('#6f9dff', 0.92), withAlpha('#a78bfa', 0.68))
    : verticalFade(withAlpha('#6f9dff', 0.92), withAlpha('#a78bfa', 0.55));
  return {
    ...baseOption(),
    tooltip: {
      trigger: 'axis',
      ...chartTooltip,
      axisPointer: { type: 'shadow', shadowStyle: { color: 'rgba(121, 157, 249, 0.06)' } },
    },
    xAxis: horizontal
      ? { type: 'value', show: false, max: max * 1.15 }
      : {
          type: 'category',
          data: rows.map((r) => r.key),
          axisLine: { lineStyle: { color: 'rgba(145, 169, 222, 0.16)' } },
          axisTick: { show: false },
          axisLabel: { color: AXIS_TEXT, fontSize: 10, interval: 0, rotate: rows.length > 5 ? 24 : 0 },
        },
    yAxis: horizontal
      ? {
          type: 'category',
          data: rows.map((r) => r.key).reverse(),
          axisLine: { show: false },
          axisTick: { show: false },
          axisLabel: { color: AXIS_TEXT, fontSize: 10, width: 96, overflow: 'truncate' },
        }
      : {
          type: 'value',
          splitLine: { lineStyle: { color: 'rgba(145, 169, 222, 0.075)', type: 'dashed' } },
          axisLabel: { color: AXIS_TEXT, fontSize: 10, formatter: compact },
        },
    series: [
      {
        type: 'bar',
        barWidth: horizontal ? 10 : '34%',
        showBackground: horizontal,
        backgroundStyle: { color: 'rgba(145, 169, 222, 0.06)', borderRadius: 6 },
        data: (horizontal ? [...rows].reverse() : rows).map((r) => ({
          value: r.totalTokens,
          itemStyle: { color: gradient, borderRadius: horizontal ? 6 : [4, 4, 0, 0] },
        })),
        label: horizontal
          ? {
              show: true,
              position: 'right',
              color: palette.textTertiary,
              fontSize: 10,
              formatter: (params: { value: number }) => compact(params.value),
            }
          : undefined,
      },
    ],
    grid: horizontal
      ? { left: 110, right: 40, top: 12, bottom: 10 }
      : { left: 46, right: 16, top: 18, bottom: 30 },
  };
};

export interface TokenUsagePageProps {
  scopeInstanceId?: string;
}

// A different instance must not inherit data, pagination or an open drawer from the previous scope.
const TokenUsagePage: React.FC<TokenUsagePageProps> = (props) => (
  <TokenUsageView key={props.scopeInstanceId ?? '__global__'} {...props} />
);

const TokenUsageView: React.FC<TokenUsagePageProps> = ({ scopeInstanceId }) => {
  const { t } = useTranslation();
  const location = useLocation();
  const navigate = useNavigate();
  const [searchParams, setSearchParams] = useSearchParams();
  const normalizedParams = useMemo(
    () => normalizeUsageQuery(searchParams, scopeInstanceId),
    [searchParams, scopeInstanceId],
  );
  const { instanceId, routeId, unresolvedOnly, from, to, tenantId, model, granularity, page, size } =
    readUsageQuery(normalizedParams, scopeInstanceId);
  useEffect(() => {
    if (normalizedParams.toString() !== searchParams.toString()) {
      setSearchParams(normalizedParams, { replace: true });
    }
  }, [normalizedParams, searchParams, setSearchParams]);
  const setQuery = (patch: Parameters<typeof updateUsageQuery>[1]) => {
    const next = updateUsageQuery(normalizedParams, patch, scopeInstanceId);
    if (next.toString() !== normalizedParams.toString()) setSearchParams(next);
  };
  const range: [Dayjs, Dayjs] = [dayjs(from), dayjs(to)];
  // Text fields hold only unsubmitted drafts. Enter/search commits once; navigation discards drafts.
  const inputContext = `${location.key}?${normalizedParams}`;
  const returnTo = usageSourceReturnUrl(instanceId, searchParams.get('returnTo'));
  const usageUrl = `${location.pathname}?${normalizedParams}`;
  const [reconciling, setReconciling] = useState<TokenUsageEvent | null>(null);

  const filters: TokenUsageFilters = useMemo(
    () => ({ from, to, instanceId, tenantId, routeId, model }),
    [from, to, instanceId, tenantId, routeId, model],
  );
  const deps = [filters, page, size, unresolvedOnly];

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
    (signal) => getTokenUsageEvents({ ...filters, unresolvedOnly }, page, size, { signal }),
    {
      deps,
    },
  );

  const overview = overviewReq.data;
  const series = seriesReq.data?.series ?? EMPTY_SERIES;
  const hasData = (overview?.requests ?? 0) > 0;

  const timeseriesOption = useMemo(() => {
    const firstDay = series[0]?.bucket.slice(0, 10);
    const singleDay = Boolean(firstDay) && series.every((s) => s.bucket.slice(0, 10) === firstDay);
    const bucketLabel = (value: string): string => {
      const time = value.match(/^(\d{4})-(\d{2}-\d{2})[T ](\d{2}:\d{2})/);
      if (!time) return value;
      return singleDay ? time[3] : `${time[2]} ${time[3]}`;
    };
    return {
      ...baseOption(),
      grid: { left: 46, right: 18, top: 44, bottom: 26 },
      legend: {
        ...chartLegend,
        top: 2,
        left: 0,
        itemGap: 18,
        textStyle: { color: AXIS_TEXT, fontSize: 11 },
      },
      xAxis: {
        type: 'category',
        data: series.map((s) => s.bucket),
        axisLine: { lineStyle: { color: 'rgba(145, 169, 222, 0.16)' } },
        axisTick: { show: false },
        axisLabel: {
          color: AXIS_TEXT, fontSize: 10, hideOverlap: true, margin: 12, formatter: bucketLabel,
        },
      },
      yAxis: {
        type: 'value',
        splitLine: { lineStyle: { color: 'rgba(145, 169, 222, 0.075)', type: 'dashed' } },
        axisLabel: { color: AXIS_TEXT, fontSize: 10, formatter: compact },
      },
      series: [
        {
          name: t('tokenUsage.chart.prompt'),
          type: 'bar',
          stack: 'tokens',
          barWidth: '46%',
          data: series.map((s) => s.promptTokens),
          itemStyle: {
            color: verticalFade(withAlpha('#5b8cf5', 0.95), withAlpha('#5b8cf5', 0.28)),
          },
          emphasis: { itemStyle: { color: '#7ba6ff' } },
        },
        {
          name: t('tokenUsage.chart.completion'),
          type: 'bar',
          stack: 'tokens',
          barWidth: '46%',
          data: series.map((s) => s.completionTokens),
          itemStyle: {
            color: verticalFade(withAlpha('#a78bfa', 0.95), withAlpha('#a78bfa', 0.24)),
            borderRadius: [3, 3, 0, 0],
          },
          emphasis: { itemStyle: { color: '#bfa8ff' } },
        },
        {
          name: t('tokenUsage.chart.total'),
          type: 'line',
          smooth: 0.35,
          symbol: 'none',
          data: series.map((s) => s.totalTokens),
          lineStyle: {
            width: 2,
            color: '#a7c5ff',
            shadowBlur: 10,
            shadowColor: 'rgba(126, 166, 255, 0.45)',
          },
          z: 3,
        },
      ],
    };
  }, [series, t]);

  const pieOption = useMemo(() => {
    const rows = tenantReq.data?.rows ?? [];
    const total = rows.reduce((sum, row) => sum + row.totalTokens, 0);
    return {
      ...baseOption(),
      tooltip: {
        trigger: 'item',
        ...chartTooltip,
      },
      title: {
        text: rows.length ? compact(total) : '',
        subtext: rows.length ? t('tokenUsage.chart.centerTotal') : '',
        left: 'center',
        top: '34%',
        textAlign: 'center',
        itemGap: 3,
        textStyle: { color: palette.textPrimary, fontSize: 22, fontWeight: 600 },
        subtextStyle: { color: palette.textCaption, fontSize: 10 },
      },
      legend: {
        ...chartLegend,
        bottom: 0,
        left: 'center',
        type: 'scroll',
        icon: 'circle',
        itemWidth: 8,
        itemHeight: 8,
        itemGap: 12,
        textStyle: { color: AXIS_TEXT, fontSize: 10 },
      },
      series: [
        {
          type: 'pie',
          radius: ['54%', '74%'],
          center: ['50%', '46%'],
          avoidLabelOverlap: true,
          itemStyle: { borderColor: palette.bgCard, borderWidth: 2, borderRadius: 4 },
          label: { show: false },
          labelLine: { show: false },
          emphasis: {
            scaleSize: 4,
            itemStyle: { shadowBlur: 18, shadowColor: 'rgba(3, 7, 18, 0.6)' },
          },
          data: rows.map((row, index) => ({
            name: row.key,
            value: row.totalTokens,
            itemStyle: { color: PALETTE[index % PALETTE.length] },
          })),
        },
      ],
    };
  }, [tenantReq.data, t]);

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
  // Rebuilding this array on every render made antd reconcile every cell on each page turn.
  const columns = useMemo<ColumnsType<TokenUsageEvent>>(() => [
    {
      title: t('tokenUsage.table.time'),
      dataIndex: 'requestTime',
      width: 150,
      className: 'token-col-time',
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
      className: 'token-col-prompt',
      render: (_: unknown, record: TokenUsageEvent) => {
        const value = usageTokenValue(record, 'promptTokens');
        return typeof value === 'number' ? value.toLocaleString() : value;
      },
      width: 76,
      align: 'right',
    },
    {
      title: t('tokenUsage.table.completion'),
      dataIndex: 'completionTokens',
      className: 'token-col-completion',
      render: (_: unknown, record: TokenUsageEvent) => {
        const value = usageTokenValue(record, 'completionTokens');
        return typeof value === 'number' ? value.toLocaleString() : value;
      },
      width: 76,
      align: 'right',
    },
    {
      title: t('tokenUsage.table.total'),
      dataIndex: 'totalTokens',
      className: 'token-col-total',
      render: (_: unknown, record: TokenUsageEvent) => {
        const value = usageTokenValue(record, 'totalTokens');
        return typeof value === 'number' ? value.toLocaleString() : value;
      },
      width: 76,
      align: 'right',
    },
    {
      title: t('tokenUsage.table.status'),
      key: 'status',
      width: 150,
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
            width: 120,
            ellipsis: true,
            render: (v: string | null) => v ?? '—',
          },
        ]
      : []),
    {
      title: t('tokenUsage.details.action'),
      key: 'details',
      width: 80,
      fixed: 'right',
      render: (_: unknown, record: TokenUsageEvent) => (
        <Button type="link" onClick={() => setReconciling(record)}>
          {t('tokenUsage.details.action')}
        </Button>
      ),
    },
  ], [t, scopeInstanceId]);
  const resetFilters = () => setQuery({
    ...defaultUsageRange(), instanceId: '', routeId: '', tenantId: '', model: '',
    unresolvedOnly: false, granularity: 'hour', page: 0, size: 20,
  });

  return (
    <div className="token-usage-page management-page">
      <ManagementPageHeader
        title={t('tokenUsage.title')}
        subtitle={t('tokenUsage.subtitle')}
        eyebrow={t('tokenUsage.workspace_label')}
        actions={
          <Space>
            {returnTo && <Button onClick={() => navigate(returnTo)}>{t('tokenUsage.backToSource')}</Button>}
            <Button
              icon={<ReloadOutlined spin={refreshing} />}
              onClick={refresh}
              disabled={refreshing}
            >
              {t('common.refresh')}
            </Button>
          </Space>
        }
      />

      {((overview?.unknownUsageRequests ?? 0) > 0 ||
        (overview?.failedSettlementRequests ?? 0) > 0) && (
        <Alert
          className="token-pending-alert"
          type="warning"
          showIcon
          title={t('tokenUsage.pendingWarning', {
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

      <div className="management-metrics">
        <ManagementMetric
          title={t('tokenUsage.card.requests')}
          value={overview?.requests ?? '—'}
          icon={<ApiOutlined />}
          subtitle={t('tokenUsage.requests_hint')}
          spark={series.map((s) => s.requests)}
          sparkTone={palette.primaryLight}
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
          spark={series.map((s) => s.totalTokens)}
          sparkTone={palette.accentLight}
          accentBar={overview && overview.totalTokens > 0 ? (
            <span className="management-metric-accent" aria-hidden="true">
              <i
                style={{
                  width: `${(overview.promptTokens / overview.totalTokens) * 100}%`,
                  background: 'linear-gradient(90deg, #5b8cf5, #6f9dff)',
                }}
              />
              <i
                style={{
                  width: `${(overview.completionTokens / overview.totalTokens) * 100}%`,
                  background: 'linear-gradient(90deg, #a78bfa, #bfa8ff)',
                }}
              />
            </span>
          ) : undefined}
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
      <p className="token-note">
        <InfoCircleOutlined aria-hidden="true" />
        <span>{t('tokenUsage.recordedTokensNote')}</span>
      </p>

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
              allowClear={false}
              value={range}
              onChange={(v) => {
                if (v && v[0] && v[1]) {
                  setQuery({ from: v[0].format('YYYY-MM-DDTHH:mm:ss'), to: v[1].format('YYYY-MM-DDTHH:mm:ss') });
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
              onChange={(v) => setQuery({ granularity: v })}
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
              onChange={(v) => setQuery({ tenantId: v ?? '' })}
              options={(tenantsReq.data ?? []).map((q) => ({
                value: q.tenantId,
                label: q.tenantId,
              }))}
            />
          </div>
          <div className="token-filter-field">
            <span>{t('tokenUsage.filter.route')}</span>
            <Input.Search
              key={`route:${inputContext}`}
              aria-label={t('tokenUsage.filter.route')}
              style={{ width: 180 }}
              allowClear
              placeholder={t('tokenUsage.filter.route.ph')}
              defaultValue={routeId}
              onSearch={(value) => setQuery({ routeId: value.trim() })}
            />
          </div>
          <div className="token-filter-field token-filter-wide">
            <span>{t('tokenUsage.filter.model')}</span>
            <Input.Search
              key={`model:${inputContext}`}
              aria-label={t('tokenUsage.filter.model')}
              style={{ width: 180 }}
              allowClear
              placeholder={t('tokenUsage.filter.model.ph')}
              defaultValue={model}
              onSearch={(value) => setQuery({ model: value.trim() })}
            />
          </div>
        </div>
      </Card>

      {requestError && (
        <Alert
          showIcon
          type="error"
          title={t('tokenUsage.load_failed')}
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
          scroll={{ x: scopeInstanceId === undefined ? 1120 : 1000 }}
          rowKey="id"
          size="small"
          loading={eventsReq.loading}
          dataSource={events}
          pagination={{
            current: page + 1,
            pageSize: size,
            total: eventsReq.data?.total ?? 0,
            showSizeChanger: true,
            pageSizeOptions: [20, 50, 100],
            onChange: (p, nextSize) => setQuery({ page: p - 1, size: nextSize }),
          }}
          locale={{
            emptyText: t(unresolvedOnly ? 'tokenUsage.emptyUnresolved' : 'tokenUsage.empty_title'),
          }}
          columns={columns}
        />
      </Card>
      {reconciling && (
        <TokenUsageReconciliationDrawer
          key={reconciling.id}
          event={events.find((e) => e.id === reconciling.id) ?? reconciling}
          onClose={() => setReconciling(null)}
          onApplied={refresh}
          usageReturnTo={usageUrl}
        />
      )}
    </div>
  );
};

export default TokenUsagePage;
