/**
 * Strategy card: identity, status and only the decision-critical config values.
 * Full configuration lives in the edit modal.
 */
import React, { memo } from 'react';
import { Badge, Button, Card, Popconfirm, Space, Tag, Tooltip, Typography } from 'antd';
import { message } from '../../../utils/antdStatic';
import {
  CompassOutlined, CopyOutlined, DeleteOutlined, EditOutlined,
  GlobalOutlined, PlayCircleOutlined, StopOutlined,
} from '@ant-design/icons';
import { useTranslation } from 'react-i18next';
import type { StrategyConfig, StrategyDefinition, StrategyTypeInfo } from '../../../types/strategy';
import { getStrategyTypeTagClass } from '../utils';
import { getStrategyTypeIcon } from './icons';
import { statusTone, toneForLegacyColor } from '../../../styles/palette';

const { Text } = Typography;

type TFunc = (key: string, options?: Record<string, unknown>) => string;

interface StrategyCardProps {
  strategy: StrategyDefinition;
  typeInfo?: StrategyTypeInfo;
  /** Locale-aware type name (falls back to raw typeCode). */
  typeLabel: string;
  onEdit: (strategy: StrategyDefinition) => void;
  onToggle: (strategy: StrategyDefinition) => void;
  onDelete: (strategy: StrategyDefinition) => void;
}

/** Preferred summary fields per type, most decision-relevant first. */
const SUMMARY_FIELDS: Record<string, string[]> = {
  RATE_LIMITER: ['qps', 'burstCapacity'],
  TOKEN_RATE_LIMITER: ['defaultMonthlyQuota', 'defaultDailyQuota', 'responseFormat', 'quotaPeriod'],
  IP_FILTER: ['mode', 'ipList'],
  TIMEOUT: ['connectTimeout', 'responseTimeout'],
  CIRCUIT_BREAKER: ['failureRateThreshold', 'waitDurationInOpenState'],
  AUTH: ['authType'],
  RETRY: ['maxAttempts', 'retryIntervalMs'],
  CORS: ['allowedOrigins', 'maxAge'],
  ACCESS_LOG: ['logLevel', 'samplingRate'],
  HEADER_OP: ['traceIdHeader', 'enableTraceId'],
  CACHE: ['ttlSeconds', 'maxSize'],
  SECURITY: ['mode'],
  API_VERSION: ['versionMode', 'defaultVersion'],
  REQUEST_TRANSFORM: ['maxBodySize', 'validateAfterTransform'],
  RESPONSE_TRANSFORM: ['maxBodySize', 'errorHandling'],
  MOCK_RESPONSE: ['mockMode'],
  AI_PROXY: ['providerSelection', 'defaultModel'],
  AI_SECURITY: ['inboundAction', 'outboundAction'],
  AI_PROMPT_TEMPLATE: ['model', 'systemPromptMode'],
  AI_SEMANTIC_CACHE: ['similarityThreshold', 'ttlSeconds'],
  AI_CONTEXT_LIMIT: ['maxContextTokens', 'bufferRatio'],
};

/** Config keys whose locale label prefixes the value in the summary. */
const VALUE_LABELS: Record<string, string> = {
  connectTimeout: 'strategy.config.connect_timeout',
  responseTimeout: 'strategy.config.response_timeout',
  failureRateThreshold: 'strategy.config.failure_rate',
  waitDurationInOpenState: 'strategy.config.wait_duration',
  maxAttempts: 'strategy.config.max_attempts',
  retryIntervalMs: 'strategy.config.retry_interval',
  ttlSeconds: 'strategy.config.ttl_seconds',
  maxSize: 'strategy.config.max_size',
  defaultMonthlyQuota: 'strategy.summary.monthly_quota',
  defaultDailyQuota: 'strategy.summary.daily_quota',
  maxContextTokens: 'strategy.summary.max_context',
  similarityThreshold: 'strategy.summary.similarity',
  responseFormat: 'strategy.summary.response_format',
  quotaPeriod: 'strategy.summary.quota_period',
};

const QUOTA_PERIOD_KEYS: Record<string, string> = {
  MONTHLY: 'strategy.summary.quota_period_monthly',
  DAILY: 'strategy.summary.quota_period_daily',
  BOTH: 'strategy.summary.quota_period_both',
};

const AUTH_TYPE_KEYS: Record<string, string> = {
  JWT: 'strategy.auth_type.jwt',
  API_KEY: 'strategy.auth_type.api_key',
  OAUTH2: 'strategy.auth_type.oauth2',
  BASIC: 'strategy.auth_type.basic',
  HMAC: 'strategy.auth_type.hmac',
};

const LOG_LEVEL_KEYS: Record<string, string> = {
  MINIMAL: 'strategy.log_level.minimal',
  NORMAL: 'strategy.log_level.normal',
  VERBOSE: 'strategy.log_level.verbose',
};

const VERSION_MODE_KEYS: Record<string, string> = {
  PATH: 'strategy.version_mode.path',
  HEADER: 'strategy.version_mode.header',
  QUERY: 'strategy.version_mode.query',
};

