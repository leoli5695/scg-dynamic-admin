import React, { useState, useEffect } from "react";
import {
  Card,
  Button,
  Space,
  Typography,
  message,
  Modal,
  Form,
  Input,
  InputNumber,
  Radio,
  Spin,
  Progress,
  Statistic,
  Row,
  Col,
  Tag,
  Divider,
  Tabs,
  Alert,
  Select,
  Tooltip,
  Collapse,
} from "antd";
import {
  ThunderboltOutlined,
  PlayCircleOutlined,
  StopOutlined,
  ReloadOutlined,
  LineChartOutlined,
  DeleteOutlined,
  RobotOutlined,
  DashboardOutlined,
  ClockCircleOutlined,
  CheckCircleOutlined,
  CloseCircleOutlined,
  SettingOutlined,
  RocketOutlined,
  ExperimentOutlined,
  InfoCircleOutlined,
  AlertOutlined,
  FieldTimeOutlined,
  ApiOutlined,
  SafetyOutlined,
  SyncOutlined,
  DatabaseOutlined,
  DownloadOutlined,
  ShareAltOutlined,
  LinkOutlined,
  CopyOutlined,
} from "@ant-design/icons";
import axios from "axios";
import AiReportRenderer from "../components/AiReportRenderer";
import StressTestCharts from "../components/StressTestCharts";
import "../styles/ai-report.css";
import { useTranslation } from "react-i18next";

const { Title, Text, Paragraph } = Typography;
const { TextArea } = Input;
const { Panel } = Collapse;

// Preset test templates for common scenarios
const TEST_TEMPLATES = [
  {
    key: "smoke",
    nameKey: "stress_test.template_smoke",
    icon: <ExperimentOutlined />,
    descriptionKey: "stress_test.template_smoke_desc",
    scenarioKey: "stress_test.template_smoke_scenario",
    config: {
      concurrentUsers: 5,
      totalRequests: 100,
      durationSeconds: undefined,
      rampUpSeconds: 5,
      targetQps: undefined,
      requestTimeoutSeconds: 30,
    },
  },
  {
    key: "load",
    nameKey: "stress_test.template_load",
    icon: <DashboardOutlined />,
    descriptionKey: "stress_test.template_load_desc",
    scenarioKey: "stress_test.template_load_scenario",
    config: {
      concurrentUsers: 50,
      totalRequests: undefined,
      durationSeconds: 300,
      rampUpSeconds: 30,
      targetQps: 500,
      requestTimeoutSeconds: 30,
    },
  },
  {
    key: "stress",
    nameKey: "stress_test.template_stress",
    icon: <ThunderboltOutlined />,
    descriptionKey: "stress_test.template_stress_desc",
    scenarioKey: "stress_test.template_stress_scenario",
    config: {
      concurrentUsers: 200,
      totalRequests: undefined,
      durationSeconds: 600,
      rampUpSeconds: 60,
      targetQps: 2000,
      requestTimeoutSeconds: 60,
    },
  },
  {
    key: "spike",
    nameKey: "stress_test.template_spike",
    icon: <RocketOutlined />,
    descriptionKey: "stress_test.template_spike_desc",
    scenarioKey: "stress_test.template_spike_scenario",
    config: {
      concurrentUsers: 500,
      totalRequests: undefined,
      durationSeconds: 120,
      rampUpSeconds: 10,
      targetQps: 5000,
      requestTimeoutSeconds: 30,
    },
  },
  {
    key: "soak",
    nameKey: "stress_test.template_soak",
    icon: <ClockCircleOutlined />,
    descriptionKey: "stress_test.template_soak_desc",
    scenarioKey: "stress_test.template_soak_scenario",
    config: {
      concurrentUsers: 30,
      totalRequests: undefined,
      durationSeconds: 1800,
      rampUpSeconds: 60,
      targetQps: 300,
      requestTimeoutSeconds: 30,
    },
  },
  {
    key: "breakpoint",
    nameKey: "stress_test.template_breakpoint",
    icon: <AlertOutlined />,
    descriptionKey: "stress_test.template_breakpoint_desc",
    scenarioKey: "stress_test.template_breakpoint_scenario",
    config: {
      concurrentUsers: 100,
      totalRequests: undefined,
      durationSeconds: 600,
      rampUpSeconds: 120,
      targetQps: undefined,
      requestTimeoutSeconds: 60,
    },
  },
  {
    key: "latency",
    nameKey: "stress_test.template_latency",
    icon: <FieldTimeOutlined />,
    descriptionKey: "stress_test.template_latency_desc",
    scenarioKey: "stress_test.template_latency_scenario",
    config: {
      concurrentUsers: 20,
      totalRequests: undefined,
      durationSeconds: 300,
      rampUpSeconds: 30,
      targetQps: 100,
      requestTimeoutSeconds: 30,
    },
  },
  {
    key: "api_benchmark",
    nameKey: "stress_test.template_api_benchmark",
    icon: <ApiOutlined />,
    descriptionKey: "stress_test.template_api_benchmark_desc",
    scenarioKey: "stress_test.template_api_benchmark_scenario",
    config: {
      concurrentUsers: 10,
      totalRequests: 10000,
      durationSeconds: undefined,
      rampUpSeconds: 10,
      targetQps: 1000,
      requestTimeoutSeconds: 30,
    },
  },
  {
    key: "capacity",
    nameKey: "stress_test.template_capacity",
    icon: <DatabaseOutlined />,
    descriptionKey: "stress_test.template_capacity_desc",
    scenarioKey: "stress_test.template_capacity_scenario",
    config: {
      concurrentUsers: 150,
      totalRequests: undefined,
      durationSeconds: 900,
      rampUpSeconds: 90,
      targetQps: 1500,
      requestTimeoutSeconds: 45,
    },
  },
  {
    key: "stability",
    nameKey: "stress_test.template_stability",
    icon: <SafetyOutlined />,
    descriptionKey: "stress_test.template_stability_desc",
    scenarioKey: "stress_test.template_stability_scenario",
    config: {
      concurrentUsers: 40,
      totalRequests: undefined,
      durationSeconds: 3600,
      rampUpSeconds: 120,
      targetQps: 400,
      requestTimeoutSeconds: 30,
    },
  },
  {
    key: "gradual",
    nameKey: "stress_test.template_gradual",
    icon: <SyncOutlined />,
    descriptionKey: "stress_test.template_gradual_desc",
    scenarioKey: "stress_test.template_gradual_scenario",
    config: {
      concurrentUsers: 80,
      totalRequests: undefined,
      durationSeconds: 480,
      rampUpSeconds: 240,
      targetQps: 800,
      requestTimeoutSeconds: 30,
    },
  },
];

