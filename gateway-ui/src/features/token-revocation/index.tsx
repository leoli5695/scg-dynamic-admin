/**
 * Data-plane JWT revocation page.
 * Backend: controller/GatewayTokenRevocationController.java
 * (/api/gateway/jwt-revocations).
 *
 * The blacklist lives in the Redis shared by the whole gateway deployment, so
 * actions here are not instance-scoped even though the page sits inside the
 * instance console - the copy says so explicitly.
 */
import React, { useMemo, useState } from 'react';
import {
  Alert, Button, Card, Col, Descriptions, Form, Input, InputNumber, Popconfirm,
  Radio, Row, Space, Switch, Tag, Typography,
} from 'antd';
import { message } from '../../utils/antdStatic';
import { SafetyCertificateOutlined, StopOutlined } from '@ant-design/icons';
import { useTranslation } from 'react-i18next';
import { useRequest } from '../../hooks/useRequest';
import { ApiError } from '../../api/http';
import {
  getRevocationSwitch, liftJwtRevocation, revokeJwtToken, setRevocationSwitch,
} from '../../api/jwtRevocation';
import type { RevocationResult } from '../../api/jwtRevocation';
// Shared page-header / card styles live in the strategies feature.
import '../strategies/strategies-premium.css';

const { Title, Paragraph, Text } = Typography;

type TargetMode = 'token' | 'jti';

interface LastOperation {
  kind: 'revoke' | 'lift';
  data: RevocationResult;
}

const errorMessage = (error: unknown, fallbackKey: string, t: (k: string) => string): string => (
  error instanceof ApiError && error.message ? error.message : t(fallbackKey)
);

