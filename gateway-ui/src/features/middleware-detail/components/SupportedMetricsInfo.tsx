/**
 * Supported metrics description panel (backend supportedMetrics payload).
 */
import React from 'react';
import { useTranslation } from 'react-i18next';
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
  const { t } = useTranslation();
  const { type, description, categories } = info;

  return (
    <Card title={<Space><InfoCircleOutlined />{t('middleware.supported_metrics_title')}</Space>} size="small">
      <Descriptions column={1}>
        <Descriptions.Item label={t('middleware.middleware_type')}>{type}</Descriptions.Item>
        <Descriptions.Item label={t('common.description')}>{description}</Descriptions.Item>
      </Descriptions>
      <Divider style={{ margin: '12px 0' }} />
      <Row gutter={[8, 8]}>
        {Object.entries(categories).map(([cat, desc]) => {
          const catConfig = CATEGORY_CONFIG[cat] || CATEGORY_CONFIG.other;
          return (
            <Col key={cat} span={12}>
              <Space>
                <Tag color={catConfig.color} icon={catConfig.icon}>{t(catConfig.labelKey)}</Tag>
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
