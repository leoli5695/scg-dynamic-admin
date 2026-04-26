import React, { useState, useEffect } from "react";
import {
  Card,
  Row,
  Col,
  Button,
  Tag,
  Space,
  Spin,
  message,
  Popconfirm,
  Tooltip,
  Empty,
  Descriptions,
  Typography,
  Badge,
  Progress,
} from "antd";
import {
  PlusOutlined,
  PlayCircleOutlined,
  PauseCircleOutlined,
  DeleteOutlined,
  ReloadOutlined,
  CloudServerOutlined,
  ClusterOutlined,
  InboxOutlined,
  DashboardOutlined,
  SettingOutlined,
} from "@ant-design/icons";
import { useNavigate } from "react-router-dom";
import { useTranslation } from "react-i18next";
import axios from "axios";
import "./InstancesPage.css";

const { Text } = Typography;

interface GatewayInstance {
  id: number;
  instanceId: string;
  instanceName: string;
  clusterId: number;
  clusterName: string;
  namespace: string;
  nacosNamespace: string;
  nacosServerAddr?: string;
  redisServerAddr?: string;
  specType: string;
  cpuCores: number;
  memoryMB: number;
  replicas: number;
  image: string;
  status: string;
  statusCode: number;
  statusMessage?: string;
  deploymentName: string;
  serviceName: string;
  nodePort: number;
  nodeIp?: string;
  serverPort?: number;
  enabled: boolean;
  description: string;
  createdAt: string;
  updatedAt: string;
}

interface KubernetesCluster {
  id: number;
  clusterName: string;
  serverUrl: string;
  connectionStatus: string;
  clusterVersion: string;
}

