/**
 * Routes page (rewritten as a feature).
 * Fields aligned with RouteController + model/RouteDefinition.java.
 */
import React, { useCallback, useEffect, useMemo, useState } from 'react';
import {
  Button, Card, Empty, Pagination, Spin, Tabs  } from 'antd';
import { message, modal } from '../../utils/antdStatic';
import { CompassOutlined, PlusOutlined, RobotOutlined } from '@ant-design/icons';
import { useParams, useSearchParams } from 'react-router-dom';
import { useTranslation } from 'react-i18next';
import { useRequest } from '../../hooks/useRequest';
import { ApiError } from '../../api/http';
import {
  createRoute, deleteRoute, disableRoute, enableRoute, listRoutes, updateRoute,
} from '../../api/routes';
import { getNacosDiscoveryServices, listServices } from '../../api/services';
import type { RouteDefinition, RouteResponse } from '../../types/route';
import RouteCard from './components/RouteCard';
import RouteDetailDrawer from './components/RouteDetailDrawer';
import RouteFormModal from './components/RouteFormModal';
import RouteSearchBar, { RouteStatusFilter } from './components/RouteSearchBar';
import RouteStatsBar from './components/RouteStatsBar';
import './routes-premium.css';
import '../instance-detail/console-ui.css';

const errorMessage = (error: unknown, fallbackKey: string, t: (k: string) => string): string => (
  error instanceof ApiError && error.message ? error.message : t(fallbackKey)
);

