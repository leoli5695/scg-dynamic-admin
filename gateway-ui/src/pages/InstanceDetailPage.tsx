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
  Popover,
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
  Select,
  Progress,
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
  AlertOutlined,
  ClockCircleOutlined,
  RightOutlined,
  DownOutlined,
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
  effectiveAccessUrl?: string;
  enabled: boolean;
  description: string;
  createdAt: string;
  updatedAt: string;
}

// Kubernetes Event interface
interface K8sEvent {
  type: string;
  reason: string;
  message: string;
  count: number;
  firstTimestamp: string | null;
  lastTimestamp: string | null;
  objectName: string;
  objectKind: string;
  namespace: string;
}

// Helper function to get effective access URL - use backend computed value if available
const getEffectiveAccessUrl = (inst: GatewayInstance): string | null => {
  // Use backend computed effectiveAccessUrl if available
  if (inst.effectiveAccessUrl) return inst.effectiveAccessUrl;
  // Fallback to local computation
  if (inst.manualAccessUrl) return inst.manualAccessUrl;
  if (inst.discoveredAccessUrl) return inst.discoveredAccessUrl;
  if (inst.reportedAccessUrl) return inst.reportedAccessUrl;
  if (inst.nodeIp && inst.nodePort) {
    return `http://${inst.nodeIp}:${inst.nodePort}`;
  }
  return null;
};

