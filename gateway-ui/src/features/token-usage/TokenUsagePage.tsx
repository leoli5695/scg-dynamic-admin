/**
 * Token 计量可观测页：总量卡 + 时序/租户/路由/模型图 + 明细分页表。
 * 数据源 = 网关结算后上报的 token_usage_history（/api/token-usage 聚合）。
 */
import React, { useMemo, useState } from 'react';
import {
  Card, Col, DatePicker, Empty, Input, Row, Select, Space, Statistic, Table, Typography,
} from 'antd';
import type { Dayjs } from 'dayjs';
import dayjs from 'dayjs';
import ReactECharts from 'echarts-for-react';
import { useTranslation } from 'react-i18next';
import { useRequest } from '../../hooks/useRequest';
import { listInstances } from '../../api/instances';
import {
  getTokenUsageBreakdown, getTokenUsageEvents, getTokenUsageOverview, getTokenUsageTimeseries,
  getTokenQuotaTenants,
} from '../../api/tokenUsage';
import type { TokenUsageBucket, TokenUsageFilters } from '../../api/tokenUsage';

const { Title, Text } = Typography;
const { RangePicker } = DatePicker;

const PALETTE = ['#165DFF', '#52c41a', '#faad14', '#722ed1', '#13c2c2', '#eb2f96', '#fa8c16', '#a0d911'];
const AXIS_TEXT = 'rgba(255,255,255,0.65)';
const SPLIT_LINE = 'rgba(255,255,255,0.12)';

// Stable identity for "no data yet". An inline `?? []` below produced a fresh array on every render,
// which made the timeseries useMemo dependency change every time and defeated the memo entirely.
const EMPTY_SERIES: TokenUsageBucket[] = [];

const baseOption = () => ({
  backgroundColor: 'transparent',
  color: PALETTE,
  textStyle: { color: AXIS_TEXT },
  tooltip: { trigger: 'axis', backgroundColor: 'rgba(20,20,20,0.9)', borderColor: SPLIT_LINE, textStyle: { color: '#fff' } },
  grid: { left: 48, right: 24, top: 32, bottom: 28 },
});

