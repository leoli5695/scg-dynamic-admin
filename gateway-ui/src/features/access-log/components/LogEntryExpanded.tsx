/**
 * Expanded row content for one access log entry: basic info, IDs, auth /
 * routing info and the request/response bodies with a formatted/raw toggle.
 */
import React from 'react';
import { Alert, Badge, Descriptions, Radio, Tag, Tooltip, Typography } from 'antd';
import { useTranslation } from 'react-i18next';
import dayjs from 'dayjs';
import utc from 'dayjs/plugin/utc';
import type { AccessLogEntry } from '../../../types/accessLog';
import { getDurationColor, getMethodColor, getStatusBadge, renderFormattedJson, renderRawJson } from '../utils';

dayjs.extend(utc);

const { Text } = Typography;

interface LogEntryExpandedProps {
  record: AccessLogEntry;
  bodyViewMode: 'raw' | 'formatted';
  onBodyViewModeChange: (mode: 'raw' | 'formatted') => void;
}

const formatTime = (ts: string) => (
  ts ? dayjs(ts).local().format('YYYY-MM-DD HH:mm:ss.SSS') : '-'
);

const BodyPanel: React.FC<{
  title: string;
  body: string | undefined;
  viewMode: 'raw' | 'formatted';
  headerColor?: string;
  notRecordedLabel: string;
}> = ({ title, body, viewMode, headerColor, notRecordedLabel }) => (
  <div className="json-container">
    <div className="json-header" style={headerColor ? { color: headerColor } : undefined}>
      <span>{title}</span>
      {body && <span className="json-size">{body.length} bytes</span>}
    </div>
    <div className="json-content">
      {body ? (
        viewMode === 'formatted' ? (
          <pre className="json-viewer" dangerouslySetInnerHTML={{ __html: renderFormattedJson(body) || '' }} />
        ) : (
          <div style={{ padding: 16, color: 'var(--text-primary)', fontSize: 13, fontFamily: 'Consolas, Monaco, monospace', whiteSpace: 'pre-wrap', wordBreak: 'break-word' }}>
            {renderRawJson(body)}
          </div>
        )
      ) : (
        <div style={{ padding: 24, textAlign: 'center', color: 'var(--text-secondary)' }}>{notRecordedLabel}</div>
      )}
    </div>
  </div>
);

