/**
 * Plugins page (rewritten as a feature).
 * Fields aligned with PluginController (/api/plugins) + model/PluginEntity.java.
 * Controllers return bare entities — httpClient passes them through as-is.
 */
import React, { useCallback, useMemo, useState } from 'react';
import {
  Badge,
  Button,
  Card,
  Popconfirm,
  Space,
  Table,
  Tooltip,
} from 'antd';
import { message } from '../../utils/antdStatic';
import { formatServerTime } from '../../utils/serverTime';
import type { ColumnsType } from 'antd/es/table';
import {
  AppstoreOutlined,
  DeleteOutlined,
  EyeOutlined,
  FileTextOutlined,
  PlayCircleOutlined,
  StopOutlined,
  SyncOutlined,
  ThunderboltOutlined,
  UploadOutlined,
} from '@ant-design/icons';
import { useParams } from 'react-router-dom';
import { useTranslation } from 'react-i18next';
import { useRequest } from '../../hooks/useRequest';
import { deletePlugin, disablePlugin, enablePlugin, listPlugins } from '../../api/plugins';
import type { Plugin } from '../../types/plugin';
import { getCategoryColor, getCategoryIcon, getPluginDescription, getPluginStatus } from './utils';
import UploadPluginModal from './components/UploadPluginModal';
import PluginDetailDrawer from './components/PluginDetailDrawer';
import DevGuideCard from './components/DevGuideCard';
import { ManagementMetric, ManagementPageHeader } from '../layout/components/ManagementPage';
import { statusTone, toneForLegacyColor, withAlpha } from '../../styles/palette';
import './plugins.css';

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
  const plugins = useMemo(() => pluginsRequest.data ?? [], [pluginsRequest.data]);
  const stats = useMemo(
    () => ({
      total: plugins.length,
      enabled: plugins.filter((plugin) => plugin.enabled).length,
      disabled: plugins.filter((plugin) => !plugin.enabled).length,
      loaded: plugins.filter((plugin) => plugin.loaded).length,
      registered: plugins.filter((plugin) => plugin.registered).length,
    }),
    [plugins],
  );

  const reload = useCallback(() => {
    pluginsRequest.run();
  }, [pluginsRequest]);

  const handleToggleEnabled = useCallback(
    async (plugin: Plugin) => {
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
    },
    [t, reload],
  );

  const handleDelete = useCallback(
    async (pluginId: string) => {
      try {
        await deletePlugin(pluginId);
        message.success(t('plugins.delete_success'));
        reload();
      } catch {
        message.error(t('plugins.delete_failed'));
      }
    },
    [t, reload],
  );

  const columns: ColumnsType<Plugin> = useMemo(
    () => [
      {
        title: t('plugins.plugin_name'),
        dataIndex: 'pluginName',
        key: 'pluginName',
        render: (name: string, record: Plugin) => {
          const color = record.color
            ? statusTone[toneForLegacyColor(record.color)].color
            : getCategoryColor(record.category);
          return (
            <span
              className="plugin-name-chip"
              style={{
                color,
                background: withAlpha(color, 0.12),
                borderColor: withAlpha(color, 0.32),
              }}
            >
              {getCategoryIcon(record.category)}
              <span>{name}</span>
            </span>
          );
        },
      },
      {
        title: t('plugins.version'),
        dataIndex: 'version',
        key: 'version',
        render: (version: string) => <span className="plugin-version">{version}</span>,
      },
      {
        title: t('plugins.category'),
        dataIndex: 'category',
        key: 'category',
        render: (category: string) => (
          <span className="plugin-category">{(category || 'other').toUpperCase()}</span>
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
        className: 'plugin-col-time',
        render: (date: string) => formatServerTime(date),
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
                <Button
                  type="text"
                  icon={<StopOutlined />}
                  onClick={() => handleToggleEnabled(record)}
                />
              </Tooltip>
            ) : (
              <Tooltip title={t('plugins.enable')}>
                <Button
                  type="text"
                  icon={<PlayCircleOutlined />}
                  onClick={() => handleToggleEnabled(record)}
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
    ],
    [t, i18n.language, handleToggleEnabled, handleDelete],
  );

  return (
    <div className="plugins-page console-resource-page">
      <ManagementPageHeader
        eyebrow={t('instance_nav.tools')}
        title={t('plugins.title')}
        subtitle={t('plugins.description')}
        actions={(
          <Space>
            <Button
              type="primary"
              icon={<UploadOutlined />}
              onClick={() => setUploadOpen(true)}
            >
              {t('plugins.upload')}
            </Button>
            <Button icon={<SyncOutlined />} onClick={reload} loading={pluginsRequest.loading}>
              {t('plugins.refresh')}
            </Button>
          </Space>
        )}
      />

      <div className="management-metrics plugins-metrics">
        <ManagementMetric
          title={t('plugins.total_plugins')}
          value={stats.total}
          icon={<AppstoreOutlined />}
          subtitle={t('plugins.card.total_hint', { count: stats.registered })}
        />
        <ManagementMetric
          title={t('plugins.enabled')}
          value={stats.enabled}
          icon={<PlayCircleOutlined />}
          tone="green"
          subtitle={t('plugins.card.enabled_hint', { count: stats.disabled })}
        />
        <ManagementMetric
          title={t('plugins.loaded')}
          value={stats.loaded}
          icon={<ThunderboltOutlined />}
          tone="purple"
          subtitle={t('plugins.card.loaded_hint')}
        />
        <ManagementMetric
          title={t('plugins.active_rate')}
          value={`${stats.total ? Math.round((stats.registered / stats.total) * 100) : 0}%`}
          icon={<FileTextOutlined />}
          tone="amber"
          subtitle={t('plugins.card.active_hint')}
          accentBar={(
            <span className="management-metric-accent" aria-hidden="true">
              <i
                style={{
                  width: `${stats.total ? Math.round((stats.registered / stats.total) * 100) : 0}%`,
                  background: 'linear-gradient(90deg, #5b8cf5, #a78bfa)',
                }}
              />
            </span>
          )}
        />
      </div>

      <Card className="plugins-table-card">
        <Table
          columns={columns}
          dataSource={plugins}
          rowKey="pluginId"
          loading={pluginsRequest.loading}
          pagination={{ defaultPageSize: 10 }}
          scroll={{ x: 900 }}
        />
      </Card>

      <DevGuideCard />

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
