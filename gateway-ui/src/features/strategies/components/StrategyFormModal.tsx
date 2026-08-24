/**
 * Unified create/edit strategy modal.
 * Config fields are rendered dynamically from the strategy type schema
 * (strategy_types.config_schema, enriched with AUTH / TOKEN_RATE_LIMITER
 * subSchemas by the backend).
 */
import React, { useMemo } from 'react';
import {
  Alert, Button, Col, Form, Input, InputNumber, Modal, Radio, Row, Select, Switch, Tag, Typography
} from 'antd';
import {
  ApiOutlined, EditOutlined, GlobalOutlined, KeyOutlined, LockOutlined,
  PlusOutlined, SafetyOutlined, SettingOutlined, ThunderboltOutlined, WarningOutlined,
} from '@ant-design/icons';
import type { FormInstance } from 'antd';
import { useTranslation } from 'react-i18next';
import type { RouteResponse } from '../../../types/route';
import type { StrategyDefinition, StrategyTypeInfo } from '../../../types/strategy';
import { buildStrategyConfig, requiresGlobalUniqueness, strategyToFormValues } from '../utils';
import type { StrategyFormValues } from '../utils';
import DynamicConfigFields, {
  AuthSubSchemaFields, MockResponseFields, MultiDimRateLimiterFields,
  RequestTransformFields, ResponseTransformFields,
} from './DynamicConfigFields';
import { TokenRateLimiterFields } from './TokenRateLimiterFields';

const { Text } = Typography;

/** All type-specific AUTH fields, reset when authType switches. */
const AUTH_RESET_FIELDS = [
  'secretKey', 'jwtAlgorithm', 'jwtIssuer', 'jwtAudience', 'jwtClockSkewSeconds',
  'apiKey', 'apiKeyHeader', 'apiKeyPrefix', 'apiKeyQueryParam',
  'clientId', 'clientSecret', 'tokenEndpoint', 'requiredScopes',
  'basicUsername', 'basicPassword', 'realm', 'passwordHashAlgorithm', 'basicUsersJson',
  'accessKey', 'signatureAlgorithm', 'clockSkewMinutes', 'requireNonce',
  'validateContentMd5', 'accessKeySecretsJson',
];

interface StrategyFormModalProps {
  mode: 'create' | 'edit';
  /** Strategy type metadata driving the dynamic config section. */
  typeInfo: StrategyTypeInfo;
  /** Present in edit mode. */
  strategy?: StrategyDefinition;
  routes: RouteResponse[];
  /** Create mode: existing GLOBAL strategy of this type (uniqueness guard). */
  existingGlobal?: StrategyDefinition;
  submitting?: boolean;
  onCancel: () => void;
  onBackToTypeSelect?: () => void;
  onSubmit: (body: StrategyDefinition) => Promise<void>;
}

/** Pick the right renderer for the type's config schema. */
function renderConfigFields(
  typeInfo: StrategyTypeInfo,
  form: FormInstance,
  t: (key: string, options?: Record<string, unknown>) => string,
): React.ReactNode {
  const schema = typeInfo.configSchema;
  if (!schema) return null;
  const code = typeInfo.typeCode;

  if (code === 'AUTH' && schema.subSchemas) {
    return (
      <>
        <Row gutter={16}>
          <Col span={12}>
            <Form.Item name="authType" label={t('strategy.config.auth_type')} initialValue="JWT">
              <Select onChange={() => form.resetFields(AUTH_RESET_FIELDS)}>
                <Select.Option value="JWT"><KeyOutlined /> JWT</Select.Option>
                <Select.Option value="API_KEY"><ApiOutlined /> API Key</Select.Option>
                <Select.Option value="OAUTH2"><GlobalOutlined /> OAuth2</Select.Option>
                <Select.Option value="BASIC"><LockOutlined /> Basic</Select.Option>
                <Select.Option value="HMAC"><SafetyOutlined /> HMAC</Select.Option>
              </Select>
            </Form.Item>
          </Col>
        </Row>
        <Form.Item noStyle shouldUpdate>
          {({ getFieldValue }) => {
            const authType = getFieldValue('authType') || 'JWT';
            const subSchema = schema.subSchemas?.[authType];
            if (!subSchema || !subSchema.fields) return null;
            return <AuthSubSchemaFields schema={{ fields: subSchema.fields, subSchemas: schema.subSchemas }} form={form} t={t} />;
          }}
        </Form.Item>
      </>
    );
  }

  if (code === 'MULTI_DIM_RATE_LIMITER' && schema.multiDimension) {
    return <MultiDimRateLimiterFields schema={schema} form={form} t={t} />;
  }
  if (code === 'REQUEST_TRANSFORM' && schema.subSchemas) {
    return <RequestTransformFields schema={schema} form={form} t={t} />;
  }
  if (code === 'RESPONSE_TRANSFORM' && schema.subSchemas) {
    return <ResponseTransformFields schema={schema} form={form} t={t} />;
  }
  if (code === 'MOCK_RESPONSE' && schema.subSchemas) {
    return <MockResponseFields schema={schema} form={form} t={t} />;
  }
  if (code === 'TOKEN_RATE_LIMITER' && schema.subSchemas) {
    return <TokenRateLimiterFields schema={schema} form={form} t={t} />;
  }

  // TIMEOUT gets a low-value warning on top of the generic fields.
  if (code === 'TIMEOUT') {
    return (
      <>
        <Form.Item noStyle shouldUpdate>
          {({ getFieldValue }) => {
            const connectTimeout = getFieldValue('connectTimeout');
            const responseTimeout = getFieldValue('responseTimeout');
            const hasLowTimeout = (connectTimeout && connectTimeout < 100) || (responseTimeout && responseTimeout < 100);
            if (!hasLowTimeout) return null;
            return (
              <Alert
                message={t('strategy.timeout_low_warning', { defaultValue: '超时时间设置小于100ms可能导致正常请求被误拦截，请确认是否用于测试目的' })}
                type="warning"
                showIcon
                icon={<WarningOutlined />}
                style={{ marginBottom: 16 }}
              />
            );
          }}
        </Form.Item>
        <DynamicConfigFields schema={schema} form={form} t={t} />
      </>
    );
  }

  return <DynamicConfigFields schema={schema} form={form} t={t} />;
}

