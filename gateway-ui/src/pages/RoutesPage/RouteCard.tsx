import React from 'react';
import { Card, Tag, Tooltip, Badge, Button, message, Typography } from 'antd';
import {
  ApiOutlined, BranchesOutlined, ThunderboltOutlined, CloudOutlined,
  SplitCellsOutlined, CopyOutlined
} from '@ant-design/icons';
import { useTranslation } from 'react-i18next';
import type { TFunction } from 'i18next';
import copy from 'copy-to-clipboard';
import type { Route, RoutePredicate } from './types';
import RouteActionDropdown from './RouteActionDropdown';

const { Text } = Typography;

interface RouteCardProps {
  route: Route;
  onEdit: (route: Route) => void;
  onDetail: (route: Route) => void;
  onToggle: (route: Route) => void;
  onDelete: (route: Route) => void;
}

/**
 * Predicate tag renderer - extracts display value from predicate args
 */
const getPredicateTag = (predicate: RoutePredicate, t: TFunction) => {
  const name = predicate.name;
  const args: Record<string, unknown> = (predicate.args && typeof predicate.args === 'object') ? predicate.args : {};
  let value = '';
  if (name === 'Path') value = String(args.pattern || '/**');
  else if (name === 'Host') value = String(args.patterns || args.pattern || '');
  else if (name === 'Method') {
    // Handle indexed keys format: methods.0=GET, methods.1=POST
    if (Array.isArray(args.methods)) {
      value = (args.methods as unknown[]).join(', ');
    } else if (typeof args.methods === 'string') {
      value = args.methods;
    } else {
      // Extract from indexed keys (methods.0, methods.1, etc.)
      const methodKeys = Object.keys(args).filter(k => k.startsWith('methods.'));
      if (methodKeys.length > 0) {
        const methods = methodKeys
          .sort((a, b) => parseInt(a.split('.')[1]) - parseInt(b.split('.')[1]))
          .map(k => args[k]);
        value = methods.join(', ');
      }
    }
  }
  else if (name === 'Header') value = String(args.header || args.name || '');
  else value = JSON.stringify(predicate.args);

  const desc = t(`predicate.${name}.detail`, { defaultValue: '' });

  // Premium predicate tag style - soft blue with subtle border
  const predicateTagStyle: React.CSSProperties = {
    borderRadius: '9999px',
    padding: '4px 12px',
    fontSize: '13px',
    fontWeight: 500,
    backgroundColor: 'rgba(59, 130, 246, 0.15)',
    color: '#60a5fa',
    border: '1px solid rgba(59, 130, 246, 0.3)',
    cursor: 'help',
  };

  return (
    <Tooltip key={name} title={desc} placement="top">
      <Tag style={predicateTagStyle} className="predicate-tag">
        <BranchesOutlined /> {name}: <code style={{ fontFamily: 'JetBrains Mono, monospace' }}>{value}</code>
      </Tag>
    </Tooltip>
  );
};

/**
 * Plugin tag style - soft green with subtle border
 */
const pluginTagStyle: React.CSSProperties = {
  borderRadius: '9999px',
  padding: '4px 12px',
  fontSize: '13px',
  fontWeight: 500,
  backgroundColor: 'rgba(16, 185, 129, 0.15)',
  color: '#34d399',
  border: '1px solid rgba(16, 185, 129, 0.3)',
  cursor: 'help',
};

/**
 * Route card component - displays a single route in the grid
 */
const RouteCard: React.FC<RouteCardProps> = ({ route, onEdit, onDetail, onToggle, onDelete }) => {
  const { t } = useTranslation();
  const predicateCount = route.predicates?.length || 0;
  const pluginCount = route.filters?.length || 0;
  const isMultiService = route.mode === 'MULTI' && route.services && route.services.length > 0;

  return (
    <Card className="route-card" hoverable>
      <div className="route-card-header">
        <div className="route-info">
          <div className="route-icon"><ApiOutlined /></div>
          <div className="route-details">
            <Text strong className="route-name">{route.routeName || (route.id && route.id.substring(0, 8)) || 'Unknown'}</Text>
            <div className="route-id-row">
              <Text type="secondary" className="route-id">{route.id ? route.id.substring(0, 16) : 'N/A'}...</Text>
              <Tooltip title={t('common.copy')}>
                <Button
                  type="text"
                  size="small"
                  icon={<CopyOutlined />}
                  className="copy-btn"
                  onClick={(e) => {
                    e.stopPropagation();
                    copy(route.id || '');
                    message.success(t('message.copy_success'));
                  }}
                />
              </Tooltip>
            </div>
          </div>
        </div>
        <div className="route-status">
          <Badge status={route.enabled ? 'success' : 'default'} text={route.enabled ? t('common.enabled') : t('common.disabled')} />
        </div>
        <RouteActionDropdown
          route={route}
          onEdit={onEdit}
          onDetail={onDetail}
          onToggle={onToggle}
          onDelete={onDelete}
        />
      </div>

      {/* Target Service(s) */}
      <div className="route-section">
        <Text type="secondary" className="section-label">
          {isMultiService ? <SplitCellsOutlined /> : <CloudOutlined />} {isMultiService ? t('routes.services') : t('routes.target_uri')}
        </Text>
        <div className="tags-container">
          {isMultiService ? (
            route.services!.map((s, idx) => (
              <Tag key={idx} className="service-tag" color={s.enabled ? 'blue' : 'default'}>
                {s.serviceName || s.serviceId} ({s.weight}%)
              </Tag>
            ))
          ) : (
            <Text code>{route.uri || 'N/A'}</Text>
          )}
        </div>
      </div>

      {/* Predicates */}
      <div className="route-section">
        <Text type="secondary" className="section-label">
          <BranchesOutlined /> {t('routes.predicates')} ({predicateCount})
        </Text>
        <div className="tags-container">
          {predicateCount > 0 ? (
            route.predicates!.map((p, idx) => getPredicateTag(p, t))
          ) : (
            <Text type="secondary" className="no-data">{t('routes.no_predicates')}</Text>
          )}
        </div>
      </div>

      {/* Plugins */}
      <div className="route-section">
        <Text type="secondary" className="section-label">
          <ThunderboltOutlined /> {t('routes.plugins')} ({pluginCount})
        </Text>
        <div className="tags-container">
          {pluginCount > 0 ? (
            route.filters!.map((f, idx) => (
              <Tooltip key={idx} title={t(`plugin.${f.name}.detail`, { defaultValue: '' })} placement="top">
                <Tag style={pluginTagStyle} className="plugin-tag">
                  <ThunderboltOutlined /> {t(`plugin.${f.name}.name`, { defaultValue: f.name })}
                </Tag>
              </Tooltip>
            ))
          ) : (
            <Text type="secondary" className="no-data">{t('routes.no_plugins')}</Text>
          )}
        </div>
      </div>

      {/* Meta */}
      <div className="route-meta">
        <Text type="secondary">Order: {route.order ?? 0}</Text>
        {route.description && (
          <Tooltip title={route.description}>
            <Text type="secondary" ellipsis className="route-desc">{route.description}</Text>
          </Tooltip>
        )}
      </div>
    </Card>
  );
};

export default RouteCard;