interface StressTest {
  id: number;
  instanceId: string;
  testName: string;
  targetUrl: string;
  method: string;
  concurrentUsers: number;
  totalRequests: number;
  durationSeconds: number;
  rampUpSeconds: number;
  requestTimeoutSeconds: number;
  targetQps: number;
  status: string;
  startTime: string;
  endTime: string;
  actualRequests: number;
  successfulRequests: number;
  failedRequests: number;
  minResponseTimeMs: number;
  maxResponseTimeMs: number;
  avgResponseTimeMs: number;
  p50ResponseTimeMs: number;
  p90ResponseTimeMs: number;
  p95ResponseTimeMs: number;
  p99ResponseTimeMs: number;
  requestsPerSecond: number;
  errorRate: number;
  throughputKbps: number;
  responseTimeDistribution: string;
  errorDistribution: string;
  createdAt: string;
}

interface MetricDataPoint {
  timestamp: number;
  rps: number;
  avgResponseTime: number;
  p95ResponseTime: number;
  p99ResponseTime: number;
  errorRate: number;
  totalRequests: number;
  successRequests: number;
  failedRequests: number;
}

interface SummaryMetrics {
  totalRequests: number;
  successRequests: number;
  failedRequests: number;
  avgResponseTime: number;
  minResponseTime: number;
  maxResponseTime: number;
  p50ResponseTime: number;
  p90ResponseTime: number;
  p95ResponseTime: number;
  p99ResponseTime: number;
  requestsPerSecond: number;
  errorRate: number;
  throughputKbps: number;
}

interface StressTestMetrics {
  testId: number;
  status: string;
  progress: number;
  timeline: MetricDataPoint[];
  summary: SummaryMetrics;
}

interface TestStatus {
  testId: number;
  status: string;
  actualRequests: number;
  successfulRequests: number;
  failedRequests: number;
  avgResponseTimeMs: number;
  requestsPerSecond: number;
  errorRate: number;
  progress: number;
  liveRps: number;
  timeline?: MetricDataPoint[];
}

interface Props {
  instanceId: string;
}

