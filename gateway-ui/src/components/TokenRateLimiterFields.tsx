import React from 'react';
import { Form, Input, InputNumber, Select, Switch, Row, Col, Card, Button, Space, Tooltip } from 'antd';
import { RobotOutlined, PlusOutlined, DeleteOutlined, PlayCircleOutlined, UserOutlined, SyncOutlined, KeyOutlined, InfoCircleOutlined, ApiOutlined, ThunderboltOutlined } from '@ant-design/icons';
import type { StrategyTypeField, StrategyTypeSchema } from '../hooks/useStrategyTypes';

interface TransformFieldsProps {
  schema: StrategyTypeSchema | undefined;
  form: any;
  t: (key: string, options?: any) => string;
}

/**
 * Token Rate Limiter Fields - AI Token quota configuration.
 * Supports tenant-level quotas, response format, and alert settings.
 */
export function TokenRateLimiterFields({ schema, form, t }: TransformFieldsProps) {
  const subSchemas = schema?.subSchemas || {};

  // Helper function to get column span for a field
  const getFieldColSpan = (field: StrategyTypeField): number => {
    return field.colSpan || (field.type === 'textarea' ? 24 : 12);
  };

  // Helper function to render a single field
  const renderField = (field: StrategyTypeField): React.ReactNode => {
    const label = field.label || field.name;
    const colSpan = getFieldColSpan(field);

    switch (field.type) {
      case 'number':
        return (
          <Col span={colSpan} key={field.name}>
            <Form.Item name={field.name} label={label} initialValue={field.default}>
              <InputNumber min={field.min} max={field.max} addonAfter={field.unit} style={{ width: '100%' }} />
            </Form.Item>
          </Col>
        );

      case 'text':
        return (
          <Col span={colSpan} key={field.name}>
            <Form.Item name={field.name} label={label} initialValue={field.default}>
              <Input placeholder={field.placeholder} />
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
                {field.options?.map((opt: any) => (
                  <Select.Option key={opt.value} value={opt.value}>
                    {opt.label || opt.value}
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
  const renderRuleField = (field: StrategyTypeField, name: number, restField: any): React.ReactNode => {
    const label = field.label || field.name;
    const colSpan = getFieldColSpan(field);

    switch (field.type) {
      case 'number':
        return (
          <Col span={colSpan} key={field.name}>
            <Form.Item {...restField} name={[name, field.name]} label={label}>
              <InputNumber min={field.min} max={field.max} addonAfter={field.unit} style={{ width: '100%' }} />
            </Form.Item>
          </Col>
        );

      case 'text':
        return (
          <Col span={colSpan} key={field.name}>
            <Form.Item {...restField} name={[name, field.name]} label={label}>
              <Input placeholder={field.placeholder} />
            </Form.Item>
          </Col>
        );

      case 'switch':
        return (
          <Col span={colSpan} key={field.name}>
            <Form.Item {...restField} name={[name, field.name]} label={label} valuePropName="checked">
              <Switch />
            </Form.Item>
          </Col>
        );

      case 'select':
        return (
          <Col span={colSpan} key={field.name}>
            <Form.Item {...restField} name={[name, field.name]} label={label}>
              <Select>
                {field.options?.map((opt: any) => (
                  <Select.Option key={opt.value} value={opt.value}>
                    {opt.label || opt.value}
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
              <span>{subSchemas.tenantQuotas.sectionLabel || '租户配额配置'}</span>
              {subSchemas.tenantQuotas.sectionDescription && (
                <Tooltip title={subSchemas.tenantQuotas.sectionDescription}>
                  <InfoCircleOutlined style={{ color: '#999' }} />
                </Tooltip>
              )}
            </Space>
          }
        >
          <Form.List name={['config', 'tenantQuotas']}>
            {(fields, { add, remove }) => (
              <>
                {fields.map(({ key, name, ...restField }) => (
                  <Card
                    key={key}
                    size="small"
                    type="inner"
                    style={{ marginBottom: 8, background: '#fafafa' }}
                    title={
                      <Space>
                        <span>{subSchemas.tenantQuotas.ruleLabel || '租户'} {name + 1}</span>
                        <Form.Item
                          {...restField}
                          name={[name, 'tenantId']}
                          style={{ marginBottom: 0, marginLeft: 8 }}
                        >
                          <Input placeholder="tenant-001" style={{ width: 150 }} addonBefore={<UserOutlined />} />
                        </Form.Item>
                      </Space>
                    }
                    extra={
                      <Button type="text" danger size="small" icon={<DeleteOutlined />} onClick={() => remove(name)} />
                    }
                  >
                    <Row gutter={16}>
                      {subSchemas.tenantQuotas.ruleFields
                        ?.filter((f: StrategyTypeField) => f.name !== 'tenantId')
                        .map((field: StrategyTypeField) => (
                          <React.Fragment key={field.name}>{renderRuleField(field, name, restField)}</React.Fragment>
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
                  {t('strategy.config.add_tenant_quota', { defaultValue: '添加租户配额' })}
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
              <span>{subSchemas.defaultQuotas.sectionLabel || '默认配额设置'}</span>
              {subSchemas.defaultQuotas.sectionDescription && (
                <Tooltip title={subSchemas.defaultQuotas.sectionDescription}>
                  <InfoCircleOutlined style={{ color: '#999' }} />
                </Tooltip>
              )}
            </Space>
          }
        >
          <Row gutter={16}>
            {subSchemas.defaultQuotas.fields.map((field: StrategyTypeField) => (
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
              <span>{subSchemas.responseFormat.sectionLabel || '响应格式配置'}</span>
              {subSchemas.responseFormat.sectionDescription && (
                <Tooltip title={subSchemas.responseFormat.sectionDescription}>
                  <InfoCircleOutlined style={{ color: '#999' }} />
                </Tooltip>
              )}
            </Space>
          }
        >
          <Row gutter={16}>
            {subSchemas.responseFormat.fields.map((field: StrategyTypeField) => (
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
              <span>{subSchemas.tenantIdExtraction.sectionLabel || '租户识别配置'}</span>
              {subSchemas.tenantIdExtraction.sectionDescription && (
                <Tooltip title={subSchemas.tenantIdExtraction.sectionDescription}>
                  <InfoCircleOutlined style={{ color: '#999' }} />
                </Tooltip>
              )}
            </Space>
          }
        >
          <Row gutter={16}>
            {subSchemas.tenantIdExtraction.fields.map((field: StrategyTypeField) => (
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
              <span>{subSchemas.alert.sectionLabel || '告警配置'}</span>
              {subSchemas.alert.sectionDescription && (
                <Tooltip title={subSchemas.alert.sectionDescription}>
                  <InfoCircleOutlined style={{ color: '#999' }} />
                </Tooltip>
              )}
            </Space>
          }
        >
          <Row gutter={16}>
            {subSchemas.alert.fields.map((field: StrategyTypeField) => (
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
              <span>{subSchemas.sseHandling.sectionLabel || 'SSE流处理配置'}</span>
              {subSchemas.sseHandling.sectionDescription && (
                <Tooltip title={subSchemas.sseHandling.sectionDescription}>
                  <InfoCircleOutlined style={{ color: '#999' }} />
                </Tooltip>
              )}
            </Space>
          }
        >
          <Row gutter={16}>
            {subSchemas.sseHandling.fields.map((field: StrategyTypeField) => (
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
              <span>{subSchemas.preDeduct.sectionLabel || '预扣与退款策略'}</span>
              {subSchemas.preDeduct.sectionDescription && (
                <Tooltip title={subSchemas.preDeduct.sectionDescription}>
                  <InfoCircleOutlined style={{ color: '#999' }} />
                </Tooltip>
              )}
            </Space>
          }
        >
          <Row gutter={16}>
            {subSchemas.preDeduct.fields.map((field: StrategyTypeField) => (
              <React.Fragment key={field.name}>{renderField(field)}</React.Fragment>
            ))}
          </Row>
        </Card>
      )}
    </>
  );
}