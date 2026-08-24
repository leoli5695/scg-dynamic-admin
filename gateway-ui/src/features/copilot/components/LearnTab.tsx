import React, { useState } from 'react';
import {
  Alert, Button, Card, Divider, Input, Space, Tag, Typography  } from 'antd';
import { message } from '../../../utils/antdStatic';
import { BookOutlined } from '@ant-design/icons';
import { useTranslation } from 'react-i18next';
import { explainConcept } from '../../../api/copilot';
import { ApiError } from '../../../api/http';
import AiReportRenderer from '../../../components/ai/AiReportRenderer';
import '../../../styles/ai-report.css';

const { Title } = Typography;

interface LearnTabProps {
  instanceId: string;
  provider: string;
  model: string;
}

const LearnTab: React.FC<LearnTabProps> = ({ instanceId, provider, model }) => {
  const { t } = useTranslation();
  const [conceptName, setConceptName] = useState('');
  const [explainedConcept, setExplainedConcept] = useState('');
  const [conceptExplanation, setConceptExplanation] = useState('');
  const [conceptLoading, setConceptLoading] = useState(false);

  const commonConcepts = [
    t('copilot.concept.route_predicate'),
    t('copilot.concept.gateway_filter'),
    t('copilot.concept.circuit_breaker'),
    t('copilot.concept.rate_limiter'),
    t('copilot.concept.load_balancer'),
    t('copilot.concept.service_discovery'),
    t('copilot.concept.retry_policy'),
    t('copilot.concept.timeout_config'),
  ];

  const handleExplainConcept = async (concept?: string) => {
    const target = (concept ?? conceptName).trim();
    if (!target) {
      message.warning(t('copilot.concept_required'));
      return;
    }

    setConceptLoading(true);
    try {
      const response = await explainConcept({
        concept: target,
        instanceId: instanceId || undefined,
        provider: provider || undefined,
        model: model || undefined,
      });

      if (response.success) {
        setExplainedConcept(target);
        setConceptExplanation(response.explanation || '');
      } else {
        message.error(t('copilot.explain_failed'));
      }
    } catch (e) {
      message.error(e instanceof ApiError && e.message ? e.message : t('copilot.explain_error'));
    } finally {
      setConceptLoading(false);
    }
  };

  return (
    <div style={{ padding: '16px' }}>
      <Alert
        type="info"
        showIcon
        message={t('copilot.learn_title')}
        description={t('copilot.learn_desc')}
        style={{ marginBottom: '24px' }}
      />

      {/* Quick Concepts */}
      <Card
        title={t('copilot.common_concepts')}
        style={{ background: 'rgba(255,255,255,0.05)', marginBottom: '24px' }}
      >
        <Space wrap>
          {commonConcepts.map(concept => (
            <Tag
              key={concept}
              color="blue"
              style={{ cursor: 'pointer', margin: '4px' }}
              onClick={() => {
                setConceptName(concept);
                handleExplainConcept(concept);
              }}
            >
              {concept}
            </Tag>
          ))}
        </Space>
      </Card>

      {/* Custom Concept */}
      <Card
        title={t('copilot.custom_concept')}
        style={{ background: 'rgba(255,255,255,0.05)' }}
      >
        <Space.Compact style={{ width: '100%' }}>
          <Input
            value={conceptName}
            onChange={e => setConceptName(e.target.value)}
            placeholder={t('copilot.concept_placeholder')}
            style={{ background: 'rgba(0,0,0,0.2)', color: '#fff' }}
          />
          <Button
            type="primary"
            icon={<BookOutlined />}
            onClick={() => handleExplainConcept()}
            loading={conceptLoading}
          >
            {t('copilot.explain')}
          </Button>
        </Space.Compact>

        {conceptExplanation && (
          <div style={{ marginTop: '24px' }}>
            <Divider />
            <Title level={5} style={{ color: '#fff' }}>
              {explainedConcept}
            </Title>
            <div style={{ color: 'rgba(255,255,255,0.85)' }}>
              <AiReportRenderer content={conceptExplanation} />
            </div>
          </div>
        )}
      </Card>
    </div>
  );
};

export default LearnTab;
