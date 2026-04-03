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
} from "antd";
import {
  PlusOutlined,
  PlayCircleOutlined,
  PauseCircleOutlined,
  DeleteOutlined,
  ReloadOutlined,
  CloudServerOutlined,
} from "@ant-design/icons";
import { useNavigate } from "react-router-dom";
import { useTranslation } from "react-i18next";
import axios from "axios";

interface GatewayInstance {
  id: number;
  instanceId: string;
  instanceName: string;
  clusterId: number;
  clusterName: string;
  namespace: string;
  nacosNamespace: string;
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

  const getStatusText = (statusCode: number, status: string) => {
    switch (statusCode) {
      case 0: return t("instance.status_starting") || "Starting";
      case 1: return t("instance.status_running") || "Running";
      case 2: return t("instance.status_error") || "Error";
      case 3: return t("instance.status_stopping") || "Stopping";
      case 4: return t("instance.status_stopped") || "Stopped";
      default: return status;
    }
  };

  const getSpecText = (instance: GatewayInstance) => {
    if (instance.specType === "custom") {
      return `${instance.cpuCores}C ${instance.memoryMB}MB`;
    }
    return instance.specType.toUpperCase();
  };

  return (
    <div className="instances-page" style={{ padding: "24px" }}>
      <div style={{ marginBottom: "24px", display: "flex", justifyContent: "space-between", alignItems: "center" }}>
        <h2 style={{ margin: 0, color: "#fff" }}>
          <CloudServerOutlined style={{ marginRight: "8px" }} />
          {t("menu.instances")}
        </h2>
        <Button type="primary" icon={<PlusOutlined />} onClick={() => navigate("/instances/create")} disabled={clusters.length === 0}>
          {t("instance.create_instance")}
        </Button>
      </div>

      <Spin spinning={loading}>
        <Row gutter={[24, 24]}>
          {instances.map((instance) => (
            <Col xs={24} sm={12} lg={8} xl={6} key={instance.id}>
              <Card className="instance-card" style={{ background: "rgba(255,255,255,0.05)", borderColor: "rgba(255,255,255,0.1)" }} hoverable onClick={() => navigate(`/instances/${instance.instanceId}`)}>
                <div style={{ marginBottom: "16px" }}>
                  <Space>
                    <CloudServerOutlined style={{ fontSize: "24px", color: "#165DFF" }} />
                    <span style={{ fontSize: "18px", fontWeight: 600, color: "#fff" }}>{instance.instanceName}</span>
                  </Space>
                  <Tag color={getStatusColor(instance.statusCode)} style={{ marginLeft: "8px" }}>
                    {getStatusText(instance.statusCode, instance.status)}
                  </Tag>
                </div>

                <Descriptions column={1} size="small">
                  <Descriptions.Item label={t("instance.cluster")}>{instance.clusterName}</Descriptions.Item>
                  <Descriptions.Item label={t("instance.namespace")}>{instance.namespace}</Descriptions.Item>
                  <Descriptions.Item label={t("instance.spec")}>{getSpecText(instance)}</Descriptions.Item>
                  <Descriptions.Item label={t("instance.replicas")}>{instance.replicas}</Descriptions.Item>
                  {instance.nodePort && <Descriptions.Item label={t("instance.node_port")}>{instance.nodePort}</Descriptions.Item>}
                  {instance.statusMessage && <Descriptions.Item label={t("instance.status_message") || "Status"}><span style={{ color: instance.statusCode === 2 ? "#ff4d4f" : "inherit" }}>{instance.statusMessage}</span></Descriptions.Item>}
                </Descriptions>

                <div style={{ marginTop: "16px", borderTop: "1px solid rgba(255,255,255,0.1)", paddingTop: "16px" }} onClick={(e) => e.stopPropagation()}>
                  <Space>
                    {instance.statusCode === 1 && <Tooltip title={t("instance.stop")}><Button type="text" icon={<PauseCircleOutlined />} onClick={() => handleStopInstance(instance.id)} style={{ color: "#faad14" }} /></Tooltip>}
                    {(instance.statusCode === 4 || instance.statusCode === 2) && <Tooltip title={t("instance.start")}><Button type="text" icon={<PlayCircleOutlined />} onClick={() => handleStartInstance(instance.id)} style={{ color: "#52c41a" }} /></Tooltip>}
                    <Tooltip title={t("common.refresh")}><Button type="text" icon={<ReloadOutlined />} onClick={() => handleRefreshStatus(instance.id)} style={{ color: "#165DFF" }} /></Tooltip>
                    <Popconfirm title={t("instance.delete_confirm")} onConfirm={() => handleDeleteInstance(instance.id)} okText={t("common.confirm")} cancelText={t("common.cancel")}><Tooltip title={t("common.delete")}><Button type="text" danger icon={<DeleteOutlined />} /></Tooltip></Popconfirm>
                  </Space>
                </div>
              </Card>
            </Col>
          ))}
        </Row>

        {instances.length === 0 && !loading && clusters.length > 0 && <Empty description={<span style={{ color: "rgba(255,255,255,0.65)" }}>{t("instance.no_instances")}</span>} />}
      </Spin>
    </div>
  );
};

export default InstancesPage;