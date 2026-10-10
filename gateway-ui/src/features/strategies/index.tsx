/**
 * Strategies page (rewritten as a feature).
 * Fields aligned with StrategyController + model/StrategyDefinition.java;
 * type metadata from StrategyTypeController (/api/strategy-types/enabled).
 */
import React, { useCallback, useMemo, useState } from 'react';
import { Button, Card, Empty, Select, Spin, Tabs, Typography } from 'antd';
import { message } from '../../utils/antdStatic';
import { PlusOutlined, RobotOutlined, ThunderboltOutlined } from '@ant-design/icons';
import { useParams } from 'react-router-dom';
import { useTranslation } from 'react-i18next';
import { useRequest } from '../../hooks/useRequest';
import { ApiError } from '../../api/http';
import {
  createStrategy, deleteStrategy, disableStrategy, enableStrategy,
  listEnabledStrategyTypes, listStrategies, updateStrategy,
} from '../../api/strategies';
import { listRoutes } from '../../api/routes';
import type { StrategyDefinition, StrategyTypeInfo } from '../../types/strategy';
import { findGlobalStrategy } from './utils';
import StrategyCard from './components/StrategyCard';
import StrategyFormModal from './components/StrategyFormModal';
import StrategyTypeSelectModal from './components/StrategyTypeSelectModal';
import './strategies-premium.css';

const { Text, Title } = Typography;

const errorMessage = (error: unknown, fallbackKey: string, t: (k: string) => string): string => (
  error instanceof ApiError && error.message ? error.message : t(fallbackKey)
);

/** Minimal placeholder when a stored strategy references an unknown type. */
const fallbackTypeInfo = (typeCode: string): StrategyTypeInfo => ({
  typeCode,
  typeName: typeCode,
  typeNameEn: typeCode,
  icon: '',
  color: '#3b82f6',
  category: 'misc',
  enabled: true,
  sortOrder: 0,
});

