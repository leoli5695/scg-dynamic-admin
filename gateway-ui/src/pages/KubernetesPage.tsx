import { useState, useEffect, useCallback, useMemo, memo } from 'react';
import {
  Card, Button, Space, Modal, message, Spin, Tag, Form, Input, Select,
  Empty, Dropdown, Tooltip, Badge, Divider, Typography, Alert, Drawer,
  Row, Col, Statistic, Tabs, Table, Progress, InputNumber, Collapse, Popconfirm,
  Descriptions
} from 'antd';
import {
  PlusOutlined, DeleteOutlined, EditOutlined, MoreOutlined,
  ClusterOutlined, CloudServerOutlined, ApiOutlined, LinkOutlined,
  ReloadOutlined, CheckCircleOutlined, CloseCircleOutlined, ExclamationCircleOutlined,
  CopyOutlined, EyeOutlined, PlayCircleOutlined, StopOutlined, ExpandOutlined,
  FileTextOutlined, SettingOutlined, GlobalOutlined, CodeOutlined
} from '@ant-design/icons';
import type { MenuProps } from 'antd';
import type { ColumnType } from 'antd/es/table';
import api from '../utils/api';
import { useTranslation } from 'react-i18next';

const { Text, Title, Paragraph } = Typography;
const { TextArea } = Input;

interface KubernetesCluster {
  id: number;
  clusterName: string;
  serverUrl: string;
  kubeconfig: string;
  contextName: string;
  clusterVersion: string;
  connectionStatus: string;
  lastCheckedAt: string;
  description: string;
  enabled: boolean;
  createdAt: string;
  updatedAt: string;
}

interface NodeInfo {
  name: string;
  status: string;
  kubeletVersion: string;
  osImage: string;
}

interface PodInfo {
  name: string;
  namespace: string;
  phase: string;
  podIP: string;
  labels: Record<string, string>;
  containers: Array<{ name: string; ready: boolean; restartCount: number }>;
}

interface PodDetail {
  name: string;
  namespace: string;
  phase: string;
  podIP: string;
  hostIP?: string;
  nodeName?: string;
  startTime?: string;
  labels?: Record<string, string>;
  annotations?: Record<string, string>;
  serviceAccountName?: string;
  restartPolicy?: string;
  containers: Array<{
    name: string;
    image: string;
    imagePullPolicy?: string;
    ports?: Array<{ containerPort: number; protocol: string; name?: string }>;
    env?: Array<{ name: string; value?: string }>;
    resources?: { requests?: Record<string, string>; limits?: Record<string, string> };
  }>;
  containerStatuses?: Array<{
    name: string;
    ready: boolean;
    restartCount: number;
    image: string;
    state?: {
      running?: { startedAt: string };
      waiting?: { reason: string; message?: string };
      terminated?: { exitCode: number; reason: string; finishedAt?: string; startedAt?: string };
    };
  }>;
  conditions?: Array<{
    type: string;
    status: string;
    reason?: string;
    message?: string;
    lastTransitionTime?: string;
  }>;
  events?: Array<{
    type: string;
    reason: string;
    message: string;
    count?: number;
    firstTimestamp?: string;
    lastTimestamp?: string;
  }>;
}

interface DeploymentInfo {
  name: string;
  namespace: string;
  replicas: number;
  readyReplicas: number;
  availableReplicas: number;
  labels: Record<string, string>;
  createdAt: string;
}

const getStatusColor = (status: string) => {
  switch (status) {
    case 'CONNECTED': return 'green';
    case 'IMPORTED': return 'blue';
    case 'CONNECTION_FAILED': return 'red';
    case 'ERROR': return 'red';
    case 'UNKNOWN': return 'gray';
    default: return 'gray';
  }
};

const getStatusIcon = (status: string) => {
  switch (status) {
    case 'CONNECTED': return <CheckCircleOutlined style={{ color: '#52c41a' }} />;
    case 'IMPORTED': return <ExclamationCircleOutlined style={{ color: '#1890ff' }} />;
    case 'CONNECTION_FAILED': return <CloseCircleOutlined style={{ color: '#ff4d4f' }} />;
    case 'ERROR': return <CloseCircleOutlined style={{ color: '#ff4d4f' }} />;
    default: return <ExclamationCircleOutlined style={{ color: '#8c8c8c' }} />;
  }
};

// Separate component for cluster card to prevent re-renders
interface ClusterCardProps {
  cluster: KubernetesCluster;
  onDetail: (cluster: KubernetesCluster) => void;
  onRefresh: (cluster: KubernetesCluster) => void;
  onDelete: (cluster: KubernetesCluster) => void;
  t: (key: string) => string;
}

