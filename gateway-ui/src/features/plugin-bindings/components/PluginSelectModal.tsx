/**
 * Plugin picker modal — enabled plugins grouped by category as cards.
 */
import React, { useMemo } from 'react';
import { Card, Col, Divider, Empty, Modal, Row, Spin, Tag, Tooltip, Typography } from 'antd';
import {
  AppstoreAddOutlined,
  CheckCircleOutlined,
  PlusOutlined,
  ThunderboltOutlined,
} from '@ant-design/icons';
import { useTranslation } from 'react-i18next';
import { BUILTIN_JAR_PATH } from '../../../types/plugin';
import type { Plugin } from '../../../types/plugin';
import { getPluginDescription } from '../../plugins/utils';
import { CATEGORY_LABELS, getCategoryDesign, getPluginIcon } from '../utils';

const { Text } = Typography;

interface PluginSelectModalProps {
  plugins: Plugin[];
  loading: boolean;
  onSelect: (plugin: Plugin) => void;
  onClose: () => void;
}

const PluginSelectModal: React.FC<PluginSelectModalProps> = ({
  plugins,
  loading,
  onSelect,
  onClose,
}) => {
  const { t, i18n } = useTranslation();

  const pluginsByCategory = useMemo(() => {
    const grouped: Record<string, Plugin[]> = {};
    plugins.forEach((p) => {
      if (!grouped[p.category]) {
        grouped[p.category] = [];
      }
      grouped[p.category].push(p);
    });
    return grouped;
  }, [plugins]);

  return (
    <Modal
      title={
        <div style={{ display: 'flex', alignItems: 'center' }}>
          <div
            style={{
              width: 40,
              height: 40,
              borderRadius: 10,
              background: 'linear-gradient(135deg, #8b5cf6 0%, #7c3aed 100%)',
              display: 'flex',
              alignItems: 'center',
              justifyContent: 'center',
              marginRight: 12,
              color: '#fff',
            }}
          >
            <PlusOutlined />
          </div>
          <div>
            <div style={{ fontSize: 16, fontWeight: 600, color: 'var(--text-primary)' }}>
              {t('plugin_bindings.select_plugin')}
            </div>
            <div style={{ fontSize: 12, color: 'var(--text-muted)' }}>
              {t('plugin_bindings.select_plugin_desc')}
            </div>
          </div>
        </div>
      }
      open
      onCancel={onClose}
      footer={null}
      width={1060}
      className="console-picker plugin-select-modal"
      destroyOnHidden
    >
      <Spin spinning={loading}>
        {plugins.length === 0 ? (
          <Empty
            image={<ThunderboltOutlined style={{ fontSize: 48, color: 'var(--text-muted)' }} />}
            description={t('plugin_bindings.plugins_empty')}
          />
        ) : (
          Object.entries(pluginsByCategory).map(([category, categoryPlugins]) => {
            const design = getCategoryDesign(category);
            return (
              <div key={category} style={{ marginBottom: 24 }}>
                <Divider style={{ margin: '0 0 16px 0' }}>
                  <Tag
                    style={{
                      background: `${design.accentColor}20`,
                      border: `1px solid ${design.accentColor}40`,
                      color: design.accentColor,
                      borderRadius: 8,
                      padding: '4px 12px',
                      fontSize: 13,
                    }}
                  >
                    {category} / {CATEGORY_LABELS[category] || category}
                  </Tag>
                </Divider>
                <Row gutter={[16, 16]}>
                  {categoryPlugins.map((plugin) => {
                    const pluginDesign = getCategoryDesign(plugin.category);
                    const description = getPluginDescription(plugin, i18n.language);
                    return (
                      <Col xs={24} sm={12} lg={8} key={plugin.pluginId}>
                        <Card
                          hoverable
                          role="button"
                          tabIndex={0}
                          onKeyDown={(event) => {
                            if (event.key === 'Enter' || event.key === ' ') {
                              event.preventDefault();
                              onSelect(plugin);
                            }
                          }}
                          style={{
                            borderRadius: 16,
                            border: '1px solid var(--border-subtle)',
                            background: 'var(--bg-card)',
                            transition: 'all 0.3s cubic-bezier(0.4, 0, 0.2, 1)',
                            cursor: 'pointer',
                            overflow: 'hidden',
                          }}
                          styles={{ body: { padding: '24px 16px 16px' } }}
                          onClick={() => onSelect(plugin)}
                          className="plugin-card-hover"
                        >
                          {/* Plugin icon with gradient background + glow */}
                          <div
                            style={{
                              width: 56,
                              height: 56,
                              borderRadius: 14,
                              background: pluginDesign.gradient,
                              display: 'flex',
                              alignItems: 'center',
                              justifyContent: 'center',
                              margin: '0 auto 16px',
                              fontSize: 26,
                              color: '#fff',
                              boxShadow: `0 8px 24px ${pluginDesign.glowColor}`,
                              position: 'relative',
                            }}
                          >
                            {getPluginIcon(plugin)}
                            {plugin.jarPath === BUILTIN_JAR_PATH && (
                              <div
                                style={{
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
                                }}
                              >
                                <CheckCircleOutlined
                                  style={{ color: 'var(--primary-color)', fontSize: 10 }}
                                />
                              </div>
                            )}
                          </div>

                          <Text
                            strong
                            style={{
                              display: 'block',
                              marginBottom: 8,
                              textAlign: 'center',
                              color: 'var(--text-primary)',
                              fontSize: 14,
                            }}
                          >
                            {plugin.pluginName}
                          </Text>

                          <div
                            style={{
                              display: 'flex',
                              justifyContent: 'center',
                              gap: 6,
                              marginBottom: 8,
                            }}
                          >
                            <Tag
                              style={{
                                background: `${pluginDesign.accentColor}20`,
                                border: `1px solid ${pluginDesign.accentColor}40`,
                                color: pluginDesign.accentColor,
                                borderRadius: 6,
                                fontSize: 11,
                                padding: '2px 8px',
                              }}
                            >
                              {CATEGORY_LABELS[plugin.category] || plugin.category}
                            </Tag>
                            {plugin.jarPath !== BUILTIN_JAR_PATH && (
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

                          {description && (
                            <Tooltip title={description}>
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
                                {description}
                              </Text>
                            </Tooltip>
                          )}
                        </Card>
                      </Col>
                    );
                  })}
                </Row>
              </div>
            );
          })
        )}
      </Spin>
    </Modal>
  );
};

export default PluginSelectModal;