const StressTestPage: React.FC<Props> = ({ instanceId }) => {
  const { t } = useTranslation();
  const [tests, setTests] = useState<StressTest[]>([]);
  const [loading, setLoading] = useState(true);
  const [activeTab, setActiveTab] = useState("history");

  // Create modal
  const [createModalVisible, setCreateModalVisible] = useState(false);
  const [createForm] = Form.useForm();
  const [creating, setCreating] = useState(false);

  // Running test
  const [runningTestId, setRunningTestId] = useState<number | null>(null);
  const [runningStatus, setRunningStatus] = useState<TestStatus | null>(null);
  const [statusLoading, setStatusLoading] = useState(false);
  const [testMetrics, setTestMetrics] = useState<StressTestMetrics | null>(null);

  // Quick test modal
  const [quickModalVisible, setQuickModalVisible] = useState(false);
  const [quickRequests, setQuickRequests] = useState(100);
  const [quickConcurrent, setQuickConcurrent] = useState(10);

  // Analysis modal
  const [analysisModalVisible, setAnalysisModalVisible] = useState(false);
  const [analysisResult, setAnalysisResult] = useState("");
  const [analysisLoading, setAnalysisLoading] = useState(false);
  const [analysisTestId, setAnalysisTestId] = useState<number | null>(null);

  useEffect(() => {
    loadTests();
  }, [instanceId]);

  // Poll running test status
  useEffect(() => {
    if (runningTestId) {
      const interval = setInterval(() => {
        fetchTestStatus(runningTestId);
      }, 2000);

      return () => clearInterval(interval);
    }
  }, [runningTestId]);

  const loadTests = async () => {
    setLoading(true);
    try {
      const response = await axios.get(`/api/stress-test/instance/${instanceId}`);
      setTests(response.data);

      // Check if any test is running
      const runningTest = response.data.find((t: StressTest) => t.status === "RUNNING");
      if (runningTest) {
        setRunningTestId(runningTest.id);
      }
    } catch (error) {
      message.error(t("stress_test.load_failed"));
    } finally {
      setLoading(false);
    }
  };

  const fetchTestStatus = async (testId: number) => {
    setStatusLoading(true);
    try {
      // Fetch both status and metrics in parallel
      const [statusResponse, metricsResponse] = await Promise.all([
        axios.get(`/api/stress-test/${testId}/status`),
        axios.get(`/api/stress-test/${testId}/metrics`).catch(() => null)
      ]);

      setRunningStatus(statusResponse.data);

      // Update test metrics for charts if available
      if (metricsResponse?.data && metricsResponse.data.timeline?.length > 0) {
        setTestMetrics(metricsResponse.data);
      } else if (statusResponse.data.timeline && statusResponse.data.timeline.length > 0) {
        // Fallback to timeline data from status if metrics endpoint doesn't have data
        setTestMetrics({
          testId: statusResponse.data.testId,
          status: statusResponse.data.status,
          progress: statusResponse.data.progress,
          timeline: statusResponse.data.timeline,
          summary: {
            totalRequests: statusResponse.data.actualRequests || 0,
            successRequests: statusResponse.data.successfulRequests || 0,
            failedRequests: statusResponse.data.failedRequests || 0,
            avgResponseTime: statusResponse.data.avgResponseTimeMs || 0,
            minResponseTime: 0,
            maxResponseTime: 0,
            p50ResponseTime: 0,
            p90ResponseTime: 0,
            p95ResponseTime: 0,
            p99ResponseTime: 0,
            requestsPerSecond: statusResponse.data.requestsPerSecond || 0,
            errorRate: statusResponse.data.errorRate || 0,
            throughputKbps: 0,
          }
        });
      }

      if (statusResponse.data.status !== "RUNNING") {
        setRunningTestId(null);
        setRunningStatus(null);
        setTestMetrics(null);
        loadTests();
      }
    } catch (error) {
      console.error("Failed to fetch status");
    } finally {
      setStatusLoading(false);
    }
  };

  const handleCreateTest = async (values: any) => {
    setCreating(true);
    
    const submitData = {
      ...values,
      headers: values.headers ? JSON.parse(values.headers) : null,
    };
    
    if (submitData.durationSeconds && submitData.totalRequests) {
      if (submitData.durationSeconds > 0) {
        submitData.totalRequests = null;
      } else {
        submitData.durationSeconds = null;
      }
    }
    
    console.log("Submit data:", JSON.stringify(submitData, null, 2));
    
    try {
      const response = await axios.post("/api/stress-test/start", submitData, {
        params: { instanceId },
      });

      if (response.data.success) {
        message.success(t("stress_test.test_started"));
        setCreateModalVisible(false);
        createForm.resetFields();
        setRunningTestId(response.data.testId);
        loadTests();
      } else {
        message.error(response.data.error || t("stress_test.start_failed"));
      }
    } catch (error: any) {
      message.error(error.response?.data?.error || t("stress_test.start_failed"));
    } finally {
      setCreating(false);
    }
  };

  const handleQuickTest = async () => {
    try {
      const response = await axios.post("/api/stress-test/quick", null, {
        params: {
          instanceId,
          requests: quickRequests,
          concurrent: quickConcurrent,
        },
      });

      if (response.data.success) {
        message.success(t("stress_test.test_started"));
        setQuickModalVisible(false);
        setRunningTestId(response.data.testId);
        loadTests();
      } else {
        message.error(response.data.error || t("stress_test.start_failed"));
      }
    } catch (error: any) {
      message.error(error.response?.data?.error || t("stress_test.start_failed"));
    }
  };

  const handleStopTest = async (testId: number) => {
    try {
      const response = await axios.post(`/api/stress-test/${testId}/stop`);

      if (response.data.success) {
        message.success(t("stress_test.test_stopped"));
        setRunningTestId(null);
        setRunningStatus(null);
        loadTests();
      } else {
        message.error(response.data.error || t("stress_test.stop_failed"));
      }
    } catch (error: any) {
      message.error(error.response?.data?.error || t("stress_test.stop_failed"));
    }
  };

  const handleDeleteTest = async (testId: number) => {
    Modal.confirm({
      title: t("stress_test.delete_confirm_title"),
      content: t("stress_test.delete_confirm_content"),
      onOk: async () => {
        try {
          await axios.delete(`/api/stress-test/${testId}`);
          message.success(t("stress_test.test_deleted"));
          loadTests();
        } catch (error) {
          message.error(t("stress_test.delete_failed"));
        }
      },
    });
  };

  const handleAnalyzeTest = async (testId: number) => {
    setAnalysisTestId(testId);
    setAnalysisModalVisible(true);
    setAnalysisLoading(true);
    setAnalysisResult("");

    try {
      const response = await axios.get(`/api/stress-test/${testId}/analyze`);

      if (response.data.success) {
        setAnalysisResult(response.data.analysis);
      } else {
        message.error(response.data.error || t("ai.analysis_failed"));
      }
    } catch (error: any) {
      message.error(error.response?.data?.error || t("ai.analysis_failed"));
    } finally {
      setAnalysisLoading(false);
    }
  };

  const handleExportReport = async (testId: number) => {
    try {
      const response = await axios.get(`/api/stress-test/${testId}/export`, {
        responseType: "blob",
      });

      const url = window.URL.createObjectURL(new Blob([response.data]));
      const link = document.createElement("a");
      link.href = url;
      link.setAttribute("download", `stress-test-report-${testId}.md`);
      document.body.appendChild(link);
      link.click();
      link.remove();
      window.URL.revokeObjectURL(url);

      message.success(t("stress_test.export_success"));
    } catch (error: any) {
      message.error(error.response?.data?.error || t("stress_test.export_failed"));
    }
  };

  const handleShareReport = async (testId: number, expiresIn: number | null) => {
    try {
      const params = expiresIn ? { expiresIn } : {};
      const response = await axios.post(`/api/stress-test/${testId}/share`, null, { params });

      if (response.data.success) {
        const shareUrl = `${window.location.origin}/share/${response.data.shareId}`;
        
        Modal.success({
          title: t("stress_test.share_created"),
          content: (
            <div>
              <Paragraph>{t("stress_test.share_url_label")}</Paragraph>
              <Input 
                value={shareUrl} 
                readOnly 
                addonAfter={
                  <CopyOutlined 
                    onClick={() => {
                      navigator.clipboard.writeText(shareUrl);
                      message.success(t("stress_test.copied"));
                    }}
                  />
                }
              />
              <Paragraph type="secondary" style={{ marginTop: 8 }}>
                {t("stress_test.expires_in")}: {response.data.expiresIn}
              </Paragraph>
            </div>
          ),
        });
      } else {
        message.error(response.data.error || t("stress_test.share_failed"));
      }
    } catch (error: any) {
      message.error(error.response?.data?.error || t("stress_test.share_failed"));
    }
  };

  const showShareModal = (testId: number) => {
    let selectedValue = 24;
    
    Modal.confirm({
      title: t("stress_test.create_share_link"),
      content: (
        <div>
          <Paragraph style={{ color: "#fff" }}>{t("stress_test.select_expiration")}</Paragraph>
          <Select
            style={{ width: "100%" }}
            className="dark-select"
            defaultValue={24}
            popupClassName="dark-select-dropdown"
            options={[
              { value: 1, label: t("stress_test.expires_1h") },
              { value: 24, label: t("stress_test.expires_24h") },
              { value: 168, label: t("stress_test.expires_7d") },
              { value: 0, label: t("stress_test.expires_permanent") },
            ]}
            onChange={(value) => {
              selectedValue = value;
            }}
          />
        </div>
      ),
      onOk: () => handleShareReport(testId, selectedValue === 0 ? null : selectedValue),
      okText: t("common.confirm"),
      cancelText: t("common.cancel"),
    });
  };

  const getStatusColor = (status: string) => {
    switch (status) {
      case "RUNNING":
        return "blue";
      case "COMPLETED":
        return "green";
      case "STOPPED":
        return "orange";
      case "FAILED":
        return "red";
      default:
        return "default";
    }
  };

  const getStatusIcon = (status: string) => {
    switch (status) {
      case "RUNNING":
        return <ClockCircleOutlined />;
      case "COMPLETED":
        return <CheckCircleOutlined />;
      case "STOPPED":
        return <StopOutlined />;
      case "FAILED":
        return <CloseCircleOutlined />;
      default:
        return null;
    }
  };

  const renderRunningTest = () => {
    if (!runningTestId || !runningStatus) return null;

    return (
      <div>
        <Card
          title={
            <Space>
              <ThunderboltOutlined style={{ color: "#165DFF" }} />
              <Title level={5} style={{ margin: 0, color: "#fff" }}>
                Test #{runningTestId} {t("stress_test.running")}
              </Title>
              <Tag color="blue">RUNNING</Tag>
            </Space>
          }
          style={{ background: "rgba(255,255,255,0.05)", marginBottom: "24px" }}
          extra={
            <Button danger icon={<StopOutlined />} onClick={() => handleStopTest(runningTestId)}>
              {t("stress_test.stop_test")}
            </Button>
          }
        >
          <Spin spinning={statusLoading}>
            <Row gutter={[24, 24]}>
              <Col span={24}>
                <Progress
                  percent={Math.round(runningStatus.progress * 100)}
                  status="active"
                  strokeColor={{ from: "#165DFF", to: "#52c41a" }}
                />
              </Col>

              <Col xs={12} sm={6}>
                <Statistic
                  title={t("stress_test.requests_sent")}
                  value={runningStatus.actualRequests || 0}
                  prefix={<ThunderboltOutlined />}
                  valueStyle={{ color: "#165DFF" }}
                />
              </Col>

              <Col xs={12} sm={6}>
                <Statistic
                  title={t("stress_test.live_rps")}
                  value={runningStatus.liveRps?.toFixed(2) || "0"}
                  suffix="req/s"
                  prefix={<DashboardOutlined />}
                  valueStyle={{ color: "#52c41a" }}
                />
              </Col>

              <Col xs={12} sm={6}>
                <Statistic
                  title={t("stress_test.avg_response_time")}
                  value={runningStatus.avgResponseTimeMs?.toFixed(2) || "0"}
                  suffix="ms"
                  prefix={<ClockCircleOutlined />}
                  valueStyle={{ color: "#fff" }}
                />
              </Col>

              <Col xs={12} sm={6}>
                <Statistic
                  title={t("stress_test.error_rate")}
                  value={runningStatus.errorRate?.toFixed(2) || "0"}
                  suffix="%"
                  prefix={<CloseCircleOutlined />}
                  valueStyle={{ color: runningStatus.errorRate > 5 ? "#f5222d" : "#52c41a" }}
                />
              </Col>
            </Row>
          </Spin>
        </Card>

        {/* Real-time monitoring charts */}
        <StressTestCharts metrics={testMetrics} loading={statusLoading} />
      </div>
    );
  };

  const renderCompletedTest = (test: StressTest) => (
    <Card
      key={test.id}
      title={
        <Space>
          <Title level={5} style={{ margin: 0, color: "#fff" }}>
            {test.testName || `Test #${test.id}`}
          </Title>
          <Tag color={getStatusColor(test.status)} icon={getStatusIcon(test.status)}>
            {test.status}
          </Tag>
        </Space>
      }
      style={{ background: "rgba(255,255,255,0.05)", marginBottom: "16px" }}
      extra={
        <Space>
          {test.status === "COMPLETED" && (
            <>
              <Button icon={<RobotOutlined />} onClick={() => handleAnalyzeTest(test.id)}>
                {t("stress_test.ai_analysis")}
              </Button>
              <Button icon={<DownloadOutlined />} onClick={() => handleExportReport(test.id)}>
                {t("stress_test.export")}
              </Button>
              <Button icon={<ShareAltOutlined />} onClick={() => showShareModal(test.id)}>
                {t("stress_test.share")}
              </Button>
            </>
          )}
          {test.status !== "RUNNING" && (
            <Button danger icon={<DeleteOutlined />} onClick={() => handleDeleteTest(test.id)}>
              {t("stress_test.delete_test")}
            </Button>
          )}
        </Space>
      }
    >
      <Row gutter={[24, 24]}>
        <Col xs={24} sm={12}>
          <Statistic
            title={t("stress_test.total_requests")}
            value={test.actualRequests}
            suffix={test.totalRequests ? `/ ${test.totalRequests}` : ""}
            valueStyle={{ color: "#fff" }}
          />
          <Space style={{ marginTop: "8px" }}>
            <Tag color="green">{test.successfulRequests} {t("stress_test.success")}</Tag>
            <Tag color="red">{test.failedRequests} {t("stress_test.failed")}</Tag>
          </Space>
        </Col>

        <Col xs={24} sm={12}>
          <Statistic
            title={t("stress_test.req_per_sec")}
            value={test.requestsPerSecond?.toFixed(2) || "0"}
            suffix="req/s"
            valueStyle={{ color: "#52c41a" }}
          />
        </Col>

        <Col xs={24} sm={12}>
          <Statistic
            title={t("stress_test.error_rate")}
            value={test.errorRate?.toFixed(2) || "0"}
            suffix="%"
            valueStyle={{ color: test.errorRate > 5 ? "#f5222d" : "#52c41a" }}
          />
        </Col>

        <Col xs={24} sm={12}>
          <Statistic
            title={t("stress_test.avg_response_time")}
            value={test.avgResponseTimeMs?.toFixed(2) || "0"}
            suffix="ms"
            valueStyle={{ color: "#fff" }}
          />
        </Col>

        <Col span={24}>
          <Divider>{t("stress_test.response_time_dist")}</Divider>
          <Row gutter={[16, 16]}>
            <Col xs={12} sm={6}>
              <Statistic title={t("stress_test.min")} value={test.minResponseTimeMs?.toFixed(2) || "0"} suffix="ms" />
            </Col>
            <Col xs={12} sm={6}>
              <Statistic title="P50" value={test.p50ResponseTimeMs?.toFixed(2) || "0"} suffix="ms" />
            </Col>
            <Col xs={12} sm={6}>
              <Statistic title="P90" value={test.p90ResponseTimeMs?.toFixed(2) || "0"} suffix="ms" />
            </Col>
            <Col xs={12} sm={6}>
              <Statistic title="P99" value={test.p99ResponseTimeMs?.toFixed(2) || "0"} suffix="ms" />
            </Col>
          </Row>
        </Col>

        <Col span={24}>
          <Text type="secondary">{t("stress_test.target")}: {test.targetUrl}</Text>
          <br />
          <Text type="secondary">{t("stress_test.method")}: {test.method} | {t("stress_test.concurrent")}: {test.concurrentUsers}</Text>
          <br />
          <Text type="secondary">
            {test.startTime && `${t("stress_test.started")}: ${new Date(test.startTime).toLocaleString()}`}
            {test.endTime && ` | ${t("stress_test.ended")}: ${new Date(test.endTime).toLocaleString()}`}
          </Text>
        </Col>
      </Row>
    </Card>
  );

  const renderHistoryTab = () => (
    <div>
      {/* Running test display */}
      {renderRunningTest()}

      {/* Completed tests */}
      <Spin spinning={loading}>
        {tests.length === 0 ? (
          <Alert
            type="info"
            message={t("stress_test.no_tests")}
            description={t("stress_test.no_tests_desc")}
            showIcon
          />
        ) : (
          tests
            .filter((t) => t.status !== "RUNNING")
            .map(renderCompletedTest)
        )}
      </Spin>
    </div>
  );

  const renderCreateTab = () => (
    <Space direction="vertical" size="large" style={{ width: "100%" }}>
      {/* Preset Templates */}
      <Card title={<Space><RocketOutlined />{t("stress_test.quick_start_templates")}</Space>} style={{ background: "rgba(255,255,255,0.05)" }}>
        <Row gutter={[16, 16]}>
          {TEST_TEMPLATES.map((template) => (
            <Col xs={24} sm={12} md={6} lg={4} key={template.key}>
              <Card
                hoverable
                size="small"
                onClick={() => {
                  createForm.setFieldsValue({
                    ...template.config,
                  });
                  message.success(`${t("stress_test.template_applied")}: ${t(template.nameKey)}`);
                }}
                style={{ background: "rgba(255,255,255,0.08)", borderColor: "#165DFF" }}
              >
                <Space direction="vertical" style={{ width: "100%" }} size="small">
                  <Space>
                    {template.icon}
                    <Text strong style={{ color: "#fff" }}>{t(template.nameKey)}</Text>
                  </Space>
                  <Text type="secondary" style={{ fontSize: 12 }}>{t(template.descriptionKey)}</Text>
                  <Tooltip title={t(template.scenarioKey)}>
                    <Tag color="green" style={{ cursor: "help" }}>{t("stress_test.scenario")}</Tag>
                  </Tooltip>
                  <Tag color="blue">{template.config.concurrentUsers} {t("stress_test.users")}</Tag>
                </Space>
              </Card>
            </Col>
          ))}
        </Row>
      </Card>

      {/* Test Configuration Form */}
      <Card 
        title={<Space><SettingOutlined />{t("stress_test.test_configuration")}</Space>} 
        style={{ background: "rgba(255,255,255,0.05)" }}
        extra={
          <Button icon={<ReloadOutlined />} onClick={() => createForm.resetFields()}>
            {t("stress_test.reset")}
          </Button>
        }
      >
        <Form form={createForm} layout="vertical" onFinish={handleCreateTest}>
          <Collapse defaultActiveKey={["basic"]} ghost>
            <Panel header={<Space><InfoCircleOutlined />{t("stress_test.basic_settings")}</Space>} key="basic">
              <Form.Item 
                name="testName" 
                label={t("stress_test.test_name")}
                rules={[{ required: true, message: t("stress_test.test_name_required") || "Please enter test name" }]}
              >
                <Input placeholder={t("stress_test.test_placeholder")} />
              </Form.Item>

              <Form.Item 
                name="targetUrl" 
                label={t("stress_test.target_url")} 
                rules={[
                  { required: true, message: t("stress_test.target_url_required") || "Please enter target URL" },
                  { pattern: /^https?:\/\/.+/, message: t("stress_test.url_format_error") || "URL must start with http:// or https://" }
                ]}
                help={t("stress_test.target_url_help")}
              >
                <Input placeholder="http://localhost:8080" />
              </Form.Item>

              <Form.Item 
                name="path" 
                label={t("stress_test.test_path")}
                rules={[{ required: true, message: t("stress_test.test_path_required") || "Please enter test path" }]}
                help={t("stress_test.test_path_help")}
              >
                <Input placeholder="/api/test" />
              </Form.Item>

              <Row gutter={16}>
                <Col span={12}>
                  <Form.Item name="method" label={t("stress_test.http_method")} initialValue="GET">
                    <Select>
                      <Select.Option value="GET">GET</Select.Option>
                      <Select.Option value="POST">POST</Select.Option>
                      <Select.Option value="PUT">PUT</Select.Option>
                      <Select.Option value="DELETE">DELETE</Select.Option>
                      <Select.Option value="PATCH">PATCH</Select.Option>
                    </Select>
                  </Form.Item>
                </Col>
                <Col span={12}>
                  <Form.Item 
                    name="requestTimeoutSeconds" 
                    label={t("stress_test.request_timeout")}
                    initialValue={30}
                    rules={[{ required: true }]}
                  >
                    <InputNumber min={1} max={300} style={{ width: "100%" }} />
                  </Form.Item>
                </Col>
              </Row>
            </Panel>
          </Collapse>

          <Divider style={{ textAlign: 'left' }}>{t("stress_test.load_parameters")}</Divider>

          <Alert
            type="info"
            showIcon
            style={{ marginBottom: 16 }}
            message={t("stress_test.mode_hint_title") || "Test Mode Selection"}
            description={t("stress_test.mode_hint_desc") || "Duration mode and Request count mode are mutually exclusive. Fill in 'Duration' to run for a specified time, or fill in 'Total Requests' to run until reaching the request count. If both are filled, the test will stop when either condition is met."}
          />

          <Row gutter={[16, 0]}>
            <Col xs={24} sm={12} md={8}>
              <Form.Item
                name="concurrentUsers"
                label={
                  <Space>
                    {t("stress_test.concurrent_users")}
                    <Tooltip title={t("stress_test.concurrent_users_tooltip") || "Number of simulated concurrent users"}>
                      <InfoCircleOutlined style={{ color: "rgba(255,255,255,0.45)" }} />
                    </Tooltip>
                  </Space>
                }
                initialValue={10}
                rules={[{ required: true, message: t("stress_test.concurrent_users_required") || "Please enter concurrent users" }]}
              >
                <InputNumber min={1} max={500} style={{ width: "100%" }} />
              </Form.Item>
            </Col>
            <Col xs={24} sm={12} md={8}>
              <Form.Item
                name="totalRequests"
                label={
                  <Space>
                    {t("stress_test.total_requests")}
                    <Tooltip title={t("stress_test.total_requests_tooltip") || "Leave empty if using duration-based test"}>
                      <InfoCircleOutlined style={{ color: "rgba(255,255,255,0.45)" }} />
                    </Tooltip>
                  </Space>
                }
              >
                <InputNumber 
                  min={1} 
                  max={1000000} 
                  placeholder={t("stress_test.unlimited") || "Unlimited"} 
                  style={{ width: "100%" }}
                  onChange={(value) => {
                    if (value !== null && value !== undefined) {
                      createForm.setFieldValue("durationSeconds", undefined);
                    }
                  }}
                />
              </Form.Item>
            </Col>
            <Col xs={24} sm={12} md={8}>
              <Form.Item
                name="durationSeconds"
                label={
                  <Space>
                    {t("stress_test.duration_seconds")}
                    <Tooltip title={t("stress_test.duration_tooltip") || "Test duration. Leave empty for request-count based test"}>
                      <InfoCircleOutlined style={{ color: "rgba(255,255,255,0.45)" }} />
                    </Tooltip>
                  </Space>
                }
              >
                <InputNumber 
                  min={1} 
                  max={3600} 
                  placeholder={t("stress_test.unlimited") || "Unlimited"} 
                  style={{ width: "100%" }}
                  onChange={(value) => {
                    if (value !== null && value !== undefined) {
                      createForm.setFieldValue("totalRequests", undefined);
                    }
                  }}
                />
              </Form.Item>
            </Col>
          </Row>

          <Row gutter={[16, 0]}>
            <Col xs={24} sm={12} md={8}>
              <Form.Item 
                name="targetQps" 
                label={
                  <Space>
                    {t("stress_test.target_qps")}
                    <Tooltip title={t("stress_test.target_qps_tooltip") || "Queries per second limit. Leave empty for maximum speed"}>
                      <InfoCircleOutlined style={{ color: "rgba(255,255,255,0.45)" }} />
                    </Tooltip>
                  </Space>
                }
              >
                <InputNumber min={1} max={10000} placeholder={t("stress_test.no_limit") || "No limit"} style={{ width: "100%" }} />
              </Form.Item>
            </Col>
            <Col xs={24} sm={12} md={8}>
              <Form.Item 
                name="rampUpSeconds" 
                label={
                  <Space>
                    {t("stress_test.ramp_up")}
                    <Tooltip title={t("stress_test.ramp_up_tooltip") || "Time to gradually increase from 1 to target concurrency"}>
                      <InfoCircleOutlined style={{ color: "rgba(255,255,255,0.45)" }} />
                    </Tooltip>
                  </Space>
                } 
                initialValue={0}
              >
                <InputNumber min={0} max={300} style={{ width: "100%" }} />
              </Form.Item>
            </Col>
          </Row>

          <Collapse ghost style={{ marginTop: 16 }}>
            <Panel header={<Space><SettingOutlined />{t("stress_test.advanced_options")}</Space>} key="advanced">
              <Form.Item name="headers" label={t("stress_test.headers")} help={t("stress_test.headers_help_json")}>
                <TextArea rows={3} placeholder='{"Authorization": "Bearer token", "Content-Type": "application/json"}' />
              </Form.Item>

              <Form.Item name="body" label={t("stress_test.request_body")} help={t("stress_test.body_help")}>
                <TextArea rows={4} placeholder='{"key": "value"}' />
              </Form.Item>
            </Panel>
          </Collapse>

          <Divider />

          <Form.Item>
            <Space>
              <Button type="primary" htmlType="submit" icon={<PlayCircleOutlined />} loading={creating} size="large">
                {t("stress_test.start_test")}
              </Button>
              <Button onClick={() => createForm.resetFields()} size="large">
                Reset
              </Button>
            </Space>
          </Form.Item>
        </Form>
      </Card>
    </Space>
  );

  const renderQuickTestModal = () => (
    <Modal
      title={
        <Space>
          <ThunderboltOutlined style={{ color: "#165DFF" }} />
          {t("stress_test.quick_test_title")}
        </Space>
      }
      open={quickModalVisible}
      onCancel={() => setQuickModalVisible(false)}
      onOk={handleQuickTest}
      okText={t("stress_test.start_quick_test")}
    >
      <Paragraph style={{ color: "rgba(255,255,255,0.65)" }}>
        {t("stress_test.quick_test_desc")}
      </Paragraph>

      <Form layout="vertical">
        <Form.Item label={t("stress_test.num_requests")}>
          <InputNumber
            value={quickRequests}
            onChange={(value) => setQuickRequests(value || 100)}
            min={10}
            max={10000}
            style={{ width: "100%" }}
          />
        </Form.Item>

        <Form.Item label={t("stress_test.concurrent_users")}>
          <InputNumber
            value={quickConcurrent}
            onChange={(value) => setQuickConcurrent(value || 10)}
            min={1}
            max={50}
            style={{ width: "100%" }}
          />
        </Form.Item>
      </Form>
    </Modal>
  );

  const renderAnalysisModal = () => (
    <Modal
      title={
        <Space>
          <RobotOutlined style={{ color: "#52c41a" }} />
          {t("stress_test.ai_analysis_title")} - Test #{analysisTestId}
        </Space>
      }
      open={analysisModalVisible}
      onCancel={() => setAnalysisModalVisible(false)}
      width={700}
      footer={[
        <Button key="close" onClick={() => setAnalysisModalVisible(false)}>
          {t("common.cancel")}
        </Button>,
      ]}
    >
      <Spin spinning={analysisLoading}>
        {analysisResult ? (
          <div style={{ color: "rgba(255,255,255,0.85)" }}>
            <AiReportRenderer content={analysisResult} />
          </div>
        ) : (
          <Alert type="info" message={t("stress_test.waiting_analysis")} showIcon />
        )}
      </Spin>
    </Modal>
  );

  const tabItems = [
    {
      key: "history",
      label: (
        <span>
          <LineChartOutlined />
          {t("stress_test.history")}
        </span>
      ),
    },
    {
      key: "create",
      label: (
        <span>
          <ThunderboltOutlined />
          {t("stress_test.create_test")}
        </span>
      ),
    },
  ];

  return (
    <div style={{ padding: "24px" }}>
      {/* Header */}
      <div style={{ marginBottom: "24px" }}>
        <Space style={{ width: "100%", justifyContent: "space-between" }}>
          <Title level={3} style={{ color: "#fff" }}>
            {t("stress_test.title")}
          </Title>
          <Space>
            <Button
              type="primary"
              icon={<ThunderboltOutlined />}
              onClick={() => setQuickModalVisible(true)}
            >
              {t("stress_test.quick_test")}
            </Button>
            <Button icon={<ReloadOutlined />} onClick={loadTests}>
              {t("stress_test.refresh")}
            </Button>
          </Space>
        </Space>
        <Text style={{ color: "rgba(255,255,255,0.65)" }}>
          {t("stress_test.subtitle")}
        </Text>
      </div>

      {/* Tabs */}
      <Tabs
        activeKey={activeTab}
        onChange={setActiveTab}
        items={tabItems}
        tabBarStyle={{ marginBottom: "16px" }}
      />

      {activeTab === "history" && renderHistoryTab()}
      {activeTab === "create" && renderCreateTab()}

      {/* Modals */}
      {renderQuickTestModal()}
      {renderAnalysisModal()}
    </div>
  );
};

export default StressTestPage;