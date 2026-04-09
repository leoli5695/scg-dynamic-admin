import React, { useCallback } from 'react';
import { Card, Button, Space, Tag, Tooltip, Typography, Popconfirm } from 'antd';
import { 
  ClusterOutlined, 
  EyeOutlined, 
  ReloadOutlined, 
  DeleteOutlined,
  CheckCircleOutlined,
  CloseCircleOutlined,
  ExclamationCircleOutlined,
  ApiOutlined,
  CloudServerOutlined
} from '@ant-design/icons';
import { useTranslation } from 'react-i18next';

export interface KubernetesCluster {
  id: number;
  clusterName: string;
  serverUrl: string;
  kubeconfig?: string;
  contextName?: string;
  clusterVersion: string;
  connectionStatus: string;
  lastCheckedAt?: string;
  description?: string;
  enabled: boolean;
  createdAt?: string;
  updatedAt?: string;
  nodeCount?: number;
  podCount?: number;
  namespaceCount?: number;
  totalCpuCores?: number;
  totalMemoryGb?: number;
}

interface ClusterCardProps {
  cluster: KubernetesCluster;
  onDetail: (cluster: KubernetesCluster) => void;
  onRefresh: (cluster: KubernetesCluster) => void;
  onDelete: (cluster: KubernetesCluster) => void;
}

const { Text } = Typography;

export const ClusterCardPremium: React.FC<ClusterCardProps> = ({
  cluster,
  onDetail,
  onRefresh,
  onDelete
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

  const getStatusIcon = (status: string) => {
    switch (status) {
      case 'CONNECTED':
        return <CheckCircleOutlined />;
      case 'IMPORTED':
        return <ExclamationCircleOutlined />;
      case 'CONNECTION_FAILED':
      case 'ERROR':
        return <CloseCircleOutlined />;
      default:
        return <ExclamationCircleOutlined />;
    }
  };

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

  return (
    <div
      className={`cluster-card-premium ${getStatusClass(cluster.connectionStatus)}`}
      onClick={handleDetail}
      style={{ cursor: 'pointer' }}
    >
      {/* Card Header */}
      <div className="cluster-card-header">
        <div className={`cluster-card-icon ${getStatusClass(cluster.connectionStatus)}`}>
          <ClusterOutlined />
        </div>

        <div className="cluster-card-info">
          <div className="cluster-card-name">
            {cluster.clusterName}
            <div className={`cluster-card-status-dot-inline ${getStatusClass(cluster.connectionStatus)}`} />
          </div>
          <div className="cluster-card-meta">
            <span className="cluster-card-version">v{cluster.clusterVersion || 'N/A'}</span>
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

          <Popconfirm
            title={`${t('common.delete')}: ${cluster.clusterName}`}
            onConfirm={handleDelete}
            okText={t('common.confirm')}
            cancelText={t('common.cancel')}
          >
            <button
              className="cluster-card-action-btn danger"
              onClick={(e) => e.stopPropagation()}
            >
              <DeleteOutlined />
            </button>
          </Popconfirm>
        </div>
      </div>
    </div>
  );
};
