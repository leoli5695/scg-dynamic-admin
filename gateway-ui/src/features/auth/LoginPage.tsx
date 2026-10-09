/**
 * Login page (rewritten). Visual identical to the legacy page;
 * auth flow now goes through AuthContext instead of raw localStorage writes.
 */
import React, { useCallback, useState } from 'react';
import {
  Button, Form, Input, Typography  } from 'antd';
import { message } from '../../utils/antdStatic';
import {
  ArrowsAltOutlined, ArrowRightOutlined, EyeOutlined,
  LockOutlined, SafetyCertificateOutlined, ThunderboltOutlined, UserOutlined,
} from '@ant-design/icons';
import { useNavigate } from 'react-router-dom';
import { useTranslation } from 'react-i18next';
import { useAuth } from './AuthContext';
import { ApiError } from '../../api/http';
import GatewayFlowBackground from './GatewayFlowBackground';
// Legacy stylesheet — will be moved into features/auth during final cleanup.
import './login.css';

const { Title, Text } = Typography;

interface LoginFormData {
  username: string;
  password: string;
}

const LoginPage: React.FC = () => {
  const [loading, setLoading] = useState(false);
  const navigate = useNavigate();
  const { t } = useTranslation();
  const { login } = useAuth();

  // Pointer-tracked spotlight inside the card (CSS vars consumed by .login-card::after)
  const onCardMouseMove = useCallback((e: React.MouseEvent<HTMLDivElement>) => {
    const rect = e.currentTarget.getBoundingClientRect();
    e.currentTarget.style.setProperty('--mx', `${e.clientX - rect.left}px`);
    e.currentTarget.style.setProperty('--my', `${e.clientY - rect.top}px`);
  }, []);

  const onFinish = async (values: LoginFormData) => {
    setLoading(true);
    try {
      const user = await login(values.username, values.password);
      message.success(t('login.success', { nickname: user.nickname }));
      setTimeout(() => navigate('/', { replace: true }), 500);
    } catch (error) {
      const msg = error instanceof ApiError && error.message
        ? error.message
        : t('login.failed');
      message.error(msg);
    } finally {
      setLoading(false);
    }
  };

  return (
    <div className="login-container">
      {/* Premium animated background */}
      <div className="login-background">
        <div className="bg-aurora bg-aurora-1" />
        <div className="bg-aurora bg-aurora-2" />
        <div className="bg-aurora bg-aurora-3" />
        <div className="bg-grid" />
        <GatewayFlowBackground curveCount={9} packetsPerCurve={3} opacity={0.9} />
        <div className="bg-horizon" />
        <div className="bg-spotlight" />
        <div className="bg-grain" />
        <div className="bg-vignette" />
      </div>

      {/* Login card - Glassmorphism */}
      <div className="login-card" onMouseMove={onCardMouseMove}>
        <span className="card-ring" aria-hidden="true" />
        <div className="login-brand">
          <Title level={2} className="brand-title">
            {t('login.title')}
          </Title>
          <span className="brand-subtitle">{t('login.subtitle')}</span>
          <div className="brand-chips">
            <span className="chip"><ThunderboltOutlined />{t('login.chip.ha')}</span>
            <span className="chip"><EyeOutlined />{t('login.chip.observability')}</span>
            <span className="chip"><SafetyCertificateOutlined />{t('login.chip.security')}</span>
            <span className="chip"><ArrowsAltOutlined />{t('login.chip.elastic')}</span>
          </div>
        </div>

        <div className="login-form-section">
          <Form
            name="login"
            layout="vertical"
            onFinish={onFinish}
            autoComplete="off"
            className="login-form"
          >
            <Form.Item
              name="username"
              rules={[
                { required: true, message: t('login.username_required') },
                { min: 3, message: t('login.username_min_length') },
              ]}
            >
              <Input
                prefix={<UserOutlined className="input-icon" />}
                placeholder={t('login.username_placeholder')}
                size="large"
                className="modern-input"
              />
            </Form.Item>

            <Form.Item
              name="password"
              rules={[
                { required: true, message: t('login.password_required') },
                { min: 6, message: t('login.password_min_length') },
              ]}
            >
              <Input.Password
                prefix={<LockOutlined className="input-icon" />}
                placeholder={t('login.password_placeholder')}
                size="large"
                className="modern-input"
              />
            </Form.Item>

            <Form.Item className="form-button">
              <Button
                type="primary"
                htmlType="submit"
                loading={loading}
                size="large"
                block
                className="login-button"
              >
                <span className="login-button-label">{t('login.button')}</span>
                <ArrowRightOutlined className="login-button-arrow" />
              </Button>
            </Form.Item>
          </Form>
        </div>
      </div>

      <div className="login-page-footer">
        <Text className="page-footer-copyright">
          ©
          {' '}
          {new Date().getFullYear()}
          {' '}
          leoli. All rights reserved.
        </Text>
      </div>
    </div>
  );
};

export default LoginPage;
