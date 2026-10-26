/**
 * SSL certificates page (rewritten as a feature).
 * Fields aligned with SslCertificateController (/api/ssl) +
 * model/SslCertificate.java. Keystore upload sends query params per the
 * backend's @RequestParam signature (the old JSON-body call was broken).
 */
import React, { useCallback, useMemo, useState } from 'react';
import {
  Button, Card, Col, Popconfirm, Row, Space, Statistic, Table, Tag, Typography  } from 'antd';
import { message } from '../../utils/antdStatic';
import type { ColumnsType } from 'antd/es/table';
import {
  DeleteOutlined, PlusOutlined, ReloadOutlined, SafetyCertificateOutlined,
} from '@ant-design/icons';
import { useParams } from 'react-router-dom';
import { parseServerTime } from '../../utils/serverTime';
import { useTranslation } from 'react-i18next';
import { useRequest } from '../../hooks/useRequest';
import { ApiError } from '../../api/http';
import {
  deleteCertificate, getCertificateStats, listCertificates,
  refreshCertificateStatuses, setCertificateEnabled, uploadKeystore, uploadPemCertificate,
} from '../../api/certificates';
import type { SslCertificate } from '../../types/certificate';
import StatusTag from './components/StatusTag';
import CertDetailModal from './components/CertDetailModal';
import UploadCertModal, { CertificateUploadPayload } from './components/UploadCertModal';

const { Title, Text } = Typography;

const errorMessage = (error: unknown, fallbackKey: string, t: (k: string) => string): string => (
  error instanceof ApiError && error.message ? error.message : t(fallbackKey)
);

