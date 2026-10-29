import { useState } from 'react';
import { Alert, Button, Card, DatePicker, Form, Input, InputNumber, Space, Table, Tabs, Tag, Typography } from 'antd';
import { ReloadOutlined, SearchOutlined } from '@ant-design/icons';
import dayjs from 'dayjs';
import { useNavigate, useSearchParams } from 'react-router-dom';
import { useTranslation } from 'react-i18next';
import { searchTraces } from '../../api/traces';
import { getHistoryEntries } from '../../api/accessLog';
import { useRequest } from '../../hooks/useRequest';
import { useInstanceDetail } from '../instance-detail/InstanceDetailContext';
import { requestJourneyUrl, routeConfigurationUrl } from './links';
import { readRequestQuery, requestLogRange, requestLogLocalTime } from './requestQuery';

interface RequestRow {
  key: string;
  time: string;
  traceId?: string;
  routeId?: string;
  method: string;
  path?: string;
  statusCode: number;
  durationMs: number;
}

function RequestQuery({ instanceId }: { instanceId: string }) {
  const { t } = useTranslation();
  const navigate = useNavigate();
  const [params, setParams] = useSearchParams();
  const [query] = useState(() => readRequestQuery(params));
  const [form] = Form.useForm();
  const basePath = `/instances/${encodeURIComponent(instanceId)}`;
  const criteria = {
    instanceId, routeId: query.routeId, traceId: query.traceId,
    statusCode: query.statusCode, page: query.page, size: 20,
  };
  const request = useRequest(async signal => {
    if (query.source === 'logs') {
      const result = await getHistoryEntries({ ...criteria, ...requestLogRange(query.from, query.to) }, { signal });
      return {
        total: result.total,
        rows: result.entries.map((row, index): RequestRow => ({
          key: `${query.page}-${index}`, time: requestLogLocalTime(row['@timestamp']), traceId: row.traceId, routeId: row.routeId,
          method: row.method, path: row.path, statusCode: row.statusCode, durationMs: row.durationMs,
        })),
      };
    }
    const result = await searchTraces({ ...criteria, start: query.from, end: query.to }, { signal });
    return {
      total: result.totalElements,
      rows: result.content.map((row): RequestRow => ({
        key: String(row.id), time: row.traceTime, traceId: row.traceId, routeId: row.routeId,
        method: row.method, path: row.path, statusCode: row.statusCode, durationMs: row.latencyMs,
      })),
    };
  }, { immediate: !query.error });

  const updateQuery = (values: Record<string, string | number | undefined>) => {
    const next = new URLSearchParams(params);
    next.set('from', query.from);
    next.set('to', query.to);
    Object.entries(values).forEach(([key, value]) => {
      if (value === undefined || value === '') next.delete(key);
      else next.set(key, String(value));
    });
    setParams(next);
  };
  const openRequest = (row: RequestRow) => {
    if (!row.traceId) return;
    const target = requestJourneyUrl(instanceId, row.traceId, row.routeId);
    const context = new URLSearchParams(params);
    context.set('from', query.from);
    context.set('to', query.to);
    const returnTo = `${basePath}/requests?${context}`;
    navigate(`${target}${target.includes('?') ? '&' : '?'}${new URLSearchParams({ returnTo })}`);
  };

  return <div className="console-workspace-page" style={{ display: 'grid', gap: 16 }}>
    <Space wrap style={{ justifyContent: 'space-between' }}>
      <Typography.Title level={3} style={{ margin: 0 }}>{t('menu.requests')}</Typography.Title>
      <Button icon={<ReloadOutlined />} loading={request.loading} disabled={!!query.error} onClick={request.run}>{t('common.refresh')}</Button>
    </Space>
    <Typography.Text type="secondary">{t('requestSearch.subtitle')}</Typography.Text>
    <Card>
      <Form form={form} layout="vertical" initialValues={{
        range: [dayjs(query.from), dayjs(query.to)], routeId: query.routeId,
        traceId: query.traceId, statusCode: query.statusCode,
      }} onFinish={values => updateQuery({
        from: values.range[0].format('YYYY-MM-DDTHH:mm:ss'), to: values.range[1].format('YYYY-MM-DDTHH:mm:ss'),
        routeId: values.routeId?.trim(), traceId: values.traceId?.trim(), statusCode: values.statusCode ?? undefined, page: 0,
      })}>
        <Space wrap align="start" size={[16, 0]}>
          <Form.Item name="range" label={t('requestSearch.range')} rules={[{ required: true }]}>
            <DatePicker.RangePicker showTime allowClear={false} style={{ maxWidth: '100%' }} />
          </Form.Item>
          <Form.Item name="routeId" label={t('tokenUsage.table.route')}>
            <Input allowClear placeholder={t('requestSearch.exactRoute')} />
          </Form.Item>
          <Form.Item name="traceId" label="Trace ID">
            <Input allowClear placeholder={t('requestSearch.exactTrace')} />
          </Form.Item>
          <Form.Item name="statusCode" label={t('trace.status')}>
            <InputNumber min={100} max={599} precision={0} placeholder="200 / 401 / 500" style={{ width: 155 }} />
          </Form.Item>
        </Space>
        <Space>
          <Button type="primary" htmlType="submit" icon={<SearchOutlined />}>{t('common.search')}</Button>
          <Button onClick={() => setParams({ source: query.source })}>{t('common.reset')}</Button>
        </Space>
      </Form>
    </Card>
    <Card>
      <Tabs activeKey={query.source} onChange={source => updateQuery({ source, page: 0 })} items={[
        { key: 'traces', label: t('requestSearch.traces') },
        { key: 'logs', label: t('requestSearch.logs') },
      ]} />
      <Alert type="info" showIcon message={t(`requestSearch.${query.source}Hint`)} style={{ marginBottom: 16 }} />
      {query.error ? <Alert type="error" showIcon message={t(`requestSearch.invalid.${query.error}`)} />
        : request.error ? <Alert type="error" showIcon message={t('requestSearch.loadFailed')} description={request.error.message}
          action={<Button onClick={request.run}>{t('common.refresh')}</Button>} />
        : <Table<RequestRow> rowKey="key" loading={request.loading} dataSource={request.data?.rows ?? []} scroll={{ x: 1000 }}
          locale={{ emptyText: t('requestSearch.empty') }} pagination={{
            current: query.page + 1, pageSize: 20, total: request.data?.total ?? 0, showSizeChanger: false,
            onChange: page => updateQuery({ page: page - 1 }),
          }} columns={[
            { title: t('tokenUsage.table.time'), dataIndex: 'time', width: 180, render: value => value?.replace('T', ' ').slice(0, 19) || '—' },
            { title: t('tokenUsage.table.route'), dataIndex: 'routeId', ellipsis: true, render: value => value
              ? <Button type="link" size="small" onClick={() => navigate(routeConfigurationUrl(instanceId, value))}>{value}</Button> : '—' },
            { title: t('trace.method'), dataIndex: 'method', width: 90 },
            { title: t('trace.path'), dataIndex: 'path', ellipsis: true },
            { title: 'HTTP', dataIndex: 'statusCode', width: 80, render: value => <Tag color={value >= 400 ? 'red' : 'green'}>{value}</Tag> },
            { title: t('trace.latency'), dataIndex: 'durationMs', width: 100, render: value => `${value} ms` },
            { title: 'Trace ID', dataIndex: 'traceId', ellipsis: true, render: value => value || '—' },
            { title: t('common.actions'), key: 'actions', width: 110, fixed: 'right', render: (_, row) =>
              <Button type="link" disabled={!row.traceId} onClick={() => openRequest(row)}>{t('journey.inspect')}</Button> },
          ]} />}
      <Space wrap style={{ marginTop: 16 }}>
        <Button onClick={() => navigate(`${basePath}/access_log`)}>{t('requestSearch.collection')}</Button>
        <Button onClick={() => navigate(`${basePath}/trace`)}>{t('requestSearch.advanced')}</Button>
      </Space>
    </Card>
  </div>;
}

export default function RequestQueryPage() {
  const { instance } = useInstanceDetail();
  const [params] = useSearchParams();
  return <RequestQuery key={`${instance.instanceId}?${params}`} instanceId={instance.instanceId} />;
}
