import React, { useState, useEffect, useMemo } from "react";
import {
  Card,
  Tabs,
  Descriptions,
  Tag,
  Button,
  Space,
  Spin,
  message,
  Typography,
  Tooltip,
  Badge,
  Row,
  Col,
  Statistic,
} from "antd";
import {
  ArrowLeftOutlined,
  ReloadOutlined,
  PlayCircleOutlined,
  PauseCircleOutlined,
  CloudServerOutlined,
  AppstoreOutlined,
  DeploymentUnitOutlined,
  SafetyOutlined,
  LockOutlined,
  KeyOutlined,
  LineChartOutlined,
  DashboardOutlined,
  BellOutlined,
  FileTextOutlined,
  HistoryOutlined,
} from "@ant-design/icons";
import { useParams, useNavigate, useLocation } from "react-router-dom";
import { useTranslation } from "react-i18next";
import axios from "axios";

// Import existing pages
import ServicesPage from "./ServicesPage";
import RoutesPage from "./RoutesPage";
import StrategiesPage from "./StrategiesPage";
import AuthPoliciesPage from "./AuthPoliciesPage";
import CertificatePage from "./CertificatePage";
import TracePage from "./TracePage";
import MonitorPage from "./MonitorPage";
import AlertPage from "./AlertPage";
import AccessLogConfigPage from "./AccessLogConfigPage";
import AuditLogsPage from "./AuditLogsPage";

const { Title, Text } = Typography;

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
  deploymentName: string;
  serviceName: string;
  nodePort: number;
  enabled: boolean;
  description: string;
  createdAt: string;
  updatedAt: string;
}

