import React from 'react';
import { Alert, Divider, Drawer, List, Space, Tag, Typography } from 'antd';
import { CheckCircleOutlined, RocketOutlined } from '@ant-design/icons';
import { EDITION_CONFIG, type LicenseFeatureDef } from '../constants/featureCatalog';
import { useTranslation } from 'react-i18next';
import { palette } from '../../../styles/palette';

const { Title, Paragraph, Text } = Typography;

interface FeatureDetailDrawerProps {
  feature: LicenseFeatureDef | null;
  onClose: () => void;
}

export const FeatureDetailDrawer: React.FC<FeatureDetailDrawerProps> = ({ feature, onClose }) => {
  const { t } = useTranslation();

  return (
    <Drawer
      title={
        <Space>
          {feature?.icon}
          <span>{feature ? t(feature.displayNameKey) : null}</span>
        </Space>
      }
      placement="right"
      size={480}
      open={!!feature}
      onClose={onClose}
      destroyOnHidden
    >
      {feature && (
        <div className="feature-detail-drawer">
          {/* Edition & Status */}
          <div className="feature-detail-header">
            <Space size="middle">
              <Tag color={EDITION_CONFIG[feature.requiredEdition].color}>
                {t(EDITION_CONFIG[feature.requiredEdition].labelKey)}
              </Tag>
              {feature.requiredEdition === 'BASIC' ? (
                <Tag color="success" style={{ backgroundColor: palette.successSolid, color: '#fff' }}>{t('licenseUI.free_to_use')}</Tag>
              ) : feature.enabled ? (
                <Tag color="success">{t('licenseUI.authorized')}</Tag>
              ) : feature.requiredEdition === 'ADVANCED' ? (
                <Tag color="processing" style={{ backgroundColor: palette.primary, color: '#fff' }}>{t('licenseUI.requires_advanced')}</Tag>
              ) : (
                <Tag color="purple" style={{ backgroundColor: palette.accentSolid, color: '#fff' }}>{t('licenseUI.requires_enterprise')}</Tag>
              )}
            </Space>
          </div>

          <Divider />

          {/* Description */}
          <div className="feature-detail-section">
            <Title level={5}>{t('licenseUI.feature_intro')}</Title>
            <Paragraph>
              {feature.detailedDescriptionKey
                ? t(feature.detailedDescriptionKey)
                : t(feature.descriptionKey)}
            </Paragraph>
          </div>

          {/* Benefits */}
          {feature.benefitKeys && feature.benefitKeys.length > 0 && (
            <div className="feature-detail-section">
              <Title level={5}>{t('licenseUI.key_benefits')}</Title>
              <List
                size="small"
                dataSource={feature.benefitKeys}
                renderItem={(item) => (
                  <List.Item style={{ padding: '8px 0', border: 'none' }}>
                    <Space>
                      <CheckCircleOutlined style={{ color: palette.success }} />
                      <Text>{t(item)}</Text>
                    </Space>
                  </List.Item>
                )}
              />
            </div>
          )}

          {/* Use Cases */}
          {feature.useCaseKeys && feature.useCaseKeys.length > 0 && (
            <div className="feature-detail-section">
              <Title level={5}>{t('licenseUI.use_cases')}</Title>
              <List
                size="small"
                dataSource={feature.useCaseKeys}
                renderItem={(item) => (
                  <List.Item style={{ padding: '8px 0', border: 'none' }}>
                    <Space>
                      <RocketOutlined style={{ color: palette.primary }} />
                      <Text>{t(item)}</Text>
                    </Space>
                  </List.Item>
                )}
              />
            </div>
          )}

          {/* Upgrade Hint */}
          {feature.requiredEdition !== 'BASIC' && !feature.enabled && (
            <Alert
              type="info"
              title={t('licenseUI.upgrade_hint_title')}
              description={t('licenseUI.upgrade_hint', {
                edition: t(EDITION_CONFIG[feature.requiredEdition].labelKey),
              })}
              showIcon
              style={{ marginTop: 16 }}
            />
          )}
        </div>
      )}
    </Drawer>
  );
};