const StrategiesPage: React.FC = () => {
  const { t, i18n } = useTranslation();
  const { instanceId } = useParams<{ instanceId: string }>();

  const [filterType, setFilterType] = useState<string>('all');
  const [filterScope, setFilterScope] = useState<string>('all');
  const [statusFilter, setStatusFilter] = useState<string>('all');
  // Category tab: ALL / NORMAL / AI. A strategy is AI when its type's category is 'ai'.
  const [categoryTab, setCategoryTab] = useState<'ALL' | 'NORMAL' | 'AI'>('ALL');
  const [typeSelectOpen, setTypeSelectOpen] = useState(false);
  const [creatingType, setCreatingType] = useState<StrategyTypeInfo | null>(null);
  const [editingStrategy, setEditingStrategy] = useState<StrategyDefinition | null>(null);

  const strategiesRequest = useRequest(
    (signal) => listStrategies(instanceId ? { instanceId } : undefined, { signal }),
    {
      deps: [instanceId],
      onError: (error) => {
        message.error(t('message.load_strategies_failed', { error: error.message }));
      },
    },
  );
  const strategies = useMemo(() => strategiesRequest.data ?? [], [strategiesRequest.data]);

  // Best-effort context for the ROUTE scope select.
  const routesRequest = useRequest(
    (signal) => listRoutes(instanceId ? { instanceId } : undefined, { signal }),
    { deps: [instanceId], onError: () => {} },
  );
  const routes = useMemo(() => routesRequest.data ?? [], [routesRequest.data]);

  const typesRequest = useRequest(
    (signal) => listEnabledStrategyTypes({ signal }),
    { onError: () => {} },
  );
  const strategyTypes = useMemo(() => typesRequest.data ?? [], [typesRequest.data]);

  const getStrategyType = (typeCode: string): StrategyTypeInfo | undefined =>
    strategyTypes.find((st) => st.typeCode === typeCode);

  /** A strategy is AI when its type's category is 'ai' (strategy_types.category). */
  const isAiStrategy = useCallback((s: StrategyDefinition): boolean => (
    (strategyTypes.find((st) => st.typeCode === s.strategyType)?.category || '').toLowerCase() === 'ai'
  ), [strategyTypes]);

  // Locale-aware type label (Chinese name for zh, English name for en).
  const typeLabel = useCallback((info?: StrategyTypeInfo, rawCode?: string): string => {
    if (!info) return rawCode || '';
    return i18n.language === 'en' ? info.typeNameEn : info.typeName;
  }, [i18n.language]);

  const filteredStrategies = useMemo(() => strategies.filter((s) => {
    const matchesType = filterType === 'all' || s.strategyType === filterType;
    const matchesScope = filterScope === 'all' || s.scope === filterScope;
    const matchesStatus = statusFilter === 'all'
      || (statusFilter === 'enabled' && s.enabled)
      || (statusFilter === 'disabled' && !s.enabled);
    const matchesCategory = categoryTab === 'ALL'
      || (categoryTab === 'AI' ? isAiStrategy(s) : !isAiStrategy(s));
    return matchesType && matchesScope && matchesStatus && matchesCategory;
  }), [strategies, filterType, filterScope, statusFilter, categoryTab, isAiStrategy]);

  // Per-tab counts for the tab labels.
  const categoryCounts = useMemo(() => {
    const ai = strategies.filter(isAiStrategy).length;
    return { all: strategies.length, ai, normal: strategies.length - ai };
  }, [strategies, isAiStrategy]);

  // The type filter dropdown follows the active tab; the create-flow picker
  // always sees ALL types (it has its own general/AI tabs).
  const selectableStrategyTypes = useMemo(() => (
    categoryTab === 'ALL'
      ? strategyTypes
      : strategyTypes.filter((st) => (
        categoryTab === 'AI'
          ? (st.category || '').toLowerCase() === 'ai'
          : (st.category || '').toLowerCase() !== 'ai'
      ))
  ), [strategyTypes, categoryTab]);

  const strategyStats = useMemo(() => ({
    total: strategies.length,
    enabled: strategies.filter((s) => s.enabled).length,
  }), [strategies]);

  const strategyTypeOptions = useMemo(() => selectableStrategyTypes.map((st) => ({
    value: st.typeCode,
    label: typeLabel(st),
  })), [selectableStrategyTypes, typeLabel]);

  // ============================================================
  // CRUD handlers
  // ============================================================

  const handleSelectType = (type: StrategyTypeInfo) => {
    const existing = findGlobalStrategy(strategies, type.typeCode);
    if (existing) {
      message.warning(t('strategy.global_strategy_exists_warning', {
        type: typeLabel(type),
        name: existing.strategyName,
        defaultValue: `已存在全局${typeLabel(type)}策略 "${existing.strategyName}"，只能创建路由级别的策略`,
      }));
    }
    setTypeSelectOpen(false);
    setCreatingType(type);
  };

  const handleCreateSubmit = async (body: StrategyDefinition) => {
    // Frontend guard for the one-global-per-type product rule.
    const existing = findGlobalStrategy(strategies, body.strategyType);
    if (body.scope === 'GLOBAL' && existing) {
      message.error(t('strategy.global_strategy_exists_error', {
        type: typeLabel(getStrategyType(body.strategyType)),
        name: existing.strategyName,
        defaultValue: `已存在全局${typeLabel(getStrategyType(body.strategyType))}策略 "${existing.strategyName}"，每个类型只允许创建一个全局策略`,
      }));
      return;
    }
    try {
      await createStrategy(body, instanceId);
      message.success(t('message.create_success'));
      setCreatingType(null);
      strategiesRequest.run();
    } catch (error) {
      message.error(errorMessage(error, 'message.create_failed', t));
    }
  };

  const handleEditSubmit = async (body: StrategyDefinition) => {
    if (!editingStrategy) return;
    try {
      await updateStrategy(editingStrategy.strategyId, body, instanceId);
      message.success(t('message.update_success'));
      setEditingStrategy(null);
      strategiesRequest.run();
    } catch (error) {
      message.error(errorMessage(error, 'message.update_failed', t));
    }
  };

  const handleDelete = async (strategy: StrategyDefinition) => {
    try {
      await deleteStrategy(strategy.strategyId, instanceId);
      message.success(t('message.delete_success'));
      strategiesRequest.run();
    } catch (error) {
      message.error(errorMessage(error, 'message.delete_failed', t));
    }
  };

  const handleToggle = async (strategy: StrategyDefinition) => {
    try {
      if (strategy.enabled) {
        await disableStrategy(strategy.strategyId, instanceId);
        message.success(t('message.disable_success'));
      } else {
        await enableStrategy(strategy.strategyId, instanceId);
        message.success(t('message.enable_success'));
      }
      strategiesRequest.run();
    } catch (error) {
      message.error(errorMessage(error, 'message.update_failed', t));
    }
  };

  const editingTypeInfo = editingStrategy
    ? (getStrategyType(editingStrategy.strategyType) ?? fallbackTypeInfo(editingStrategy.strategyType))
    : null;

  return (
    <div className="strategies-page">
      {/* Header with inline stat chips (clickable status filter, saves a full row) */}
      <div className="page-header-modern">
        <div className="page-header-left">
          <Title level={3} className="page-title-main">{t('strategy.title')}</Title>
          <Text type="secondary">{t('strategy.description')}</Text>
        </div>
        <div className="page-header-right">
          <div className="strategy-stats-inline">
            <div
              className={`stat-chip ${statusFilter === 'all' ? 'stat-chip-active' : ''}`}
              onClick={() => setStatusFilter('all')}
            >
              <span className="stat-chip-value">{strategyStats.total}</span>
              <span className="stat-chip-label">{t('strategy.stats_total')}</span>
            </div>
            <div
              className={`stat-chip ${statusFilter === 'enabled' ? 'stat-chip-active' : ''}`}
              onClick={() => setStatusFilter('enabled')}
            >
              <span className="stat-chip-value text-green-600">{strategyStats.enabled}</span>
              <span className="stat-chip-label">{t('strategy.stats_enabled')}</span>
            </div>
            <div
              className={`stat-chip ${statusFilter === 'disabled' ? 'stat-chip-active' : ''}`}
              onClick={() => setStatusFilter('disabled')}
            >
              <span className="stat-chip-value text-red-600">{strategyStats.total - strategyStats.enabled}</span>
              <span className="stat-chip-label">{t('strategy.stats_disabled')}</span>
            </div>
          </div>
          <Select value={filterType} onChange={setFilterType} style={{ width: 160 }}>
            <Select.Option value="all">{t('common.all_types')}</Select.Option>
            {strategyTypeOptions.map((opt) => (
              <Select.Option key={opt.value} value={opt.value}>{opt.label}</Select.Option>
            ))}
          </Select>
          <Select value={filterScope} onChange={setFilterScope} style={{ width: 140 }}>
            <Select.Option value="all">{t('common.all_scopes')}</Select.Option>
            <Select.Option value="GLOBAL">{t('strategy.scope_global')}</Select.Option>
            <Select.Option value="ROUTE">{t('strategy.scope_route')}</Select.Option>
          </Select>
          <Button type="primary" icon={<PlusOutlined />} onClick={() => setTypeSelectOpen(true)} className="create-btn">
            {t('strategy.create')}
          </Button>
        </div>
      </div>

      {/* Category tabs: normal vs AI strategies */}
      <Tabs
        activeKey={categoryTab}
        onChange={(k) => setCategoryTab(k as 'ALL' | 'NORMAL' | 'AI')}
        className="strategy-category-tabs"
        items={[
          { key: 'ALL', label: `${t('strategy.tab_all')} (${categoryCounts.all})` },
          {
            key: 'NORMAL',
            label: (<span><ThunderboltOutlined /> {t('strategy.tab_normal')} ({categoryCounts.normal})</span>),
          },
          {
            key: 'AI',
            label: (<span><RobotOutlined /> {t('strategy.tab_ai')} ({categoryCounts.ai})</span>),
          },
        ]}
      />

      {/* Strategies Grid */}
      <Spin spinning={strategiesRequest.loading}>
        {filteredStrategies.length === 0 ? (
          <Card className="empty-card">
            <Empty
              image={<ThunderboltOutlined className="empty-icon" />}
              description={<span className="empty-text">{t('strategy.empty')}</span>}
            >
              <Button type="primary" icon={<PlusOutlined />} onClick={() => setTypeSelectOpen(true)}>
                {t('strategy.create_first')}
              </Button>
            </Empty>
          </Card>
        ) : (
          <div className="strategies-grid">
            {filteredStrategies.map((strategy) => (
              <StrategyCard
                key={strategy.strategyId}
                strategy={strategy}
                typeInfo={getStrategyType(strategy.strategyType)}
                typeLabel={typeLabel(getStrategyType(strategy.strategyType), strategy.strategyType)}
                onEdit={setEditingStrategy}
                onToggle={handleToggle}
                onDelete={handleDelete}
              />
            ))}
          </div>
        )}
      </Spin>

      {/* Type selection modal (step 1 of create flow) */}
      {typeSelectOpen && (
        <StrategyTypeSelectModal
          strategyTypes={strategyTypes}
          initialTab={categoryTab === 'AI' ? 'ai' : 'general'}
          loading={typesRequest.loading}
          onSelect={handleSelectType}
          onCancel={() => setTypeSelectOpen(false)}
        />
      )}

      {/* Create modal (step 2 of create flow) */}
      {creatingType && (
        <StrategyFormModal
          mode="create"
          typeInfo={creatingType}
          routes={routes}
          existingGlobal={findGlobalStrategy(strategies, creatingType.typeCode)}
          onCancel={() => setCreatingType(null)}
          onBackToTypeSelect={() => {
            setCreatingType(null);
            setTypeSelectOpen(true);
          }}
          onSubmit={handleCreateSubmit}
        />
      )}

      {/* Edit modal */}
      {editingStrategy && editingTypeInfo && (
        <StrategyFormModal
          mode="edit"
          typeInfo={editingTypeInfo}
          strategy={editingStrategy}
          routes={routes}
          onCancel={() => setEditingStrategy(null)}
          onSubmit={handleEditSubmit}
        />
      )}
    </div>
  );
};

export default StrategiesPage;
