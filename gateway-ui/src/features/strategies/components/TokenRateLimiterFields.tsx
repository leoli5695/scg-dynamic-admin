/**
 * Token Rate Limiter Fields — AI Token quota configuration.
 * Migrated from src/components/TokenRateLimiterFields.tsx.
 *
 * Form values are flat (tenantQuotas lives at the top level) and
 * utils.buildStrategyConfig converts them to the backend shape aligned
 * with my-gateway model/TokenQuotaConfig.java.
 */
import React from 'react';
import { Form, Input, InputNumber, Select, Switch, Row, Col, Card, Button, Space, Tooltip } from 'antd';
import type { FormInstance } from 'antd';
import {
  RobotOutlined, PlusOutlined, DeleteOutlined, PlayCircleOutlined,
  UserOutlined, SyncOutlined, ApiOutlined, ThunderboltOutlined, InfoCircleOutlined,
} from '@ant-design/icons';
import type { StrategyTypeField, StrategyTypeSchema } from '../../../types/strategy';
import type { TFunc } from './DynamicConfigFields';
import { fieldLabel, fieldPlaceholder, fieldUnit, optionLabel, sectionText, sectionDesc, ruleText } from './schemaLabels';
import { palette } from '../../../styles/palette';

interface TokenRateLimiterFieldsProps {
  schema: StrategyTypeSchema | undefined;
  form: FormInstance;
  t: TFunc;
}

/**
 * Token Rate Limiter Fields - AI Token quota configuration.
 * Supports tenant-level quotas, response format, and alert settings.
 */