const LogEntryExpanded: React.FC<LogEntryExpandedProps> = ({
  record, bodyViewMode, onBodyViewModeChange,
}) => {
  const { t } = useTranslation();

  const reqSize = record.requestHeaders?.['content-length']
    || record.requestHeaders?.['Content-Length']
    || (record.requestBody ? `${record.requestBody.length}` : '-');
  const resSize = record.responseHeaders?.['content-length']
    || record.responseHeaders?.['Content-Length']
    || (record.responseBody ? `${record.responseBody.length}` : '-');

  return (
    <div style={{ padding: 16, background: 'var(--bg-secondary)', borderRadius: 4 }}>
      {/* Basic info */}
      <Descriptions
        size="small"
        column={4}
        labelStyle={{ width: 80, color: 'var(--text-secondary)' }}
        style={{ marginBottom: 8 }}
      >
        <Descriptions.Item label={t('access_log.time')}>{formatTime(record['@timestamp'])}</Descriptions.Item>
        <Descriptions.Item label={t('access_log.status_code')}>
          <Badge status={getStatusBadge(record.statusCode)} text={<Text strong>{record.statusCode}</Text>} />
        </Descriptions.Item>
        <Descriptions.Item label={t('access_log.latency')}>
          <Text style={{ color: getDurationColor(record.durationMs), fontWeight: 'bold' }}>
            {record.durationMs} ms
          </Text>
        </Descriptions.Item>
        <Descriptions.Item label={t('access_log.client_ip_label')}>{record.clientIp || '-'}</Descriptions.Item>
      </Descriptions>

      {/* Request line */}
      <Descriptions
        size="small"
        column={3}
        labelStyle={{ width: 80, color: 'var(--text-secondary)' }}
        style={{ marginBottom: 8 }}
      >
        <Descriptions.Item label={t('access_log.method_label')}>
          <Tag color={getMethodColor(record.method)}>{record.method}</Tag>
        </Descriptions.Item>
        <Descriptions.Item label={t('access_log.path_label')}>
          <Text code style={{ fontSize: 12, wordBreak: 'break-all' }}>{record.path}</Text>
        </Descriptions.Item>
        <Descriptions.Item label={t('access_log.query_params')}>
          {record.query ? <Text code className="text-xs-sm" style={{ wordBreak: 'break-all' }}>{record.query}</Text> : '-'}
        </Descriptions.Item>
      </Descriptions>

      {/* Correlation IDs */}
      <Descriptions
        size="small"
        column={4}
        labelStyle={{ width: 80, color: 'var(--text-secondary)' }}
        style={{ marginBottom: 8 }}
      >
        <Descriptions.Item label={t('access_log.request_id')}>
          <Text copyable code style={{ fontSize: 11 }}>{record.requestId}</Text>
        </Descriptions.Item>
        <Descriptions.Item label={t('access_log.trace_id_label')}>
          {record.traceId ? (
            <Tooltip title={record.traceId}>
              <Text copyable code style={{ fontSize: 11 }}>
                {record.traceId.length > 20
                  ? `${record.traceId.substring(0, 10)}...${record.traceId.substring(record.traceId.length - 8)}`
                  : record.traceId}
              </Text>
            </Tooltip>
          ) : '-'}
        </Descriptions.Item>
        <Descriptions.Item label={t('access_log.request_size')}>{reqSize}</Descriptions.Item>
        <Descriptions.Item label={t('access_log.response_size')}>{resSize}</Descriptions.Item>
      </Descriptions>

      {/* Auth info */}
      {(record.authType || record.routeId || record.serviceId) && (
        <Descriptions
          size="small"
          column={3}
          labelStyle={{ width: 80, color: 'var(--text-secondary)' }}
          style={{ marginBottom: 8 }}
        >
          {record.authType && <Descriptions.Item label={t('access_log.auth')}><Tag color="purple">{record.authType}</Tag></Descriptions.Item>}
          {record.authPolicy && <Descriptions.Item label={t('access_log.policy')}><Text code style={{ fontSize: 11 }}>{record.authPolicy}</Text></Descriptions.Item>}
          {record.authUser && <Descriptions.Item label={t('access_log.user')}>{record.authUser}</Descriptions.Item>}
        </Descriptions>
      )}

      {/* Route / service routing info */}
      {(record.routeId || record.serviceId) && (
        <Descriptions
          size="small"
          column={2}
          labelStyle={{ width: 80, color: 'var(--text-secondary)' }}
          style={{ marginBottom: 8 }}
        >
          {record.routeId && (
            <Descriptions.Item label={t('access_log.route_id')}>
              <Text code copyable style={{ fontSize: 11, wordBreak: 'break-all' }}>{record.routeId}</Text>
            </Descriptions.Item>
          )}
          {record.serviceId && (
            <Descriptions.Item label={t('access_log.service_id')}>
              <Text code copyable style={{ fontSize: 11 }}>{record.serviceId}</Text>
            </Descriptions.Item>
          )}
        </Descriptions>
      )}

      {/* Request body */}
      <div style={{ marginTop: 12 }}>
        <div className="json-container">
          <div className="json-header">
            <span>📤 {t('access_log.request_body')}</span>
            {record.requestBody && <span className="json-size">{record.requestBody.length} bytes</span>}
            <Radio.Group
              size="small"
              value={bodyViewMode}
              onChange={(e) => onBodyViewModeChange(e.target.value)}
            >
              <Radio.Button value="formatted">{t('access_log.formatted')}</Radio.Button>
              <Radio.Button value="raw">{t('access_log.raw')}</Radio.Button>
            </Radio.Group>
          </div>
          <div className="json-content">
            {record.requestBody ? (
              bodyViewMode === 'formatted' ? (
                <pre className="json-viewer" dangerouslySetInnerHTML={{ __html: renderFormattedJson(record.requestBody) || '' }} />
              ) : (
                <div style={{ padding: 16, color: 'var(--text-primary)', fontSize: 13, fontFamily: 'Consolas, Monaco, monospace', whiteSpace: 'pre-wrap', wordBreak: 'break-word' }}>
                  {renderRawJson(record.requestBody)}
                </div>
              )
            ) : <div style={{ padding: 24, textAlign: 'center', color: 'var(--text-secondary)' }}>{t('access_log.not_recorded')}</div>}
          </div>
        </div>
      </div>

      {/* Response body */}
      <div style={{ marginTop: 12 }}>
        <BodyPanel
          title={`📥 ${t('access_log.response_body')}`}
          body={record.responseBody}
          viewMode={bodyViewMode}
          headerColor="var(--success-color, #52c41a)"
          notRecordedLabel={t('access_log.not_recorded')}
        />
      </div>

      {record.errorMessage && (
        <Alert
          message={t('access_log.error_info')}
          description={record.errorMessage}
          type="error"
          showIcon
          style={{ marginTop: 12 }}
        />
      )}
    </div>
  );
};

export default LogEntryExpanded;
