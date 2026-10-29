/**
 * Edit-request modal — shows the prepared replayable request and lets the
 * user tweak path/query/headers/body before executing. Mounted with a key
 * per trace so local edit state resets for each prepared request.
 */
import React, { useState } from 'react';
import ResourceEditor from '../../instance-detail/ResourceEditor';
import { Button, Col, Divider, Form, Input, Row, Select, Space, Statistic, Typography } from 'antd';
import { EditOutlined, ThunderboltOutlined } from '@ant-design/icons';
import { useTranslation } from 'react-i18next';
import type { ReplayableRequest, ReplayOptions } from '../../../types/replay';

const { Text } = Typography;
const { TextArea } = Input;

interface EditRequestModalProps {
  open: boolean;
  request: ReplayableRequest | null;
  replaying: boolean;
  onCancel: () => void;
  onExecute: (quick: boolean, options: ReplayOptions) => void;
}

const EditRequestModal: React.FC<EditRequestModalProps> = ({
  open,
  request,
  replaying,
  onCancel,
  onExecute,
}) => {
  const { t } = useTranslation();
  const [form] = Form.useForm();

  const [editPath, setEditPath] = useState(request?.path || '');
  const [editQueryString, setEditQueryString] = useState(request?.queryString || '');
  const [editHeaders, setEditHeaders] = useState<Record<string, string>>(request?.headers || {});
  const [editBody, setEditBody] = useState(request?.requestBody || '');
  const [newHeaderKey, setNewHeaderKey] = useState('');
  const [newHeaderValue, setNewHeaderValue] = useState('');

  const addHeader = () => {
    if (newHeaderKey.trim()) {
      setEditHeaders({ ...editHeaders, [newHeaderKey.trim()]: newHeaderValue });
      setNewHeaderKey('');
      setNewHeaderValue('');
    }
  };

  const removeHeader = (key: string) => {
    const nextHeaders = { ...editHeaders };
    delete nextHeaders[key];
    setEditHeaders(nextHeaders);
  };

  const handleExecute = (quick: boolean) => {
    if (!request) return;
    const options: ReplayOptions = quick
      ? {}
      : {
          modifiedPath: editPath !== request.path ? editPath : undefined,
          modifiedQueryString:
            editQueryString !== request.queryString ? editQueryString : undefined,
          modifiedHeaders: editHeaders,
          // The server merges modifiedHeaders; omissions alone do not remove captured credentials.
          // Compare case-insensitively so deleting and re-adding a header with new casing keeps it.
          removedHeaders: Object.keys(request.headers || {}).filter(
            key => !Object.keys(editHeaders).some(editedKey => editedKey.toLowerCase() === key.toLowerCase()),
          ),
          modifiedBody: editBody !== request.requestBody ? editBody : undefined,
          compareWithOriginal: true,
        };
    onExecute(quick, options);
  };

  if (!request || !open) return null;
  return (
    <ResourceEditor
      className="console-replay-editor"
      open={open}
      title={
        <div className="modal-title-wrapper">
          <div className="modal-icon-wrapper">
            <EditOutlined />
          </div>
          <div className="modal-title-text">
            <div className="modal-title">{t('replay.edit_request')}</div>
            <div className="modal-subtitle">
              {request.method} {request.path}
            </div>
          </div>
        </div>
      }
      form={form}
      initialValues={{ path: editPath }}
      submitting={replaying}
      onCancel={onCancel}
      onFinish={async () => handleExecute(false)}
      submitLabel={t('replay.execute')}
      footerExtra={
        <Button
          disabled={replaying}
          icon={<ThunderboltOutlined />}
          onClick={() => handleExecute(true)}
        >
          {t('replay.quick_replay')}
        </Button>
      }
      sections={[
        {
          label: t('strategy.basic_info'),
          fields: ['path'],
          content: (
            <div className="form-section-modern">
              {' '}
              {/* Original request info */}
              <Row gutter={16} style={{ marginBottom: 16 }}>
                <Col span={12}>
                  <Statistic
                    title={t('replay.original_status')}
                    value={request.originalStatusCode}
                    valueStyle={{
                      color: request.originalStatusCode >= 400 ? '#ff4d4f' : '#52c41a',
                    }}
                  />
                </Col>
                <Col span={12}>
                  <Statistic
                    title={t('replay.original_latency')}
                    value={request.originalLatencyMs}
                    suffix="ms"
                  />
                </Col>
              </Row>
              <Divider />
              {/* Method & path */}
              <Row gutter={16}>
                <Col span={4}>
                  <Select
                    value={request.method}
                    style={{ width: '100%' }}
                    disabled
                    options={[
                      { value: 'GET', label: 'GET' },
                      { value: 'POST', label: 'POST' },
                      { value: 'PUT', label: 'PUT' },
                      { value: 'DELETE', label: 'DELETE' },
                      { value: 'PATCH', label: 'PATCH' },
                    ]}
                  />
                </Col>
                <Col span={20}>
                  <Form.Item name="path" rules={[{ required: true }]}>
                    <Input
                      aria-label="Path"
                      placeholder="/api/users"
                      value={editPath}
                      onChange={(e) => setEditPath(e.target.value)}
                      addonBefore="Path"
                    />
                  </Form.Item>
                </Col>
              </Row>
              <div style={{ marginTop: 12 }}>
                <Input
                  aria-label="Query"
                  placeholder={t('replay.query_string')}
                  value={editQueryString}
                  onChange={(e) => setEditQueryString(e.target.value)}
                  addonBefore="Query"
                />
              </div>
            </div>
          ),
        },
        {
          label: t('replay.headers'),
          fields: [],
          content: (
            <div className="form-section-modern">
              {' '}
              {/* Headers */}
              <Divider>{t('replay.headers')}</Divider>
              <Space.Compact style={{ width: '100%', marginBottom: 8 }}>
                <Input
                  placeholder={t('replay.header_name')}
                  value={newHeaderKey}
                  onChange={(e) => setNewHeaderKey(e.target.value)}
                  style={{ width: '35%' }}
                />
                <Input
                  placeholder={t('replay.header_value')}
                  value={newHeaderValue}
                  onChange={(e) => setNewHeaderValue(e.target.value)}
                  style={{ width: '55%' }}
                />
                <Button type="primary" onClick={addHeader}>
                  +
                </Button>
              </Space.Compact>
              <div style={{ maxHeight: 150, overflowY: 'auto', marginBottom: 12 }}>
                {Object.entries(editHeaders).map(([key, value]) => (
                  <div
                    key={key}
                    style={{
                      display: 'flex',
                      justifyContent: 'space-between',
                      padding: '4px 8px',
                      background: 'rgba(255,255,255,0.05)',
                      marginBottom: 4,
                      borderRadius: 4,
                    }}
                  >
                    <Text code>{key}</Text>
                    <Space>
                      <Text>{value}</Text>
                      <Button size="small" danger onClick={() => removeHeader(key)}>
                        ×
                      </Button>
                    </Space>
                  </div>
                ))}
              </div>
            </div>
          ),
        },
        {
          label: t('replay.request_body'),
          fields: [],
          content: (
            <div className="form-section-modern">
              {' '}
              {/* Body */}
              {['POST', 'PUT', 'PATCH'].includes(request.method) && (
                <>
                  <Divider>{t('replay.request_body')}</Divider>
                  <TextArea
                    rows={8}
                    value={editBody}
                    onChange={(e) => setEditBody(e.target.value)}
                    style={{ fontFamily: 'monospace' }}
                  />
                </>
              )}
              {!['POST', 'PUT', 'PATCH'].includes(request.method) && (
                <Text type="secondary">
                  {request.method} · {t('replay.request_body')} —
                </Text>
              )}
            </div>
          ),
        },
      ]}
    />
  );
};

export default EditRequestModal;