const ClusterCard = memo(({ cluster, onDetail, onRefresh, onDelete, t }: ClusterCardProps) => {
  const handleDetail = useCallback(() => onDetail(cluster), [onDetail, cluster]);
  const handleRefresh = useCallback(() => onRefresh(cluster), [onRefresh, cluster]);
  const handleDelete = useCallback(() => onDelete(cluster), [onDelete, cluster]);

  return (
    <Card className="cluster-card" hoverable onClick={handleDetail}>
      <div className="cluster-header">
        <div className="cluster-icon"><ClusterOutlined /></div>
        <div className="cluster-info">
          <Text strong className="cluster-name">{cluster.clusterName}</Text>
          <div className="cluster-id-row">
            <Text className="cluster-version">{cluster.clusterVersion || 'N/A'}</Text>
          </div>
        </div>
        <Space onClick={e => e.stopPropagation()}>
          <Tooltip title={t('common.detail')}>
            <Button type="text" size="small" icon={<EyeOutlined />} className="action-btn" onClick={handleDetail} />
          </Tooltip>
          <Tooltip title={t('k8s.refresh_connection')}>
            <Button type="text" size="small" icon={<ReloadOutlined />} className="action-btn" onClick={handleRefresh} />
          </Tooltip>
          <Popconfirm
            title={`${t('common.delete')}: ${cluster.clusterName}`}
            onConfirm={handleDelete}
            okText={t('common.confirm')}
            cancelText={t('common.cancel')}
          >
            <Tooltip title={t('common.delete')}>
              <Button type="text" size="small" danger icon={<DeleteOutlined />} className="action-btn" onClick={e => e.stopPropagation()} />
            </Tooltip>
          </Popconfirm>
        </Space>
      </div>
      <Divider style={{ margin: '12px 0', borderColor: 'rgba(148, 163, 184, 0.15)' }} />
      <div className="cluster-status">
        <Space>
          {getStatusIcon(cluster.connectionStatus)}
          <Tag color={getStatusColor(cluster.connectionStatus)}>{cluster.connectionStatus}</Tag>
          <Tag color={cluster.enabled ? 'green' : 'red'}>{cluster.enabled ? t('common.enabled') : t('common.disabled')}</Tag>
        </Space>
      </div>
      <div className="cluster-meta">
        <ApiOutlined />
        <Text ellipsis>{cluster.serverUrl || 'N/A'}</Text>
      </div>
      {cluster.description && (
        <div className="cluster-description">
          <Text type="secondary" ellipsis>{cluster.description}</Text>
        </div>
      )}
    </Card>
  );
});

