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
} from "@ant-design/icons";
import axios from "axios";
import ReactMarkdown from "react-markdown";
import { useTranslation } from "react-i18next";

const { Title, Text, Paragraph } = Typography;
const { TextArea } = Input;

interface StressTest {
  id: number;
  instanceId: string;
  testName: string;
  targetUrl: string;
  method: string;
  concurrentUsers: number;
  totalRequests: number;
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
  createdAt: string;
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
      const response = await axios.get(`/api/stress-test/${testId}/status`);
      setRunningStatus(response.data);

      if (response.data.status !== "RUNNING") {
        setRunningTestId(null);
        setRunningStatus(null);
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
    try {
      const response = await axios.post("/api/stress-test/start", {
        ...values,
        headers: values.headers ? JSON.parse(values.headers) : null,
      }, {
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
            <Button icon={<RobotOutlined />} onClick={() => handleAnalyzeTest(test.id)}>
              {t("stress_test.ai_analysis")}
            </Button>
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
            suffix={`/ ${test.totalRequests}`}
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
    <Card style={{ background: "rgba(255,255,255,0.05)" }}>
      <Form form={createForm} layout="vertical" onFinish={handleCreateTest}>
        <Form.Item name="testName" label={t("stress_test.test_name")}>
          <Input placeholder="e.g., API Gateway Load Test" />
        </Form.Item>

        <Form.Item name="targetUrl" label={t("stress_test.target_url")} help={t("stress_test.target_url_help")}>
          <Input placeholder="e.g., http://gateway.example.com/api/test" />
        </Form.Item>

        <Form.Item name="path" label={t("stress_test.path")} help={t("stress_test.path_help")}>
          <Input placeholder="e.g., /api/users" />
        </Form.Item>

        <Form.Item name="method" label={t("stress_test.http_method")} initialValue="GET">
          <Radio.Group optionType="button" buttonStyle="solid">
            <Radio.Button value="GET">GET</Radio.Button>
            <Radio.Button value="POST">POST</Radio.Button>
            <Radio.Button value="PUT">PUT</Radio.Button>
            <Radio.Button value="DELETE">DELETE</Radio.Button>
          </Radio.Group>
        </Form.Item>

        <Form.Item name="headers" label={t("stress_test.headers")} help={t("stress_test.headers_help")}>
          <TextArea rows={3} placeholder='{"Authorization": "Bearer token"}' />
        </Form.Item>

        <Form.Item name="body" label={t("stress_test.request_body")}>
          <TextArea rows={4} placeholder="Request body for POST/PUT requests" />
        </Form.Item>

        <Row gutter={16}>
          <Col span={6}>
            <Form.Item
              name="concurrentUsers"
              label={t("stress_test.concurrent_users")}
              initialValue={10}
              rules={[{ required: true }]}
            >
              <InputNumber min={1} max={100} style={{ width: "100%" }} />
            </Form.Item>
          </Col>
          <Col span={6}>
            <Form.Item
              name="totalRequests"
              label={t("stress_test.total_requests")}
              initialValue={1000}
              rules={[{ required: true }]}
            >
              <InputNumber min={1} max={100000} style={{ width: "100%" }} />
            </Form.Item>
          </Col>
          <Col span={6}>
            <Form.Item name="targetQps" label={t("stress_test.target_qps")}>
              <InputNumber min={1} max={50000} placeholder="No limit" style={{ width: "100%" }} />
            </Form.Item>
          </Col>
          <Col span={6}>
            <Form.Item name="rampUpSeconds" label={t("stress_test.ramp_up")} initialValue={0}>
              <InputNumber min={0} max={60} style={{ width: "100%" }} />
            </Form.Item>
          </Col>
        </Row>

        <Form.Item>
          <Space>
            <Button type="primary" htmlType="submit" icon={<PlayCircleOutlined />} loading={creating}>
              {t("stress_test.start_test")}
            </Button>
            <Button onClick={() => createForm.resetFields()}>{t("common.reset")}</Button>
          </Space>
        </Form.Item>
      </Form>
    </Card>
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
            <ReactMarkdown>{analysisResult}</ReactMarkdown>
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