import React, { useCallback, useEffect, useMemo, useState } from 'react';
import { Button, Spin, Typography } from 'antd';
import { message, modal } from '../../utils/antdStatic';
import {
  CheckCircleOutlined, CloudServerOutlined, ClusterOutlined, DashboardOutlined, PlusOutlined, ReloadOutlined,
} from '@ant-design/icons';
import { useTranslation } from 'react-i18next';
import { deleteCluster, getClusters, refreshClusterConnection } from '../../api/kubernetes';
import { ApiError } from '../../api/http';
import type { KubernetesCluster } from '../../types/kubernetes';
import { StatsCard } from './components/StatsCard';
import { ClusterCard } from './components/ClusterCard';
import { ImportClusterModal } from './components/ImportClusterModal';
import { ClusterDetailDrawer } from './components/ClusterDetailDrawer';
import './kubernetes.css';

const { Text, Title } = Typography;

const KubernetesPage: React.FC = () => {
  const { t } = useTranslation();
  const [clusters, setClusters] = useState<KubernetesCluster[]>([]);
  const [loading, setLoading] = useState(true);
  const [importModalVisible, setImportModalVisible] = useState(false);
  const [selectedCluster, setSelectedCluster] = useState<KubernetesCluster | null>(null);

  const errorMessage = useCallback((e: unknown, fallback: string) => (
    e instanceof ApiError && e.message ? e.message : fallback
  ), []);

  const loadClusters = useCallback(async () => {
    setLoading(true);
    try {
      const data = await getClusters();
      setClusters(data);
    } catch {
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

  const handleRefreshConnection = useCallback(async (cluster: KubernetesCluster) => {
    message.loading({ content: t('k8s.refreshing'), key: 'refresh', duration: 0 });
    try {
      const refreshed = await refreshClusterConnection(cluster.id);
      message.success({ content: t('k8s.refresh_success'), key: 'refresh' });
      setClusters(prev => prev.map(c => (c.id === refreshed.id ? refreshed : c)));
      setSelectedCluster(prev => (prev && prev.id === refreshed.id ? refreshed : prev));
    } catch (e) {
      message.error({ content: errorMessage(e, t('k8s.refresh_failed')), key: 'refresh' });
    }
  }, [t, errorMessage]);

  const handleDeleteCluster = useCallback((cluster: KubernetesCluster) => {
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
  }, [loadClusters, t, errorMessage]);

  const stats = useMemo(() => {
    const total = clusters.length;
    const connected = clusters.filter(c => c.connectionStatus === 'CONNECTED').length;
    const totalNodes = clusters.reduce((sum, c) => sum + (c.nodeCount || 0), 0);
    const totalPods = clusters.reduce((sum, c) => sum + (c.podCount || 0), 0);
    return { total, connected, totalNodes, totalPods };
  }, [clusters]);

  return (
    <div className="page-container kubernetes-page">
      {/* Page Header */}
      <div className="k8s-page-header">
        <div className="k8s-page-header-left">
          <div className="k8s-page-title">
            <ClusterOutlined />
            <Title level={3} style={{ margin: 0, color: '#f1f5f9' }}>{t('k8s.title')}</Title>
          </div>
          <Text className="k8s-page-subtitle">{t('k8s.description')}</Text>
        </div>
        <div className="k8s-page-actions">
          <Button
            icon={<ReloadOutlined />}
            onClick={loadClusters}
            loading={loading}
          >
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
        </div>
      </div>

      {/* Stats Cards */}
      <div className="k8s-stats-grid">
        <StatsCard
          title={t('k8s.stats_total')}
          value={stats.total}
          icon={<ClusterOutlined />}
          iconType="default"
          subtitle={t('k8s.clusters')}
        />
        <StatsCard
          title={t('k8s.stats_connected')}
          value={stats.connected}
          icon={<CheckCircleOutlined />}
          iconType="success"
          subtitle={t('k8s.active_clusters')}
        />
        <StatsCard
          title={t('k8s.stats_nodes')}
          value={stats.totalNodes}
          icon={<CloudServerOutlined />}
          iconType="default"
          subtitle={t('k8s.total_nodes')}
        />
        <StatsCard
          title={t('k8s.stats_pods')}
          value={stats.totalPods}
          icon={<DashboardOutlined />}
          iconType="default"
          subtitle={t('k8s.running_pods')}
        />
      </div>

      {/* Cluster Cards */}
      <Spin spinning={loading}>
        {clusters.length === 0 ? (
          <div className="k8s-empty">
            <ClusterOutlined className="k8s-empty-icon" />
            <Text className="k8s-empty-text">{t('k8s.empty')}</Text>
            <Button
              type="primary"
              icon={<PlusOutlined />}
              onClick={() => setImportModalVisible(true)}
              size="large"
            >
              {t('k8s.import_first')}
            </Button>
          </div>
        ) : (
          <>
            <div className="k8s-section-header">
              <Title level={5} style={{ margin: 0 }}>{t('k8s.cluster_list')}</Title>
              <Text className="k8s-section-count">{t('k8s.cluster_count', { count: clusters.length })}</Text>
            </div>
            <div className="cluster-grid">
              {clusters.map(cluster => (
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
        )}
      </Spin>

      <ImportClusterModal
        open={importModalVisible}
        onClose={() => setImportModalVisible(false)}
        onImported={loadClusters}
      />

      <ClusterDetailDrawer
        cluster={selectedCluster}
        onClose={() => setSelectedCluster(null)}
      />
    </div>
  );
};

export default KubernetesPage;
