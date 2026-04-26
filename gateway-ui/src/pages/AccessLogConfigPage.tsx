import { useState, useEffect } from 'react';
import {
  Card, Row, Col, Form, Input, Select, Button, Switch, InputNumber,
  Space, message, Spin, Divider, Typography, Tag, Alert, Tooltip,
  Tabs, Table, DatePicker, Statistic, Badge, Tooltip as AntTooltip,
  Descriptions, Segmented, Radio
} from 'antd';
import type { TabsProps } from 'antd';
import {
  FileTextOutlined, SettingOutlined, FolderOutlined,
  CloudServerOutlined, DesktopOutlined, ContainerOutlined,
  ReloadOutlined, SaveOutlined, InfoCircleOutlined,
  EyeOutlined, BarChartOutlined, FilterOutlined,
  SearchOutlined, ClockCircleOutlined, CopyOutlined,
  CheckCircleOutlined, CodeOutlined, FileOutlined, HistoryOutlined
} from '@ant-design/icons';
import api from '../utils/api';
import { useTranslation } from 'react-i18next';
import dayjs from 'dayjs';
import utc from 'dayjs/plugin/utc';

dayjs.extend(utc);

const { Title, Text } = Typography;
const { RangePicker } = DatePicker;

interface AccessLogConfig {
  enabled: boolean;
  deployMode: string;
  logDirectory: string;
  fileNamePattern: string;
  logFormat: string;
  logLevel: string;
  logRequestHeaders: boolean;
  logResponseHeaders: boolean;
  logRequestBody: boolean;
  logResponseBody: boolean;
  maxBodyLength: number;
  samplingRate: number;
  sensitiveFields: string[];
  maxFileSizeMb: number;
  maxBackupFiles: number;
  logToConsole: boolean;
  includeAuthInfo: boolean;
  outputTarget?: 'stdout' | 'file' | 'both';  // 日志输出目标
}

interface DeployMode {
  mode: string;
  description: string;
  defaultPath: string;
}

interface LogEntry {
  '@timestamp': string;
  traceId?: string;
  requestId?: string;
  routeId?: string;
  serviceId?: string;
  method: string;
  path: string;
  query?: string;
  clientIp?: string;
  userAgent?: string;
  statusCode: number;
  durationMs: number;
  authType?: string;
  authPolicy?: string;
  authUser?: string;
  requestHeaders?: Record<string, string>;
  responseHeaders?: Record<string, string>;
  requestBody?: string;
  responseBody?: string;
  errorMessage?: string;
}

interface LogStats {
  totalRequests: number;
  avgDuration: number;
  statusCodes: Record<string, number>;
  topPaths: Array<{ path: string; count: number }>;
  methods: Record<string, number>;
  file?: string;
}

interface AccessLogConfigPageProps {
  instanceId?: string;
}

