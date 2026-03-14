import { useState } from 'react';
import { Form, Input, Button, message, Typography } from 'antd';
import { UserOutlined, LockOutlined, GatewayOutlined } from '@ant-design/icons';
import { useNavigate } from 'react-router-dom';
import api from '../utils/api';
import { useTranslation } from 'react-i18next';
import './LoginPage.css';

const { Title } = Typography;

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
        
        // Redirect to main page
        setTimeout(() => {
          navigate('/');
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
      {/* Background decoration */}
      <div className="login-background">
        <div className="bg-circle circle-1"></div>
        <div className="bg-circle circle-2"></div>
        <div className="bg-circle circle-3"></div>
        <div className="bg-grid"></div>
      </div>

      {/* Login box */}
      <div className="login-box">
        <div className="login-header">
          <GatewayOutlined className="logo-icon" />
          <Title level={2} className="login-title">
            {t('login.title')}
          </Title>
          <p className="login-subtitle">{t('login.subtitle')}</p>
        </div>

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
              prefix={<UserOutlined />}
              placeholder={t('login.username_placeholder')}
              size="large"
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
              prefix={<LockOutlined />}
              placeholder={t('login.password_placeholder')}
              size="large"
            />
          </Form.Item>

          <Form.Item>
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

        <div className="login-footer">
          <p className="footer-text">{t('login.default_credentials')}</p>
        </div>
      </div>
    </div>
  );
};

export default LoginPage;