const TokenUsagePage: React.FC = () => {
  const { t } = useTranslation();
  const [range, setRange] = useState<[Dayjs, Dayjs]>([dayjs().subtract(24, 'hour'), dayjs()]);
  const [granularity, setGranularity] = useState<'hour' | 'day'>('hour');
  const [instanceId, setInstanceId] = useState('');
  const [tenantId, setTenantId] = useState('');
  const [routeId, setRouteId] = useState('');
  const [model, setModel] = useState('');
  const [page, setPage] = useState(0);

  const filters: TokenUsageFilters = useMemo(() => ({
    from: range[0].format('YYYY-MM-DDTHH:mm:ss'),
    to: range[1].format('YYYY-MM-DDTHH:mm:ss'),
    instanceId, tenantId, routeId, model,
  }), [range, instanceId, tenantId, routeId, model]);

  const deps = [filters, granularity, page];

  const instancesReq = useRequest((signal) => listInstances({ signal }), {});
  const tenantsReq = useRequest((signal) => getTokenQuotaTenants({ signal }), {});
  const overviewReq = useRequest((signal) => getTokenUsageOverview(filters, { signal }), { deps: [filters] });
  const seriesReq = useRequest((signal) => getTokenUsageTimeseries(filters, granularity, { signal }), { deps: [filters, granularity] });
  const tenantReq = useRequest((signal) => getTokenUsageBreakdown(filters, 'tenant', 8, { signal }), { deps: [filters] });
  const routeReq = useRequest((signal) => getTokenUsageBreakdown(filters, 'route', 10, { signal }), { deps: [filters] });
  const modelReq = useRequest((signal) => getTokenUsageBreakdown(filters, 'model', 8, { signal }), { deps: [filters] });
  const eventsReq = useRequest((signal) => getTokenUsageEvents(filters, page, 20, { signal }), { deps });

  const overview = overviewReq.data;
  const series = seriesReq.data?.series ?? EMPTY_SERIES;
  const hasData = (overview?.requests ?? 0) > 0;

  const timeseriesOption = useMemo(() => ({
    ...baseOption(),
    legend: { textStyle: { color: AXIS_TEXT }, top: 0 },
    xAxis: { type: 'category', data: series.map(s => s.bucket), axisLine: { lineStyle: { color: SPLIT_LINE } }, axisLabel: { color: AXIS_TEXT } },
    yAxis: { type: 'value', splitLine: { lineStyle: { color: SPLIT_LINE } }, axisLabel: { color: AXIS_TEXT } },
    series: [
      { name: t('tokenUsage.chart.prompt'), type: 'bar', stack: 'tokens', data: series.map(s => s.promptTokens) },
      { name: t('tokenUsage.chart.completion'), type: 'bar', stack: 'tokens', data: series.map(s => s.completionTokens) },
      { name: t('tokenUsage.chart.total'), type: 'line', smooth: true, data: series.map(s => s.totalTokens) },
    ],
  }), [series, t]);

  const pieOption = useMemo(() => ({
    ...baseOption(),
    tooltip: { trigger: 'item', backgroundColor: 'rgba(20,20,20,0.9)', borderColor: SPLIT_LINE, textStyle: { color: '#fff' } },
    series: [{
      type: 'pie', radius: ['42%', '70%'],
      label: { color: AXIS_TEXT, formatter: '{b}: {d}%' },
      data: (tenantReq.data?.rows ?? []).map(r => ({ name: r.key, value: r.totalTokens })),
    }],
  }), [tenantReq.data]);

  const barOption = (rows: { key: string; totalTokens: number }[], horizontal: boolean) => ({
    ...baseOption(),
    xAxis: horizontal
      ? { type: 'value', splitLine: { lineStyle: { color: SPLIT_LINE } }, axisLabel: { color: AXIS_TEXT } }
      : { type: 'category', data: rows.map(r => r.key), axisLine: { lineStyle: { color: SPLIT_LINE } }, axisLabel: { color: AXIS_TEXT, interval: 0, rotate: rows.length > 5 ? 24 : 0 } },
    yAxis: horizontal
      ? { type: 'category', data: rows.map(r => r.key).reverse(), axisLabel: { color: AXIS_TEXT } }
      : { type: 'value', splitLine: { lineStyle: { color: SPLIT_LINE } }, axisLabel: { color: AXIS_TEXT } },
    series: [{
      type: 'bar',
      data: horizontal ? rows.map(r => r.totalTokens).reverse() : rows.map(r => r.totalTokens),
      itemStyle: { borderRadius: horizontal ? [0, 4, 4, 0] : [4, 4, 0, 0] },
    }],
    grid: { left: horizontal ? 120 : 48, right: 24, top: 16, bottom: 28 },
  });

  const events = eventsReq.data?.content ?? [];

  return (
    <div style={{ padding: '16px 24px' }}>
      <Space direction="vertical" size={4} style={{ marginBottom: 16 }}>
        <Title level={4} style={{ margin: 0, color: '#fff' }}>{t('tokenUsage.title')}</Title>
        <Text type="secondary">{t('tokenUsage.subtitle')}</Text>
      </Space>

      <Card size="small" style={{ marginBottom: 16 }}>
        <Space wrap size={12}>
          <RangePicker
            showTime
            value={range}
            onChange={(v) => { if (v && v[0] && v[1]) { setRange([v[0], v[1]]); setPage(0); } }}
            presets={[
              { label: t('tokenUsage.filter.preset.24h'), value: [dayjs().subtract(24, 'hour'), dayjs()] },
              { label: t('tokenUsage.filter.preset.7d'), value: [dayjs().subtract(7, 'day'), dayjs()] },
              { label: t('tokenUsage.filter.preset.30d'), value: [dayjs().subtract(30, 'day'), dayjs()] },
            ]}
          />
          <Select
            style={{ width: 100 }}
            value={granularity}
            onChange={(v) => setGranularity(v)}
            options={[
              { value: 'hour', label: t('tokenUsage.filter.granularity.hour') },
              { value: 'day', label: t('tokenUsage.filter.granularity.day') },
            ]}
          />
          <Select
            style={{ width: 200 }}
            allowClear
            showSearch
            placeholder={t('tokenUsage.filter.instance.all')}
            value={instanceId || undefined}
            onChange={(v) => { setInstanceId(v ?? ''); setPage(0); }}
            options={(instancesReq.data ?? []).map(i => ({ value: i.instanceId, label: `${i.instanceId}` }))}
          />
          <Select
            style={{ width: 180 }}
            allowClear
            showSearch
            placeholder={t('tokenUsage.filter.tenant.all')}
            value={tenantId || undefined}
            onChange={(v) => { setTenantId(v ?? ''); setPage(0); }}
            options={(tenantsReq.data ?? []).map(q => ({ value: q.tenantId, label: q.tenantId }))}
          />
          <Input
            style={{ width: 160 }}
            allowClear
            placeholder={t('tokenUsage.filter.route.ph')}
            value={routeId}
            onChange={(e) => { setRouteId(e.target.value); setPage(0); }}
          />
          <Input
            style={{ width: 160 }}
            allowClear
            placeholder={t('tokenUsage.filter.model.ph')}
            value={model}
            onChange={(e) => { setModel(e.target.value); setPage(0); }}
          />
        </Space>
      </Card>

      {!hasData ? (
        <Card><Empty description={t('tokenUsage.empty')} /></Card>
      ) : (
        <>
          <Row gutter={16} style={{ marginBottom: 16 }}>
            <Col span={6}>
              <Card size="small"><Statistic title={t('tokenUsage.card.requests')} value={overview?.requests ?? 0} /></Card>
            </Col>
            <Col span={6}>
              <Card size="small">
                <Statistic title={t('tokenUsage.card.totalTokens')} value={overview?.totalTokens ?? 0} />
                <Text type="secondary" style={{ fontSize: 12 }}>
                  {t('tokenUsage.card.totalTokens.sub', { prompt: overview?.promptTokens ?? 0, completion: overview?.completionTokens ?? 0 })}
                </Text>
              </Card>
            </Col>
            <Col span={6}>
              <Card size="small">
                <Statistic title={t('tokenUsage.card.avgLatency')} value={overview?.avgLatencyMs ?? 0} suffix={t('tokenUsage.card.avgLatency.unit')} />
              </Card>
            </Col>
            <Col span={6}>
              <Card size="small">
                <Statistic title={t('tokenUsage.card.active')} value={overview?.activeTenants ?? 0} />
                <Text type="secondary" style={{ fontSize: 12 }}>
                  {t('tokenUsage.card.active.sub', { tenants: overview?.activeTenants ?? 0, routes: overview?.activeRoutes ?? 0, models: overview?.activeModels ?? 0 })}
                </Text>
              </Card>
            </Col>
          </Row>

          <Row gutter={16} style={{ marginBottom: 16 }}>
            <Col span={16}>
              <Card size="small" title={t('tokenUsage.chart.timeseries')}>
                <ReactECharts option={timeseriesOption} style={{ height: 260 }} notMerge />
              </Card>
            </Col>
            <Col span={8}>
              <Card size="small" title={t('tokenUsage.chart.tenantPie')}>
                <ReactECharts option={pieOption} style={{ height: 260 }} notMerge />
              </Card>
            </Col>
          </Row>
          <Row gutter={16} style={{ marginBottom: 16 }}>
            <Col span={12}>
              <Card size="small" title={t('tokenUsage.chart.routeBar')}>
                <ReactECharts option={barOption(routeReq.data?.rows ?? [], true)} style={{ height: 240 }} notMerge />
              </Card>
            </Col>
            <Col span={12}>
              <Card size="small" title={t('tokenUsage.chart.modelBar')}>
                <ReactECharts option={barOption(modelReq.data?.rows ?? [], false)} style={{ height: 240 }} notMerge />
              </Card>
            </Col>
          </Row>

          <Card size="small" title={t('tokenUsage.table.title')}>
            <Table
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
              columns={[
                { title: t('tokenUsage.table.time'), dataIndex: 'requestTime', width: 170, render: (v: string) => (v ? v.replace('T', ' ').slice(0, 19) : '-') },
                { title: t('tokenUsage.table.tenant'), dataIndex: 'tenantId', ellipsis: true },
                { title: t('tokenUsage.table.route'), dataIndex: 'routeId', ellipsis: true, render: (v: string | null) => v ?? '-' },
                { title: t('tokenUsage.table.model'), dataIndex: 'model', ellipsis: true, render: (v: string | null) => v ?? '-' },
                { title: t('tokenUsage.table.format'), dataIndex: 'responseFormat', width: 90, render: (v: string | null) => v ?? '-' },
                { title: t('tokenUsage.table.prompt'), dataIndex: 'promptTokens', width: 80, align: 'right' },
                { title: t('tokenUsage.table.completion'), dataIndex: 'completionTokens', width: 80, align: 'right' },
                { title: t('tokenUsage.table.total'), dataIndex: 'totalTokens', width: 80, align: 'right' },
                { title: t('tokenUsage.table.latency'), dataIndex: 'latencyMs', width: 90, align: 'right', render: (v: number | null) => v ?? '-' },
                { title: t('tokenUsage.table.trace'), dataIndex: 'traceId', width: 140, ellipsis: true, render: (v: string | null) => v ?? '-' },
                { title: t('tokenUsage.table.instance'), dataIndex: 'instanceId', width: 140, ellipsis: true, render: (v: string | null) => v ?? '-' },
              ]}
            />
          </Card>
        </>
      )}
    </div>
  );
};

export default TokenUsagePage;
