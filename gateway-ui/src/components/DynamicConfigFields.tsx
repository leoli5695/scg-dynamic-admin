import React, { useState } from 'react';
import { Form, Input, InputNumber, Select, Switch, Row, Col, Card, Button, Space, Divider, Collapse } from 'antd';
import { KeyOutlined, UserOutlined, PlusOutlined, DeleteOutlined, SyncOutlined, PlayCircleOutlined } from '@ant-design/icons';
import type { StrategyTypeField, StrategyTypeSchema, StrategyTypeSubSchema } from '../hooks/useStrategyTypes';

interface DynamicConfigFieldsProps {
  schema: StrategyTypeSchema | undefined;
  form: any;
  t: (key: string, options?: any) => string;
}

interface TransformFieldsProps {
  schema: StrategyTypeSchema | undefined;
  form: any;
  t: (key: string, options?: any) => string;
}

/**
 * Dynamically render form fields based on config schema.
 */
export function DynamicConfigFields({ schema, form, t }: DynamicConfigFieldsProps) {
  if (!schema || !schema.fields) {
    return null;
  }

  return (
    <Row gutter={16}>
      {schema.fields.map((field) => renderField(field, form, t))}
    </Row>
  );
}

/**
 * Render a single field based on its type.
 */
function renderField(field: StrategyTypeField, form: any, t: (key: string, options?: any) => string): React.ReactNode {
  const label = field.label || field.labelEn || field.name;
  const colSpan = getFieldColSpan(field);

  switch (field.type) {
    case 'number':
      return (
        <Col span={colSpan} key={field.name}>
          <Form.Item
            name={field.name}
            label={label}
            initialValue={field.default}
          >
            <InputNumber
              min={field.min}
              max={field.max}
              addonAfter={field.unit}
              style={{ width: '100%' }}
            />
          </Form.Item>
        </Col>
      );

    case 'text':
      return (
        <Col span={colSpan} key={field.name}>
          <Form.Item
            name={field.name}
            label={label}
            initialValue={field.default}
          >
            <Input placeholder={field.placeholder} />
          </Form.Item>
        </Col>
      );

    case 'password':
      return (
        <Col span={colSpan} key={field.name}>
          <Form.Item
            name={field.name}
            label={label}
            initialValue={field.default}
          >
            <Input.Password placeholder={field.placeholder || `your-${field.name}`} />
          </Form.Item>
        </Col>
      );

    case 'textarea':
      return (
        <Col span={24} key={field.name}>
          <Form.Item
            name={field.name}
            label={label}
            initialValue={field.default}
          >
            <Input.TextArea rows={2} placeholder={field.placeholder} />
          </Form.Item>
        </Col>
      );

    case 'select':
      return (
        <Col span={colSpan} key={field.name}>
          <Form.Item
            name={field.name}
            label={label}
            initialValue={field.default}
          >
            <Select>
              {field.options?.map((opt) => (
                <Select.Option key={opt.value} value={opt.value}>
                  {opt.label}
                </Select.Option>
              ))}
            </Select>
          </Form.Item>
        </Col>
      );

    case 'switch':
      return (
        <Col span={colSpan} key={field.name}>
          <Form.Item
            name={field.name}
            label={label}
            valuePropName="checked"
            initialValue={field.default ?? false}
          >
            <Switch />
          </Form.Item>
        </Col>
      );

    case 'object':
      return (
        <Col span={24} key={field.name}>
          <Form.Item
            name={field.name}
            label={label}
          >
            <Input.TextArea rows={3} placeholder={`JSON object for ${label}`} />
          </Form.Item>
        </Col>
      );

    case 'array':
      return (
        <Col span={24} key={field.name}>
          <Form.Item
            name={field.name}
            label={label}
          >
            <Input.TextArea rows={3} placeholder={`JSON array for ${label}`} />
          </Form.Item>
        </Col>
      );

    default:
      return (
        <Col span={colSpan} key={field.name}>
          <Form.Item
            name={field.name}
            label={label}
            initialValue={field.default}
          >
            <Input />
          </Form.Item>
        </Col>
      );
  }
}

/**
 * Determine column span based on field type.
 */
