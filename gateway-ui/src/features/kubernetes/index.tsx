import React, { useCallback, useEffect, useMemo, useState } from 'react';
import { Alert, Button, Input, Select, Spin } from 'antd';
import { message, modal } from '../../utils/antdStatic';
import {
  CheckCircleOutlined,
  CloudServerOutlined,
  ClusterOutlined,
  DashboardOutlined,
  PlusOutlined,
  ReloadOutlined,
  SearchOutlined,
} from '@ant-design/icons';
import { useTranslation } from 'react-i18next';
import { deleteCluster, getClusters, refreshClusterConnection } from '../../api/kubernetes';
import { ApiError } from '../../api/http';
import type { KubernetesCluster } from '../../types/kubernetes';
import { ManagementMetric, ManagementPageHeader } from '../layout/components/ManagementPage';
import { ClusterCard } from './components/ClusterCard';
import { ImportClusterModal } from './components/ImportClusterModal';
import { ClusterDetailDrawer } from './components/ClusterDetailDrawer';
import './kubernetes.css';
import './overview.css';

const KubernetesPage: React.FC = () => {
  const { t } = useTranslation();
  const [clusters, setClusters] = useState<KubernetesCluster[]>([]);
  const [loading, setLoading] = useState(true);
  const [importModalVisible, setImportModalVisible] = useState(false);
  const [selectedCluster, setSelectedCluster] = useState<KubernetesCluster | null>(null);
  const [search, setSearch] = useState('');
  const [connectionFilter, setConnectionFilter] = useState('all');
  const [loadError, setLoadError] = useState(false);

  const errorMessage = useCallback(
    (e: unknown, fallback: string) => (e instanceof ApiError && e.message ? e.message : fallback),
    [],
  );

  const loadClusters = useCallback(async () => {
    setLoading(true);
    setLoadError(false);
    try {
      const data = await getClusters();
      setClusters(data);
    } catch {
      setLoadError(true);
      message.error(t('k8s.load_failed'));
    } finally {
      setLoading(false);
    }
  }, [t]);

  useEffect(() => {
    (async () => {
      await loadClusters();
    })();
  }, [loadClusters]);

  const handleRefreshConnection = useCallback(
    async (cluster: KubernetesCluster) => {
      message.loading({ content: t('k8s.refreshing'), key: 'refresh', duration: 0 });
      try {
        const refreshed = await refreshClusterConnection(cluster.id);
        message.success({ content: t('k8s.refresh_success'), key: 'refresh' });
        setClusters((prev) => prev.map((c) => (c.id === refreshed.id ? refreshed : c)));
        setSelectedCluster((prev) => (prev && prev.id === refreshed.id ? refreshed : prev));
      } catch (e) {
        message.error({ content: errorMessage(e, t('k8s.refresh_failed')), key: 'refresh' });
      }
    },
    [t, errorMessage],
  );

  const handleDeleteCluster = useCallback(
    (cluster: KubernetesCluster) => {
      modal.confirm({
        title: t('k8s.delete_confirm_title'),
        content: t('k8s.delete_confirm_content', { name: cluster.clusterName }),
        okType: 'danger',
        onOk: async () => {
          try {
            await deleteCluster(cluster.id);
            message.success(t('k8s.delete_success'));
            await loadClusters();
          } catch (e) {
            message.error(errorMessage(e, t('k8s.delete_failed')));
          }
        },
      });
    },
    [loadClusters, t, errorMessage],
  );

  const stats = useMemo(() => {
    const total = clusters.length;
    const connected = clusters.filter((c) => c.connectionStatus === 'CONNECTED').length;
    const totalNodes = clusters.reduce((sum, c) => sum + (c.nodeCount || 0), 0);
    const totalPods = clusters.reduce((sum, c) => sum + (c.podCount || 0), 0);
    return { total, connected, totalNodes, totalPods };
  }, [clusters]);

  const visibleClusters = useMemo(
    () =>
      clusters.filter((cluster) => {
        const query = search.trim().toLowerCase();
        const matchesStatus =
          connectionFilter === 'all' ||
          (connectionFilter === 'connected'
            ? cluster.connectionStatus === 'CONNECTED'
            : cluster.connectionStatus !== 'CONNECTED');
        return (
          matchesStatus &&
          (!query ||
            [cluster.clusterName, cluster.clusterVersion].some((value) =>
              value?.toLowerCase().includes(query),
            ))
        );
      }),
    [clusters, search, connectionFilter],
  );

  return (
    <div className="kubernetes-page management-page">
      {/* Page Header */}
      <ManagementPageHeader
        title={t('k8s.title')}
        subtitle={t('k8s.description')}
        eyebrow={t('k8s.workspace_label')}
        actions={
          <>
            <Button icon={<ReloadOutlined />} onClick={loadClusters} loading={loading}>
              {t('common.refresh')}
            </Button>
            <Button
              type="primary"
              icon={<PlusOutlined />}
              onClick={() => setImportModalVisible(true)}
              size="large"
            >
              {t('k8s.import_cluster')}
            </Button>
          </>
        }
      />

      {/* Stats Cards */}
      <div className="management-metrics">
        <ManagementMetric
          title={t('k8s.stats_total')}
          value={loading && clusters.length === 0 ? '—' : stats.total}
          icon={<ClusterOutlined />}
          subtitle={t('k8s.clusters')}
        />
        <ManagementMetric
          title={t('k8s.stats_connected')}
          value={loading && clusters.length === 0 ? '—' : stats.connected}
          icon={<CheckCircleOutlined />}
          tone="green"
          subtitle={t('k8s.active_clusters')}
        />
        <ManagementMetric
          title={t('k8s.stats_nodes')}
          value={loading && clusters.length === 0 ? '—' : stats.totalNodes}
          icon={<CloudServerOutlined />}
          subtitle={t('k8s.total_nodes')}
        />
        <ManagementMetric
          title={t('k8s.stats_pods')}
          value={loading && clusters.length === 0 ? '—' : stats.totalPods}
          icon={<DashboardOutlined />}
          tone="purple"
          subtitle={t('k8s.running_pods')}
        />
      </div>

      <div className="management-section-heading">
        <h3>
          {t('k8s.cluster_list')}
          <span className="management-count">{visibleClusters.length}</span>
        </h3>
        <div className="management-toolbar">
          <Input
            className="management-search"
            prefix={<SearchOutlined />}
            value={search}
            allowClear
            onChange={(event) => setSearch(event.target.value)}
            placeholder={t('k8s.search_placeholder')}
            aria-label={t('k8s.search_placeholder')}
          />
          <Select
            value={connectionFilter}
            onChange={setConnectionFilter}
            style={{ minWidth: 130 }}
            aria-label={t('k8s.connection_filter')}
            options={[
              { value: 'all', label: t('k8s.all_connections') },
              { value: 'connected', label: t('k8s.status_connected') },
              { value: 'other', label: t('k8s.needs_attention') },
            ]}
          />
        </div>
      </div>
      {loadError && (
        <Alert
          type="error"
          showIcon
          message={t('k8s.load_failed')}
          action={
            <Button size="small" onClick={loadClusters}>
              {t('common.refresh')}
            </Button>
          }
        />
      )}

      {/* Cluster Cards */}
      <Spin spinning={loading && clusters.length === 0}>
        {clusters.length === 0 && !loadError ? (
          <div className="management-empty">
            <span className="management-empty-icon">
              <ClusterOutlined />
            </span>
            <h3>{t('k8s.empty')}</h3>
            <p>{t('k8s.empty_description')}</p>
            <Button
              type="primary"
              icon={<PlusOutlined />}
              onClick={() => setImportModalVisible(true)}
              size="large"
              className="management-primary"
            >
              {t('k8s.import_first')}
            </Button>
          </div>
        ) : visibleClusters.length > 0 ? (
          <>
            <div className="cluster-grid">
              {visibleClusters.map((cluster) => (
                <ClusterCard
                  key={cluster.id}
                  cluster={cluster}
                  onDetail={setSelectedCluster}
                  onRefresh={handleRefreshConnection}
                  onDelete={handleDeleteCluster}
                />
              ))}
            </div>
          </>
        ) : (
          !loadError && (
            <div className="management-empty">
              <h3>{t('k8s.no_matches')}</h3>
              <Button
                onClick={() => {
                  setSearch('');
                  setConnectionFilter('all');
                }}
              >
                {t('k8s.reset_filters')}
              </Button>
            </div>
          )
        )}
      </Spin>

      <ImportClusterModal
        open={importModalVisible}
        onClose={() => setImportModalVisible(false)}
        onImported={loadClusters}
      />

      <ClusterDetailDrawer cluster={selectedCluster} onClose={() => setSelectedCluster(null)} />
    </div>
  );
};

export default KubernetesPage;
