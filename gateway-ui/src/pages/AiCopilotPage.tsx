import React, { useState, useRef, useEffect } from "react";
import {
  Card,
  Input,
  Button,
  Space,
  Typography,
  message,
  Spin,
  Tabs,
  Modal,
  Form,
  Select,
  Tag,
  Divider,
  Empty,
  Tooltip,
  Alert,
  Radio,
  Descriptions,
} from "antd";
import {
  SendOutlined,
  RobotOutlined,
  UserOutlined,
  ClearOutlined,
  ThunderboltOutlined,
  BugOutlined,
  DashboardOutlined,
  BookOutlined,
  CopyOutlined,
  BulbOutlined,
  SettingOutlined,
  CheckCircleOutlined,
  ExclamationCircleOutlined,
  WarningOutlined,
} from "@ant-design/icons";
import axios from "axios";
import ReactMarkdown from "react-markdown";
import { useTranslation } from "react-i18next";

const { Title, Text, Paragraph } = Typography;
const { TextArea } = Input;

interface ChatMessage {
  role: "user" | "assistant";
  content: string;
  timestamp: Date;
}

interface PendingConfirmation {
  toolName: string;
  preview: {
    operationType?: string;
    riskLevel?: string;
    warning?: string;
    affectedRoutes?: string;
    confirmationPrompt?: string;
    routeId?: string;
    routeName?: string;
    routeIds?: string;
    newEnabledState?: boolean;
    logId?: number;
    [key: string]: any;
  };
  originalArgs?: Record<string, any>;
}

interface AiProvider {
  id: number;
  provider: string;
  providerName: string;
  region: string;
  model: string;
  isValid: boolean;
}

interface Props {
  instanceId: string;
}

