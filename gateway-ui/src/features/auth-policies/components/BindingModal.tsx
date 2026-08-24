/**
 * Route binding modal for one policy — lists current bindings (with
 * enable/disable + unbind) and adds new ones.
 * Endpoints: POST /api/auth/bindings, DELETE /api/auth/bindings/{id},
 * POST /api/auth/bindings/{id}/enable|disable.
 */
import React, { useState } from 'react';
import { Button, Form, InputNumber, Modal, Select, Switch, Tag, Typography } from 'antd';
import { LinkOutlined, PlusOutlined } from '@ant-design/icons';
import { useTranslation } from 'react-i18next';
import type { AuthPolicyDefinition, RouteAuthBinding } from '../../../types/authPolicy';
import type { RouteResponse } from '../../../types/route';

const { Text } = Typography;

interface BindingModalProps {
  policy: AuthPolicyDefinition;
  bindings: RouteAuthBinding[];
  routes: RouteResponse[];
  onClose: () => void;
  /** Returns true when every requested binding succeeded (modal closes). */
  onAddBindings: (routeIds: string[], priority: number) => Promise<boolean>;
  onUnbind: (binding: RouteAuthBinding) => void;
  onToggleBinding: (binding: RouteAuthBinding) => void;
}

const BindingModal: React.FC<BindingModalProps> = ({
  policy, bindings, routes, onClose, onAddBindings, onUnbind, onToggleBinding,
}) => {
  const { t } = useTranslation();
  const [form] = Form.useForm<{ routeIds: string[]; priority: number }>();
  const [submitting, setSubmitting] = useState(false);

  const boundRouteIds = bindings.map((b) => b.routeId);
  const availableRoutes = routes.filter((r) => !boundRouteIds.includes(r.id));
  const getRouteName = (routeId: string) =>
    routes.find((r) => r.id === routeId)?.routeName || routeId?.substring(0, 12) || 'Unknown';

  const handleFinish = async (values: { routeIds?: string[]; priority?: number }) => {
    const routeIds = values.routeIds ?? [];
    // No new routes: user may have only unbound/toggled — just close.
    if (routeIds.length === 0) {
      onClose();
      return;
    }
    setSubmitting(true);
    try {
      const allOk = await onAddBindings(routeIds, values.priority ?? 100);
      if (allOk) {
        form.resetFields(['routeIds']);
        onClose();
      }
    } finally {
      setSubmitting(false);
    }
  };

  return (
    <Modal
      title={
        <div className="modal-header-modern">
          <div className="modal-icon-wrapper" style={{ background: 'linear-gradient(135deg, #3b82f6, #1d4ed8)' }}>
            <LinkOutlined />
          </div>
          <div className="modal-title-text">
            <div className="modal-title">{t('auth_policy.bind_routes')}</div>
            <div className="modal-subtitle">{policy.policyName}</div>
          </div>
        </div>
      }
      open
      onCancel={onClose}
      footer={null}
      width={600}
      destroyOnHidden
    >
      {/* Current bindings */}
      <div className="form-section-modern">
        <div className="section-header">
          <LinkOutlined className="section-icon" />
          <span className="section-title">{t('auth_policy.current_bindings')}</span>
        </div>
        {bindings.length > 0 ? (
          <div style={{ marginBottom: 16 }}>
            {bindings.map((binding) => (
              <div
                key={binding.bindingId}
                style={{
                  display: 'flex', justifyContent: 'space-between', alignItems: 'center',
                  padding: '8px 0', borderBottom: '1px solid rgba(148, 163, 184, 0.2)',
                }}
              >
                <div style={{ display: 'flex', alignItems: 'center', gap: 8 }}>
                  <Tag color="blue">{getRouteName(binding.routeId)}</Tag>
                  <Text type="secondary">Priority: {binding.priority ?? 100}</Text>
                </div>
                <div style={{ display: 'flex', alignItems: 'center', gap: 8 }}>
                  <Switch
                    size="small"
                    checked={binding.enabled !== false}
                    onChange={() => onToggleBinding(binding)}
                  />
                  <Button size="small" danger onClick={() => onUnbind(binding)}>
                    {t('auth_policy.unbind')}
                  </Button>
                </div>
              </div>
            ))}
          </div>
        ) : (
          <Text type="secondary">{t('auth_policy.no_bindings')}</Text>
        )}
      </div>

      {/* Add new bindings */}
      <div className="form-section-modern">
        <div className="section-header">
          <PlusOutlined className="section-icon" />
          <span className="section-title">{t('auth_policy.add_binding')}</span>
        </div>
        <Form form={form} layout="vertical" onFinish={handleFinish} initialValues={{ priority: 100 }}>
          <Form.Item name="routeIds" label={t('auth_policy.select_routes')}>
            <Select
              mode="multiple"
              showSearch
              optionFilterProp="children"
              placeholder={t('auth_policy.select_routes_placeholder')}
              style={{ width: '100%' }}
              notFoundContent={
                <span style={{ color: '#999' }}>
                  {routes.length > 0 && availableRoutes.length === 0
                    ? t('auth_policy.all_routes_bound')
                    : t('auth_policy.no_routes_available')}
                </span>
              }
            >
              {availableRoutes.map((route) => (
                <Select.Option key={route.id} value={route.id}>
                  {route.routeName || route.id}
                </Select.Option>
              ))}
            </Select>
          </Form.Item>
          <Form.Item name="priority" label={t('auth_policy.binding_priority')}>
            <InputNumber min={1} max={1000} style={{ width: '100%' }} />
          </Form.Item>
          <div className="modal-footer-modern">
            <Button onClick={onClose}>{t('common.cancel')}</Button>
            <Button type="primary" htmlType="submit" loading={submitting}>{t('common.confirm')}</Button>
          </div>
        </Form>
      </div>
    </Modal>
  );
};

export default BindingModal;
