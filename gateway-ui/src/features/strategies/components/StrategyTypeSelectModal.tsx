/**
 * Strategy type picker shown before the create form.
 * Splits creatable strategy types into two tabs - general gateway strategies and
 * AI-gateway strategies - and groups each tab's cards by category.
 * Excluded from manual creation:
 * - ACCESS_LOG: moved to instance-level configuration.
 * - AI_PROXY: owned by the AI route form, which auto-generates a companion AI_PROXY
 *   strategy from the route's bound AI services (see AiRouteProjector). Creating one
 *   manually here would fight that projection, so it is not offered.
 */
import React, { useMemo } from 'react';
import { Card, Col, Divider, Modal, Row, Spin, Tabs, Tag, Typography } from 'antd';
import { useTranslation } from 'react-i18next';
import type { StrategyTypeInfo } from '../../../types/strategy';
import { CATEGORY_LABELS } from '../utils';
import { getStrategyTypeIcon } from './icons';

const { Text } = Typography;

/** Strategy types not offered for manual creation (managed elsewhere). */
const NON_CREATABLE_TYPES = new Set(['ACCESS_LOG', 'AI_PROXY']);

/** Category bucket shown in the AI tab; everything else lands in the general tab. */
const AI_CATEGORY = 'ai';

interface StrategyTypeSelectModalProps {
  strategyTypes: StrategyTypeInfo[];
  /** Tab opened first; mirrors the page's category tab so AI-tab users land on AI types. */
  initialTab?: 'general' | 'ai';
  loading: boolean;
  onSelect: (type: StrategyTypeInfo) => void;
  onCancel: () => void;
}

const StrategyTypeSelectModal: React.FC<StrategyTypeSelectModalProps> = ({
  strategyTypes, initialTab = 'general', loading, onSelect, onCancel,
}) => {
  const { t } = useTranslation();

  const { generalGroups, aiGroups } = useMemo(() => {
    const grouped: Record<string, StrategyTypeInfo[]> = {};
    strategyTypes
      .filter((st) => !NON_CREATABLE_TYPES.has(st.typeCode))
      .forEach((st) => {
        if (!grouped[st.category]) grouped[st.category] = [];
        grouped[st.category].push(st);
      });
    const entries = Object.entries(grouped);
    return {
      generalGroups: entries.filter(([category]) => category !== AI_CATEGORY),
      aiGroups: entries.filter(([category]) => category === AI_CATEGORY),
    };
  }, [strategyTypes]);

  const renderGroups = (groups: Array<[string, StrategyTypeInfo[]]>) => (
    <>
      {groups.map(([category, types]) => (
        <div key={category} style={{ marginBottom: 24 }}>
          <Divider style={{ margin: '0 0 16px 0' }}>
            <Tag color="blue">{CATEGORY_LABELS[category] || category}</Tag>
          </Divider>
          <Row gutter={[16, 16]} justify="center">
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
    </>
  );

  const generalCount = generalGroups.reduce((n, [, types]) => n + types.length, 0);
  const aiCount = aiGroups.reduce((n, [, types]) => n + types.length, 0);

  return (
    <Modal
      title={
        <div className="modal-header-modern">
          <div className="modal-title-wrapper">
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
      styles={{ body: { maxHeight: '62vh', overflowY: 'auto' } }}
    >
      <Spin spinning={loading}>
        <Tabs
          centered
          defaultActiveKey={initialTab}
          items={[
            {
              key: 'general',
              label: `${t('strategy.tab_general', { defaultValue: '普通策略' })} (${generalCount})`,
              children: renderGroups(generalGroups),
            },
            {
              key: 'ai',
              label: `${t('strategy.tab_ai', { defaultValue: 'AI 策略' })} (${aiCount})`,
              children: renderGroups(aiGroups),
            },
          ]}
        />
      </Spin>
    </Modal>
  );
};

export default StrategyTypeSelectModal;
