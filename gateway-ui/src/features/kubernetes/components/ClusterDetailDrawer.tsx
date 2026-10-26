import React, { useCallback, useEffect, useMemo, useState } from 'react';
import {
  Button, Card, Drawer, InputNumber, Progress, Select, Space, Table, Tabs, Tag, Tooltip, Typography  } from 'antd';
import { message, modal } from '../../../utils/antdStatic';
import {
  ClusterOutlined, DeleteOutlined, ExpandOutlined, EyeOutlined, PlayCircleOutlined, ReloadOutlined,
} from '@ant-design/icons';
import type { ColumnType } from 'antd/es/table';
import { useTranslation } from 'react-i18next';
import { parseServerTime } from '../../../utils/serverTime';
import {
  deleteDeployment, getClusterDeployments, getClusterNamespaces, getClusterNodes, getClusterPods, scaleDeployment,
} from '../../../api/kubernetes';
import { ApiError } from '../../../api/http';
import type {
  KubernetesCluster, KubernetesDeploymentInfo, KubernetesNodeInfo, KubernetesPodInfo,
} from '../../../types/kubernetes';
import { DeployGatewayModal } from './DeployGatewayModal';
import { PodDetailModal } from './PodDetailModal';

const { Text } = Typography;

const DEFAULT_NAMESPACE = 'default';

const getStatusColor = (status: string) => {
  switch (status) {
    case 'CONNECTED': return 'green';
    case 'IMPORTED': return 'blue';
    case 'CONNECTION_FAILED': return 'red';
    case 'ERROR': return 'red';
    default: return 'gray';
  }
};

/** Static cluster metadata shown on the overview tab. */
const ClusterInfo: React.FC<{ cluster: KubernetesCluster }> = ({ cluster }) => {
  const { t } = useTranslation();
  const fallback = <Text type="secondary">-</Text>;
  return (
    <div className="k8s-descriptions">
      <div className="k8s-description-item">
        <span className="k8s-description-label">{t('k8s.server_url')}</span>
        <span className="k8s-description-value">
          {cluster.serverUrl
            ? <Text copyable style={{ color: '#f1f5f9' }}>{cluster.serverUrl}</Text>
            : fallback}
        </span>
      </div>
      <div className="k8s-description-item">
        <span className="k8s-description-label">{t('k8s.context')}</span>
        <span className="k8s-description-value">{cluster.contextName || fallback}</span>
      </div>
      <div className="k8s-description-item">
        <span className="k8s-description-label">{t('k8s.cluster_version')}</span>
        <span className="k8s-description-value">
          {cluster.clusterVersion ? <Tag color="blue">v{cluster.clusterVersion}</Tag> : fallback}
        </span>
      </div>
      <div className="k8s-description-item">
        <span className="k8s-description-label">{t('k8s.last_checked')}</span>
        <span className="k8s-description-value">
          {cluster.lastCheckedAt ? parseServerTime(cluster.lastCheckedAt).toLocaleString() : fallback}
        </span>
      </div>
    </div>
  );
};

interface ClusterDetailDrawerProps {
  cluster: KubernetesCluster | null;
  onClose: () => void;
}

