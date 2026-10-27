/**
 * Trace detail drawer — summary, request/response bodies and the filter
 * chain execution section (table + waterfall views).
 */
import React, { useMemo, useState } from 'react';
import {
  Alert, Badge, Button, Card, Col, Collapse, Drawer, Progress,
  Row, Segmented, Space, Statistic, Table, Tabs, Tag, Tooltip, Typography } from 'antd';
import { message } from '../../../utils/antdStatic';
import type { ColumnsType } from 'antd/es/table';
import {
  CheckCircleOutlined, CompressOutlined, CopyOutlined, ExpandOutlined,
  FilterOutlined, ShareAltOutlined, ThunderboltOutlined, UnorderedListOutlined,
  WarningOutlined,
} from '@ant-design/icons';
import dayjs from 'dayjs';
import { useTranslation } from 'react-i18next';
import FilterWaterfallChart from './FilterWaterfallChart';
import FilterExecutionDetailDrawer from './FilterExecutionDetailDrawer';
import { useRequest } from '../../../hooks/useRequest';
import { getTraceById } from '../../../api/traces';
import type { FilterChainExecution, FilterExecutionBar, RequestTrace } from '../../../types/trace';
import {
  formatJson, getFilterPercentageColor, getHeadersCount, getMethodColor,
  getStatusColor, isBodyLarge, parsePercentage, toFilterExecutionBars,
} from '../utils';

const { Title, Text, Link } = Typography;

/** Parse a JSON header string; fall back to the raw value when invalid. */
const parseHeaders = (raw?: string | null): unknown => {
  if (!raw) return {};
  try {
    return JSON.parse(raw);
  } catch {
    return raw;
  }
};

interface TraceDetailDrawerProps {
  open: boolean;
  trace: RequestTrace | null;
  onClose: () => void;
  onJumpToDistributed: (traceId: string) => void;
  onNavigateToRoute?: (routeId: string) => void;
}

