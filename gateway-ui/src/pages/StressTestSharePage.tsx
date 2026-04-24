import React, { useState, useEffect } from "react";
import { Card, Spin, Alert, Typography, Tag, Descriptions, Divider, Button, Space } from "antd";
import { CheckCircleOutlined, CloseCircleOutlined, ClockCircleOutlined, ThunderboltOutlined, DownloadOutlined } from "@ant-design/icons";
import axios from "axios";
import AiReportRenderer from "../components/AiReportRenderer";
import "../styles/ai-report.css";

const { Title, Text } = Typography;

interface StressTest {
  id: number;
  testName: string;
  targetUrl: string;
  method: string;
  status: string;
  startTime: string;
  endTime: string;
  actualRequests: number;
  successfulRequests: number;
  failedRequests: number;
  errorRate: number;
  minResponseTimeMs: number;
  maxResponseTimeMs: number;
  avgResponseTimeMs: number;
  p50ResponseTimeMs: number;
  p90ResponseTimeMs: number;
  p95ResponseTimeMs: number;
  p99ResponseTimeMs: number;
  requestsPerSecond: number;
  concurrentUsers: number;
  durationSeconds: number;
  totalRequests: number;
  aiAnalysisResult: string;
}

interface Props {
  shareId: string;
}

const StressTestSharePage: React.FC<Props> = ({ shareId }) => {
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [test, setTest] = useState<StressTest | null>(null);
  const [markdown, setMarkdown] = useState<string>("");

  useEffect(() => {
    loadSharedReport();
  }, [shareId]);

  const loadSharedReport = async () => {
    setLoading(true);
    setError(null);

    try {
      const response = await axios.get(`/api/stress-test/share/${shareId}`);

      if (response.data.success) {
        setTest(response.data.test);
        setMarkdown(response.data.markdown);
      } else {
        setError(response.data.error || "Failed to load report");
      }
    } catch (err: any) {
      setError(err.response?.data?.error || "Share link not found or expired");
    } finally {
      setLoading(false);
    }
  };

  const handleDownload = () => {
    if (!markdown) return;

    const blob = new Blob([markdown], { type: "text/markdown" });
    const url = window.URL.createObjectURL(blob);
    const link = document.createElement("a");
    link.href = url;
    link.setAttribute("download", `stress-test-report-${shareId}.md`);
    document.body.appendChild(link);
    link.click();
    link.remove();
    window.URL.revokeObjectURL(url);
  };

  const getStatusColor = (status: string) => {
    switch (status) {
      case "COMPLETED": return "success";
      case "STOPPED": return "warning";
      case "FAILED": return "error";
      default: return "default";
    }
  };

  const formatDate = (dateStr: string) => {
    if (!dateStr) return "N/A";
    return new Date(dateStr).toLocaleString();
  };

  if (loading) {
    return (
      <div style={{ 
        display: "flex", 
        justifyContent: "center", 
        alignItems: "center", 
        minHeight: "100vh",
        background: "#0f172a"
      }}>
        <Spin size="large" />
      </div>
    );
  }

  if (error) {
    return (
      <div style={{ 
        padding: "48px", 
        maxWidth: "600px", 
        margin: "0 auto",
        background: "#0f172a",
        minHeight: "100vh"
      }}>
        <Alert
          type="error"
          message="Report Not Available"
          description={error}
          showIcon
        />
      </div>
    );
  }

  return (
    <div style={{ 
      padding: "24px", 
      maxWidth: "1200px", 
      margin: "0 auto",
      background: "#0f172a",
      minHeight: "100vh"
    }}>
      <Card 
        style={{ 
          background: "rgba(30, 41, 59, 0.8)", 
          border: "1px solid rgba(148, 163, 184, 0.2)",
          marginBottom: "24px"
        }}
      >
        <div style={{ display: "flex", justifyContent: "space-between", alignItems: "center", marginBottom: "16px" }}>
          <Title level={3} style={{ margin: 0, color: "#f1f5f9" }}>
            <ThunderboltOutlined style={{ marginRight: "8px", color: "#3b82f6" }} />
            {test?.testName || "Stress Test Report"}
          </Title>
          <Space>
            <Tag color={getStatusColor(test?.status || "")}>{test?.status}</Tag>
            <Button icon={<DownloadOutlined />} onClick={handleDownload}>
              Download
            </Button>
          </Space>
        </div>

        <Descriptions 
          column={2} 
          bordered 
          size="small"
          styles={{
            label: { background: "rgba(15, 23, 42, 0.6)", color: "#94a3b8" },
            content: { background: "transparent", color: "#f1f5f9" }
          }}
        >
          <Descriptions.Item label="Target URL">{test?.targetUrl}</Descriptions.Item>
          <Descriptions.Item label="Method">{test?.method}</Descriptions.Item>
          <Descriptions.Item label="Start Time">{formatDate(test?.startTime || "")}</Descriptions.Item>
          <Descriptions.Item label="End Time">{formatDate(test?.endTime || "")}</Descriptions.Item>
          <Descriptions.Item label="Concurrent Users">{test?.concurrentUsers}</Descriptions.Item>
          <Descriptions.Item label="Duration">{test?.durationSeconds ? `${test.durationSeconds}s` : test?.totalRequests ? `${test.totalRequests} requests` : "N/A"}</Descriptions.Item>
        </Descriptions>

        <Divider style={{ borderColor: "rgba(148, 163, 184, 0.2)" }} />

        <Title level={5} style={{ color: "#f1f5f9", marginBottom: "16px" }}>Test Results</Title>

        <div style={{ display: "grid", gridTemplateColumns: "repeat(auto-fit, minmax(200px, 1fr))", gap: "16px" }}>
          <Card size="small" style={{ background: "rgba(15, 23, 42, 0.6)", border: "none" }}>
            <div style={{ textAlign: "center" }}>
              <Text type="secondary">Total Requests</Text>
              <div style={{ fontSize: "24px", fontWeight: "bold", color: "#f1f5f9" }}>{test?.actualRequests}</div>
              <Space style={{ marginTop: "8px" }}>
                <Tag color="success" icon={<CheckCircleOutlined />}>{test?.successfulRequests} success</Tag>
                <Tag color="error" icon={<CloseCircleOutlined />}>{test?.failedRequests} failed</Tag>
              </Space>
            </div>
          </Card>

          <Card size="small" style={{ background: "rgba(15, 23, 42, 0.6)", border: "none" }}>
            <div style={{ textAlign: "center" }}>
              <Text type="secondary">Requests/Second</Text>
              <div style={{ fontSize: "24px", fontWeight: "bold", color: "#22c55e" }}>{test?.requestsPerSecond?.toFixed(2)}</div>
              <Text type="secondary" style={{ fontSize: "12px" }}>req/s</Text>
            </div>
          </Card>

          <Card size="small" style={{ background: "rgba(15, 23, 42, 0.6)", border: "none" }}>
            <div style={{ textAlign: "center" }}>
              <Text type="secondary">Error Rate</Text>
              <div style={{ fontSize: "24px", fontWeight: "bold", color: test?.errorRate > 5 ? "#ef4444" : "#22c55e" }}>
                {test?.errorRate?.toFixed(2)}%
              </div>
            </div>
          </Card>

          <Card size="small" style={{ background: "rgba(15, 23, 42, 0.6)", border: "none" }}>
            <div style={{ textAlign: "center" }}>
              <Text type="secondary">Avg Response Time</Text>
              <div style={{ fontSize: "24px", fontWeight: "bold", color: "#f1f5f9" }}>{test?.avgResponseTimeMs?.toFixed(2)}</div>
              <Text type="secondary" style={{ fontSize: "12px" }}>ms</Text>
            </div>
          </Card>
        </div>

        <Divider style={{ borderColor: "rgba(148, 163, 184, 0.2)", margin: "24px 0" }} />

        <Title level={5} style={{ color: "#f1f5f9", marginBottom: "16px" }}>Response Time Distribution</Title>

        <div style={{ display: "grid", gridTemplateColumns: "repeat(4, 1fr)", gap: "12px" }}>
          {[
            { label: "Min", value: test?.minResponseTimeMs },
            { label: "P50", value: test?.p50ResponseTimeMs },
            { label: "P90", value: test?.p90ResponseTimeMs },
            { label: "P95", value: test?.p95ResponseTimeMs },
            { label: "P99", value: test?.p99ResponseTimeMs },
            { label: "Max", value: test?.maxResponseTimeMs },
          ].map((item) => (
            <div key={item.label} style={{ textAlign: "center", padding: "12px", background: "rgba(15, 23, 42, 0.6)", borderRadius: "8px" }}>
              <Text type="secondary">{item.label}</Text>
              <div style={{ fontSize: "18px", fontWeight: "bold", color: "#f1f5f9" }}>
                {item.value?.toFixed(2) || "0"}
              </div>
              <Text type="secondary" style={{ fontSize: "12px" }}>ms</Text>
            </div>
          ))}
        </div>
      </Card>

      {test?.aiAnalysisResult && (
        <Card 
          title={
            <Space>
              <ClockCircleOutlined style={{ color: "#3b82f6" }} />
              <Title level={4} style={{ margin: 0, color: "#f1f5f9" }}>AI Analysis</Title>
            </Space>
          }
          style={{ 
            background: "rgba(30, 41, 59, 0.8)", 
            border: "1px solid rgba(148, 163, 184, 0.2)"
          }}
        >
          <AiReportRenderer content={test.aiAnalysisResult} />
        </Card>
      )}

      <div style={{ textAlign: "center", marginTop: "24px", color: "#64748b" }}>
        <Text type="secondary">
          Generated by Gateway Admin • {new Date().toLocaleString()}
        </Text>
      </div>
    </div>
  );
};

export default StressTestSharePage;