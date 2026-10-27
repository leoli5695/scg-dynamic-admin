/**
 * Controlled input for one plugin config schema field.
 */
import React from 'react';
import { Input, InputNumber, Select, Switch } from 'antd';
import type { PluginConfigField } from '../../../types/plugin';

interface ConfigFieldInputProps {
  id?: string;
  'aria-describedby'?: string;
  'aria-invalid'?: React.AriaAttributes['aria-invalid'];
  'aria-required'?: React.AriaAttributes['aria-required'];
  field: PluginConfigField;
  value?: unknown;
  onChange?: (value: unknown) => void;
}

const ConfigFieldInput: React.FC<ConfigFieldInputProps> = ({
  field,
  value,
  onChange = () => {},
  ...accessibility
}) => {
  switch (field.type) {
    case 'number':
      return (
        <InputNumber
          {...accessibility}
          value={value as number}
          onChange={(v) => onChange(v)}
          min={field.min}
          max={field.max}
          style={{ width: '100%' }}
        />
      );
    case 'switch':
      return <Switch {...accessibility} checked={Boolean(value)} onChange={(v) => onChange(v)} />;
    case 'select':
      return (
        <Select
          {...accessibility}
          value={value == null ? undefined : String(value)}
          onChange={(v) => onChange(v)}
          options={field.options}
          style={{ width: '100%' }}
        />
      );
    case 'textarea':
      // Textarea keeps raw text; valid JSON is parsed so objects round-trip.
      return (
        <Input.TextArea
          {...accessibility}
          value={
            typeof value === 'object' && value !== null
              ? JSON.stringify(value, null, 2)
              : String(value ?? '')
          }
          onChange={(e) => {
            try {
              onChange(JSON.parse(e.target.value));
            } catch {
              onChange(e.target.value);
            }
          }}
          rows={4}
          placeholder="Enter JSON configuration"
        />
      );
    default:
      return <Input {...accessibility} value={value == null ? '' : String(value)} onChange={(e) => onChange(e.target.value)} />;
  }
};

export default ConfigFieldInput;