// Helper function to get effective Nacos server address
const getEffectiveNacosServerAddr = (inst: GatewayInstance | null, globalAddr: string): string => {
  return inst?.nacosServerAddr || globalAddr;
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
  const [scaleSpecType, setScaleSpecType] = useState<string>("");
  const [scaleCpuCores, setScaleCpuCores] = useState<number>(0);
  const [scaleMemoryMB, setScaleMemoryMB] = useState<number>(0);
  const [scaleLoading, setScaleLoading] = useState(false);
  const [globalNacosAddr, setGlobalNacosAddr] = useState<string>("127.0.0.1:8848");

  // Events state
  const [events, setEvents] = useState<K8sEvent[]>([]);
  const [eventsLoading, setEventsLoading] = useState(false);
  const [eventsTotal, setEventsTotal] = useState(0);
  const [eventsWarningCount, setEventsWarningCount] = useState(0);
  const [eventsNormalCount, setEventsNormalCount] = useState(0);

  // Health Score state
  const [healthScore, setHealthScore] = useState<number | null>(null);
  const [healthLevel, setHealthLevel] = useState<string>("");
  const [healthDetails, setHealthDetails] = useState<any>(null);
  const [healthLoading, setHealthLoading] = useState(false);
  const [healthExpanded, setHealthExpanded] = useState(false);

  // Resource metrics state
  const [resourceMetrics, setResourceMetrics] = useState<any>(null);
  const [resourceLoading, setResourceLoading] = useState(false);

  // Spec options for scaling
  const specOptions = [
    { value: "", label: t("instance.spec_no_change") || "不修改规格" },
    { value: "small", label: t("instance.spec_small") || "小型 (0.5C 512MB)" },
    { value: "medium", label: t("instance.spec_medium") || "中型 (1C 1024MB)" },
    { value: "large", label: t("instance.spec_large") || "大型 (2C 2048MB)" },
    { value: "xlarge", label: t("instance.spec_xlarge") || "超大型 (4C 4096MB)" },
    { value: "custom", label: t("instance.spec_custom") || "自定义" },
  ];

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
    fetchConfig();
  }, [instanceId]);

  // Fetch events when activeTab changes to 'events'
  useEffect(() => {
    if (activeTab === 'events' && instance && events.length === 0) {
      fetchEvents();
    }
  }, [activeTab, instance]);

  // Fetch health score when instance is loaded
  useEffect(() => {
    if (instance && healthScore === null) {
      fetchHealthScore();
    }
  }, [instance]);

  // Fetch resource metrics when activeTab changes to 'resources'
  useEffect(() => {
    if (activeTab === 'resources' && instance && !resourceMetrics) {
      fetchResourceMetrics();
    }
  }, [activeTab, instance]);

  const fetchConfig = async () => {
    try {
      const response = await axios.get("/api/instances/config");
      if (response.data.code === 200 && response.data.data?.nacosServerAddr) {
        setGlobalNacosAddr(response.data.data.nacosServerAddr);
      }
    } catch (error) {
      console.error("Failed to fetch config:", error);
    }
  };

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

  // Fetch Kubernetes events for the instance
  const fetchEvents = async () => {
    if (!instance) return;
    setEventsLoading(true);
    try {
      const response = await axios.get(`/api/instances/${instance.id}/events`, {
        params: {
          sinceSeconds: 3600, // Last 1 hour
          limit: 50,
        },
      });
      if (response.data.code === 200) {
        const data = response.data.data;
        setEvents(data.events || []);
        setEventsTotal(data.total || 0);
        setEventsWarningCount(data.warningCount || 0);
        setEventsNormalCount(data.normalCount || 0);
      }
    } catch (error) {
      console.error("Failed to fetch events:", error);
      message.error(t("events.fetch_error") || "获取事件失败");
    } finally {
      setEventsLoading(false);
    }
  };

  // Fetch cluster health score for the instance
  const fetchHealthScore = async () => {
    if (!instance) return;
    setHealthLoading(true);
    try {
      const response = await axios.get(`/api/instances/${instance.id}/health-score`);
      if (response.data.code === 200) {
        const data = response.data.data;
        setHealthScore(data.score);
        setHealthLevel(data.level);
        setHealthDetails(data);
      }
    } catch (error) {
      console.error("Failed to fetch health score:", error);
      setHealthScore(null);
    } finally {
      setHealthLoading(false);
    }
  };

  // Fetch cluster resource metrics for the instance
  const fetchResourceMetrics = async () => {
    if (!instance) return;
    setResourceLoading(true);
    try {
      const response = await axios.get(`/api/instances/${instance.id}/resource-metrics`);
      if (response.data.code === 200) {
        setResourceMetrics(response.data.data);
      }
    } catch (error) {
      console.error("Failed to fetch resource metrics:", error);
      setResourceMetrics(null);
    } finally {
      setResourceLoading(false);
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
      const requestBody: any = {
        replicas: scaleReplicas,
      };
      
      // Add spec parameters if specType is selected
      if (scaleSpecType) {
        requestBody.specType = scaleSpecType;
        if (scaleSpecType === "custom") {
          requestBody.cpuCores = scaleCpuCores;
          requestBody.memoryMB = scaleMemoryMB;
        }
      }

      const response = await axios.put(`/api/instances/${instance.id}/scale`, requestBody);
      if (response.data.code === 200) {
        let successMsg = t("instance.scale_success");
        if (scaleSpecType && scaleSpecType !== instance.specType) {
          successMsg = t("instance.scale_and_spec_success") || "扩缩容和规格修改成功，Pod正在重启";
        }
        message.success(successMsg);
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
      setScaleSpecType("");  // Default to no change
      setScaleCpuCores(instance.cpuCores || 1);
      setScaleMemoryMB(instance.memoryMB || 1024);
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

  // Health score color helpers
  const getHealthColor = (score: number): string => {
    if (score >= 90) return '#52c41a';
    if (score >= 80) return '#73d13d';
    if (score >= 60) return '#faad14';
    if (score >= 40) return '#fa8c16';
    return '#f5222d';
  };

  const getHealthTagColor = (score: number): string => {
    if (score >= 90) return 'success';
    if (score >= 80) return 'green';
    if (score >= 60) return 'warning';
    if (score >= 40) return 'orange';
    return 'error';
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
    {
      key: "events",
      label: (
        <span>
          <AlertOutlined />
          {t("menu.events") || "事件"}
        </span>
      ),
    },
    {
      key: "resources",
      label: (
        <span>
          <DashboardOutlined />
          {t("menu.resources") || "资源"}
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
                  <div style={{ textAlign: 'center' }}>
                    <div style={{ marginBottom: 8, color: 'var(--text-muted)', fontSize: 12 }}>
                      {t("instance.health_score") || "集群健康评分"}
                    </div>
                    {healthLoading ? (
                      <Spin size="small" />
                    ) : healthScore !== null ? (
                      <Popover 
                        content={
                          <div style={{ width: 340 }}>
                            {/* Header with Score */}
                            <div style={{ 
                              display: 'flex', 
                              alignItems: 'center', 
                              justifyContent: 'space-between',
                              padding: '12px 0',
                              borderBottom: '1px solid #f0f0f0',
                              marginBottom: 12
                            }}>
                              <div>
                                <div style={{ fontSize: 13, fontWeight: 600, color: '#262626' }}>
                                  {t("instance.health_score") || "集群健康评分"}
                                </div>
                                <div style={{ fontSize: 12, color: '#8c8c8c', marginTop: 4 }}>
                                  {healthDetails?.clusterName} / {healthDetails?.namespace}
                                </div>
                              </div>
                              <div style={{ textAlign: 'right' }}>
                                <div style={{ 
                                  fontSize: 24, 
                                  fontWeight: 'bold', 
                                  color: getHealthColor(healthScore) 
                                }}>
                                  {healthScore}
                                </div>
                                <Tag color={getHealthTagColor(healthScore)} style={{ marginTop: 4 }}>
                                  {healthLevel}
                                </Tag>
                              </div>
                            </div>
                            
                            {/* Health Metrics Grid */}
                            <Row gutter={[12, 12]} style={{ marginBottom: 12 }}>
                              {/* Node Health */}
                              <Col span={12}>
                                <div style={{ 
                                  background: '#f6ffed', 
                                  borderRadius: 8, 
                                  padding: '10px 12px',
                                  border: '1px solid #b7eb8f'
                                }}>
                                  <div style={{ display: 'flex', alignItems: 'center', marginBottom: 6 }}>
                                    <Badge status={healthDetails?.nodeHealth?.score === 100 ? "success" : "warning"} />
                                    <span style={{ fontSize: 12, fontWeight: 500, marginLeft: 6 }}>
                                      {t("instance.node_health") || "节点"}
                                    </span>
                                  </div>
                                  <div style={{ fontSize: 16, fontWeight: 'bold', color: '#52c41a' }}>
                                    {healthDetails?.nodeHealth?.readyNodes || 0}/{healthDetails?.nodeHealth?.totalNodes || 0}
                                  </div>
                                  <Progress 
                                    percent={healthDetails?.nodeHealth?.score || 0} 
                                    size="small" 
                                    showInfo={false}
                                    strokeColor="#52c41a"
                                    trailColor="#d9f7be"
                                  />
                                </div>
                              </Col>
                              
                              {/* Pod Health */}
                              <Col span={12}>
                                <div style={{ 
                                  background: healthDetails?.podHealth?.score >= 80 ? '#f6ffed' : '#fffbe6', 
                                  borderRadius: 8, 
                                  padding: '10px 12px',
                                  border: `1px solid ${healthDetails?.podHealth?.score >= 80 ? '#b7eb8f' : '#ffe58f'}`
                                }}>
                                  <div style={{ display: 'flex', alignItems: 'center', marginBottom: 6 }}>
                                    <Badge status={healthDetails?.podHealth?.score >= 80 ? "success" : "warning"} />
                                    <span style={{ fontSize: 12, fontWeight: 500, marginLeft: 6 }}>
                                      {t("instance.pod_health") || "Pod"}
                                    </span>
                                  </div>
                                  <div style={{ fontSize: 16, fontWeight: 'bold', color: healthDetails?.podHealth?.score >= 80 ? '#52c41a' : '#faad14' }}>
                                    {healthDetails?.podHealth?.runningPods || 0}/{healthDetails?.podHealth?.totalPods || 0}
                                  </div>
                                  <div style={{ fontSize: 11, color: '#8c8c8c', marginTop: 4 }}>
                                    {t("instance.restarts") || "重启"}: {healthDetails?.podHealth?.totalRestarts || 0}
                                  </div>
                                  <Progress 
                                    percent={healthDetails?.podHealth?.score || 0} 
                                    size="small" 
                                    showInfo={false}
                                    strokeColor={healthDetails?.podHealth?.score >= 80 ? '#52c41a' : '#faad14'}
                                    trailColor={healthDetails?.podHealth?.score >= 80 ? '#d9f7be' : '#fff1b8'}
                                  />
                                </div>
                              </Col>
                            </Row>
                            
                            {/* Issues Section */}
                            {healthDetails?.issues?.length > 0 && (
                              <div style={{ 
                                marginBottom: 12,
                                background: '#fff2f0',
                                borderRadius: 8,
                                padding: '10px 12px',
                                border: '1px solid #ffccc7'
                              }}>
                                <div style={{ display: 'flex', alignItems: 'center', marginBottom: 8 }}>
                                  <AlertOutlined style={{ color: '#ff4d4f', fontSize: 14 }} />
                                  <span style={{ fontWeight: 500, marginLeft: 8, color: '#ff4d4f' }}>
                                    {t("instance.issues") || "问题"} ({healthDetails.issues.length})
                                  </span>
                                </div>
                                <div style={{ maxHeight: healthExpanded ? 200 : 80, overflowY: 'auto' }}>
                                  {healthDetails.issues.slice(0, healthExpanded ? healthDetails.issues.length : 3).map((issue: any, idx: number) => (
                                    <div key={idx} style={{ 
                                      display: 'flex', 
                                      alignItems: 'center',
                                      marginBottom: 4,
                                      padding: '4px 8px',
                                      background: '#fff',
                                      borderRadius: 4,
                                      fontSize: 11
                                    }}>
                                      <Tag 
                                        color={issue.severity === 'critical' ? 'error' : issue.severity === 'high' ? 'warning' : 'default'}
                                        style={{ fontSize: 10, marginRight: 8 }}
                                      >
                                        {issue.severity}
                                      </Tag>
                                      <span style={{ color: '#595959', flex: 1 }}>{issue.message}</span>
                                    </div>
                                  ))}
                                </div>
                                {healthDetails.issues.length > 3 && (
                                  <div 
                                    style={{ 
                                      textAlign: 'center', 
                                      color: '#1890ff', 
                                      fontSize: 11, 
                                      padding: 4,
                                      cursor: 'pointer',
                                      marginTop: 4
                                    }}
                                    onClick={() => setHealthExpanded(!healthExpanded)}
                                  >
                                    {healthExpanded ? (
                                      <>{t("instance.collapse") || "收起"} <DownOutlined style={{ marginLeft: 4 }} /></>
                                    ) : (
                                      <>+{healthDetails.issues.length - 3} {t("instance.more_issues") || "更多"} <RightOutlined style={{ marginLeft: 4 }} /></>
                                    )}
                                  </div>
                                )}
                              </div>
                            )}
                            
                            {/* Problematic Pods */}
                            {healthDetails?.podHealth?.problematicPods?.length > 0 && (
                              <div style={{ 
                                marginBottom: 12,
                                background: '#fafafa',
                                borderRadius: 8,
                                padding: '10px 12px'
                              }}>
                                <div style={{ display: 'flex', alignItems: 'center', marginBottom: 8 }}>
                                  <BugOutlined style={{ color: '#fa8c16', fontSize: 14 }} />
                                  <span style={{ fontWeight: 500, marginLeft: 8, color: '#595959' }}>
                                    {t("instance.problematic_pods") || "问题Pod"} ({healthDetails.podHealth.problematicPods.length})
                                  </span>
                                </div>
                                <div style={{ display: 'flex', flexWrap: 'wrap', gap: 6 }}>
                                  {healthDetails.podHealth.problematicPods.slice(0, healthExpanded ? healthDetails.podHealth.problematicPods.length : 4).map((pod: any, idx: number) => (
                                    <Tag key={idx} style={{ fontSize: 11, borderRadius: 4 }}>
                                      {pod.name.substring(0, 20)}... ({pod.restartCount}{t("instance.times") || "次"})
                                    </Tag>
                                  ))}
                                </div>
                                {healthDetails.podHealth.problematicPods.length > 4 && (
                                  <div 
                                    style={{ 
                                      textAlign: 'center', 
                                      color: '#1890ff', 
                                      fontSize: 11, 
                                      padding: 4,
                                      cursor: 'pointer',
                                      marginTop: 6
                                    }}
                                    onClick={() => setHealthExpanded(!healthExpanded)}
                                  >
                                    {healthExpanded ? (
                                      <>{t("instance.collapse") || "收起"} <DownOutlined style={{ marginLeft: 4 }} /></>
                                    ) : (
                                      <>+{healthDetails.podHealth.problematicPods.length - 4} {t("instance.more_pods") || "还有"} <RightOutlined style={{ marginLeft: 4 }} /></>
                                    )}
                                  </div>
                                )}
                              </div>
                            )}
                            
                            {/* Recommendations */}
                            {healthDetails?.recommendations?.length > 0 && (
                              <div style={{ 
                                background: '#e6f7ff',
                                borderRadius: 8,
                                padding: '10px 12px',
                                border: '1px solid #91d5ff'
                              }}>
                                <div style={{ display: 'flex', alignItems: 'center', marginBottom: 6 }}>
                                  <SafetyOutlined style={{ color: '#1890ff', fontSize: 14 }} />
                                  <span style={{ fontWeight: 500, marginLeft: 8, color: '#1890ff' }}>
                                    {t("instance.recommendations") || "建议"}
                                  </span>
                                </div>
                                <ul style={{ margin: 0, paddingLeft: 18, fontSize: 11, color: '#595959' }}>
                                  {healthDetails.recommendations.slice(0, 2).map((rec: string, idx: number) => (
                                    <li key={idx} style={{ marginBottom: 2 }}>{rec}</li>
                                  ))}
                                </ul>
                              </div>
                            )}
                          </div>
                        }
                        title={null}
                        placement="bottom"
                        trigger="hover"
                        overlayStyle={{ maxWidth: 380 }}
                      >
                        <div style={{ cursor: 'pointer' }}>
                          <Progress
                            type="circle"
                            percent={healthScore}
                            size={80}
                            strokeColor={getHealthColor(healthScore)}
                            format={(percent) => (
                              <span style={{ fontWeight: 'bold', fontSize: 18 }}>{percent}</span>
                            )}
                          />
                          <div style={{ marginTop: 8, fontSize: 12 }}>
                            <Tag color={getHealthTagColor(healthScore)}>{healthLevel}</Tag>
                          </div>
                        </div>
                      </Popover>
                    ) : (
                      <Text type="secondary">{t("instance.health_unavailable") || "暂无数据"}</Text>
                    )}
                    <Button
                      size="small"
                      type="link"
                      icon={<ReloadOutlined />}
                      onClick={fetchHealthScore}
                      loading={healthLoading}
                      style={{ marginTop: 8 }}
                    />
                  </div>
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
                      href={`http://${getEffectiveNacosServerAddr(instance, globalNacosAddr)}/nacos/#/configurationManagement?dataId=&group=&namespace=${instance?.nacosNamespace}`}
                      target="_blank"
                      rel="noopener noreferrer"
                      className="instance-access-url"
                    >
                      <LinkOutlined style={{ marginRight: "4px" }} />
                      http://{getEffectiveNacosServerAddr(instance, globalNacosAddr)}/nacos
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
              <MonitorPage instanceId={instance.instanceId} instanceDbId={instance.id} />
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
      case "events":
        return (
          <Card>
            <div style={{ marginBottom: 16, display: "flex", justifyContent: "space-between", alignItems: "center" }}>
              <Space>
                <Badge status={eventsWarningCount > 0 ? "error" : "success"} text={`${eventsWarningCount} Warning`} />
                <Badge status="processing" text={`${eventsNormalCount} Normal`} />
                <Text type="secondary">Total: {eventsTotal}</Text>
              </Space>
              <Button icon={<ReloadOutlined />} onClick={fetchEvents} loading={eventsLoading}>
                {t("common.refresh") || "刷新"}
              </Button>
            </div>
            {eventsLoading ? (
              <div style={{ display: "flex", justifyContent: "center", padding: 40 }}>
                <Spin />
              </div>
            ) : events.length === 0 ? (
              <div style={{ textAlign: "center", padding: 40 }}>
                <Text type="secondary">{t("events.no_events") || "暂无事件数据"}</Text>
              </div>
            ) : (
              <div style={{ maxHeight: 500, overflowY: "auto" }}>
                {events.map((event, idx) => (
                  <Card
                    key={idx}
                    size="small"
                    style={{ marginBottom: 8 }}
                    bordered={false}
                    className={event.type === "Warning" ? "event-card-warning" : "event-card-normal"}
                  >
                    <Row gutter={16}>
                      <Col span={2}>
                        <Tag color={event.type === "Warning" ? "error" : "success"}>
                          {event.type}
                        </Tag>
                      </Col>
                      <Col span={3}>
                        <Tag color="blue">{event.reason}</Tag>
                      </Col>
                      <Col span={14}>
                        <Text>{event.message}</Text>
                      </Col>
                      <Col span={5}>
                        <Space direction="vertical" size="small">
                          <Text type="secondary" style={{ fontSize: 12 }}>
                            <ClockCircleOutlined /> {event.objectName}
                          </Text>
                          <Text type="secondary" style={{ fontSize: 12 }}>
                            Count: {event.count}
                          </Text>
                        </Space>
                      </Col>
                    </Row>
                  </Card>
                ))}
              </div>
            )}
          </Card>
        );
      case "resources":
        return (
          <Card>
            <div style={{ marginBottom: 16, display: "flex", justifyContent: "space-between", alignItems: "center" }}>
              <Title level={5}>{t("resources.title") || "集群资源监控"}</Title>
              <Button icon={<ReloadOutlined />} onClick={fetchResourceMetrics} loading={resourceLoading}>
                {t("common.refresh") || "刷新"}
              </Button>
            </div>
            {resourceLoading ? (
              <div style={{ display: "flex", justifyContent: "center", padding: 40 }}>
                <Spin />
              </div>
            ) : !resourceMetrics ? (
              <div style={{ textAlign: "center", padding: 40 }}>
                <Text type="secondary">{t("resources.no_data") || "暂无资源数据"}</Text>
              </div>
            ) : (
              <>
                {/* Nodes Section */}
                <Card title={t("resources.nodes") || "节点"} size="small" style={{ marginBottom: 16 }}>
                  <Row gutter={[16, 16]}>
                    {resourceMetrics.nodes?.list?.map((node: any, idx: number) => (
                      <Col key={idx} xs={24} sm={12} md={8} lg={6}>
                        <Card size="small" bordered={false} style={{ background: '#fafafa' }}>
                          <Space direction="vertical" size="small">
                            <Text strong>{node.name}</Text>
                            <Badge status={node.ready ? "success" : "error"} text={node.ready ? "Ready" : "Not Ready"} />
                            {node.allocatable && (
                              <>
                                <Text type="secondary" style={{ fontSize: 12 }}>CPU: {node.allocatable.cpu}</Text>
                                <Text type="secondary" style={{ fontSize: 12 }}>Memory: {node.allocatable.memory}</Text>
                              </>
                            )}
                          </Space>
                        </Card>
                      </Col>
                    ))}
                  </Row>
                  <div style={{ marginTop: 16 }}>
                    <Text type="secondary">{t("resources.total_nodes") || "节点总数"}: {resourceMetrics.nodes?.total || 0}</Text>
                  </div>
                </Card>

                {/* Pods Section */}
                <Card title={t("resources.pods") || "Pods"} size="small">
                  <Row gutter={[16, 16]}>
                    {resourceMetrics.pods?.list?.map((pod: any, idx: number) => (
                      <Col key={idx} xs={24} sm={12} md={8} lg={6}>
                        <Card size="small" bordered={false} style={{ background: pod.phase === "Running" ? '#f6ffed' : '#fff7e6' }}>
                          <Space direction="vertical" size="small">
                            <Text strong style={{ fontSize: 12 }}>{pod.name}</Text>
                            <Tag color={pod.phase === "Running" ? "success" : "warning"}>{pod.phase}</Tag>
                            {pod.podIP && (
                              <Text type="secondary" style={{ fontSize: 11 }}>IP: {pod.podIP}</Text>
                            )}
                            {pod.containers && (
                              <Text type="secondary" style={{ fontSize: 11 }}>
                                {t("resources.containers") || "容器"}: {pod.containers.length}
                              </Text>
                            )}
                          </Space>
                        </Card>
                      </Col>
                    ))}
                  </Row>
                  <div style={{ marginTop: 16 }}>
                    <Space>
                      <Text type="secondary">{t("resources.total_pods") || "Pod总数"}: {resourceMetrics.pods?.total || 0}</Text>
                      <Text type="secondary">{t("resources.running_pods") || "运行中"}: {resourceMetrics.pods?.running || 0}</Text>
                    </Space>
                  </div>
                </Card>
              </>
            )}
          </Card>
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
        title={t("instance.scale_title") || "扩缩容与规格修改"}
        open={scaleModalVisible}
        onOk={handleScale}
        onCancel={() => setScaleModalVisible(false)}
        confirmLoading={scaleLoading}
        okText={t("instance.scale_confirm") || "确认"}
        cancelText={t("common.cancel") || "取消"}
        width={500}
      >
        <Form layout="vertical">
          {/* Replicas Section */}
          <Form.Item label={t("instance.current_replicas") || "当前副本数"}>
            <Text>{instance?.replicas || 0} Pods</Text>
          </Form.Item>
          <Form.Item label={t("instance.target_replicas") || "目标副本数"} required>
            <InputNumber
              min={1}
              max={10}
              value={scaleReplicas}
              onChange={(value) => setScaleReplicas(value || 1)}
              style={{ width: "100%" }}
            />
            <Text type="secondary" style={{ fontSize: 12 }}>
              {t("instance.replicas_hint") || "支持 1-10 个副本，修改副本数不会重启 Pod"}
            </Text>
          </Form.Item>

          {/* Spec Section */}
          <Form.Item label={t("instance.current_spec") || "当前规格"}>
            <Text>{getSpecText(instance!)} ({instance?.cpuCores || 0}C {instance?.memoryMB || 0}MB)</Text>
          </Form.Item>
          <Form.Item label={t("instance.target_spec") || "目标规格"}>
            <Select
              value={scaleSpecType}
              onChange={(value) => {
                setScaleSpecType(value);
                // Set default values for custom spec
                if (value === "custom") {
                  setScaleCpuCores(instance?.cpuCores || 1);
                  setScaleMemoryMB(instance?.memoryMB || 1024);
                } else if (value) {
                  // Set preset spec values
                  const specValues: Record<string, { cpu: number; memory: number }> = {
                    small: { cpu: 0.5, memory: 512 },
                    medium: { cpu: 1, memory: 1024 },
                    large: { cpu: 2, memory: 2048 },
                    xlarge: { cpu: 4, memory: 4096 },
                  };
                  const spec = specValues[value];
                  if (spec) {
                    setScaleCpuCores(spec.cpu);
                    setScaleMemoryMB(spec.memory);
                  }
                }
              }}
              options={specOptions}
              style={{ width: "100%" }}
            />
            <Text type="secondary" style={{ fontSize: 12 }}>
              {t("instance.spec_hint") || "修改规格会触发 Pod 重启"}
            </Text>
          </Form.Item>

          {/* Custom Spec Inputs */}
          {scaleSpecType === "custom" && (
            <>
              <Form.Item label={t("instance.cpu_cores") || "CPU 核数"} required>
                <InputNumber
                  min={0.5}
                  max={8}
                  step={0.5}
                  value={scaleCpuCores}
                  onChange={(value) => setScaleCpuCores(value || 1)}
                  style={{ width: "100%" }}
                  suffix="核"
                />
              </Form.Item>
              <Form.Item label={t("instance.memory_mb") || "内存大小"} required>
                <InputNumber
                  min={256}
                  max={8192}
                  step={256}
                  value={scaleMemoryMB}
                  onChange={(value) => setScaleMemoryMB(value || 1024)}
                  style={{ width: "100%" }}
                  suffix="MB"
                />
              </Form.Item>
            </>
          )}

          {/* Warning for spec change */}
          {scaleSpecType && scaleSpecType !== "" && scaleSpecType !== instance?.specType && (
            <div style={{ 
              padding: "8px 12px", 
              background: "#fffbe6", 
              border: "1px solid #ffe58f",
              borderRadius: 4,
              marginTop: 8 
            }}>
              <Text type="warning">
                ⚠️ {t("instance.spec_change_warning") || "规格修改将触发 Pod 重启，服务可能会有短暂中断"}
              </Text>
            </div>
          )}
        </Form>
      </Modal>
    </div>
  );
};

export default InstanceDetailPage;