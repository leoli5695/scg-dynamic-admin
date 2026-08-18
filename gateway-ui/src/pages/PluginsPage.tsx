import React, { useState, useEffect } from 'react';
import {
  Card,
  Row,
  Col,
  Button,
  Table,
  Tag,
  Space,
  Modal,
  Upload,
  message,
  Popconfirm,
  Drawer,
  Descriptions,
  Statistic,
  Alert,
  Tooltip,
  Input,
  Select,
  Badge,
  Divider,
  Collapse,
  Typography,
} from 'antd';
import {
  UploadOutlined,
  ThunderboltOutlined,
  SecurityScanOutlined,
  ApiOutlined,
  SyncOutlined,
  DeleteOutlined,
  EyeOutlined,
  StopOutlined,
  PlayCircleOutlined,
  DownloadOutlined,
  FileTextOutlined,
  RocketOutlined,
  AppstoreOutlined,
  CodeOutlined,
  GithubOutlined,
  BookOutlined,
  CopyOutlined,
} from '@ant-design/icons';
import api from '../utils/api';
import type { UploadProps } from 'antd/es/upload';
import { useTranslation } from 'react-i18next';

const { Text, Paragraph } = Typography;
const { Panel } = Collapse;

interface Plugin {
  id: number;
  pluginId: string;
  pluginName: string;
  pluginClass: string;
  configClass: string;
  version: string;
  description: string;
  descriptionEn: string;
  category: string;
  icon: string;
  color: string;
  configSchema: string;
  jarPath: string;
  jarHash: string;
  enabled: boolean;
  loaded: boolean;
  registered: boolean;
  instanceId: string;
  createdAt: string;
  updatedAt: string;
}

interface PluginStats {
  total: number;
  enabled: number;
  disabled: number;
  loaded: number;
  registered: number;
}

interface PluginsPageProps {
  instanceId?: string;
}