const AccessLogConfigPage: React.FC<AccessLogConfigPageProps> = ({ instanceId }) => {
  const [loading, setLoading] = useState(false);
  const [saving, setSaving] = useState(false);
  const [config, setConfig] = useState<AccessLogConfig | null>(null);
  const [deployModes, setDeployModes] = useState<DeployMode[]>([]);
  const [form] = Form.useForm();
  const { t } = useTranslation();

  // Watch deployMode field for collection guide tab sync
  const watchedDeployMode = Form.useWatch('deployMode', form);

  // Log viewing state
  const [logLoading, setLogLoading] = useState(false);
  const [logEntries, setLogEntries] = useState<LogEntry[]>([]);
  const [logTotal, setLogTotal] = useState(0);
  const [logPage, setLogPage] = useState(0);
  const [logPageSize, setLogPageSize] = useState(20);
  const [logStats, setLogStats] = useState<LogStats | null>(null);
  const [filters, setFilters] = useState<{ 
    method?: string; 
    statusCode?: number; 
    path?: string; 
    traceId?: string;
    startTime?: string;
    endTime?: string;
  }>({});
  const [bodyViewMode, setBodyViewMode] = useState<'raw' | 'formatted'>('formatted');
  const [outputTarget, setOutputTarget] = useState<'stdout' | 'file' | 'both'>('file');
  const [showFileConfig, setShowFileConfig] = useState(true);  // 是否显示文件配置项

  // K8S Pod selection state
  const [k8sClusters, setK8sClusters] = useState<Array<{id: number; clusterName: string; enabled: boolean; connectionStatus: string}>>([]);
  const [clusterId, setClusterId] = useState<number | null>(null);
  const [k8sClustersLoading, setK8sClustersLoading] = useState(false);
  const [k8sPods, setK8sPods] = useState<Array<{name: string; namespace: string; phase: string; podIP: string}>>([]);
  const [selectedPod, setSelectedPod] = useState<string | null>(null);
  const [selectedNamespace, setSelectedNamespace] = useState<string>('');
  const [k8sNamespaces, setK8sNamespaces] = useState<string[]>([]);
  const [k8sPodsLoading, setK8sPodsLoading] = useState(false);
  const [k8sNamespacesLoading, setK8sNamespacesLoading] = useState(false);
  const [currentDeployMode, setCurrentDeployMode] = useState<string>('LOCAL'); // 当前选择的部署模式（用于日志查看Tab）
  const [logViewMode, setLogViewMode] = useState<'realtime' | 'history'>('realtime'); // 日志查看模式：实时/历史
  const [historyTimeRange, setHistoryTimeRange] = useState<string>('1h'); // 历史时间范围：1h/6h/12h/24h/7d/30d

  // Fluent Bit 配置模板常量
  const fluentBitK8sConfig = `# Fluent Bit DaemonSet 配置示例
[SERVICE]
    Flush         5
    Log_Level     info
    Parsers_File  parsers.conf

[INPUT]
    Name              tail
    Path              /var/log/containers/*gateway*.log
    Parser            json
    Tag               gateway.access
    Mem_Buf_Limit     50MB

[FILTER]
    Name              kubernetes
    Match             gateway.*
    Kube_URL          https://kubernetes.default.svc:443
    Kube_Tag_Prefix   gateway.access.

[OUTPUT]
    Name              http
    Match             gateway.*
    Host              gateway-admin-service
    Port              8080
    URI               /api/access-log/collect
    Format            json`;

  const fluentBitDockerConfig = `# Docker Compose sidecar 示例
services:
  gateway:
    volumes:
      - access-logs:/app/logs/access

  fluent-bit:
    image: fluent/fluent-bit:latest
    volumes:
      - access-logs:/app/logs/access
    config:
      [INPUT]
        Name    tail
        Path    /app/logs/access/access-*.log
        Parser  json

      [OUTPUT]
        Name    http
        Host    gateway-admin
        Port    8080`;

  const fluentBitLocalConfig = `# Fluent Bit 本地采集配置
[INPUT]
    Name    tail
    Path    ./logs/access/access-*.log
    Parser  json
    Tag     gateway.access

[OUTPUT]
    Name    http
    Host    localhost
    Port    8080
    URI     /api/access-log/collect`;

  const fluentBitCustomConfig = `# Fluent Bit 自定义路径配置
[INPUT]
    Name    tail
    Path    /your/custom/path/access-*.log
    Parser  json

[OUTPUT]
    Name    http
    Host    gateway-admin
    Port    8080`;

  // Kubernetes ConfigMap 格式（对运维同学非常友好）
  const fluentBitK8sConfigMap = `apiVersion: v1
kind: ConfigMap
metadata:
  name: fluent-bit-config
  namespace: gateway
data:
  fluent-bit.conf: |
    [SERVICE]
        Flush         5
        Log_Level     info
        Parsers_File  parsers.conf

    [INPUT]
        Name              tail
        Path              /var/log/containers/*gateway*.log
        Parser            json
        Tag               gateway.access
        Mem_Buf_Limit     50MB

    [FILTER]
        Name              kubernetes
        Match             gateway.*
        Kube_URL          https://kubernetes.default.svc:443
        Kube_Tag_Prefix   gateway.access.

    [OUTPUT]
        Name              http
        Match             gateway.*
        Host              gateway-admin-service
        Port              8080
        URI               /api/access-log/collect
        Format            json

  parsers.conf: |
    [PARSER]
        Name   json
        Format json`;

  // 一键复制配置模板
  const handleCopyConfig = (configText: string) => {
    navigator.clipboard.writeText(configText);
    message.success(t('access_log.config_copied'));
  };

  // Load config
  const loadConfig = async () => {
    try {
      setLoading(true);
      const params = instanceId ? `?instanceId=${instanceId}` : '';
      const res = await api.get(`/api/access-log/config${params}`);
      if (res.data.code === 200) {
        setConfig(res.data.data);
        form.setFieldsValue(res.data.data);

        // Set currentDeployMode
        setCurrentDeployMode(res.data.data.deployMode || 'LOCAL');

        // Infer outputTarget from existing config
        const logToConsole = res.data.data.logToConsole;
        const logDirectory = res.data.data.logDirectory;
        let inferredTarget: 'stdout' | 'file' | 'both';

        if (logToConsole && logDirectory) {
          inferredTarget = 'both';
        } else if (logToConsole) {
          inferredTarget = 'stdout';
        } else {
          inferredTarget = 'file';
        }

        setOutputTarget(inferredTarget);
        setShowFileConfig(inferredTarget !== 'stdout');
        form.setFieldsValue({ outputTarget: inferredTarget });
      }

      // Note: clusterId is now selected manually by user from cluster dropdown
      // (previously tried to get from instance via UUID, but caused type mismatch issues)
    } catch (e) {
      console.error('Failed to load config:', e);
      message.error(t('access_log.load_failed') || 'Failed to load configuration');
    } finally {
      setLoading(false);
    }
  };

  // Load deploy modes
  const loadDeployModes = async () => {
    try {
      const res = await api.get('/api/access-log/deploy-modes');
      if (res.data.code === 200) {
        setDeployModes(res.data.data);
      }
    } catch (e) {
      console.error('Failed to load deploy modes:', e);
    }
  };

  // Load log entries
  const loadLogEntries = async () => {
    try {
      setLogLoading(true);

      // 计算 sinceSeconds（用于 K8S Pod 历史查询）
      const getSinceSeconds = () => {
        switch (historyTimeRange) {
          case '1h': return 1 * 60 * 60;
          case '6h': return 6 * 60 * 60;
          case '12h': return 12 * 60 * 60;
          case '24h': return 24 * 60 * 60;
          case '7d': return 7 * 24 * 60 * 60;
          case '30d': return 30 * 24 * 60 * 60;
          default: return 1 * 60 * 60;
        }
      };

      // K8S 模式（实时和历史都从 Pod stdout 查询）
      if (currentDeployMode === 'K8S' && clusterId && selectedPod) {
        const params = new URLSearchParams();
        params.append('clusterId', clusterId.toString());
        params.append('namespace', selectedNamespace);
        params.append('podName', selectedPod);
        params.append('page', logPage.toString());
        params.append('size', logPageSize.toString());
        
        // 历史模式使用 sinceSeconds，实时模式使用 tailLines
        if (logViewMode === 'history') {
          params.append('sinceSeconds', getSinceSeconds().toString());
        } else {
          params.append('tailLines', '500');
        }
        
        if (filters.method) params.append('method', filters.method);
        if (filters.statusCode) params.append('statusCode', filters.statusCode.toString());
        if (filters.path) params.append('path', filters.path);
        if (filters.traceId) params.append('traceId', filters.traceId);

        const res = await api.get(`/api/access-log/k8s/entries?${params.toString()}`);
        if (res.data.code === 200) {
          setLogEntries(res.data.data.entries);
          setLogTotal(res.data.data.total);
        }
      } else if (logViewMode === 'history') {
        // 本地文件历史模式：从数据库查询（需要配置 Fluent Bit 收集）
        const params = new URLSearchParams();
        
        const now = new Date();
        let startTime: Date;
        switch (historyTimeRange) {
          case '1h': startTime = new Date(now.getTime() - 1 * 60 * 60 * 1000); break;
          case '6h': startTime = new Date(now.getTime() - 6 * 60 * 60 * 1000); break;
          case '12h': startTime = new Date(now.getTime() - 12 * 60 * 60 * 1000); break;
          case '24h': startTime = new Date(now.getTime() - 24 * 60 * 60 * 1000); break;
          case '7d': startTime = new Date(now.getTime() - 7 * 24 * 60 * 60 * 1000); break;
          case '30d': startTime = new Date(now.getTime() - 30 * 24 * 60 * 60 * 1000); break;
          default: startTime = new Date(now.getTime() - 1 * 60 * 60 * 1000);
        }
        params.append('startTime', startTime.toISOString());
        params.append('endTime', now.toISOString());
        
        params.append('page', logPage.toString());
        params.append('size', logPageSize.toString());
        if (filters.method) params.append('method', filters.method);
        if (filters.statusCode) params.append('statusCode', filters.statusCode.toString());
        if (filters.path) params.append('path', filters.path);
        if (filters.traceId) params.append('traceId', filters.traceId);
        if (instanceId) params.append('instanceId', instanceId);

        const res = await api.get(`/api/access-log/history/entries?${params.toString()}`);
        if (res.data.code === 200) {
          setLogEntries(res.data.data.entries);
          setLogTotal(res.data.data.total);
        }
      } else {
        // 本地文件实时模式
        const params = new URLSearchParams();
        if (filters.startTime) params.append('startTime', filters.startTime);
        if (filters.endTime) params.append('endTime', filters.endTime);
        params.append('page', logPage.toString());
        params.append('size', logPageSize.toString());
        if (filters.method) params.append('method', filters.method);
        if (filters.statusCode) params.append('statusCode', filters.statusCode.toString());
        if (filters.path) params.append('path', filters.path);
        if (filters.traceId) params.append('traceId', filters.traceId);

        const res = await api.get(`/api/access-log/entries?${params.toString()}`);
        if (res.data.code === 200) {
          setLogEntries(res.data.data.entries);
          setLogTotal(res.data.data.total);
        }
      }
    } catch (e) {
      console.error('Failed to load log entries:', e);
      message.error('Failed to load logs');
    } finally {
      setLogLoading(false);
    }
  };

  // Load log stats
  const loadLogStats = async () => {
    try {
      // 计算 sinceSeconds（用于 K8S Pod 历史查询）
      const getSinceSeconds = () => {
        switch (historyTimeRange) {
          case '1h': return 1 * 60 * 60;
          case '6h': return 6 * 60 * 60;
          case '12h': return 12 * 60 * 60;
          case '24h': return 24 * 60 * 60;
          case '7d': return 7 * 24 * 60 * 60;
          case '30d': return 30 * 24 * 60 * 60;
          default: return 1 * 60 * 60;
        }
      };

      // K8S 模式（实时和历史都从 Pod stdout 查询）
      if (currentDeployMode === 'K8S' && clusterId && selectedPod) {
        const params = new URLSearchParams();
        params.append('clusterId', clusterId.toString());
        params.append('namespace', selectedNamespace);
        params.append('podName', selectedPod);
        
        // 历史模式使用 sinceSeconds，实时模式使用 tailLines
        if (logViewMode === 'history') {
          params.append('sinceSeconds', getSinceSeconds().toString());
        } else {
          params.append('tailLines', '500');
        }
        
        const res = await api.get(`/api/access-log/k8s/stats?${params.toString()}`);
        if (res.data.code === 200) {
          setLogStats(res.data.data);
        }
      } else if (logViewMode === 'history') {
        // 本地文件历史模式：从数据库统计（需要配置 Fluent Bit 收集）
        const params = new URLSearchParams();
        
        const now = new Date();
        let startTime: Date;
        switch (historyTimeRange) {
          case '1h': startTime = new Date(now.getTime() - 1 * 60 * 60 * 1000); break;
          case '6h': startTime = new Date(now.getTime() - 6 * 60 * 60 * 1000); break;
          case '12h': startTime = new Date(now.getTime() - 12 * 60 * 60 * 1000); break;
          case '24h': startTime = new Date(now.getTime() - 24 * 60 * 60 * 1000); break;
          case '7d': startTime = new Date(now.getTime() - 7 * 24 * 60 * 60 * 1000); break;
          case '30d': startTime = new Date(now.getTime() - 30 * 24 * 60 * 60 * 1000); break;
          default: startTime = new Date(now.getTime() - 1 * 60 * 60 * 1000);
        }
        params.append('startTime', startTime.toISOString());
        params.append('endTime', now.toISOString());
        
        if (instanceId) params.append('instanceId', instanceId);
        const res = await api.get(`/api/access-log/history/stats?${params.toString()}`);
        if (res.data.code === 200) {
          setLogStats(res.data.data);
        }
      } else {
        // 本地文件实时模式
        const params = new URLSearchParams();
        if (filters.startTime) params.append('startTime', filters.startTime);
        if (filters.endTime) params.append('endTime', filters.endTime);
        const queryString = params.toString() ? `?${params.toString()}` : '';
        const res = await api.get(`/api/access-log/stats${queryString}`);
        if (res.data.code === 200) {
          setLogStats(res.data.data);
        }
      }
    } catch (e) {
      console.error('Failed to load log stats:', e);
    }
  };

  // Load K8s clusters
  const loadK8sClusters = async () => {
    try {
      setK8sClustersLoading(true);
      const res = await api.get('/api/kubernetes/clusters');
      if (res.data.code === 200) {
        const clusters = res.data.data || [];
        setK8sClusters(clusters);
        // Auto-select first enabled cluster
        const enabledCluster = clusters.find((c: {enabled: boolean}) => c.enabled);
        if (enabledCluster) {
          setClusterId(enabledCluster.id);
        }
      }
    } catch (e) {
      console.error('Failed to load K8s clusters:', e);
      message.error('Failed to load clusters');
    } finally {
      setK8sClustersLoading(false);
    }
  };

  // Load K8s namespaces
  const loadK8sNamespaces = async () => {
    if (!clusterId) return;
    try {
      setK8sNamespacesLoading(true);
      const res = await api.get(`/api/access-log/k8s/namespaces?clusterId=${clusterId}`);
      if (res.data.code === 200) {
        setK8sNamespaces(res.data.data || []);
      }
    } catch (e) {
      console.error('Failed to load K8s namespaces:', e);
      message.error('Failed to load namespaces');
    } finally {
      setK8sNamespacesLoading(false);
    }
  };

  // Load K8s gateway pods
  const loadK8sPods = async () => {
    if (!clusterId) return;
    try {
      setK8sPodsLoading(true);
      const params = new URLSearchParams();
      params.append('clusterId', clusterId.toString());
      if (selectedNamespace) params.append('namespace', selectedNamespace);
      const res = await api.get(`/api/access-log/k8s/pods?${params.toString()}`);
      if (res.data.code === 200) {
        setK8sPods(res.data.data || []);
        // Auto-select first pod if none selected
        if (res.data.data && res.data.data.length > 0 && !selectedPod) {
          setSelectedPod(res.data.data[0].name);
        }
      }
    } catch (e) {
      console.error('Failed to load K8s pods:', e);
      message.error('Failed to load gateway pods');
    } finally {
      setK8sPodsLoading(false);
    }
  };

  useEffect(() => {
    loadConfig();
    loadDeployModes();
    loadK8sClusters();
  }, []);

  // Load K8s namespaces and pods when deployMode changes to K8S and clusterId is set
  useEffect(() => {
    if (currentDeployMode === 'K8S' && clusterId) {
      loadK8sNamespaces();
      loadK8sPods();
    }
  }, [currentDeployMode, clusterId]);

  // Reload pods when namespace changes
  useEffect(() => {
    if (currentDeployMode === 'K8S' && clusterId && selectedNamespace) {
      // Clear selected pod when namespace changes (pods are namespace-specific)
      setSelectedPod(null);
      loadK8sPods();
    }
  }, [selectedNamespace]);

  useEffect(() => {
    // 历史模式直接加载，K8S实时模式需要 selectedPod
    if (logViewMode === 'history') {
      loadLogEntries();
      loadLogStats();
    } else if (currentDeployMode === 'K8S' && !selectedPod) {
      return;
    } else {
      loadLogEntries();
      loadLogStats();
    }
  }, [logPage, logPageSize, filters.method, filters.statusCode, filters.path, filters.traceId, selectedPod, currentDeployMode, logViewMode, historyTimeRange]);

  // Handle deploy mode change - auto-fill default path and set output target recommendation
  const handleDeployModeChange = (mode: string) => {
    // Update currentDeployMode for LogViewerTab
    setCurrentDeployMode(mode);

    const selectedMode = deployModes.find(m => m.mode === mode);
    if (selectedMode) {
      // Auto-fill default path
      form.setFieldsValue({ logDirectory: selectedMode.defaultPath });
    }

    // Auto-set output target based on deployment mode
    let recommendedTarget: 'stdout' | 'file' | 'both';
    let showFile = true;

    switch (mode) {
      case 'K8S':
        // Kubernetes: 强制使用 stdout (cloud-native best practice)
        // file 和 both 选项会被禁用
        recommendedTarget = 'stdout';
        showFile = false;
        form.setFieldsValue({ logToConsole: true });
        message.info(t('access_log.k8s_auto_switch_msg'));
        break;
      case 'DOCKER':
        // Docker: recommend both (stdout for collection + file for backup)
        recommendedTarget = 'both';
        showFile = true;
        form.setFieldsValue({ logToConsole: true });
        break;
      case 'LOCAL':
        // Local: recommend file
        recommendedTarget = 'file';
        showFile = true;
        form.setFieldsValue({ logToConsole: false });
        break;
      case 'CUSTOM':
        // Custom: keep current setting, show file config
        recommendedTarget = outputTarget;
        showFile = true;
        break;
      default:
        recommendedTarget = 'file';
        showFile = true;
    }

    setOutputTarget(recommendedTarget);
    setShowFileConfig(showFile);

    // Update outputTarget field in form
    form.setFieldsValue({ outputTarget: recommendedTarget });
  };

  // Handle output target change
  const handleOutputTargetChange = (target: 'stdout' | 'file' | 'both') => {
    setOutputTarget(target);

    // Update logToConsole based on target
    const logToConsole = target === 'stdout' || target === 'both';
    form.setFieldsValue({ logToConsole });

    // Show/hide file config
    setShowFileConfig(target !== 'stdout');
  };

  // Save config
  const handleSave = async () => {
    try {
      setSaving(true);
      const values = await form.validateFields();

      const payload = {
        ...values,
        instanceId: instanceId,
        sensitiveFields: config?.sensitiveFields || [
          'password', 'token', 'authorization', 'secret',
          'apiKey', 'api_key', 'accessKey', 'access_key',
          'clientSecret', 'client_secret', 'cookie'
        ]
      };

      const res = await api.post('/api/access-log/config', payload);
      if (res.data.code === 200) {
        message.success(t('access_log.save_success') || 'Configuration saved successfully');
        setConfig(payload);
      } else {
        message.error(res.data.message || t('access_log.save_failed'));
      }
    } catch (e: any) {
      message.error(e.message || t('access_log.save_failed'));
    } finally {
      setSaving(false);
    }
  };

  // Reset to default
  const handleReset = async () => {
    try {
      const params = instanceId ? `?instanceId=${instanceId}` : '';
      const res = await api.post(`/api/access-log/reset${params}`);
      if (res.data.code === 200) {
        message.success(t('access_log.reset_success') || 'Configuration reset to default');
        loadConfig();
      } else {
        message.error(res.data.message || t('access_log.reset_failed'));
      }
    } catch (e: any) {
      message.error(e.message || t('access_log.reset_failed'));
    }
  };

  // Get deploy mode icon
  const getDeployModeIcon = (mode: string) => {
    switch (mode) {
      case 'LOCAL': return <DesktopOutlined />;
      case 'DOCKER': return <ContainerOutlined />;
      case 'K8S': return <CloudServerOutlined />;
      case 'CUSTOM': return <FolderOutlined />;
      default: return <FolderOutlined />;
    }
  };

  // Get status code color
  const getStatusColor = (code: number) => {
    if (code < 200) return 'default';
    if (code < 300) return 'success';
    if (code < 400) return 'processing';
    if (code < 500) return 'warning';
    return 'error';
  };

  // Log table columns
  const logColumns = [
    {
      title: t('access_log.col_time'),
      dataIndex: '@timestamp',
      key: 'timestamp',
      width: 180,
      render: (ts: string) => (
        <Text style={{ fontSize: 12 }}>
          <ClockCircleOutlined style={{ marginRight: 4 }} />
          {dayjs(ts).format('HH:mm:ss.SSS')}
        </Text>
      ),
    },
    {
      title: t('access_log.method'),
      dataIndex: 'method',
      key: 'method',
      width: 80,
      render: (method: string) => (
        <Tag color={method === 'GET' ? 'green' : method === 'POST' ? 'blue' : method === 'DELETE' ? 'red' : 'default'}>
          {method}
        </Tag>
      ),
    },
    {
      title: t('access_log.path_label'),
      dataIndex: 'path',
      key: 'path',
      ellipsis: true,
      render: (path: string) => <Text code style={{ fontSize: 12 }}>{path}</Text>,
    },
    {
      title: t('access_log.status'),
      dataIndex: 'statusCode',
      key: 'statusCode',
      width: 80,
      render: (code: number) => <Badge status={getStatusColor(code) as any} text={code} />,
    },
    {
      title: t('access_log.col_duration'),
      dataIndex: 'durationMs',
      key: 'durationMs',
      width: 100,
      render: (ms: number) => (
        <Text style={{ fontSize: 12, color: ms > 1000 ? '#ff4d4f' : ms > 500 ? '#faad14' : '#52c41a' }}>
          {ms}ms
        </Text>
      ),
    },
    {
      title: t('access_log.col_client_ip'),
      dataIndex: 'clientIp',
      key: 'clientIp',
      width: 140,
      ellipsis: true,
    },
    {
      title: t('access_log.auth'),
      key: 'auth',
      width: 120,
      render: (_: any, record: LogEntry) => record.authType ? (
        <AntTooltip title={`${t('access_log.col_policy')}${record.authPolicy || '-'}`}>
          <Tag color="purple" style={{ fontSize: 11 }}>{record.authType}</Tag>
        </AntTooltip>
      ) : '-',
    },
    {
      title: t('access_log.col_trace_id'),
      dataIndex: 'traceId',
      key: 'traceId',
      width: 120,
      ellipsis: true,
      render: (id: string) => id ? <Text copyable style={{ fontSize: 11 }}>{id}</Text> : '-',
    },
  ];

  // Config Tab
  const ConfigTab = (
    <div style={{ padding: '16px 0' }}>
      <Form form={form} layout="vertical">
        <Row gutter={24}>
          {/* Basic Settings */}
          <Col span={12}>
            <Card
              title={<Space><SettingOutlined />{t('access_log.basic_settings') || 'Basic Settings'}</Space>}
              className="config-card"
            >
              <Form.Item
                name="enabled"
                label={t('access_log.enabled') || 'Enable Access Log'}
                valuePropName="checked"
              >
                <Switch
                  checkedChildren={t('common.enabled') || 'ON'}
                  unCheckedChildren={t('common.disabled') || 'OFF'}
                />
              </Form.Item>

              <Form.Item
                name="deployMode"
                label={
                  <Space>
                    {t('access_log.deploy_mode') || 'Deployment Mode'}
                    <Tooltip title={t('access_log.deploy_mode_tip') || 'Select deployment environment'}>
                      <InfoCircleOutlined style={{ color: '#8c8c8c' }} />
                    </Tooltip>
                  </Space>
                }
              >
                <Select onChange={handleDeployModeChange}>
                  {deployModes.map(mode => (
                    <Select.Option key={mode.mode} value={mode.mode}>
                      <Space>
                        {getDeployModeIcon(mode.mode)}
                        {mode.description}
                      </Space>
                    </Select.Option>
                  ))}
                </Select>
              </Form.Item>

              {/* 日志输出目标选项 - 使用 Segmented 控件 */}
              <Form.Item
                label={
                  <Space>
                    {t('access_log.output_target') || 'Log Output Target'}
                    <Tooltip title={t('access_log.output_target_tip') || 'Choose where to output access logs'}>
                      <InfoCircleOutlined style={{ color: '#8c8c8c' }} />
                    </Tooltip>
                  </Space>
                }
              >
                <div style={{ display: 'flex', flexDirection: 'column', gap: 8 }}>
                  <Segmented
                    value={outputTarget}
                    onChange={(value) => handleOutputTargetChange(value as 'stdout' | 'file' | 'both')}
                    block
                    options={[
                      {
                        value: 'stdout',
                        label: (
                          <Space>
                            <CodeOutlined />
                            <span>stdout</span>
                            {config?.deployMode === 'K8S' && (
                              <Badge status="success" text={<Text style={{ fontSize: 11, color: '#52c41a' }}>{t('access_log.recommended')}</Text>} />
                            )}
                          </Space>
                        ),
                      },
                      {
                        value: 'file',
                        label: (
                          <Space>
                            <FileOutlined />
                            <span>file</span>
                            {config?.deployMode === 'LOCAL' && (
                              <Badge status="success" text={<Text style={{ fontSize: 11, color: '#52c41a' }}>{t('access_log.recommended')}</Text>} />
                            )}
                          </Space>
                        ),
                        disabled: config?.deployMode === 'K8S',
                      },
                      {
                        value: 'both',
                        label: (
                          <Space>
                            <FileTextOutlined />
                            <span>both</span>
                            {config?.deployMode === 'DOCKER' && (
                              <Badge status="processing" text={<Text style={{ fontSize: 11, color: '#1890ff' }}>{t('access_log.recommended')}</Text>} />
                            )}
                          </Space>
                        ),
                        disabled: config?.deployMode === 'K8S',
                      },
                    ]}
                  />
                  {/* K8S 模式下提示为什么 file/both 被禁用 */}
                  {config?.deployMode === 'K8S' && (
                    <Alert
                      type="warning"
                      showIcon
                      style={{ marginTop: 8, padding: '8px 12px' }}
                      message={
                        <Text style={{ fontSize: 12 }}>
                          {t('access_log.file_both_disabled')}
                        </Text>
                      }
                    />
                  )}
                </div>
              </Form.Item>

              {/* 醒目的部署模式帮助提示 */}
              {config?.deployMode === 'K8S' && (
                <Alert
                  type="success"
                  showIcon
                  icon={<CheckCircleOutlined />}
                  style={{ marginBottom: 16, borderLeft: '4px solid #52c41a' }}
                  message={<Text strong>{t('access_log.k8s_stdout_hint')}</Text>}
                  description={
                    <div>
                      <p style={{ marginBottom: 8 }}>
                        {t('access_log.k8s_stdout_desc_line1')}<code style={{ background: '#f5f5f5', padding: '2px 6px', borderRadius: 4 }}>/var/log/containers/*.log</code>
                      </p>
                      <p style={{ color: '#52c41a', fontWeight: 500 }}>
                        {t('access_log.k8s_stdout_desc_line2')}
                      </p>
                    </div>
                  }
                />
              )}

              {config?.deployMode === 'DOCKER' && (
                <Alert
                  type="info"
                  showIcon
                  style={{ marginBottom: 16, borderLeft: '4px solid #1890ff' }}
                  message={<Text strong>{t('access_log.docker_hint')}</Text>}
                  description={
                    <div>
                      <p>{t('access_log.docker_desc_line1')}<code style={{ background: '#f5f5f5', padding: '2px 6px', borderRadius: 4 }}>both</code>{t('access_log.docker_desc_line2')}</p>
                      <p style={{ color: '#1890ff', fontWeight: 500 }}>
                        {t('access_log.docker_desc_line3')}
                      </p>
                    </div>
                  }
                />
              )}

              {config?.deployMode === 'LOCAL' && (
                <Alert
                  type="info"
                  showIcon
                  style={{ marginBottom: 16 }}
                  message={t('access_log.local_hint_msg')}
                  description={t('access_log.local_hint_desc')}
                />
              )}

              {config?.deployMode === 'CUSTOM' && (
                <Alert
                  type="warning"
                  showIcon
                  style={{ marginBottom: 16 }}
                  message={t('access_log.custom_hint_msg')}
                  description={t('access_log.custom_hint_desc')}
                />
              )}

              {/* 文件配置项 - 根据 outputTarget 显示/隐藏 */}
              {showFileConfig && (
                <>
                  <Form.Item
                    name="logDirectory"
                    label={t('access_log.log_directory') || 'Log Directory'}
                    extra={t('access_log.directory_hint') || 'Default path based on mode, you can customize'}
                  >
                    <Input
                      placeholder={t('access_log.directory_placeholder') || 'Enter log directory path'}
                      prefix={<FolderOutlined />}
                    />
                  </Form.Item>

                  <Form.Item
                    name="fileNamePattern"
                    label={t('access_log.file_pattern') || 'File Name Pattern'}
                    extra={t('access_log.pattern_extra') || 'Supports {yyyy-MM-dd} placeholder'}
                  >
                    <Input placeholder="access-{yyyy-MM-dd}.log" />
                  </Form.Item>
                </>
              )}

              <Form.Item
                name="logFormat"
                label={t('access_log.log_format') || 'Log Format'}
              >
                <Select>
                  <Select.Option value="JSON">
                    <Tag color="blue">JSON</Tag>
                    {t('access_log.json_desc') || 'Recommended for log aggregation'}
                  </Select.Option>
                  <Select.Option value="TEXT">
                    <Tag color="green">TEXT</Tag>
                    {t('access_log.text_desc') || 'Human-readable format'}
                  </Select.Option>
                </Select>
              </Form.Item>

              <Form.Item
                name="logLevel"
                label={t('access_log.log_level') || 'Log Level'}
              >
                <Select>
                  <Select.Option value="MINIMAL">
                    <Tag color="default">MINIMAL</Tag>
                    {t('access_log.level_minimal') || 'Request line, status, duration'}
                  </Select.Option>
                  <Select.Option value="NORMAL">
                    <Tag color="blue">NORMAL</Tag>
                    {t('access_log.level_normal') || '+ headers, auth info'}
                  </Select.Option>
                  <Select.Option value="VERBOSE">
                    <Tag color="purple">VERBOSE</Tag>
                    {t('access_log.level_verbose') || '+ request/response body'}
                  </Select.Option>
                </Select>
              </Form.Item>
            </Card>
          </Col>

          {/* Log Content Settings */}
          <Col span={12}>
            <Card
              title={<Space><FileTextOutlined />{t('access_log.content_settings') || 'Log Content Settings'}</Space>}
              className="config-card"
            >
              <Form.Item
                name="logRequestHeaders"
                label={t('access_log.log_request_headers') || 'Log Request Headers'}
                valuePropName="checked"
              >
                <Switch size="small" />
              </Form.Item>

              <Form.Item
                name="logResponseHeaders"
                label={t('access_log.log_response_headers') || 'Log Response Headers'}
                valuePropName="checked"
              >
                <Switch size="small" />
              </Form.Item>

              <Form.Item
                name="logRequestBody"
                label={t('access_log.log_request_body') || 'Log Request Body'}
                valuePropName="checked"
              >
                <Switch size="small" />
              </Form.Item>

              <Form.Item
                name="logResponseBody"
                label={t('access_log.log_response_body') || 'Log Response Body'}
                valuePropName="checked"
              >
                <Switch size="small" />
              </Form.Item>

              <Form.Item
                name="includeAuthInfo"
                label={t('access_log.include_auth_info') || 'Include Auth Info'}
                valuePropName="checked"
                extra={t('access_log.auth_info_extra') || 'Include authType, authPolicy, authUser'}
              >
                <Switch size="small" />
              </Form.Item>

              <Divider />

              <Form.Item
                name="maxBodyLength"
                label={t('access_log.max_body_length') || 'Max Body Length'}
                extra={t('access_log.max_body_extra') || 'Characters to log (truncated if exceeded)'}
              >
                <InputNumber min={0} max={10000} style={{ width: '100%' }} />
              </Form.Item>

              <Form.Item
                name="samplingRate"
                label={t('access_log.sampling_rate') || 'Sampling Rate (%)'}
                extra={t('access_log.sampling_extra')}
              >
                <InputNumber min={1} max={100} style={{ width: '100%' }} />
              </Form.Item>
            </Card>
          </Col>
        </Row>

        {/* File Settings - only show when file output is enabled */}
        {showFileConfig && (
          <Card
            title={<Space><FolderOutlined />{t('access_log.file_settings') || 'File Settings'}</Space>}
            className="config-card"
            style={{ marginTop: 16 }}
          >
            <Row gutter={24}>
              <Col span={8}>
                <Form.Item
                  name="maxFileSizeMb"
                  label={t('access_log.max_file_size') || 'Max File Size (MB)'}
                >
                  <InputNumber min={1} max={1000} style={{ width: '100%' }} />
                </Form.Item>
              </Col>
              <Col span={8}>
                <Form.Item
                  name="maxBackupFiles"
                  label={t('access_log.max_backup_files') || 'Max Backup Files'}
                >
                  <InputNumber min={0} max={100} style={{ width: '100%' }} />
                </Form.Item>
              </Col>
            </Row>
          </Card>
        )}

        {/* Log Collection Config Reference - Fluent Bit Templates */}
        <Card
          title={<Space><InfoCircleOutlined />{t('access_log.advanced_config')}</Space>}
          className="config-card"
          style={{ marginTop: 16 }}
        >
          <Alert
            type="info"
            showIcon
            style={{ marginBottom: 16 }}
            message={t('access_log.storage_hint_msg')}
            description={t('access_log.storage_hint_desc')}
          />
          <Tabs activeKey={watchedDeployMode || currentDeployMode || 'LOCAL'} items={[
            {
              key: 'K8S',
              label: <Space><CloudServerOutlined />{t('access_log.collection_k8s_title')}</Space>,
              children: (
                <div>
                  <Alert
                    type="success"
                    showIcon
                    icon={<CheckCircleOutlined />}
                    message={t('access_log.collection_k8s_msg')}
                    description={t('access_log.collection_k8s_desc')}
                    style={{ marginBottom: 12 }}
                  />
                  {/* 两个复制按钮 */}
                  <div style={{ marginBottom: 8 }}>
                    <Space>
                      <Button
                        size="small"
                        type="primary"
                        icon={<CopyOutlined />}
                        onClick={() => handleCopyConfig(fluentBitK8sConfig)}
                      >
                        {t('access_log.collection_copy_config')}
                      </Button>
                      <Button
                        size="small"
                        icon={<CopyOutlined />}
                        onClick={() => handleCopyConfig(fluentBitK8sConfigMap)}
                      >
                        {t('access_log.collection_copy_configmap')}
                      </Button>
                    </Space>
                  </div>
                  <div style={{ background: '#1e1e1e', padding: 12, borderRadius: 4 }}>
                    <pre style={{ color: '#d4d4d4', fontSize: 12, margin: 0, whiteSpace: 'pre-wrap' }}>{fluentBitK8sConfig}</pre>
                  </div>
                </div>
              ),
            },
            {
              key: 'DOCKER',
              label: <Space><ContainerOutlined />{t('access_log.collection_docker_title')}</Space>,
              children: (
                <div>
                  <Alert
                    type="info"
                    showIcon
                    message={t('access_log.collection_docker_msg')}
                    description={
                      <ul style={{ margin: 0, paddingLeft: 20 }}>
                        <li><strong>{t('access_log.collection_docker_sidecar')}</strong></li>
                        <li><strong>{t('access_log.collection_docker_host')}</strong></li>
                      </ul>
                    }
                    style={{ marginBottom: 12 }}
                  />
                  <div style={{ position: 'relative' }}>
                    <Button
                      size="small"
                      icon={<CopyOutlined />}
                      style={{ position: 'absolute', top: 8, right: 8 }}
                      onClick={() => handleCopyConfig(fluentBitDockerConfig)}
                    >
                      {t('access_log.collection_copy')}
                    </Button>
                    <div style={{ background: '#1e1e1e', padding: 12, borderRadius: 4, paddingRight: 100 }}>
                      <pre style={{ color: '#d4d4d4', fontSize: 12, margin: 0, whiteSpace: 'pre-wrap' }}>{fluentBitDockerConfig}</pre>
                    </div>
                  </div>
                </div>
              ),
            },
            {
              key: 'LOCAL',
              label: <Space><DesktopOutlined />{t('access_log.collection_local_title')}</Space>,
              children: (
                <div>
                  <Alert
                    type="info"
                    showIcon
                    message={t('access_log.collection_local_msg')}
                    description={t('access_log.collection_local_desc')}
                    style={{ marginBottom: 12 }}
                  />
                  <div style={{ position: 'relative' }}>
                    <Button
                      size="small"
                      icon={<CopyOutlined />}
                      style={{ position: 'absolute', top: 8, right: 8 }}
                      onClick={() => handleCopyConfig(fluentBitLocalConfig)}
                    >
                      {t('access_log.collection_copy')}
                    </Button>
                    <div style={{ background: '#1e1e1e', padding: 12, borderRadius: 4, paddingRight: 100 }}>
                      <pre style={{ color: '#d4d4d4', fontSize: 12, margin: 0, whiteSpace: 'pre-wrap' }}>{fluentBitLocalConfig}</pre>
                    </div>
                  </div>
                </div>
              ),
            },
            {
              key: 'CUSTOM',
              label: <Space><FolderOutlined />{t('access_log.collection_custom_title')}</Space>,
              children: (
                <div>
                  <Alert
                    type="warning"
                    showIcon
                    message={t('access_log.collection_custom_msg')}
                    description={t('access_log.collection_custom_desc')}
                    style={{ marginBottom: 12 }}
                  />
                  <div style={{ position: 'relative' }}>
                    <Button
                      size="small"
                      icon={<CopyOutlined />}
                      style={{ position: 'absolute', top: 8, right: 8 }}
                      onClick={() => handleCopyConfig(fluentBitCustomConfig)}
                    >
                      {t('access_log.collection_copy')}
                    </Button>
                    <div style={{ background: '#1e1e1e', padding: 12, borderRadius: 4, paddingRight: 100 }}>
                      <pre style={{ color: '#d4d4d4', fontSize: 12, margin: 0, whiteSpace: 'pre-wrap' }}>{fluentBitCustomConfig}</pre>
                    </div>
                  </div>
                </div>
              ),
            },
          ]} />
        </Card>
      </Form>
    </div>
  );

  // Log Viewer Tab
  const LogViewerTab = (
    <div style={{ padding: '16px 0' }}>
      {/* 实时/历史切换 */}
      <Card size="small" style={{ marginBottom: 16 }}>
        <Space>
          <Text strong>{t('access_log.view_mode')}:</Text>
          <Segmented
            value={logViewMode}
            onChange={(value) => setLogViewMode(value as 'realtime' | 'history')}
            options={[
              { value: 'realtime', label: <Space><ClockCircleOutlined />{t('access_log.realtime_mode')}</Space> },
              { value: 'history', label: <Space><HistoryOutlined />{t('access_log.history_mode')}</Space> },
            ]}
          />
          {logViewMode === 'history' && (
            <Tag color="blue">{t('access_log.history_mode_desc')}</Tag>
          )}
        </Space>
      </Card>

      {/* K8S Pod Selector - 实时和历史模式都需要 */}
      {currentDeployMode === 'K8S' && (
        <Alert
          type="info"
          showIcon
          style={{ marginBottom: 16, borderLeft: '4px solid #1890ff' }}
          message={<Space><CloudServerOutlined />{t('access_log.k8s_viewer_title')}</Space>}
          description={
            <div>
              <div style={{ marginBottom: 8 }}>
                <Text strong>{logViewMode === 'history' ? t('access_log.k8s_viewer_desc_history') : t('access_log.k8s_viewer_desc_realtime')}</Text>
              </div>
              <Space>
                <Select
                  placeholder={t('access_log.select_cluster')}
                  value={clusterId}
                  onChange={(val) => { setClusterId(Number(val)); setSelectedNamespace(''); setSelectedPod(null); }}
                  style={{ width: 180 }}
                  loading={k8sClustersLoading}
                  showSearch
                  filterOption={(input, option) =>
                    (option?.label ?? '').toLowerCase().includes(input.toLowerCase())
                  }
                  options={k8sClusters.filter(c => c.enabled).map(c => ({
                    value: c.id,
                    label: c.clusterName,
                  }))}
                />
                <Select
                  placeholder={t('access_log.select_namespace')}
                  value={selectedNamespace}
                  onChange={setSelectedNamespace}
                  style={{ width: 150 }}
                  loading={k8sNamespacesLoading}
                  showSearch
                  allowClear
                  filterOption={(input, option) =>
                    (option?.label ?? '').toLowerCase().includes(input.toLowerCase())
                  }
                  options={k8sNamespaces.map(ns => ({
                    value: ns,
                    label: ns,
                  }))}
                />
                <Select
                  placeholder={t('access_log.select_pod')}
                  value={selectedPod}
                  onChange={setSelectedPod}
                  style={{ width: 280 }}
                  loading={k8sPodsLoading}
                  showSearch
                  filterOption={(input, option) =>
                    (option?.label ?? '').toLowerCase().includes(input.toLowerCase())
                  }
                  options={k8sPods.map(p => ({
                    value: p.name,
                    label: `${p.name} (${p.phase})`,
                  }))}
                />
                <Button icon={<ReloadOutlined />} onClick={loadK8sPods} loading={k8sPodsLoading}>
                  {t('access_log.refresh_pod_list')}
                </Button>
              </Space>
            </div>
          }
        />
      )}

      {/* Local file notice - 仅实时模式显示 */}
      {logViewMode === 'realtime' && currentDeployMode !== 'K8S' && (
        <Alert
          type="info"
          showIcon
          style={{ marginBottom: 16 }}
          message={t('access_log.local_viewer_title')}
          description={t('access_log.local_viewer_desc')}
        />
      )}

      {/* Stats */}
      {logStats && (
        <Row gutter={16} style={{ marginBottom: 16 }}>
          <Col span={4}>
            <Card size="small">
              <Statistic title={t('access_log.total_requests')} value={logStats.totalRequests} />
            </Card>
          </Col>
          <Col span={4}>
            <Card size="small">
              <Statistic title={t('access_log.avg_duration')} value={logStats.avgDuration} suffix="ms" />
            </Card>
          </Col>
          <Col span={8}>
            <Card size="small" title={t('access_log.status_codes')}>
              <Space>
                {Object.entries(logStats.statusCodes).map(([code, count]) => (
                  <Tag key={code} color={getStatusColor(parseInt(code)) as any}>
                    {code}: {count}
                  </Tag>
                ))}
              </Space>
            </Card>
          </Col>
          <Col span={8}>
            <Card size="small" title={t('access_log.methods')}>
              <Space>
                {Object.entries(logStats.methods).map(([method, count]) => (
                  <Tag key={method} color={method === 'GET' ? 'green' : method === 'POST' ? 'blue' : 'default'}>
                    {method}: {count}
                  </Tag>
                ))}
              </Space>
            </Card>
          </Col>
        </Row>
      )}

      {/* Filters */}
      <Card size="small" style={{ marginBottom: 16 }}>
        <Space wrap>
          {/* 历史模式：预设时间范围选择 */}
          {logViewMode === 'history' && (
            <Select
              value={historyTimeRange}
              onChange={(value) => setHistoryTimeRange(value)}
              style={{ width: 120 }}
              getPopupContainer={(triggerNode) => document.body}
              popupMatchSelectWidth={false}
            >
              <Select.Option value="1h">{t('access_log.time_1h')}</Select.Option>
              <Select.Option value="6h">{t('access_log.time_6h')}</Select.Option>
              <Select.Option value="12h">{t('access_log.time_12h')}</Select.Option>
              <Select.Option value="24h">{t('access_log.time_24h')}</Select.Option>
              <Select.Option value="7d">{t('access_log.time_7d')}</Select.Option>
              <Select.Option value="30d">{t('access_log.time_30d')}</Select.Option>
            </Select>
          )}
          {/* 本地文件实时模式：时间范围选择 */}
          {logViewMode === 'realtime' && currentDeployMode !== 'K8S' && (
            <RangePicker
              showTime
              placeholder={[t('access_log.start_time'), t('access_log.end_time')]}
              format="YYYY-MM-DD HH:mm"
              onChange={(dates) => {
                if (dates && dates[0] && dates[1]) {
                  setFilters({ 
                    ...filters, 
                    startTime: dates[0].toISOString(),
                    endTime: dates[1].toISOString()
                  });
                } else {
                  const { startTime, endTime, ...restFilters } = filters;
                  setFilters(restFilters);
                }
              }}
              allowClear
              getPopupContainer={(triggerNode) => document.body}
              placement="bottomLeft"
              popupStyle={{ zIndex: 9999 }}
            />
          )}
          {/* K8S实时模式显示实时提示 */}
          {logViewMode === 'realtime' && currentDeployMode === 'K8S' && selectedPod && (
            <Tag color="processing" icon={<ClockCircleOutlined />}>
              {t('access_log.realtime_stdout')}
            </Tag>
          )}
          <Input
            placeholder={t('access_log.path_contains')}
            prefix={<SearchOutlined />}
            style={{ width: 200 }}
            onPressEnter={(e) => setFilters({ ...filters, path: (e.target as HTMLInputElement).value })}
            allowClear
          />
          <Select
            placeholder={t('access_log.method')}
            style={{ width: 100 }}
            allowClear
            onChange={(v) => setFilters({ ...filters, method: v || undefined })}
          >
            <Select.Option value="GET">GET</Select.Option>
            <Select.Option value="POST">POST</Select.Option>
            <Select.Option value="PUT">PUT</Select.Option>
            <Select.Option value="DELETE">DELETE</Select.Option>
          </Select>
          <Select
            placeholder={t('access_log.status')}
            style={{ width: 100 }}
            allowClear
            onChange={(v) => setFilters({ ...filters, statusCode: v || undefined })}
          >
            <Select.Option value={200}>2xx</Select.Option>
            <Select.Option value={400}>4xx</Select.Option>
            <Select.Option value={500}>5xx</Select.Option>
          </Select>
          <Input
            placeholder={t('access_log.trace_id')}
            prefix={<SearchOutlined />}
            style={{ width: 150 }}
            onPressEnter={(e) => setFilters({ ...filters, traceId: (e.target as HTMLInputElement).value })}
            allowClear
          />
          <Button icon={<ReloadOutlined />} onClick={() => { loadLogEntries(); loadLogStats(); }}>
            {t('access_log.refresh')}
          </Button>
        </Space>
      </Card>

      {/* Log Table */}
      <Table
        dataSource={logEntries}
        columns={logColumns}
        rowKey={(record) => record.requestId || record['@timestamp']}
        loading={logLoading}
        size="small"
        pagination={{
          current: logPage + 1,
          pageSize: logPageSize,
          total: logTotal,
          showSizeChanger: true,
          showTotal: (total) => t('access_log.total_entries', { count: total }),
          onChange: (page, size) => {
            setLogPage(page - 1);
            setLogPageSize(size);
          },
        }}
        scroll={{ x: 1200 }}
        expandable={{
          expandedRowRender: (record) => {
            const formatTime = (ts: string) => {
              if (!ts) return '-';
              return dayjs(ts).local().format('YYYY-MM-DD HH:mm:ss.SSS');
            };

            // Raw JSON display (one line, no formatting)
            const renderRawJson = (str: string | undefined) => {
              if (!str) return null;
              try {
                const parsed = JSON.parse(str);
                return JSON.stringify(parsed);  // Compact single line
              } catch {
                return str;
              }
            };

            // Formatted JSON with syntax highlighting - Postman style
            const renderFormattedJson = (str: string | undefined) => {
              if (!str) return null;
              try {
                const parsed = JSON.parse(str);
                const formatted = JSON.stringify(parsed, null, 2);
                return formatted
                  .replace(/&/g, '&amp;')
                  .replace(/</g, '&lt;')
                  .replace(/>/g, '&gt;')
                  .replace(/"([^"]+)":/g, '<span class="json-key">"$1"</span><span class="json-colon">:</span>')
                  .replace(/: "([^"]*)"/g, ': <span class="json-string">"$1"</span>')
                  .replace(/: (\d+\.?\d*)/g, ': <span class="json-number">$1</span>')
                  .replace(/: (true|false)/g, ': <span class="json-boolean">$1</span>')
                  .replace(/: (null)/g, ': <span class="json-null">$1</span>')
                  .replace(/([{}\[\]])/g, '<span class="json-bracket">$1</span>');
              } catch {
                return str;
              }
            };

            const reqSize = record.requestHeaders?.['content-length'] || record.requestHeaders?.['Content-Length'] || (record.requestBody ? `${record.requestBody.length}` : '-');
            const resSize = record.responseHeaders?.['content-length'] || record.responseHeaders?.['Content-Length'] || (record.responseBody ? `${record.responseBody.length}` : '-');
            const contentType = record.requestHeaders?.['content-type'] || record.requestHeaders?.['Content-Type'] || '-';

            return (
              <div style={{ padding: 16, background: 'var(--bg-secondary)', borderRadius: 4 }}>
                {/* 第一行：基本信息 */}
                <Descriptions 
                  size="small" 
                  column={4}
                  labelStyle={{ width: 80, color: 'var(--text-secondary)' }}
                  style={{ marginBottom: 8 }}
                >
                  <Descriptions.Item label={t('access_log.time')}>{formatTime(record['@timestamp'])}</Descriptions.Item>
                  <Descriptions.Item label={t('access_log.status_code')}>
                    <Badge status={getStatusColor(record.statusCode) as any} text={<Text strong>{record.statusCode}</Text>} />
                  </Descriptions.Item>
                  <Descriptions.Item label={t('access_log.latency')}>
                    <Text style={{ color: record.durationMs > 1000 ? '#ff4d4f' : record.durationMs > 500 ? '#faad14' : '#52c41a', fontWeight: 'bold' }}>
                      {record.durationMs} ms
                    </Text>
                  </Descriptions.Item>
                  <Descriptions.Item label={t('access_log.client_ip_label')}>{record.clientIp || '-'}</Descriptions.Item>
                </Descriptions>

                {/* 第二行：请求信息 */}
                <Descriptions 
                  size="small" 
                  column={3}
                  labelStyle={{ width: 80, color: 'var(--text-secondary)' }}
                  style={{ marginBottom: 8 }}
                >
                  <Descriptions.Item label={t('access_log.method_label')}>
                    <Tag color={record.method === 'GET' ? 'green' : record.method === 'POST' ? 'blue' : record.method === 'DELETE' ? 'red' : 'default'}>
                      {record.method}
                    </Tag>
                  </Descriptions.Item>
                  <Descriptions.Item label={t('access_log.path_label')}>
                    <Text code style={{ fontSize: 12, wordBreak: 'break-all' }}>{record.path}</Text>
                  </Descriptions.Item>
                  <Descriptions.Item label={t('access_log.query_params')}>
                    {record.query ? <Text code style={{ fontSize: 12, wordBreak: 'break-all' }}>{record.query}</Text> : '-'}
                  </Descriptions.Item>
                </Descriptions>

                {/* 第三行：ID信息 */}
                <Descriptions 
                  size="small" 
                  column={4}
                  labelStyle={{ width: 80, color: 'var(--text-secondary)' }}
                  style={{ marginBottom: 8 }}
                >
                  <Descriptions.Item label={t('access_log.request_id')}>
                    <Text copyable code style={{ fontSize: 11 }}>{record.requestId}</Text>
                  </Descriptions.Item>
                  <Descriptions.Item label={t('access_log.trace_id_label')}>
                    {record.traceId ? (
                      <Tooltip title={record.traceId}>
                        <Text copyable code style={{ fontSize: 11 }}>
                          {record.traceId.length > 20 ? `${record.traceId.substring(0, 10)}...${record.traceId.substring(record.traceId.length - 8)}` : record.traceId}
                        </Text>
                      </Tooltip>
                    ) : '-'}
                  </Descriptions.Item>
                  <Descriptions.Item label={t('access_log.request_size')}>{reqSize}</Descriptions.Item>
                  <Descriptions.Item label={t('access_log.response_size')}>{resSize}</Descriptions.Item>
                </Descriptions>

                {/* 第四行：认证和路由信息 */}
                {(record.authType || record.routeId || record.serviceId) && (
                  <Descriptions 
                    size="small" 
                    column={3}
                    labelStyle={{ width: 80, color: 'var(--text-secondary)' }}
                    style={{ marginBottom: 8 }}
                  >
                    {record.authType && <Descriptions.Item label={t('access_log.auth')}><Tag color="purple">{record.authType}</Tag></Descriptions.Item>}
                    {record.authPolicy && <Descriptions.Item label={t('access_log.policy')}><Text code style={{ fontSize: 11 }}>{record.authPolicy}</Text></Descriptions.Item>}
                    {record.authUser && <Descriptions.Item label={t('access_log.user')}>{record.authUser}</Descriptions.Item>}
                  </Descriptions>
                )}
                
                {/* 第五行：路由和服务信息（单独一行，更宽的显示） */}
                {(record.routeId || record.serviceId) && (
                  <Descriptions 
                    size="small" 
                    column={2}
                    labelStyle={{ width: 80, color: 'var(--text-secondary)' }}
                    style={{ marginBottom: 8 }}
                  >
                    {record.routeId && <Descriptions.Item label={t('access_log.route_id')}>
                      <Text code copyable style={{ fontSize: 11, wordBreak: 'break-all' }}>{record.routeId}</Text>
                    </Descriptions.Item>}
                    {record.serviceId && <Descriptions.Item label={t('access_log.service_id')}>
                      <Text code copyable style={{ fontSize: 11 }}>{record.serviceId}</Text>
                    </Descriptions.Item>}
                  </Descriptions>
                )}

                {/* 请求体 */}
                <div style={{ marginTop: 12 }}>
                  <div className="json-container">
                    <div className="json-header">
                      <span>📤 {t('access_log.request_body')}</span>
                      {record.requestBody && <span className="json-size">{record.requestBody.length} bytes</span>}
                      <Radio.Group 
                        size="small" 
                        value={bodyViewMode} 
                        onChange={(e) => setBodyViewMode(e.target.value)}
                      >
                        <Radio.Button value="formatted">{t('access_log.formatted')}</Radio.Button>
                        <Radio.Button value="raw">{t('access_log.raw')}</Radio.Button>
                      </Radio.Group>
                    </div>
                    <div className="json-content">
                      {record.requestBody ? (
                        bodyViewMode === 'formatted' ? (
                          <pre className="json-viewer" dangerouslySetInnerHTML={{ __html: renderFormattedJson(record.requestBody) || '' }} />
                        ) : (
                          <div style={{ padding: 16, color: 'var(--text-primary)', fontSize: 13, fontFamily: 'Consolas, Monaco, monospace', whiteSpace: 'pre-wrap', wordBreak: 'break-word' }}>
                            {renderRawJson(record.requestBody)}
                          </div>
                        )
                      ) : <div style={{ padding: 24, textAlign: 'center', color: 'var(--text-secondary)' }}>{t('access_log.not_recorded')}</div>}
                    </div>
                  </div>
                </div>

                {/* 响应体 */}
                <div style={{ marginTop: 12 }}>
                  <div className="json-container">
                    <div className="json-header" style={{ color: 'var(--success-color, #52c41a)' }}>
                      <span>📥 {t('access_log.response_body')}</span>
                      {record.responseBody && <span className="json-size">{record.responseBody.length} bytes</span>}
                    </div>
                    <div className="json-content">
                      {record.responseBody ? (
                        bodyViewMode === 'formatted' ? (
                          <pre className="json-viewer" dangerouslySetInnerHTML={{ __html: renderFormattedJson(record.responseBody) || '' }} />
                        ) : (
                          <div style={{ padding: 16, color: 'var(--text-primary)', fontSize: 13, fontFamily: 'Consolas, Monaco, monospace', whiteSpace: 'pre-wrap', wordBreak: 'break-word' }}>
                            {renderRawJson(record.responseBody)}
                          </div>
                        )
                      ) : <div style={{ padding: 24, textAlign: 'center', color: 'var(--text-secondary)' }}>{t('access_log.not_recorded')}</div>}
                    </div>
                  </div>
                </div>

                {/* 错误信息 */}
                {record.errorMessage && (
                  <Alert 
                    message={t('access_log.error_info')} 
                    description={record.errorMessage} 
                    type="error" 
                    showIcon 
                    style={{ marginTop: 12 }} 
                  />
                )}
              </div>
            );
          },
        }}
      />
    </div>
  );

  const tabItems: TabsProps['items'] = [
    {
      key: 'config',
      label: (
        <Space>
          <SettingOutlined />
          {t('access_log.config_tab') || 'Configuration'}
        </Space>
      ),
      children: ConfigTab,
    },
    {
      key: 'logs',
      label: (
        <Space>
          <EyeOutlined />
          {t('access_log.viewer_tab') || 'View Logs'}
          {logTotal > 0 && <Badge count={logTotal} style={{ backgroundColor: '#52c41a' }} />}
        </Space>
      ),
      children: LogViewerTab,
    },
  ];

  if (loading) {
    return <div style={{ textAlign: 'center', padding: 100 }}><Spin size="large" /></div>;
  }

  return (
    <div className="access-log-config-page">
      <div className="page-header">
        <div className="header-title">
          <FileTextOutlined className="header-icon" />
          <Title level={3} style={{ margin: 0 }}>{t('access_log.title') || 'Access Log'}</Title>
        </div>
        <Space className="header-actions">
          <Button icon={<ReloadOutlined />} onClick={loadConfig}>
            {t('common.refresh') || 'Refresh'}
          </Button>
          <Button onClick={handleReset}>
            {t('access_log.reset') || 'Reset Default'}
          </Button>
          <Button type="primary" icon={<SaveOutlined />} onClick={handleSave} loading={saving}>
            {t('common.save') || 'Save'}
          </Button>
        </Space>
      </div>

      <Tabs defaultActiveKey="config" items={tabItems} />

      <style>{`
        .access-log-config-page { padding: 0 }
        .page-header {
          display: flex;
          justify-content: space-between;
          align-items: center;
          margin-bottom: 8px;
          padding: 16px 0;
        }
        .header-title {
          display: flex;
          align-items: center;
          gap: 12px;
        }
        .header-icon {
          font-size: 24px;
          color: #165DFF;
        }
        .config-card {
          height: 100%;
        }
        .config-card .ant-card-head-title {
          color: #e2e8f0;
        }
        .ant-tabs-tab {
          padding: 8px 16px !important;
        }
      `}</style>
    </div>
  );
};

export default AccessLogConfigPage;