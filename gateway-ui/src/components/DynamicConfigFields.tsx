import React from 'react';
import { Form, Input, InputNumber, Select, Switch, Row, Col } from 'antd';
import { KeyOutlined, UserOutlined } from '@ant-design/icons';
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

export default DynamicConfigFields;