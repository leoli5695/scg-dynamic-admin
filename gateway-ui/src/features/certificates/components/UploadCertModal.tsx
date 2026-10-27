/**
 * Upload SSL certificate modal.
 * PEM: file or text input → POST /api/ssl/pem (JSON body).
 * JKS/P12: base64 keystore text → POST /api/ssl/keystore (query params).
 */
import React, { useState } from 'react';
import ResourceEditor from '../../instance-detail/ResourceEditor';
import { Button, Form, Input, Select, Space, Upload } from 'antd';
import { message } from '../../../utils/antdStatic';
import { FileAddOutlined, FileTextOutlined, SafetyCertificateOutlined } from '@ant-design/icons';
import { useTranslation } from 'react-i18next';
import type { CertificateType } from '../../../types/certificate';

const { TextArea } = Input;
const { Dragger } = Upload;

/** Discriminated payload consumed by the page's submit handler. */
export type CertificateUploadPayload =
  | { kind: 'pem'; certName: string; domain: string; certContent: string; keyContent: string }
  | {
      kind: 'keystore';
      certName: string;
      domain: string;
      certType: 'JKS' | 'P12';
      keystoreContent: string;
      password: string;
    };

interface UploadCertModalProps {
  onCancel: () => void;
  onSubmit: (payload: CertificateUploadPayload) => Promise<void>;
}

const readFileContent = (file: File): Promise<string> =>
  new Promise((resolve, reject) => {
    const reader = new FileReader();
    reader.onload = () => resolve(reader.result as string);
    reader.onerror = () => reject(new Error('Failed to read file'));
    reader.readAsText(file);
  });

interface FormValues {
  certName: string;
  domain: string;
  certType: CertificateType;
  certContent?: string;
  keyContent?: string;
  keystoreContent?: string;
  password?: string;
}