const KubernetesPage: React.FC = () => {
  const { t } = useTranslation();
  const [clusters, setClusters] = useState<KubernetesCluster[]>([]);
  const [loading, setLoading] = useState(true);
  const [importModalVisible, setImportModalVisible] = useState(false);
  const [deployModalVisible, setDeployModalVisible] = useState(false);
  const [detailDrawerVisible, setDetailDrawerVisible] = useState(false);
  const [selectedCluster, setSelectedCluster] = useState<KubernetesCluster | null>(null);
  const [nodes, setNodes] = useState<NodeInfo[]>([]);
  const [namespaces, setNamespaces] = useState<string[]>([]);
  const [deployments, setDeployments] = useState<DeploymentInfo[]>([]);
  const [pods, setPods] = useState<PodInfo[]>([]);
  const [selectedNamespace, setSelectedNamespace] = useState('default');
  const [activeTab, setActiveTab] = useState('overview');
  const [testingConnection, setTestingConnection] = useState(false);
  const [testResult, setTestResult] = useState<{
    success: boolean;
    serverUrl?: string;
    contextName?: string;
    clusterVersion?: string;
    nodeCount?: number;
    namespaceCount?: number;
    error?: string;
  } | null>(null);

  // Pod detail states
  const [podDetailModalVisible, setPodDetailModalVisible] = useState(false);
  const [selectedPod, setSelectedPod] = useState<PodInfo | null>(null);
  const [podDetail, setPodDetail] = useState<PodDetail | null>(null);
  const [podYaml, setPodYaml] = useState<string>('');
  const [podLogs, setPodLogs] = useState<string>('');
  const [podDetailLoading, setPodDetailLoading] = useState(false);
  const [podDetailTab, setPodDetailTab] = useState('info');

  const [importForm] = Form.useForm();
  const [deployForm] = Form.useForm();

  // Load clusters
  const loadClusters = useCallback(async () => {
    setLoading(true);
    try {
      const res = await api.get('/api/kubernetes/clusters');
      if (res.data.code === 200) {
        setClusters(res.data.data || []);
      }
    } catch (error) {
      message.error(t('k8s.load_failed'));
    } finally {
      setLoading(false);
    }
  }, [t]);

  useEffect(() => {
    loadClusters();
  }, [loadClusters]);

  // Test kubeconfig connection
  const handleTestConnection = useCallback(async () => {
    try {
      const kubeconfig = importForm.getFieldValue('kubeconfig');
      if (!kubeconfig) {
        message.warning(t('k8s.kubeconfig_required'));
        return;
      }

      setTestingConnection(true);
      setTestResult(null);

      const res = await api.post('/api/kubernetes/test-connection', { kubeconfig });

      if (res.data.code === 200) {
        setTestResult(res.data.data);
        if (res.data.data.success) {
          message.success(t('k8s.test_success'));
        } else {
          message.error(t('k8s.test_failed') + ': ' + res.data.data.error);
        }
      } else {
        message.error(res.data.message || t('k8s.test_failed'));
      }
    } catch (error: any) {
      message.error(t('k8s.test_failed') + ': ' + (error.message || 'Unknown error'));
    } finally {
      setTestingConnection(false);
    }
  }, [importForm, t]);

  // Load cluster resources when selected
  const loadClusterResources = useCallback(async (clusterId: number, namespace?: string) => {
    if (!selectedCluster) return;

    try {
      // Load nodes
      const nodesRes = await api.get(`/api/kubernetes/clusters/${clusterId}/nodes`);
      if (nodesRes.data.code === 200) {
        setNodes(nodesRes.data.data || []);
      }

      // Load namespaces
      const nsRes = await api.get(`/api/kubernetes/clusters/${clusterId}/namespaces`);
      if (nsRes.data.code === 200) {
        setNamespaces(nsRes.data.data || []);
      }

      // Load deployments
      const depRes = await api.get(`/api/kubernetes/clusters/${clusterId}/deployments`, {
        params: { namespace: namespace || selectedNamespace }
      });
      if (depRes.data.code === 200) {
        setDeployments(depRes.data.data || []);
      }

      // Load pods
      const podRes = await api.get(`/api/kubernetes/clusters/${clusterId}/pods`, {
        params: { namespace: namespace || selectedNamespace }
      });
      if (podRes.data.code === 200) {
        setPods(podRes.data.data || []);
      }
    } catch (error) {
      console.error('Failed to load cluster resources:', error);
    }
  }, [selectedCluster, selectedNamespace]);

  useEffect(() => {
    if (selectedCluster && detailDrawerVisible) {
      loadClusterResources(selectedCluster.id);
    }
  }, [selectedCluster, detailDrawerVisible, loadClusterResources]);

  // Import cluster
  const handleImportCluster = useCallback(async () => {
    try {
      const values = await importForm.validateFields();
      const res = await api.post('/api/kubernetes/clusters', {
        clusterName: values.clusterName,
        kubeconfig: values.kubeconfig,
        description: values.description
      });

      if (res.data.code === 200) {
        message.success(t('k8s.import_success'));
        setImportModalVisible(false);
        importForm.resetFields();
        loadClusters();
      } else {
        message.error(res.data.message || t('k8s.import_failed'));
      }
    } catch (error) {
      message.error(t('k8s.import_failed'));
    }
  }, [importForm, loadClusters, t]);

  // Refresh connection
  const handleRefreshConnection = useCallback(async (cluster: KubernetesCluster) => {
    try {
      message.loading({ content: t('k8s.refreshing'), key: 'refresh' });
      const res = await api.post(`/api/kubernetes/clusters/${cluster.id}/refresh`);
      if (res.data.code === 200) {
        message.success({ content: t('k8s.refresh_success'), key: 'refresh' });
        loadClusters();
      } else {
        message.error({ content: res.data.message || t('k8s.refresh_failed'), key: 'refresh' });
      }
    } catch (error) {
      message.error({ content: t('k8s.refresh_failed'), key: 'refresh' });
    }
  }, [loadClusters, t]);

  // Delete cluster
  const handleDeleteCluster = useCallback(async (cluster: KubernetesCluster) => {
    Modal.confirm({
      title: t('k8s.delete_confirm_title'),
      content: t('k8s.delete_confirm_content', { name: cluster.clusterName }),
      okType: 'danger',
      onOk: async () => {
        try {
          const res = await api.delete(`/api/kubernetes/clusters/${cluster.id}`);
          if (res.data.code === 200) {
            message.success(t('k8s.delete_success'));
            loadClusters();
          } else {
            message.error(res.data.message || t('k8s.delete_failed'));
          }
        } catch (error) {
          message.error(t('k8s.delete_failed'));
        }
      }
    });
  }, [loadClusters, t]);

  // Deploy gateway
  const handleDeployGateway = useCallback(async () => {
    try {
      const values = await deployForm.validateFields();
      message.loading({ content: t('k8s.deploying'), key: 'deploy' });

      const res = await api.post(`/api/kubernetes/clusters/${selectedCluster?.id}/deploy`, {
        namespace: values.namespace,
        appName: values.appName,
        replicas: values.replicas,
        image: values.image,
        containerPort: values.containerPort
      });

      if (res.data.code === 200) {
        message.success({ content: t('k8s.deploy_success'), key: 'deploy' });
        setDeployModalVisible(false);
        deployForm.resetFields();
        loadClusterResources(selectedCluster?.id!, values.namespace);
      } else {
        message.error({ content: res.data.message || t('k8s.deploy_failed'), key: 'deploy' });
      }
    } catch (error) {
      message.error({ content: t('k8s.deploy_failed'), key: 'deploy' });
    }
  }, [deployForm, selectedCluster, loadClusterResources, t]);

  // Scale deployment
  const handleScaleDeployment = useCallback(async (deployment: DeploymentInfo, newReplicas: number) => {
    try {
      const res = await api.put(
        `/api/kubernetes/clusters/${selectedCluster?.id}/deployments/${deployment.namespace}/${deployment.name}/scale`,
        { replicas: newReplicas }
      );

      if (res.data.code === 200) {
        message.success(t('k8s.scale_success'));
        loadClusterResources(selectedCluster?.id!, deployment.namespace);
      } else {
        message.error(res.data.message || t('k8s.scale_failed'));
      }
    } catch (error) {
      message.error(t('k8s.scale_failed'));
    }
  }, [selectedCluster, loadClusterResources, t]);

  // Delete deployment
  const handleDeleteDeployment = useCallback(async (deployment: DeploymentInfo) => {
    Modal.confirm({
      title: t('k8s.delete_deployment_confirm_title'),
      content: t('k8s.delete_deployment_confirm_content', { name: deployment.name }),
      okType: 'danger',
      onOk: async () => {
        try {
          const res = await api.delete(
            `/api/kubernetes/clusters/${selectedCluster?.id}/deployments/${deployment.namespace}/${deployment.name}`
          );
          if (res.data.code === 200) {
            message.success(t('k8s.delete_deployment_success'));
            loadClusterResources(selectedCluster?.id!, deployment.namespace);
          } else {
            message.error(res.data.message || t('k8s.delete_deployment_failed'));
          }
        } catch (error) {
          message.error(t('k8s.delete_deployment_failed'));
        }
      }
    });
  }, [selectedCluster, loadClusterResources, t]);

  // Open detail drawer
  const openDetailDrawer = useCallback((cluster: KubernetesCluster) => {
    setSelectedCluster(cluster);
    setDetailDrawerVisible(true);
  }, []);

  // Open deploy modal
  const openDeployModal = useCallback(() => {
    deployForm.setFieldsValue({
      namespace: 'gateway',
      appName: 'my-gateway',
      replicas: 1,
      image: 'my-gateway:latest',
      containerPort: 8080
    });
    setDeployModalVisible(true);
  }, [deployForm]);

  // Load pod detail
  const loadPodDetail = useCallback(async (pod: PodInfo) => {
    if (!selectedCluster) return;
    setSelectedPod(pod);
    setPodDetailModalVisible(true);
    setPodDetailLoading(true);
    setPodDetailTab('info');
    setPodDetail(null);
    setPodYaml('');
    setPodLogs('');

    try {
      const [detailRes, yamlRes, logsRes] = await Promise.all([
        api.get(`/api/kubernetes/clusters/${selectedCluster.id}/pods/${pod.namespace}/${pod.name}`),
        api.get(`/api/kubernetes/clusters/${selectedCluster.id}/pods/${pod.namespace}/${pod.name}/yaml`),
        api.get(`/api/kubernetes/clusters/${selectedCluster.id}/pods/${pod.namespace}/${pod.name}/logs`, {
          params: { tailLines: 200 }
        })
      ]);

      if (detailRes.data.code === 200) {
        setPodDetail(detailRes.data.data);
      }
      if (yamlRes.data.code === 200) {
        setPodYaml(yamlRes.data.data);
      }
      if (logsRes.data.code === 200) {
        setPodLogs(logsRes.data.data || 'No logs available');
      }
    } catch (error) {
      message.error('Failed to load pod details');
    } finally {
      setPodDetailLoading(false);
    }
  }, [selectedCluster]);

  // Copy YAML to clipboard
  const copyYamlToClipboard = useCallback(() => {
    navigator.clipboard.writeText(podYaml);
    message.success(t('common.copied') || 'Copied to clipboard');
  }, [podYaml, t]);

  // Deployment columns
  const deploymentColumns: ColumnType<DeploymentInfo>[] = useMemo(() => [
    {
      title: t('k8s.deployment_name'),
      dataIndex: 'name',
      key: 'name',
      render: (name: string) => <Text strong>{name}</Text>
    },
    {
      title: t('k8s.namespace'),
      dataIndex: 'namespace',
      key: 'namespace'
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
            <Text>{ready}/{replicas}</Text>
          </Space>
        );
      }
    },
    {
      title: t('common.actions'),
      key: 'actions',
      width: 150,
      render: (_, record) => (
        <Space>
          <Tooltip title={t('k8s.scale')}>
            <Button
              type="text"
              size="small"
              icon={<ExpandOutlined />}
              onClick={() => {
                Modal.confirm({
                  title: t('k8s.scale_title'),
                  content: (
                    <InputNumber
                      min={1}
                      max={10}
                      defaultValue={record.replicas}
                      id="scale-input"
                    />
                  ),
                  onOk: () => {
                    const input = document.getElementById('scale-input') as any;
                    handleScaleDeployment(record, input?.value || record.replicas);
                  }
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
              onClick={() => handleDeleteDeployment(record)}
            />
          </Tooltip>
        </Space>
      )
    }
  ], [t, handleScaleDeployment, handleDeleteDeployment]);

  // Pod columns
  const podColumns: ColumnType<PodInfo>[] = useMemo(() => [
    {
      title: t('k8s.pod_name'),
      dataIndex: 'name',
      key: 'name',
      render: (name: string) => <Text strong style={{ cursor: 'pointer', color: '#60a5fa' }}>{name}</Text>
    },
    {
      title: t('k8s.namespace'),
      dataIndex: 'namespace',
      key: 'namespace'
    },
    {
      title: t('k8s.phase'),
      dataIndex: 'phase',
      key: 'phase',
      render: (phase: string) => (
        <Tag color={phase === 'Running' ? 'green' : phase === 'Pending' ? 'blue' : 'red'}>
          {phase}
        </Tag>
      )
    },
    {
      title: t('k8s.pod_ip'),
      dataIndex: 'podIP',
      key: 'podIP'
    },
    {
      title: t('k8s.containers'),
      dataIndex: 'containers',
      key: 'containers',
      render: (containers: Array<{ name: string; ready: boolean; restartCount: number }>) => (
        <Space direction="vertical" size="small">
          {containers?.map(c => (
            <Tag key={c.name} color={c.ready ? 'green' : 'red'}>
              {c.name} ({c.restartCount} restarts)
            </Tag>
          ))}
        </Space>
      )
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
            onClick={() => loadPodDetail(record)}
          />
        </Tooltip>
      )
    }
  ], [t, loadPodDetail]);

  // Stats
  const stats = useMemo(() => {
    const total = clusters.length;
    const connected = clusters.filter(c => c.connectionStatus === 'CONNECTED').length;
    const enabled = clusters.filter(c => c.enabled).length;
    return { total, connected, enabled };
  }, [clusters]);

  return (
    <div className="page-container">
      {/* Premium Page Header */}
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
      <Row gutter={16} className="k8s-stats-row">
        <Col span={6}>
          <Card className="k8s-stat-card">
            <Statistic
              title={t('k8s.stats_total')}
              value={stats.total}
              prefix={<ClusterOutlined style={{ color: '#60a5fa' }} />}
              valueStyle={{ color: '#f1f5f9' }}
            />
          </Card>
        </Col>
        <Col span={6}>
          <Card className="k8s-stat-card success">
            <Statistic
              title={t('k8s.stats_connected')}
              value={stats.connected}
              prefix={<CheckCircleOutlined style={{ color: '#34d399' }} />}
              valueStyle={{ color: '#34d399' }}
            />
          </Card>
        </Col>
        <Col span={6}>
          <Card className="k8s-stat-card">
            <Statistic
              title={t('k8s.stats_enabled')}
              value={stats.enabled}
              prefix={<CloudServerOutlined style={{ color: '#60a5fa' }} />}
              valueStyle={{ color: '#f1f5f9' }}
            />
          </Card>
        </Col>
        <Col span={6}>
          <Card className="k8s-stat-card">
            <Statistic
              title={t('k8s.deployments')}
              value={deployments.length}
              prefix={<PlayCircleOutlined style={{ color: '#a78bfa' }} />}
              valueStyle={{ color: '#f1f5f9' }}
            />
          </Card>
        </Col>
      </Row>

      {/* Action Buttons */}
      <div className="k8s-action-bar">
        <Button
          icon={<ReloadOutlined />}
          onClick={loadClusters}
          loading={loading}
        >
          {t('common.refresh')}
        </Button>
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
          <div className="cluster-grid">
            {clusters.map(cluster => (
              <ClusterCard
                key={cluster.id}
                cluster={cluster}
                onDetail={openDetailDrawer}
                onRefresh={handleRefreshConnection}
                onDelete={handleDeleteCluster}
                t={t}
              />
            ))}
          </div>
        )}
      </Spin>

      {/* Import Modal */}
      <Modal
        title={t('k8s.import_title')}
        open={importModalVisible}
        onCancel={() => {
          setImportModalVisible(false);
          setTestResult(null);
        }}
        onOk={handleImportCluster}
        width={700}
        okText={t('common.confirm')}
        cancelText={t('common.cancel')}
      >
        <Alert
          type="info"
          showIcon
          message={t('k8s.import_info')}
          style={{ marginBottom: 16 }}
        />
        <Form form={importForm} layout="vertical">
          <Form.Item
            name="clusterName"
            label={t('k8s.cluster_name')}
            rules={[{ required: true, message: t('k8s.cluster_name_required') }]}
          >
            <Input placeholder={t('k8s.cluster_name_placeholder')} />
          </Form.Item>

          <Form.Item
            name="kubeconfig"
            label={
              <Space>
                {t('k8s.kubeconfig')}
                <Button
                  type="link"
                  size="small"
                  icon={<ApiOutlined />}
                  onClick={handleTestConnection}
                  loading={testingConnection}
                >
                  {t('k8s.test_connection')}
                </Button>
              </Space>
            }
            rules={[{ required: true, message: t('k8s.kubeconfig_required') }]}
          >
            <TextArea
              rows={10}
              placeholder={t('k8s.kubeconfig_placeholder')}
              style={{ fontFamily: 'monospace' }}
              onChange={() => setTestResult(null)}
            />
          </Form.Item>

          {/* Test Result */}
          {testResult && (
            <Alert
              type={testResult.success ? 'success' : 'error'}
              showIcon
              style={{ marginBottom: 16 }}
              message={testResult.success ? t('k8s.test_success') : t('k8s.test_failed')}
              description={
                testResult.success ? (
                  <div>
                    <p><strong>{t('k8s.server_url')}:</strong> {testResult.serverUrl}</p>
                    <p><strong>{t('k8s.context')}:</strong> {testResult.contextName}</p>
                    {testResult.clusterVersion && (
                      <p><strong>{t('k8s.cluster_version')}:</strong> {testResult.clusterVersion}</p>
                    )}
                    {testResult.nodeCount !== undefined && (
                      <p><strong>{t('k8s.nodes')}:</strong> {testResult.nodeCount}</p>
                    )}
                    {testResult.namespaceCount !== undefined && (
                      <p><strong>{t('k8s.namespace')}:</strong> {testResult.namespaceCount}</p>
                    )}
                  </div>
                ) : (
                  <div>
                    <p>{testResult.serverUrl && <><strong>{t('k8s.server_url')}:</strong> {testResult.serverUrl}<br /></>}</p>
                    <p style={{ color: '#ff4d4f' }}>{testResult.error}</p>
                  </div>
                )
              }
            />
          )}

          <Form.Item
            name="description"
            label={t('common.description')}
          >
            <Input placeholder={t('k8s.description_placeholder')} />
          </Form.Item>
        </Form>
      </Modal>

      {/* Cluster Detail Drawer */}
      <Drawer
        title={
          <Space>
            <ClusterOutlined />
            {selectedCluster?.clusterName}
            <Tag color={getStatusColor(selectedCluster?.connectionStatus || '')}>
              {selectedCluster?.connectionStatus}
            </Tag>
          </Space>
        }
        placement="right"
        width={720}
        open={detailDrawerVisible}
        onClose={() => setDetailDrawerVisible(false)}
        destroyOnClose
        style={{ zIndex: 999 }}
        extra={
          <Space>
            <Button
              icon={<PlayCircleOutlined />}
              onClick={openDeployModal}
              disabled={selectedCluster?.connectionStatus !== 'CONNECTED'}
            >
              {t('k8s.deploy_gateway')}
            </Button>
            <Button
              icon={<ReloadOutlined />}
              onClick={() => loadClusterResources(selectedCluster?.id!)}
            >
              {t('common.refresh')}
            </Button>
          </Space>
        }
      >
        {selectedCluster && (
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
                      <ClusterInfo cluster={selectedCluster} t={t} />
                    </Card>

                    <Card size="small" title={t('k8s.nodes')} className="k8s-info-card">
                      <Table
                        dataSource={nodes}
                        columns={[
                          { title: t('k8s.node_name'), dataIndex: 'name',
                            render: (name: string) => <Text strong style={{ color: '#f1f5f9' }}>{name}</Text> },
                          {
                            title: t('k8s.node_status'),
                            dataIndex: 'status',
                            render: (s: string) => <Tag color={s === 'True' ? 'green' : 'red'}>{s}</Tag>
                          },
                          { title: t('k8s.kubelet_version'), dataIndex: 'kubeletVersion',
                            render: (v: string) => <Text style={{ fontFamily: 'JetBrains Mono, monospace', fontSize: 12 }}>{v}</Text> },
                          { title: t('k8s.os_image'), dataIndex: 'osImage' }
                        ]}
                        rowKey="name"
                        size="small"
                        pagination={false}
                      />
                    </Card>
                  </div>
                )
              },
              {
                key: 'deployments',
                label: t('k8s.deployments'),
                children: (
                  <div>
                    <div style={{ marginBottom: 16 }}>
                      <Select
                        value={selectedNamespace}
                        onChange={(ns) => {
                          setSelectedNamespace(ns);
                          loadClusterResources(selectedCluster.id, ns);
                        }}
                        options={[
                          { label: t('k8s.all_namespaces') || 'All Namespaces', value: '' },
                          ...namespaces.map(ns => ({ label: ns, value: ns }))
                        ]}
                        style={{ width: 200 }}
                        className="namespace-select"
                      />
                    </div>
                    <Table
                      dataSource={deployments}
                      columns={deploymentColumns}
                      rowKey="name"
                      size="small"
                      pagination={{ pageSize: 10 }}
                    />
                  </div>
                )
              },
              {
                key: 'pods',
                label: t('k8s.pods'),
                children: (
                  <div>
                    <div style={{ marginBottom: 16 }}>
                      <Select
                        value={selectedNamespace}
                        onChange={(ns) => {
                          setSelectedNamespace(ns);
                          loadClusterResources(selectedCluster.id, ns);
                        }}
                        options={[
                          { label: t('k8s.all_namespaces') || 'All Namespaces', value: '' },
                          ...namespaces.map(ns => ({ label: ns, value: ns }))
                        ]}
                        style={{ width: 200 }}
                        className="namespace-select"
                      />
                    </div>
                    <Table
                      dataSource={pods}
                      columns={podColumns}
                      rowKey="name"
                      size="small"
                      pagination={{ pageSize: 10 }}
                    />
                  </div>
                )
              }
            ]}
          />
        )}
      </Drawer>

      {/* Deploy Modal */}
      <Modal
        title={t('k8s.deploy_title')}
        open={deployModalVisible}
        onCancel={() => setDeployModalVisible(false)}
        onOk={handleDeployGateway}
        width={600}
        okText={t('k8s.deploy_button')}
        cancelText={t('common.cancel')}
      >
        <Form form={deployForm} layout="vertical">
          <Form.Item
            name="namespace"
            label={t('k8s.namespace')}
            rules={[{ required: true }]}
          >
            <Select
              options={[
                { label: 'gateway', value: 'gateway' },
                { label: 'default', value: 'default' },
                ...namespaces.map(ns => ({ label: ns, value: ns }))
              ]}
            />
          </Form.Item>

          <Form.Item
            name="appName"
            label={t('k8s.app_name')}
            rules={[{ required: true }]}
          >
            <Input placeholder="my-gateway" />
          </Form.Item>

          <Form.Item
            name="replicas"
            label={t('k8s.replicas')}
            rules={[{ required: true }]}
          >
            <InputNumber min={1} max={10} />
          </Form.Item>

          <Form.Item
            name="image"
            label={t('k8s.image')}
            rules={[{ required: true }]}
          >
            <Input placeholder="my-gateway:latest" />
          </Form.Item>

          <Form.Item
            name="containerPort"
            label={t('k8s.container_port')}
            rules={[{ required: true }]}
          >
            <InputNumber min={1} max={65535} />
          </Form.Item>
        </Form>
      </Modal>

      {/* Pod Detail Modal */}
      <Modal
        title={
          <Space>
            <CloudServerOutlined />
            {selectedPod?.name}
            {selectedPod && (
              <Tag color={selectedPod.phase === 'Running' ? 'green' : selectedPod.phase === 'Pending' ? 'blue' : 'red'}>
                {selectedPod.phase}
              </Tag>
            )}
          </Space>
        }
        open={podDetailModalVisible}
        onCancel={() => setPodDetailModalVisible(false)}
        footer={null}
        width={900}
      >
        <Spin spinning={podDetailLoading}>
          <Tabs
            activeKey={podDetailTab}
            onChange={setPodDetailTab}
            items={[
              {
                key: 'info',
                label: t('k8s.pod_info'),
                children: podDetail && (
                  <div>
                    <Card size="small" title={t('k8s.basic_info')} style={{ marginBottom: 16 }}>
                      <Descriptions column={2} size="small">
                        <Descriptions.Item label={t('k8s.namespace')}>{podDetail.namespace}</Descriptions.Item>
                        <Descriptions.Item label="Pod IP">{podDetail.podIP}</Descriptions.Item>
                        <Descriptions.Item label={t('k8s.host_ip')}>{podDetail.hostIP}</Descriptions.Item>
                        <Descriptions.Item label={t('k8s.node')}>{podDetail.nodeName}</Descriptions.Item>
                        <Descriptions.Item label={t('k8s.start_time')}>{podDetail.startTime}</Descriptions.Item>
                        <Descriptions.Item label={t('k8s.service_account')}>{podDetail.serviceAccountName}</Descriptions.Item>
                        <Descriptions.Item label={t('k8s.restart_policy')}>{podDetail.restartPolicy}</Descriptions.Item>
                      </Descriptions>
                    </Card>

                    <Card size="small" title={t('k8s.containers')} style={{ marginBottom: 16 }}>
                      {podDetail.containers?.map((container, idx) => (
                        <div key={idx} style={{ marginBottom: 16, padding: 12, background: 'rgba(255,255,255,0.05)', borderRadius: 8 }}>
                          <Space style={{ marginBottom: 8 }}>
                            <Text strong>{container.name}</Text>
                            <Tag color="blue">{container.image}</Tag>
                          </Space>
                          <Descriptions column={2} size="small">
                            <Descriptions.Item label={t('k8s.image_pull_policy')}>{container.imagePullPolicy}</Descriptions.Item>
                            <Descriptions.Item label={t('k8s.ready')}>
                              {podDetail.containerStatuses?.find(s => s.name === container.name)?.ready ? (
                                <Tag color="green">{t('k8s.ready_true')}</Tag>
                              ) : (
                                <Tag color="red">{t('k8s.ready_false')}</Tag>
                              )}
                            </Descriptions.Item>
                            <Descriptions.Item label={t('k8s.restarts')}>
                              {podDetail.containerStatuses?.find(s => s.name === container.name)?.restartCount || 0}
                            </Descriptions.Item>
                            {container.ports && container.ports.length > 0 && (
                              <Descriptions.Item label={t('k8s.ports')}>
                                {container.ports.map((p, i) => (
                                  <Tag key={i}>{p.containerPort}/{p.protocol}</Tag>
                                ))}
                              </Descriptions.Item>
                            )}
                          </Descriptions>
                          {container.env && container.env.length > 0 && (
                            <div style={{ marginTop: 8 }}>
                              <Text type="secondary">{t('k8s.env_vars')}:</Text>
                              <div style={{ marginTop: 4, maxHeight: 100, overflow: 'auto' }}>
                                {container.env.map((e, i) => (
                                  <div key={i} style={{ fontSize: 12 }}>
                                    <Text code>{e.name}</Text>: {e.value || `(${t('k8s.from_secret')})`}
                                  </div>
                                ))}
                              </div>
                            </div>
                          )}
                        </div>
                      ))}
                    </Card>

                    {podDetail.conditions && podDetail.conditions.length > 0 && (
                      <Card size="small" title={t('k8s.conditions')}>
                        <Table
                          dataSource={podDetail.conditions}
                          rowKey="type"
                          size="small"
                          pagination={false}
                          columns={[
                            { title: t('k8s.type'), dataIndex: 'type', width: 150 },
                            { title: t('k8s.status'), dataIndex: 'status', width: 80, render: (s: string) => <Tag color={s === 'True' ? 'green' : 'red'}>{s}</Tag> },
                            { title: t('k8s.reason'), dataIndex: 'reason', width: 120 },
                            { title: t('k8s.message'), dataIndex: 'message', ellipsis: true }
                          ]}
                        />
                      </Card>
                    )}

                    {podDetail.events && podDetail.events.length > 0 && (
                      <Card size="small" title={t('k8s.events')} style={{ marginTop: 16 }}>
                        <div style={{ maxHeight: 200, overflow: 'auto' }}>
                          {podDetail.events.map((event, idx) => (
                            <div key={idx} style={{ padding: '8px 0', borderBottom: '1px solid rgba(255,255,255,0.1)' }}>
                              <Space>
                                <Tag color={event.type === 'Normal' ? 'blue' : 'orange'}>{event.reason}</Tag>
                                <Text type="secondary" style={{ fontSize: 12 }}>{event.lastTimestamp}</Text>
                              </Space>
                              <div style={{ marginTop: 4, fontSize: 12 }}>{event.message}</div>
                            </div>
                          ))}
                        </div>
                      </Card>
                    )}
                  </div>
                )
              },
              {
                key: 'yaml',
                label: 'YAML',
                children: (
                  <div>
                    <div style={{ marginBottom: 8 }}>
                      <Button icon={<CopyOutlined />} onClick={copyYamlToClipboard}>
                        {t('common.copy')}
                      </Button>
                    </div>
                    <Input.TextArea
                      value={podYaml}
                      rows={20}
                      readOnly
                      style={{ fontFamily: 'JetBrains Mono, monospace', fontSize: 12 }}
                    />
                  </div>
                )
              },
              {
                key: 'logs',
                label: t('k8s.logs'),
                children: (
                  <Input.TextArea
                    value={podLogs}
                    rows={20}
                    readOnly
                    style={{ fontFamily: 'JetBrains Mono, monospace', fontSize: 12, background: '#1a1a2e', color: '#e2e8f0' }}
                  />
                )
              }
            ]}
          />
        </Spin>
      </Modal>
    </div>
  );
};

