/**
 * Jar upload modal — POST /api/plugins/upload (multipart, instanceId query).
 */
import React, { useState } from 'react';
import { Alert, Modal, Upload } from 'antd';
import { message } from '../../../utils/antdStatic';
import { UploadOutlined } from '@ant-design/icons';
import type { UploadProps } from 'antd';
import { useTranslation } from 'react-i18next';
import { uploadPlugin } from '../../../api/plugins';
import { ApiError } from '../../../api/http';

interface UploadPluginModalProps {
  instanceId?: string;
  onClose: () => void;
  onUploaded: () => void;
}

const UploadPluginModal: React.FC<UploadPluginModalProps> = ({
  instanceId,
  onClose,
  onUploaded,
}) => {
  const { t } = useTranslation();
  const [uploading, setUploading] = useState(false);

  const uploadProps: UploadProps = {
    name: 'file',
    accept: '.jar',
    showUploadList: false,
    customRequest: async (options) => {
      const { file, onSuccess, onError } = options;
      setUploading(true);
      try {
        const plugin = await uploadPlugin(file as File, instanceId);
        message.success(t('plugins.upload_success'));
        onSuccess?.(plugin);
        onUploaded();
        onClose();
      } catch (error) {
        const fallback = t('plugins.upload_failed');
        message.error(error instanceof ApiError && error.message ? error.message : fallback);
        onError?.(error as Error);
      } finally {
        setUploading(false);
      }
    },
  };

  return (
    <Modal
      className="console-picker plugin-upload-modal"
      title={t('plugins.upload_title')}
      mask={{ closable: !uploading }}
      closable={!uploading}
      keyboard={!uploading}
      open
      onCancel={onClose}
      footer={null}
      destroyOnHidden
    >
      <Alert
        message={t('plugins.upload_requirements')}
        description={
          <ul style={{ margin: 0, paddingLeft: 20 }}>
            <li>{t('plugins.upload_requirement_1')}</li>
            <li>{t('plugins.upload_requirement_2')}</li>
            <li>{t('plugins.upload_requirement_3')}</li>
          </ul>
        }
        type="info"
        showIcon
        style={{ marginBottom: 16 }}
      />
      <Upload.Dragger {...uploadProps} disabled={uploading}>
        <p className="ant-upload-drag-icon">
          <UploadOutlined />
        </p>
        <p className="ant-upload-text">{t('plugins.upload_drag')}</p>
        <p className="ant-upload-hint">{t('plugins.upload_hint')}</p>
      </Upload.Dragger>
    </Modal>
  );
};

export default UploadPluginModal;
