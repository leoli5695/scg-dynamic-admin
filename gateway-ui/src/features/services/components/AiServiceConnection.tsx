import { Descriptions, Tag, Typography } from 'antd';
import { useTranslation } from 'react-i18next';
import type { ServiceDefinition } from '../../../types/service';
import { getProviderMeta, hasCustomBaseUrl } from '../providers';

const { Text } = Typography;

/**
 * Provider connection metadata for an AI service.
 *
 * Field policy (commercial gateway consoles): the provider *type* is the only
 * identity an operator configures; the data plane resolves the official base
 * URL from it. The URL is surfaced only when it is a real custom override
 * (relay / self-hosted), and credentials are represented only by their presence.
 */
export default function AiServiceConnection({ service }: { service: ServiceDefinition }) {
  const { t } = useTranslation();
  const meta = getProviderMeta(service.providerType);
  const providerLabel = meta ? t(meta.labelKey) : t('services.provider_unknown');
  const protocolLabel = service.protocol === 'ANTHROPIC'
    ? t('services.protocol.anthropic')
    : t('services.protocol.openai_compat');
  const customBaseUrl = hasCustomBaseUrl(service.providerType, service.baseUrl);

  return (
    <Descriptions
      size="small"
      column={1}
      className="ai-service-connection"
      items={[
        {
          key: 'provider',
          label: t('services.ai_provider'),
          children: (
            <span className="ai-provider-value">
              <Tag className="provider-tag" variant="filled">{providerLabel}</Tag>
              <Text type="secondary" className="ai-provider-id">{service.providerType || '—'}</Text>
            </span>
          ),
        },
        {
          key: 'protocol',
          label: t('services.ai_protocol'),
          children: <Tag className="protocol-tag" variant="filled">{protocolLabel}</Tag>,
        },
        {
          key: 'credential',
          label: t('services.api_key'),
          children: (
            <Tag color={service.credentialRef ? 'green' : 'default'} variant="filled">
              {t(service.credentialRef ? 'services.credential_configured' : 'services.credential_none')}
            </Tag>
          ),
        },
        ...(customBaseUrl ? [{
          key: 'endpoint',
          label: t('services.ai_custom_endpoint'),
          children: <Text className="ai-endpoint-value">{service.baseUrl}</Text>,
        }] : []),
      ]}
    />
  );
}