function getFieldColSpan(field: StrategyTypeField): number {
  if (field.type === 'textarea' || field.type === 'object' || field.type === 'array') {
    return 24;
  }
  if (field.type === 'switch') {
    return 6;
  }
  return 12;
}

/**
 * Render AUTH sub-schema fields based on authType.
 */
export function AuthSubSchemaFields({ schema, form, t }: DynamicConfigFieldsProps) {
  const authType = form.getFieldValue('authType') || 'JWT';
  const subSchema = schema?.subSchemas?.[authType];

  if (!subSchema || !subSchema.fields) {
    return null;
  }

  return (
    <Row gutter={16}>
      {subSchema.fields.map((field) => {
        // Add icons for specific fields
        if (field.name === 'basicUsername') {
          return (
            <Col span={12} key={field.name}>
              <Form.Item name={field.name} label={field.label || field.name}>
                <Input prefix={<UserOutlined />} placeholder={`your-${field.name}`} />
              </Form.Item>
            </Col>
          );
        }
        if (field.name === 'secretKey' && authType !== 'HMAC') {
          return (
            <Col span={12} key={field.name}>
              <Form.Item name={field.name} label={field.label || field.name}>
                <Input.Password placeholder="your-secret-key" />
              </Form.Item>
            </Col>
          );
        }
        return renderField(field, form, t);
      })}
    </Row>
  );
}

/**
 * Get default values for a dimension type from its subSchema.
 */
function getDimensionDefaults(dimensionType: string, subSchemas: Record<string, any>): Record<string, any> {
  const dimSchema = subSchemas[dimensionType];
  if (!dimSchema || !dimSchema.fields) {
    return { type: dimensionType };
  }
  
  const defaults: Record<string, any> = { type: dimensionType };
  dimSchema.fields.forEach((field: StrategyTypeField) => {
    if (field.default !== undefined) {
      defaults[field.name] = field.default;
    }
  });
  
  return defaults;
}

/**
 * Multi-dimensional rate limiter fields with dynamic dimension cards.
 * User selects dimension type, adds one by one, each shows specific config.
 */
export function MultiDimRateLimiterFields({ schema, form, t }: DynamicConfigFieldsProps) {
  const dimensionOptions = schema?.dimensionOptions || [];
  const subSchemas = schema?.subSchemas || {};
  const dimensionLabel = schema?.dimensionLabel || '维度';

  // Handler for adding a new dimension with defaults
  const handleAddDimension = (add: (value: any) => void) => {
    // Add with IP dimension defaults
    const defaults = getDimensionDefaults('IP', subSchemas);
    add(defaults);
  };

  return (
    <>
      {/* Base fields like rejectStrategy */}
      {schema?.fields && (
        <Row gutter={16}>
          {schema.fields.map((field) => renderField(field, form, t))}
        </Row>
      )}
      
      <Divider>{t('strategy.config.dimensions', { defaultValue: '限流维度配置' })}</Divider>
      
      {/* Dynamic dimension cards */}
      <Form.List name="dimensions">
        {(fields, { add, remove }) => (
          <>
            {fields.map(({ key, name, ...restField }) => (
              <Card
                key={key}
                size="small"
                style={{ marginBottom: 16 }}
                title={
                  <Form.Item
                    {...restField}
                    name={[name, 'type']}
                    label={dimensionLabel}
                    style={{ marginBottom: 0 }}
                  >
                    <Select 
                      style={{ width: 180 }}
                      placement="bottomLeft"
                      getPopupContainer={() => document.body}
                      dropdownStyle={{ minWidth: 220, zIndex: 9999 }}
                      listHeight={200}
                      onChange={(newType) => {
                        // When dimension type changes, update with new defaults
                        const newDefaults = getDimensionDefaults(newType, subSchemas);
                        const currentValues = form.getFieldValue(['dimensions', name]) as Record<string, any> || {};
                        const oldType = currentValues.type;
                        const oldDefaults = getDimensionDefaults(oldType, subSchemas);
                        
                        // Merge new defaults, but preserve user-modified values
                        const mergedValues: Record<string, any> = { ...newDefaults, type: newType };
                        // If user modified values (different from old defaults), preserve them
                        if (currentValues.qps !== undefined && currentValues.qps !== oldDefaults.qps) {
                          mergedValues.qps = currentValues.qps;
                        }
                        if (currentValues.burstCapacity !== undefined && currentValues.burstCapacity !== oldDefaults.burstCapacity) {
                          mergedValues.burstCapacity = currentValues.burstCapacity;
                        }
                        form.setFieldValue(['dimensions', name], mergedValues);
                      }}
                    >
                      {dimensionOptions.map((opt: { value: string; label: string }) => (
                        <Select.Option key={opt.value} value={opt.value}>
                          {opt.label}
                        </Select.Option>
                      ))}
                    </Select>
                  </Form.Item>
                }
                extra={
                  <Button
                    type="text"
                    danger
                    icon={<DeleteOutlined />}
                    onClick={() => remove(name)}
                  />
                }
              >
                <Form.Item noStyle shouldUpdate>
                  {({ getFieldValue }) => {
                    const dimensionType = getFieldValue(['dimensions', name, 'type']);
                    const dimSchema = subSchemas[dimensionType];
                    if (!dimSchema || !dimSchema.fields) return null;
                    return (
                      <Row gutter={16}>
                        {dimSchema.fields.map((field: StrategyTypeField) => (
                          <React.Fragment key={field.name}>
                            {renderDimensionField(field, name, restField, t)}
                          </React.Fragment>
                        ))}
                      </Row>
                    );
                  }}
                </Form.Item>
              </Card>
            ))}
            
            <Button
              type="dashed"
              onClick={() => handleAddDimension(add)}
              block
              icon={<PlusOutlined />}
            >
              {t('strategy.config.add_dimension', { defaultValue: '添加限流维度' })}
            </Button>
          </>
        )}
      </Form.List>
    </>
  );
}

