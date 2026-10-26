/**
 * Plugins page (rewritten as a feature).
 * Fields aligned with PluginController (/api/plugins) + model/PluginEntity.java.
 * Controllers return bare entities — httpClient passes them through as-is.
 */
import React, { useCallback, useMemo, useState } from 'react';
import {
  Badge, Button, Card, Col, Popconfirm, Row, Space, Statistic, Table, Tag, Tooltip  } from 'antd';
import { message } from '../../utils/antdStatic';
import { parseServerTime } from '../../utils/serverTime';
import type { ColumnsType } from 'antd/es/table';
import {
  AppstoreOutlined, DeleteOutlined, EyeOutlined, FileTextOutlined,
  PlayCircleOutlined, RocketOutlined, StopOutlined, SyncOutlined,
  ThunderboltOutlined, UploadOutlined,
} from '@ant-design/icons';
import { useParams } from 'react-router-dom';
import { useTranslation } from 'react-i18next';
import { useRequest } from '../../hooks/useRequest';
import {
  deletePlugin, disablePlugin, enablePlugin, getPluginStats, listPlugins,
} from '../../api/plugins';
import type { Plugin, PluginStats } from '../../types/plugin';
import { getCategoryColor, getCategoryIcon, getPluginDescription, getPluginStatus } from './utils';
import UploadPluginModal from './components/UploadPluginModal';
import PluginDetailDrawer from './components/PluginDetailDrawer';
import DevGuideCard from './components/DevGuideCard';

const EMPTY_STATS: PluginStats = { total: 0, enabled: 0, disabled: 0, loaded: 0, registered: 0 };

const PluginsPage: React.FC = () => {
  const { t, i18n } = useTranslation();
  const { instanceId } = useParams<{ instanceId: string }>();

  const [uploadOpen, setUploadOpen] = useState(false);
  const [detailPlugin, setDetailPlugin] = useState<Plugin | null>(null);

  const pluginsRequest = useRequest(
    (signal) => listPlugins(instanceId ? { instanceId } : undefined, { signal }),
    {
      deps: [instanceId],
      onError: () => message.error(t('plugins.load_failed')),
    },
  );
  const statsRequest = useRequest(
    (signal) => getPluginStats(instanceId ? { instanceId } : undefined, { signal }),
    { deps: [instanceId] },
  );

  const plugins = useMemo(() => pluginsRequest.data ?? [], [pluginsRequest.data]);
  const stats = statsRequest.data ?? EMPTY_STATS;

  const reload = useCallback(() => {
    pluginsRequest.run();
    statsRequest.run();
  }, [pluginsRequest, statsRequest]);

  const handleToggleEnabled = useCallback(async (plugin: Plugin) => {
    try {
      if (plugin.enabled) {
        await disablePlugin(plugin.pluginId);
        message.success(t('plugins.disable_success'));
      } else {
        await enablePlugin(plugin.pluginId);
        message.success(t('plugins.enable_success'));
      }
      reload();
    } catch {
      message.error(plugin.enabled ? t('plugins.disable_failed') : t('plugins.enable_failed'));
    }
  }, [t, reload]);

  const handleDelete = useCallback(async (pluginId: string) => {
    try {
      await deletePlugin(pluginId);
      message.success(t('plugins.delete_success'));
      reload();
    } catch {
      message.error(t('plugins.delete_failed'));
    }
  }, [t, reload]);

  const columns: ColumnsType<Plugin> = useMemo(() => [
    {
      title: t('plugins.plugin_name'),
      dataIndex: 'pluginName',
      key: 'pluginName',
      render: (name: string, record: Plugin) => (
        <Tag icon={getCategoryIcon(record.category)} color={record.color || getCategoryColor(record.category)}>
          {name}
        </Tag>
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
      render: (record: Plugin) => {
        const status = getPluginStatus(record);
        return <Badge status={status.badge} text={t(status.labelKey)} />;
      },
    },
    {
      title: t('plugins.description_col'),
      key: 'description',
      ellipsis: true,
      render: (record: Plugin) => getPluginDescription(record, i18n.language),
    },
    {
      title: t('plugins.created'),
      dataIndex: 'createdAt',
      key: 'createdAt',
      render: (date: string) => parseServerTime(date).toLocaleString(),
    },
    {
      title: t('plugins.actions'),
      key: 'actions',
      render: (record: Plugin) => (
        <Space>
          <Tooltip title={t('plugins.view_details')}>
            <Button type="text" icon={<EyeOutlined />} onClick={() => setDetailPlugin(record)} />
          </Tooltip>
          {record.enabled ? (
            <Tooltip title={t('plugins.disable')}>
              <Button type="text" icon={<StopOutlined />} onClick={() => handleToggleEnabled(record)} />
            </Tooltip>
          ) : (
            <Tooltip title={t('plugins.enable')}>
              <Button type="text" icon={<PlayCircleOutlined />} onClick={() => handleToggleEnabled(record)} />
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
  ], [t, i18n.language, handleToggleEnabled, handleDelete]);

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
                  <Button icon={<UploadOutlined />} onClick={() => setUploadOpen(true)}>
                    {t('plugins.upload')}
                  </Button>
                  <Button icon={<SyncOutlined />} onClick={reload}>
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
                <Statistic
                  title={t('plugins.active_rate')}
                  value={stats.total ? Math.round((stats.registered / stats.total) * 100) : 0}
                  suffix="%"
                />
              </Col>
            </Row>
          </Col>

          {/* Development guide + example template */}
          <Col span={24}>
            <DevGuideCard />
          </Col>

          {/* Table */}
          <Col span={24}>
            <Table
              columns={columns}
              dataSource={plugins}
              rowKey="pluginId"
              loading={pluginsRequest.loading}
              pagination={{ pageSize: 10 }}
            />
          </Col>
        </Row>
      </Card>

      {uploadOpen && (
        <UploadPluginModal
          instanceId={instanceId}
          onClose={() => setUploadOpen(false)}
          onUploaded={reload}
        />
      )}

      {detailPlugin && (
        <PluginDetailDrawer plugin={detailPlugin} onClose={() => setDetailPlugin(null)} />
      )}
    </div>
  );
};

export default PluginsPage;
