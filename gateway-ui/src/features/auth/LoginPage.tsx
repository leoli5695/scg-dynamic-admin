/**
 * Login page (rewritten). Visual identical to the legacy page;
 * auth flow now goes through AuthContext instead of raw localStorage writes.
 */
import React, { useState } from 'react';
import {
  Button, Form, Input, Typography  } from 'antd';
import { message } from '../../utils/antdStatic';
import {
  ApiOutlined, CloudOutlined, LockOutlined, UserOutlined,
} from '@ant-design/icons';
import { useNavigate } from 'react-router-dom';
import { useTranslation } from 'react-i18next';
import { useAuth } from './AuthContext';
import { ApiError } from '../../api/http';
import TechRaysBackground from './TechRaysBackground';
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
        <div className="bg-ambient-1" />
        <div className="bg-ambient-2" />
        <div className="bg-ambient-3" />
        <div className="gradient-orb gradient-orb-1" />
        <div className="gradient-orb gradient-orb-2" />
        <div className="gradient-orb gradient-orb-3" />
        <div className="bg-grid" />
        <div className="particles">
          {Array.from({ length: 9 }).map((_, i) => (
            <div key={i} className="particle" />
          ))}
        </div>
        <TechRaysBackground rayCount={80} particleCount={30} opacity={0.3} />
      </div>

      {/* Login card - Glassmorphism */}
      <div className="login-card">
        <div className="login-brand">
          <div className="brand-icon-wrapper">
            <div className="brand-icon-content">
              <ApiOutlined className="brand-icon-main" />
              <CloudOutlined className="brand-icon-bg" />
            </div>
          </div>
          <Title level={2} className="brand-title">
            {t('login.title')}
          </Title>
          <span className="brand-subtitle">{t('login.subtitle')}</span>
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
                {t('login.button')}
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