const CertificatePage: React.FC = () => {
  const { t } = useTranslation();
  const { instanceId } = useParams<{ instanceId: string }>();

  const [uploadOpen, setUploadOpen] = useState(false);
  const [detailCert, setDetailCert] = useState<SslCertificate | null>(null);

  const certsRequest = useRequest(
    (signal) => listCertificates(instanceId ? { instanceId } : undefined, { signal }),
    {
      deps: [instanceId],
      onError: (error) => {
        message.error(t('cert.load_error', { error: error.message }));
      },
    },
  );
  const certificates = useMemo(() => certsRequest.data ?? [], [certsRequest.data]);

  const statsRequest = useRequest(
    (signal) => getCertificateStats(instanceId ? { instanceId } : undefined, { signal }),
    { deps: [instanceId], onError: () => {} },
  );
  const stats = statsRequest.data;

  const reload = useCallback(() => {
    certsRequest.run();
    statsRequest.run();
  }, [certsRequest, statsRequest]);

  const handleUpload = async (payload: CertificateUploadPayload) => {
    try {
      if (payload.kind === 'pem') {
        await uploadPemCertificate(payload, instanceId);
      } else {
        await uploadKeystore(payload, instanceId);
      }
      message.success(t('cert.upload_success'));
      setUploadOpen(false);
      reload();
    } catch (error) {
      message.error(errorMessage(error, 'cert.upload_error', t));
    }
  };

  const handleToggleEnabled = useCallback(async (cert: SslCertificate) => {
    try {
      await setCertificateEnabled(cert.id, !cert.enabled);
      message.success(t('cert.update_success'));
      reload();
    } catch (error) {
      message.error(errorMessage(error, 'cert.update_error', t));
    }
  }, [t, reload]);

  const handleDelete = useCallback(async (cert: SslCertificate) => {
    try {
      await deleteCertificate(cert.id);
      message.success(t('cert.delete_success'));
      reload();
    } catch (error) {
      message.error(errorMessage(error, 'cert.delete_error', t));
    }
  }, [t, reload]);

  const handleRefreshStatus = async () => {
    try {
      await refreshCertificateStatuses();
      message.success(t('cert.refresh_success'));
      reload();
    } catch (error) {
      message.error(errorMessage(error, 'cert.refresh_error', t));
    }
  };

  const columns = useMemo<ColumnsType<SslCertificate>>(() => [
    {
      title: t('cert.cert_name'),
      dataIndex: 'certName',
      key: 'certName',
      render: (text: string) => <Text strong>{text}</Text>,
    },
    {
      title: t('cert.domain'),
      dataIndex: 'domain',
      key: 'domain',
    },
    {
      title: t('cert.type'),
      dataIndex: 'certType',
      key: 'certType',
      render: (type: string) => <Tag color="blue">{type}</Tag>,
    },
    {
      title: t('cert.status'),
      dataIndex: 'status',
      key: 'status',
      render: (_status: string, record) => (
        <StatusTag status={record.status} daysToExpiry={record.daysToExpiry} />
      ),
    },
    {
      title: t('cert.valid_to'),
      dataIndex: 'validTo',
      key: 'validTo',
      render: (date: string) => (date ? parseServerTime(date).toLocaleDateString() : '-'),
    },
    {
      title: t('cert.enabled'),
      dataIndex: 'enabled',
      key: 'enabled',
      render: (_enabled: boolean, record) => (
        <Popconfirm
          title={record.enabled ? t('cert.disable_confirm') : t('cert.enable_confirm')}
          onConfirm={() => handleToggleEnabled(record)}
        >
          <Tag color={record.enabled ? 'green' : 'default'} style={{ cursor: 'pointer' }}>
            {record.enabled ? t('cert.enabled') : t('cert.disabled')}
          </Tag>
        </Popconfirm>
      ),
    },
    {
      title: t('common.actions'),
      key: 'actions',
      render: (_unknown, record) => (
        <Space>
          <Button size="small" onClick={() => setDetailCert(record)}>
            {t('common.detail')}
          </Button>
          <Popconfirm title={t('cert.delete_confirm')} onConfirm={() => handleDelete(record)}>
            <Button size="small" danger icon={<DeleteOutlined />}>
              {t('common.delete')}
            </Button>
          </Popconfirm>
        </Space>
      ),
    },
  ], [t, handleToggleEnabled, handleDelete]);

  return (
    <div style={{ padding: 24 }}>
      <Title level={3}>
        <SafetyCertificateOutlined style={{ marginRight: 8 }} />
        {t('cert.title')}
      </Title>

      {/* Statistics Cards */}
      <Row gutter={16} style={{ marginBottom: 24 }}>
        <Col span={6}>
          <Card className="certificate-stat-card">
            <Statistic title={t('cert.total')} value={stats?.total ?? 0} />
          </Card>
        </Col>
        <Col span={6}>
          <Card className="certificate-stat-card">
            <Statistic title={t('cert.valid')} value={stats?.valid ?? 0} valueStyle={{ color: '#3f8600' }} />
          </Card>
        </Col>
        <Col span={6}>
          <Card className="certificate-stat-card">
            <Statistic title={t('cert.expiring_soon')} value={stats?.expiringSoon ?? 0} valueStyle={{ color: '#faad14' }} />
          </Card>
        </Col>
        <Col span={6}>
          <Card className="certificate-stat-card">
            <Statistic title={t('cert.expired')} value={stats?.expired ?? 0} valueStyle={{ color: '#cf1322' }} />
          </Card>
        </Col>
      </Row>

      {/* Action Buttons */}
      <Space style={{ marginBottom: 16 }}>
        <Button type="primary" icon={<PlusOutlined />} onClick={() => setUploadOpen(true)}>
          {t('cert.upload')}
        </Button>
        <Button icon={<ReloadOutlined />} onClick={handleRefreshStatus}>
          {t('cert.refresh_status')}
        </Button>
      </Space>

      {/* Certificate Table */}
      <Card>
        <Table
          className="certificate-table"
          loading={certsRequest.loading}
          dataSource={certificates}
          columns={columns}
          rowKey="id"
          pagination={{ pageSize: 10 }}
        />
      </Card>

      {uploadOpen && (
        <UploadCertModal
          onCancel={() => setUploadOpen(false)}
          onSubmit={handleUpload}
        />
      )}
      {detailCert && (
        <CertDetailModal
          certificate={detailCert}
          onClose={() => setDetailCert(null)}
        />
      )}
    </div>
  );
};

export default CertificatePage;
