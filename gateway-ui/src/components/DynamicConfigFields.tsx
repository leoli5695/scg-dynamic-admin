import React, { useState } from 'react';
import { Form, Input, InputNumber, Select, Switch, Row, Col, Card, Button, Space, Divider } from 'antd';
import { KeyOutlined, UserOutlined, PlusOutlined, DeleteOutlined } from '@ant-design/icons';
import type { StrategyTypeField, StrategyTypeSchema } from '../hooks/useStrategyTypes';

interface DynamicConfigFieldsProps {
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