/**
 * Render a field inside a dimension card (using Form.List path).
 * Note: initialValue is NOT set here because it conflicts with setFieldsValue when editing.
 * Default values should be passed when adding a new dimension via Form.List's add() method.
 * IMPORTANT: Use relative path [name, field.name] instead of absolute path for Form.List fields.
 */
function renderDimensionField(
  field: StrategyTypeField,
  name: number,
  restField: any,
  t: (key: string, options?: any) => string
): React.ReactNode {
  const label = field.label || field.name;
  const colSpan = getFieldColSpan(field);

  switch (field.type) {
    case 'number':
      return (
        <Col span={colSpan} key={field.name}>
          <Form.Item
            {...restField}
            name={[name, field.name]}
            label={label}
          >
            <InputNumber
              min={field.min}
              max={field.max}
              style={{ width: '100%' }}
            />
          </Form.Item>
        </Col>
      );

    case 'text':
      return (
        <Col span={colSpan} key={field.name}>
          <Form.Item
            {...restField}
            name={[name, field.name]}
            label={label}
          >
            <Input placeholder={field.placeholder} />
          </Form.Item>
        </Col>
      );

    case 'select':
      return (
        <Col span={colSpan} key={field.name}>
          <Form.Item
            {...restField}
            name={[name, field.name]}
            label={label}
          >
            <Select>
              {field.options?.map((opt) => (
                <Select.Option key={opt.value} value={opt.value}>
                  {opt.label}
                </Select.Option>
              ))}
            </Select>
          </Form.Item>
        </Col>
      );

    case 'switch':
      return (
        <Col span={colSpan} key={field.name}>
          <Form.Item
            {...restField}
            name={[name, field.name]}
            label={label}
            valuePropName="checked"
          >
            <Switch />
          </Form.Item>
        </Col>
      );

    default:
      return (
        <Col span={colSpan} key={field.name}>
          <Form.Item
            {...restField}
            name={[name, field.name]}
            label={label}
          >
            <Input />
          </Form.Item>
        </Col>
      );
  }
}

export default DynamicConfigFields;

/**
 * Get default values for a rule from its ruleFields.
 */
function getRuleDefaults(ruleFields: StrategyTypeField[]): Record<string, any> {
  const defaults: Record<string, any> = {};
  ruleFields.forEach((field) => {
    if (field.default !== undefined) {
      defaults[field.name] = field.default;
    }
  });
  return defaults;
}

/**
 * Render a field inside a rule card (using Form.List path).
 */
