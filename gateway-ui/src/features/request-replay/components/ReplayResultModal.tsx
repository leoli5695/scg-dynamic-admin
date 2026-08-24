/**
 * Replay result modal — summary statistics, original vs replayed comparison,
 * body diff table and the formatted response body.
 */
import React from 'react';
import {
  Button, Card, Col, Divider, Modal, Row, Space, Statistic, Table, Tag, Typography
} from 'antd';
import type { ColumnsType } from 'antd/es/table';
import {
  CheckCircleOutlined, CloseCircleOutlined, SwapOutlined,
} from '@ant-design/icons';
import { useTranslation } from 'react-i18next';
import type { ReplayBodyDiff, ReplayResult } from '../../../types/replay';

const { Text } = Typography;

/** Try to parse and prettify a JSON body; return as-is otherwise. */
const formatResponseBody = (body: string | undefined): string => {
  if (!body) return '';
  try {
    const parsed = JSON.parse(body);
    return JSON.stringify(parsed, null, 2);
  } catch {
    return body;
  }
};

interface ReplayResultModalProps {
  open: boolean;
  result: ReplayResult | null;
  onClose: () => void;
}

const ReplayResultModal: React.FC<ReplayResultModalProps> = ({ open, result, onClose }) => {
  const { t } = useTranslation();

  const bodyDiffColumns: ColumnsType<ReplayBodyDiff> = [
    { title: t('replay.field'), dataIndex: 'field', key: 'field' },
    {
      title: t('replay.original'),
      dataIndex: 'originalValue',
      key: 'originalValue',
      render: (v: string) => <Text delete type="danger">{v}</Text>,
    },
    {
      title: t('replay.replayed'),
      dataIndex: 'replayedValue',
      key: 'replayedValue',
      render: (v: string) => <Text type="success">{v}</Text>,
    },
    {
      title: t('replay.type'),
      dataIndex: 'type',
      key: 'type',
      render: (type: string) => (
        <Tag color={type === 'ADDED' ? 'green' : type === 'REMOVED' ? 'red' : 'blue'}>
          {type}
        </Tag>
      ),
    },
  ];

  return (
    <Modal
      title={(
        <Space>
          <SwapOutlined />
          {t('replay.result')}
        </Space>
      )}
      open={open}
      onCancel={onClose}
      width={900}
      footer={[
        <Button key="close" onClick={onClose}>
          {t('common.close')}
        </Button>,
      ]}
    >
      {result && (
        <div>
          {/* Summary */}
          <Row gutter={16} style={{ marginBottom: 16 }}>
            <Col span={6}>
              <Statistic
                title={t('replay.status_code')}
                value={result.statusCode}
                valueStyle={{
                  color: result.statusCode >= 400 ? '#ff4d4f' : '#52c41a',
                }}
                prefix={result.success ? <CheckCircleOutlined /> : <CloseCircleOutlined />}
              />
            </Col>
            <Col span={6}>
              <Statistic
                title={t('replay.latency')}
                value={result.latencyMs}
                suffix="ms"
              />
            </Col>
            <Col span={6}>
              {result.comparison && (
                <Statistic
                  title={t('replay.latency_diff')}
                  value={result.comparison.latencyDiffMs || 0}
                  suffix="ms"
                  valueStyle={{
                    color: (result.comparison.latencyDiffMs || 0) > 100 ? '#ff4d4f' : '#52c41a',
                  }}
                />
              )}
            </Col>
            <Col span={6}>
              {result.comparison && (
                <Statistic
                  title={t('replay.status_match')}
                  value={result.comparison.statusMatch ? 'Yes' : 'No'}
                  valueStyle={{
                    color: result.comparison.statusMatch ? '#52c41a' : '#ff4d4f',
                  }}
                />
              )}
            </Col>
          </Row>

          {/* Comparison */}
          {result.comparison && (
            <>
              <Divider>{t('replay.comparison')}</Divider>
              <Row gutter={24}>
                <Col span={12}>
                  <Card size="small" title={t('replay.original_response')} style={{ marginBottom: 16 }}>
                    <Statistic
                      title={t('replay.status')}
                      value={result.comparison.originalStatus}
                      valueStyle={{ fontSize: 16 }}
                    />
                    <Statistic
                      title={t('replay.latency')}
                      value={result.comparison.originalLatencyMs}
                      suffix="ms"
                      valueStyle={{ fontSize: 16 }}
                    />
                  </Card>
                </Col>
                <Col span={12}>
                  <Card size="small" title={t('replay.replayed_response')} style={{ marginBottom: 16 }}>
                    <Statistic
                      title={t('replay.status')}
                      value={result.comparison.replayedStatus}
                      valueStyle={{ fontSize: 16 }}
                    />
                    <Statistic
                      title={t('replay.latency')}
                      value={result.comparison.replayedLatencyMs}
                      suffix="ms"
                      valueStyle={{ fontSize: 16 }}
                    />
                  </Card>
                </Col>
              </Row>

              {/* Body diff */}
              {result.comparison.bodyDiff && result.comparison.bodyDiff.length > 0 && (
                <Card size="small" title={t('replay.body_differences')} style={{ marginTop: 16 }}>
                  <Table
                    dataSource={result.comparison.bodyDiff}
                    columns={bodyDiffColumns}
                    rowKey="field"
                    pagination={false}
                    size="small"
                  />
                </Card>
              )}
            </>
          )}

          {/* Response body */}
          <Divider>{t('replay.response_body')}</Divider>

          {/* Error type badge */}
          {result.errorType && (
            <div style={{ marginBottom: 8 }}>
              <Space>
                <Tag color={
                  result.errorType === 'CLIENT_ERROR' ? 'orange'
                    : result.errorType === 'SERVER_ERROR' ? 'red'
                      : result.errorType === 'CONNECTION_ERROR' ? 'volcano' : 'default'
                }
                >
                  {result.errorType}
                </Tag>
                {result.statusCode >= 400 && (
                  <Text type="secondary">
                    {result.errorType === 'CLIENT_ERROR'
                      ? t('replay.client_error_hint')
                      : result.errorType === 'SERVER_ERROR'
                        ? t('replay.server_error_hint')
                        : ''}
                  </Text>
                )}
              </Space>
            </div>
          )}

          <div style={{
            background: result.statusCode >= 400 ? 'rgba(255,77,79,0.1)' : 'rgba(0,0,0,0.3)',
            padding: 12,
            borderRadius: 4,
            maxHeight: 400,
            overflow: 'auto',
          }}
          >
            <pre style={{
              margin: 0, whiteSpace: 'pre-wrap', wordBreak: 'break-word',
              fontFamily: 'monospace', fontSize: 13,
            }}
            >
              {formatResponseBody(result.responseBody) || t('replay.empty_body')}
            </pre>
          </div>
        </div>
      )}
    </Modal>
  );
};

export default ReplayResultModal;
