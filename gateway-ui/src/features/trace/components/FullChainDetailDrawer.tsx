/**
 * Full-chain detail drawer — duration breakdown, gateway node and
 * downstream service nodes with span waterfall charts.
 */
import React from 'react';
import {
  Alert, Card, Col, Collapse, Descriptions, Drawer, Row, Space, Spin,
  Statistic, Tag, Typography
} from 'antd';
import {
  ApiOutlined, CloudServerOutlined, DownOutlined, ApartmentOutlined,
  ThunderboltOutlined,
} from '@ant-design/icons';
import dayjs from 'dayjs';
import { useTranslation } from 'react-i18next';
import FullChainWaterfallChart from './FullChainWaterfallChart';
import type { FullChainDetail } from '../../../types/trace';
import { formatJson } from '../utils';

const { Text } = Typography;

interface FullChainDetailDrawerProps {
  open: boolean;
  chain: FullChainDetail | undefined;
  loading: boolean;
  onClose: () => void;
}

const chainStatusColor = (status: string): string => {
  if (status === 'OBSERVED') return 'processing';
  if (status === 'PARTIAL' || status === 'OBSERVED_WITH_ERRORS' || status === 'COMPLETE') return 'warning';
  if (status === 'COMPLETE_WITH_ERRORS') return 'warning';
  if (status === 'GATEWAY_ONLY') return 'default';
  return 'error';
};

