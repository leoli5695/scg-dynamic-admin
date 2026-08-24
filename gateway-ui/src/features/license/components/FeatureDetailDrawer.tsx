import React from 'react';
import { Alert, Divider, Drawer, List, Space, Tag, Typography } from 'antd';
import { CheckCircleOutlined, RocketOutlined } from '@ant-design/icons';
import { EDITION_CONFIG, type LicenseFeatureDef } from '../constants/featureCatalog';

const { Title, Paragraph, Text } = Typography;

interface FeatureDetailDrawerProps {
  feature: LicenseFeatureDef | null;
  onClose: () => void;
}

export const FeatureDetailDrawer: React.FC<FeatureDetailDrawerProps> = ({ feature, onClose }) => (
  <Drawer
    title={
      <Space>
        {feature?.icon}
        <span>{feature?.displayName}</span>
      </Space>
    }
    placement="right"
    width={480}
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
              {EDITION_CONFIG[feature.requiredEdition].label}
            </Tag>
            {feature.requiredEdition === 'BASIC' ? (
              <Tag color="success" style={{ backgroundColor: '#22c55e', color: '#fff' }}>免费使用</Tag>
            ) : feature.enabled ? (
              <Tag color="success">已授权</Tag>
            ) : feature.requiredEdition === 'ADVANCED' ? (
              <Tag color="processing" style={{ backgroundColor: '#3b82f6', color: '#fff' }}>需要高级版</Tag>
            ) : (
              <Tag color="purple" style={{ backgroundColor: '#7c3aed', color: '#fff' }}>需要企业版</Tag>
            )}
          </Space>
        </div>

        <Divider />

        {/* Description */}
        <div className="feature-detail-section">
          <Title level={5}>功能介绍</Title>
          <Paragraph>{feature.detailedDescription || feature.description}</Paragraph>
        </div>

        {/* Benefits */}
        {feature.benefits && feature.benefits.length > 0 && (
          <div className="feature-detail-section">
            <Title level={5}>核心优势</Title>
            <List
              size="small"
              dataSource={feature.benefits}
              renderItem={(item) => (
                <List.Item style={{ padding: '8px 0', border: 'none' }}>
                  <Space>
                    <CheckCircleOutlined style={{ color: '#22c55e' }} />
                    <Text>{item}</Text>
                  </Space>
                </List.Item>
              )}
            />
          </div>
        )}

        {/* Use Cases */}
        {feature.useCases && feature.useCases.length > 0 && (
          <div className="feature-detail-section">
            <Title level={5}>应用场景</Title>
            <List
              size="small"
              dataSource={feature.useCases}
              renderItem={(item) => (
                <List.Item style={{ padding: '8px 0', border: 'none' }}>
                  <Space>
                    <RocketOutlined style={{ color: '#3b82f6' }} />
                    <Text>{item}</Text>
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
            message="升级提示"
            description={`此功能需要 ${EDITION_CONFIG[feature.requiredEdition].label} 才可使用，请联系商务获取 License Code。`}
            showIcon
            style={{ marginTop: 16 }}
          />
        )}
      </div>
    )}
  </Drawer>
);
