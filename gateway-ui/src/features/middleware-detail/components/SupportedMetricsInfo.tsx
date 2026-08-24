/**
 * Supported metrics description panel (backend supportedMetrics payload).
 */
import React from 'react';
import {
  Card, Col, Descriptions, Divider, Row, Space, Tag, Typography
} from 'antd';
import { InfoCircleOutlined } from '@ant-design/icons';
import type { SupportedMetricsInfo as SupportedMetricsInfoType } from '../../../types/middleware';
import { CATEGORY_CONFIG } from '../utils';

const { Text } = Typography;

interface SupportedMetricsInfoProps {
  info: SupportedMetricsInfoType;
}

const SupportedMetricsInfo: React.FC<SupportedMetricsInfoProps> = ({ info }) => {
  const { type, description, categories } = info;

  return (
    <Card title={<Space><InfoCircleOutlined />支持的指标说明</Space>} size="small">
      <Descriptions column={1}>
        <Descriptions.Item label="中间件类型">{type}</Descriptions.Item>
        <Descriptions.Item label="描述">{description}</Descriptions.Item>
      </Descriptions>
      <Divider style={{ margin: '12px 0' }} />
      <Row gutter={[8, 8]}>
        {Object.entries(categories).map(([cat, desc]) => {
          const catConfig = CATEGORY_CONFIG[cat] || CATEGORY_CONFIG.other;
          return (
            <Col key={cat} span={12}>
              <Space>
                <Tag color={catConfig.color} icon={catConfig.icon}>{catConfig.label}</Tag>
                <Text type="secondary">{desc}</Text>
              </Space>
            </Col>
          );
        })}
      </Row>
    </Card>
  );
};

export default SupportedMetricsInfo;