const RoutesPage: React.FC = () => {
  const { t } = useTranslation();
  const { instanceId } = useParams<{ instanceId: string }>();
  const [searchParams, setSearchParams] = useSearchParams();

  const [searchTerm, setSearchTerm] = useState('');
  const [statusFilter, setStatusFilter] = useState<RouteStatusFilter>('all');
  // Category tab: ALL / NORMAL / AI (a route's category comes from the backend).
  const [categoryTab, setCategoryTab] = useState<'ALL' | 'NORMAL' | 'AI'>('ALL');
  const [currentPage, setCurrentPage] = useState(1);
  const [pageSize, setPageSize] = useState(12);
  const [selectedRoute, setSelectedRoute] = useState<RouteResponse | null>(null);
  const [editingRoute, setEditingRoute] = useState<RouteResponse | null>(null);
  const [createOpen, setCreateOpen] = useState(false);

  const routesRequest = useRequest(
    (signal) => listRoutes(instanceId ? { instanceId } : undefined, { signal }),
    { deps: [instanceId] },
  );
  const routes = useMemo(() => routesRequest.data ?? [], [routesRequest.data]);

  // Silent loads: service dropdowns are best-effort context for the form.
  const servicesRequest = useRequest(
    (signal) => listServices(instanceId ? { instanceId } : undefined, { signal }),
    { deps: [instanceId], onError: () => {} },
  );
  const services = useMemo(() => servicesRequest.data ?? [], [servicesRequest.data]);

  const nacosRequest = useRequest(
    (signal) => getNacosDiscoveryServices({ signal }),
    { onError: () => {} },
  );
  const nacosServices = useMemo(() => nacosRequest.data ?? [], [nacosRequest.data]);

  // Deep-link support: topology jumps here with ?highlight=<id|routeName>
  useEffect(() => {
    const highlightId = searchParams.get('highlight');
    if (highlightId && routes.length > 0) {
      const target = routes.find((r) => r.id === highlightId || r.routeName === highlightId);
      if (target) {
        // eslint-disable-next-line react-hooks/set-state-in-effect
        setSelectedRoute(target);
        // Clear the params so a refresh does not reopen the drawer.
        const next = new URLSearchParams(searchParams);
        next.delete('highlight');
        next.delete('from');
        setSearchParams(next, { replace: true });
      }
    }
  }, [routes, searchParams, setSearchParams]);

  // Reset pagination when filters change.
  useEffect(() => {
    // eslint-disable-next-line react-hooks/set-state-in-effect
    setCurrentPage(1);
  }, [searchTerm, statusFilter]);

  const filteredRoutes = useMemo(() => routes.filter((route) => {
    const term = searchTerm.toLowerCase();
    const matchesSearch = !term
      || route.id.toLowerCase().includes(term)
      || (route.routeName && route.routeName.toLowerCase().includes(term))
      || (route.uri && route.uri.toLowerCase().includes(term));
    const matchesStatus = statusFilter === 'all'
      || (statusFilter === 'enabled' && route.enabled)
      || (statusFilter === 'disabled' && !route.enabled);
    const isAi = (route.category || 'NORMAL') === 'AI';
    const matchesCategory = categoryTab === 'ALL'
      || (categoryTab === 'AI' ? isAi : !isAi);
    return matchesSearch && matchesStatus && matchesCategory;
  }), [routes, searchTerm, statusFilter, categoryTab]);

  const categoryCounts = useMemo(() => {
    const ai = routes.filter((r) => (r.category || 'NORMAL') === 'AI').length;
    return { all: routes.length, ai, normal: routes.length - ai };
  }, [routes]);

  const stats = useMemo(() => ({
    total: routes.length,
    enabled: routes.filter((r) => r.enabled).length,
    disabled: routes.filter((r) => !r.enabled).length,
  }), [routes]);

  const startIndex = (currentPage - 1) * pageSize;
  const paginatedRoutes = useMemo(
    () => filteredRoutes.slice(startIndex, startIndex + pageSize),
    [filteredRoutes, startIndex, pageSize],
  );

  const handleCreate = async (body: RouteDefinition): Promise<boolean> => {
    try {
      await createRoute(body, instanceId);
      message.success(t('routes.created_success'));
      routesRequest.run();
      servicesRequest.run();
      return true;
    } catch (error) {
      message.error(`${t('routes.create_failed')}: ${errorMessage(error, 'message.create_failed', t)}`);
      return false;
    }
  };

  const handleUpdate = async (body: RouteDefinition): Promise<boolean> => {
    if (!editingRoute) return false;
    try {
      await updateRoute(editingRoute.id, body);
      message.success(t('message.update_success'));
      routesRequest.run();
      servicesRequest.run();
      return true;
    } catch (error) {
      message.error(`${t('routes.update_failed')}: ${errorMessage(error, 'message.update_failed', t)}`);
      return false;
    }
  };

  const refreshRoutes = routesRequest.run;
  const handleDelete = useCallback((record: RouteResponse) => {
    modal.confirm({
      title: t('common.confirm'),
      content: t('message.confirm_delete_route', {
        name: record.routeName,
        routeId: record.id || 'N/A',
      }),
      okText: t('common.delete'),
      okType: 'danger',
      cancelText: t('common.cancel'),
      onOk: async () => {
        if (!record.id) {
          message.error(t('message.route_uuid_not_found'));
          return;
        }
        try {
          await deleteRoute(record.id);
          message.success(t('message.delete_success'));
          refreshRoutes();
          setSelectedRoute((current) => current?.id === record.id ? null : current);
        } catch (error) {
          message.error(`${t('routes.delete_failed')}: ${errorMessage(error, 'message.delete_failed', t)}`);
        }
      },
    });
  }, [refreshRoutes, t]);

  const handleToggle = useCallback(async (record: RouteResponse) => {
    if (!record.id) {
      message.error(t('routes.id_not_found'));
      return;
    }
    try {
      if (record.enabled) {
        await disableRoute(record.id);
        message.success(t('routes.disabled_success'));
      } else {
        await enableRoute(record.id);
        message.success(t('routes.enabled_success'));
      }
      refreshRoutes();
    } catch (error) {
      const key = record.enabled ? 'routes.disable_failed' : 'routes.enable_failed';
      message.error(`${t(key)}: ${errorMessage(error, key, t)}`);
    }
  }, [refreshRoutes, t]);

  const openEditModal = useCallback((record: RouteResponse) => {
    // Close the drawer first, then open the edit modal.
    setSelectedRoute(null);
    setEditingRoute(record);
  }, []);

  return (
    <div className="routes-page console-resource-page">
      <RouteSearchBar
        searchTerm={searchTerm}
        onSearchChange={setSearchTerm}
        statusFilter={statusFilter}
        onStatusFilterChange={setStatusFilter}
        onCreateClick={() => setCreateOpen(true)}
        stats={<RouteStatsBar total={stats.total} enabled={stats.enabled} disabled={stats.disabled} />}
      />

      {/* Category tabs: normal vs AI routes */}
      <Tabs
        activeKey={categoryTab}
        onChange={(k) => {
          setCategoryTab(k as 'ALL' | 'NORMAL' | 'AI');
          setCurrentPage(1);
        }}
        className="route-category-tabs"
        items={[
          { key: 'ALL', label: `${t('routes.tab_all')} (${categoryCounts.all})` },
          {
            key: 'NORMAL',
            label: (<span><CompassOutlined /> {t('routes.tab_normal')} ({categoryCounts.normal})</span>),
          },
          {
            key: 'AI',
            label: (<span><RobotOutlined /> {t('routes.tab_ai')} ({categoryCounts.ai})</span>),
          },
        ]}
      />

      <Spin spinning={routesRequest.loading}>
        {filteredRoutes.length === 0 ? (
          <Card className="empty-card">
            <Empty
              image={<CompassOutlined className="empty-icon" />}
              description={<><span className="empty-text">{t('routes.' + (routes.length ? 'no_matches' : 'empty_description'))}</span><p className="empty-hint">{t('routes.' + (routes.length ? 'filter_hint' : 'empty_hint'))}</p></>}
            >
              {routes.length > 0 ? <Button onClick={() => { setSearchTerm(''); setStatusFilter('all'); setCategoryTab('ALL'); }}>{t('routes.clear_filters')}</Button> : <Button type="primary" icon={<PlusOutlined />} onClick={() => setCreateOpen(true)}>
                {t('routes.create_first')}
              </Button>}
            </Empty>
          </Card>
        ) : (
          <>
            <div className="routes-grid">
              {paginatedRoutes.map((route) => (
                <RouteCard
                  key={route.id}
                  route={route}
                  onEdit={openEditModal}
                  onDetail={setSelectedRoute}
                  onToggle={handleToggle}
                  onDelete={handleDelete}
                />
              ))}
            </div>
            {filteredRoutes.length > pageSize && (
              <div className="pagination-container">
                <Pagination
                  current={currentPage}
                  pageSize={pageSize}
                  total={filteredRoutes.length}
                  onChange={(page, size) => {
                    setCurrentPage(page);
                    setPageSize(size);
                  }}
                  showSizeChanger
                  showQuickJumper
                  showTotal={(total) => t('routes.pagination_total', { total })}
                  pageSizeOptions={['12', '24', '48', '96']}
                />
              </div>
            )}
          </>
        )}
      </Spin>

      {/* Detail drawer */}
      {selectedRoute && (
        <RouteDetailDrawer
          route={selectedRoute}
          onClose={() => setSelectedRoute(null)}
          onEdit={openEditModal}
          onDelete={handleDelete}
        />
      )}

      {/* Create modal */}
      {createOpen && (
        <RouteFormModal
          mode="create"
          services={services}
          nacosServices={nacosServices}
          onCancel={() => setCreateOpen(false)}
          onSubmit={handleCreate}
        />
      )}

      {/* Edit modal */}
      {editingRoute && (
        <RouteFormModal
          mode="edit"
          route={editingRoute}
          services={services}
          nacosServices={nacosServices}
          onCancel={() => setEditingRoute(null)}
          onSubmit={handleUpdate}
        />
      )}
    </div>
  );
};

export default RoutesPage;