const TraceDetailDrawer: React.FC<TraceDetailDrawerProps> = ({
  open, trace, onClose, onJumpToDistributed, onNavigateToRoute,
}) => {
  const { t } = useTranslation();
  const [viewMode, setViewMode] = useState<'formatted' | 'raw'>('formatted');
  const [requestBodyExpanded, setRequestBodyExpanded] = useState(false);
  const [responseBodyExpanded, setResponseBodyExpanded] = useState(false);
  const [selectedFilterExecution, setSelectedFilterExecution] = useState<FilterExecutionBar | null>(null);
  const [filterDetailVisible, setFilterDetailVisible] = useState(false);

  // GET /api/traces/{id} adds request/response bodies and filterChain data.
  const detail = useRequest<RequestTrace>(
    () => getTraceById(trace?.id ?? -1),
    { deps: [trace?.id], immediate: open && !!trace },
  );

  const selectedTrace: RequestTrace | null = trace
    ? { ...trace, filterChain: detail.data?.filterChain }
    : null;

  const copyRequest = () => {
    if (!selectedTrace) return;
    navigator.clipboard.writeText(JSON.stringify({
      method: selectedTrace.method,
      uri: selectedTrace.uri,
      path: selectedTrace.path,
      queryString: selectedTrace.queryString,
      headers: parseHeaders(selectedTrace.requestHeaders),
      body: selectedTrace.requestBody,
    }, null, 2));
    message.success('Request copied to clipboard');
  };

  const copyResponse = () => {
    if (!selectedTrace) return;
    navigator.clipboard.writeText(JSON.stringify({
      statusCode: selectedTrace.statusCode,
      headers: parseHeaders(selectedTrace.responseHeaders),
      body: selectedTrace.responseBody,
      latencyMs: selectedTrace.latencyMs,
      errorMessage: selectedTrace.errorMessage,
    }, null, 2));
    message.success('Response copied to clipboard');
  };

  const filterColumns = useMemo<ColumnsType<FilterChainExecution>>(() => [
    {
      title: t('trace.filter_order'),
      dataIndex: 'filterOrder',
      key: 'filterOrder',
      width: 60,
      render: (order: number) => <Badge count={order} style={{ backgroundColor: '#1890ff' }} />,
    },
    {
      title: t('trace.filter_name'),
      dataIndex: 'filterName',
      key: 'filterName',
      width: 200,
      ellipsis: true,
    },
    {
      title: t('trace.filter_duration'),
      dataIndex: 'durationMs',
      key: 'durationMs',
      width: 100,
      render: (ms: number) => <Text type={ms > 100 ? 'danger' : undefined}>{ms}ms</Text>,
    },
    {
      title: t('trace.filter_percentage'),
      dataIndex: 'timePercentage',
      key: 'timePercentage',
      width: 150,
      render: (percentage: string | number) => (
        <Progress
          percent={parsePercentage(percentage)}
          size="small"
          strokeColor={getFilterPercentageColor(parsePercentage(percentage))}
          format={(p) => `${p?.toFixed(1)}%`}
        />
      ),
    },
    {
      title: t('trace.filter_status'),
      dataIndex: 'success',
      key: 'success',
      width: 80,
      render: (success: boolean) => (success
        ? <CheckCircleOutlined style={{ color: '#52c41a' }} />
        : <WarningOutlined style={{ color: '#faad14' }} />),
    },
    {
      title: t('trace.filter_error'),
      dataIndex: 'errorMessage',
      key: 'errorMessage',
      ellipsis: true,
      render: (error: string) => (error ? <Text type="danger">{error}</Text> : '-'),
    },
  ], [t]);

  const executions = selectedTrace?.filterChain?.executions ?? [];
  const waterfallBars = toFilterExecutionBars(executions);

  const preStyle: React.CSSProperties = {
    background: '#f5f5f5',
    padding: 8,
    borderRadius: 4,
    fontSize: 11,
    maxHeight: 200,
    overflow: 'auto',
  };

  return (
    <Drawer rootClassName="console-drawer"
      title={(
        <Space>
          <Text>{t('trace.detail_title')}</Text>
          {selectedTrace && (
            <Tag color={getStatusColor(selectedTrace.statusCode)}>
              {selectedTrace.statusCode}
            </Tag>
          )}
        </Space>
      )}
      placement="right"
      styles={{ wrapper: { width: 900 }, body: { padding: 16 } }}
      destroyOnClose
      maskClosable
      onClose={onClose}
      open={open}
      extra={(
        <Segmented
          options={[
            { label: t('trace.formatted'), value: 'formatted' },
            { label: t('trace.raw'), value: 'raw' },
          ]}
          value={viewMode}
          onChange={(v) => setViewMode(v as 'formatted' | 'raw')}
          size="small"
        />
      )}
    >
      {selectedTrace && (
        <div>
          {detail.loading && <div style={{ textAlign: 'center', padding: 20 }}>Loading...</div>}

          {/* Summary Bar */}
          <Card size="small" className="mb-4">
            <Row gutter={16}>
              <Col span={4}>
                <Statistic title={t('trace.method')} />
                <Tag color={getMethodColor(selectedTrace.method)}>{selectedTrace.method}</Tag>
              </Col>
              <Col span={4}>
                <Statistic title={t('trace.status')} />
                <Badge status={getStatusColor(selectedTrace.statusCode)} text={selectedTrace.statusCode} />
              </Col>
              <Col span={4}>
                <Statistic title={t('trace.latency')} value={selectedTrace.latencyMs} suffix="ms" />
              </Col>
              <Col span={4}>
                <Statistic title={t('trace.client_ip')} valueStyle={{ fontSize: 12 }} value={selectedTrace.clientIp || '-'} />
              </Col>
              <Col span={8}>
                <Statistic title={t('trace.trace_id')} />
                <Space>
                  <Text copyable className="text-xs-sm">{selectedTrace.traceId}</Text>
                  <Tooltip title={t('trace.view_distributed')}>
                    <Button
                      size="small"
                      type="link"
                      icon={<ShareAltOutlined />}
                      onClick={() => {
                        onClose();
                        onJumpToDistributed(selectedTrace.traceId);
                      }}
                    />
                  </Tooltip>
                </Space>
              </Col>
            </Row>
          </Card>

          {/* Path & Query Info */}
          <Card size="small" className="mb-4">
            <Row gutter={16}>
              <Col span={12}>
                <Text strong>{t('trace.path')}: </Text>
                <Tooltip title={selectedTrace.uri}>
                  <Text copyable>{selectedTrace.path || '-'}</Text>
                </Tooltip>
              </Col>
              <Col span={6}>
                <Text strong>{t('trace.route_id')}: </Text>
                {onNavigateToRoute && selectedTrace.routeId ? (
                  <Link onClick={() => onNavigateToRoute(selectedTrace.routeId!)}>
                    {selectedTrace.routeId.substring(0, 8)}...
                  </Link>
                ) : (
                  <Text copyable>{selectedTrace.routeId ? `${selectedTrace.routeId.substring(0, 12)}...` : '-'}</Text>
                )}
              </Col>
              <Col span={6}>
                <Text strong>{t('trace.query_string')}: </Text>
                <Text copyable>{selectedTrace.queryString || '-'}</Text>
              </Col>
            </Row>
          </Card>

          {/* Error Info (if error) */}
          {selectedTrace.statusCode >= 400 && (
            <Alert
              type={selectedTrace.statusCode >= 500 ? 'error' : 'warning'}
              className="mb-4"
              message={(
                <Space>
                  {selectedTrace.errorType && <Tag color="error">{selectedTrace.errorType}</Tag>}
                  <Text>{selectedTrace.errorMessage || t('trace.error_unknown')}</Text>
                </Space>
              )}
            />
          )}

          {/* Request & Response - Two Column Layout */}
          <Row gutter={16}>
            <Col span={12}>
              <Card
                size="small"
                title={<Text strong>Request</Text>}
                extra={<Button size="small" icon={<CopyOutlined />} onClick={copyRequest}>Copy</Button>}
                className="mb-4"
              >
                <Collapse
                  bordered
                  size="small"
                  defaultActiveKey={['headers', 'body']}
                  items={[
                    {
                      key: 'headers',
                      label: `Headers (${getHeadersCount(selectedTrace.requestHeaders)})`,
                      children: (
                        <pre style={{
                          ...preStyle,
                          background: viewMode === 'formatted' ? '#f5f5f5' : 'transparent',
                          whiteSpace: viewMode === 'raw' ? 'pre-wrap' : 'pre',
                        }}
                        >
                          {viewMode === 'formatted'
                            ? formatJson(selectedTrace.requestHeaders)
                            : selectedTrace.requestHeaders || '-'}
                        </pre>
                      ),
                    },
                    {
                      key: 'body',
                      label: `Body (${selectedTrace.requestBody?.length || 0} bytes)`,
                      extra: isBodyLarge(selectedTrace.requestBody) && (
                        <Button
                          size="small"
                          type="text"
                          icon={requestBodyExpanded ? <CompressOutlined /> : <ExpandOutlined />}
                          onClick={(e) => { e.stopPropagation(); setRequestBodyExpanded(!requestBodyExpanded); }}
                        />
                      ),
                      children: (
                        <pre style={{ ...preStyle, maxHeight: requestBodyExpanded ? 500 : 150 }}>
                          {formatJson(selectedTrace.requestBody)}
                        </pre>
                      ),
                    },
                  ]}
                />
              </Card>
            </Col>
            <Col span={12}>
              <Card
                size="small"
                title={<Text strong>Response</Text>}
                extra={<Button size="small" icon={<CopyOutlined />} onClick={copyResponse}>Copy</Button>}
                className="mb-4"
              >
                <Collapse
                  bordered
                  size="small"
                  defaultActiveKey={['headers', 'body']}
                  items={[
                    {
                      key: 'headers',
                      label: `Headers (${getHeadersCount(selectedTrace.responseHeaders)})`,
                      children: (
                        <pre style={preStyle}>
                          {formatJson(selectedTrace.responseHeaders)}
                        </pre>
                      ),
                    },
                    {
                      key: 'body',
                      label: `Body (${selectedTrace.responseBody?.length || 0} bytes)`,
                      extra: isBodyLarge(selectedTrace.responseBody) && (
                        <Button
                          size="small"
                          type="text"
                          icon={responseBodyExpanded ? <CompressOutlined /> : <ExpandOutlined />}
                          onClick={(e) => { e.stopPropagation(); setResponseBodyExpanded(!responseBodyExpanded); }}
                        />
                      ),
                      children: (
                        <pre style={{ ...preStyle, maxHeight: responseBodyExpanded ? 500 : 150 }}>
                          {formatJson(selectedTrace.responseBody)}
                        </pre>
                      ),
                    },
                  ]}
                />
              </Card>
            </Col>
          </Row>

          {/* Filter Chain Execution Section */}
          {selectedTrace.filterChain?.hasFilterData && (
            <Card size="small" style={{ marginTop: 16 }}>
              <Title level={5} style={{ marginBottom: 12 }}>
                <FilterOutlined style={{ marginRight: 8 }} />
                {t('trace.filter_chain_execution')}
              </Title>
              <Row gutter={16} style={{ marginBottom: 12 }}>
                <Col span={4}>
                  <Statistic title={t('trace.filter_count')} value={selectedTrace.filterChain.filterCount} />
                </Col>
                <Col span={4}>
                  <Statistic
                    title={t('trace.filter_total_duration')}
                    value={selectedTrace.filterChain.totalFilterDurationMs}
                    suffix="ms"
                  />
                </Col>
                <Col span={4}>
                  <Statistic
                    title={t('trace.filter_success')}
                    value={selectedTrace.filterChain.successCount}
                    valueStyle={{ color: '#52c41a' }}
                  />
                </Col>
                <Col span={4}>
                  <Statistic
                    title={t('trace.filter_failure')}
                    value={selectedTrace.filterChain.failureCount}
                    valueStyle={{ color: (selectedTrace.filterChain.failureCount ?? 0) > 0 ? '#cf1322' : '#52c41a' }}
                  />
                </Col>
                <Col span={8}>
                  <Statistic
                    title={t('trace.filter_slowest')}
                    value={selectedTrace.filterChain.slowestFilter || '-'}
                    valueStyle={{ fontSize: 14 }}
                  />
                </Col>
              </Row>

              {(selectedTrace.filterChain.failureCount ?? 0) > 0 && (
                <Alert type="warning" message={t('trace.filter_warning')} style={{ marginBottom: 12 }} />
              )}

              <Tabs
                defaultActiveKey="table"
                items={[
                  {
                    key: 'table',
                    label: <span><UnorderedListOutlined /> {t('trace.table_view')}</span>,
                    children: (
                      <Table
                        size="small"
                        dataSource={executions}
                        columns={filterColumns}
                        rowKey={(record, index) => `${record.filterName}_${record.filterOrder}_${index}`}
                        pagination={false}
                        scroll={{ x: 500 }}
                      />
                    ),
                  },
                  {
                    key: 'waterfall',
                    label: <span><ThunderboltOutlined /> {t('trace.waterfall_view')}</span>,
                    children: (
                      <FilterWaterfallChart
                        executions={waterfallBars}
                        totalDurationMs={selectedTrace.filterChain?.totalFilterDurationMs ?? 0}
                        onFilterClick={(bar) => {
                          setSelectedFilterExecution(bar);
                          setFilterDetailVisible(true);
                        }}
                        slowThreshold={50}
                      />
                    ),
                  },
                ]}
              />
            </Card>
          )}

          <FilterExecutionDetailDrawer
            visible={filterDetailVisible}
            execution={selectedFilterExecution}
            onClose={() => setFilterDetailVisible(false)}
            instanceId={selectedTrace.instanceId}
          />

          {/* Meta Info */}
          <Card size="small" style={{ marginTop: 16 }}>
            <Row gutter={16}>
              <Col span={8}>
                <Text type="secondary">{t('trace.target_instance')}: </Text>
                <Text>{selectedTrace.targetInstance || '-'}</Text>
              </Col>
              <Col span={8}>
                <Text type="secondary">{t('trace.user_agent')}: </Text>
                <Text ellipsis style={{ maxWidth: 200 }}>{selectedTrace.userAgent || '-'}</Text>
              </Col>
              <Col span={8}>
                <Text type="secondary">{t('trace.time')}: </Text>
                <Text>{dayjs(selectedTrace.traceTime).format('YYYY-MM-DD HH:mm:ss')}</Text>
              </Col>
            </Row>
          </Card>
        </div>
      )}
    </Drawer>
  );
};

export default TraceDetailDrawer;