const InstanceDetailPage: React.FC = () => {
  const { instanceId } = useParams<{ instanceId: string }>();
  const navigate = useNavigate();
  const location = useLocation();
  const { t } = useTranslation();

  const [instance, setInstance] = useState<GatewayInstance | null>(null);
  const [loading, setLoading] = useState(true);

  // Derive activeTab from URL path
  const getActiveTabFromPath = (): string => {
    const pathParts = location.pathname.split('/');
    // Path format: /instances/:instanceId/:tab
    if (pathParts.length > 3 && pathParts[3]) {
      return pathParts[3];
    }
    return 'overview';
  };

  const activeTab = getActiveTabFromPath();

  // Handle tab change - update URL
  const handleTabChange = (key: string) => {
    if (key === 'overview') {
      navigate(`/instances/${instanceId}`);
    } else {
      navigate(`/instances/${instanceId}/${key}`);
    }
  };

  useEffect(() => {
    fetchInstance();
  }, [instanceId]);

  const fetchInstance = async () => {
    if (!instanceId) return;
    setLoading(true);
    try {
      const response = await axios.get(`/api/instances/by-instance-id/${instanceId}`);
      if (response.data.code === 200) {
        setInstance(response.data.data);
      } else {
        message.error(t("instance.not_found"));
        navigate("/instances");
      }
    } catch (error) {
      message.error(t("common.fetch_error"));
      navigate("/instances");
    } finally {
      setLoading(false);
    }
  };

  const handleStart = async () => {
    if (!instance) return;
    try {
      const response = await axios.post(`/api/instances/${instance.id}/start`);
      if (response.data.code === 200) {
        message.success(t("instance.start_success"));
        fetchInstance();
      }
    } catch (error) {
      message.error(t("instance.start_failed"));
    }
  };

  const handleStop = async () => {
    if (!instance) return;
    try {
      const response = await axios.post(`/api/instances/${instance.id}/stop`);
      if (response.data.code === 200) {
        message.success(t("instance.stop_success"));
        fetchInstance();
      }
    } catch (error) {
      message.error(t("instance.stop_failed"));
    }
  };

  const getStatusColor = (status: string) => {
    switch (status) {
      case "RUNNING":
        return "green";
      case "CREATING":
        return "blue";
      case "STOPPED":
        return "orange";
      case "ERROR":
        return "red";
      default:
        return "default";
    }
  };

  const getStatusText = (status: string) => {
    switch (status) {
      case "RUNNING":
        return t("instance.status_running");
      case "CREATING":
        return t("instance.status_creating");
      case "STOPPED":
        return t("instance.status_stopped");
      case "ERROR":
        return t("instance.status_error");
      default:
        return status;
    }
  };

  const getSpecText = (inst: GatewayInstance) => {
    if (inst.specType === "custom") {
      return `${inst.cpuCores}C ${inst.memoryMB}MB`;
    }
    return inst.specType.toUpperCase();
  };

  // Tab items for instance detail
  const tabItems = [
    {
      key: "overview",
      label: (
        <span>
          <CloudServerOutlined />
          {t("instance.overview")}
        </span>
      ),
    },
    {
      key: "services",
      label: (
        <span>
          <AppstoreOutlined />
          {t("menu.services")}
        </span>
      ),
    },
    {
      key: "routes",
      label: (
        <span>
          <DeploymentUnitOutlined />
          {t("menu.routes")}
        </span>
      ),
    },
    {
      key: "strategies",
      label: (
        <span>
          <SafetyOutlined />
          {t("menu.strategies")}
        </span>
      ),
    },
    {
      key: "authentication",
      label: (
        <span>
          <LockOutlined />
          {t("menu.authentication")}
        </span>
      ),
    },
    {
      key: "certificates",
      label: (
        <span>
          <KeyOutlined />
          {t("menu.certificates")}
        </span>
      ),
    },
    {
      key: "trace",
      label: (
        <span>
          <LineChartOutlined />
          {t("menu.trace")}
        </span>
      ),
    },
    {
      key: "monitor",
      label: (
        <span>
          <DashboardOutlined />
          {t("menu.monitor")}
        </span>
      ),
    },
    {
      key: "alerts",
      label: (
        <span>
          <BellOutlined />
          {t("menu.alerts")}
        </span>
      ),
    },
    {
      key: "access_log",
      label: (
        <span>
          <FileTextOutlined />
          {t("menu.access_log")}
        </span>
      ),
    },
    {
      key: "audit_logs",
      label: (
        <span>
          <HistoryOutlined />
          {t("menu.audit_logs")}
        </span>
      ),
    },
  ];

  const renderTabContent = () => {
    if (!instance) return null;

    switch (activeTab) {
      case "overview":
        return (
          <div style={{ padding: "24px" }}>
            <Row gutter={[24, 24]}>
              <Col xs={24} md={12} lg={6}>
                <Card style={{ background: "rgba(255,255,255,0.05)" }}>
                  <Statistic
                    title={<span style={{ color: "rgba(255,255,255,0.65)" }}>{t("instance.status")}</span>}
                    value={getStatusText(instance.status)}
                    valueStyle={{
                      color: instance.status === "RUNNING" ? "#52c41a" : "#faad14",
                    }}
                    prefix={<Badge status={instance.status === "RUNNING" ? "success" : "warning"} />}
                  />
                </Card>
              </Col>
              <Col xs={24} md={12} lg={6}>
                <Card style={{ background: "rgba(255,255,255,0.05)" }}>
                  <Statistic
                    title={<span style={{ color: "rgba(255,255,255,0.65)" }}>{t("instance.replicas")}</span>}
                    value={instance.replicas}
                    suffix="Pods"
                    valueStyle={{ color: "#165DFF" }}
                  />
                </Card>
              </Col>
              <Col xs={24} md={12} lg={6}>
                <Card style={{ background: "rgba(255,255,255,0.05)" }}>
                  <Statistic
                    title={<span style={{ color: "rgba(255,255,255,0.65)" }}>{t("instance.spec")}</span>}
                    value={getSpecText(instance)}
                    valueStyle={{ color: "#fff" }}
                  />
                </Card>
              </Col>
              <Col xs={24} md={12} lg={6}>
                <Card style={{ background: "rgba(255,255,255,0.05)" }}>
                  <Statistic
                    title={<span style={{ color: "rgba(255,255,255,0.65)" }}>{t("instance.node_port")}</span>}
                    value={instance.nodePort || "-"}
                    valueStyle={{ color: "#fff" }}
                  />
                </Card>
              </Col>
            </Row>

            <Card
              title={t("instance.basic_info")}
              style={{ marginTop: "24px", background: "rgba(255,255,255,0.05)" }}
            >
              <Descriptions column={{ xs: 1, sm: 2, md: 3 }}>
                <Descriptions.Item label={t("instance.instance_id")}>
                  <Text copyable style={{ color: "#fff" }}>
                    {instance.instanceId}
                  </Text>
                </Descriptions.Item>
                <Descriptions.Item label={t("instance.cluster")}>
                  {instance.clusterName}
                </Descriptions.Item>
                <Descriptions.Item label={t("instance.namespace")}>
                  {instance.namespace}
                </Descriptions.Item>
                <Descriptions.Item label={t("instance.nacos_namespace")}>
                  <Text copyable style={{ color: "#fff" }}>
                    {instance.nacosNamespace}
                  </Text>
                </Descriptions.Item>
                <Descriptions.Item label={t("instance.image")}>
                  {instance.image}
                </Descriptions.Item>
                <Descriptions.Item label={t("instance.deployment")}>
                  {instance.deploymentName}
                </Descriptions.Item>
                <Descriptions.Item label={t("instance.service")}>
                  {instance.serviceName}
                </Descriptions.Item>
                <Descriptions.Item label={t("instance.created_at")}>
                  {new Date(instance.createdAt).toLocaleString()}
                </Descriptions.Item>
                {instance.description && (
                  <Descriptions.Item label={t("instance.description")}>
                    {instance.description}
                  </Descriptions.Item>
                )}
              </Descriptions>
            </Card>
          </div>
        );

      // Pass instanceId to existing pages for configuration isolation
      case "services":
        return <ServicesPage instanceId={instance.instanceId} />;
      case "routes":
        return <RoutesPage instanceId={instance.instanceId} />;
      case "strategies":
        return <StrategiesPage instanceId={instance.instanceId} />;
      case "authentication":
        return <AuthPoliciesPage instanceId={instance.instanceId} />;
      case "certificates":
        return <CertificatePage instanceId={instance.instanceId} />;
      case "trace":
        return <TracePage instanceId={instance.instanceId} />;
      case "monitor":
        return <MonitorPage instanceId={instance.instanceId} />;
      case "alerts":
        return <AlertPage instanceId={instance.instanceId} />;
      case "access_log":
        return <AccessLogConfigPage instanceId={instance.instanceId} />;
      case "audit_logs":
        return <AuditLogsPage instanceId={instance.instanceId} />;
      default:
        return null;
    }
  };

  if (loading) {
    return (
      <div style={{ display: "flex", justifyContent: "center", alignItems: "center", minHeight: "400px" }}>
        <Spin size="large" />
      </div>
    );
  }

  if (!instance) {
    return null;
  }

  return (
    <div className="instance-detail-page" style={{ minHeight: "100vh" }}>
      {/* Header */}
      <div
        style={{
          background: "rgba(255,255,255,0.05)",
          borderBottom: "1px solid rgba(255,255,255,0.1)",
          padding: "16px 24px",
          display: "flex",
          alignItems: "center",
          justifyContent: "space-between",
        }}
      >
        <Space>
          <Button
            type="text"
            icon={<ArrowLeftOutlined />}
            onClick={() => navigate("/instances")}
            style={{ color: "rgba(255,255,255,0.85)" }}
          />
          <CloudServerOutlined style={{ fontSize: "24px", color: "#165DFF" }} />
          <Title level={4} style={{ margin: 0, color: "#fff" }}>
            {instance.instanceName}
          </Title>
          <Tag color={getStatusColor(instance.status)}>{getStatusText(instance.status)}</Tag>
        </Space>

        <Space>
          {instance.status === "RUNNING" && (
            <Button icon={<PauseCircleOutlined />} onClick={handleStop}>
              {t("instance.stop")}
            </Button>
          )}
          {instance.status === "STOPPED" && (
            <Button type="primary" icon={<PlayCircleOutlined />} onClick={handleStart}>
              {t("instance.start")}
            </Button>
          )}
          <Tooltip title={t("common.refresh")}>
            <Button icon={<ReloadOutlined />} onClick={fetchInstance} />
          </Tooltip>
        </Space>
      </div>

      {/* Tabs */}
      <Tabs
        activeKey={activeTab}
        onChange={handleTabChange}
        items={tabItems}
        style={{ paddingLeft: "24px", paddingRight: "24px" }}
        tabBarStyle={{
          marginBottom: 0,
          borderBottom: "1px solid rgba(255,255,255,0.1)",
        }}
      />

      {/* Content */}
      <div style={{ background: "rgba(0,0,0,0.2)", minHeight: "calc(100vh - 120px)" }}>
        {renderTabContent()}
      </div>
    </div>
  );
};

export default InstanceDetailPage;