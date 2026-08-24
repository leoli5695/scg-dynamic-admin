/**
 * Strategy card with action buttons and config preview.
 */
import React, { memo } from 'react';
import { Badge, Button, Card, Popconfirm, Space, Tag, Tooltip, Typography } from 'antd';
import { message } from '../../../utils/antdStatic';
import {
  ApiOutlined, CopyOutlined, DeleteOutlined, EditOutlined,
  GlobalOutlined, PlayCircleOutlined, StopOutlined,
} from '@ant-design/icons';
import { useTranslation } from 'react-i18next';
import type { StrategyConfig, StrategyDefinition, StrategyTypeInfo } from '../../../types/strategy';
import {
  formatHeaderNames, formatWindowSize, getQuotaDisplayName,
  getRejectStrategyDisplayName, getStrategyTypeTagClass,
} from '../utils';
import { getStrategyTypeIcon } from './icons';

const { Text } = Typography;

interface StrategyCardProps {
  strategy: StrategyDefinition;
  typeInfo?: StrategyTypeInfo;
  /** Locale-aware type name (falls back to raw typeCode). */
  typeLabel: string;
  onEdit: (strategy: StrategyDefinition) => void;
  onToggle: (strategy: StrategyDefinition) => void;
  onDelete: (strategy: StrategyDefinition) => void;
}

/** Format one config entry for the preview / tooltip. */
function formatConfigEntry(
  key: string,
  value: unknown,
  strategyType: string,
  config: StrategyConfig,
): { displayKey: string; displayValue: string } {
  let displayKey = key;
  let displayValue: string;

  if (strategyType === 'MULTI_DIM_RATE_LIMITER') {
    displayKey = getQuotaDisplayName(key, config);
    const keyNameMap: Record<string, string> = {
      rejectStrategy: '拒绝策略',
      headerNames: 'Header配置',
      keyPrefix: '键前缀',
      windowSizeMs: '时间窗口',
      keySource: '键来源',
    };
    displayKey = keyNameMap[key] || displayKey;

    if (key.endsWith('Quota') && typeof value === 'object' && value !== null) {
      const quota = value as Record<string, unknown>;
      displayValue = quota.enabled
        ? `${Number(quota.qps) || 100} QPS, burst: ${Number(quota.burstCapacity) || 200}`
        : '未启用';
    } else if (key === 'rejectStrategy') {
      displayValue = getRejectStrategyDisplayName(String(value));
    } else if (key === 'headerNames' && typeof value === 'object' && value !== null) {
      displayValue = formatHeaderNames(value as Record<string, string>);
    } else if (key === 'windowSizeMs') {
      displayValue = formatWindowSize(Number(value));
    } else if (key === 'keySource') {
      const keySourceMap: Record<string, string> = {
        combined: '组合键',
        ip: 'IP',
        user: '用户',
        header: 'Header',
      };
      displayValue = keySourceMap[String(value)] || String(value);
    } else {
      displayValue = typeof value === 'object' ? JSON.stringify(value) : String(value);
    }
  } else {
    displayValue = typeof value === 'object' ? JSON.stringify(value) : String(value);
  }

  return { displayKey, displayValue };
}

const StrategyCard = memo(({ strategy, typeInfo, typeLabel, onEdit, onToggle, onDelete }: StrategyCardProps) => {
  const { t } = useTranslation();
  const config = strategy.config || {};
  const color = typeInfo?.color || '#3b82f6';
  const entries = Object.entries(config);

  return (
    <Card className={`strategy-card ${!strategy.enabled ? 'strategy-card-disabled' : ''}`} hoverable>
      <div className="strategy-card-header">
        <div className="strategy-icon" style={{ background: `${color}20`, color }}>
          {getStrategyTypeIcon(typeInfo?.icon)}
        </div>
        <div className="strategy-info">
          <Text strong className="strategy-name">{strategy.strategyName}</Text>
          <div className="strategy-id-row">
            <Tag className={`strategy-type-tag ${getStrategyTypeTagClass(typeInfo?.category)}`}>
              {typeLabel}
            </Tag>
            <Tooltip title={t('common.copy_id')}>
              <Button
                type="text"
                size="small"
                icon={<CopyOutlined />}
                className="copy-btn"
                onClick={(e) => {
                  e.stopPropagation();
                  navigator.clipboard.writeText(strategy.strategyId);
                  message.success(t('message.copy_success'));
                }}
              />
            </Tooltip>
          </div>
        </div>
        <Space>
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
        <Badge status={strategy.enabled ? 'success' : 'default'} text={strategy.enabled ? t('common.enabled') : t('common.disabled')} />
        <Tag icon={strategy.scope === 'GLOBAL' ? <GlobalOutlined /> : <ApiOutlined />} color={strategy.scope === 'GLOBAL' ? 'blue' : 'green'}>
          {strategy.scope === 'GLOBAL' ? t('strategy.scope_global') : t('strategy.scope_route')}
        </Tag>
      </div>

      {strategy.scope === 'ROUTE' && strategy.routeId && (
        <div className="strategy-route">
          <Text type="secondary">{t('strategy.bound_route')}:</Text>
          <Text code className="route-id">{strategy.routeId.substring(0, 16)}...</Text>
        </div>
      )}

      <div className="strategy-config">
        {entries.slice(0, 2).map(([key, value]) => {
          const { displayKey, displayValue } = formatConfigEntry(key, value, strategy.strategyType, config);
          const shortValue = displayValue.substring(0, 25);
          return (
            <div key={key} className="config-item">
              <Text type="secondary" className="config-key">{displayKey}:</Text>
              <Text className="config-value" title={displayValue}>
                {shortValue}{displayValue.length > 25 ? '...' : ''}
              </Text>
            </div>
          );
        })}
        {entries.length > 2 && (
          <Tooltip
            title={
              <div style={{ maxHeight: '300px', overflowY: 'auto' }}>
                {entries.slice(2).map(([key, value]) => {
                  const { displayKey, displayValue } = formatConfigEntry(key, value, strategy.strategyType, config);
                  return (
                    <div key={key} style={{ marginBottom: '4px', fontSize: '12px' }}>
                      <span style={{ color: '#999' }}>{displayKey}: </span>
                      <span>{displayValue}</span>
                    </div>
                  );
                })}
              </div>
            }
            placement="top"
            mouseEnterDelay={0.3}
          >
            <Text type="secondary" style={{ fontSize: '11px', marginTop: '2px', cursor: 'pointer' }}>
              +{entries.length - 2} more
            </Text>
          </Tooltip>
        )}
      </div>

      {strategy.description && (
        <div className="strategy-description">
          <Text type="secondary" ellipsis>{strategy.description}</Text>
        </div>
      )}
    </Card>
  );
});

export default StrategyCard;
