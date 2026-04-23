import React, { useState, useEffect, useMemo, lazy, Suspense } from "react";
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
  Modal,
  InputNumber,
  Form,
  Dropdown,
  Avatar,
  Result,
} from "antd";
import type { MenuProps } from "antd";
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
  PlusSquareOutlined,
  LinkOutlined,
  MedicineBoxOutlined,
  ApartmentOutlined,
  FilterOutlined,
  BugOutlined,
  RobotOutlined,
  UserOutlined,
  LogoutOutlined,
  ThunderboltOutlined,
  HomeOutlined,
} from "@ant-design/icons";
import { useParams, useNavigate, useLocation } from "react-router-dom";
import { useTranslation } from "react-i18next";
import axios from "axios";
import LanguageSwitcher from "../components/LanguageSwitcher";
import ErrorBoundary from "../components/ErrorBoundary";

// Lazy load sub-pages for better performance
const ServicesPage = lazy(() => import("./ServicesPage"));
const RoutesPage = lazy(() => import("./RoutesPage"));
const StrategiesPage = lazy(() => import("./StrategiesPage"));
const AuthPoliciesPage = lazy(() => import("./AuthPoliciesPage"));
const CertificatePage = lazy(() => import("./CertificatePage"));
const TracePage = lazy(() => import("./TracePage"));
const MonitorPage = lazy(() => import("./MonitorPage"));
const AlertPage = lazy(() => import("./AlertPage"));
const AccessLogConfigPage = lazy(() => import("./AccessLogConfigPage"));
const AuditLogsPage = lazy(() => import("./AuditLogsPage"));
const DiagnosticPage = lazy(() => import("./DiagnosticPage"));
const TrafficTopologyPage = lazy(() => import("./TrafficTopologyPage"));
const FilterChainPage = lazy(() => import("./FilterChainPage"));
const RequestReplayPage = lazy(() => import("./RequestReplayPage"));
const AiCopilotPage = lazy(() => import("./AiCopilotPage"));
const StressTestPage = lazy(() => import("./StressTestPage"));

// Tab loading fallback component
const TabLoading: React.FC = () => (
  <div style={{
    display: 'flex',
    justifyContent: 'center',
    alignItems: 'center',
    minHeight: '300px',
  }}>
    <Spin size="large" />
  </div>
);

import "./InstanceDetailPage.css";

const { Title, Text } = Typography;

interface GatewayInstance {
  id: number;
  instanceId: string;
  instanceName: string;
  clusterId: number;
  clusterName: string;
  namespace: string;
  nacosNamespace: string;
  nacosServerAddr?: string;
  specType: string;
  cpuCores: number;
  memoryMB: number;
  replicas: number;
  image: string;
  status: string;
  deploymentName: string;
  serviceName: string;
  nodePort: number;
  nodeIp: string;
  serverPort?: number;
  manualAccessUrl?: string;
  discoveredAccessUrl?: string;
  reportedAccessUrl?: string;
  enabled: boolean;
  description: string;
  createdAt: string;
  updatedAt: string;
}

// Helper function to get effective access URL with priority
const getEffectiveAccessUrl = (inst: GatewayInstance): string | null => {
  // 1. Manual configured (highest priority, for SLB/custom domain)
  if (inst.manualAccessUrl) return inst.manualAccessUrl;
  // 2. K8s discovered (LoadBalancer IP or NodePort)
  if (inst.discoveredAccessUrl) return inst.discoveredAccessUrl;
  // 3. Heartbeat reported (local dev, ECS direct)
  if (inst.reportedAccessUrl) return inst.reportedAccessUrl;
  // 4. Default: nodeIp:serverPort
  if (inst.nodeIp && inst.serverPort) {
    return `http://${inst.nodeIp}:${inst.serverPort}`;
  }
  if (inst.nodeIp && inst.nodePort) {
    return `http://${inst.nodeIp}:${inst.nodePort}`;
  }
  return null;
};

// Helper function to get effective Nacos server address
const getEffectiveNacosServerAddr = (inst: GatewayInstance): string => {
  return inst.nacosServerAddr || '127.0.0.1:8848';
};