const InstancesPage: React.FC = () => {
  const { t } = useTranslation();
  const navigate = useNavigate();
  const [instances, setInstances] = useState<GatewayInstance[]>([]);
  const [clusters, setClusters] = useState<KubernetesCluster[]>([]);
  const [loading, setLoading] = useState(true);

  useEffect(() => {
    fetchData();
  }, []);

  const fetchData = async () => {
    setLoading(true);
    try {
      const [instancesRes, clustersRes] = await Promise.all([
        axios.get("/api/instances"),
        axios.get("/api/kubernetes/clusters"),
      ]);
      setInstances(instancesRes.data.data || []);
      setClusters(clustersRes.data.data || []);
    } catch (error) {
      console.error("Failed to fetch data:", error);
      message.error(t("common.fetch_error"));
    } finally {
      setLoading(false);
    }
  };

  const handleStartInstance = async (id: number) => {
    try {
      const response = await axios.post(`/api/instances/${id}/start`);
      if (response.data.code === 200) {
        message.success(t("instance.start_success"));
        fetchData();
      }
    } catch (error) {
      message.error(t("instance.start_failed"));
    }
  };

  const handleStopInstance = async (id: number) => {
    try {
      const response = await axios.post(`/api/instances/${id}/stop`);
      if (response.data.code === 200) {
        message.success(t("instance.stop_success"));
        fetchData();
      }
    } catch (error) {
      message.error(t("instance.stop_failed"));
    }
  };

  const handleDeleteInstance = async (id: number) => {
    try {
      const response = await axios.delete(`/api/instances/${id}`);
      if (response.data.code === 200) {
        message.success(t("instance.delete_success"));
        fetchData();
      }
    } catch (error) {
      message.error(t("instance.delete_failed"));
    }
  };

  const handleRefreshStatus = async (id: number) => {
    try {
      await axios.post(`/api/instances/${id}/refresh`);
      fetchData();
    } catch (error) {
      message.error(t("common.refresh_failed"));
    }
  };

  const getStatusColor = (statusCode: number) => {
    switch (statusCode) {
      case 1: return "green";
      case 0: return "blue";
      case 4: return "orange";
      case 2: return "red";
      case 3: return "purple";
      default: return "default";
    }
  };

  const getStatusCodeClass = (statusCode: number) => {
    switch (statusCode) {
      case 1: return "status-running";
      case 0: return "status-starting";
      case 4: return "status-stopped";
      case 2: return "status-error";
      case 3: return "status-stopping";
      default: return "";
    }
  };

  const getStatusText = (statusCode: number, status: string) => {
    switch (statusCode) {
      case 0: return t("instance.status_starting");
      case 1: return t("instance.status_running");
      case 2: return t("instance.status_error");
      case 3: return t("instance.status_stopping");
      case 4: return t("instance.status_stopped");
      default: return status;
    }
  };

  const getSpecText = (instance: GatewayInstance) => {
    if (instance.specType === "custom") {
      return `${instance.cpuCores}C ${instance.memoryMB}MB`;
    }
    const specKey = `instance.spec_${instance.specType}`;
    return t(specKey);
  };

  return (
    <div className="instances-page">
      {/* Page Header */}
      <div className="instances-page-header">
        <div className="instances-page-header-left">
          <div className="instances-page-title">
            <CloudServerOutlined />
            <span className="instances-page-title-text">{t("menu.instances")}</span>
          </div>
          <Text className="instances-page-subtitle">{t("instance.description") || t("common.manage_instances")}</Text>
        </div>
        <div className="instances-page-actions">
          <Button
            type="primary"
            icon={<PlusOutlined />}
            onClick={() => navigate("/instances/create")}
            disabled={clusters.length === 0}
            size="large"
          >
            {t("instance.create_instance")}
          </Button>
        </div>
      </div>

      <Spin spinning={loading}>
        <Row gutter={[16, 16]} className="instances-grid">
          {instances.map((instance) => (
            <Col xs={24} sm={12} lg={8} xl={6} key={instance.id}>
              <Card
                className={`instance-card ${getStatusCodeClass(instance.statusCode)}`}
                hoverable
                onClick={() => navigate(`/instances/${instance.instanceId}`)}
                bodyStyle={{ padding: 20 }}
              >
                {/* Card Header */}
                <div className="instance-card-header">
                  <div className="instance-card-icon">
                    <CloudServerOutlined />
                  </div>
                  <div className="instance-card-info">
                    <div className="instance-card-name">
                      <Text strong>{instance.instanceName}</Text>
                    </div>
                    <div className="instance-card-meta">
                      <span className="instance-cluster">
                        <ClusterOutlined style={{ marginRight: 4, fontSize: 12 }} />
                        {instance.clusterName}
                      </span>
                      <span className="instance-divider">·</span>
                      <span className="instance-namespace">
                        <InboxOutlined style={{ marginRight: 4, fontSize: 12 }} />
                        {instance.namespace}
                      </span>
                    </div>
                  </div>
                  <div className="instance-card-status-indicator">
                    <div className={`status-bar status-bar-${instance.statusCode}`}></div>
                    <Tag className="instance-status-tag" color={getStatusColor(instance.statusCode)}>
                      {getStatusText(instance.statusCode, instance.status)}
                    </Tag>
                  </div>
                </div>

                {/* Card Body - Resource Metrics */}
                <div className="instance-card-body">
                  <div className="instance-metrics-grid">
                    <div className="instance-metric-item">
                      <div className="instance-metric-icon">
                        <DashboardOutlined />
                      </div>
                      <div className="instance-metric-content">
                        <span className="instance-metric-label">{t("instance.spec") || '规格'}</span>
                        <span className="instance-metric-value">{getSpecText(instance)}</span>
                      </div>
                    </div>
                    <div className="instance-metric-item">
                      <div className="instance-metric-icon">
                        <SettingOutlined />
                      </div>
                      <div className="instance-metric-content">
                        <span className="instance-metric-label">{t("instance.replicas") || '副本'}</span>
                        <span className="instance-metric-value">{instance.replicas}</span>
                      </div>
                    </div>
                  </div>
                  
                  {instance.statusMessage && (
                    <div className="instance-status-message">
                      <Tooltip title={instance.statusMessage} placement="topLeft">
                        <Text className="instance-status-text">
                          {instance.statusMessage}
                        </Text>
                      </Tooltip>
                    </div>
                  )}
                </div>

                {/* Card Actions */}
                <div className="instance-card-actions" onClick={(e) => e.stopPropagation()}>
                  {instance.statusCode === 1 && (
                    <Tooltip title={t("instance.stop")}>
                      <Button
                        className="instance-action-btn"
                        type="text"
                        icon={<PauseCircleOutlined />}
                        onClick={() => handleStopInstance(instance.id)}
                      />
                    </Tooltip>
                  )}
                  {(instance.statusCode === 4 || instance.statusCode === 2) && (
                    <Tooltip title={t("instance.start")}>
                      <Button
                        className="instance-action-btn instance-action-btn-start"
                        type="text"
                        icon={<PlayCircleOutlined />}
                        onClick={() => handleStartInstance(instance.id)}
                      />
                    </Tooltip>
                  )}
                  <Tooltip title={t("common.refresh")}>
                    <Button
                      className="instance-action-btn"
                      type="text"
                      icon={<ReloadOutlined />}
                      onClick={() => handleRefreshStatus(instance.id)}
                    />
                  </Tooltip>
                  <Popconfirm
                    title={t("instance.delete_confirm")}
                    onConfirm={() => handleDeleteInstance(instance.id)}
                    okText={t("common.confirm")}
                    cancelText={t("common.cancel")}
                  >
                    <Tooltip title={t("common.delete")}>
                      <Button
                        className="instance-action-btn instance-action-btn-danger"
                        type="text"
                        danger
                        icon={<DeleteOutlined />}
                      />
                    </Tooltip>
                  </Popconfirm>
                </div>
              </Card>
            </Col>
          ))}
        </Row>

        {instances.length === 0 && !loading && clusters.length > 0 && (
          <div className="instances-empty">
            <Empty
              image={Empty.PRESENTED_IMAGE_SIMPLE}
              description={
                <Text type="secondary">{t("instance.no_instances")}</Text>
              }
            />
          </div>
        )}
      </Spin>
    </div>
  );
};

export default InstancesPage;