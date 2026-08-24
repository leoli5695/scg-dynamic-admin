/**
 * One editable upstream-instance row inside the service create/edit forms.
 */
import React from 'react';
import {
  Button, Form, Input, Select
} from 'antd';
import { MinusCircleOutlined } from '@ant-design/icons';
import { useTranslation } from 'react-i18next';
import type { ServiceInstanceItem } from '../../../types/service';

interface InstanceRowProps {
  instance: ServiceInstanceItem;
  index: number;
  onChange: (index: number, patch: Partial<ServiceInstanceItem>) => void;
  onRemove: (index: number) => void;
  canRemove: boolean;
}

const InstanceRow: React.FC<InstanceRowProps> = ({
  instance, index, onChange, onRemove, canRemove,
}) => {
  const { t } = useTranslation();

  return (
    <div className="instance-row-modern">
      <div className="instance-row-header">
        <span className="instance-label">
          {t('services.instance')}
          {' '}
          {index + 1}
        </span>
        <Button
          type="text"
          danger
          size="small"
          icon={<MinusCircleOutlined />}
          onClick={() => onRemove(index)}
          disabled={!canRemove}
        />
      </div>
      <div className="instance-row-grid">
        <Form.Item label={t('services.ip_address')} className="instance-form-item">
          <Input
            placeholder="192.168.1.100"
            value={instance.ip}
            onChange={(e) => onChange(index, { ip: e.target.value })}
            className="instance-input-ip"
            size="large"
          />
        </Form.Item>
        <Form.Item label={t('services.port')} className="instance-form-item">
          <Input
            type="number"
            placeholder="8080"
            value={instance.port}
            onChange={(e) => onChange(index, { port: parseInt(e.target.value, 10) || 8080 })}
            className="instance-input-port"
            size="large"
          />
        </Form.Item>
        <Form.Item label={t('services.weight')} className="instance-form-item">
          <Input
            type="number"
            placeholder="1"
            value={instance.weight}
            onChange={(e) => onChange(index, { weight: parseInt(e.target.value, 10) || 1 })}
            className="instance-input-weight"
            size="large"
            min={1}
            max={100}
          />
        </Form.Item>
        <Form.Item label={t('services.status')} className="instance-form-item">
          <Select
            value={instance.enabled !== false ? 'enabled' : 'disabled'}
            onChange={(v) => onChange(index, { enabled: v === 'enabled' })}
            className="instance-input-status"
            size="large"
          >
            <Select.Option value="enabled">
              <span className="status-dot status-dot-success" />
              {t('common.enabled')}
            </Select.Option>
            <Select.Option value="disabled">
              <span className="status-dot status-dot-default" />
              {t('common.disabled')}
            </Select.Option>
          </Select>
        </Form.Item>
      </div>
    </div>
  );
};

export default InstanceRow;