const InstanceDetailPage: React.FC = () => {
  const { instanceId } = useParams<{ instanceId: string }>();
  const navigate = useNavigate();
  const location = useLocation();
  const { t } = useTranslation();

  const [instance, setInstance] = useState<GatewayInstance | null>(null);
  const [loading, setLoading] = useState(true);
  const [scaleModalVisible, setScaleModalVisible] = useState(false);
  const [scaleReplicas, setScaleReplicas] = useState(1);
  const [scaleLoading, setScaleLoading] = useState(false);

  // User info for header display
  const [user, setUser] = useState<{
    username: string;
    nickname: string;
    role: string;
  } | null>(null);
  const [userMenuOpen, setUserMenuOpen] = useState(false);

  // Load user info from localStorage
  useEffect(() => {
    const username = localStorage.getItem("username");
    const nickname = localStorage.getItem("nickname");
    const role = localStorage.getItem("role");
    if (username) {
      setUser({
        username,
        nickname: nickname || username,
        role: role || "USER",
      });
    }
  }, []);

  // Handle logout
  const handleLogout = () => {
    localStorage.removeItem("token");
    localStorage.removeItem("username");
    localStorage.removeItem("nickname");
    localStorage.removeItem("role");
    message.success(t("common.logout_success") || "Logout successful");
    window.location.href = "/login";
  };

  // User dropdown menu items
  const userMenuItems: MenuProps["items"] = [
    {
      key: "logout",
      icon: <LogoutOutlined />,
      label: t("common.logout"),
      onClick: () => {
        setUserMenuOpen(false);
        handleLogout();
      },
    },
  ];

  // Derive activeTab from URL path - use useMemo to cache the result
  const activeTab = useMemo(() => {
    const pathParts = location.pathname.split('/');
    // Path format: /instances/:instanceId/:tab
    if (pathParts.length > 3 && pathParts[3]) {
      return pathParts[3];
    }
    return 'overview';
  }, [location.pathname]);

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

  const handleScale = async () => {
    if (!instance) return;
    setScaleLoading(true);
    try {
      const response = await axios.put(`/api/instances/${instance.id}/replicas`, {
        replicas: scaleReplicas,
      });
      if (response.data.code === 200) {
        message.success(t("instance.scale_success"));
        setScaleModalVisible(false);
        fetchInstance();
      } else {
        message.error(response.data.message || t("instance.scale_failed"));
      }
    } catch (error) {
      message.error(t("instance.scale_failed"));
    } finally {
      setScaleLoading(false);
    }
  };

  const openScaleModal = () => {
    if (instance) {
      setScaleReplicas(instance.replicas || 1);
      setScaleModalVisible(true);
    }
  };

  const getStatusColor = (status: string) => {
    switch (status) {
      case "Running":
        return "green";
      case "Creating":
      case "Starting":
        return "blue";
      case "Stopped":
        return "orange";
      case "Error":
        return "red";
      default:
        return "default";
    }
  };

  const getStatusText = (status: string) => {
    switch (status) {
      case "Running":
        return t("instance.status_running");
      case "Creating":
        return t("instance.status_creating");
      case "Starting":
        return t("instance.status_starting");
      case "Stopped":
        return t("instance.status_stopped");
      case "Error":
        return t("instance.status_error");
      default:
        return status;
    }
  };

  const getSpecText = (inst: GatewayInstance) => {
    if (inst.specType === "custom") {
      return `${inst.cpuCores}C ${inst.memoryMB}MB`;
    }
    const specKey = `instance.spec_${inst.specType}`;
    return t(specKey);
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
    {
      key: "diagnostic",
      label: (
        <span>
          <MedicineBoxOutlined />
          {t("menu.diagnostic")}
        </span>
      ),
    },
    {
      key: "topology",
      label: (
        <span>
          <ApartmentOutlined />
          {t("menu.topology")}
        </span>
      ),
    },
    {
      key: "filter_chain",
      label: (
        <span>
          <FilterOutlined />
          {t("menu.filter_chain")}
        </span>
      ),
    },
    {
      key: "request_replay",
      label: (
        <span>
          <BugOutlined />
          {t("menu.request_replay")}
        </span>
      ),
    },
    {
      key: "copilot",
      label: (
        <span>
          <RobotOutlined />
          {t("menu.copilot")}
        </span>
      ),
    },
    {
      key: "stress_test",
      label: (
        <span>
          <ThunderboltOutlined />
          {t("menu.stress_test")}
        </span>
      ),
    },
  ];

  const renderTabContent = () => {
    // Show error state instead of blank page when instance is not available
    if (!instance) {
      return (
        <div style={{
          display: 'flex',
          justifyContent: 'center',
          alignItems: 'center',
          minHeight: '400px',
          padding: '24px',
        }}>
          <Result
            status="warning"
            title={t("instance.not_found") || "实例信息加载失败"}
            subTitle="请尝试刷新页面或返回实例列表"
            extra={[
              <Button
                key="reload"
                type="primary"
                icon={<ReloadOutlined />}
                onClick={() => window.location.reload()}
              >
                刷新页面
              </Button>,
              <Button
                key="home"
                icon={<HomeOutlined />}
                onClick={() => navigate("/instances")}
              >
                返回列表
              </Button>,
            ]}
          />
        </div>
      );
    }

    switch (activeTab) {
      case "overview":
        return (
          <div className="instance-overview-stats">
            <Row gutter={[24, 24]}>
              <Col xs={24} md={12} lg={6}>
                <Card className="instance-stat-card">
                  <Statistic
                    title={t("instance.status")}
                    value={getStatusText(instance.status)}
                    valueStyle={{
                      color: instance.status === "RUNNING" ? "var(--success-color)" : "var(--warning-color)",
                    }}
                    prefix={<Badge status={instance.status === "RUNNING" ? "success" : "warning"} />}
                  />
                </Card>
              </Col>
              <Col xs={24} md={12} lg={6}>
                <Card className="instance-stat-card">
                  <Statistic
                    title={t("instance.replicas")}
                    value={instance.replicas}
                    suffix="Pods"
                    valueStyle={{ color: "var(--primary-color)" }}
                  />
                </Card>
              </Col>
              <Col xs={24} md={12} lg={6}>
                <Card className="instance-stat-card">
                  <Statistic
                    title={t("instance.spec")}
                    value={getSpecText(instance)}
                    valueStyle={{ color: "var(--text-primary)" }}
                  />
                </Card>
              </Col>
              <Col xs={24} md={12} lg={6}>
                <Card className="instance-stat-card">
                  <Statistic
                    title={t("instance.node_port")}
                    value={instance.nodePort || "-"}
                    valueStyle={{ color: "var(--text-primary)" }}
                  />
                </Card>
              </Col>
            </Row>

            <Card
              className="instance-info-section"
              title={t("instance.basic_info")}
            >
              <Descriptions column={{ xs: 1, sm: 2, md: 3 }}>
                <Descriptions.Item label={t("instance.instance_id")}>
                  <Text copyable>
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
                  <Text copyable>
                    {instance.nacosNamespace}
                  </Text>
                </Descriptions.Item>
                <Descriptions.Item label={t("instance.image")}>
                  {instance.image}
                </Descriptions.Item>
                <Descriptions.Item label={t("instance.deployment")}>
                  {instance.deploymentName}
                </Descriptions.Item>
                <Descriptions.Item label={t("instance.access_url")}>
                  {(() => {
                    const accessUrl = getEffectiveAccessUrl(instance);
                    if (!accessUrl) {
                      return <Text type="secondary">-</Text>;
                    }

                    // Determine the source of the URL
                    let source = t("instance.access_url_default") || "默认";
                    if (instance.manualAccessUrl) {
                      source = t("instance.access_url_manual") || "手动配置";
                    } else if (instance.discoveredAccessUrl) {
                      source = t("instance.access_url_discovered") || "K8s发现";
                    } else if (instance.reportedAccessUrl) {
                      source = t("instance.access_url_reported") || "心跳上报";
                    }

                    return (
                      <Space direction="vertical" size={0}>
                        <Tooltip title={t("instance.click_to_open")}>
                          <a
                            href={accessUrl}
                            target="_blank"
                            rel="noopener noreferrer"
                            className="instance-access-url"
                          >
                            <LinkOutlined style={{ marginRight: "4px" }} />
                            {accessUrl}
                          </a>
                        </Tooltip>
                        <Text type="secondary" style={{ fontSize: 12 }}>
                          ({source})
                        </Text>
                      </Space>
                    );
                  })()}
                </Descriptions.Item>
                <Descriptions.Item label={t("instance.nacos_console")}>
                  <Tooltip title={t("instance.click_to_open_nacos")}>
                    <a
                      href={`http://${getEffectiveNacosServerAddr(instance)}/nacos/#/configurationManagement?dataId=&group=&namespace=${instance.nacosNamespace}`}
                      target="_blank"
                      rel="noopener noreferrer"
                      className="instance-access-url"
                    >
                      <LinkOutlined style={{ marginRight: "4px" }} />
                      http://{getEffectiveNacosServerAddr(instance)}/nacos
                    </a>
                  </Tooltip>
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
      // Wrap each lazy-loaded component with ErrorBoundary for error handling
      case "services":
        return (
          <ErrorBoundary>
            <Suspense fallback={<TabLoading />}>
              <ServicesPage instanceId={instance.instanceId} />
            </Suspense>
          </ErrorBoundary>
        );
      case "routes":
        return (
          <ErrorBoundary>
            <Suspense fallback={<TabLoading />}>
              <RoutesPage instanceId={instance.instanceId} />
            </Suspense>
          </ErrorBoundary>
        );
      case "strategies":
        return (
          <ErrorBoundary>
            <Suspense fallback={<TabLoading />}>
              <StrategiesPage instanceId={instance.instanceId} />
            </Suspense>
          </ErrorBoundary>
        );
      case "authentication":
        return (
          <ErrorBoundary>
            <Suspense fallback={<TabLoading />}>
              <AuthPoliciesPage instanceId={instance.instanceId} />
            </Suspense>
          </ErrorBoundary>
        );
      case "certificates":
        return (
          <ErrorBoundary>
            <Suspense fallback={<TabLoading />}>
              <CertificatePage instanceId={instance.instanceId} />
            </Suspense>
          </ErrorBoundary>
        );
      case "trace":
        return (
          <ErrorBoundary>
            <Suspense fallback={<TabLoading />}>
              <TracePage instanceId={instance.instanceId} onNavigateToReplay={() => handleTabChange('request_replay')} />
            </Suspense>
          </ErrorBoundary>
        );
      case "monitor":
        return (
          <ErrorBoundary>
            <Suspense fallback={<TabLoading />}>
              <MonitorPage instanceId={instance.instanceId} />
            </Suspense>
          </ErrorBoundary>
        );
      case "alerts":
        return (
          <ErrorBoundary>
            <Suspense fallback={<TabLoading />}>
              <AlertPage instanceId={instance.instanceId} />
            </Suspense>
          </ErrorBoundary>
        );
      case "access_log":
        return (
          <ErrorBoundary>
            <Suspense fallback={<TabLoading />}>
              <AccessLogConfigPage instanceId={instance.instanceId} />
            </Suspense>
          </ErrorBoundary>
        );
      case "audit_logs":
        return (
          <ErrorBoundary>
            <Suspense fallback={<TabLoading />}>
              <AuditLogsPage instanceId={instance.instanceId} />
            </Suspense>
          </ErrorBoundary>
        );
      case "diagnostic":
        return (
          <ErrorBoundary>
            <Suspense fallback={<TabLoading />}>
              <DiagnosticPage instanceId={instance.instanceId} />
            </Suspense>
          </ErrorBoundary>
        );
      case "topology":
        return (
          <ErrorBoundary>
            <Suspense fallback={<TabLoading />}>
              <TrafficTopologyPage instanceId={instance.instanceId} />
            </Suspense>
          </ErrorBoundary>
        );
      case "filter_chain":
        return (
          <ErrorBoundary>
            <Suspense fallback={<TabLoading />}>
              <FilterChainPage instanceId={instance.instanceId} />
            </Suspense>
          </ErrorBoundary>
        );
      case "request_replay":
        return (
          <ErrorBoundary>
            <Suspense fallback={<TabLoading />}>
              <RequestReplayPage instanceId={instance.instanceId} />
            </Suspense>
          </ErrorBoundary>
        );
      case "copilot":
        return (
          <ErrorBoundary>
            <Suspense fallback={<TabLoading />}>
              <AiCopilotPage instanceId={instance.instanceId} />
            </Suspense>
          </ErrorBoundary>
        );
      case "stress_test":
        return (
          <ErrorBoundary>
            <Suspense fallback={<TabLoading />}>
              <StressTestPage instanceId={instance.instanceId} />
            </Suspense>
          </ErrorBoundary>
        );
      default:
        return (
          <div style={{
            display: 'flex',
            justifyContent: 'center',
            alignItems: 'center',
            minHeight: '300px',
          }}>
            <Text type="secondary">未知的标签页: {activeTab}</Text>
          </div>
        );
    }
  };

  if (loading) {
    return (
      <div style={{ display: "flex", justifyContent: "center", alignItems: "center", minHeight: "400px" }}>
        <Spin size="large" />
      </div>
    );
  }

  // Show error state instead of blank page when instance is not available
  if (!instance) {
    return (
      <div style={{
        display: 'flex',
        justifyContent: 'center',
        alignItems: 'center',
        minHeight: '400px',
        padding: '24px',
      }}>
        <Result
          status="warning"
          title={t("instance.not_found") || "实例不存在"}
          subTitle="该实例可能已被删除或您没有访问权限"
          extra={[
            <Button
              key="reload"
              type="primary"
              icon={<ReloadOutlined />}
              onClick={() => window.location.reload()}
            >
              刷新页面
            </Button>,
            <Button
              key="home"
              icon={<HomeOutlined />}
              onClick={() => navigate("/instances")}
            >
              返回列表
            </Button>,
          ]}
        />
      </div>
    );
  }

  return (
    <div className="instance-detail-page">
      {/* Header */}
      <div className="instance-detail-header">
        <div className="instance-detail-header-left">
          <Button
            type="text"
            icon={<ArrowLeftOutlined />}
            onClick={() => navigate("/instances")}
            className="instance-detail-back-btn"
          />
          <CloudServerOutlined className="instance-detail-icon" />
          <Title level={4} className="instance-detail-title">
            {instance.instanceName}
          </Title>
          <Tag className="instance-detail-status-tag" color={getStatusColor(instance.status)}>
            {getStatusText(instance.status)}
          </Tag>
        </div>

        <div className="instance-detail-header-right">
          {instance.status === "Running" && (
            <>
              <Button icon={<PlusSquareOutlined />} onClick={openScaleModal}>
                {t("instance.scale")}
              </Button>
              <Button icon={<PauseCircleOutlined />} onClick={handleStop}>
                {t("instance.stop")}
              </Button>
            </>
          )}
          {instance.status === "Stopped" && (
            <Button type="primary" icon={<PlayCircleOutlined />} onClick={handleStart}>
              {t("instance.start")}
            </Button>
          )}
          <Tooltip title={t("common.refresh")}>
            <Button icon={<ReloadOutlined />} onClick={fetchInstance} />
          </Tooltip>
          <LanguageSwitcher />
          {user && (
            <Dropdown
              menu={{ items: userMenuItems }}
              placement="bottomRight"
              arrow
              trigger={["click"]}
              open={userMenuOpen}
              onOpenChange={setUserMenuOpen}
            >
              <Space className="user-info" style={{ cursor: "pointer", marginLeft: 12 }}>
                <Avatar
                  style={{ backgroundColor: "#165DFF" }}
                  icon={<UserOutlined />}
                  size="small"
                />
                <span className="username">{user.nickname || user.username}</span>
              </Space>
            </Dropdown>
          )}
        </div>
      </div>

      {/* Tabs */}
      <Tabs
        activeKey={activeTab}
        onChange={handleTabChange}
        items={tabItems}
        className="instance-detail-tabs"
      />

      {/* Content */}
      <div className="instance-detail-content">
        {renderTabContent()}
      </div>

      {/* Scale Modal */}
      <Modal
        title={t("instance.scale_title")}
        open={scaleModalVisible}
        onOk={handleScale}
        onCancel={() => setScaleModalVisible(false)}
        confirmLoading={scaleLoading}
        okText={t("instance.scale_confirm")}
        cancelText={t("common.cancel")}
      >
        <Form layout="vertical">
          <Form.Item label={t("instance.current_replicas")}>
            <Text>{instance?.replicas || 0}</Text>
          </Form.Item>
          <Form.Item label={t("instance.target_replicas")} required>
            <InputNumber
              min={1}
              max={99}
              value={scaleReplicas}
              onChange={(value) => setScaleReplicas(value || 1)}
              style={{ width: "100%" }}
            />
          </Form.Item>
        </Form>
      </Modal>
    </div>
  );
};

export default InstanceDetailPage;