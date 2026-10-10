import React, { useCallback } from 'react';
import { Tooltip } from 'antd';
import {
  CloudServerOutlined,
  ClusterOutlined,
  DeleteOutlined,
  EyeOutlined,
  ReloadOutlined,
} from '@ant-design/icons';
import { useTranslation } from 'react-i18next';
import type { KubernetesCluster } from '../../../types/kubernetes';

interface ClusterCardProps {
  cluster: KubernetesCluster;
  onDetail: (cluster: KubernetesCluster) => void;
  onRefresh: (cluster: KubernetesCluster) => void;
  onDelete: (cluster: KubernetesCluster) => void;
}

const getStatusClass = (status: string) => {
  switch (status) {
    case 'CONNECTED':
      return 'connected';
    case 'CONNECTION_FAILED':
    case 'ERROR':
      return 'error';
    default:
      return '';
  }
};

const getStatusKey = (statusClass: string) => {
  switch (statusClass) {
    case 'connected':
      return 'k8s.status_connected';
    case 'error':
      return 'k8s.status_failed';
    default:
      return 'k8s.status_unknown';
  }
};

export const ClusterCard: React.FC<ClusterCardProps> = ({
  cluster,
  onDetail,
  onRefresh,
  onDelete,
}) => {
  const { t } = useTranslation();

  const handleDetail = useCallback((e?: React.MouseEvent) => {
    e?.stopPropagation();
    onDetail(cluster);
  }, [onDetail, cluster]);

  const handleRefresh = useCallback((e?: React.MouseEvent) => {
    e?.stopPropagation();
    onRefresh(cluster);
  }, [onRefresh, cluster]);

  const handleDelete = useCallback((e?: React.MouseEvent) => {
    e?.stopPropagation();
    onDelete(cluster);
  }, [onDelete, cluster]);

  const statusClass = getStatusClass(cluster.connectionStatus);

  return (
    <div
      className={`cluster-card-premium ${statusClass}`}
      onClick={handleDetail}
      style={{ cursor: 'pointer' }}
    >
      <div className="cluster-card-header">
        <div className={`cluster-card-icon ${statusClass}`}>
          <ClusterOutlined />
        </div>

        <div className="cluster-card-info">
          <div className="cluster-card-name">
            {cluster.clusterName}
            <div className={`cluster-card-status-dot-inline ${statusClass}`} />
            <span className={`cluster-card-status-text ${statusClass}`}>
              {t(getStatusKey(statusClass))}
            </span>
          </div>
          <div className="cluster-card-meta">
            <span className="cluster-card-version">
              {cluster.clusterVersion ? `v${cluster.clusterVersion}` : t('k8s.version_unknown')}
            </span>
            <span className="cluster-card-divider">·</span>
            <span className="cluster-card-stats">
              <CloudServerOutlined style={{ marginRight: 4 }} />
              {cluster.nodeCount || 0} {t('k8s.nodes')}
            </span>
            <span className="cluster-card-divider">·</span>
            <span className="cluster-card-stats">
              {cluster.podCount || 0} Pods
            </span>
          </div>
        </div>

        <div className="cluster-card-actions">
          <Tooltip title={t('common.detail')}>
            <button
              className="cluster-card-action-btn primary"
              onClick={handleDetail}
            >
              <EyeOutlined />
            </button>
          </Tooltip>

          <Tooltip title={t('common.refresh')}>
            <button
              className="cluster-card-action-btn"
              onClick={handleRefresh}
            >
              <ReloadOutlined />
            </button>
          </Tooltip>

          {/* Confirmation happens via Modal.confirm in the page. */}
          <Tooltip title={t('common.delete')}>
            <button
              className="cluster-card-action-btn danger"
              onClick={handleDelete}
            >
              <DeleteOutlined />
            </button>
          </Tooltip>
        </div>
      </div>
    </div>
  );
};