const TokenRevocationPage: React.FC = () => {
  const { t } = useTranslation();

  const [mode, setMode] = useState<TargetMode>('token');
  const [token, setToken] = useState('');
  const [jti, setJti] = useState('');
  const [ttlSeconds, setTtlSeconds] = useState<number | null>(null);
  const [submitting, setSubmitting] = useState(false);
  const [lastOperation, setLastOperation] = useState<LastOperation | null>(null);

  const switchRequest = useRequest((signal) => getRevocationSwitch({ signal }), {
    onError: (error) => {
      message.error(errorMessage(error, 'jwt_revocation.switch_load_failed', t));
    },
  });
  // Absent switch state is shown as enabled, matching the gateway default.
  const checkEnabled = switchRequest.data?.enabled ?? true;

  const target = useMemo(
    () => (mode === 'token' ? token.trim() : jti.trim()),
    [mode, token, jti],
  );

  const handleToggle = async (next: boolean) => {
    try {
      await setRevocationSwitch(next);
      message.success(t('jwt_revocation.switch_updated'));
      switchRequest.run();
    } catch (error) {
      message.error(errorMessage(error, 'jwt_revocation.switch_update_failed', t));
    }
  };

  const runAction = async (kind: 'revoke' | 'lift') => {
    if (!target) {
      message.warning(t('jwt_revocation.input_required'));
      return;
    }
    setSubmitting(true);
    try {
      const body = kind === 'revoke'
        ? {
          ...(mode === 'token' ? { token: target } : { jti: target }),
          ...(ttlSeconds ? { ttlSeconds } : {}),
        }
        : (mode === 'token' ? { token: target } : { jti: target });
      const data = kind === 'revoke'
        ? await revokeJwtToken(body)
        : await liftJwtRevocation(body);
      setLastOperation({ kind, data });
      message.success(t(kind === 'revoke'
        ? 'jwt_revocation.revoke_success'
        : 'jwt_revocation.lift_success'));
    } catch (error) {
      message.error(errorMessage(error, 'jwt_revocation.action_failed', t));
    } finally {
      setSubmitting(false);
    }
  };

  return (
    <div className="strategies-page">
      {/* Header */}
      <div className="page-header-modern">
        <div className="page-header-left">
          <SafetyCertificateOutlined className="page-icon" />
          <Title level={2}>{t('jwt_revocation.title')}</Title>
        </div>
      </div>

      <Paragraph type="secondary">{t('jwt_revocation.description')}</Paragraph>

      <Space direction="vertical" size={16} style={{ width: '100%' }}>
        <Alert
          type="info"
          showIcon
          message={t('jwt_revocation.scope_note')}
          description={t('jwt_revocation.redis_note')}
        />

        {!checkEnabled && (
          <Alert
            type="warning"
            showIcon
            message={t('jwt_revocation.switch_off')}
            description={t('jwt_revocation.switch_desc')}
          />
        )}

        {/* Runtime switch */}
        <Card title={t('jwt_revocation.switch_title')}>
          <Row gutter={16} align="middle">
            <Col xs={24} md={6}>
              <Space>
                <Switch
                  checked={checkEnabled}
                  loading={switchRequest.loading}
                  onChange={handleToggle}
                />
                <Tag color={checkEnabled ? 'success' : 'default'}>
                  {checkEnabled
                    ? t('jwt_revocation.switch_on')
                    : t('jwt_revocation.switch_off')}
                </Tag>
              </Space>
            </Col>
            <Col xs={24} md={18}>
              <Text type="secondary">{t('jwt_revocation.switch_desc')}</Text>
            </Col>
          </Row>
        </Card>

        {/* Revoke / lift form */}
        <Card title={t('jwt_revocation.form_title')}>
          <Form layout="vertical">
            <Row gutter={16}>
              <Col xs={24} md={6}>
                <Form.Item label={t('jwt_revocation.mode_label')}>
                  <Radio.Group
                    value={mode}
                    onChange={(e) => setMode(e.target.value as TargetMode)}
                    optionType="button"
                    buttonStyle="solid"
                  >
                    <Radio.Button value="token">{t('jwt_revocation.mode_token')}</Radio.Button>
                    <Radio.Button value="jti">{t('jwt_revocation.mode_jti')}</Radio.Button>
                  </Radio.Group>
                </Form.Item>
              </Col>
              <Col xs={24} md={18}>
                {mode === 'token' ? (
                  <Form.Item label={t('jwt_revocation.token_label')}>
                    <Input.TextArea
                      rows={3}
                      value={token}
                      onChange={(e) => setToken(e.target.value)}
                      placeholder={t('jwt_revocation.token_placeholder')}
                      style={{ fontFamily: 'monospace' }}
                    />
                  </Form.Item>
                ) : (
                  <Form.Item label={t('jwt_revocation.jti_label')}>
                    <Input
                      value={jti}
                      onChange={(e) => setJti(e.target.value)}
                      placeholder={t('jwt_revocation.jti_placeholder')}
                      allowClear
                    />
                  </Form.Item>
                )}
              </Col>
            </Row>

            <Row gutter={16} align="bottom">
              <Col xs={24} md={6}>
                <Form.Item label={t('jwt_revocation.ttl_label')}>
                  <InputNumber
                    min={60}
                    max={604800}
                    value={ttlSeconds}
                    onChange={(value) => setTtlSeconds(value)}
                    placeholder={t('jwt_revocation.ttl_placeholder')}
                    style={{ width: '100%' }}
                    disabled={mode === 'jti'}
                  />
                </Form.Item>
              </Col>
              <Col xs={24} md={18}>
                <Form.Item label=" ">
                  <Space>
                    <Popconfirm
                      title={t('jwt_revocation.revoke_confirm_title')}
                      description={t('jwt_revocation.revoke_confirm_desc')}
                      okText={t('jwt_revocation.revoke')}
                      onConfirm={() => runAction('revoke')}
                      disabled={!target}
                    >
                      <Button
                        type="primary"
                        danger
                        icon={<StopOutlined />}
                        loading={submitting}
                        disabled={!target}
                      >
                        {t('jwt_revocation.revoke')}
                      </Button>
                    </Popconfirm>
                    <Popconfirm
                      title={t('jwt_revocation.lift_confirm_title')}
                      description={t('jwt_revocation.lift_confirm_desc')}
                      okText={t('jwt_revocation.lift')}
                      onConfirm={() => runAction('lift')}
                      disabled={!target}
                    >
                      <Button
                        icon={<SafetyCertificateOutlined />}
                        loading={submitting}
                        disabled={!target}
                      >
                        {t('jwt_revocation.lift')}
                      </Button>
                    </Popconfirm>
                  </Space>
                </Form.Item>
              </Col>
            </Row>
          </Form>
        </Card>

        {/* Last operation */}
        <Card title={t('jwt_revocation.result_title')}>
          {lastOperation ? (
            <Descriptions column={1} size="small" bordered>
              <Descriptions.Item label={t('jwt_revocation.mode_label')}>
                <Tag color={lastOperation.kind === 'revoke' ? 'error' : 'success'}>
                  {lastOperation.kind === 'revoke'
                    ? t('jwt_revocation.revoke')
                    : t('jwt_revocation.lift')}
                </Tag>
              </Descriptions.Item>
              <Descriptions.Item label={t('jwt_revocation.result_ids')}>
                <Space direction="vertical" size={4}>
                  {lastOperation.data.ids.map((id) => (
                    <Text key={id} code copyable style={{ fontSize: 12 }}>{id}</Text>
                  ))}
                </Space>
              </Descriptions.Item>
              {lastOperation.kind === 'revoke' && (
                <Descriptions.Item label={t('jwt_revocation.result_ttl')}>
                  {t('jwt_revocation.result_ttl_value', { ttl: lastOperation.data.ttlSeconds })}
                </Descriptions.Item>
              )}
            </Descriptions>
          ) : (
            <Text type="secondary">{t('jwt_revocation.result_empty')}</Text>
          )}
        </Card>
      </Space>
    </div>
  );
};

export default TokenRevocationPage;
