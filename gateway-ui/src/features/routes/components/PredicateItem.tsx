/**
 * Predicate form row — supports Path, Host, Method and Header predicates.
 */
import React from 'react';
import { Button, Form, Input, Select } from 'antd';
import type { FormInstance } from 'antd';
import { DeleteOutlined } from '@ant-design/icons';
import { useTranslation } from 'react-i18next';

interface PredicateItemProps {
  form: FormInstance;
  restField: Record<string, unknown>;
  name: number;
  onRemove: (index: number) => void;
}

const METHOD_OPTIONS = [
  { label: 'GET', value: 'GET' },
  { label: 'POST', value: 'POST' },
  { label: 'PUT', value: 'PUT' },
  { label: 'DELETE', value: 'DELETE' },
  { label: 'PATCH', value: 'PATCH' },
  { label: 'HEAD', value: 'HEAD' },
  { label: 'OPTIONS', value: 'OPTIONS' },
];

const PredicateItem: React.FC<PredicateItemProps> = ({
  form, restField, name, onRemove,
}) => {
  const { t } = useTranslation();
  const predicateType = Form.useWatch(['predicates', name, 'name'], form) || '';

  const placeholders: Record<string, string> = {
    Path: '/api/**',
    Host: '*.example.com',
    Header: 'X-Header:regex',
  };

  return (
    <div className="form-item-row">
      <Form.Item {...restField} name={[name, 'name']} noStyle>
        <Select
          placeholder={t('routes.predicate_type')}
          style={{
            width: 150,
            backgroundColor: 'rgba(0, 0, 0, 0.3)',
            border: '1px solid rgba(148, 163, 184, 0.15)',
          }}
          onChange={(value) => {
            form.setFieldValue(['predicates', name, 'args'], value === 'Method' ? {} : '');
          }}
          suffixIcon={<span style={{ color: '#94a3b8', fontSize: '12px' }}>▼</span>}
        >
          <Select.Option value="Path">Path</Select.Option>
          <Select.Option value="Host">Host</Select.Option>
          <Select.Option value="Method">Method</Select.Option>
          <Select.Option value="Header">Header</Select.Option>
        </Select>
      </Form.Item>

      {predicateType === 'Method' ? (
        <Form.Item
          {...restField}
          name={[name, 'args']}
          noStyle
          rules={[{ required: true, message: t('routes.method_required') }]}
        >
          <Select
            mode="multiple"
            placeholder={t('routes.select_methods')}
            style={{ width: 300 }}
            options={METHOD_OPTIONS}
          />
        </Form.Item>
      ) : (
        <Form.Item
          {...restField}
          name={[name, 'args']}
          noStyle
          rules={[{ required: true, message: t('routes.args_required') }]}
        >
          <Input
            placeholder={placeholders[predicateType] || 'value'}
            style={{ width: 300 }}
          />
        </Form.Item>
      )}

      <Form.Item noStyle>
        <Button
          type="text"
          danger
          icon={<DeleteOutlined />}
          onClick={() => onRemove(name)}
          style={{ marginLeft: 8 }}
        >
          {t('common.remove')}
        </Button>
      </Form.Item>
    </div>
  );
};

export default PredicateItem;
