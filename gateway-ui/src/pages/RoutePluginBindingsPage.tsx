import React, { useState, useEffect, useMemo, useCallback } from 'react';
import {
  Card,
  Row,
  Col,
  Button,
  Tag,
  Space,
  Modal,
  Form,
  Select,
  InputNumber,
  Input,
  Switch,
  message,
  Popconfirm,
  Tooltip,
  Alert,
  Divider,
  Badge,
  Typography,
  Radio,
  Spin,
  Empty,
  Table,
} from 'antd';
import {
  ApiOutlined,
  PlusOutlined,
  DeleteOutlined,
  EditOutlined,
  StopOutlined,
  PlayCircleOutlined,
  OrderedListOutlined,
  SettingOutlined,
  ThunderboltOutlined,
  GlobalOutlined,
  CopyOutlined,
  MinusOutlined,
  ScissorOutlined,
  FolderAddOutlined,
  SyncOutlined,
  FileTextOutlined,
  CheckCircleOutlined,
  LogoutOutlined,
  SafetyCertificateOutlined,
  FileExclamationOutlined,
  AppstoreAddOutlined,
  CodeOutlined,
} from '@ant-design/icons';
import api from '../utils/api';
import { useTranslation } from 'react-i18next';

const { Text, Title } = Typography;

interface Plugin {
  pluginId: string;
  pluginName: string;
  category: string;
  icon?: string;
  color: string;
  configSchema: string;
  description?: string;
  descriptionEn?: string;
  enabled: boolean;
  jarPath?: string;
}

interface Route {
  id: string;
  routeName: string;
  uri: string;
  enabled: boolean;
}

interface Binding {
  id: number;
  bindingId: string;
  routeId: string;
  routeName?: string;
  pluginId: string;
  pluginName: string;
  config: string;
  order: number;
  enabled: boolean;
  instanceId: string;
  createdAt: string;
}

interface ConfigField {
  name: string;
  type: string;
  label: string;
  default?: unknown;
  options?: { label: string; value: string }[];
  min?: number;
  max?: number;
  description?: string;
}

interface ConfigSchema {
  fields: ConfigField[];
  hasSubSchemas?: boolean;
}

interface RoutePluginBindingsPageProps {
  instanceId?: string;
}