const StrategyFormModal: React.FC<StrategyFormModalProps> = ({
  mode, typeInfo, strategy, routes, existingGlobal,
  submitting = false, onCancel, onBackToTypeSelect, onSubmit,
}) => {
  const { t } = useTranslation();
  const [form] = Form.useForm();
  const isEdit = mode === 'edit';
  const strategyTypeCode = strategy?.strategyType ?? typeInfo.typeCode;

  const initialValues = useMemo<StrategyFormValues>(() => {
    if (isEdit && strategy) {
      return strategyToFormValues(strategy);
    }
    return {
      strategyType: typeInfo.typeCode,
      scope: existingGlobal ? 'ROUTE' : 'GLOBAL',
      priority: 100,
      enabled: true,
    };
  }, [isEdit, strategy, typeInfo.typeCode, existingGlobal]);

  const globalLocked = !isEdit
    && !!existingGlobal
    && requiresGlobalUniqueness(typeInfo.typeCode);

  const handleFinish = async (values: StrategyFormValues) => {
    const body: StrategyDefinition = {
      strategyId: strategy?.strategyId ?? '',
      strategyName: String(values.strategyName ?? ''),
      strategyType: strategyTypeCode,
      scope: values.scope === 'ROUTE' ? 'ROUTE' : 'GLOBAL',
      routeId: values.scope === 'ROUTE' ? String(values.routeId ?? '') : undefined,
      priority: typeof values.priority === 'number' ? values.priority : 100,
      enabled: values.enabled !== false,
      config: buildStrategyConfig(values, strategyTypeCode),
      description: values.description ? String(values.description) : undefined,
    };
    await onSubmit(body);
  };

  const routeSelect = (
    <Form.Item noStyle shouldUpdate>
      {({ getFieldValue }) => {
        if (getFieldValue('scope') !== 'ROUTE') return null;
        return (
          <Form.Item
            name="routeId"
            label={t('strategy.route_id')}
            rules={[{ required: true }]}
            extra={t('strategy.route_id_helper')}
          >
            <Select placeholder={t('strategy.route_id_placeholder')} size="large" showSearch optionFilterProp="children">
              {routes.map((route) => (
                <Select.Option key={route.id} value={route.id}>
                  {route.routeName || route.id}
                </Select.Option>
              ))}
            </Select>
          </Form.Item>
        );
      }}
    </Form.Item>
  );

  const title = (
    <div className="modal-header-modern">
      <div className="modal-title-wrapper">
        {!isEdit && onBackToTypeSelect && (
          <Button
            type="text"
            icon={<EditOutlined />}
            onClick={onBackToTypeSelect}
            style={{ marginRight: 8 }}
          >
            {t('common.back')}
          </Button>
        )}
        <div className={`modal-icon-wrapper${isEdit ? ' edit' : ''}`}>
          {isEdit ? <EditOutlined /> : <PlusOutlined />}
        </div>
        <div className="modal-title-text">
          <div className="modal-title">
            {isEdit ? t('strategy.edit') : `${t('strategy.create')} - ${typeInfo.typeName}`}
          </div>
          <div className="modal-subtitle">
            {isEdit ? t('strategy.edit_description') : (typeInfo.description || t('strategy.create_description'))}
          </div>
        </div>
      </div>
    </div>
  );

  return (
    <Modal
      title={title}
      open
      onCancel={onCancel}
      footer={null}
      width={720}
      className={`strategy-modal ${isEdit ? 'strategy-edit-modal' : 'strategy-create-modal'}`}
    >
      <Form form={form} layout="vertical" initialValues={initialValues} onFinish={handleFinish}>
        {/* Basic Info Section */}
        <div className="form-section-modern">
          <div className="section-header">
            <ThunderboltOutlined className="section-icon" />
            <span className="section-title">{t('strategy.basic_info')}</span>
          </div>
          <div className="section-content">
            <Row gutter={16}>
              <Col span={12}>
                <Form.Item
                  name="strategyName"
                  label={t('strategy.name')}
                  rules={[{ required: true }]}
                  extra={t('strategy.name_helper')}
                >
                  <Input placeholder={t('strategy.name_placeholder')} size="large" />
                </Form.Item>
              </Col>
              <Col span={12}>
                {isEdit ? (
                  <Form.Item
                    name="scope"
                    label={t('strategy.scope')}
                    extra={t('strategy.scope_helper')}
                  >
                    <Radio.Group size="large">
                      <Radio.Button value="GLOBAL"><GlobalOutlined /> {t('strategy.scope_global')}</Radio.Button>
                      <Radio.Button value="ROUTE"><ApiOutlined /> {t('strategy.scope_route')}</Radio.Button>
                    </Radio.Group>
                  </Form.Item>
                ) : (
                  <>
                    <Form.Item name="strategyType" label={t('strategy.type')} hidden>
                      <Input />
                    </Form.Item>
                    <div style={{ padding: '8px 0' }}>
                      <Text type="secondary">{t('strategy.type')}: </Text>
                      <Tag color={typeInfo.color} style={{ fontSize: 14, padding: '4px 12px' }}>
                        {typeInfo.typeName}
                      </Tag>
                    </div>
                  </>
                )}
              </Col>
            </Row>
            <Row gutter={16}>
              <Col span={12}>
                {!isEdit && (
                  <Form.Item
                    name="scope"
                    label={t('strategy.scope')}
                    extra={
                      globalLocked
                        ? t('strategy.global_strategy_exists_helper', {
                          type: typeInfo.typeName,
                          name: existingGlobal?.strategyName,
                          defaultValue: `已存在全局${typeInfo.typeName}策略 "${existingGlobal?.strategyName}"，请选择路由级别`,
                        })
                        : t('strategy.scope_helper')
                    }
                  >
                    <Radio.Group size="large">
                      <Radio.Button value="GLOBAL" disabled={globalLocked}>
                        <GlobalOutlined /> {t('strategy.scope_global')}
                      </Radio.Button>
                      <Radio.Button value="ROUTE"><ApiOutlined /> {t('strategy.scope_route')}</Radio.Button>
                    </Radio.Group>
                  </Form.Item>
                )}
              </Col>
              <Col span={12}>
                {routeSelect}
              </Col>
            </Row>
          </div>
        </div>

        {/* Configuration Section */}
        <div className="form-section-modern">
          <div className="section-header">
            <SettingOutlined className="section-icon" />
            <span className="section-title">{t('strategy.config')}</span>
            <span className="section-subtitle">{t('strategy.config_helper')}</span>
          </div>
          <div className="section-content">
            {renderConfigFields(typeInfo, form, t)}
          </div>
        </div>

        {/* Advanced Settings Section */}
        <div className="form-section-modern">
          <div className="section-header">
            <SettingOutlined className="section-icon" />
            <span className="section-title">{t('strategy.advanced_settings')}</span>
          </div>
          <div className="section-content">
            <Row gutter={16}>
              <Col span={8}>
                <Form.Item
                  name="priority"
                  label={t('strategy.priority')}
                  extra={t('strategy.priority_helper')}
                >
                  <InputNumber min={1} max={1000} style={{ width: '100%' }} size="large" />
                </Form.Item>
              </Col>
              <Col span={8}>
                <Form.Item
                  name="enabled"
                  label={t('strategy.enabled')}
                  valuePropName="checked"
                >
                  <Switch checkedChildren={t('common.enabled')} unCheckedChildren={t('common.disabled')} />
                </Form.Item>
              </Col>
            </Row>
            <Form.Item
              name="description"
              label={t('strategy.description_label')}
              extra={t('strategy.description_helper')}
            >
              <Input.TextArea rows={2} showCount maxLength={500} />
            </Form.Item>
          </div>
        </div>

        {/* Modal Footer */}
        <div className="modal-footer-modern">
          <Button onClick={onCancel} size="large">{t('common.cancel')}</Button>
          <Button type="primary" htmlType="submit" size="large" loading={submitting} icon={isEdit ? <EditOutlined /> : <PlusOutlined />}>
            {isEdit ? t('common.update') : t('common.create')}
          </Button>
        </div>
      </Form>
    </Modal>
  );
};

export default StrategyFormModal;
