/**
 * Create/edit auth policy modal (unified).
 * Form field names map 1:1 to the flat fields of
 * model/AuthPolicyDefinition.java, so values are submitted as-is.
 * On edit the full stored definition is merged under the form values —
 * the backend PUT replaces the whole config, so fields not exposed in the
 * form (whitelistPaths, apiKeys, ...) must be carried over.
 */
import React, { useMemo } from 'react';
import ResourceEditor from '../../instance-detail/ResourceEditor';
import { Col, Form, Input, InputNumber, Row, Select, Switch } from 'antd';
import { EditOutlined, PlusOutlined, SafetyOutlined } from '@ant-design/icons';
import { useTranslation } from 'react-i18next';
import type { AuthPolicyDefinition, AuthType } from '../../../types/authPolicy';
import { AUTH_CONFIG_FIELDS, AUTH_TYPES, AUTH_TYPE_DEFAULTS, getAuthTypeLabel } from '../utils';

interface AuthPolicyFormModalProps {
  mode: 'create' | 'edit';
  /** Required for edit mode — the full stored definition. */
  policy?: AuthPolicyDefinition;
  onCancel: () => void;
  onSubmit: (body: AuthPolicyDefinition) => Promise<void>;
}

const AuthPolicyFormModal: React.FC<AuthPolicyFormModalProps> = ({
  mode,
  policy,
  onCancel,
  onSubmit,
}) => {
  const { t } = useTranslation();
  const isEdit = mode === 'edit';
  const [form] = Form.useForm();
  const [submitting, setSubmitting] = React.useState(false);

  const initialValues = useMemo<Partial<AuthPolicyDefinition>>(
    () => (isEdit && policy ? policy : { enabled: true }),
    [isEdit, policy],
  );

  const handleTypeChange = (value: AuthType) => {
    // Drop the previous type's config, then apply backend-aligned defaults.
    form.resetFields(AUTH_CONFIG_FIELDS);
    const defaults = AUTH_TYPE_DEFAULTS[value];
    if (defaults) form.setFieldsValue(defaults as Partial<AuthPolicyDefinition>);
  };

  const handleFinish = async (values: AuthPolicyDefinition) => {
    const body: AuthPolicyDefinition =
      isEdit && policy ? { ...policy, ...values, policyId: policy.policyId } : values;
    setSubmitting(true);
    try {
      await onSubmit(body);
    } finally {
      setSubmitting(false);
    }
  };

  const renderConfigFields = (authType?: string) => {
    switch (authType) {
      case 'JWT':
        return (
          <>
            <Form.Item
              name="secretKey"
              label={t('auth_policy.jwt_secret')}
              rules={[{ required: true }]}
            >
              <Input.Password />
            </Form.Item>
            <Row gutter={16}>
              <Col span={12}>
                <Form.Item name="jwtAlgorithm" label={t('auth_policy.jwt_algorithm')}>
                  <Select>
                    <Select.Option value="HS256">HS256</Select.Option>
                    <Select.Option value="HS512">HS512</Select.Option>
                    <Select.Option value="RS256">RS256</Select.Option>
                  </Select>
                </Form.Item>
              </Col>
              <Col span={12}>
                <Form.Item name="jwtClockSkewSeconds" label={t('auth_policy.jwt_clock_skew')}>
                  <InputNumber min={0} style={{ width: '100%' }} />
                </Form.Item>
              </Col>
            </Row>
            <Form.Item name="jwtIssuer" label={t('auth_policy.jwt_issuer')}>
              <Input />
            </Form.Item>
            <Form.Item name="jwtAudience" label={t('auth_policy.jwt_audience')}>
              <Input />
            </Form.Item>
          </>
        );
      case 'API_KEY':
        return (
          <>
            <Form.Item name="apiKey" label={t('auth_policy.api_key')} rules={[{ required: true }]}>
              <Input.Password />
            </Form.Item>
            <Form.Item name="apiKeyHeader" label={t('auth_policy.api_key_header')}>
              <Input placeholder="X-API-Key" />
            </Form.Item>
            <Form.Item name="apiKeyPrefix" label={t('auth_policy.api_key_prefix')}>
              <Input placeholder="e.g., sk_live_" />
            </Form.Item>
          </>
        );
      case 'OAUTH2':
        return (
          <>
            <Form.Item
              name="clientId"
              label={t('auth_policy.client_id')}
              rules={[{ required: true }]}
            >
              <Input />
            </Form.Item>
            <Form.Item
              name="clientSecret"
              label={t('auth_policy.client_secret')}
              rules={[{ required: true }]}
            >
              <Input.Password />
            </Form.Item>
            <Form.Item
              name="tokenEndpoint"
              label={t('auth_policy.token_endpoint')}
              rules={[{ required: true }]}
            >
              <Input placeholder="https://auth.example.com/introspect" />
            </Form.Item>
            <Form.Item name="requiredScopes" label={t('auth_policy.required_scopes')}>
              <Input placeholder="read,write" />
            </Form.Item>
          </>
        );
      case 'BASIC':
        return (
          <>
            <Form.Item
              name="basicUsername"
              label={t('auth_policy.username')}
              rules={[{ required: true }]}
            >
              <Input />
            </Form.Item>
            <Form.Item
              name="basicPassword"
              label={t('auth_policy.password')}
              rules={[{ required: true }]}
            >
              <Input.Password />
            </Form.Item>
            <Form.Item name="realm" label={t('auth_policy.realm')}>
              <Input placeholder="API Gateway" />
            </Form.Item>
          </>
        );
      case 'HMAC':
        return (
          <>
            <Form.Item
              name="accessKey"
              label={t('auth_policy.access_key')}
              rules={[{ required: true }]}
            >
              <Input />
            </Form.Item>
            <Form.Item name="signatureAlgorithm" label={t('auth_policy.signature_algorithm')}>
              <Select>
                <Select.Option value="HMAC-SHA256">HMAC-SHA256</Select.Option>
                <Select.Option value="HMAC-SHA512">HMAC-SHA512</Select.Option>
              </Select>
            </Form.Item>
          </>
        );
      default:
        return null;
    }
  };

  const typeOptions = AUTH_TYPES.map((type) => (
    <Select.Option key={type} value={type}>
      {getAuthTypeLabel(type, t)}
    </Select.Option>
  ));

  return (
    <ResourceEditor
      title={
        <div className="modal-header-modern">
          <div className={`modal-icon-wrapper${isEdit ? ' edit' : ''}`}>
            {isEdit ? <EditOutlined /> : <PlusOutlined />}
          </div>
          <div className="modal-title-text">
            <div className="modal-title">
              {t(isEdit ? 'auth_policy.edit' : 'auth_policy.create')}
            </div>
            {!isEdit && <div className="modal-subtitle">{t('auth_policy.create_description')}</div>}
          </div>
        </div>
      }
      form={form}
      initialValues={initialValues}
      submitting={submitting}
      onCancel={onCancel}
      onFinish={handleFinish}
      submitLabel={t(isEdit ? 'common.update' : 'common.create')}
      sections={[
        {
          label: t('strategy.basic_info'),
          fields: ['policyName', 'authType'],
          content: (
            <>
              <div className="form-section-modern">
                <Form.Item
                  name="policyName"
                  label={t('auth_policy.policy_name')}
                  rules={[{ required: true }]}
                >
                  <Input placeholder={t('auth_policy.policy_name_placeholder')} />
                </Form.Item>
                <Form.Item
                  name="authType"
                  label={t('auth_policy.auth_type')}
                  rules={[{ required: true }]}
                >
                  <Select disabled={isEdit} onChange={isEdit ? undefined : handleTypeChange}>
                    {typeOptions}
                  </Select>
                </Form.Item>
              </div>
            </>
          ),
        },
        {
          label: t('auth_policy.config_section'),
          content: (
            <>
              <div className="form-section-modern">
                <div className="section-header">
                  <SafetyOutlined className="section-icon" />
                  <span className="section-title">{t('auth_policy.config_section')}</span>
                </div>
                <Form.Item noStyle shouldUpdate={(prev, cur) => prev.authType !== cur.authType}>
                  {({ getFieldValue }) => renderConfigFields(getFieldValue('authType'))}
                </Form.Item>
              </div>
            </>
          ),
        },
        {
          label: t('strategy.advanced_settings'),
          fields: ['description', 'enabled'],
          content: (
            <div className="form-section-modern">
              <Form.Item name="description" label={t('common.description')}>
                <Input.TextArea rows={2} />
              </Form.Item>
              {isEdit && (
                <Form.Item name="enabled" label={t('common.status')} valuePropName="checked">
                  <Switch
                    checkedChildren={t('common.enabled')}
                    unCheckedChildren={t('common.disabled')}
                  />
                </Form.Item>
              )}
            </div>
          ),
        },
      ]}
    />
  );
};

export default AuthPolicyFormModal;
