/**
 * Plugin detail drawer — full metadata of a single PluginEntity.
 */
import React from 'react';
import { Badge, Descriptions, Drawer, Tag, Tooltip } from 'antd';
import { useTranslation } from 'react-i18next';
import type { Plugin } from '../../../types/plugin';
import { getCategoryColor, getPluginDescription, getPluginStatus } from '../utils';
import { parseServerTime } from '../../../utils/serverTime';

interface PluginDetailDrawerProps {
  plugin: Plugin;
  onClose: () => void;
}

const PluginDetailDrawer: React.FC<PluginDetailDrawerProps> = ({ plugin, onClose }) => {
  const { t, i18n } = useTranslation();
  const status = getPluginStatus(plugin);

  return (
    <Drawer
      title={t('plugins.detail_title')}
      width={600}
      open
      onClose={onClose}
      destroyOnHidden
    >
      <Descriptions column={2} bordered>
        <Descriptions.Item label={t('plugins.plugin_id')} span={2}>
          {plugin.pluginId}
        </Descriptions.Item>
        <Descriptions.Item label={t('plugins.plugin_name_label')}>
          <Tag color={plugin.color}>{plugin.pluginName}</Tag>
        </Descriptions.Item>
        <Descriptions.Item label={t('plugins.version_label')}>
          <Tag>{plugin.version}</Tag>
        </Descriptions.Item>
        <Descriptions.Item label={t('plugins.factory_class')} span={2}>
          <code style={{ fontSize: 12 }}>{plugin.pluginClass}</code>
        </Descriptions.Item>
        <Descriptions.Item label={t('plugins.config_class')} span={2}>
          {plugin.configClass ? (
            <code style={{ fontSize: 12 }}>{plugin.configClass}</code>
          ) : (
            <Tag>{t('plugins.no_config_class')}</Tag>
          )}
        </Descriptions.Item>
        <Descriptions.Item label={t('plugins.category_label')}>
          <Tag color={getCategoryColor(plugin.category)}>{plugin.category}</Tag>
        </Descriptions.Item>
        <Descriptions.Item label={t('plugins.status_label')}>
          <Badge status={status.badge} text={t(status.labelKey)} />
        </Descriptions.Item>
        <Descriptions.Item label={t('plugins.enabled_label')}>
          {plugin.enabled ? <Tag color="green">{t('common.yes')}</Tag> : <Tag color="red">{t('common.no')}</Tag>}
        </Descriptions.Item>
        <Descriptions.Item label={t('plugins.loaded_label')}>
          {plugin.loaded ? <Tag color="blue">{t('common.yes')}</Tag> : <Tag>{t('common.no')}</Tag>}
        </Descriptions.Item>
        <Descriptions.Item label={t('plugins.registered_label')}>
          {plugin.registered ? <Tag color="success">{t('common.yes')}</Tag> : <Tag>{t('common.no')}</Tag>}
        </Descriptions.Item>
        <Descriptions.Item label={t('plugins.jar_hash')}>
          {plugin.jarHash ? (
            <Tooltip title={plugin.jarHash}>
              <code style={{ fontSize: 10 }}>{plugin.jarHash.substring(0, 16)}...</code>
            </Tooltip>
          ) : (
            '-'
          )}
        </Descriptions.Item>
        <Descriptions.Item label={t('plugins.description_label')} span={2}>
          {getPluginDescription(plugin, i18n.language)}
        </Descriptions.Item>
        <Descriptions.Item label={t('plugins.created_at')}>
          {parseServerTime(plugin.createdAt).toLocaleString()}
        </Descriptions.Item>
        <Descriptions.Item label={t('plugins.updated_at')}>
          {parseServerTime(plugin.updatedAt).toLocaleString()}
        </Descriptions.Item>
      </Descriptions>
    </Drawer>
  );
};

export default PluginDetailDrawer;
