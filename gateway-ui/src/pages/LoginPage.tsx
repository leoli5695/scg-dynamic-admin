import { useState } from 'react';
import { Form, Input, Button, message, Typography, Divider } from 'antd';
import { UserOutlined, LockOutlined, GatewayOutlined, GlobalOutlined } from '@ant-design/icons';
import { useNavigate } from 'react-router-dom';
import api from '../utils/api';
import { useTranslation } from 'react-i18next';
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
        
        // Store token and user info in localStorage
        localStorage.setItem('token', token);
        localStorage.setItem('username', username);
        localStorage.setItem('nickname', nickname || username);
        localStorage.setItem('role', role);
        
        message.success(t('login.success', { nickname: nickname || username }));
        
        // Force reload to update App component state
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
      {/* Animated background */}
      <div className="login-background">
        <div className="bg-gradient"></div>
        <div className="bg-grid"></div>
        <div className="bg-glow glow-1"></div>
        <div className="bg-glow glow-2"></div>
      </div>

      {/* Login card */}
      <div className="login-card">
        {/* Brand section */}
        <div className="login-brand">
          <div className="brand-icon-wrapper">
            <GatewayOutlined className="brand-icon" />
          </div>
          <Title level={2} className="brand-title">
            API Gateway Console
          </Title>
          <Text className="brand-subtitle">
            Unified Management Platform
          </Text>
        </div>

        {/* Form section */}
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

        {/* Footer section */}
        <Divider className="footer-divider" />
        <div className="login-footer">
          <Text className="footer-text">
            {t('login.default_credentials')}
          </Text>
        </div>
      </div>
    </div>
  );
};

export default LoginPage;
