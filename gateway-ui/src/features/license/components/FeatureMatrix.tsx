import React, { useCallback } from 'react';
import { Card, List, Tabs, Tag, Typography } from 'antd';
import { CheckCircleOutlined, CloseCircleOutlined, CrownOutlined } from '@ant-design/icons';
import { EDITION_CONFIG, type LicenseFeatureDef } from '../constants/featureCatalog';
import { useTranslation } from 'react-i18next';
import { palette } from '../../../styles/palette';

const { Text } = Typography;

interface FeatureMatrixProps {
  features: LicenseFeatureDef[];
  onFeatureClick: (feature: LicenseFeatureDef) => void;
}

/** Feature authorization matrix grouped by edition. */
export const FeatureMatrix: React.FC<FeatureMatrixProps> = ({ features, onFeatureClick }) => {
  const { t } = useTranslation();
  const basicFeatures = features.filter((f) => f.requiredEdition === 'BASIC');
  const advancedFeatures = features.filter((f) => f.requiredEdition === 'ADVANCED');
  const enterpriseFeatures = features.filter((f) => f.requiredEdition === 'ENTERPRISE');

  // BASIC features are free (green), ADVANCED (blue), ENTERPRISE (purple)
  const renderFeatureItem = useCallback(
    (feature: LicenseFeatureDef) => {
      const editionClass = feature.requiredEdition.toLowerCase();

      return (
        <List.Item key={feature.name}>
          <div
            className={`feature-item ${editionClass} ${feature.enabled ? 'enabled' : 'disabled'}`}
            onClick={() => onFeatureClick(feature)}
            role="button"
            tabIndex={0}
            onKeyDown={(event) => {
              if (event.key === 'Enter' || event.key === ' ') {
                event.preventDefault();
                onFeatureClick(feature);
              }
            }}
            style={{ cursor: 'pointer' }}
          >
            <div className="feature-icon">{feature.icon}</div>
            <div className="feature-content">
              <div className="feature-header">
                <Text strong>{t(feature.displayNameKey)}</Text>
                {feature.requiredEdition === 'BASIC' ? (
                  <Tag
                    color="success"
                    icon={<CheckCircleOutlined />}
                    style={{ backgroundColor: palette.successSolid, color: '#fff' }}
                  >
                    {t('licenseUI.free')}
                  </Tag>
                ) : feature.enabled ? (
                  <Tag color="success" icon={<CheckCircleOutlined />}>
                    {t('licenseUI.authorized')}
                  </Tag>
                ) : feature.requiredEdition === 'ADVANCED' ? (
                  <Tag
                    color="processing"
                    icon={<CloseCircleOutlined />}
                    style={{ backgroundColor: palette.primary, color: '#fff' }}
                  >
                    {t('licenseUI.edition.ADVANCED.label')}
                  </Tag>
                ) : (
                  <Tag
                    color="purple"
                    icon={<CloseCircleOutlined />}
                    style={{ backgroundColor: palette.accentSolid, color: '#fff' }}
                  >
                    {t('licenseUI.edition.ENTERPRISE.label')}
                  </Tag>
                )}
              </div>
              <Text type="secondary" className="feature-desc">
                {t(feature.descriptionKey)}
              </Text>
            </div>
            <div className="feature-edition">
              <Tag color={EDITION_CONFIG[feature.requiredEdition].color}>
                {t(EDITION_CONFIG[feature.requiredEdition].labelKey)}
              </Tag>
            </div>
          </div>
        </List.Item>
      );
    },
    [onFeatureClick, t],
  );

  return (
    <Card
      className="license-feature-matrix"
      title={t('licenseUI.feature_matrix')}
      extra={
        <span className="license-feature-count">
          {t('licenseUI.feature_count', {
            enabled: features.filter((feature) => feature.enabled).length,
            total: features.length,
          })}
        </span>
      }
      style={{ marginTop: 20 }}
    >
      <Tabs
        items={[
          {
            key: 'basic',
            label: (
              <span>
                <CheckCircleOutlined />
                {t('licenseUI.basic_features_free')}
              </span>
            ),
            children: (
              <List
                grid={{ gutter: 14, xs: 1, sm: 1, md: 2, xl: 2, xxl: 3 }}
                dataSource={basicFeatures}
                renderItem={renderFeatureItem}
              />
            ),
          },
          {
            key: 'advanced',
            label: (
              <span>
                <CrownOutlined style={{ color: palette.primary }} />
                {t('licenseUI.advanced_features')}
              </span>
            ),
            children: (
              <List
                grid={{ gutter: 14, xs: 1, sm: 1, md: 2, xl: 2, xxl: 3 }}
                dataSource={advancedFeatures}
                renderItem={renderFeatureItem}
              />
            ),
          },
          {
            key: 'enterprise',
            label: (
              <span>
                <CrownOutlined style={{ color: palette.warning }} />
                {t('licenseUI.enterprise_features')}
              </span>
            ),
            children: (
              <List
                grid={{ gutter: 14, xs: 1, sm: 1, md: 2, xl: 2, xxl: 3 }}
                dataSource={enterpriseFeatures}
                renderItem={renderFeatureItem}
              />
            ),
          },
        ]}
      />
    </Card>
  );
};