function renderRuleField(
  field: StrategyTypeField,
  name: number,
  restField: any,
  t: (key: string, options?: any) => string,
  hidden?: boolean
): React.ReactNode {
  if (hidden) return null;

  const label = field.label || field.name;
  const colSpan = getFieldColSpan(field);

  switch (field.type) {
    case 'number':
      return (
        <Col span={colSpan} key={field.name}>
          <Form.Item
            {...restField}
            name={[name, field.name]}
            label={label}
          >
            <InputNumber
              min={field.min}
              max={field.max}
              addonAfter={field.unit}
              style={{ width: '100%' }}
            />
          </Form.Item>
        </Col>
      );

    case 'text':
      return (
        <Col span={colSpan} key={field.name}>
          <Form.Item
            {...restField}
            name={[name, field.name]}
            label={label}
          >
            <Input placeholder={field.placeholder} />
          </Form.Item>
        </Col>
      );

    case 'select':
      return (
        <Col span={colSpan} key={field.name}>
          <Form.Item
            {...restField}
            name={[name, field.name]}
            label={label}
          >
            <Select style={{ width: '100%' }}>
              {field.options?.map((opt) => (
                <Select.Option key={opt.value} value={opt.value}>
                  {opt.label}
                </Select.Option>
              ))}
            </Select>
          </Form.Item>
        </Col>
      );

    case 'switch':
      return (
        <Col span={colSpan} key={field.name}>
          <Form.Item
            {...restField}
            name={[name, field.name]}
            label={label}
            valuePropName="checked"
          >
            <Switch />
          </Form.Item>
        </Col>
      );

    default:
      return (
        <Col span={colSpan} key={field.name}>
          <Form.Item
            {...restField}
            name={[name, field.name]}
            label={label}
          >
            <Input placeholder={field.placeholder} />
          </Form.Item>
        </Col>
      );
  }
}

/**
 * Request Transform fields with multiple configuration sections.
 * Each section (protocolTransform, fieldMapping, dataMasking) has its own card.
 */