const UploadCertModal: React.FC<UploadCertModalProps> = ({ onCancel, onSubmit }) => {
  const { t } = useTranslation();
  const [form] = Form.useForm<FormValues>();
  const [submitting, setSubmitting] = useState(false);
  const [uploadMode, setUploadMode] = useState<'file' | 'text'>('file');
  const [certFileContent, setCertFileContent] = useState('');
  const [keyFileContent, setKeyFileContent] = useState('');
  const [certType, setCertType] = useState<CertificateType>('PEM');

  const handleFinish = async (values: FormValues) => {
    let payload: CertificateUploadPayload;
    if (values.certType === 'PEM') {
      const certContent = uploadMode === 'file' ? certFileContent : values.certContent;
      const keyContent = uploadMode === 'file' ? keyFileContent : values.keyContent;
      if (!certContent || !keyContent) {
        message.error(t('cert.file_required'));
        return;
      }
      payload = {
        kind: 'pem',
        certName: values.certName,
        domain: values.domain,
        certContent,
        keyContent,
      };
    } else {
      if (!values.keystoreContent || !values.password) return;
      payload = {
        kind: 'keystore',
        certName: values.certName,
        domain: values.domain,
        certType: values.certType,
        keystoreContent: values.keystoreContent,
        password: values.password,
      };
    }
    setSubmitting(true);
    try {
      await onSubmit(payload);
    } finally {
      setSubmitting(false);
    }
  };

  const loadFile = (setter: (content: string) => void) => async (file: File) => {
    const content = await readFileContent(file);
    setter(content);
    message.success(`${file.name} ${t('cert.file_loaded')}`);
    return false;
  };

  return (
    <ResourceEditor
      title={
        <div className="modal-title-wrapper">
          <div className="modal-icon-wrapper">
            <SafetyCertificateOutlined />
          </div>
          <div className="modal-title-text">
            <div className="modal-title">{t('cert.upload_title')}</div>
            <div className="modal-subtitle">
              {t('cert.cert_name')} · {t('cert.domain')}
            </div>
          </div>
        </div>
      }
      form={form}
      initialValues={{ certType: 'PEM' }}
      onFinish={handleFinish}
      submitting={submitting}
      onCancel={onCancel}
      submitLabel={t('common.submit')}
      sections={[
        {
          label: t('strategy.basic_info'),
          fields: ['certName', 'domain', 'certType'],
          content: (
            <div className="form-section-modern">
              <Form.Item name="certName" label={t('cert.cert_name')} rules={[{ required: true }]}>
                <Input placeholder={t('cert.cert_name_placeholder')} />
              </Form.Item>
              <Form.Item name="domain" label={t('cert.domain')} rules={[{ required: true }]}>
                <Input placeholder="example.com" />
              </Form.Item>
              <Form.Item name="certType" label={t('cert.type')}>
                <Select onChange={(value: CertificateType) => setCertType(value)}>
                  <Select.Option value="PEM">{t('cert.type_pem')}</Select.Option>
                  <Select.Option value="JKS">{t('cert.type_jks')}</Select.Option>
                  <Select.Option value="P12">{t('cert.type_p12')}</Select.Option>
                </Select>
              </Form.Item>
            </div>
          ),
        },
        {
          label: t('cert.cert_content'),
          content: (
            <div className="form-section-modern">
              {certType === 'PEM' ? (
                <>
                  <div style={{ marginBottom: 16 }}>
                    <Space>
                      <Button
                        type={uploadMode === 'file' ? 'primary' : 'default'}
                        size="small"
                        icon={<FileAddOutlined />}
                        onClick={() => setUploadMode('file')}
                      >
                        {t('cert.file_upload')}
                      </Button>
                      <Button
                        type={uploadMode === 'text' ? 'primary' : 'default'}
                        size="small"
                        icon={<FileTextOutlined />}
                        onClick={() => setUploadMode('text')}
                      >
                        {t('cert.text_input')}
                      </Button>
                    </Space>
                  </div>
                  {uploadMode === 'file' ? (
                    <>
                      <Form.Item label={t('cert.cert_file')} required>
                        <Dragger
                          accept=".crt,.cer,.pem"
                          maxCount={1}
                          beforeUpload={loadFile(setCertFileContent)}
                          onRemove={() => setCertFileContent('')}
                        >
                          <p className="ant-upload-drag-icon">
                            <SafetyCertificateOutlined style={{ fontSize: 48, color: '#1890ff' }} />
                          </p>
                          <p className="ant-upload-text">{t('cert.click_upload')}</p>
                          <p className="ant-upload-hint">.crt, .cer, .pem</p>
                        </Dragger>
                      </Form.Item>
                      <Form.Item label={t('cert.key_file')} required>
                        <Dragger
                          accept=".key,.pem"
                          maxCount={1}
                          beforeUpload={loadFile(setKeyFileContent)}
                          onRemove={() => setKeyFileContent('')}
                        >
                          <p className="ant-upload-drag-icon">
                            <SafetyCertificateOutlined style={{ fontSize: 48, color: '#52c41a' }} />
                          </p>
                          <p className="ant-upload-text">{t('cert.click_upload_key')}</p>
                          <p className="ant-upload-hint">.key, .pem</p>
                        </Dragger>
                      </Form.Item>
                    </>
                  ) : (
                    <>
                      <Form.Item
                        name="certContent"
                        label={t('cert.cert_content')}
                        rules={[{ required: true }]}
                      >
                        <TextArea
                          rows={6}
                          placeholder={
                            '-----BEGIN CERTIFICATE-----\n...\n-----END CERTIFICATE-----'
                          }
                        />
                      </Form.Item>
                      <Form.Item
                        name="keyContent"
                        label={t('cert.key_content')}
                        rules={[{ required: true }]}
                      >
                        <TextArea
                          rows={6}
                          placeholder={
                            '-----BEGIN PRIVATE KEY-----\n...\n-----END PRIVATE KEY-----'
                          }
                        />
                      </Form.Item>
                    </>
                  )}
                </>
              ) : (
                <>
                  <Form.Item
                    name="keystoreContent"
                    label={t('cert.keystore_content')}
                    rules={[{ required: true }]}
                  >
                    <TextArea rows={6} placeholder={t('cert.keystore_placeholder')} />
                  </Form.Item>
                  <Form.Item
                    name="password"
                    label={t('cert.keystore_password')}
                    rules={[{ required: true }]}
                  >
                    <Input.Password placeholder={t('cert.password_placeholder')} />
                  </Form.Item>
                </>
              )}
            </div>
          ),
        },
      ]}
    />
  );
};

export default UploadCertModal;
