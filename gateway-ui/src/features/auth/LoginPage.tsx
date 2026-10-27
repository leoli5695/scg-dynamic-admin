import React, { useState } from 'react';
import { Button, Form, Input, Typography } from 'antd';
import { message } from '../../utils/antdStatic';
import {
  ApiOutlined,
  ArrowRightOutlined,
  EyeOutlined,
  LockOutlined,
  SafetyCertificateOutlined,
  ThunderboltOutlined,
  UserOutlined,
} from '@ant-design/icons';
import { useNavigate } from 'react-router-dom';
import { useTranslation } from 'react-i18next';
import { useAuth } from './AuthContext';
import { ApiError } from '../../api/http';
import LoginParticles from './LoginParticles';
import './login.css';

const { Title, Text } = Typography;

interface LoginFormData {
  username: string;
  password: string;
}

const LoginPage: React.FC = () => {
  const [loading, setLoading] = useState(false);
  const [capsLock, setCapsLock] = useState(false);
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
      const msg = error instanceof ApiError && error.message ? error.message : t('login.failed');
      message.error(msg);
    } finally {
      setLoading(false);
    }
  };

  return (
    <div className="login-container">
      <div className="login-background" aria-hidden="true">
        <div className="bg-grid" />
        <svg className="login-background-flow" viewBox="0 0 1440 900" preserveAspectRatio="none">
          <defs>
            <linearGradient id="login-flow-stroke" x1="0" y1="0" x2="1" y2="0">
              <stop stopColor="#6998ef" stopOpacity="0" />
              <stop offset="0.4" stopColor="#6998ef" stopOpacity="0.13" />
              <stop offset="1" stopColor="#a492ea" stopOpacity="0" />
            </linearGradient>
          </defs>
          <g fill="none" stroke="url(#login-flow-stroke)" strokeWidth="1">
            <path d="M-100 610 C320 650 470 340 800 450 S1160 680 1540 400" />
            <path d="M-100 420 C260 390 410 630 740 530 S1180 300 1540 420" />
            <path d="M-100 760 C350 680 520 500 800 530 S1170 610 1540 700" />
          </g>
        </svg>
        <LoginParticles />
      </div>

      <header className="login-header">
        <div className="login-wordmark">
          <span className="login-logo">
            <ApiOutlined />
          </span>
          <span>
            API GATEWAY<span className="wordmark-caption">{t('login.subtitle')}</span>
          </span>
        </div>
        <span className="login-header-tag">{t('login.header_tag')}</span>
      </header>

      <main className="login-main">
        <section className="login-intro" aria-labelledby="login-hero-title">
          <span className="login-eyebrow">
            <span />
            {t('login.eyebrow')}
          </span>
          <h1 id="login-hero-title">
            {t('login.hero_line1')}
            <br />
            <span>{t('login.hero_line2')}</span>
          </h1>
          <p className="login-description">{t('login.description')}</p>
          <div className="login-topology" aria-hidden="true">
            <div className="topology-heading">
              <span>{t('login.topology_title')}</span>
              <span className="topology-caption">TRAFFIC FLOW</span>
            </div>
            <div className="topology-flow">
              <div className="topology-sources">
                <span>REST API</span>
                <span>AI / LLM</span>
                <span>WebSocket</span>
              </div>
              <svg className="topology-lines" viewBox="0 0 460 150" preserveAspectRatio="none">
                <path d="M0 25 H40 Q65 25 65 50 V55 Q65 75 95 75 H365 Q395 75 395 55 V50 Q395 25 420 25 H460 M0 75 H460 M0 125 H40 Q65 125 65 100 V95 Q65 75 95 75 M365 75 Q395 75 395 95 V100 Q395 125 420 125 H460" />
              </svg>
              <div className="topology-core">
                <ApiOutlined />
                <span>GATEWAY</span>
              </div>
              <div className="topology-services">
                <span>Service A</span>
                <span>Service B</span>
                <span>Service C</span>
              </div>
            </div>
            <div className="topology-legend">
              <span />
              <span>{t('login.topology_note')}</span>
            </div>
          </div>
          <div className="login-capabilities">
            <span>
              <ThunderboltOutlined />
              {t('login.chip.ha')}
            </span>
            <span>
              <EyeOutlined />
              {t('login.chip.observability')}
            </span>
            <span>
              <SafetyCertificateOutlined />
              {t('login.chip.security')}
            </span>
          </div>
        </section>

        <section className="login-card" aria-labelledby="login-form-title">
          <div className="login-brand">
            <div className="login-card-heading">
              <span className="login-card-icon" aria-hidden="true">
                <SafetyCertificateOutlined />
              </span>
              <span className="login-card-eyebrow">{t('login.card_eyebrow')}</span>
              <span className="login-card-badge">{t('login.identity_badge')}</span>
            </div>
            <Title level={2} id="login-form-title" className="brand-title">
              {t('login.welcome')}
            </Title>
            <span className="brand-subtitle">{t('login.form_description')}</span>
          </div>

          <div className="login-form-section">
            <div className="login-form-divider">
              <span>{t('login.account_login')}</span>
            </div>
            <Form
              name="login"
              layout="vertical"
              onFinish={onFinish}
              autoComplete="on"
              requiredMark={false}
              className="login-form"
            >
              <Form.Item
                name="username"
                label={t('login.username_label')}
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
                  autoComplete="username"
                  autoCapitalize="none"
                  spellCheck={false}
                />
              </Form.Item>

              <Form.Item
                name="password"
                label={t('login.password_label')}
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
                  autoComplete="current-password"
                  onKeyDown={(event) => setCapsLock(event.getModifierState('CapsLock'))}
                  onKeyUp={(event) => setCapsLock(event.getModifierState('CapsLock'))}
                  onBlur={() => setCapsLock(false)}
                />
              </Form.Item>
              {capsLock && (
                <p className="login-caps-hint" role="status">
                  {t('login.caps_lock')}
                </p>
              )}

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
          <p className="login-access-note">
            <span className="login-note-icon" aria-hidden="true">
              <LockOutlined />
            </span>
            <span>
              <strong>{t('login.access_title')}</strong>
              {t('login.access_note')}
            </span>
          </p>
        </section>
      </main>

      <footer className="login-page-footer">
        <Text className="page-footer-copyright">
          © {new Date().getFullYear()} leoli. All rights reserved.
        </Text>
        <span className="login-footer-note">{t('login.footer_note')}</span>
      </footer>
    </div>
  );
};

export default LoginPage;