export function RequestTransformFields({ schema, form, t }: TransformFieldsProps) {
  const subSchemas = (schema?.subSchemas || {}) as Record<string, StrategyTypeSubSchema>;

  return (
    <>
      {/* Base fields: maxBodySize, validateAfterTransform */}
      {schema?.fields && (
        <Row gutter={16}>
          {schema.fields.map((field) => renderField(field, form, t))}
        </Row>
      )}

      {/* Protocol Transform Section */}
      {subSchemas.protocolTransform && (
        <Card
          size="small"
          style={{ marginBottom: 16 }}
          title={
            <Space>
              <SyncOutlined />
              <span>{subSchemas.protocolTransform.sectionLabel || '协议转换配置'}</span>
            </Space>
          }
        >
          <Form.Item name={['protocolTransform', 'enabled']} valuePropName="checked" initialValue={false} noStyle>
            <Switch style={{ marginBottom: 16 }} />
          </Form.Item>
          <Form.Item noStyle shouldUpdate>
            {({ getFieldValue }) => {
              const enabled = getFieldValue(['protocolTransform', 'enabled']);
              if (!enabled) return <span style={{ color: '#999' }}>启用后可配置协议转换选项</span>;
              return (
                <Row gutter={16}>
                  {subSchemas.protocolTransform.fields
                    ?.filter((f: StrategyTypeField) => f.name !== 'enabled')
                    .map((field: StrategyTypeField) => (
                      <React.Fragment key={field.name}>
                        {renderNestedField(field, 'protocolTransform', form, t)}
                      </React.Fragment>
                    ))}
                </Row>
              );
            }}
          </Form.Item>
        </Card>
      )}

      {/* Field Mapping Section - dynamic rules */}
      {subSchemas.fieldMapping?.multiRule && (
        <Card
          size="small"
          style={{ marginBottom: 16 }}
          title={
            <Space>
              <SyncOutlined />
              <span>{subSchemas.fieldMapping.sectionLabel || '字段映射配置'}</span>
            </Space>
          }
        >
          <Form.List name="fieldMappingRules">
            {(fields, { add, remove }) => (
              <>
                {fields.map(({ key, name, ...restField }) => (
                  <Card
                    key={key}
                    size="small"
                    type="inner"
                    style={{ marginBottom: 8, background: '#fafafa' }}
                    title={`${subSchemas.fieldMapping.ruleLabel || '映射规则'} ${name + 1}`}
                    extra={
                      <Button
                        type="text"
                        danger
                        size="small"
                        icon={<DeleteOutlined />}
                        onClick={() => remove(name)}
                      />
                    }
                  >
                    <Row gutter={16}>
                      {subSchemas.fieldMapping.ruleFields?.map((field: StrategyTypeField) =>
                        renderRuleField(field, name, restField, t)
                      )}
                    </Row>
                  </Card>
                ))}
                <Button
                  type="dashed"
                  block
                  icon={<PlusOutlined />}
                  onClick={() => add(getRuleDefaults(subSchemas.fieldMapping.ruleFields || []))}
                >
                  {t('strategy.config.add_mapping_rule', { defaultValue: '添加映射规则' })}
                </Button>
              </>
            )}
          </Form.List>
        </Card>
      )}

      {/* Data Masking Section - dynamic rules */}
      {subSchemas.dataMasking?.multiRule && (
        <Card
          size="small"
          style={{ marginBottom: 16 }}
          title={
            <Space>
              <SyncOutlined />
              <span>{subSchemas.dataMasking.sectionLabel || '数据脱敏配置'}</span>
            </Space>
          }
        >
          <Form.List name="dataMaskingRules">
            {(fields, { add, remove }) => (
              <>
                {fields.map(({ key, name, ...restField }) => (
                  <Card
                    key={key}
                    size="small"
                    type="inner"
                    style={{ marginBottom: 8, background: '#fafafa' }}
                    title={`${subSchemas.dataMasking.ruleLabel || '脱敏规则'} ${name + 1}`}
                    extra={
                      <Button
                        type="text"
                        danger
                        size="small"
                        icon={<DeleteOutlined />}
                        onClick={() => remove(name)}
                      />
                    }
                  >
                    <Form.Item noStyle shouldUpdate>
                      {({ getFieldValue }) => {
                        const maskType = getFieldValue(['dataMaskingRules', name, 'maskType']);
                        return (
                          <Row gutter={16}>
                            {subSchemas.dataMasking.ruleFields?.map((field: StrategyTypeField) => {
                              // Hide PARTIAL-specific fields when maskType is not PARTIAL
                              if ((field.name === 'keepLength' || field.name === 'keepPosition') && maskType !== 'PARTIAL') {
                                return null;
                              }
                              // Hide pattern field when maskType is not CUSTOM
                              if (field.name === 'pattern' && maskType !== 'CUSTOM') {
                                return null;
                              }
                              return renderRuleField(field, name, restField, t);
                            })}
                          </Row>
                        );
                      }}
                    </Form.Item>
                  </Card>
                ))}
                <Button
                  type="dashed"
                  block
                  icon={<PlusOutlined />}
                  onClick={() => add(getRuleDefaults(subSchemas.dataMasking.ruleFields || []))}
                >
                  {t('strategy.config.add_masking_rule', { defaultValue: '添加脱敏规则' })}
                </Button>
              </>
            )}
          </Form.List>
        </Card>
      )}
    </>
  );
}

/**
 * Response Transform fields with multiple configuration sections.
 * Similar to RequestTransformFields but with response-specific defaults.
 */
