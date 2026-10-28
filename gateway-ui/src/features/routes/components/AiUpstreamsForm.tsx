/**
 * AI route binding form — selects AI services (LLM providers) as weighted upstreams
 * and configures model-match predicates. Used by RouteFormModal when category === 'AI'.
 *
 * Field shape mirrors backend model/AiUpstream.java + model/AiModelPredicate.java
 * (themselves aligned with Higress AiUpstream / AiModelPredicate).
 */
import React, { useMemo } from 'react';
import {
  Button, Form, Input, InputNumber, Select,
} from 'antd';
import { DeleteOutlined, PlusOutlined, RobotOutlined } from '@ant-design/icons';
import { useTranslation } from 'react-i18next';
import type { FormInstance } from 'antd';
import type { ServiceDefinition } from '../../../types/service';

interface AiUpstreamsFormProps {
  form: FormInstance;
  /** Only AI-category services; an AI route may bind nothing else. */
  aiServices: ServiceDefinition[];
}

const AiUpstreamsForm: React.FC<AiUpstreamsFormProps> = ({ aiServices }) => {
  const { t } = useTranslation();

  const serviceOptions = useMemo(() => aiServices.map((s) => ({
    label: s.providerType ? `${s.name} (${s.providerType})` : s.name,
    value: s.name,
  })), [aiServices]);

  return (
    <>
      {/* AI service upstreams */}
      <Form.List name="aiUpstreams">
        {(fields, { add, remove }) => (
          <>
            {fields.map(({ key, name, ...restField }) => (
              <div key={key} style={{
                padding: '10px 12px', marginBottom: 8, borderRadius: 6,
                border: '1px solid rgba(148, 163, 184, 0.2)',
              }}
              >
                <div className="editor-rule-fields"
                >
                  <Form.Item
                    {...restField}
                    name={[name, 'provider']}
                    noStyle
                    rules={[{ required: true, message: t('routes.ai_upstream_required') }]}
                  >
                    <Select
                      placeholder={t('routes.ai_select_service')}
                      style={{ width: 300, flexShrink: 0 }}
                      size="middle"
                      showSearch
                      optionFilterProp="label"
                      options={serviceOptions}
                      notFoundContent={t('routes.ai_no_services')}
                    />
                  </Form.Item>
                  <Form.Item {...restField} name={[name, 'weight']} noStyle initialValue={100}>
                    <InputNumber min={1} max={100} size="middle" style={{ width: 100, flexShrink: 0 }} addonAfter="%" />
                  </Form.Item>
                  <Button
                    type="text"
                    danger
                    icon={<DeleteOutlined />}
                    style={{ flexShrink: 0, padding: '4px' }}
                    onClick={() => remove(name)}
                  />
                </div>

                {/* Per-upstream model mapping: requested model -> upstream model */}
                <div style={{ marginTop: 10 }}>
                  <div style={{ fontSize: 12, opacity: 0.75, marginBottom: 4 }} title={t('routes.ai_model_mapping_hint')}>
                    {t('routes.ai_model_mapping')}
                  </div>
                  <Form.List name={[name, 'modelMappingPairs']}>
                    {(mmFields, { add: addMm, remove: removeMm }) => (
                      <>
                        {mmFields.map(({ key: mmKey, name: mmName, ...mmRest }) => (
                          <div key={mmKey} style={{
                            display: 'flex', gap: 8, alignItems: 'center', marginBottom: 6, flexWrap: 'nowrap',
                          }}
                          >
                            <Form.Item
                              {...mmRest}
                              name={[mmName, 'key']}
                              noStyle
                              rules={[{ required: true, message: t('routes.ai_model_mapping_key_required') }]}
                            >
                              <Input placeholder={t('routes.ai_model_mapping_key')} size="small" style={{ flex: 1 }} />
                            </Form.Item>
                            <span style={{ opacity: 0.5, flexShrink: 0 }}>→</span>
                            <Form.Item
                              {...mmRest}
                              name={[mmName, 'value']}
                              noStyle
                              rules={[{ required: true, message: t('routes.ai_model_mapping_value_required') }]}
                            >
                              <Input placeholder={t('routes.ai_model_mapping_value')} size="small" style={{ flex: 1 }} />
                            </Form.Item>
                            <Button
                              type="text"
                              danger
                              size="small"
                              icon={<DeleteOutlined />}
                              style={{ flexShrink: 0, padding: '2px' }}
                              onClick={() => removeMm(mmName)}
                            />
                          </div>
                        ))}
                        <Button type="link" size="small" icon={<PlusOutlined />} onClick={() => addMm({})} style={{ padding: 0 }}>
                          {t('routes.ai_add_model_mapping')}
                        </Button>
                      </>
                    )}
                  </Form.List>
                </div>
              </div>
            ))}
            <Button type="dashed" onClick={() => add({ weight: 100 })} block icon={<PlusOutlined />}>
              {t('routes.ai_add_upstream')}
            </Button>
          </>
        )}
      </Form.List>

      {/* Model predicates */}
      <div style={{ marginTop: 16 }}>
        <div style={{ marginBottom: 8, fontWeight: 500 }}>
          <RobotOutlined style={{ marginRight: 6 }} />
          {t('routes.ai_model_predicates')}
        </div>
        <Form.List name="modelPredicates">
          {(fields, { add, remove }) => (
            <>
              {fields.map(({ key, name, ...restField }) => (
                <div key={key} style={{
                  display: 'flex', gap: 8, alignItems: 'center', marginBottom: 6, flexWrap: 'nowrap',
                }}
                >
                  <Form.Item {...restField} name={[name, 'matchType']} noStyle initialValue="PREFIX">
                    <Select style={{ width: 120, flexShrink: 0 }} size="middle">
                      <Select.Option value="PREFIX">{t('routes.ai_match_prefix')}</Select.Option>
                      <Select.Option value="EXACT">{t('routes.ai_match_exact')}</Select.Option>
                    </Select>
                  </Form.Item>
                  <Form.Item
                    {...restField}
                    name={[name, 'value']}
                    noStyle
                    rules={[{ required: true, message: t('routes.ai_model_value_required') }]}
                  >
                    <Input placeholder={t('routes.ai_model_value_placeholder')} style={{ flex: 1 }} size="middle" />
                  </Form.Item>
                  <Button
                    type="text"
                    danger
                    icon={<DeleteOutlined />}
                    style={{ flexShrink: 0, padding: '4px' }}
                    onClick={() => remove(name)}
                  />
                </div>
              ))}
              <Button type="dashed" onClick={() => add({ matchType: 'PREFIX' })} block icon={<PlusOutlined />}>
                {t('routes.ai_add_model_predicate')}
              </Button>
            </>
          )}
        </Form.List>
      </div>
    </>
  );
};

export default AiUpstreamsForm;