const AiCopilotPage: React.FC<Props> = ({ instanceId }) => {
  const { t } = useTranslation();
  const [messages, setMessages] = useState<ChatMessage[]>([]);
  const [inputMessage, setInputMessage] = useState("");
  const [loading, setLoading] = useState(false);
  const [sessionId, setSessionId] = useState<string>("");
  const [activeTab, setActiveTab] = useState("chat");

  // AI Provider configuration
  const [providers, setProviders] = useState<AiProvider[]>([]);
  const [selectedProvider, setSelectedProvider] = useState<string>("");
  const [availableModels, setAvailableModels] = useState<string[]>([]);
  const [selectedModel, setSelectedModel] = useState<string>("");
  const [apiKey, setApiKey] = useState("");
  const [baseUrl, setBaseUrl] = useState("");
  const [validating, setValidating] = useState(false);
  const [validated, setValidated] = useState(false);
  const [configModalVisible, setConfigModalVisible] = useState(false);

  // Route generation
  const [routeDescription, setRouteDescription] = useState("");
  const [generatedRoute, setGeneratedRoute] = useState("");
  const [routeLoading, setRouteLoading] = useState(false);
  // Services for route generator
  const [services, setServices] = useState<{ serviceId: string; name: string }[]>([]);
  const [selectedServiceId, setSelectedServiceId] = useState<string>("");
  // Validation and apply
  const [validationResult, setValidationResult] = useState<{ valid: boolean; errors?: string[] } | null>(null);
  const [validatingRoute, setValidatingRoute] = useState(false);
  const [applyingRoute, setApplyingRoute] = useState(false);

  // Error analysis
  const [errorMessage, setErrorMessage] = useState("");
  const [errorAnalysis, setErrorAnalysis] = useState("");
  const [errorLoading, setErrorLoading] = useState(false);

  // Optimization
  const [optimizations, setOptimizations] = useState("");
  const [optimizationLoading, setOptimizationLoading] = useState(false);

  // Concept explanation
  const [conceptName, setConceptName] = useState("");
  const [conceptExplanation, setConceptExplanation] = useState("");
  const [conceptLoading, setConceptLoading] = useState(false);

  // Confirmation flow (二次确认)
  const [pendingConfirmation, setPendingConfirmation] = useState<PendingConfirmation | null>(null);
  const [confirmationModalVisible, setConfirmationModalVisible] = useState(false);
  const [confirmLoading, setConfirmLoading] = useState(false);

  const messagesEndRef = useRef<HTMLDivElement>(null);

  // Initialize session
  useEffect(() => {
    setSessionId(`session-${Date.now()}`);
    loadProviders();
    loadServices();
  }, []);

  // Load providers on mount
  const loadProviders = async () => {
    try {
      const response = await axios.get("/api/copilot/providers");
      setProviders(response.data);

      // Auto-select first valid provider
      const validProvider = response.data.find((p: AiProvider) => p.isValid);
      if (validProvider) {
        setSelectedProvider(validProvider.provider);
        setSelectedModel(validProvider.model);
        setValidated(true);
        loadModels(validProvider.provider);
      }
    } catch (error) {
      console.error("Failed to load providers", error);
    }
  };

  // Load services for route generator
  const loadServices = async () => {
    try {
      const response = await axios.get(`/api/services?instanceId=${instanceId}`);
      const serviceList = response.data?.data || [];
      setServices(serviceList.map((s: any) => ({
        serviceId: s.serviceId || s.name,
        name: s.name
      })));
    } catch (error) {
      console.error("Failed to load services", error);
    }
  };

  // Load models when provider changes
  const loadModels = async (provider: string) => {
    try {
      const response = await axios.get(`/api/copilot/providers/${provider}/models`);
      setAvailableModels(response.data);
    } catch (error) {
      console.error("Failed to load models", error);
    }
  };

  // Scroll to bottom when messages change
  useEffect(() => {
    messagesEndRef.current?.scrollIntoView({ behavior: "smooth" });
  }, [messages]);

  // Handle provider change
  const handleProviderChange = (provider: string) => {
    setSelectedProvider(provider);
    setSelectedModel("");
    setApiKey("");
    setBaseUrl("");
    setValidated(false);
    loadModels(provider);
    
    // Load saved config if exists
    const savedProvider = providers.find(p => p.provider === provider);
    if (savedProvider?.isValid && savedProvider.model) {
      setSelectedModel(savedProvider.model);
      setValidated(true);
    }
  };

  // Validate API Key
  const handleValidate = async () => {
    if (!apiKey) {
      message.warning(t("ai.please_input_api_key"));
      return;
    }

    setValidating(true);
    try {
      const response = await axios.post("/api/copilot/validate", {
        provider: selectedProvider,
        apiKey,
        baseUrl: baseUrl || null,
      });

      if (response.data.valid) {
        setValidated(true);
        message.success(t("ai.validate_success"));
      } else {
        setValidated(false);
        message.error(t("ai.validate_failed"));
      }
    } catch (error: any) {
      setValidated(false);
      message.error(error.response?.data?.message || t("ai.validate_failed"));
    } finally {
      setValidating(false);
    }
  };

  // Save configuration
  const handleSaveConfig = async () => {
    if (!validated || !selectedModel) {
      message.warning(t("ai.please_validate_first"));
      return;
    }

    try {
      await axios.post("/api/copilot/config", {
        provider: selectedProvider,
        model: selectedModel,
        apiKey,
        baseUrl: baseUrl || null,
      });
      message.success(t("common.success"));
      setConfigModalVisible(false);
      loadProviders();
    } catch (error) {
      message.error(t("common.error"));
    }
  };

  // Send chat message
  const handleSendMessage = async () => {
    if (!inputMessage.trim()) return;

    const userMessage: ChatMessage = {
      role: "user",
      content: inputMessage.trim(),
      timestamp: new Date(),
    };

    setMessages((prev) => [...prev, userMessage]);
    setInputMessage("");
    setLoading(true);

    try {
      const response = await axios.post("/api/copilot/chat", {
        sessionId,
        message: userMessage.content,
        context: "gateway",
        instanceId,
        provider: selectedProvider || undefined,
        model: selectedModel || undefined,
      });

      if (response.data.success) {
        const assistantMessage: ChatMessage = {
          role: "assistant",
          content: response.data.response,
          timestamp: new Date(),
        };
        setMessages((prev) => [...prev, assistantMessage]);
        setSessionId(response.data.sessionId);
      } else if (response.data.pendingConfirmation) {
        // Handle pending confirmation (二次确认)
        setPendingConfirmation({
          toolName: response.data.toolName,
          preview: response.data.confirmationPreview || {},
          originalArgs: response.data.originalArgs,
        });
        setConfirmationModalVisible(true);
        // Add a message to inform user
        const confirmMessage: ChatMessage = {
          role: "assistant",
          content: `⚠️ **操作需要确认**\n\n${response.data.confirmationPreview?.warning || '此操作需要您的确认。'}\n\n请在弹出的对话框中确认或取消。`,
          timestamp: new Date(),
        };
        setMessages((prev) => [...prev, confirmMessage]);
        setSessionId(response.data.sessionId);
      } else {
        message.error(response.data.error || t("copilot.chat_failed"));
      }
    } catch (error: any) {
      message.error(error.response?.data?.message || t("copilot.send_message_failed"));
    } finally {
      setLoading(false);
    }
  };

  // Handle confirmation execute
  const handleConfirmExecute = async () => {
    if (!pendingConfirmation) return;

    setConfirmLoading(true);
    try {
      const response = await axios.post("/api/copilot/chat", {
        sessionId,
        message: `确认执行 ${pendingConfirmation.toolName} 操作`,
        context: "gateway",
        instanceId,
        provider: selectedProvider || undefined,
        model: selectedModel || undefined,
        confirmed: true,
        toolName: pendingConfirmation.toolName,
        ...pendingConfirmation.originalArgs,
      });

      if (response.data.success) {
        message.success(t("copilot.operation_success") || "操作执行成功");
        const resultMessage: ChatMessage = {
          role: "assistant",
          content: response.data.response,
          timestamp: new Date(),
        };
        setMessages((prev) => [...prev, resultMessage]);
      } else {
        message.error(response.data.error || t("copilot.operation_failed") || "操作执行失败");
        const errorMessage: ChatMessage = {
          role: "assistant",
          content: `❌ **操作失败**\n\n${response.data.error}`,
          timestamp: new Date(),
        };
        setMessages((prev) => [...prev, errorMessage]);
      }
    } catch (error: any) {
      message.error(error.response?.data?.message || t("copilot.operation_failed"));
    } finally {
      setConfirmLoading(false);
      setConfirmationModalVisible(false);
      setPendingConfirmation(null);
    }
  };

  // Handle confirmation cancel
  const handleConfirmCancel = () => {
    const cancelMessage: ChatMessage = {
      role: "assistant",
      content: "✅ 操作已取消。如需重新执行，请再次提出请求。",
      timestamp: new Date(),
    };
    setMessages((prev) => [...prev, cancelMessage]);
    setConfirmationModalVisible(false);
    setPendingConfirmation(null);
  };

  // Clear conversation
  const handleClearConversation = async () => {
    try {
      await axios.delete(`/api/copilot/chat/${sessionId}`);
      setMessages([]);
      setSessionId(`session-${Date.now()}`);
      message.success(t("copilot.conversation_cleared"));
    } catch (error) {
      message.error(t("copilot.clear_failed"));
    }
  };

  // Generate route
  const handleGenerateRoute = async () => {
    if (!routeDescription.trim()) {
      message.warning(t("copilot.route_desc_required"));
      return;
    }

    setRouteLoading(true);
    setValidationResult(null); // Clear previous validation
    try {
      const response = await axios.post("/api/copilot/generate-route", {
        description: selectedServiceId ? `${routeDescription} (目标服务: ${selectedServiceId})` : routeDescription,
        instanceId,
        provider: selectedProvider || undefined,
        model: selectedModel || undefined,
      });

      if (response.data.success) {
        setGeneratedRoute(response.data.config);
      } else {
        message.error(response.data.error || t("copilot.route_gen_failed"));
      }
    } catch (error: any) {
      message.error(error.response?.data?.message || t("copilot.route_gen_error"));
    } finally {
      setRouteLoading(false);
    }
  };

  // Validate route configuration
  const handleValidateRoute = async () => {
    if (!generatedRoute.trim()) {
      message.warning(t("copilot.route_required") || "请先生成路由配置");
      return;
    }

    setValidatingRoute(true);
    try {
      const response = await axios.post("/api/copilot/validate-route", {
        routeJson: generatedRoute,
      });

      setValidationResult({
        valid: response.data.valid,
        errors: response.data.errors,
      });

      if (response.data.valid) {
        message.success(t("copilot.validation_passed") || "配置校验通过");
      } else {
        message.warning(t("copilot.validation_failed") || "配置校验失败");
      }
    } catch (error: any) {
      message.error(error.response?.data?.message || t("copilot.validation_error") || "校验失败");
    } finally {
      setValidatingRoute(false);
    }
  };

  // Apply route configuration
  const handleApplyRoute = async () => {
    if (!generatedRoute.trim()) {
      message.warning(t("copilot.route_required") || "请先生成路由配置");
      return;
    }

    setApplyingRoute(true);
    try {
      const response = await axios.post("/api/copilot/apply-route", {
        routeJson: generatedRoute,
        instanceId,
      });

      if (response.data.success) {
        message.success(t("copilot.route_applied") || `路由创建成功: ${response.data.routeName}`);
        setGeneratedRoute("");
        setValidationResult(null);
        setRouteDescription("");
        setSelectedServiceId("");
      } else {
        message.error(response.data.message || t("copilot.route_apply_failed") || "路由创建失败");
      }
    } catch (error: any) {
      message.error(error.response?.data?.message || t("copilot.route_apply_error") || "创建失败");
    } finally {
      setApplyingRoute(false);
    }
  };

  // Analyze error
  const handleAnalyzeError = async () => {
    if (!errorMessage.trim()) {
      message.warning(t("copilot.error_required"));
      return;
    }

    setErrorLoading(true);
    try {
      const response = await axios.post("/api/copilot/analyze-error", {
        errorMessage,
        instanceId,
        provider: selectedProvider || undefined,
        model: selectedModel || undefined,
      });

      if (response.data.success) {
        setErrorAnalysis(response.data.analysis);
      } else {
        message.error(response.data.error || t("copilot.error_analysis_failed"));
      }
    } catch (error: any) {
      message.error(error.response?.data?.message || t("copilot.error_analyze_error"));
    } finally {
      setErrorLoading(false);
    }
  };

  // Get optimization suggestions
  const handleGetOptimizations = async () => {
    setOptimizationLoading(true);
    try {
      const params = new URLSearchParams();
      if (selectedProvider) params.append("provider", selectedProvider);
      if (selectedModel) params.append("model", selectedModel);

      const response = await axios.get(
        `/api/copilot/optimizations/${instanceId}?${params.toString()}`
      );

      if (response.data.success) {
        setOptimizations(response.data.suggestions);
      } else {
        message.error(response.data.error || t("copilot.optimization_failed"));
      }
    } catch (error: any) {
      message.error(error.response?.data?.message || t("copilot.optimization_error"));
    } finally {
      setOptimizationLoading(false);
    }
  };

  // Explain concept
  const handleExplainConcept = async () => {
    if (!conceptName.trim()) {
      message.warning(t("copilot.concept_required"));
      return;
    }

    setConceptLoading(true);
    try {
      const params = new URLSearchParams({ concept: conceptName });
      if (instanceId) params.append("instanceId", instanceId);
      if (selectedProvider) params.append("provider", selectedProvider);
      if (selectedModel) params.append("model", selectedModel);

      const response = await axios.get(`/api/copilot/explain?${params.toString()}`);

      if (response.data.success) {
        setConceptExplanation(response.data.explanation);
      } else {
        message.error(t("copilot.explain_failed"));
      }
    } catch (error: any) {
      message.error(error.response?.data?.message || t("copilot.explain_error"));
    } finally {
      setConceptLoading(false);
    }
  };

  // Copy to clipboard
  const handleCopy = (text: string) => {
    navigator.clipboard.writeText(text);
    message.success(t("copilot.copied"));
  };

  // Common concepts
  const commonConcepts = [
    t("copilot.concept.route_predicate"),
    t("copilot.concept.gateway_filter"),
    t("copilot.concept.circuit_breaker"),
    t("copilot.concept.rate_limiter"),
    t("copilot.concept.load_balancer"),
    t("copilot.concept.service_discovery"),
    t("copilot.concept.retry_policy"),
    t("copilot.concept.timeout_config"),
  ];

  // Group providers by region
  const domesticProviders = providers.filter((p) => p.region === "DOMESTIC");
  const overseasProviders = providers.filter((p) => p.region === "OVERSEAS");

  // Render model config section
  const renderModelConfig = () => (
    <Card
      size="small"
      title={
        <Space>
          <SettingOutlined />
          {t("copilot.model_config") || "模型配置"}
        </Space>
      }
      extra={
        <Button
          size="small"
          icon={<SettingOutlined />}
          onClick={() => setConfigModalVisible(true)}
        >
          {t("copilot.configure") || "配置"}
        </Button>
      }
      style={{ marginBottom: "16px", background: "rgba(255,255,255,0.05)" }}
    >
      <Space wrap>
        <Text type="secondary">{t("copilot.current_model") || "当前模型"}:</Text>
        {selectedProvider && selectedModel ? (
          <Tag color="blue" icon={<CheckCircleOutlined />}>
            {providers.find((p) => p.provider === selectedProvider)?.providerName} - {selectedModel}
          </Tag>
        ) : (
          <Tag color="orange">{t("copilot.not_configured") || "未配置"}</Tag>
        )}
      </Space>
    </Card>
  );

  // Render confirmation modal (二次确认对话框)
  const renderConfirmationModal = () => (
    <Modal
      title={
        <Space>
          {pendingConfirmation?.preview?.riskLevel === 'HIGH'
            ? <ExclamationCircleOutlined style={{ color: '#ff4d4f' }} />
            : <WarningOutlined style={{ color: '#faad14' }} />
          }
          {pendingConfirmation?.preview?.operationType || t("copilot.confirm_title") || "操作确认"}
        </Space>
      }
      open={confirmationModalVisible}
      onCancel={handleConfirmCancel}
      footer={
        <Space>
          <Button onClick={handleConfirmCancel}>
            {t("copilot.confirm_cancel") || "取消"}
          </Button>
          <Button
            type="primary"
            danger={pendingConfirmation?.preview?.riskLevel === 'HIGH'}
            onClick={handleConfirmExecute}
            loading={confirmLoading}
          >
            {t("copilot.confirm_execute") || "确认执行"}
          </Button>
        </Space>
      }
      width={600}
    >
      {/* Risk level and details */}
      <Descriptions column={1} size="small" style={{ marginBottom: 16 }}>
        <Descriptions.Item label={t("copilot.risk_level") || "风险级别"}>
          <Tag color={
            pendingConfirmation?.preview?.riskLevel === 'HIGH' ? 'red' :
            pendingConfirmation?.preview?.riskLevel === 'MEDIUM' ? 'orange' : 'green'
          }>
            {pendingConfirmation?.preview?.riskLevel === 'HIGH' ? (t("copilot.risk_high") || "高危") :
             pendingConfirmation?.preview?.riskLevel === 'MEDIUM' ? (t("copilot.risk_medium") || "中危") :
             (t("copilot.risk_low") || "低危")}
          </Tag>
        </Descriptions.Item>
        {pendingConfirmation?.preview?.affectedRoutes && (
          <Descriptions.Item label={t("copilot.affected_routes") || "影响范围"}>
            {pendingConfirmation.preview.affectedRoutes}
          </Descriptions.Item>
        )}
        {pendingConfirmation?.preview?.routeId && (
          <Descriptions.Item label="路由ID">
            {pendingConfirmation.preview.routeId}
          </Descriptions.Item>
        )}
        {pendingConfirmation?.preview?.routeName && (
          <Descriptions.Item label="路由名称">
            {pendingConfirmation.preview.routeName}
          </Descriptions.Item>
        )}
        {pendingConfirmation?.preview?.routeIds && (
          <Descriptions.Item label="路由列表">
            {pendingConfirmation.preview.routeIds}
          </Descriptions.Item>
        )}
        {pendingConfirmation?.preview?.logId && (
          <Descriptions.Item label="审计日志ID">
            {pendingConfirmation.preview.logId}
          </Descriptions.Item>
        )}
      </Descriptions>

      <Divider />

      {/* Warning message */}
      {pendingConfirmation?.preview?.warning && (
        <Alert
          type="warning"
          showIcon
          message={t("copilot.warning") || "警告"}
          description={pendingConfirmation.preview.warning}
          style={{ marginBottom: 16 }}
        />
      )}

      {/* Confirmation prompt */}
      {pendingConfirmation?.preview?.confirmationPrompt && (
        <Alert
          type="info"
          showIcon
          message={t("copilot.please_confirm") || "请确认操作"}
          description={pendingConfirmation.preview.confirmationPrompt}
        />
      )}
    </Modal>
  );

  // Render config modal
  const renderConfigModal = () => (
    <Modal
      title={
        <Space>
          <SettingOutlined />
          {t("copilot.model_config") || "模型配置"}
        </Space>
      }
      open={configModalVisible}
      onCancel={() => setConfigModalVisible(false)}
      onOk={handleSaveConfig}
      okText={t("common.save")}
      cancelText={t("common.cancel")}
      width={600}
    >
      <Tabs
        items={[
          {
            key: "domestic",
            label: t("ai.domestic_models") || "国内大模型",
            children: (
              <div style={{ maxHeight: 200, overflow: "auto" }}>
                {domesticProviders.map((p) => (
                  <Card
                    key={p.provider}
                    hoverable
                    size="small"
                    style={{
                      marginBottom: 8,
                      borderColor: selectedProvider === p.provider ? "#1890ff" : undefined,
                    }}
                    onClick={() => handleProviderChange(p.provider)}
                  >
                    <Space>
                      <Tag color={p.isValid ? "success" : "default"}>{p.providerName}</Tag>
                      {p.isValid && <CheckCircleOutlined style={{ color: "#52c41a" }} />}
                    </Space>
                  </Card>
                ))}
              </div>
            ),
          },
          {
            key: "overseas",
            label: t("ai.overseas_models") || "国外大模型",
            children: (
              <div style={{ maxHeight: 200, overflow: "auto" }}>
                {overseasProviders.map((p) => (
                  <Card
                    key={p.provider}
                    hoverable
                    size="small"
                    style={{
                      marginBottom: 8,
                      borderColor: selectedProvider === p.provider ? "#1890ff" : undefined,
                    }}
                    onClick={() => handleProviderChange(p.provider)}
                  >
                    <Space>
                      <Tag color={p.isValid ? "success" : "default"}>{p.providerName}</Tag>
                      {p.isValid && <CheckCircleOutlined style={{ color: "#52c41a" }} />}
                    </Space>
                  </Card>
                ))}
              </div>
            ),
          },
        ]}
      />

      {selectedProvider && (
        <div style={{ marginTop: 16 }}>
          <Divider>{t("ai.config") || "配置"}</Divider>

          <Form layout="vertical">
            <Form.Item label={t("ai.select_model") || "选择模型"}>
              <Radio.Group
                value={selectedModel}
                onChange={(e) => setSelectedModel(e.target.value)}
                style={{ width: "100%" }}
              >
                <Space direction="vertical" style={{ width: "100%" }}>
                  {availableModels.map((m) => (
                    <Radio key={m} value={m}>
                      {m}
                    </Radio>
                  ))}
                </Space>
              </Radio.Group>
            </Form.Item>

            <Form.Item label="API Key">
              <Input.Password
                value={apiKey}
                onChange={(e) => {
                  setApiKey(e.target.value);
                  setValidated(false);
                }}
                placeholder={t("ai.api_key_placeholder") || "请输入API Key"}
                autoComplete="new-password"
                suffix={
                  validated ? (
                    <CheckCircleOutlined style={{ color: "#52c41a" }} />
                  ) : (
                    <Button
                      type="link"
                      size="small"
                      loading={validating}
                      onClick={handleValidate}
                    >
                      {t("ai.validate") || "验证"}
                    </Button>
                  )
                }
              />
            </Form.Item>

            <Form.Item label={t("ai.base_url") || "API地址（可选）"}>
              <Input
                value={baseUrl}
                onChange={(e) => setBaseUrl(e.target.value)}
                placeholder={t("ai.base_url_placeholder") || "留空使用默认地址"}
                autoComplete="off"
              />
            </Form.Item>
          </Form>
        </div>
      )}
    </Modal>
  );

  // Tab items
  const tabItems = [
    {
      key: "chat",
      label: (
        <span>
          <RobotOutlined />
          {t("copilot.tab_chat")}
        </span>
      ),
    },
    {
      key: "tools",
      label: (
        <span>
          <ThunderboltOutlined />
          {t("copilot.tab_tools")}
        </span>
      ),
    },
    {
      key: "learn",
      label: (
        <span>
          <BookOutlined />
          {t("copilot.tab_learn")}
        </span>
      ),
    },
  ];

  const renderChatTab = () => (
    <div style={{ display: "flex", flexDirection: "column", height: "calc(100vh - 350px)" }}>
      {/* Messages Area */}
      <div
        style={{
          flex: 1,
          overflowY: "auto",
          padding: "16px",
          background: "rgba(0,0,0,0.2)",
          borderRadius: "8px",
        }}
      >
        {messages.length === 0 ? (
          <Empty
            description={
              <span style={{ color: "rgba(255,255,255,0.5)" }}>
                {t("copilot.empty_message")}
              </span>
            }
            image={Empty.PRESENTED_IMAGE_SIMPLE}
          />
        ) : (
          messages.map((msg, index) => (
            <div
              key={index}
              style={{
                marginBottom: "16px",
                display: "flex",
                flexDirection: msg.role === "user" ? "row-reverse" : "row",
              }}
            >
              <div
                style={{
                  maxWidth: "80%",
                  padding: "12px 16px",
                  borderRadius: "8px",
                  background:
                    msg.role === "user"
                      ? "rgba(22, 93, 255, 0.2)"
                      : "rgba(255,255,255,0.05)",
                }}
              >
                <div
                  style={{
                    display: "flex",
                    alignItems: "center",
                    marginBottom: "8px",
                  }}
                >
                  {msg.role === "assistant" ? (
                    <RobotOutlined style={{ color: "#52c41a", marginRight: "8px" }} />
                  ) : (
                    <UserOutlined style={{ color: "#165DFF", marginRight: "8px" }} />
                  )}
                  <Text type="secondary" style={{ fontSize: "12px" }}>
                    {msg.timestamp.toLocaleTimeString()}
                  </Text>
                </div>
                <div style={{ color: "rgba(255,255,255,0.85)" }}>
                  <ReactMarkdown>{msg.content}</ReactMarkdown>
                </div>
              </div>
            </div>
          ))
        )}
        <div ref={messagesEndRef} />
        {loading && (
          <div style={{ textAlign: "center", padding: "16px" }}>
            <Spin tip={t("copilot.ai_thinking")} />
          </div>
        )}
      </div>

      {/* Input Area */}
      <div
        style={{
          marginTop: "16px",
          display: "flex",
          gap: "8px",
          alignItems: "flex-end",
        }}
      >
        <TextArea
          value={inputMessage}
          onChange={(e) => setInputMessage(e.target.value)}
          placeholder={t("copilot.input_placeholder")}
          autoSize={{ minRows: 1, maxRows: 4 }}
          onPressEnter={(e) => {
            if (!e.shiftKey) {
              e.preventDefault();
              handleSendMessage();
            }
          }}
          style={{
            flex: 1,
            background: "rgba(255,255,255,0.05)",
            border: "1px solid rgba(255,255,255,0.1)",
            color: "#fff",
          }}
        />
        <Button
          type="primary"
          icon={<SendOutlined />}
          onClick={handleSendMessage}
          loading={loading}
        >
          {t("copilot.send")}
        </Button>
        <Tooltip title={t("copilot.clear_conversation")}>
          <Button icon={<ClearOutlined />} onClick={handleClearConversation} />
        </Tooltip>
      </div>
    </div>
  );

  const renderToolsTab = () => (
    <div style={{ padding: "16px" }}>
      <Space direction="vertical" size="large" style={{ width: "100%" }}>
        {/* Route Generator */}
        <Card
          title={
            <span>
              <ThunderboltOutlined style={{ marginRight: "8px", color: "#165DFF" }} />
              {t("copilot.route_generator")}
            </span>
          }
          style={{ background: "rgba(255,255,255,0.05)" }}
        >
          <Paragraph style={{ color: "rgba(255,255,255,0.65)" }}>
            {t("copilot.route_generator_desc")}
          </Paragraph>

          {/* Service Selection */}
          <div style={{ marginBottom: "12px" }}>
            <Text style={{ color: "rgba(255,255,255,0.85)", marginRight: "8px" }}>
              {t("copilot.target_service") || "目标服务"}:
            </Text>
            <Select
              value={selectedServiceId}
              onChange={(value) => setSelectedServiceId(value)}
              placeholder={t("copilot.select_service") || "选择服务（可选）"}
              allowClear
              style={{ width: 200 }}
              options={services.map((s) => ({ value: s.serviceId, label: s.name }))}
            />
          </div>

          {/* Description Input */}
          <TextArea
            value={routeDescription}
            onChange={(e) => setRouteDescription(e.target.value)}
            placeholder={t("copilot.route_placeholder")}
            rows={3}
            style={{
              marginBottom: "16px",
              background: "rgba(0,0,0,0.2)",
              color: "#fff",
            }}
          />
          <Button
            type="primary"
            icon={<BulbOutlined />}
            onClick={handleGenerateRoute}
            loading={routeLoading}
          >
            {t("copilot.generate_route")}
          </Button>

          {generatedRoute && (
            <div style={{ marginTop: "16px" }}>
              <Divider />
              <div
                style={{
                  display: "flex",
                  justifyContent: "space-between",
                  alignItems: "center",
                  marginBottom: "8px",
                }}
              >
                <Text strong>{t("copilot.generated_config")}</Text>
                <Space>
                  <Button
                    size="small"
                    icon={<CheckCircleOutlined />}
                    onClick={handleValidateRoute}
                    loading={validatingRoute}
                    style={{
                      borderColor: validationResult?.valid ? "#52c41a" : undefined
                    }}
                  >
                    {t("copilot.validate") || "校验"}
                  </Button>
                  <Button
                    size="small"
                    icon={<CopyOutlined />}
                    onClick={() => handleCopy(generatedRoute)}
                  >
                    {t("common.copy")}
                  </Button>
                </Space>
              </div>

              {/* Editable JSON Editor */}
              <TextArea
                value={generatedRoute}
                onChange={(e) => {
                  setGeneratedRoute(e.target.value);
                  setValidationResult(null); // Clear validation on edit
                }}
                rows={12}
                style={{
                  background: "rgba(0,0,0,0.3)",
                  color: "#52c41a",
                  fontFamily: "monospace",
                  fontSize: "13px",
                }}
              />

              {/* Validation Result */}
              {validationResult && (
                <div style={{ marginTop: "12px" }}>
                  {validationResult.valid ? (
                    <Alert
                      type="success"
                      showIcon
                      message={t("copilot.validation_passed") || "配置校验通过"}
                      style={{ marginBottom: "12px" }}
                    />
                  ) : (
                    <Alert
                      type="error"
                      showIcon
                      message={t("copilot.validation_failed") || "配置校验失败"}
                      description={
                        validationResult.errors?.map((err, idx) => (
                          <div key={idx}>• {err}</div>
                        ))
                      }
                      style={{ marginBottom: "12px" }}
                    />
                  )}
                </div>
              )}

              {/* Apply Button */}
              <Button
                type="primary"
                icon={<CheckCircleOutlined />}
                onClick={handleApplyRoute}
                loading={applyingRoute}
                disabled={!validationResult?.valid}
                style={{ marginTop: "12px" }}
              >
                {t("copilot.apply_route") || "应用配置"}
              </Button>
            </div>
          )}
        </Card>

        {/* Error Analyzer */}
        <Card
          title={
            <span>
              <BugOutlined style={{ marginRight: "8px", color: "#f5222d" }} />
              {t("copilot.error_analyzer")}
            </span>
          }
          style={{ background: "rgba(255,255,255,0.05)" }}
        >
          <Paragraph style={{ color: "rgba(255,255,255,0.65)" }}>
            {t("copilot.error_analyzer_desc")}
          </Paragraph>
          <TextArea
            value={errorMessage}
            onChange={(e) => setErrorMessage(e.target.value)}
            placeholder={t("copilot.error_placeholder")}
            rows={4}
            style={{
              marginBottom: "16px",
              background: "rgba(0,0,0,0.2)",
              color: "#fff",
            }}
          />
          <Button
            type="primary"
            danger
            icon={<BugOutlined />}
            onClick={handleAnalyzeError}
            loading={errorLoading}
          >
            {t("copilot.analyze_error")}
          </Button>
          {errorAnalysis && (
            <div style={{ marginTop: "16px" }}>
              <Divider />
              <div style={{ color: "rgba(255,255,255,0.85)" }}>
                <ReactMarkdown>{errorAnalysis}</ReactMarkdown>
              </div>
            </div>
          )}
        </Card>

        {/* Optimization Suggestions */}
        <Card
          title={
            <span>
              <DashboardOutlined style={{ marginRight: "8px", color: "#52c41a" }} />
              {t("copilot.performance_optimizer")}
            </span>
          }
          style={{ background: "rgba(255,255,255,0.05)" }}
        >
          <Paragraph style={{ color: "rgba(255,255,255,0.65)" }}>
            {t("copilot.performance_desc")}
          </Paragraph>
          <Button
            type="primary"
            icon={<DashboardOutlined />}
            onClick={handleGetOptimizations}
            loading={optimizationLoading}
          >
            {t("copilot.get_optimizations")}
          </Button>
          {optimizations && (
            <div style={{ marginTop: "16px" }}>
              <Divider />
              <div style={{ color: "rgba(255,255,255,0.85)" }}>
                <ReactMarkdown>{optimizations}</ReactMarkdown>
              </div>
            </div>
          )}
        </Card>
      </Space>
    </div>
  );

  const renderLearnTab = () => (
    <div style={{ padding: "16px" }}>
      <Alert
        type="info"
        showIcon
        message={t("copilot.learn_title")}
        description={t("copilot.learn_desc")}
        style={{ marginBottom: "24px" }}
      />

      {/* Quick Concepts */}
      <Card
        title={t("copilot.common_concepts")}
        style={{ background: "rgba(255,255,255,0.05)", marginBottom: "24px" }}
      >
        <Space wrap>
          {commonConcepts.map((concept) => (
            <Tag
              key={concept}
              color="blue"
              style={{ cursor: "pointer", margin: "4px" }}
              onClick={() => {
                setConceptName(concept);
                handleExplainConcept();
              }}
            >
              {concept}
            </Tag>
          ))}
        </Space>
      </Card>

      {/* Custom Concept */}
      <Card
        title={t("copilot.custom_concept")}
        style={{ background: "rgba(255,255,255,0.05)" }}
      >
        <Space.Compact style={{ width: "100%" }}>
          <Input
            value={conceptName}
            onChange={(e) => setConceptName(e.target.value)}
            placeholder={t("copilot.concept_placeholder")}
            style={{ background: "rgba(0,0,0,0.2)", color: "#fff" }}
          />
          <Button
            type="primary"
            icon={<BookOutlined />}
            onClick={handleExplainConcept}
            loading={conceptLoading}
          >
            {t("copilot.explain")}
          </Button>
        </Space.Compact>

        {conceptExplanation && (
          <div style={{ marginTop: "24px" }}>
            <Divider />
            <Title level={5} style={{ color: "#fff" }}>
              {conceptName}
            </Title>
            <div style={{ color: "rgba(255,255,255,0.85)" }}>
              <ReactMarkdown>{conceptExplanation}</ReactMarkdown>
            </div>
          </div>
        )}
      </Card>
    </div>
  );

  return (
    <div style={{ padding: "24px" }}>
      <Card
        title={
          <span>
            <RobotOutlined style={{ marginRight: "8px", color: "#52c41a" }} />
            {t("copilot.title")}
          </span>
        }
        style={{ background: "rgba(255,255,255,0.05)" }}
      >
        {/* Model Configuration */}
        {renderModelConfig()}

        <Tabs
          activeKey={activeTab}
          onChange={setActiveTab}
          items={tabItems}
          tabBarStyle={{ marginBottom: "16px" }}
        />

        {activeTab === "chat" && renderChatTab()}
        {activeTab === "tools" && renderToolsTab()}
        {activeTab === "learn" && renderLearnTab()}
      </Card>

      {/* Config Modal */}
      {renderConfigModal()}

      {/* Confirmation Modal */}
      {renderConfirmationModal()}
    </div>
  );
};

export default AiCopilotPage;