export function ResponseTransformFields({ schema, form, t }: TransformFieldsProps) {
  const subSchemas = (schema?.subSchemas || {}) as Record<string, StrategyTypeSubSchema>;

  return (
    <>
      {/* Base fields: maxBodySize, errorHandling */}
      {schema?.fields && (
        <Row gutter={16}>
          {schema.fields.map((field) => renderField(field, form, t))}
        </Row>
      )}

      {/* Protocol Transform Section */}
      {subSchemas.protocolTransform && (
        <Card
          size="small"
          style={{ marginBottom: 16 }}
          title={
            <Space>
              <SyncOutlined />
              <span>{subSchemas.protocolTransform.sectionLabel || '协议转换配置'}</span>
            </Space>
          }
        >
          <Form.Item name={['protocolTransform', 'enabled']} valuePropName="checked" initialValue={false} noStyle>
            <Switch style={{ marginBottom: 16 }} />
          </Form.Item>
          <Form.Item noStyle shouldUpdate>
            {({ getFieldValue }) => {
              const enabled = getFieldValue(['protocolTransform', 'enabled']);
              if (!enabled) return <span style={{ color: '#999' }}>启用后可配置协议转换选项</span>;
              return (
                <Row gutter={16}>
                  {subSchemas.protocolTransform.fields
                    ?.filter((f: StrategyTypeField) => f.name !== 'enabled')
                    .map((field: StrategyTypeField) => (
                      <React.Fragment key={field.name}>
                        {renderNestedField(field, 'protocolTransform', form, t)}
                      </React.Fragment>
                    ))}
                </Row>
              );
            }}
          </Form.Item>
        </Card>
      )}

      {/* Field Mapping Section - dynamic rules */}
      {subSchemas.fieldMapping?.multiRule && (
        <Card
          size="small"
          style={{ marginBottom: 16 }}
          title={
            <Space>
              <SyncOutlined />
              <span>{subSchemas.fieldMapping.sectionLabel || '字段映射配置'}</span>
            </Space>
          }
        >
          <Form.List name="fieldMappingRules">
            {(fields, { add, remove }) => (
              <>
                {fields.map(({ key, name, ...restField }) => (
                  <Card
                    key={key}
                    size="small"
                    type="inner"
                    style={{ marginBottom: 8, background: '#fafafa' }}
                    title={`${subSchemas.fieldMapping.ruleLabel || '映射规则'} ${name + 1}`}
                    extra={
                      <Button
                        type="text"
                        danger
                        size="small"
                        icon={<DeleteOutlined />}
                        onClick={() => remove(name)}
                      />
                    }
                  >
                    <Row gutter={16}>
                      {subSchemas.fieldMapping.ruleFields?.map((field: StrategyTypeField) =>
                        renderRuleField(field, name, restField, t)
                      )}
                    </Row>
                  </Card>
                ))}
                <Button
                  type="dashed"
                  block
                  icon={<PlusOutlined />}
                  onClick={() => add(getRuleDefaults(subSchemas.fieldMapping.ruleFields || []))}
                >
                  {t('strategy.config.add_mapping_rule', { defaultValue: '添加映射规则' })}
                </Button>
              </>
            )}
          </Form.List>
        </Card>
      )}

      {/* Data Masking Section - dynamic rules */}
      {subSchemas.dataMasking?.multiRule && (
        <Card
          size="small"
          style={{ marginBottom: 16 }}
          title={
            <Space>
              <SyncOutlined />
              <span>{subSchemas.dataMasking.sectionLabel || '数据脱敏配置'}</span>
            </Space>
          }
        >
          <Form.List name="dataMaskingRules">
            {(fields, { add, remove }) => (
              <>
                {fields.map(({ key, name, ...restField }) => (
                  <Card
                    key={key}
                    size="small"
                    type="inner"
                    style={{ marginBottom: 8, background: '#fafafa' }}
                    title={`${subSchemas.dataMasking.ruleLabel || '脱敏规则'} ${name + 1}`}
                    extra={
                      <Button
                        type="text"
                        danger
                        size="small"
                        icon={<DeleteOutlined />}
                        onClick={() => remove(name)}
                      />
                    }
                  >
                    <Form.Item noStyle shouldUpdate>
                      {({ getFieldValue }) => {
                        const maskType = getFieldValue(['dataMaskingRules', name, 'maskType']);
                        return (
                          <Row gutter={16}>
                            {subSchemas.dataMasking.ruleFields?.map((field: StrategyTypeField) => {
                              // Hide PARTIAL-specific fields when maskType is not PARTIAL
                              if ((field.name === 'keepLength' || field.name === 'keepPosition') && maskType !== 'PARTIAL') {
                                return null;
                              }
                              // Hide pattern field when maskType is not CUSTOM
                              if (field.name === 'pattern' && maskType !== 'CUSTOM') {
                                return null;
                              }
                              return renderRuleField(field, name, restField, t);
                            })}
                          </Row>
                        );
                      }}
                    </Form.Item>
                  </Card>
                ))}
                <Button
                  type="dashed"
                  block
                  icon={<PlusOutlined />}
                  onClick={() => add(getRuleDefaults(subSchemas.dataMasking.ruleFields || []))}
                >
                  {t('strategy.config.add_masking_rule', { defaultValue: '添加脱敏规则' })}
                </Button>
              </>
            )}
          </Form.List>
        </Card>
      )}
    </>
  );
}

