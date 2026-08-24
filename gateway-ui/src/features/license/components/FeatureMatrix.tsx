import React, { useCallback } from 'react';
import { Card, List, Tabs, Tag, Typography } from 'antd';
import { CheckCircleOutlined, CloseCircleOutlined, CrownOutlined } from '@ant-design/icons';
import { EDITION_CONFIG, type LicenseFeatureDef } from '../constants/featureCatalog';

const { Text } = Typography;

interface FeatureMatrixProps {
  features: LicenseFeatureDef[];
  onFeatureClick: (feature: LicenseFeatureDef) => void;
}

/** Feature authorization matrix grouped by edition. */
export const FeatureMatrix: React.FC<FeatureMatrixProps> = ({ features, onFeatureClick }) => {
  const basicFeatures = features.filter(f => f.requiredEdition === 'BASIC');
  const advancedFeatures = features.filter(f => f.requiredEdition === 'ADVANCED');
  const enterpriseFeatures = features.filter(f => f.requiredEdition === 'ENTERPRISE');

  // BASIC features are free (green), ADVANCED (blue), ENTERPRISE (purple)
  const renderFeatureItem = useCallback((feature: LicenseFeatureDef) => {
    const editionClass = feature.requiredEdition.toLowerCase();

    return (
      <List.Item key={feature.name}>
        <div
          className={`feature-item ${editionClass} ${feature.enabled ? 'enabled' : 'disabled'}`}
          onClick={() => onFeatureClick(feature)}
          style={{ cursor: 'pointer' }}
        >
          <div className="feature-icon">{feature.icon}</div>
          <div className="feature-content">
            <div className="feature-header">
              <Text strong>{feature.displayName}</Text>
              {feature.requiredEdition === 'BASIC' ? (
                <Tag color="success" icon={<CheckCircleOutlined />} style={{ backgroundColor: '#22c55e', color: '#fff' }}>
                  免费
                </Tag>
              ) : feature.enabled ? (
                <Tag color="success" icon={<CheckCircleOutlined />}>
                  已授权
                </Tag>
              ) : feature.requiredEdition === 'ADVANCED' ? (
                <Tag color="processing" icon={<CloseCircleOutlined />} style={{ backgroundColor: '#3b82f6', color: '#fff' }}>
                  高级版
                </Tag>
              ) : (
                <Tag color="purple" icon={<CloseCircleOutlined />} style={{ backgroundColor: '#7c3aed', color: '#fff' }}>
                  企业版
                </Tag>
              )}
            </div>
            <Text type="secondary" className="feature-desc">
              {feature.description}
            </Text>
          </div>
          <div className="feature-edition">
            <Tag color={EDITION_CONFIG[feature.requiredEdition].color}>
              {EDITION_CONFIG[feature.requiredEdition].label}
            </Tag>
          </div>
        </div>
      </List.Item>
    );
  }, [onFeatureClick]);

  return (
    <Card title="功能授权矩阵" style={{ marginTop: 16 }}>
      <Tabs
        items={[
          {
            key: 'basic',
            label: (
              <span>
                <CheckCircleOutlined />
                基础功能（免费）
              </span>
            ),
            children: (
              <List
                grid={{ gutter: 16, column: 2 }}
                dataSource={basicFeatures}
                renderItem={renderFeatureItem}
              />
            ),
          },
          {
            key: 'advanced',
            label: (
              <span>
                <CrownOutlined style={{ color: '#1890ff' }} />
                高级功能
              </span>
            ),
            children: (
              <List
                grid={{ gutter: 16, column: 2 }}
                dataSource={advancedFeatures}
                renderItem={renderFeatureItem}
              />
            ),
          },
          {
            key: 'enterprise',
            label: (
              <span>
                <CrownOutlined style={{ color: '#faad14' }} />
                企业功能
              </span>
            ),
            children: (
              <List
                grid={{ gutter: 16, column: 2 }}
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
