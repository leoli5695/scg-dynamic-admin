/**
 * Certificate detail modal — read-only Descriptions view.
 */
import React from 'react';
import { Descriptions, Modal } from 'antd';
import { useTranslation } from 'react-i18next';
import type { SslCertificate } from '../../../types/certificate';
import StatusTag from './StatusTag';
import { parseServerTime } from '../../../utils/serverTime';

interface CertDetailModalProps {
  certificate: SslCertificate;
  onClose: () => void;
}

const CertDetailModal: React.FC<CertDetailModalProps> = ({ certificate, onClose }) => {
  const { t } = useTranslation();
  return (
    <Modal
      title={t('cert.detail_title')}
      open
      onCancel={onClose}
      footer={null}
      width={600}
      destroyOnHidden
    >
      <Descriptions bordered column={2}>
        <Descriptions.Item label={t('cert.cert_name')} span={2}>{certificate.certName}</Descriptions.Item>
        <Descriptions.Item label={t('cert.domain')} span={2}>{certificate.domain}</Descriptions.Item>
        <Descriptions.Item label={t('cert.type')}>{certificate.certType}</Descriptions.Item>
        <Descriptions.Item label={t('cert.status')}>
          <StatusTag status={certificate.status} daysToExpiry={certificate.daysToExpiry} />
        </Descriptions.Item>
        <Descriptions.Item label={t('cert.issuer')} span={2}>{certificate.issuer || '-'}</Descriptions.Item>
        <Descriptions.Item label={t('cert.serial')}>{certificate.serialNumber || '-'}</Descriptions.Item>
        <Descriptions.Item label={t('cert.days_to_expiry')}>
          {certificate.daysToExpiry} {t('cert.days')}
        </Descriptions.Item>
        <Descriptions.Item label={t('cert.valid_from')}>
          {certificate.validFrom ? parseServerTime(certificate.validFrom).toLocaleString() : '-'}
        </Descriptions.Item>
        <Descriptions.Item label={t('cert.valid_to')}>
          {certificate.validTo ? parseServerTime(certificate.validTo).toLocaleString() : '-'}
        </Descriptions.Item>
        <Descriptions.Item label={t('cert.enabled')}>
          {certificate.enabled ? t('cert.enabled') : t('cert.disabled')}
        </Descriptions.Item>
        <Descriptions.Item label={t('cert.auto_renew')}>
          {certificate.autoRenew ? t('cert.yes') : t('cert.no')}
        </Descriptions.Item>
      </Descriptions>
    </Modal>
  );
};

export default CertDetailModal;