/**
 * Render a nested field (for protocolTransform section).
 */
function renderNestedField(
  field: StrategyTypeField,
  parentPath: string,
  form: any,
  t: (key: string, options?: any) => string
): React.ReactNode {
  const label = field.label || field.name;
  const colSpan = getFieldColSpan(field);

  switch (field.type) {
    case 'number':
      return (
        <Col span={colSpan} key={field.name}>
          <Form.Item
            name={[parentPath, field.name]}
            label={label}
            initialValue={field.default}
          >
            <InputNumber
              min={field.min}
              max={field.max}
              addonAfter={field.unit}
              style={{ width: '100%' }}
            />
          </Form.Item>
        </Col>
      );

    case 'text':
      return (
        <Col span={colSpan} key={field.name}>
          <Form.Item
            name={[parentPath, field.name]}
            label={label}
            initialValue={field.default}
          >
            <Input placeholder={field.placeholder} />
          </Form.Item>
        </Col>
      );

    case 'select':
      return (
        <Col span={colSpan} key={field.name}>
          <Form.Item
            name={[parentPath, field.name]}
            label={label}
            initialValue={field.default}
          >
            <Select style={{ width: '100%' }}>
              {field.options?.map((opt) => (
                <Select.Option key={opt.value} value={opt.value}>
                  {opt.label}
                </Select.Option>
              ))}
            </Select>
          </Form.Item>
        </Col>
      );

    case 'switch':
      return (
        <Col span={colSpan} key={field.name}>
          <Form.Item
            name={[parentPath, field.name]}
            label={label}
            valuePropName="checked"
            initialValue={field.default ?? false}
          >
            <Switch />
          </Form.Item>
        </Col>
      );

    default:
      return (
        <Col span={colSpan} key={field.name}>
          <Form.Item
            name={[parentPath, field.name]}
            label={label}
            initialValue={field.default}
          >
            <Input placeholder={field.placeholder} />
          </Form.Item>
        </Col>
      );
  }
}

/**
 * Mock Response fields with multiple configuration sections.
 * Supports STATIC, DYNAMIC, and TEMPLATE mock modes.
 */
