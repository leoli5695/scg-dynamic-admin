/**
 * Create / edit binding modal — order field plus schema-driven config form.
 * Config values are schema-driven (GET /api/plugins/{pluginId}/schema);
 * the backend BindingRequest body is {pluginId?, config, order, instanceId?}.
 */
import React, { useState } from 'react';
import {
  Alert, Badge, Divider, Form, InputNumber, Radio, Select, Typography
} from 'antd';
import { ApiOutlined, EditOutlined, GlobalOutlined, SettingOutlined } from '@ant-design/icons';
import { useTranslation } from 'react-i18next';
import { FormModal } from '../../../components/ui/FormModal';
import type { Plugin, PluginBinding, PluginConfigSchema } from '../../../types/plugin';
import type { RouteResponse } from '../../../types/route';
import { GLOBAL_BINDING_ROUTE_ID } from '../../../types/plugin';
import ConfigFieldInput from './ConfigFieldInput';
import { getPluginIcon } from '../utils';

const { Text } = Typography;

export interface BindingFormPayload {
  order: number;
  config: Record<string, unknown>;
  /** create mode only */
  bindType?: 'GLOBAL' | 'ROUTE';
  routeId?: string;
}

interface BindingFormModalProps {
  mode: 'create' | 'edit';
  plugin: Plugin | null;
  schema: PluginConfigSchema;
  initialConfig: Record<string, unknown>;
  initialOrder: number;
  /** create mode: routes to pick from; edit mode: binding being edited. */
  routes?: RouteResponse[];
  binding?: PluginBinding & { routeName?: string };
  onSubmit: (payload: BindingFormPayload) => Promise<boolean>;
  onClose: () => void;
}

const BindingFormModal: React.FC<BindingFormModalProps> = ({
  mode, plugin, schema, initialConfig, initialOrder, routes, binding, onSubmit, onClose,
}) => {
  const { t } = useTranslation();
  const [form] = Form.useForm();
  const [configValues, setConfigValues] = useState<Record<string, unknown>>(initialConfig);
  const [bindType, setBindType] = useState<'GLOBAL' | 'ROUTE'>('GLOBAL');
  const [submitting, setSubmitting] = useState(false);

  const isEdit = mode === 'edit';

  const handleOk = async () => {
    try {
      const values = await form.validateFields();
      setSubmitting(true);
      const ok = await onSubmit({
        order: values.order ?? 0,
        config: configValues,
        ...(isEdit ? {} : { bindType, routeId: values.routeId }),
      });
      if (ok) {
        onClose();
      }
    } catch {
      // Validation errors are shown inline; nothing to do here.
    } finally {
      setSubmitting(false);
    }
  };

  const renderConfigForm = () => {
    if (!schema.fields || schema.fields.length === 0) {
      return (
        <Alert
          message={t('plugin_bindings.no_config_info')}
          description={t('plugin_bindings.no_config_desc')}
          type="info"
        />
      );
    }
    return (
      <div>
        <Divider plain>
          <SettingOutlined /> {t('plugin_bindings.plugin_config')}
        </Divider>
        {schema.fields.map((field) => (
          <Form.Item key={field.name} label={field.label} help={field.description}>
            <ConfigFieldInput
              field={field}
              value={configValues[field.name]}
              onChange={(value) => setConfigValues((prev) => ({ ...prev, [field.name]: value }))}
            />
          </Form.Item>
        ))}
      </div>
    );
  };

  const title = isEdit ? (
    <div style={{ display: 'flex', alignItems: 'center' }}>
      <EditOutlined style={{ marginRight: 12, color: '#8b5cf6' }} />
      <div>
        <div style={{ fontSize: 16, fontWeight: 600 }}>{t('plugin_bindings.edit_title')}</div>
        <div style={{ fontSize: 12, color: '#666' }}>
          {binding?.pluginName} - {binding?.routeId === GLOBAL_BINDING_ROUTE_ID ? t('plugin_bindings.global') : binding?.routeName}
        </div>
      </div>
    </div>
  ) : (
    <div style={{ display: 'flex', alignItems: 'center' }}>
      <div style={{
        width: 40,
        height: 40,
        borderRadius: '50%',
        background: `${plugin?.color || '#8b5cf6'}20`,
        display: 'flex',
        alignItems: 'center',
        justifyContent: 'center',
        marginRight: 12,
        color: plugin?.color || '#8b5cf6',
      }}
      >
        {plugin && getPluginIcon(plugin)}
      </div>
      <div>
        <div style={{ fontSize: 16, fontWeight: 600 }}>{plugin?.pluginName}</div>
        <div style={{ fontSize: 12, color: '#666' }}>{t('plugin_bindings.create_binding')}</div>
      </div>
    </div>
  );

  return (
    <FormModal
      title={title}
      open
      onClose={onClose}
      onOk={handleOk}
      confirmLoading={submitting}
      width={600}
    >
      {!isEdit && (
        <Alert
          message={t('plugin_bindings.bind_notice')}
          description={t('plugin_bindings.bind_notice_desc')}
          type="info"
          showIcon
          style={{ marginBottom: 16 }}
        />
      )}

      <Form form={form} layout="vertical" initialValues={{ order: initialOrder }}>
        {!isEdit && (
          <>
            <Form.Item label={t('plugin_bindings.binding_type')} required>
              <Radio.Group
                value={bindType}
                onChange={(e) => setBindType(e.target.value)}
                style={{ width: '100%' }}
              >
                <Radio.Button value="GLOBAL" style={{ width: '50%', textAlign: 'center' }}>
                  <GlobalOutlined style={{ marginRight: 8 }} />
                  {t('plugin_bindings.global_binding')}
                </Radio.Button>
                <Radio.Button value="ROUTE" style={{ width: '50%', textAlign: 'center' }}>
                  <ApiOutlined style={{ marginRight: 8 }} />
                  {t('plugin_bindings.route_binding')}
                </Radio.Button>
              </Radio.Group>
            </Form.Item>

            {bindType === 'ROUTE' && (
              <Form.Item
                name="routeId"
                label={t('plugin_bindings.select_route')}
                rules={[{ required: true, message: t('plugin_bindings.select_route_required') }]}
              >
                <Select
                  placeholder={t('plugin_bindings.select_route_placeholder')}
                  showSearch
                  optionFilterProp="children"
                  style={{ width: '100%' }}
                >
                  {(routes ?? []).map((route) => (
                    <Select.Option key={route.id} value={route.id}>
                      <Badge status={route.enabled ? 'success' : 'default'} style={{ marginRight: 8 }} />
                      {route.routeName || route.id}
                      <Text type="secondary" style={{ marginLeft: 8 }}>({route.uri})</Text>
                    </Select.Option>
                  ))}
                </Select>
              </Form.Item>
            )}
          </>
        )}

        <Form.Item
          name="order"
          label={t('plugin_bindings.execution_order')}
          help={isEdit ? undefined : t('plugin_bindings.execution_order_help')}
        >
          <InputNumber min={-1000} max={10000} style={{ width: '100%' }} />
        </Form.Item>

        {renderConfigForm()}
      </Form>
    </FormModal>
  );
};

export default BindingFormModal;