const PluginsPage: React.FC<PluginsPageProps> = ({ instanceId }) => {
  const { t, i18n } = useTranslation();
  const [plugins, setPlugins] = useState<Plugin[]>([]);
  const [loading, setLoading] = useState(false);
  const [stats, setStats] = useState<PluginStats>({ total: 0, enabled: 0, disabled: 0, loaded: 0, registered: 0 });
  const [uploadModalVisible, setUploadModalVisible] = useState(false);
  const [detailDrawerVisible, setDetailDrawerVisible] = useState(false);
  const [selectedPlugin, setSelectedPlugin] = useState<Plugin | null>(null);

  // Helper: get description based on current language
  const getDescription = (plugin: Plugin) => {
    const isZh = i18n.language === 'zh' || i18n.language?.startsWith('zh');
    return isZh ? plugin.description : (plugin.descriptionEn || plugin.description);
  };

  // Load plugins
  const loadPlugins = async () => {
    setLoading(true);
    try {
      const response = await api.get('/api/plugins', { params: { instanceId } });
      setPlugins(response.data || []);
    } catch (error) {
      console.error('Failed to load plugins:', error);
      message.error(t('plugins.load_failed'));
    } finally {
      setLoading(false);
    }
  };

  // Load stats
  const loadStats = async () => {
    try {
      const response = await api.get('/api/plugins/stats', { params: { instanceId } });
      setStats(response.data || { total: 0, enabled: 0, disabled: 0, loaded: 0, registered: 0 });
    } catch (error) {
      console.error('Failed to load stats:', error);
    }
  };

  useEffect(() => {
    loadPlugins();
    loadStats();
  }, [instanceId]);

  // Upload props
  const uploadProps: UploadProps = {
    name: 'file',
    accept: '.jar',
    showUploadList: false,
    customRequest: async (options) => {
      const { file, onSuccess, onError } = options;
      const formData = new FormData();
      formData.append('file', file as File);
      if (instanceId) {
        formData.append('instanceId', instanceId);
      }

      try {
        const response = await api.post('/api/plugins/upload', formData, {
          headers: { 'Content-Type': 'multipart/form-data' },
        });
        message.success(t('plugins.upload_success'));
        setUploadModalVisible(false);
        loadPlugins();
        loadStats();
        onSuccess?.(response.data);
      } catch (error) {
        const err = error as { response?: { data?: string } };
        const errorMsg = err.response?.data || t('plugins.upload_failed');
        message.error(errorMsg);
        onError?.(error as ProgressEvent<EventTarget>);
      }
    },
  };

  // Enable plugin
  const handleEnable = async (pluginId: string) => {
    try {
      await api.post(`/api/plugins/${pluginId}/enable`);
      message.success(t('plugins.enable_success'));
      loadPlugins();
      loadStats();
    } catch (error) {
      message.error(t('plugins.enable_failed'));
    }
  };

  // Disable plugin
  const handleDisable = async (pluginId: string) => {
    try {
      await api.post(`/api/plugins/${pluginId}/disable`);
      message.success(t('plugins.disable_success'));
      loadPlugins();
      loadStats();
    } catch (error) {
      message.error(t('plugins.disable_failed'));
    }
  };

  // Delete plugin
  const handleDelete = async (pluginId: string) => {
    try {
      await api.delete(`/api/plugins/${pluginId}`);
      message.success(t('plugins.delete_success'));
      loadPlugins();
      loadStats();
    } catch (error) {
      message.error(t('plugins.delete_failed'));
    }
  };

  // View plugin details
  const handleViewDetails = (plugin: Plugin) => {
    setSelectedPlugin(plugin);
    setDetailDrawerVisible(true);
  };

  // Category icon mapping
  const getCategoryIcon = (category: string) => {
    switch (category) {
      case 'security':
        return <SecurityScanOutlined />;
      case 'traffic':
        return <ApiOutlined />;
      case 'transform':
        return <SyncOutlined />;
      default:
        return <ThunderboltOutlined />;
    }
  };

  // Category color mapping
  const getCategoryColor = (category: string) => {
    switch (category) {
      case 'security':
        return 'red';
      case 'traffic':
        return 'blue';
      case 'transform':
        return 'green';
      default:
        return 'purple';
    }
  };

  // Status badge
  const getStatusBadge = (plugin: Plugin) => {
    if (!plugin.enabled) {
      return <Badge status="default" text={t('plugins.status_disabled')} />;
    }
    if (!plugin.loaded) {
      return <Badge status="warning" text={t('plugins.status_not_loaded')} />;
    }
    if (!plugin.registered) {
      return <Badge status="processing" text={t('plugins.status_loading')} />;
    }
    return <Badge status="success" text={t('plugins.status_active')} />;
  };

  // Table columns
  const columns = [
    {
      title: t('plugins.plugin_name'),
      dataIndex: 'pluginName',
      key: 'pluginName',
      render: (name: string, record: Plugin) => (
        <Space>
          <Tag icon={getCategoryIcon(record.category)} color={record.color || getCategoryColor(record.category)}>
            {name}
          </Tag>
        </Space>
      ),
    },
    {
      title: t('plugins.version'),
      dataIndex: 'version',
      key: 'version',
      render: (version: string) => <Tag>{version}</Tag>,
    },
    {
      title: t('plugins.category'),
      dataIndex: 'category',
      key: 'category',
      render: (category: string) => (
        <Tag color={getCategoryColor(category)}>{category.toUpperCase()}</Tag>
      ),
    },
    {
      title: t('plugins.status'),
      key: 'status',
      render: (record: Plugin) => getStatusBadge(record),
    },
    {
      title: t('plugins.description_col'),
      key: 'description',
      ellipsis: true,
      render: (record: Plugin) => getDescription(record),
    },
    {
      title: t('plugins.created'),
      dataIndex: 'createdAt',
      key: 'createdAt',
      render: (date: string) => new Date(date).toLocaleString(),
    },
    {
      title: t('plugins.actions'),
      key: 'actions',
      render: (record: Plugin) => (
        <Space>
          <Tooltip title={t('plugins.view_details')}>
            <Button
              type="text"
              icon={<EyeOutlined />}
              onClick={() => handleViewDetails(record)}
            />
          </Tooltip>
          {record.enabled ? (
            <Tooltip title={t('plugins.disable')}>
              <Button
                type="text"
                icon={<StopOutlined />}
                onClick={() => handleDisable(record.pluginId)}
              />
            </Tooltip>
          ) : (
            <Tooltip title={t('plugins.enable')}>
              <Button
                type="text"
                icon={<PlayCircleOutlined />}
                onClick={() => handleEnable(record.pluginId)}
              />
            </Tooltip>
          )}
          <Popconfirm
            title={t('plugins.delete_confirm')}
            description={t('plugins.delete_confirm_desc')}
            onConfirm={() => handleDelete(record.pluginId)}
            okText={t('common.yes')}
            cancelText={t('common.no')}
          >
            <Tooltip title={t('plugins.delete')}>
              <Button type="text" danger icon={<DeleteOutlined />} />
            </Tooltip>
          </Popconfirm>
        </Space>
      ),
    },
  ];

  return (
    <div style={{ padding: '24px' }}>
      <Card>
        <Row gutter={[16, 16]}>
          <Col span={24}>
            <Row justify="space-between" align="middle">
              <Col>
                <h2 style={{ margin: 0 }}>
                  <RocketOutlined style={{ marginRight: 8 }} />
                  {t('plugins.title')}
                </h2>
              </Col>
              <Col>
                <Space>
                  <Button icon={<UploadOutlined />} onClick={() => setUploadModalVisible(true)}>
                    {t('plugins.upload')}
                  </Button>
                  <Button icon={<SyncOutlined />} onClick={loadPlugins}>
                    {t('plugins.refresh')}
                  </Button>
                </Space>
              </Col>
            </Row>
          </Col>

          {/* Stats Row */}
          <Col span={24}>
            <Row gutter={16}>
              <Col span={4}>
                <Statistic title={t('plugins.total_plugins')} value={stats.total} prefix={<AppstoreOutlined />} />
              </Col>
              <Col span={4}>
                <Statistic title={t('plugins.enabled')} value={stats.enabled} valueStyle={{ color: '#3f8600' }} prefix={<PlayCircleOutlined />} />
              </Col>
              <Col span={4}>
                <Statistic title={t('plugins.disabled')} value={stats.disabled} valueStyle={{ color: '#cf1322' }} prefix={<StopOutlined />} />
              </Col>
              <Col span={4}>
                <Statistic title={t('plugins.loaded')} value={stats.loaded} valueStyle={{ color: '#1890ff' }} prefix={<ThunderboltOutlined />} />
              </Col>
              <Col span={4}>
                <Statistic title={t('plugins.registered')} value={stats.registered} valueStyle={{ color: '#52c41a' }} prefix={<FileTextOutlined />} />
              </Col>
              <Col span={4}>
                <Statistic title={t('plugins.active_rate')} value={Math.round((stats.registered / stats.total) * 100) || 0} suffix="%" />
              </Col>
            </Row>
          </Col>

          {/* Info Alert */}
          <Col span={24}>
            <Alert
              message={t('plugins.development_guide')}
              description={t('plugins.development_guide_desc')}
              type="info"
              showIcon
              closable
            />
          </Col>

          {/* Example Plugin Template */}
          <Col span={24}>
            <Card 
              title={<span><BookOutlined style={{ marginRight: 8 }} />{t('plugins.example_template')}</span>}
              size="small"
              style={{ marginBottom: 16 }}
              extra={
                <Space>
                  <Button 
                    type="primary" 
                    icon={<DownloadOutlined />}
                    onClick={() => {
                      message.info(t('plugins.view_example_hint'));
                    }}
                  >
                    {t('plugins.view_example_source')}
                  </Button>
                </Space>
              }
            >
              <Collapse ghost>
                <Panel header={t('plugins.dev_steps')} key="steps">
                  <ol style={{ paddingLeft: 20, margin: 0 }}>
                    <li><Text>{t('plugins.dev_step_1')}</Text></li>
                    <li><Text>{t('plugins.dev_step_2')}</Text></li>
                    <li><Text>{t('plugins.dev_step_3')}</Text></li>
                    <li><Text>{t('plugins.dev_step_4')}</Text></li>
                    <li><Text>{t('plugins.dev_step_5')}</Text></li>
                    <li><Text>{t('plugins.dev_step_6')}</Text></li>
                    <li><Text>{t('plugins.dev_step_7')}</Text></li>
                  </ol>
                </Panel>
                <Panel header={t('plugins.example_structure')} key="structure">
                  <Paragraph style={{ marginBottom: 8 }}>
                    <pre style={{ 
                      background: '#1e1e1e', 
                      color: '#d4d4d4', 
                      padding: 12, 
                      borderRadius: 4,
                      fontSize: 12,
                      overflow: 'auto'
                    }}>
{`plugin-example/
├── pom.xml
├── src/main/java/com/example/
│   └── CustomRateLimitGatewayFilterFactory.java
└── src/main/resources/META-INF/
    └── plugin-schema.json`}
                    </pre>
                  </Paragraph>
                </Panel>
                <Panel header={t('plugins.schema_example')} key="schema">
                  <Paragraph style={{ marginBottom: 0 }}>
                    <pre style={{ 
                      background: '#1e1e1e', 
                      color: '#d4d4d4', 
                      padding: 12, 
                      borderRadius: 4,
                      fontSize: 12,
                      maxHeight: 200,
                      overflow: 'auto'
                    }}>
{`{
  "fields": [
    {
      "name": "maxRequests",
      "type": "number",
      "label": "${t('plugins.schema_field_max_requests')}",
      "default": 100,
      "min": 1,
      "max": 10000
    },
    {
      "name": "keyResolver",
      "type": "select",
      "label": "${t('plugins.schema_field_key_resolver')}",
      "options": [
        {"label": "${t('plugins.schema_option_ip')}", "value": "IP"},
        {"label": "${t('plugins.schema_option_user')}", "value": "USER"}
      ]
    }
  ]
}`}
                    </pre>
                  </Paragraph>
                </Panel>
              </Collapse>
            </Card>
          </Col>

          {/* Table */}
          <Col span={24}>
            <Table
              columns={columns}
              dataSource={plugins}
              rowKey="pluginId"
              loading={loading}
              pagination={{ pageSize: 10 }}
            />
          </Col>
        </Row>
      </Card>

      {/* Upload Modal */}
      <Modal
        title={t('plugins.upload_title')}
        open={uploadModalVisible}
        onCancel={() => setUploadModalVisible(false)}
        footer={null}
      >
        <Alert
          message={t('plugins.upload_requirements')}
          description={
            <ul style={{ margin: 0, paddingLeft: 20 }}>
              <li>{t('plugins.upload_requirement_1')}</li>
              <li>{t('plugins.upload_requirement_2')}</li>
              <li>{t('plugins.upload_requirement_3')}</li>
            </ul>
          }
          type="info"
          showIcon
          style={{ marginBottom: 16 }}
        />
        <Upload.Dragger {...uploadProps}>
          <p className="ant-upload-drag-icon">
            <UploadOutlined />
          </p>
          <p className="ant-upload-text">{t('plugins.upload_drag')}</p>
          <p className="ant-upload-hint">{t('plugins.upload_hint')}</p>
        </Upload.Dragger>
      </Modal>

      {/* Detail Drawer */}
      <Drawer
        title={t('plugins.detail_title')}
        width={600}
        open={detailDrawerVisible}
        onClose={() => setDetailDrawerVisible(false)}
      >
        {selectedPlugin && (
          <Descriptions column={2} bordered>
            <Descriptions.Item label={t('plugins.plugin_id')} span={2}>
              {selectedPlugin.pluginId}
            </Descriptions.Item>
            <Descriptions.Item label={t('plugins.plugin_name_label')}>
              <Tag color={selectedPlugin.color}>{selectedPlugin.pluginName}</Tag>
            </Descriptions.Item>
            <Descriptions.Item label={t('plugins.version_label')}>
              <Tag>{selectedPlugin.version}</Tag>
            </Descriptions.Item>
            <Descriptions.Item label={t('plugins.factory_class')} span={2}>
              <code style={{ fontSize: 12 }}>{selectedPlugin.pluginClass}</code>
            </Descriptions.Item>
            <Descriptions.Item label={t('plugins.config_class')} span={2}>
              {selectedPlugin.configClass ? (
                <code style={{ fontSize: 12 }}>{selectedPlugin.configClass}</code>
              ) : (
                <Tag>{t('plugins.no_config_class')}</Tag>
              )}
            </Descriptions.Item>
            <Descriptions.Item label={t('plugins.category_label')}>
              <Tag color={getCategoryColor(selectedPlugin.category)}>{selectedPlugin.category}</Tag>
            </Descriptions.Item>
            <Descriptions.Item label={t('plugins.status_label')}>
              {getStatusBadge(selectedPlugin)}
            </Descriptions.Item>
            <Descriptions.Item label={t('plugins.enabled_label')}>
              {selectedPlugin.enabled ? <Tag color="green">{t('common.yes')}</Tag> : <Tag color="red">{t('common.no')}</Tag>}
            </Descriptions.Item>
            <Descriptions.Item label={t('plugins.loaded_label')}>
              {selectedPlugin.loaded ? <Tag color="blue">{t('common.yes')}</Tag> : <Tag>{t('common.no')}</Tag>}
            </Descriptions.Item>
            <Descriptions.Item label={t('plugins.registered_label')}>
              {selectedPlugin.registered ? <Tag color="success">{t('common.yes')}</Tag> : <Tag>{t('common.no')}</Tag>}
            </Descriptions.Item>
            <Descriptions.Item label={t('plugins.jar_hash')}>
              <Tooltip title={selectedPlugin.jarHash}>
                <code style={{ fontSize: 10 }}>{selectedPlugin.jarHash?.substring(0, 16)}...</code>
              </Tooltip>
            </Descriptions.Item>
            <Descriptions.Item label={t('plugins.description_label')} span={2}>
              {getDescription(selectedPlugin)}
            </Descriptions.Item>
            <Descriptions.Item label={t('plugins.created_at')}>
              {new Date(selectedPlugin.createdAt).toLocaleString()}
            </Descriptions.Item>
            <Descriptions.Item label={t('plugins.updated_at')}>
              {new Date(selectedPlugin.updatedAt).toLocaleString()}
            </Descriptions.Item>
          </Descriptions>
        )}
      </Drawer>
    </div>
  );
};

export default PluginsPage;