export function TokenRateLimiterFields({ schema, t }: TokenRateLimiterFieldsProps) {
  const subSchemas = schema?.subSchemas || {};

  // Helper function to get column span for a field
  const getFieldColSpan = (field: StrategyTypeField): number => {
    return field.colSpan || (field.type === 'textarea' ? 24 : 12);
  };

  // Helper function to render a single field
  const renderField = (field: StrategyTypeField): React.ReactNode => {
    const label = fieldLabel(field);
    const colSpan = getFieldColSpan(field);

    switch (field.type) {
      case 'number':
        return (
          <Col span={colSpan} key={field.name}>
            <Form.Item name={field.name} label={label} initialValue={field.default}>
              <InputNumber min={field.min} max={field.max} addonAfter={fieldUnit(field)} style={{ width: '100%' }} />
            </Form.Item>
          </Col>
        );

      case 'text':
        return (
          <Col span={colSpan} key={field.name}>
            <Form.Item name={field.name} label={label} initialValue={field.default}>
              <Input placeholder={fieldPlaceholder(field)} />
            </Form.Item>
          </Col>
        );

      case 'switch':
        return (
          <Col span={colSpan} key={field.name}>
            <Form.Item name={field.name} label={label} initialValue={field.default ?? false} valuePropName="checked">
              <Switch />
            </Form.Item>
          </Col>
        );

      case 'select':
        return (
          <Col span={colSpan} key={field.name}>
            <Form.Item name={field.name} label={label} initialValue={field.default}>
              <Select>
                {field.options?.map((opt) => (
                  <Select.Option key={opt.value} value={opt.value}>
                    {optionLabel(opt) || opt.value}
                  </Select.Option>
                ))}
              </Select>
            </Form.Item>
          </Col>
        );

      default:
        return null;
    }
  };

  // Helper function to render a field inside a Form.List rule
  const renderRuleField = (field: StrategyTypeField, name: number): React.ReactNode => {
    const label = fieldLabel(field);
    const colSpan = getFieldColSpan(field);

    switch (field.type) {
      case 'number':
        return (
          <Col span={colSpan} key={field.name}>
            <Form.Item name={[name, field.name]} label={label}>
              <InputNumber min={field.min} max={field.max} addonAfter={fieldUnit(field)} style={{ width: '100%' }} />
            </Form.Item>
          </Col>
        );

      case 'text':
        return (
          <Col span={colSpan} key={field.name}>
            <Form.Item name={[name, field.name]} label={label}>
              <Input placeholder={fieldPlaceholder(field)} />
            </Form.Item>
          </Col>
        );

      case 'switch':
        return (
          <Col span={colSpan} key={field.name}>
            <Form.Item name={[name, field.name]} label={label} valuePropName="checked">
              <Switch />
            </Form.Item>
          </Col>
        );

      case 'select':
        return (
          <Col span={colSpan} key={field.name}>
            <Form.Item name={[name, field.name]} label={label}>
              <Select>
                {field.options?.map((opt) => (
                  <Select.Option key={opt.value} value={opt.value}>
                    {optionLabel(opt) || opt.value}
                  </Select.Option>
                ))}
              </Select>
            </Form.Item>
          </Col>
        );

      default:
        return null;
    }
  };

  return (
    <>
      {/* Tenant Quotas Section - Multi-rule pattern */}
      {subSchemas.tenantQuotas?.multiRule && (
        <Card
          size="small"
          style={{ marginBottom: 16 }}
          title={
            <Space>
              <RobotOutlined />
              <span>{sectionText(subSchemas.tenantQuotas) || t('strategy.config.tenant_quotas')}</span>
              {sectionDesc(subSchemas.tenantQuotas) && (
                <Tooltip title={sectionDesc(subSchemas.tenantQuotas)}>
                  <InfoCircleOutlined style={{ color: palette.textMuted }} />
                </Tooltip>
              )}
            </Space>
          }
        >
          <Form.List name="tenantQuotas">
            {(fields, { add, remove }) => (
              <>
                {fields.map(({ key, name }) => (
                  <Card
                    key={key}
                    size="small"
                    type="inner"
                    style={{ marginBottom: 8, background: palette.bgElevated }}
                    title={
                      <Space>
                        <span>{ruleText(subSchemas.tenantQuotas) || t('strategy.config.tenant')} {name + 1}</span>
                        <Form.Item
                          name={[name, 'tenantId']}
                          style={{ marginBottom: 0, marginLeft: 8 }}
                        >
                          <Input placeholder={t('strategy.config.tenant_id_placeholder')} style={{ width: 150 }} addonBefore={<UserOutlined />} />
                        </Form.Item>
                      </Space>
                    }
                    extra={
                      <Button type="text" danger size="small" icon={<DeleteOutlined />} onClick={() => remove(name)} />
                    }
                  >
                    <Row gutter={16}>
                      {subSchemas.tenantQuotas.ruleFields
                        ?.filter((f) => f.name !== 'tenantId')
                        .map((field) => (
                          <React.Fragment key={field.name}>{renderRuleField(field, name)}</React.Fragment>
                        ))}
                    </Row>
                  </Card>
                ))}
                <Button
                  type="dashed"
                  block
                  icon={<PlusOutlined />}
                  onClick={() =>
                    add({
                      tenantId: '',
                      monthlyQuota: 1000000,
                      dailyQuota: 50000,
                      burstQuota: 0,
                      quotaPeriod: 'BOTH',
                    })
                  }
                >
                  {t('strategy.config.add_tenant_quota')}
                </Button>
              </>
            )}
          </Form.List>
        </Card>
      )}

      {/* Default Quotas Section */}
      {subSchemas.defaultQuotas?.fields && (
        <Card
          size="small"
          style={{ marginBottom: 16 }}
          title={
            <Space>
              <span>{sectionText(subSchemas.defaultQuotas) || t('strategy.config.default_quotas')}</span>
              {sectionDesc(subSchemas.defaultQuotas) && (
                <Tooltip title={sectionDesc(subSchemas.defaultQuotas)}>
                  <InfoCircleOutlined style={{ color: palette.textMuted }} />
                </Tooltip>
              )}
            </Space>
          }
        >
          <Row gutter={16}>
            {subSchemas.defaultQuotas.fields.map((field) => (
              <React.Fragment key={field.name}>{renderField(field)}</React.Fragment>
            ))}
          </Row>
        </Card>
      )}

      {/* Response Format Section */}
      {subSchemas.responseFormat?.fields && (
        <Card
          size="small"
          style={{ marginBottom: 16 }}
          title={
            <Space>
              <PlayCircleOutlined />
              <span>{sectionText(subSchemas.responseFormat) || t('strategy.config.response_format')}</span>
              {sectionDesc(subSchemas.responseFormat) && (
                <Tooltip title={sectionDesc(subSchemas.responseFormat)}>
                  <InfoCircleOutlined style={{ color: palette.textMuted }} />
                </Tooltip>
              )}
            </Space>
          }
        >
          <Row gutter={16}>
            {subSchemas.responseFormat.fields.map((field) => (
              <React.Fragment key={field.name}>{renderField(field)}</React.Fragment>
            ))}
          </Row>
        </Card>
      )}

      {/* Tenant ID Extraction Section */}
      {subSchemas.tenantIdExtraction?.fields && (
        <Card
          size="small"
          style={{ marginBottom: 16 }}
          title={
            <Space>
              <UserOutlined />
              <span>{sectionText(subSchemas.tenantIdExtraction) || t('strategy.config.tenant_id_extraction')}</span>
              {sectionDesc(subSchemas.tenantIdExtraction) && (
                <Tooltip title={sectionDesc(subSchemas.tenantIdExtraction)}>
                  <InfoCircleOutlined style={{ color: palette.textMuted }} />
                </Tooltip>
              )}
            </Space>
          }
        >
          <Row gutter={16}>
            {subSchemas.tenantIdExtraction.fields.map((field) => (
              <React.Fragment key={field.name}>{renderField(field)}</React.Fragment>
            ))}
          </Row>
        </Card>
      )}

      {/* Alert Configuration Section */}
      {subSchemas.alert?.fields && (
        <Card
          size="small"
          style={{ marginBottom: 16 }}
          title={
            <Space>
              <SyncOutlined />
              <span>{sectionText(subSchemas.alert) || t('strategy.config.alert')}</span>
              {sectionDesc(subSchemas.alert) && (
                <Tooltip title={sectionDesc(subSchemas.alert)}>
                  <InfoCircleOutlined style={{ color: palette.textMuted }} />
                </Tooltip>
              )}
            </Space>
          }
        >
          <Row gutter={16}>
            {subSchemas.alert.fields.map((field) => (
              <React.Fragment key={field.name}>{renderField(field)}</React.Fragment>
            ))}
          </Row>
        </Card>
      )}

      {/* SSE Stream Handling Section */}
      {subSchemas.sseHandling?.fields && (
        <Card
          size="small"
          style={{ marginBottom: 16 }}
          title={
            <Space>
              <ApiOutlined />
              <span>{sectionText(subSchemas.sseHandling) || t('strategy.config.sse_handling')}</span>
              {sectionDesc(subSchemas.sseHandling) && (
                <Tooltip title={sectionDesc(subSchemas.sseHandling)}>
                  <InfoCircleOutlined style={{ color: palette.textMuted }} />
                </Tooltip>
              )}
            </Space>
          }
        >
          <Row gutter={16}>
            {subSchemas.sseHandling.fields.map((field) => (
              <React.Fragment key={field.name}>{renderField(field)}</React.Fragment>
            ))}
          </Row>
        </Card>
      )}

      {/* Pre-deduct Strategy Section */}
      {subSchemas.preDeduct?.fields && (
        <Card
          size="small"
          style={{ marginBottom: 16 }}
          title={
            <Space>
              <ThunderboltOutlined />
              <span>{sectionText(subSchemas.preDeduct) || t('strategy.config.pre_deduct')}</span>
              {sectionDesc(subSchemas.preDeduct) && (
                <Tooltip title={sectionDesc(subSchemas.preDeduct)}>
                  <InfoCircleOutlined style={{ color: palette.textMuted }} />
                </Tooltip>
              )}
            </Space>
          }
        >
          <Row gutter={16}>
            {subSchemas.preDeduct.fields.map((field) => (
              <React.Fragment key={field.name}>{renderField(field)}</React.Fragment>
            ))}
          </Row>
        </Card>
      )}

      {/* Window Token Rate Limit Section (Higress ai-token-ratelimit) */}
      {subSchemas.windowLimit?.fields && (
        <Card
          size="small"
          style={{ marginBottom: 16 }}
          title={
            <Space>
              <ThunderboltOutlined />
              <span>{sectionText(subSchemas.windowLimit) || t('strategy.config.window_limit')}</span>
              {sectionDesc(subSchemas.windowLimit) && (
                <Tooltip title={sectionDesc(subSchemas.windowLimit)}>
                  <InfoCircleOutlined style={{ color: palette.textMuted }} />
                </Tooltip>
              )}
            </Space>
          }
        >
          <Row gutter={16}>
            {subSchemas.windowLimit.fields.map((field) => (
              <React.Fragment key={field.name}>{renderField(field)}</React.Fragment>
            ))}
          </Row>
        </Card>
      )}
    </>
  );
}
