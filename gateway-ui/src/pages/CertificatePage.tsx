import { useState, useEffect } from 'react';
import {
  Card, Row, Col, Table, Tag, Space, Button, Modal, Form, Input, Upload,
  Select, message, Spin, Typography, Badge, Tooltip, Popconfirm, Progress, Descriptions, Divider, Tabs
} from 'antd';
import {
  SafetyCertificateOutlined, PlusOutlined, DeleteOutlined, UploadOutlined,
  ReloadOutlined, ExclamationCircleOutlined, CheckCircleOutlined, ClockCircleOutlined, FileTextOutlined, FileAddOutlined
} from '@ant-design/icons';
import api from '../utils/api';
import { useTranslation } from 'react-i18next';

const { Title, Text } = Typography;
const { TextArea } = Input;
const { Dragger } = Upload;

interface SslCertificate {
  id: number;
  certName: string;
  domain: string;
  certType: string;
  issuer: string;
  serialNumber: string;
  validFrom: string;
  validTo: string;
  daysToExpiry: number;
  status: string;
  enabled: boolean;
  autoRenew: boolean;
  createdAt: string;
}

interface CertificateStats {
  total: number;
  valid: number;
  expiringSoon: number;
  expired: number;
  expiringList: SslCertificate[];
}

const CertificatePage: React.FC = () => {
  const [loading, setLoading] = useState(false);
  const [certificates, setCertificates] = useState<SslCertificate[]>([]);
  const [stats, setStats] = useState<CertificateStats | null>(null);
  const [uploadModalVisible, setUploadModalVisible] = useState(false);
  const [detailModalVisible, setDetailModalVisible] = useState(false);
  const [selectedCert, setSelectedCert] = useState<SslCertificate | null>(null);
  const [uploadMode, setUploadMode] = useState<'text' | 'file'>('file');
  const [certFileContent, setCertFileContent] = useState<string>('');
  const [keyFileContent, setKeyFileContent] = useState<string>('');
  const [form] = Form.useForm();
  const { t } = useTranslation();

  const loadCertificates = async () => {
    try {
      setLoading(true);
      const [certsRes, statsRes] = await Promise.all([
        api.get('/api/ssl'),
        api.get('/api/ssl/stats')
      ]);
      setCertificates(certsRes.data || []);
      setStats(statsRes.data || null);
    } catch (e) {
      console.error('Failed to load certificates:', e);
      message.error(t('cert.load_error'));
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => {
    loadCertificates();
  }, []);

  // 读取文件内容
  const readFileContent = (file: File): Promise<string> => {
    return new Promise((resolve, reject) => {
      const reader = new FileReader();
      reader.onload = () => resolve(reader.result as string);
      reader.onerror = () => reject(new Error('Failed to read file'));
      reader.readAsText(file);
    });
  };

  const handleUpload = async (values: any) => {
    try {
      const payload: any = {
        certName: values.certName,
        domain: values.domain,
      };

      if (values.certType === 'PEM') {
        // 使用文件内容或文本内容
        payload.certContent = uploadMode === 'file' ? certFileContent : values.certContent;
        payload.keyContent = uploadMode === 'file' ? keyFileContent : values.keyContent;
        
        if (!payload.certContent || !payload.keyContent) {
          message.error(t('cert.file_required') || 'Please provide certificate and key');
          return;
        }
        
        await api.post('/api/ssl/pem', payload);
      } else {
        payload.certType = values.certType;
        payload.keystoreContent = values.keystoreContent;
        payload.password = values.password;
        await api.post('/api/ssl/keystore', payload);
      }

      message.success(t('cert.upload_success'));
      setUploadModalVisible(false);
      form.resetFields();
      setCertFileContent('');
      setKeyFileContent('');
      loadCertificates();
    } catch (e: any) {
      console.error('Failed to upload certificate:', e);
      message.error(e.response?.data?.error || t('cert.upload_error'));
    }
  };

  const handleToggleEnabled = async (id: number, enabled: boolean) => {
    try {
      await api.put(`/api/ssl/${id}/enabled?enabled=${enabled}`);
      message.success(t('cert.update_success'));
      loadCertificates();
    } catch (e) {
      console.error('Failed to update certificate:', e);
      message.error(t('cert.update_error'));
    }
  };

  const handleDelete = async (id: number) => {
    try {
      await api.delete(`/api/ssl/${id}`);
      message.success(t('cert.delete_success'));
      loadCertificates();
    } catch (e) {
      console.error('Failed to delete certificate:', e);
      message.error(t('cert.delete_error'));
    }
  };

  const handleRefreshStatus = async () => {
    try {
      await api.post('/api/ssl/refresh-status');
      message.success(t('cert.refresh_success'));
      loadCertificates();
    } catch (e) {
      console.error('Failed to refresh status:', e);
      message.error(t('cert.refresh_error'));
    }
  };

  const getStatusTag = (status: string, daysToExpiry: number) => {
    switch (status) {
      case 'VALID':
        return <Tag icon={<CheckCircleOutlined />} color="success">{t('cert.status_valid')}</Tag>;
      case 'EXPIRING_SOON':
        return <Tag icon={<ClockCircleOutlined />} color="warning">{t('cert.status_expiring')} ({daysToExpiry} {t('cert.days')})</Tag>;
      case 'EXPIRED':
        return <Tag icon={<ExclamationCircleOutlined />} color="error">{t('cert.status_expired')}</Tag>;
      default:
        return <Tag>{status}</Tag>;
    }
  };

  const columns = [
    {
      title: t('cert.cert_name'),
      dataIndex: 'certName',
      key: 'certName',
      render: (text: string) => <Text strong>{text}</Text>
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
      render: (type: string) => <Tag color="blue">{type}</Tag>
    },
    {
      title: t('cert.status'),
      dataIndex: 'status',
      key: 'status',
      render: (status: string, record: SslCertificate) => getStatusTag(status, record.daysToExpiry)
    },
    {
      title: t('cert.valid_to'),
      dataIndex: 'validTo',
      key: 'validTo',
      render: (date: string) => date ? new Date(date).toLocaleDateString() : '-'
    },
    {
      title: t('cert.enabled'),
      dataIndex: 'enabled',
      key: 'enabled',
      render: (enabled: boolean, record: SslCertificate) => (
        <Popconfirm
          title={enabled ? t('cert.disable_confirm') : t('cert.enable_confirm')}
          onConfirm={() => handleToggleEnabled(record.id, !enabled)}
        >
          <Tag color={enabled ? 'green' : 'default'} style={{ cursor: 'pointer' }}>
            {enabled ? t('cert.enabled') : t('cert.disabled')}
          </Tag>
        </Popconfirm>
      )
    },
    {
      title: t('common.actions'),
      key: 'actions',
      render: (_: any, record: SslCertificate) => (
        <Space>
          <Button size="small" onClick={() => { setSelectedCert(record); setDetailModalVisible(true); }}>
            {t('common.detail')}
          </Button>
          <Popconfirm
            title={t('cert.delete_confirm')}
            onConfirm={() => handleDelete(record.id)}
          >
            <Button size="small" danger icon={<DeleteOutlined />}>
              {t('common.delete')}
            </Button>
          </Popconfirm>
        </Space>
      )
    }
  ];

  return (
    <div style={{ padding: 24 }}>
      <Title level={3}>
        <SafetyCertificateOutlined style={{ marginRight: 8 }} />
        {t('cert.title')}
      </Title>

      {/* Statistics Cards */}
      <Row gutter={16} style={{ marginBottom: 24 }}>
        <Col span={6}>
          <Card>
            <Statistic title={t('cert.total')} value={stats?.total || 0} />
          </Card>
        </Col>
        <Col span={6}>
          <Card>
            <Statistic title={t('cert.valid')} value={stats?.valid || 0} valueStyle={{ color: '#3f8600' }} />
          </Card>
        </Col>
        <Col span={6}>
          <Card>
            <Statistic title={t('cert.expiring_soon')} value={stats?.expiringSoon || 0} valueStyle={{ color: '#faad14' }} />
          </Card>
        </Col>
        <Col span={6}>
          <Card>
            <Statistic title={t('cert.expired')} value={stats?.expired || 0} valueStyle={{ color: '#cf1322' }} />
          </Card>
        </Col>
      </Row>

      {/* Action Buttons */}
      <Space style={{ marginBottom: 16 }}>
        <Button type="primary" icon={<PlusOutlined />} onClick={() => setUploadModalVisible(true)}>
          {t('cert.upload')}
        </Button>
        <Button icon={<ReloadOutlined />} onClick={handleRefreshStatus}>
          {t('cert.refresh_status')}
        </Button>
      </Space>

      {/* Certificate Table */}
      <Card>
        <Table
          loading={loading}
          dataSource={certificates}
          columns={columns}
          rowKey="id"
          pagination={{ pageSize: 10 }}
        />
      </Card>

      {/* Upload Modal */}
      <Modal
        title={t('cert.upload_title')}
        open={uploadModalVisible}
        onCancel={() => { 
          setUploadModalVisible(false); 
          form.resetFields();
          setCertFileContent('');
          setKeyFileContent('');
          setUploadMode('file');
        }}
        onOk={() => form.submit()}
        width={750}
      >
        <Form form={form} layout="vertical" onFinish={handleUpload}>
          <Form.Item name="certName" label={t('cert.cert_name')} rules={[{ required: true }]}>
            <Input placeholder={t('cert.cert_name_placeholder')} />
          </Form.Item>
          <Form.Item name="domain" label={t('cert.domain')} rules={[{ required: true }]}>
            <Input placeholder="example.com" />
          </Form.Item>
          <Form.Item name="certType" label={t('cert.type')} initialValue="PEM">
            <Select>
              <Select.Option value="PEM">PEM (证书 + 私钥)</Select.Option>
              <Select.Option value="JKS">JKS (Java Keystore)</Select.Option>
              <Select.Option value="P12">P12/PFX (PKCS#12)</Select.Option>
            </Select>
          </Form.Item>
          <Form.Item noStyle shouldUpdate>
            {({ getFieldValue }) => {
              const certType = getFieldValue('certType');
              if (certType === 'PEM') {
                return (
                  <>
                    <div style={{ marginBottom: 16 }}>
                      <Space>
                        <Button 
                          type={uploadMode === 'file' ? 'primary' : 'default'}
                          size="small"
                          icon={<FileAddOutlined />}
                          onClick={() => setUploadMode('file')}
                        >
                          {t('cert.file_upload') || '文件上传'}
                        </Button>
                        <Button 
                          type={uploadMode === 'text' ? 'primary' : 'default'}
                          size="small"
                          icon={<FileTextOutlined />}
                          onClick={() => setUploadMode('text')}
                        >
                          {t('cert.text_input') || '文本输入'}
                        </Button>
                      </Space>
                    </div>
                    
                    {uploadMode === 'file' ? (
                      <>
                        <Form.Item label={t('cert.cert_file') || '证书文件'} required>
                          <Dragger
                            accept=".crt,.cer,.pem"
                            maxCount={1}
                            beforeUpload={async (file) => {
                              const content = await readFileContent(file);
                              setCertFileContent(content);
                              message.success(file.name + ' ' + (t('cert.file_loaded') || '已加载'));
                              return false;
                            }}
                            onRemove={() => setCertFileContent('')}
                          >
                            <p className="ant-upload-drag-icon">
                              <SafetyCertificateOutlined style={{ fontSize: 48, color: '#1890ff' }} />
                            </p>
                            <p className="ant-upload-text">{t('cert.click_upload') || '点击或拖拽上传证书文件'}</p>
                            <p className="ant-upload-hint">.crt, .cer, .pem</p>
                          </Dragger>
                        </Form.Item>
                        <Form.Item label={t('cert.key_file') || '私钥文件'} required>
                          <Dragger
                            accept=".key,.pem"
                            maxCount={1}
                            beforeUpload={async (file) => {
                              const content = await readFileContent(file);
                              setKeyFileContent(content);
                              message.success(file.name + ' ' + (t('cert.file_loaded') || '已加载'));
                              return false;
                            }}
                            onRemove={() => setKeyFileContent('')}
                          >
                            <p className="ant-upload-drag-icon">
                              <SafetyCertificateOutlined style={{ fontSize: 48, color: '#52c41a' }} />
                            </p>
                            <p className="ant-upload-text">{t('cert.click_upload_key') || '点击或拖拽上传私钥文件'}</p>
                            <p className="ant-upload-hint">.key, .pem</p>
                          </Dragger>
                        </Form.Item>
                      </>
                    ) : (
                      <>
                        <Form.Item name="certContent" label={t('cert.cert_content')} rules={[{ required: uploadMode === 'text' }]}>
                          <TextArea rows={6} placeholder="-----BEGIN CERTIFICATE-----&#10;...&#10;-----END CERTIFICATE-----" />
                        </Form.Item>
                        <Form.Item name="keyContent" label={t('cert.key_content')} rules={[{ required: uploadMode === 'text' }]}>
                          <TextArea rows={6} placeholder="-----BEGIN PRIVATE KEY-----&#10;...&#10;-----END PRIVATE KEY-----" />
                        </Form.Item>
                      </>
                    )}
                  </>
                );
              } else {
                return (
                  <>
                    <Form.Item name="keystoreContent" label={t('cert.keystore_content')} rules={[{ required: true }]}>
                      <TextArea rows={6} placeholder={t('cert.keystore_placeholder')} />
                    </Form.Item>
                    <Form.Item name="password" label={t('cert.keystore_password')} rules={[{ required: true }]}>
                      <Input.Password placeholder={t('cert.password_placeholder')} />
                    </Form.Item>
                  </>
                );
              }
            }}
          </Form.Item>
        </Form>
      </Modal>

      {/* Detail Modal */}
      <Modal
        title={t('cert.detail_title')}
        open={detailModalVisible}
        onCancel={() => setDetailModalVisible(false)}
        footer={null}
        width={600}
      >
        {selectedCert && (
          <Descriptions bordered column={2}>
            <Descriptions.Item label={t('cert.cert_name')} span={2}>{selectedCert.certName}</Descriptions.Item>
            <Descriptions.Item label={t('cert.domain')} span={2}>{selectedCert.domain}</Descriptions.Item>
            <Descriptions.Item label={t('cert.type')}>{selectedCert.certType}</Descriptions.Item>
            <Descriptions.Item label={t('cert.status')}>{getStatusTag(selectedCert.status, selectedCert.daysToExpiry)}</Descriptions.Item>
            <Descriptions.Item label={t('cert.issuer')} span={2}>{selectedCert.issuer || '-'}</Descriptions.Item>
            <Descriptions.Item label={t('cert.serial')}>{selectedCert.serialNumber || '-'}</Descriptions.Item>
            <Descriptions.Item label={t('cert.days_to_expiry')}>{selectedCert.daysToExpiry} {t('cert.days')}</Descriptions.Item>
            <Descriptions.Item label={t('cert.valid_from')}>{selectedCert.validFrom ? new Date(selectedCert.validFrom).toLocaleString() : '-'}</Descriptions.Item>
            <Descriptions.Item label={t('cert.valid_to')}>{selectedCert.validTo ? new Date(selectedCert.validTo).toLocaleString() : '-'}</Descriptions.Item>
            <Descriptions.Item label={t('cert.enabled')}>{selectedCert.enabled ? t('cert.enabled') : t('cert.disabled')}</Descriptions.Item>
            <Descriptions.Item label={t('cert.auto_renew')}>{selectedCert.autoRenew ? t('cert.yes') : t('cert.no')}</Descriptions.Item>
          </Descriptions>
        )}
      </Modal>
    </div>
  );
};

// Statistic component
const Statistic: React.FC<{ title: string; value: number; valueStyle?: React.CSSProperties }> = ({ title, value, valueStyle }) => (
  <div>
    <Text type="secondary">{title}</Text>
    <div style={{ fontSize: 28, fontWeight: 600, ...valueStyle }}>{value}</div>
  </div>
);

export default CertificatePage;