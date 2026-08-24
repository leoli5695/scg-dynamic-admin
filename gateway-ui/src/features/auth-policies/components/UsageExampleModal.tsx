/**
 * Usage example modal — fetches GET /api/auth/policies/{id}/usage-example
 * and renders auth-type-specific header/curl snippets with copy buttons.
 */
import React from 'react';
import { Button, Input, Modal, Spin, Tag, Typography } from 'antd';
import { message } from '../../../utils/antdStatic';
import { CopyOutlined, QuestionCircleOutlined } from '@ant-design/icons';
import { useTranslation } from 'react-i18next';
import { useRequest } from '../../../hooks/useRequest';
import { getAuthPolicyUsageExample } from '../../../api/authPolicies';
import type { AuthPolicyDefinition } from '../../../types/authPolicy';

const { Text } = Typography;

interface UsageExampleModalProps {
  policy: AuthPolicyDefinition;
  onClose: () => void;
}

const monoAreaStyle: React.CSSProperties = { fontFamily: 'monospace', fontSize: 13 };

const UsageExampleModal: React.FC<UsageExampleModalProps> = ({ policy, onClose }) => {
  const { t } = useTranslation();

  const { data: example, loading } = useRequest(
    (signal) => getAuthPolicyUsageExample(policy.policyId ?? '', { signal }),
    {
      deps: [policy.policyId],
      onError: () => message.error(t('auth_policy.usage_example_failed')),
    },
  );

  const copyToClipboard = (text?: string) => {
    if (!text) return;
    navigator.clipboard.writeText(text);
    message.success(t('message.copy_success'));
  };

  const renderCurlBlock = (labelKey: string, value?: string) => value && (
    <div style={{ marginBottom: 16 }}>
      <Text type="secondary">{t(labelKey)}</Text>
      <div style={{ marginTop: 8 }}>
        <Input.TextArea value={value} readOnly autoSize={{ minRows: 6, maxRows: 15 }} style={monoAreaStyle} />
        <Button icon={<CopyOutlined />} style={{ marginTop: 8 }} onClick={() => copyToClipboard(value)}>
          {t('common.copy')}
        </Button>
      </div>
    </div>
  );

  const renderContent = () => {
    if (!example) return null;
    if (example.error) return <Text type="danger">{example.error}</Text>;

    switch (example.authType?.toUpperCase()) {
      case 'JWT':
        return (
          <div>
            <div style={{ marginBottom: 16 }}>
              <Text type="secondary">{t('auth_policy.jwt_header_format')}</Text>
              <div style={{ marginTop: 8, display: 'flex', alignItems: 'center', gap: 8 }}>
                <Input value={example.headerValue} readOnly style={{ flex: 1 }} />
                <Button icon={<CopyOutlined />} onClick={() => copyToClipboard(example.headerValue)} />
              </div>
            </div>
            {renderCurlBlock('auth_policy.curl_example', example.curlExample)}
            {example.tokenExpiresIn && (
              <div style={{ marginBottom: 8 }}>
                <Tag color="green">{t('auth_policy.token_expires')}: {example.tokenExpiresIn}</Tag>
                <Tag color="blue">{t('auth_policy.algorithm')}: {example.algorithm}</Tag>
              </div>
            )}
          </div>
        );

      case 'API_KEY':
        return (
          <div>
            <div style={{ marginBottom: 16 }}>
              <Text type="secondary">{t('auth_policy.api_key_header_format')}</Text>
              <div style={{ marginTop: 8, display: 'flex', alignItems: 'center', gap: 8 }}>
                <Input addonBefore={`${example.headerName}: `} value={example.headerValue} readOnly style={{ flex: 1 }} />
                <Button icon={<CopyOutlined />} onClick={() => copyToClipboard(example.headerValue)} />
              </div>
            </div>
            {renderCurlBlock('auth_policy.curl_example', example.curlExample)}
            {example.prefixNote && <Tag color="orange">{example.prefixNote}</Tag>}
          </div>
        );

      case 'BASIC':
        return (
          <div>
            <div style={{ marginBottom: 16 }}>
              <Text type="secondary">{t('auth_policy.basic_header_format')}</Text>
              <div style={{ marginTop: 8, display: 'flex', alignItems: 'center', gap: 8 }}>
                <Input value={example.headerValue} readOnly style={{ flex: 1 }} />
                <Button icon={<CopyOutlined />} onClick={() => copyToClipboard(example.headerValue)} />
              </div>
            </div>
            <div style={{ marginBottom: 16 }}>
              <Text type="secondary">{t('auth_policy.credentials')}</Text>
              <div style={{ marginTop: 8 }}>
                <Text code>{example.credentialsFormat}</Text>
                <Text type="secondary" style={{ marginLeft: 8 }}>(Base64: {example.credentialsEncoded})</Text>
              </div>
            </div>
            {renderCurlBlock('auth_policy.curl_example', example.curlExample)}
            {renderCurlBlock('auth_policy.curl_alternative', example.curlWithAuthFlag)}
            {example.realm && (
              <Tag color="purple">{t('auth_policy.realm')}: {example.realm}</Tag>
            )}
          </div>
        );

      case 'OAUTH2':
        return (
          <div>
            {renderCurlBlock('auth_policy.oauth2_get_token', example.getTokenCurl)}
            <div style={{ marginBottom: 16 }}>
              <Text type="secondary">{t('auth_policy.oauth2_use_token')}</Text>
              <div style={{ marginTop: 8, display: 'flex', alignItems: 'center', gap: 8 }}>
                <Input value={example.headerValue} readOnly style={{ flex: 1 }} />
                <Button icon={<CopyOutlined />} onClick={() => copyToClipboard(example.headerValue)} />
              </div>
            </div>
            <div style={{ marginBottom: 8 }}>
              <Tag color="blue">Client ID: {example.clientId}</Tag>
              <Tag color="green">Token Endpoint: {example.tokenEndpoint}</Tag>
            </div>
            {example.requiredScopes && (
              <Tag color="orange">{t('auth_policy.required_scopes')}: {example.requiredScopes}</Tag>
            )}
            {example.note && (
              <Text type="secondary" style={{ marginTop: 16 }}>{example.note}</Text>
            )}
          </div>
        );

      case 'HMAC':
        return (
          <div>
            <div style={{ marginBottom: 16 }}>
              <Text type="secondary">{t('auth_policy.hmac_required_headers')}</Text>
              <div style={{ marginTop: 8 }}>
                {example.requiredHeaders?.map((h, i) => (
                  <Tag key={`${h.name}-${i}`} color="blue">{h.name}: {h.value}</Tag>
                ))}
              </div>
            </div>
            <div style={{ marginBottom: 16 }}>
              <Text type="secondary">{t('auth_policy.hmac_signature_calc')}</Text>
              <div style={{ marginTop: 8 }}>
                <Text code>{example.signatureCalculation}</Text>
              </div>
              <div style={{ marginTop: 4 }}>
                <Text type="secondary">{example.stringToSignFormat}</Text>
              </div>
            </div>
            {renderCurlBlock('auth_policy.curl_example', example.curlExample)}
            <div style={{ marginBottom: 8 }}>
              <Tag color="purple">{t('auth_policy.signature_algorithm')}: {example.signatureAlgorithm}</Tag>
              <Tag color="orange">{t('auth_policy.access_key')}: {example.accessKey}</Tag>
            </div>
            {example.note && (
              <Text type="secondary" style={{ marginTop: 16 }}>{example.note}</Text>
            )}
          </div>
        );

      default:
        return <Text type="secondary">{t('auth_policy.no_example_available')}</Text>;
    }
  };

  return (
    <Modal
      title={
        <div className="modal-header-modern">
          <div className="modal-icon-wrapper" style={{ background: 'linear-gradient(135deg, #10b981, #059669)' }}>
            <QuestionCircleOutlined />
          </div>
          <div className="modal-title-text">
            <div className="modal-title">{t('auth_policy.usage_example')}</div>
            <div className="modal-subtitle">{policy.policyName} ({policy.authType})</div>
          </div>
        </div>
      }
      open
      onCancel={onClose}
      footer={<Button onClick={onClose}>{t('common.close')}</Button>}
      width={700}
      destroyOnHidden
    >
      <Spin spinning={loading}>
        {renderContent()}
      </Spin>
    </Modal>
  );
};

export default UsageExampleModal;
