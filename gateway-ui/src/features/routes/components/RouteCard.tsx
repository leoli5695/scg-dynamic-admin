/**
 * Route card in the routes grid.
 *
 * Field policy follows the commercial gateway console layout: the card carries
 * identity, status and the routing summary an operator scans for (method + path,
 * target, predicate/plugin counts). Predicate and plugin details live in the
 * detail drawer.
 */
import React from 'react';
import {
  Badge, Button, Card, Tag, Tooltip, Typography  } from 'antd';
import { message } from '../../../utils/antdStatic';
import {
  AppstoreAddOutlined, BranchesOutlined, CloudOutlined, CompassOutlined, CopyOutlined,
} from '@ant-design/icons';
import { useTranslation } from 'react-i18next';
import copy from 'copy-to-clipboard';
import type { RouteResponse } from '../../../types/route';
import type { ServiceDefinition } from '../../../types/service';
import { extractMethods, resolveServiceDisplayName, splitServiceUri } from '../utils';
import RouteActions from './RouteActions';

const { Text } = Typography;

/** Known HTTP methods get their own hue; anything else stays neutral. */
const HTTP_METHODS = ['GET', 'POST', 'PUT', 'PATCH', 'DELETE', 'HEAD', 'OPTIONS'];

/** Shortcut YAML compiles to `_genkey_*` args; named keys win when present. */
const shortcutValues = (args?: Record<string, string>): string[] => (
  Object.keys(args ?? {})
    .filter((key) => key.startsWith('_genkey_'))
    .sort()
    .map((key) => String(args![key] ?? ''))
    .filter((value) => value.trim() !== '')
);

const pathOf = (args?: Record<string, string>): string => {
  const direct = args?.pattern || args?.patterns;
  if (direct) return String(direct);
  return shortcutValues(args)[0] || '/**';
};

const methodsOf = (args?: Record<string, string>): string[] => {
  const named = extractMethods(args);
  if (named.length > 0) return named;
  return shortcutValues(args)
    .flatMap((value) => value.split(','))
    .map((value) => value.trim().toUpperCase())
    .filter((value) => HTTP_METHODS.includes(value));
};

interface RouteCardProps {
  route: RouteResponse;
  services: ServiceDefinition[];
  onEdit: (route: RouteResponse) => void;
  onDetail: (route: RouteResponse) => void;
  onToggle: (route: RouteResponse) => void;
  onDelete: (route: RouteResponse) => void;
}

const RouteCard: React.FC<RouteCardProps> = ({
  route, services, onEdit, onDetail, onToggle, onDelete,
}) => {
  const { t } = useTranslation();
  const isAi = route.category === 'AI';
  const isMulti = route.mode === 'MULTI' && !!route.services && route.services.length > 0;
  const predicateCount = route.predicates?.length || 0;
  const pluginCount = route.filters?.length || 0;

  const pathPredicates = (route.predicates ?? []).filter((p) => p.name === 'Path');
  const pathPatterns = pathPredicates.map((p) => pathOf(p.args));
  const primaryPath = pathPatterns[0] || '—';

  const methods = Array.from(new Set(
    (route.predicates ?? [])
      .filter((p) => p.name === 'Method')
      .flatMap((p) => methodsOf(p.args))
      .filter(Boolean),
  ));
  const visibleMethods = methods.slice(0, 3);
  const hiddenMethodCount = methods.length - visibleMethods.length;

  const aiNames = (route.aiUpstreams ?? []).map((u) => u.provider).filter(Boolean);
  const singleId = route.serviceId || (route.uri ? splitServiceUri(route.uri)[1] : '');
  const singleTarget = singleId
    ? resolveServiceDisplayName(undefined, singleId, services)
    : (route.uri || 'N/A');

  const targetText = isAi
    ? t('routes.ai_upstream_summary', { count: aiNames.length })
    : isMulti
      ? t('routes.multi_service_summary', { count: route.services!.length })
      : singleTarget;

  const targetTooltip = isAi
    ? aiNames.join(' / ') || route.uri || ''
    : isMulti
      ? route.services!.map((s) => `${s.serviceName || s.serviceId} ${s.weight}%`).join(' · ')
      : (route.uri || singleTarget);

  const routeName = route.routeName || route.id || 'Unknown';
  const idCopyValue = route.id || '';
  const modeTag = isAi
    ? { label: t('routes.category_ai'), tone: 'ai' }
    : isMulti
      ? { label: t('routes.multi_service'), tone: 'multi' }
      : { label: t('routes.single_service'), tone: 'single' };

  return (
    <Card className="route-card" hoverable>
      <div className="route-card-header">
        <div className="route-info">
          <div className={`route-icon ${isAi ? 'ai' : ''}`}><CompassOutlined /></div>
          <div className="route-details">
            <Tooltip title={routeName}>
              <Text strong className="route-name">{routeName}</Text>
            </Tooltip>
            <div className="route-id-row">
              <Tooltip title={idCopyValue || 'N/A'}>
                <Text type="secondary" className="route-id">{idCopyValue || 'N/A'}</Text>
              </Tooltip>
              <Tooltip title={t('common.copy')}>
                <Button
                  type="text"
                  size="small"
                  icon={<CopyOutlined />}
                  className="copy-btn"
                  onClick={(e) => {
                    e.stopPropagation();
                    copy(idCopyValue);
                    message.success(t('message.copy_success'));
                  }}
                />
              </Tooltip>
            </div>
          </div>
        </div>
        <RouteActions
          route={route}
          onEdit={onEdit}
          onDetail={onDetail}
          onToggle={onToggle}
          onDelete={onDelete}
        />
      </div>

      <div className="route-card-meta">
        <Badge
          status={route.enabled ? 'success' : 'default'}
          text={route.enabled ? t('common.enabled') : t('common.disabled')}
        />
        <Tag className="route-mode-tag" data-tone={modeTag.tone}>{modeTag.label}</Tag>
      </div>

      <div className="route-summary">
        {visibleMethods.length > 0 ? (
          visibleMethods.map((method) => (
            <span
              key={method}
              className="route-method-chip"
              data-tone={HTTP_METHODS.includes(method) ? method : 'OTHER'}
            >
              {method}
            </span>
          ))
        ) : (
          <span className="route-method-chip" data-tone="ALL">{t('routes.all_methods')}</span>
        )}
        {hiddenMethodCount > 0 && <span className="route-method-more">+{hiddenMethodCount}</span>}
        <Tooltip title={pathPatterns.join('  /  ')}>
          <code className="route-path">{primaryPath}</code>
        </Tooltip>
      </div>

      <div className="route-target">
        <CloudOutlined className="route-target-icon" />
        <Tooltip title={targetTooltip}>
          <span className="route-target-text">{targetText}</span>
        </Tooltip>
      </div>

      <div className="route-counts">
        <span className="route-count-chip">
          <BranchesOutlined />
          {' '}
          {t('routes.predicates')}
          {' '}
          {predicateCount}
        </span>
        <span className="route-count-chip">
          <AppstoreAddOutlined />
          {' '}
          {t('routes.plugins')}
          {' '}
          {pluginCount}
        </span>
      </div>
    </Card>
  );
};

export default React.memo(RouteCard);