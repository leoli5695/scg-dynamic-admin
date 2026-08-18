import React from 'react';
import { Form, Select, Input, Button } from 'antd';
import type { FormInstance } from 'antd';
import { DeleteOutlined } from '@ant-design/icons';
import type { TFunction } from 'i18next';

interface PredicateItemProps {
  form: FormInstance;
  restField: Record<string, unknown>;
  name: number;
  t: TFunction;
  onRemove: (index: number) => void;
}

/**
 * Predicate 表单项组件
 * 支持 Path、Host、Method、Header 四种断言类型
 */
const PredicateItem: React.FC<PredicateItemProps> = ({ form, restField, name, t, onRemove }) => {
  const predicateType = Form.useWatch(['predicates', name, 'name'], form) || '';

  const extractMethods = (args: Record<string, unknown> | string): string[] => {
    if (typeof args === 'string') return args.split(',').map((m: string) => m.trim());
    if (!args) return [];
    if (Array.isArray(args.methods)) return args.methods as string[];
    if (typeof args.methods === 'string') return args.methods.split(',').map((m: string) => m.trim());
    const methodKeys = Object.keys(args).filter(k => k.startsWith('methods.'));
    if (methodKeys.length > 0) {
      return methodKeys
        .sort((a, b) => parseInt(a.split('.')[1]) - parseInt(b.split('.')[1]))
        .map(k => args[k] as string);
    }
    return [];
  };

  const buildMethodArgs = (methods: string[]): Record<string, string> => {
    if (!methods || methods.length === 0) return {};
    const args: Record<string, string> = {};
    methods.forEach((m, i) => {
      args[`methods.${i}`] = m;
    });
    return args;
  };

  return (
    <div key={name} className="form-item-row">
      <Form.Item {...restField} name={[name, 'name']} noStyle>
        <Select
          placeholder={t('routes.predicate_type')}
          style={{
            width: 150,
            backgroundColor: 'rgba(0, 0, 0, 0.3)',
            border: '1px solid rgba(148, 163, 184, 0.15)'
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
            options={[
              { label: 'GET', value: 'GET' },
              { label: 'POST', value: 'POST' },
              { label: 'PUT', value: 'PUT' },
              { label: 'DELETE', value: 'DELETE' },
              { label: 'PATCH', value: 'PATCH' },
              { label: 'HEAD', value: 'HEAD' },
              { label: 'OPTIONS', value: 'OPTIONS' },
            ]}
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
            placeholder={predicateType === 'Path' ? '/api/**' : predicateType === 'Host' ? '*.example.com' : 'value'}
            style={{ width: 300 }}
          />
        </Form.Item>
      )}

      <Form.Item noStyle>
        <Button 
          type="text" 
          danger 
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
