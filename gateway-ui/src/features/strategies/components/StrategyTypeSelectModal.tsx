/**
 * Strategy type picker shown before the create form.
 * Groups enabled strategy types by category (ACCESS_LOG excluded —
 * access log moved to instance-level configuration).
 */
import React, { useMemo } from 'react';
import { Card, Col, Divider, Modal, Row, Spin, Tag, Typography } from 'antd';
import { PlusOutlined } from '@ant-design/icons';
import { useTranslation } from 'react-i18next';
import type { StrategyTypeInfo } from '../../../types/strategy';
import { CATEGORY_LABELS } from '../utils';
import { getStrategyTypeIcon } from './icons';

const { Text } = Typography;

interface StrategyTypeSelectModalProps {
  strategyTypes: StrategyTypeInfo[];
  loading: boolean;
  onSelect: (type: StrategyTypeInfo) => void;
  onCancel: () => void;
}

const StrategyTypeSelectModal: React.FC<StrategyTypeSelectModalProps> = ({
  strategyTypes, loading, onSelect, onCancel,
}) => {
  const { t } = useTranslation();

  const typesByCategory = useMemo(() => {
    const grouped: Record<string, StrategyTypeInfo[]> = {};
    strategyTypes
      .filter((st) => st.typeCode !== 'ACCESS_LOG')
      .forEach((st) => {
        if (!grouped[st.category]) grouped[st.category] = [];
        grouped[st.category].push(st);
      });
    return grouped;
  }, [strategyTypes]);

  return (
    <Modal
      title={
        <div className="modal-header-modern">
          <div className="modal-title-wrapper">
            <div className="modal-icon-wrapper">
              <PlusOutlined />
            </div>
            <div className="modal-title-text">
              <div className="modal-title">{t('strategy.select_type')}</div>
              <div className="modal-subtitle">{t('strategy.select_type_description')}</div>
            </div>
          </div>
        </div>
      }
      open
      onCancel={onCancel}
      footer={null}
      width={800}
      className="strategy-type-select-modal"
    >
      <Spin spinning={loading}>
        {Object.entries(typesByCategory).map(([category, types]) => (
          <div key={category} style={{ marginBottom: 24 }}>
            <Divider style={{ margin: '0 0 16px 0' }}>
              <Tag color="blue">{CATEGORY_LABELS[category] || category}</Tag>
            </Divider>
            <Row gutter={[16, 16]}>
              {types.map((type) => (
                <Col span={6} key={type.typeCode}>
                  <Card
                    hoverable
                    className="strategy-type-card"
                    onClick={() => onSelect(type)}
                    style={{
                      textAlign: 'center',
                      borderRadius: 12,
                      border: '1px solid #f0f0f0',
                      transition: 'all 0.3s',
                    }}
                    styles={{ body: { padding: '20px 12px' } }}
                  >
                    <div
                      style={{
                        width: 48,
                        height: 48,
                        borderRadius: '50%',
                        background: `${type.color}20`,
                        display: 'flex',
                        alignItems: 'center',
                        justifyContent: 'center',
                        margin: '0 auto 12px',
                        fontSize: 24,
                        color: type.color,
                      }}
                    >
                      {getStrategyTypeIcon(type.icon)}
                    </div>
                    <Text strong style={{ display: 'block', marginBottom: 4 }}>
                      {type.typeName}
                    </Text>
                    <Text type="secondary" style={{ fontSize: 12 }}>
                      {type.typeNameEn}
                    </Text>
                    {type.description && (
                      <Text type="secondary" style={{ fontSize: 11, display: 'block', marginTop: 8 }}>
                        {type.description.length > 30 ? `${type.description.substring(0, 30)}...` : type.description}
                      </Text>
                    )}
                  </Card>
                </Col>
              ))}
            </Row>
          </div>
        ))}
      </Spin>
    </Modal>
  );
};

export default StrategyTypeSelectModal;