const AI_ACTION_KEYS: Record<string, string> = {
  BLOCK: 'strategy.ai_security.action_block',
  REPLACE: 'strategy.ai_security.action_replace',
  DETECT: 'strategy.ai_security.action_detect',
};

/** One compact "value" segment for a config entry, or null when not summarisable. */
function formatConfigValue(key: string, value: unknown, t: TFunc): string | null {
  if (value === null || value === undefined) return null;

  if (key === 'qps') return `${value} QPS`;
  if (key === 'burstCapacity') return `burst ${value}`;
  if (key === 'mode') {
    if (value === 'whitelist') return t('strategy.config.whitelist');
    if (value === 'blacklist') return t('strategy.config.blacklist');
    if (value === 'BLOCK') return t('strategy.config.block_mode');
    if (value === 'DETECT') return t('strategy.config.detect_mode');
    return String(value);
  }
  if (key === 'ipList' && Array.isArray(value)) {
    return t('strategy.summary.ip_count', { count: value.length });
  }
  if (key === 'allowedOrigins' && Array.isArray(value)) {
    return t('strategy.summary.origins', { count: value.length });
  }
  if (key === 'authType') {
    const authKey = AUTH_TYPE_KEYS[String(value)];
    return authKey ? t(authKey) : String(value);
  }
  if (key === 'logLevel') return LOG_LEVEL_KEYS[String(value)] ? t(LOG_LEVEL_KEYS[String(value)]) : String(value);
  if (key === 'versionMode') return VERSION_MODE_KEYS[String(value)] ? t(VERSION_MODE_KEYS[String(value)]) : String(value);
  if (key === 'inboundAction' || key === 'outboundAction') {
    return AI_ACTION_KEYS[String(value)] ? t(AI_ACTION_KEYS[String(value)]) : String(value);
  }
  if (key === 'quotaPeriod') {
    const periodKey = QUOTA_PERIOD_KEYS[String(value)];
    return periodKey ? t(periodKey) : String(value);
  }
  if (key === 'defaultVersion' || key === 'defaultModel' || key === 'traceIdHeader'
    || key === 'providerSelection' || key === 'mockMode' || key === 'systemPromptMode'
    || key === 'responseFormat') {
    return value === '' ? null : String(value);
  }

  if (typeof value === 'boolean') {
    return value ? t('common.enabled') : t('common.disabled');
  }
  if (typeof value === 'number') {
    if (key.endsWith('Timeout') || key === 'waitDurationInOpenState') return `${value}ms`;
    if (key.endsWith('Ms')) return `${value}ms`;
    if (key.endsWith('Seconds')) return `${value}s`;
    if (/Rate$/i.test(key)) return `${value}%`;
    if (key === 'defaultMonthlyQuota' || key === 'defaultDailyQuota' || key === 'maxContextTokens') {
      // Zero means "no default" in the token quota config; showing it would be noise.
      return value > 0 ? `${value} tokens` : null;
    }
    return `${value}`;
  }
  if (typeof value === 'string') return value || null;
  if (Array.isArray(value)) return `${value.length}`;
  if (typeof value === 'object') return `${Object.keys(value).length}`;
  return null;
}

/** MULTI_DIM_RATE_LIMITER: lead with the most specific enabled quota. */
function summarizeMultiDim(config: StrategyConfig, t: TFunc): { segments: string[]; usedKeys: string[] } {
  const quotas: Array<{ key: string; label: string }> = [
    { key: 'userQuota', label: t('strategy.config.quota_user') },
    { key: 'ipQuota', label: t('strategy.config.quota_ip') },
    {
      key: 'tenantQuota',
      label: t(config.keySource === 'header' ? 'strategy.config.quota_header' : 'strategy.config.quota_client'),
    },
    { key: 'globalQuota', label: t('strategy.config.quota_global') },
  ];
  const enabled = quotas.filter(({ key }) => {
    const quota = config[key] as { enabled?: boolean } | undefined;
    return !!quota?.enabled;
  });
  if (enabled.length === 0) {
    return { segments: [t('strategy.config.quota_disabled')], usedKeys: quotas.map(({ key }) => key) };
  }
  const primary = enabled[0];
  const quota = config[primary.key] as { qps?: number; burstCapacity?: number };
  const qps = Number(quota.qps ?? 100);
  const burst = Number(quota.burstCapacity ?? 200);
  const qpsSegment = `${Number.isFinite(qps) ? qps : 100} QPS`;
  return {
    segments: [enabled.length > 1 ? `${primary.label} ${qpsSegment}` : qpsSegment, `burst ${Number.isFinite(burst) ? burst : 200}`],
    usedKeys: [primary.key],
  };
}