export const ClusterDetailDrawer: React.FC<ClusterDetailDrawerProps> = ({ cluster, onClose }) => {
  const { t } = useTranslation();

  const [nodes, setNodes] = useState<KubernetesNodeInfo[]>([]);
  const [namespaces, setNamespaces] = useState<string[]>([]);
  const [deployments, setDeployments] = useState<KubernetesDeploymentInfo[]>([]);
  const [pods, setPods] = useState<KubernetesPodInfo[]>([]);
  const [selectedNamespace, setSelectedNamespace] = useState(DEFAULT_NAMESPACE);
  const [activeTab, setActiveTab] = useState('overview');
  const [deployModalVisible, setDeployModalVisible] = useState(false);
  const [selectedPod, setSelectedPod] = useState<KubernetesPodInfo | null>(null);

  const errorMessage = useCallback((e: unknown, fallback: string) => (
    e instanceof ApiError && e.message ? e.message : fallback
  ), []);

  // Namespace is passed explicitly so the callback stays referentially stable.
  const loadClusterResources = useCallback(async (clusterId: number, namespace: string) => {
    try {
      const [nodeData, namespaceData] = await Promise.all([
        getClusterNodes(clusterId),
        getClusterNamespaces(clusterId),
      ]);
      setNodes(nodeData);
      setNamespaces(namespaceData);

      const [deploymentData, podData] = await Promise.all([
        getClusterDeployments(clusterId, namespace),
        getClusterPods(clusterId, namespace),
      ]);
      setDeployments(deploymentData);
      setPods(podData);
    } catch {
      // Keep previous data; connection errors surface via cluster status.
    }
  }, []);

  useEffect(() => {
    if (!cluster) return;
    (async () => {
      await loadClusterResources(cluster.id, DEFAULT_NAMESPACE);
    })();
  }, [cluster, loadClusterResources]);

  const handleNamespaceChange = useCallback((ns: string) => {
    setSelectedNamespace(ns);
    if (cluster) {
      void loadClusterResources(cluster.id, ns);
    }
  }, [cluster, loadClusterResources]);

  const handleScaleDeployment = useCallback(async (deployment: KubernetesDeploymentInfo, newReplicas: number) => {
    if (!cluster) return;
    const hide = message.loading({ content: t('k8s.scaling'), duration: 0 });
    try {
      await scaleDeployment(cluster.id, deployment.namespace, deployment.name, newReplicas);
      hide();
      message.success(t('k8s.scale_success'));
      await loadClusterResources(cluster.id, deployment.namespace);
    } catch (e) {
      hide();
      message.error(errorMessage(e, t('k8s.scale_failed')));
    }
  }, [cluster, loadClusterResources, t, errorMessage]);

  const handleDeleteDeployment = useCallback((deployment: KubernetesDeploymentInfo) => {
    if (!cluster) return;
    modal.confirm({
      title: t('k8s.delete_deployment_confirm_title'),
      content: t('k8s.delete_deployment_confirm_content', { name: deployment.name }),
      okType: 'danger',
      okText: t('common.delete'),
      cancelText: t('common.cancel'),
      onOk: async () => {
        try {
          await deleteDeployment(cluster.id, deployment.namespace, deployment.name);
          message.success(t('k8s.delete_deployment_success'));
          await loadClusterResources(cluster.id, deployment.namespace);
        } catch (e) {
          message.error(errorMessage(e, t('k8s.delete_deployment_failed')));
        }
      },
    });
  }, [cluster, loadClusterResources, t, errorMessage]);

  const deploymentColumns: ColumnType<KubernetesDeploymentInfo>[] = useMemo(() => [
    {
      title: t('k8s.deployment_name'),
      dataIndex: 'name',
      key: 'name',
      render: (name: string) => <Text strong style={{ color: '#fafafa' }}>{name}</Text>,
    },
    {
      title: t('k8s.namespace'),
      dataIndex: 'namespace',
      key: 'namespace',
    },
    {
      title: t('k8s.replicas'),
      dataIndex: 'replicas',
      key: 'replicas',
      render: (replicas: number, record) => {
        const ready = record.readyReplicas || 0;
        const percentage = replicas > 0 ? (ready / replicas) * 100 : 0;
        return (
          <Space>
            <Progress
              percent={percentage}
              size="small"
              status={ready === replicas ? 'success' : 'active'}
              style={{ width: 60 }}
            />
            <Text style={{ color: '#a1a1aa' }}>{ready}/{replicas}</Text>
          </Space>
        );
      },
    },
    {
      title: t('common.actions'),
      key: 'actions',
      width: 120,
      render: (_, record) => (
        <Space style={{ display: 'inline-flex', alignItems: 'center', gap: '4px' }}>
          <Tooltip title={t('k8s.scale')}>
            <Button
              type="text"
              size="small"
              icon={<ExpandOutlined />}
              onClick={(e) => {
                e.stopPropagation();
                let newValue: number | null = null;
                modal.confirm({
                  title: <span style={{ color: '#fafafa', fontWeight: 600 }}>{t('k8s.scale_title')}</span>,
                  content: (
                    <div style={{ padding: '20px 0' }}>
                      <InputNumber
                        min={1}
                        max={10}
                        defaultValue={record.replicas}
                        onChange={(value) => { newValue = value; }}
                        style={{ width: '100%' }}
                        autoFocus
                      />
                    </div>
                  ),
                  okText: t('common.confirm'),
                  cancelText: t('common.cancel'),
                  onOk: () => handleScaleDeployment(record, newValue ?? record.replicas),
                });
              }}
            />
          </Tooltip>
          <Tooltip title={t('common.delete')}>
            <Button
              type="text"
              size="small"
              danger
              icon={<DeleteOutlined />}
              onClick={(e) => {
                e.stopPropagation();
                handleDeleteDeployment(record);
              }}
            />
          </Tooltip>
        </Space>
      ),
    },
  ], [t, handleScaleDeployment, handleDeleteDeployment]);

  const podColumns: ColumnType<KubernetesPodInfo>[] = useMemo(() => [
    {
      title: t('k8s.pod_name'),
      dataIndex: 'name',
      key: 'name',
      render: (name: string) => <span className="pod-name-link">{name}</span>,
    },
    {
      title: t('k8s.namespace'),
      dataIndex: 'namespace',
      key: 'namespace',
    },
    {
      title: t('k8s.phase'),
      dataIndex: 'phase',
      key: 'phase',
      render: (phase: string) => (
        <Tag color={phase === 'Running' ? 'green' : phase === 'Pending' ? 'blue' : 'red'}>
          {phase}
        </Tag>
      ),
    },
    {
      title: t('k8s.pod_ip'),
      dataIndex: 'podIP',
      key: 'podIP',
    },
    {
      title: t('k8s.containers'),
      dataIndex: 'containers',
      key: 'containers',
      render: (containers: KubernetesPodInfo['containers']) => (
        <Space direction="vertical" size="small" style={{ margin: 0 }}>
          {containers?.map(c => (
            <Tag key={c.name} className="pod-container-tag" color={c.ready ? 'green' : 'red'}>
              {c.name} ({c.restartCount})
            </Tag>
          ))}
        </Space>
      ),
    },
    {
      title: t('common.actions'),
      key: 'actions',
      width: 80,
      render: (_, record) => (
        <Tooltip title={t('common.detail')}>
          <Button
            type="text"
            size="small"
            icon={<EyeOutlined />}
            onClick={() => setSelectedPod(record)}
          />
        </Tooltip>
      ),
    },
  ], [t]);

  const namespaceSelector = (
    <div style={{ marginBottom: 16 }}>
      <Select
        value={selectedNamespace}
        onChange={handleNamespaceChange}
        options={[
          { label: t('k8s.all_namespaces'), value: '' },
          ...namespaces.map(ns => ({ label: ns, value: ns })),
        ]}
        className="namespace-select"
        getPopupContainer={trigger => trigger.parentNode}
      />
    </div>
  );

  return (
    <Drawer
      title={
        <Space>
          <ClusterOutlined />
          {cluster?.clusterName}
          {cluster && (
            <Tag color={getStatusColor(cluster.connectionStatus)}>
              {cluster.connectionStatus}
            </Tag>
          )}
        </Space>
      }
      placement="right"
      width={720}
      open={!!cluster}
      onClose={onClose}
      destroyOnHidden
      style={{ zIndex: 999 }}
      extra={
        <Space>
          <Button
            icon={<PlayCircleOutlined />}
            onClick={() => setDeployModalVisible(true)}
            disabled={cluster?.connectionStatus !== 'CONNECTED'}
          >
            {t('k8s.deploy_gateway')}
          </Button>
          <Button
            icon={<ReloadOutlined />}
            onClick={() => cluster && loadClusterResources(cluster.id, selectedNamespace)}
          >
            {t('common.refresh')}
          </Button>
        </Space>
      }
    >
      {cluster && (
        <Tabs
          activeKey={activeTab}
          onChange={setActiveTab}
          items={[
            {
              key: 'overview',
              label: t('k8s.overview'),
              children: (
                <div>
                  <Card size="small" title={t('k8s.cluster_info')} className="k8s-info-card" style={{ marginBottom: 16 }}>
                    <ClusterInfo cluster={cluster} />
                  </Card>

                  <Card size="small" title={t('k8s.nodes')} className="k8s-info-card">
                    <Table
                      dataSource={nodes}
                      columns={[
                        {
                          title: t('k8s.node_name'),
                          dataIndex: 'name',
                          render: (name: string) => <Text strong style={{ color: '#f1f5f9' }}>{name}</Text>,
                        },
                        {
                          title: t('k8s.node_status'),
                          dataIndex: 'status',
                          render: (s: string) => <Tag color={s === 'True' ? 'green' : 'red'}>{s}</Tag>,
                        },
                        {
                          title: t('k8s.kubelet_version'),
                          dataIndex: 'kubeletVersion',
                          render: (v: string) => <Text style={{ fontFamily: 'JetBrains Mono, monospace', fontSize: 12 }}>{v}</Text>,
                        },
                        { title: t('k8s.os_image'), dataIndex: 'osImage' },
                      ]}
                      rowKey="name"
                      size="small"
                      pagination={false}
                    />
                  </Card>
                </div>
              ),
            },
            {
              key: 'deployments',
              label: t('k8s.deployments'),
              children: (
                <div className="k8s-deployments-container">
                  {namespaceSelector}
                  <div className="k8s-table-container">
                    <Table
                      dataSource={deployments}
                      columns={deploymentColumns}
                      rowKey="name"
                      size="small"
                      pagination={{ pageSize: 10 }}
                    />
                  </div>
                </div>
              ),
            },
            {
              key: 'pods',
              label: t('k8s.pods'),
              children: (
                <div className="k8s-pods-container">
                  {namespaceSelector}
                  <div className="k8s-table-container">
                    <Table
                      dataSource={pods}
                      columns={podColumns}
                      rowKey="name"
                      size="small"
                      pagination={{ pageSize: 10 }}
                    />
                  </div>
                </div>
              ),
            },
          ]}
        />
      )}

      <DeployGatewayModal
        open={deployModalVisible}
        clusterId={cluster?.id}
        namespaces={namespaces}
        onClose={() => setDeployModalVisible(false)}
        onDeployed={(ns) => cluster && loadClusterResources(cluster.id, ns)}
      />

      <PodDetailModal
        clusterId={cluster?.id}
        pod={selectedPod}
        onClose={() => setSelectedPod(null)}
      />
    </Drawer>
  );
};