const FullChainDetailDrawer: React.FC<FullChainDetailDrawerProps> = ({
  open, chain, loading, onClose,
}) => {
  const { t } = useTranslation();

  const preStyle: React.CSSProperties = {
    background: '#f5f5f5', padding: 8, borderRadius: 4, fontSize: 11,
    maxHeight: 150, overflow: 'auto', margin: '4px 0 0',
  };

  const chainStatusLabel = (status: string): string => {
    if (status === 'COMPLETE' || status === 'OBSERVED') return t('trace.chain_observed');
    if (status === 'PARTIAL') return t('trace.chain_partial');
    if (status === 'OBSERVED_WITH_ERRORS') return t('trace.chain_observed_errors');
    if (status === 'GATEWAY_ONLY') return t('trace.gateway_only');
    return status;
  };

  return (
    <Drawer rootClassName="console-drawer"
      title={(
        <Space>
          <ApartmentOutlined />
          <Text>{t('trace.full_chain_detail')}</Text>
          {chain && <Tag color={chainStatusColor(chain.chainStatus)}>{chainStatusLabel(chain.chainStatus)}</Tag>}
        </Space>
      )}
      placement="right"
      width={950}
      destroyOnClose
      onClose={onClose}
      open={open}
      styles={{ body: { padding: 16 } }}
    >
      {loading && <div style={{ textAlign: 'center', padding: 40 }}><Spin /></div>}
      {chain && !loading && (
        <div>
          {/* TraceId */}
          <Card size="small" className="mb-4">
            <Row gutter={16} align="middle">
              <Col span={16}>
                <Text strong>Trace ID: </Text>
                <Text copyable className="text-xs-sm">{chain.traceId}</Text>
              </Col>
              <Col span={8} style={{ textAlign: 'right' }}>
                <Tag color={chainStatusColor(chain.chainStatus)}>{chainStatusLabel(chain.chainStatus)}</Tag>
              </Col>
            </Row>
          </Card>

          {/* Duration Breakdown */}
          {chain.durationBreakdown && (
            <Card size="small" title={t('trace.duration_breakdown')} className="mb-4">
              <Row gutter={12}>
                <Col span={5}><Statistic title={t('trace.total_latency')} value={chain.durationBreakdown.gatewayTotalMs ?? t('trace.unknown')} suffix={chain.durationBreakdown.gatewayTotalMs == null ? '' : 'ms'} /></Col>
                <Col span={5}><Statistic title={t('trace.filter_self_time')} value={chain.durationBreakdown.gatewayOverheadMs ?? t('trace.unknown')} suffix={chain.durationBreakdown.gatewayOverheadMs == null ? '' : 'ms'} /></Col>
                <Col span={5}><Statistic title={t('trace.observed_service_wall')} value={chain.durationBreakdown.serviceDurationMs ?? t('trace.unknown')} suffix={chain.durationBreakdown.serviceDurationMs == null ? '' : 'ms'} /></Col>
                <Col span={5}><Statistic title={t('trace.network_transfer')} value={chain.durationBreakdown.networkTransferMs ?? t('trace.unknown')} suffix={chain.durationBreakdown.networkTransferMs == null ? '' : 'ms'} /></Col>
                <Col span={4}><Statistic title={t('trace.other_time')} value={chain.durationBreakdown.otherMs ?? t('trace.unknown')} suffix={chain.durationBreakdown.otherMs == null ? '' : 'ms'} /></Col>
              </Row>
              <Alert className="mt-2" type="info" showIcon title={t('trace.time_model_note')} />
              {chain.integrity && <Alert className="mt-2" type={chain.integrity.observedGraphValid ? 'info' : 'warning'} showIcon
                title={t('trace.integrity_unverified')}
                description={chain.integrity.reasons.map(reason => t('trace.integrity_' + reason)).join(' / ') || t('trace.graph_valid_note')} />}
            </Card>
          )}

          {/* Gateway Node */}
          {chain.hasGatewayTrace && chain.gateway && (
            <Card
              size="small"
              title={(
                <Space>
                  <ApiOutlined style={{ color: '#1890ff' }} />
                  <Text strong>{t('trace.gateway_node')}</Text>
                  <Tag color={chain.gateway.statusCode >= 400 ? 'error' : 'success'}>
                    {chain.gateway.statusCode}
                  </Tag>
                  {chain.gateway.errorType && <Tag color="error">{chain.gateway.errorType}</Tag>}
                  {chain.gateway.traceType && chain.gateway.traceType !== 'ALL' && (
                    <Tag color={chain.gateway.traceType === 'ERROR' ? 'error' : 'warning'}>
                      {chain.gateway.traceType}
                    </Tag>
                  )}
                </Space>
              )}
              className="mb-4"
            >
              <Descriptions size="small" column={2} bordered>
                <Descriptions.Item label={t('trace.method')}>
                  <Tag color="blue">{chain.gateway.method}</Tag>
                </Descriptions.Item>
                <Descriptions.Item label={t('trace.gateway_overhead')}>
                  {chain.durationBreakdown ? (
                    <Text style={{ color: (chain.durationBreakdown.gatewayOverheadMs ?? 0) > 200 ? '#cf1322' : undefined }}>
                      {chain.durationBreakdown.gatewayOverheadMs == null ? t('trace.unknown') : chain.durationBreakdown.gatewayOverheadMs + 'ms'}
                    </Text>
                  ) : (
                    <Text>{chain.gateway.latencyMs}ms</Text>
                  )}
                </Descriptions.Item>
                <Descriptions.Item label={t('trace.path')} span={2}>
                  <Text copyable className="text-xs-sm">{chain.gateway.uri}</Text>
                </Descriptions.Item>
                {chain.gateway.queryString && (
                  <Descriptions.Item label={t('trace.query_string')} span={2}>
                    <Text copyable className="text-xs-sm">{chain.gateway.queryString}</Text>
                  </Descriptions.Item>
                )}
                <Descriptions.Item label={t('trace.route_id')}>
                  <Text className="text-xs-sm">{chain.gateway.routeId || '-'}</Text>
                </Descriptions.Item>
                <Descriptions.Item label={t('trace.target_instance')}>
                  <Text className="text-xs-sm">{chain.gateway.targetInstance || '-'}</Text>
                </Descriptions.Item>
                <Descriptions.Item label={t('trace.client_ip')}>
                  {chain.gateway.clientIp || '-'}
                </Descriptions.Item>
                <Descriptions.Item label={t('trace.time')}>
                  {chain.gateway.traceTime ? dayjs(chain.gateway.traceTime).format('YYYY-MM-DD HH:mm:ss') : '-'}
                </Descriptions.Item>
                {chain.gateway.userAgent && (
                  <Descriptions.Item label="User-Agent" span={2}>
                    <Text className="text-xs-tiny" ellipsis={{ tooltip: chain.gateway.userAgent }}>
                      {chain.gateway.userAgent}
                    </Text>
                  </Descriptions.Item>
                )}
                {chain.gateway.errorMessage && (
                  <Descriptions.Item label={t('trace.error')} span={2}>
                    <Text type="danger">{chain.gateway.errorMessage}</Text>
                  </Descriptions.Item>
                )}
              </Descriptions>

              {/* Request/Response collapsible panels */}
              <Collapse
                size="small"
                style={{ marginTop: 12 }}
                items={[
                  ...(chain.gateway.requestHeaders || chain.gateway.requestBody ? [{
                    key: 'request',
                    label: <Text strong className="text-xs-sm">Request</Text>,
                    children: (
                      <div>
                        {chain.gateway.requestHeaders && (
                          <div style={{ marginBottom: 8 }}>
                            <Text type="secondary" className="text-xs-tiny">Headers</Text>
                            <pre style={preStyle}>{formatJson(chain.gateway.requestHeaders)}</pre>
                          </div>
                        )}
                        {chain.gateway.requestBody && (
                          <div>
                            <Text type="secondary" className="text-xs-tiny">Body</Text>
                            <pre style={{ ...preStyle, maxHeight: 200 }}>{formatJson(chain.gateway.requestBody)}</pre>
                          </div>
                        )}
                      </div>
                    ),
                  }] : []),
                  ...(chain.gateway.responseHeaders || chain.gateway.responseBody ? [{
                    key: 'response',
                    label: <Text strong className="text-xs-sm">Response</Text>,
                    children: (
                      <div>
                        {chain.gateway.responseHeaders && (
                          <div style={{ marginBottom: 8 }}>
                            <Text type="secondary" className="text-xs-tiny">Headers</Text>
                            <pre style={preStyle}>{formatJson(chain.gateway.responseHeaders)}</pre>
                          </div>
                        )}
                        {chain.gateway.responseBody && (
                          <div>
                            <Text type="secondary" className="text-xs-tiny">Body</Text>
                            <pre style={{ ...preStyle, maxHeight: 200 }}>{formatJson(chain.gateway.responseBody)}</pre>
                          </div>
                        )}
                      </div>
                    ),
                  }] : []),
                ]}
              />
            </Card>
          )}

          {/* Arrow */}
          {chain.hasGatewayTrace && chain.hasServiceTrace && (
            <div style={{ textAlign: 'center', margin: '8px 0' }}>
              <DownOutlined style={{ fontSize: 20, color: '#1890ff' }} />
            </div>
          )}

          {/* Service Nodes */}
          {chain.hasServiceTrace && chain.services.map((service, idx) => (
            <Card
              key={idx}
              size="small"
              title={(
                <Space>
                  <CloudServerOutlined style={{ color: '#52c41a' }} />
                  <Text strong>{service.serviceName}</Text>
                  <Tag color={service.success ? 'success' : 'error'}>
                    {service.statusCode || (service.success ? 'OK' : 'ERROR')}
                  </Tag>
                  <Text type="secondary">{service.totalDurationMs}ms</Text>
                  {service.isSlow && <Tag color="warning">{t('trace.slow')}</Tag>}
                </Space>
              )}
              className="mb-4"
            >
              <Descriptions size="small" column={2} bordered style={{ marginBottom: 12 }}>
                <Descriptions.Item label={t('trace.path')}>
                  <Text className="text-xs-sm">{service.path}</Text>
                </Descriptions.Item>
                <Descriptions.Item label={t('trace.method')}>
                  <Tag>{service.method}</Tag>
                </Descriptions.Item>
                <Descriptions.Item label={t('trace.duration')}>
                  {service.totalDurationMs}ms
                </Descriptions.Item>
                <Descriptions.Item label={t('trace.spans')}>
                  {service.spanCount || 0}
                </Descriptions.Item>
                {service.errorMessage && (
                  <Descriptions.Item label={t('trace.error')} span={2}>
                    <Text type="danger">{service.errorMessage}</Text>
                  </Descriptions.Item>
                )}
              </Descriptions>

              {/* Spans Detail - Enhanced Waterfall Chart */}
              {service.spans && service.spans.length > 0 && (
                <Collapse
                  size="small"
                  style={{ marginTop: 12 }}
                  defaultActiveKey={['spans']}
                  items={[
                    {
                      key: 'spans',
                      label: (
                        <Space>
                          <ThunderboltOutlined />
                          <Text strong className="text-xs-sm">
                            {t('trace.span_details')} ({service.spans.length})
                          </Text>
                          {service.spans.some((s) => !s.success) && (
                            <Tag color="error" style={{ fontSize: 10 }}>有错误</Tag>
                          )}
                        </Space>
                      ),
                      children: (
                        <FullChainWaterfallChart
                          serviceSpans={service.spans}
                          traceStartTimeMs={service.startTimeMs}
                          serviceName={service.serviceName}
                          gatewayTotalDurationMs={service.totalDurationMs}
                          slowThreshold={50}
                          showHierarchy
                        />
                      ),
                    },
                  ]}
                />
              )}
            </Card>
          ))}

          {/* No service trace hint */}
          {!chain.hasServiceTrace && chain.hasGatewayTrace && (
            <Alert
              type="info"
              showIcon
              message={t('trace.no_service_trace')}
              description={t('trace.no_service_trace_desc')}
              style={{ marginTop: 16 }}
            />
          )}
        </div>
      )}
    </Drawer>
  );
};

export default FullChainDetailDrawer;