/** Identity + the 1-2 config values worth showing on the card. */
function summarizeConfig(strategy: StrategyDefinition, t: TFunc): { segments: string[]; hidden: number } {
  const config = strategy.config || {};
  // `enabled` is already shown as the card status; it would only add noise to the count.
  const allKeys = Object.keys(config).filter((key) => key !== 'enabled');
  if (strategy.strategyType === 'MULTI_DIM_RATE_LIMITER') {
    const { segments, usedKeys } = summarizeMultiDim(config, t);
    return { segments, hidden: allKeys.filter((key) => !usedKeys.includes(key)).length };
  }

  const preferred = SUMMARY_FIELDS[strategy.strategyType] ?? [];
  const segments: string[] = [];
  const usedKeys: string[] = [];
  for (const key of preferred) {
    if (segments.length >= 2) break;
    if (!(key in config)) continue;
    const raw = formatConfigValue(key, config[key], t);
    if (raw === null) continue;
    const labelKey = VALUE_LABELS[key];
    segments.push(labelKey ? `${t(labelKey)} ${raw}` : raw);
    usedKeys.push(key);
  }
  return { segments, hidden: allKeys.filter((key) => !usedKeys.includes(key)).length };
}

const StrategyCard = memo(({ strategy, typeInfo, typeLabel, onEdit, onToggle, onDelete }: StrategyCardProps) => {
  const { t } = useTranslation();
  const tone = statusTone[toneForLegacyColor(typeInfo?.color)];
  const { segments, hidden } = summarizeConfig(strategy, t);
  const statusText = strategy.enabled ? t('common.enabled') : t('common.disabled');

  return (
    <Card className={`strategy-card ${!strategy.enabled ? 'strategy-card-disabled' : ''}`} hoverable>
      <div className="strategy-card-header">
        <div className="strategy-info">
          <div className="strategy-icon" style={{ color: tone.color, '--tone-bg': tone.bg } as React.CSSProperties}>
            {getStrategyTypeIcon(typeInfo?.icon)}
          </div>
          <div className="strategy-details">
            <div className="strategy-name-row">
              <Tooltip title={strategy.strategyName}>
                <Text strong className="strategy-name">{strategy.strategyName}</Text>
              </Tooltip>
              <Badge
                className="strategy-status"
                status={strategy.enabled ? 'success' : 'default'}
                text={statusText}
              />
            </div>
            <div className="strategy-id-row">
              <Tooltip title={strategy.strategyId}>
                <Text type="secondary" className="strategy-id">{strategy.strategyId}</Text>
              </Tooltip>
              <Tooltip title={t('common.copy_id')}>
                <Button
                  type="text"
                  size="small"
                  icon={<CopyOutlined />}
                  className="copy-btn"
                  onClick={() => {
                    navigator.clipboard.writeText(strategy.strategyId);
                    message.success(t('message.copy_success'));
                  }}
                />
              </Tooltip>
            </div>
          </div>
        </div>
        <Space className="strategy-actions">
          <Tooltip title={t('common.edit')}>
            <Button type="text" size="small" icon={<EditOutlined />} className="action-btn" onClick={() => onEdit(strategy)} />
          </Tooltip>
          <Tooltip title={strategy.enabled ? t('common.disable') : t('common.enable')}>
            <Button
              type="text"
              size="small"
              icon={strategy.enabled ? <StopOutlined /> : <PlayCircleOutlined />}
              className="action-btn"
              onClick={() => onToggle(strategy)}
            />
          </Tooltip>
          <Popconfirm
            title={`${t('common.delete')}: ${strategy.strategyName}`}
            onConfirm={() => onDelete(strategy)}
            okText={t('common.confirm')}
            cancelText={t('common.cancel')}
          >
            <Tooltip title={t('common.delete')}>
              <Button type="text" size="small" danger icon={<DeleteOutlined />} className="action-btn" />
            </Tooltip>
          </Popconfirm>
        </Space>
      </div>

      <div className="strategy-meta">
        <Tag
          className="strategy-scope-tag"
          icon={strategy.scope === 'GLOBAL' ? <GlobalOutlined /> : <CompassOutlined />}
          color={strategy.scope === 'GLOBAL' ? 'blue' : 'green'}
        >
          {strategy.scope === 'GLOBAL' ? t('strategy.scope_global') : t('strategy.scope_route')}
        </Tag>
        {strategy.scope === 'ROUTE' && strategy.routeId && (
          <Tooltip title={`${t('strategy.bound_route')}: ${strategy.routeId}`}>
            <span className="strategy-route-chip">
              <CompassOutlined />
              <span className="strategy-route-id">{strategy.routeId}</span>
            </span>
          </Tooltip>
        )}
      </div>

      <div className="strategy-type-row">
        <Tag className={`strategy-type-tag ${getStrategyTypeTagClass(typeInfo?.category)}`}>
          {typeLabel}
        </Tag>
        {segments.map((segment, index) => (
          <span key={`${segment}-${index}`} className="strategy-summary-chip">{segment}</span>
        ))}
        {hidden > 0 && (
          <span className="strategy-more-chip">{t('strategy.more_config_entries', { count: hidden })}</span>
        )}
      </div>

      {strategy.description && (
        <div className="strategy-description">
          <Text type="secondary" ellipsis={{ tooltip: strategy.description }}>{strategy.description}</Text>
        </div>
      )}
    </Card>
  );
});

export default StrategyCard;