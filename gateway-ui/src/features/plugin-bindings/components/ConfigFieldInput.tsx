/**
 * Controlled input for one plugin config schema field.
 */
import React from 'react';
import { Input, InputNumber, Select, Switch } from 'antd';
import type { PluginConfigField } from '../../../types/plugin';

interface ConfigFieldInputProps {
  field: PluginConfigField;
  value: unknown;
  onChange: (value: unknown) => void;
}

const ConfigFieldInput: React.FC<ConfigFieldInputProps> = ({ field, value, onChange }) => {
  switch (field.type) {
    case 'number':
      return (
        <InputNumber
          value={value as number}
          onChange={(v) => onChange(v)}
          min={field.min}
          max={field.max}
          style={{ width: '100%' }}
        />
      );
    case 'switch':
      return <Switch checked={Boolean(value)} onChange={(v) => onChange(v)} />;
    case 'select':
      return (
        <Select
          value={value as string}
          onChange={(v) => onChange(v)}
          options={field.options}
          style={{ width: '100%' }}
        />
      );
    case 'textarea':
      // Textarea keeps raw text; valid JSON is parsed so objects round-trip.
      return (
        <Input.TextArea
          value={typeof value === 'object' && value !== null ? JSON.stringify(value, null, 2) : String(value ?? '')}
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
      return <Input value={value as string} onChange={(e) => onChange(e.target.value)} />;
  }
};

export default ConfigFieldInput;
