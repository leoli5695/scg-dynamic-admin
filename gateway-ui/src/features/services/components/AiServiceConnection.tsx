import { Descriptions, Tag } from 'antd';
import { useTranslation } from 'react-i18next';
import type { ServiceDefinition } from '../../../types/service';

/** Provider connection metadata; credentials are represented only by their presence. */
export default function AiServiceConnection({ service }: { service: ServiceDefinition }) {
  const { t } = useTranslation();
  return (
    <Descriptions
      size="small"
      column={1}
      className="ai-service-connection"
      items={[
        { key: 'provider', label: t('services.provider_type'), children: service.providerType || '—' },
        {
          key: 'endpoint', label: t('services.base_url'),
          children: <span style={{ overflowWrap: 'anywhere' }}>{service.baseUrl || t('services.ai_default_endpoint')}</span>,
        },
        {
          key: 'credential', label: t('services.api_key'),
          children: <Tag color={service.credentialRef ? 'blue' : 'default'}>
            {t(service.credentialRef ? 'services.credential_configured' : 'services.credential_none')}
          </Tag>,
        },
      ]}
    />
  );
}