// ClusterInfo component for cluster info display
const ClusterInfo: React.FC<{ cluster: KubernetesCluster; t: (key: string) => string }> = ({ cluster, t }) => (
  <div className="k8s-descriptions">
    <div className="k8s-description-item">
      <span className="k8s-description-label">{t('k8s.server_url')}</span>
      <span className="k8s-description-value">
        <Text copyable style={{ color: '#f1f5f9' }}>{cluster.serverUrl || 'N/A'}</Text>
      </span>
    </div>
    <div className="k8s-description-item">
      <span className="k8s-description-label">{t('k8s.context')}</span>
      <span className="k8s-description-value">{cluster.contextName || 'N/A'}</span>
    </div>
    <div className="k8s-description-item">
      <span className="k8s-description-label">{t('k8s.cluster_version')}</span>
      <span className="k8s-description-value">
        <Tag color="blue">{cluster.clusterVersion || 'N/A'}</Tag>
      </span>
    </div>
    <div className="k8s-description-item">
      <span className="k8s-description-label">{t('k8s.last_checked')}</span>
      <span className="k8s-description-value">
        {cluster.lastCheckedAt ? new Date(cluster.lastCheckedAt).toLocaleString() : 'N/A'}
      </span>
    </div>
  </div>
);

export default KubernetesPage;