const RoutePluginBindingsPage: React.FC<RoutePluginBindingsPageProps> = ({ instanceId }) => {
  const { t, i18n } = useTranslation();
  const [routes, setRoutes] = useState<Route[]>([]);
  const [plugins, setPlugins] = useState<Plugin[]>([]);
  const [bindings, setBindings] = useState<Binding[]>([]);
  const [loading, setLoading] = useState(false);
  const [bindingsLoading, setBindingsLoading] = useState(false);
  const [bindModalVisible, setBindModalVisible] = useState(false);
  const [editModalVisible, setEditModalVisible] = useState(false);
  const [pluginSelectModalVisible, setPluginSelectModalVisible] = useState(false);
  const [selectedPlugin, setSelectedPlugin] = useState<Plugin | null>(null);
  const [selectedBinding, setSelectedBinding] = useState<Binding | null>(null);
  const [configSchema, setConfigSchema] = useState<ConfigSchema | null>(null);
  const [configValues, setConfigValues] = useState<Record<string, any>>({});
  const [bindType, setBindType] = useState<'GLOBAL' | 'ROUTE'>('GLOBAL');
  const [selectedRouteId, setSelectedRouteId] = useState<string | null>(null);
  const [form] = Form.useForm();

  // Get description based on language
  const getDescription = (plugin: Plugin) => {
    const isZh = i18n.language === 'zh' || i18n.language?.startsWith('zh');
    return isZh ? plugin.description : (plugin.descriptionEn || plugin.description);
  };

  // Get plugin icon based on icon name and plugin type
  const getPluginIcon = (plugin: Plugin) => {
    // Custom plugin (uploaded JAR) - use unified custom icon
    if (plugin.jarPath !== 'BUILTIN') {
      return <AppstoreAddOutlined />;
    }

    // Built-in plugins - use specific icon
    const iconMap: Record<string, React.ReactNode> = {
      'PlusOutlined': <PlusOutlined />,
      'MinusOutlined': <MinusOutlined />,
      'EditOutlined': <EditOutlined />,
      'ScissorOutlined': <ScissorOutlined />,
      'FolderAddOutlined': <FolderAddOutlined />,
      'SyncOutlined': <SyncOutlined />,
      'FileTextOutlined': <FileTextOutlined />,
      'CheckCircleOutlined': <CheckCircleOutlined />,
      'LogoutOutlined': <LogoutOutlined />,
      'SafetyCertificateOutlined': <SafetyCertificateOutlined />,
      'FileExclamationOutlined': <FileExclamationOutlined />,
      'ThunderboltOutlined': <ThunderboltOutlined />,
      'CodeOutlined': <CodeOutlined />,
    };

    return iconMap[plugin.icon || 'ThunderboltOutlined'] || <ThunderboltOutlined />;
  };

  // Load routes
  const loadRoutes = async () => {
    try {
      const response = await api.get('/api/routes', { params: { instanceId } });
      setRoutes(response.data?.data || []);
    } catch (error) {
      console.error('Failed to load routes:', error);
    }
  };

  // Load plugins
  const loadPlugins = async () => {
    setLoading(true);
    try {
      const response = await api.get('/api/plugins', { params: { instanceId } });
      const enabledPlugins = (response.data || []).filter((p: Plugin) => p.enabled);
      setPlugins(enabledPlugins);
    } catch (error) {
      console.error('Failed to load plugins:', error);
      message.error(t('plugin_bindings.load_plugins_failed'));
    } finally {
      setLoading(false);
    }
  };

  // Load all bindings
  const loadAllBindings = async () => {
    setBindingsLoading(true);
    try {
      // Load global bindings
      const globalResponse = await api.get('/api/plugin-bindings/global');
      const globalBindings = (globalResponse.data?.data || []).map((b: Binding) => ({
        ...b,
        routeId: 'GLOBAL',
        routeName: t('plugin_bindings.global'),
      }));

      // Load route bindings for each route
      const routeBindingsPromises = routes.map(route =>
        api.get(`/api/routes/${route.id}/plugins`, { params: { instanceId } })
          .then(res => (res.data?.data || []).map((b: Binding) => ({
            ...b,
            routeId: route.id,
            routeName: route.routeName || route.id,
          })))
          .catch(() => [])
      );

      const routeBindingsResults = await Promise.all(routeBindingsPromises);
      const allRouteBindings = routeBindingsResults.flat();

      setBindings([...globalBindings, ...allRouteBindings]);
    } catch (error) {
      console.error('Failed to load bindings:', error);
    } finally {
      setBindingsLoading(false);
    }
  };

  useEffect(() => {
    loadRoutes();
    loadPlugins();
  }, [instanceId]);

  useEffect(() => {
    if (routes.length > 0) {
      loadAllBindings();
    }
  }, [routes]);

  // Calculate binding stats
  const bindingStats = useMemo(() => ({
    total: bindings.length,
    global: bindings.filter(b => b.routeId === 'GLOBAL').length,
    route: bindings.filter(b => b.routeId !== 'GLOBAL').length,
    enabled: bindings.filter(b => b.enabled).length,
  }), [bindings]);

  // Group plugins by category
  const pluginsByCategory = useMemo(() => {
    const grouped: Record<string, Plugin[]> = {};
    plugins.forEach(p => {
      if (!grouped[p.category]) {
        grouped[p.category] = [];
      }
      grouped[p.category].push(p);
    });
    return grouped;
  }, [plugins]);

  // Category labels
  const categoryLabels: Record<string, string> = {
    '流量控制': 'Traffic Control',
    '安全防护': 'Security',
    '弹性容错': 'Resilience',
    '数据转换': 'Transform',
    '可观测性': 'Observability',
    '其他': 'Misc',
    'Request Headers': 'Headers',
    'Request Parameters': 'Params',
    'Response Headers': 'Response',
    'Path': 'Path',
    'Path Modification': 'Path Mod',
    'Status Code': 'Status',
    'Security': 'Security',
    'Request Size': 'Size',
    'Other': 'Other',
  };

  // Category design configuration - gradient colors and unique icons
  // Supports both Chinese and English category names from backend
  const categoryDesign: Record<string, { gradient: string; glowColor: string; primaryIcon: React.ReactNode; accentColor: string }> = {
    // 中文分类名
    '流量控制': {
      gradient: 'linear-gradient(135deg, #3b82f6 0%, #1d4ed8 100%)',
      glowColor: 'rgba(59, 130, 246, 0.4)',
      primaryIcon: <ThunderboltOutlined />,
      accentColor: '#60a5fa',
    },
    '安全防护': {
      gradient: 'linear-gradient(135deg, #ef4444 0%, #dc2626 100%)',
      glowColor: 'rgba(239, 68, 68, 0.4)',
      primaryIcon: <SafetyCertificateOutlined />,
      accentColor: '#f87171',
    },
    '弹性容错': {
      gradient: 'linear-gradient(135deg, #f59e0b 0%, #d97706 100%)',
      glowColor: 'rgba(245, 158, 11, 0.4)',
      primaryIcon: <CheckCircleOutlined />,
      accentColor: '#fbbf24',
    },
    '数据转换': {
      gradient: 'linear-gradient(135deg, #8b5cf6 0%, #7c3aed 100%)',
      glowColor: 'rgba(139, 92, 246, 0.4)',
      primaryIcon: <SyncOutlined />,
      accentColor: '#a78bfa',
    },
    '可观测性': {
      gradient: 'linear-gradient(135deg, #10b981 0%, #059669 100%)',
      glowColor: 'rgba(16, 185, 129, 0.4)',
      primaryIcon: <FileTextOutlined />,
      accentColor: '#34d399',
    },
    // 英文分类名 (来自后端数据库)
    'traffic': {
      gradient: 'linear-gradient(135deg, #3b82f6 0%, #1d4ed8 100%)',
      glowColor: 'rgba(59, 130, 246, 0.4)',
      primaryIcon: <ThunderboltOutlined />,
      accentColor: '#60a5fa',
    },
    'security': {
      gradient: 'linear-gradient(135deg, #ef4444 0%, #dc2626 100%)',
      glowColor: 'rgba(239, 68, 68, 0.4)',
      primaryIcon: <SafetyCertificateOutlined />,
      accentColor: '#f87171',
    },
    'resilience': {
      gradient: 'linear-gradient(135deg, #f59e0b 0%, #d97706 100%)',
      glowColor: 'rgba(245, 158, 11, 0.4)',
      primaryIcon: <CheckCircleOutlined />,
      accentColor: '#fbbf24',
    },
    'transform': {
      gradient: 'linear-gradient(135deg, #8b5cf6 0%, #7c3aed 100%)',
      glowColor: 'rgba(139, 92, 246, 0.4)',
      primaryIcon: <SyncOutlined />,
      accentColor: '#a78bfa',
    },
    'observability': {
      gradient: 'linear-gradient(135deg, #10b981 0%, #059669 100%)',
      glowColor: 'rgba(16, 185, 129, 0.4)',
      primaryIcon: <FileTextOutlined />,
      accentColor: '#34d399',
    },
    'custom': {
      gradient: 'linear-gradient(135deg, #a855f7 0%, #9333ea 100%)',
      glowColor: 'rgba(168, 85, 247, 0.4)',
      primaryIcon: <AppstoreAddOutlined />,
      accentColor: '#c084fc',
    },
    // 其他英文分类
    'Request Headers': {
      gradient: 'linear-gradient(135deg, #6366f1 0%, #4f46e5 100%)',
      glowColor: 'rgba(99, 102, 241, 0.4)',
      primaryIcon: <EditOutlined />,
      accentColor: '#818cf8',
    },
    'Request Parameters': {
      gradient: 'linear-gradient(135deg, #14b8a6 0%, #0d9488 100%)',
      glowColor: 'rgba(20, 184, 166, 0.4)',
      primaryIcon: <SettingOutlined />,
      accentColor: '#2dd4bf',
    },
    'Response Headers': {
      gradient: 'linear-gradient(135deg, #f472b6 0%, #ec4899 100%)',
      glowColor: 'rgba(244, 114, 182, 0.4)',
      primaryIcon: <LogoutOutlined />,
      accentColor: '#f9a8d4',
    },
    'Path': {
      gradient: 'linear-gradient(135deg, #0ea5e9 0%, #0284c7 100%)',
      glowColor: 'rgba(14, 165, 233, 0.4)',
      primaryIcon: <FolderAddOutlined />,
      accentColor: '#38bdf8',
    },
    'Path Modification': {
      gradient: 'linear-gradient(135deg, #a855f7 0%, #9333ea 100%)',
      glowColor: 'rgba(168, 85, 247, 0.4)',
      primaryIcon: <ScissorOutlined />,
      accentColor: '#c084fc',
    },
    'Status Code': {
      gradient: 'linear-gradient(135deg, #fb923c 0%, #f97316 100%)',
      glowColor: 'rgba(251, 146, 60, 0.4)',
      primaryIcon: <FileExclamationOutlined />,
      accentColor: '#fdba74',
    },
    'Security': {
      gradient: 'linear-gradient(135deg, #ef4444 0%, #dc2626 100%)',
      glowColor: 'rgba(239, 68, 68, 0.4)',
      primaryIcon: <SafetyCertificateOutlined />,
      accentColor: '#f87171',
    },
    'Request Size': {
      gradient: 'linear-gradient(135deg, #84cc16 0%, #65a30d 100%)',
      glowColor: 'rgba(132, 204, 22, 0.4)',
      primaryIcon: <MinusOutlined />,
      accentColor: '#a3e635',
    },
    '其他': {
      gradient: 'linear-gradient(135deg, #64748b 0%, #475569 100%)',
      glowColor: 'rgba(100, 116, 139, 0.4)',
      primaryIcon: <ThunderboltOutlined />,
      accentColor: '#94a3b8',
    },
    'Other': {
      gradient: 'linear-gradient(135deg, #64748b 0%, #475569 100%)',
      glowColor: 'rgba(100, 116, 139, 0.4)',
      primaryIcon: <ThunderboltOutlined />,
      accentColor: '#94a3b8',
    },
  };

  // Get category design config - normalize category name for matching
  const getCategoryDesign = (category: string) => {
    // Normalize: try exact match first, then lowercase
    const normalizedCategory = category?.toLowerCase() || 'custom';
    const design = categoryDesign[category] || categoryDesign[normalizedCategory];
    if (design) return design;

    // Default fallback for unknown categories - use purple gradient
    return {
      gradient: 'linear-gradient(135deg, #8b5cf6 0%, #7c3aed 100%)',
      glowColor: 'rgba(139, 92, 246, 0.4)',
      primaryIcon: <ThunderboltOutlined />,
      accentColor: '#a78bfa',
    };
  };

  // Load config schema when selecting a plugin
  const handlePluginSelect = async (plugin: Plugin) => {
    setSelectedPlugin(plugin);
    try {
      const response = await api.get(`/api/plugins/${plugin.pluginId}/schema`);
      const schema = JSON.parse(response.data || '{"fields": []}');
      setConfigSchema(schema);

      // Initialize config values with defaults
      const defaults: Record<string, any> = {};
      if (schema.fields) {
        schema.fields.forEach((field: ConfigField) => {
          defaults[field.name] = field.default;
        });
      }
      setConfigValues(defaults);
    } catch (error) {
      console.error('Failed to load schema:', error);
      setConfigSchema({ fields: [] });
    }
    setBindModalVisible(true);
  };

  // Bind plugin
  const handleBind = async () => {
    try {
      const values = await form.validateFields();
      const url = bindType === 'GLOBAL'
        ? '/api/plugin-bindings/global'
        : `/api/routes/${selectedRouteId}/plugins`;

      await api.post(url, {
        pluginId: selectedPlugin?.pluginId,
        config: configValues,
        order: values.order || 0,
      });

      message.success(bindType === 'GLOBAL'
        ? t('plugin_bindings.global_bind_success')
        : t('plugin_bindings.bind_success')
      );

      setBindModalVisible(false);
      form.resetFields();
      setSelectedPlugin(null);
      setConfigSchema(null);
      setConfigValues({});
      setSelectedRouteId(null);
      loadAllBindings();
    } catch (error) {
      message.error(t('plugin_bindings.bind_failed'));
    }
  };

  // Open edit modal
  const openEditModal = async (binding: Binding) => {
    setSelectedBinding(binding);
    form.setFieldsValue({ order: binding.order });

    // Find plugin and load schema
    const plugin = plugins.find(p => p.pluginId === binding.pluginId);
    if (plugin) {
      setSelectedPlugin(plugin);
      try {
        const response = await api.get(`/api/plugins/${plugin.pluginId}/schema`);
        const schema = JSON.parse(response.data || '{"fields": []}');
        setConfigSchema(schema);
      } catch (error) {
        setConfigSchema({ fields: [] });
      }
    }

    // Set current config values
    if (binding.config) {
      try {
        const currentConfig = JSON.parse(binding.config);
        setConfigValues(currentConfig);
      } catch (e) {
        console.error('Failed to parse config:', e);
      }
    }

    setEditModalVisible(true);
  };

  // Edit binding
  const handleEditBinding = async () => {
    if (!selectedBinding) return;

    try {
      const values = await form.validateFields();
      const isGlobal = selectedBinding.routeId === 'GLOBAL';
      const url = isGlobal
        ? `/api/plugin-bindings/global/${selectedBinding.bindingId}`
        : `/api/routes/${selectedBinding.routeId}/plugins/${selectedBinding.bindingId}`;

      await api.put(url, {
        config: configValues,
        order: values.order,
      });

      message.success(t('plugin_bindings.update_success'));
      setEditModalVisible(false);
      form.resetFields();
      setSelectedBinding(null);
      setSelectedPlugin(null);
      setConfigSchema(null);
      setConfigValues({});
      loadAllBindings();
    } catch (error) {
      message.error(t('plugin_bindings.update_failed'));
    }
  };

  // Enable/disable binding
  const handleToggleBinding = async (binding: Binding, enable: boolean) => {
    const isGlobal = binding.routeId === 'GLOBAL';
    try {
      const url = isGlobal
        ? `/api/plugin-bindings/global/${binding.bindingId}/${enable ? 'enable' : 'disable'}`
        : `/api/routes/${binding.routeId}/plugins/${binding.bindingId}/${enable ? 'enable' : 'disable'}`;
      await api.post(url);
      message.success(enable ? t('plugin_bindings.toggle_enable') : t('plugin_bindings.toggle_disable'));
      loadAllBindings();
    } catch (error) {
      message.error(t('plugin_bindings.toggle_failed'));
    }
  };

  // Delete binding
  const handleDeleteBinding = async (binding: Binding) => {
    const isGlobal = binding.routeId === 'GLOBAL';
    try {
      const url = isGlobal
        ? `/api/plugin-bindings/global/${binding.bindingId}`
        : `/api/routes/${binding.routeId}/plugins/${binding.bindingId}`;
      await api.delete(url);
      message.success(t('plugin_bindings.unbind_success'));
      loadAllBindings();
    } catch (error) {
      message.error(t('plugin_bindings.unbind_failed'));
    }
  };

  // Render config form based on schema
  const renderConfigForm = () => {
    if (!configSchema || !configSchema.fields || configSchema.fields.length === 0) {
      return (
        <Alert
          message={t('plugin_bindings.no_config_info')}
          description={t('plugin_bindings.no_config_desc')}
          type="info"
        />
      );
    }

    return (
      <div>
        <Divider plain>
          <SettingOutlined /> {t('plugin_bindings.plugin_config')}
        </Divider>
        {configSchema.fields.map((field: ConfigField) => (
          <Form.Item
            key={field.name}
            label={field.label}
            help={field.description}
          >
            {renderFieldInput(field)}
          </Form.Item>
        ))}
      </div>
    );
  };

  // Render field input based on type
  const renderFieldInput = (field: ConfigField) => {
    const value = configValues[field.name];

    switch (field.type) {
      case 'number':
        return (
          <InputNumber
            value={value}
            onChange={(v) => setConfigValues({ ...configValues, [field.name]: v })}
            min={field.min}
            max={field.max}
            style={{ width: '100%' }}
          />
        );
      case 'switch':
        return (
          <Switch
            checked={value}
            onChange={(v) => setConfigValues({ ...configValues, [field.name]: v })}
          />
        );
      case 'select':
        return (
          <Select
            value={value}
            onChange={(v) => setConfigValues({ ...configValues, [field.name]: v })}
            options={field.options}
            style={{ width: '100%' }}
          />
        );
      case 'textarea':
        return (
          <Input.TextArea
            value={typeof value === 'object' ? JSON.stringify(value, null, 2) : value}
            onChange={(e) => {
              try {
                const parsed = JSON.parse(e.target.value);
                setConfigValues({ ...configValues, [field.name]: parsed });
              } catch {
                setConfigValues({ ...configValues, [field.name]: e.target.value });
              }
            }}
            rows={4}
            placeholder="Enter JSON configuration"
          />
        );
      default:
        return (
          <Input
            value={value}
            onChange={(e) => setConfigValues({ ...configValues, [field.name]: e.target.value })}
          />
        );
    }
  };

  // Binding table columns
  const columns = [
    {
      title: t('plugin_bindings.plugin'),
      dataIndex: 'pluginName',
      key: 'pluginName',
      render: (name: string) => (
        <Tag color="purple" icon={<ThunderboltOutlined />}>
          {name}
        </Tag>
      ),
    },
    {
      title: t('plugin_bindings.binding_scope'),
      dataIndex: 'routeName',
      key: 'routeName',
      render: (name: string, record: Binding) => (
        record.routeId === 'GLOBAL'
          ? <Tag color="gold" icon={<GlobalOutlined />}>{t('plugin_bindings.global')}</Tag>
          : <Tag color="green" icon={<ApiOutlined />}>{name}</Tag>
      ),
    },
    {
      title: t('plugin_bindings.order'),
      dataIndex: 'order',
      key: 'order',
      width: 80,
      render: (order: number) => (
        <Tag icon={<OrderedListOutlined />}>{order}</Tag>
      ),
      sorter: (a: Binding, b: Binding) => a.order - b.order,
    },
    {
      title: t('plugin_bindings.status'),
      dataIndex: 'enabled',
      key: 'enabled',
      width: 100,
      render: (enabled: boolean) => (
        <Badge status={enabled ? 'success' : 'default'} text={enabled ? t('plugin_bindings.active') : t('plugin_bindings.disabled_binding')} />
      ),
    },
    {
      title: t('plugin_bindings.actions'),
      key: 'actions',
      width: 150,
      render: (record: Binding) => (
        <Space size="small">
          <Tooltip title={t('plugin_bindings.edit_config')}>
            <Button
              type="text"
              size="small"
              icon={<EditOutlined />}
              onClick={() => openEditModal(record)}
            />
          </Tooltip>
          {record.enabled ? (
            <Tooltip title={t('plugin_bindings.disable_binding')}>
              <Button
                type="text"
                size="small"
                icon={<StopOutlined />}
                onClick={() => handleToggleBinding(record, false)}
              />
            </Tooltip>
          ) : (
            <Tooltip title={t('plugin_bindings.enable_binding')}>
              <Button
                type="text"
                size="small"
                icon={<PlayCircleOutlined />}
                onClick={() => handleToggleBinding(record, true)}
              />
            </Tooltip>
          )}
          <Popconfirm
            title={t('plugin_bindings.unbind_confirm')}
            onConfirm={() => handleDeleteBinding(record)}
            okText={t('common.yes')}
            cancelText={t('common.no')}
          >
            <Tooltip title={t('plugin_bindings.unbind')}>
              <Button type="text" size="small" danger icon={<DeleteOutlined />} />
            </Tooltip>
          </Popconfirm>
        </Space>
      ),
    },
  ];

  return (
    <div style={{ padding: '24px' }}>
      {/* Stats Bar */}
      <div className="stats-bar" style={{
        display: 'flex',
        alignItems: 'center',
        marginBottom: 24,
        padding: '16px 24px',
        background: 'var(--bg-card)',
        borderRadius: 12,
        border: '1px solid var(--border-subtle)',
      }}>
        <div style={{ textAlign: 'center', paddingRight: 24 }}>
          <div style={{ fontSize: 24, fontWeight: 600, color: 'var(--text-primary)' }}>{bindingStats.total}</div>
          <div style={{ fontSize: 12, color: 'var(--text-muted)' }}>{t('plugin_bindings.stats_total')}</div>
        </div>
        <Divider type="vertical" style={{ height: 40, borderColor: 'var(--border-default)' }} />
        <div style={{ textAlign: 'center', padding: '0 24px' }}>
          <div style={{ fontSize: 24, fontWeight: 600, color: '#faad14' }}>{bindingStats.global}</div>
          <div style={{ fontSize: 12, color: 'var(--text-muted)' }}>{t('plugin_bindings.stats_global')}</div>
        </div>
        <Divider type="vertical" style={{ height: 40, borderColor: 'var(--border-default)' }} />
        <div style={{ textAlign: 'center', padding: '0 24px' }}>
          <div style={{ fontSize: 24, fontWeight: 600, color: '#52c41a' }}>{bindingStats.route}</div>
          <div style={{ fontSize: 12, color: 'var(--text-muted)' }}>{t('plugin_bindings.stats_route')}</div>
        </div>
        <Divider type="vertical" style={{ height: 40, borderColor: 'var(--border-default)' }} />
        <div style={{ textAlign: 'center', paddingLeft: 24 }}>
          <div style={{ fontSize: 24, fontWeight: 600, color: '#3b82f6' }}>{bindingStats.enabled}</div>
          <div style={{ fontSize: 12, color: 'var(--text-muted)' }}>{t('plugin_bindings.stats_enabled')}</div>
        </div>
      </div>

      {/* Page Header with Create Button */}
      <div style={{
        display: 'flex',
        justifyContent: 'space-between',
        alignItems: 'center',
        marginBottom: 24
      }}>
        <div>
          <Title level={3} style={{ margin: 0, color: 'var(--text-primary)' }}>
            <ThunderboltOutlined style={{ marginRight: 8, color: '#8b5cf6' }} />
            {t('plugin_bindings.plugins_title')}
          </Title>
          <Text type="secondary" style={{ color: 'var(--text-muted)' }}>{t('plugin_bindings.plugins_desc')}</Text>
        </div>
        <Space>
          <Button
            type="primary"
            icon={<PlusOutlined />}
            onClick={() => setPluginSelectModalVisible(true)}
            style={{
              background: 'linear-gradient(135deg, #8b5cf6 0%, #7c3aed 100%)',
              border: 'none',
              borderRadius: 8,
            }}
          >
            {t('plugin_bindings.create_binding')}
          </Button>
          <Button
            icon={<SyncOutlined />}
            onClick={loadAllBindings}
            loading={bindingsLoading}
          >
            {t('common.refresh')}
          </Button>
        </Space>
      </div>

      {/* Bindings Table - Main Content */}
      <Card style={{
        background: 'var(--bg-card)',
        border: '1px solid var(--border-subtle)',
        borderRadius: 12,
      }}>
        <Table
          columns={columns}
          dataSource={bindings}
          rowKey="bindingId"
          loading={bindingsLoading}
          pagination={{ pageSize: 10, showSizeChanger: true, showQuickJumper: true }}
          locale={{ emptyText: (
            <Empty
              image={<ThunderboltOutlined style={{ fontSize: 48, color: 'var(--text-muted)' }} />}
              description={t('plugin_bindings.bindings_empty')}
            />
          ) }}
          size="middle"
          style={{ background: 'transparent' }}
        />
      </Card>

      {/* Plugin Select Modal - Shown when clicking Create button */}
      <Modal
        title={
          <div style={{ display: 'flex', alignItems: 'center' }}>
            <div style={{
              width: 40,
              height: 40,
              borderRadius: 10,
              background: 'linear-gradient(135deg, #8b5cf6 0%, #7c3aed 100%)',
              display: 'flex',
              alignItems: 'center',
              justifyContent: 'center',
              marginRight: 12,
              color: '#fff',
            }}>
              <PlusOutlined />
            </div>
            <div>
              <div style={{ fontSize: 16, fontWeight: 600, color: 'var(--text-primary)' }}>{t('plugin_bindings.select_plugin')}</div>
              <div style={{ fontSize: 12, color: 'var(--text-muted)' }}>{t('plugin_bindings.select_plugin_desc')}</div>
            </div>
          </div>
        }
        open={pluginSelectModalVisible}
        onCancel={() => setPluginSelectModalVisible(false)}
        footer={null}
        width={800}
        style={{ top: 20 }}
      >
        <Spin spinning={loading}>
          {plugins.length === 0 ? (
            <Empty
              image={<ThunderboltOutlined style={{ fontSize: 48, color: 'var(--text-muted)' }} />}
              description={t('plugin_bindings.plugins_empty')}
            />
          ) : (
            Object.entries(pluginsByCategory).map(([category, categoryPlugins]) => (
              <div key={category} style={{ marginBottom: 24 }}>
                <Divider style={{ margin: '0 0 16px 0' }}>
                  <Tag style={{
                    background: `${getCategoryDesign(category).accentColor}20`,
                    border: `1px solid ${getCategoryDesign(category).accentColor}40`,
                    color: getCategoryDesign(category).accentColor,
                    borderRadius: 8,
                    padding: '4px 12px',
                    fontSize: 13,
                  }}>
                    {category} / {categoryLabels[category] || category}
                  </Tag>
                </Divider>
                <Row gutter={[16, 16]}>
                  {categoryPlugins.map((plugin) => (
                    <Col span={6} key={plugin.pluginId}>
                      <Card
                        hoverable
                        style={{
                          borderRadius: 16,
                          border: '1px solid var(--border-subtle)',
                          background: 'var(--bg-card)',
                          transition: 'all 0.3s cubic-bezier(0.4, 0, 0.2, 1)',
                          cursor: 'pointer',
                          overflow: 'hidden',
                        }}
                        styles={{ body: { padding: '24px 16px 16px' } }}
                        onClick={() => {
                          setPluginSelectModalVisible(false);
                          handlePluginSelect(plugin);
                        }}
                        className="plugin-card-hover"
                      >
                        {/* Plugin Icon - Gradient background with glow effect */}
                        <div style={{
                          width: 56,
                          height: 56,
                          borderRadius: 14,
                          background: getCategoryDesign(plugin.category).gradient,
                          display: 'flex',
                          alignItems: 'center',
                          justifyContent: 'center',
                          margin: '0 auto 16px',
                          fontSize: 26,
                          color: '#fff',
                          boxShadow: `0 8px 24px ${getCategoryDesign(plugin.category).glowColor}`,
                          position: 'relative',
                        }}>
                          {plugin.jarPath !== 'BUILTIN' ? (
                            <AppstoreAddOutlined style={{ opacity: 0.9 }} />
                          ) : (
                            getPluginIcon(plugin)
                          )}
                          {/* Built-in badge */}
                          {plugin.jarPath === 'BUILTIN' && (
                            <div style={{
                              position: 'absolute',
                              top: -4,
                              right: -4,
                              width: 18,
                              height: 18,
                              borderRadius: '50%',
                              background: 'var(--bg-card)',
                              border: '2px solid var(--primary-color)',
                              display: 'flex',
                              alignItems: 'center',
                              justifyContent: 'center',
                              fontSize: 10,
                            }}>
                              <CheckCircleOutlined style={{ color: 'var(--primary-color)', fontSize: 10 }} />
                            </div>
                          )}
                        </div>
                        
                        {/* Plugin Name */}
                        <Text strong style={{ 
                          display: 'block', 
                          marginBottom: 8, 
                          textAlign: 'center',
                          color: 'var(--text-primary)',
                          fontSize: 14,
                        }}>
                          {plugin.pluginName}
                        </Text>
                        
                        {/* Category/Type Badge */}
                        <div style={{ display: 'flex', justifyContent: 'center', gap: 6, marginBottom: 8 }}>
                          <Tag 
                            style={{ 
                              background: `${getCategoryDesign(plugin.category).accentColor}20`,
                              border: `1px solid ${getCategoryDesign(plugin.category).accentColor}40`,
                              color: getCategoryDesign(plugin.category).accentColor,
                              borderRadius: 6,
                              fontSize: 11,
                              padding: '2px 8px',
                            }}
                          >
                            {categoryLabels[plugin.category] || plugin.category}
                          </Tag>
                          {plugin.jarPath !== 'BUILTIN' && (
                            <Tag 
                              icon={<AppstoreAddOutlined />}
                              style={{ 
                                background: 'rgba(139, 92, 246, 0.15)',
                                border: '1px solid rgba(139, 92, 246, 0.3)',
                                color: '#a78bfa',
                                borderRadius: 6,
                                fontSize: 11,
                                padding: '2px 8px',
                              }}
                            >
                              {t('plugin_bindings.custom')}
                            </Tag>
                          )}
                        </div>
                        
                        {/* Description */}
                        {getDescription(plugin) && (
                          <Tooltip title={getDescription(plugin)}>
                            <Text 
                              type="secondary" 
                              style={{ 
                                fontSize: 12, 
                                display: 'block', 
                                textAlign: 'center', 
                                overflow: 'hidden', 
                                textOverflow: 'ellipsis', 
                                whiteSpace: 'nowrap',
                                color: 'var(--text-muted)',
                              }}
                            >
                              {getDescription(plugin)}
                            </Text>
                          </Tooltip>
                        )}
                      </Card>
                    </Col>
                  ))}
                </Row>
              </div>
            ))
          )}
        </Spin>
      </Modal>

      {/* Bind Plugin Modal */}
      <Modal
        title={
          <div style={{ display: 'flex', alignItems: 'center' }}>
            <div style={{
              width: 40,
              height: 40,
              borderRadius: '50%',
              background: `${selectedPlugin?.color || '#8b5cf6'}20`,
              display: 'flex',
              alignItems: 'center',
              justifyContent: 'center',
              marginRight: 12,
              color: selectedPlugin?.color || '#8b5cf6',
            }}>
              {selectedPlugin && getPluginIcon(selectedPlugin)}
            </div>
            <div>
              <div style={{ fontSize: 16, fontWeight: 600 }}>{selectedPlugin?.pluginName}</div>
              <div style={{ fontSize: 12, color: '#666' }}>{t('plugin_bindings.create_binding')}</div>
            </div>
          </div>
        }
        open={bindModalVisible}
        onOk={handleBind}
        onCancel={() => {
          setBindModalVisible(false);
          form.resetFields();
          setSelectedPlugin(null);
          setConfigSchema(null);
          setConfigValues({});
          setSelectedRouteId(null);
        }}
        width={600}
      >
        <Alert
          message={t('plugin_bindings.bind_notice')}
          description={t('plugin_bindings.bind_notice_desc')}
          type="info"
          showIcon
          style={{ marginBottom: 16 }}
        />

        <Form form={form} layout="vertical">
          {/* Binding Type Selection */}
          <Form.Item
            label={t('plugin_bindings.binding_type')}
            required
          >
            <Radio.Group
              value={bindType}
              onChange={(e) => setBindType(e.target.value)}
              style={{ width: '100%' }}
            >
              <Radio.Button value="GLOBAL" style={{ width: '50%', textAlign: 'center' }}>
                <GlobalOutlined style={{ marginRight: 8 }} />
                {t('plugin_bindings.global_binding')}
              </Radio.Button>
              <Radio.Button value="ROUTE" style={{ width: '50%', textAlign: 'center' }}>
                <ApiOutlined style={{ marginRight: 8 }} />
                {t('plugin_bindings.route_binding')}
              </Radio.Button>
            </Radio.Group>
          </Form.Item>

          {/* Route Selection (if ROUTE binding) */}
          {bindType === 'ROUTE' && (
            <Form.Item
              label={t('plugin_bindings.select_route')}
              required
              rules={[{ required: bindType === 'ROUTE', message: t('plugin_bindings.select_route_required') }]}
            >
              <Select
                placeholder={t('plugin_bindings.select_route_placeholder')}
                value={selectedRouteId}
                onChange={(v) => setSelectedRouteId(v)}
                showSearch
                optionFilterProp="children"
                style={{ width: '100%' }}
              >
                {routes.map((route) => (
                  <Select.Option key={route.id} value={route.id}>
                    <Badge status={route.enabled ? 'success' : 'default'} style={{ marginRight: 8 }} />
                    {route.routeName || route.id}
                    <Text type="secondary" style={{ marginLeft: 8 }}>({route.uri})</Text>
                  </Select.Option>
                ))}
              </Select>
            </Form.Item>
          )}

          <Form.Item
            name="order"
            label={t('plugin_bindings.execution_order')}
            initialValue={0}
            help={t('plugin_bindings.execution_order_help')}
          >
            <InputNumber min={-1000} max={10000} style={{ width: '100%' }} />
          </Form.Item>

          {renderConfigForm()}
        </Form>
      </Modal>

      {/* Edit Binding Modal */}
      <Modal
        title={
          <div style={{ display: 'flex', alignItems: 'center' }}>
            <EditOutlined style={{ marginRight: 12, color: '#8b5cf6' }} />
            <div>
              <div style={{ fontSize: 16, fontWeight: 600 }}>{t('plugin_bindings.edit_title')}</div>
              <div style={{ fontSize: 12, color: '#666' }}>
                {selectedBinding?.pluginName} - {selectedBinding?.routeId === 'GLOBAL' ? t('plugin_bindings.global') : selectedBinding?.routeName}
              </div>
            </div>
          </div>
        }
        open={editModalVisible}
        onOk={handleEditBinding}
        onCancel={() => {
          setEditModalVisible(false);
          form.resetFields();
          setSelectedBinding(null);
          setSelectedPlugin(null);
          setConfigSchema(null);
          setConfigValues({});
        }}
        width={600}
      >
        <Form form={form} layout="vertical">
          <Form.Item
            name="order"
            label={t('plugin_bindings.execution_order')}
          >
            <InputNumber min={-1000} max={10000} style={{ width: '100%' }} />
          </Form.Item>
          {renderConfigForm()}
        </Form>
      </Modal>
    </div>
  );
};

export default RoutePluginBindingsPage;