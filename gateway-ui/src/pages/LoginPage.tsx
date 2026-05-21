import { useState } from 'react';
import { Form, Input, Button, message, Typography, Divider } from 'antd';
import { UserOutlined, LockOutlined, GlobalOutlined, ApiOutlined, CloudOutlined, SafetyOutlined } from '@ant-design/icons';
import { useNavigate } from 'react-router-dom';
import api from '../utils/api';
import { useTranslation } from 'react-i18next';
import TechRaysBackground from '../components/TechRaysBackground';
import './LoginPage.css';

const { Title, Text } = Typography;

interface LoginFormData {
  username: string;
  password: string;
}

const LoginPage: React.FC = () => {
  const [loading, setLoading] = useState(false);
  const navigate = useNavigate();
  const { t } = useTranslation();

  const onFinish = async (values: LoginFormData) => {
    setLoading(true);
    
    try {
      const response = await api.post('/api/auth/login', values);
      
      if (response.data.code === 200) {
        const { token, username, nickname, role } = response.data.data;
        
        localStorage.setItem('token', token);
        localStorage.setItem('username', username);
        localStorage.setItem('nickname', nickname || username);
        localStorage.setItem('role', role);
        
        message.success(t('login.success', { nickname: nickname || username }));
        
        setTimeout(() => {
          window.location.href = '/';
        }, 500);
      } else {
        message.error(response.data.message || t('login.failed'));
      }
    } catch (error: any) {
      console.error('Login error:', error);
      message.error(error.response?.data?.message || t('login.failed'));
    } finally {
      setLoading(false);
    }
  };

  return (
    <div className="login-container">
      {/* Premium animated background */}
      <div className="login-background">
        {/* Ambient glow layers */}
        <div className="bg-ambient-1"></div>
        <div className="bg-ambient-2"></div>
        <div className="bg-ambient-3"></div>
        
        {/* Gradient orbs */}
        <div className="gradient-orb gradient-orb-1"></div>
        <div className="gradient-orb gradient-orb-2"></div>
        <div className="gradient-orb gradient-orb-3"></div>
        
        {/* Grid pattern */}
        <div className="bg-grid"></div>
        
        {/* Floating particles */}
        <div className="particles">
          <div className="particle"></div>
          <div className="particle"></div>
          <div className="particle"></div>
          <div className="particle"></div>
          <div className="particle"></div>
          <div className="particle"></div>
          <div className="particle"></div>
          <div className="particle"></div>
          <div className="particle"></div>
        </div>
        
        {/* Tech rays canvas */}
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
                { min: 3, message: t('login.username_min_length') }
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
                { min: 6, message: t('login.password_min_length') }
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

      {/* Page footer - outside login card */}
      <div className="login-page-footer">
        <Text className="page-footer-copyright">
          © {new Date().getFullYear()} leoli. All rights reserved.
        </Text>
      </div>
    </div>
  );
};

export default LoginPage;