export function MockResponseFields({ schema, form, t }: TransformFieldsProps) {
  const subSchemas = (schema?.subSchemas || {}) as Record<string, StrategyTypeSubSchema>;

  return (
    <>
      {/* Base fields: mockMode */}
      {schema?.fields && (
        <Row gutter={16}>
          {schema.fields.map((field) => renderField(field, form, t))}
        </Row>
      )}

      <Form.Item noStyle shouldUpdate>
        {({ getFieldValue }) => {
          const mockMode = getFieldValue('mockMode') || 'STATIC';

          return (
            <>
              {/* Static Mock Section - show when mode is STATIC */}
              {mockMode === 'STATIC' && subSchemas.staticMock && (
                <Card
                  size="small"
                  style={{ marginBottom: 16 }}
                  title={
                    <Space>
                      <PlayCircleOutlined />
                      <span>{subSchemas.staticMock.sectionLabel || '静态Mock配置'}</span>
                    </Space>
                  }
                >
                  <Row gutter={16}>
                    {subSchemas.staticMock.fields?.map((field: StrategyTypeField) => (
                      <React.Fragment key={field.name}>
                        {renderNestedField(field, 'staticMock', form, t)}
                      </React.Fragment>
                    ))}
                  </Row>
                </Card>
              )}

              {/* Dynamic Mock Section - show when mode is DYNAMIC */}
              {mockMode === 'DYNAMIC' && subSchemas.dynamicMock?.multiRule && (
                <Card
                  size="small"
                  style={{ marginBottom: 16 }}
                  title={
                    <Space>
                      <PlayCircleOutlined />
                      <span>{subSchemas.dynamicMock.sectionLabel || '动态Mock配置'}</span>
                    </Space>
                  }
                >
                  <Form.List name="dynamicMockConditions">
                    {(fields, { add, remove }) => (
                      <>
                        {fields.map(({ key, name, ...restField }) => (
                          <Card
                            key={key}
                            size="small"
                            type="inner"
                            style={{ marginBottom: 8, background: '#fafafa' }}
                            title={`${subSchemas.dynamicMock.ruleLabel || '条件规则'} ${name + 1}`}
                            extra={
                              <Button
                                type="text"
                                danger
                                size="small"
                                icon={<DeleteOutlined />}
                                onClick={() => remove(name)}
                              />
                            }
                          >
                            <Row gutter={16}>
                              {subSchemas.dynamicMock.ruleFields?.map((field: StrategyTypeField) =>
                                renderRuleField(field, name, restField, t)
                              )}
                            </Row>
                          </Card>
                        ))}
                        <Button
                          type="dashed"
                          block
                          icon={<PlusOutlined />}
                          onClick={() => add(getRuleDefaults(subSchemas.dynamicMock.ruleFields || []))}
                        >
                          {t('strategy.config.add_condition', { defaultValue: '添加条件规则' })}
                        </Button>
                      </>
                    )}
                  </Form.List>
                </Card>
              )}

              {/* Template Mock Section - show when mode is TEMPLATE */}
              {mockMode === 'TEMPLATE' && subSchemas.templateMock && (
                <Card
                  size="small"
                  style={{ marginBottom: 16 }}
                  title={
                    <Space>
                      <PlayCircleOutlined />
                      <span>{subSchemas.templateMock.sectionLabel || '模板Mock配置'}</span>
                    </Space>
                  }
                >
                  <Row gutter={16}>
                    {subSchemas.templateMock.fields?.map((field: StrategyTypeField) => (
                      <React.Fragment key={field.name}>
                        {renderNestedField(field, 'templateMock', form, t)}
                      </React.Fragment>
                    ))}
                  </Row>
                </Card>
              )}

              {/* Delay Simulation Section - show for all modes */}
              {subSchemas.delay && (
                <Card
                  size="small"
                  style={{ marginBottom: 16 }}
                  title={
                    <Space>
                      <SyncOutlined />
                      <span>{subSchemas.delay.sectionLabel || '延迟模拟配置'}</span>
                    </Space>
                  }
                >
                  <Form.Item name={['delay', 'enabled']} valuePropName="checked" initialValue={false} noStyle>
                    <Switch style={{ marginBottom: 16 }} />
                  </Form.Item>
                  <Form.Item noStyle shouldUpdate>
                    {({ getFieldValue }) => {
                      const enabled = getFieldValue(['delay', 'enabled']);
                      if (!enabled) return <span style={{ color: '#999' }}>启用后可配置延迟模拟</span>;
                      return (
                        <Row gutter={16}>
                          {subSchemas.delay.fields
                            ?.filter((f: StrategyTypeField) => f.name !== 'enabled')
                            .map((field: StrategyTypeField) => (
                              <React.Fragment key={field.name}>
                                {renderNestedField(field, 'delay', form, t)}
                              </React.Fragment>
                            ))}
                        </Row>
                      );
                    }}
                  </Form.Item>
                </Card>
              )}

              {/* Error Simulation Section - show for all modes */}
              {subSchemas.errorSimulation && (
                <Card
                  size="small"
                  style={{ marginBottom: 16 }}
                  title={
                    <Space>
                      <SyncOutlined />
                      <span>{subSchemas.errorSimulation.sectionLabel || '错误模拟配置'}</span>
                    </Space>
                  }
                >
                  <Form.Item name={['errorSimulation', 'enabled']} valuePropName="checked" initialValue={false} noStyle>
                    <Switch style={{ marginBottom: 16 }} />
                  </Form.Item>
                  <Form.Item noStyle shouldUpdate>
                    {({ getFieldValue }) => {
                      const enabled = getFieldValue(['errorSimulation', 'enabled']);
                      if (!enabled) return <span style={{ color: '#999' }}>启用后可配置错误模拟</span>;
                      return (
                        <Row gutter={16}>
                          {subSchemas.errorSimulation.fields
                            ?.filter((f: StrategyTypeField) => f.name !== 'enabled')
                            .map((field: StrategyTypeField) => (
                              <React.Fragment key={field.name}>
                                {renderNestedField(field, 'errorSimulation', form, t)}
                              </React.Fragment>
                            ))}
                        </Row>
                      );
                    }}
                  </Form.Item>
                </Card>
              )}
            </>
          );
        }}
      </Form.Item>
    </>
  );
}