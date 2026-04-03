import { useState, useEffect, useRef } from 'react';
import { Form, Input, Button, message, Typography, Divider } from 'antd';
import { UserOutlined, LockOutlined, GlobalOutlined, ApiOutlined, CloudOutlined, SafetyOutlined } from '@ant-design/icons';
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
  const canvasRef = useRef<HTMLCanvasElement>(null);
  const navigate = useNavigate();
  const { t } = useTranslation();

  // Initialize dynamic tech ray background with meteor particles
  useEffect(() => {
    const canvas = canvasRef.current;
    if (!canvas) return;

    const ctx = canvas.getContext('2d');
    if (!ctx) return;

    // Configuration - adjustable parameters
    const config = {
      rayCount: window.innerWidth < 768 ? 40 : 80,
      particleCount: window.innerWidth < 768 ? 15 : 30,
      colors: ['rgba(96, 165, 250, ', 'rgba(167, 139, 250, ', 'rgba(34, 211, 238, '],
      baseSpeed: 0.3,
      maxOpacity: 0.6,
      minOpacity: 0.2,
      particleSpeed: 0.5,
    };

    let animationFrameId: number;
    let rays: Array<{
      angle: number;
      speed: number;
      length: number;
      width: number;
      opacity: number;
      colorIndex: number;
      phase: number;
      pulseSpeed: number;
    }> = [];
    
    let particles: Array<{
      rayIndex: number;
      position: number;
      speed: number;
      size: number;
      opacity: number;
      colorIndex: number;
      trail: Array<{position: number; opacity: number}>;
    }> = [];

    const resizeCanvas = () => {
      canvas.width = window.innerWidth;
      canvas.height = window.innerHeight;
    };
    resizeCanvas();

    const initRays = () => {
      rays = [];
      for (let i = 0; i < config.rayCount; i++) {
        rays.push({
          angle: (Math.PI * 2 * i) / config.rayCount,
          speed: config.baseSpeed + Math.random() * 0.2,
          length: 100 + Math.random() * 150,
          width: 1 + Math.random() * 2,
          opacity: config.minOpacity + Math.random() * (config.maxOpacity - config.minOpacity),
          colorIndex: Math.floor(Math.random() * config.colors.length),
          phase: Math.random() * Math.PI * 2,
          pulseSpeed: 0.01 + Math.random() * 0.02,
        });
      }
    };
    initRays();

    const initParticles = () => {
      particles = [];
      for (let i = 0; i < config.particleCount; i++) {
        particles.push({
          rayIndex: Math.floor(Math.random() * config.rayCount),
          position: Math.random(),
          speed: config.particleSpeed + Math.random() * 0.3,
          size: 2 + Math.random() * 3,
          opacity: 0.6 + Math.random() * 0.4,
          colorIndex: Math.floor(Math.random() * config.colors.length),
          trail: [],
        });
      }
    };
    initParticles();

    let lastTime = 0;
    const throttleInterval = 1000 / 30;

    const animate = (timestamp: number) => {
      if (timestamp - lastTime < throttleInterval) {
        animationFrameId = requestAnimationFrame(animate);
        return;
      }
      lastTime = timestamp;

      if (!ctx) return;
      ctx.clearRect(0, 0, canvas.width, canvas.height);

      const centerX = canvas.width / 2;
      const centerY = canvas.height / 2;

      // Draw pulsing rays with glow
      rays.forEach((ray) => {
        ray.phase += ray.pulseSpeed;
        const pulseOpacity = ray.opacity * (0.6 + 0.4 * Math.sin(ray.phase));

        const endX = centerX + Math.cos(ray.angle) * ray.length * 3;
        const endY = centerY + Math.sin(ray.angle) * ray.length * 3;

        const gradient = ctx.createLinearGradient(centerX, centerY, endX, endY);
        gradient.addColorStop(0, config.colors[ray.colorIndex] + '0)');
        gradient.addColorStop(0.2, config.colors[ray.colorIndex] + pulseOpacity * 0.2 + ')');
        gradient.addColorStop(0.5, config.colors[ray.colorIndex] + pulseOpacity + ')');
        gradient.addColorStop(0.8, config.colors[ray.colorIndex] + pulseOpacity * 0.3 + ')');
        gradient.addColorStop(1, config.colors[ray.colorIndex] + '0)');

        ctx.beginPath();
        ctx.moveTo(centerX, centerY);
        ctx.lineTo(endX, endY);
        ctx.strokeStyle = gradient;
        ctx.lineWidth = ray.width;
        ctx.lineCap = 'round';
        
        ctx.shadowBlur = 15;
        ctx.shadowColor = config.colors[ray.colorIndex] + pulseOpacity + ')';
        ctx.stroke();
        ctx.shadowBlur = 0;

        ray.angle += 0.0001;
      });

      // Update and draw meteor particles with trails
      particles.forEach((particle) => {
        particle.position += particle.speed * 0.01;
        
        particle.trail.unshift({ position: particle.position, opacity: particle.opacity });
        if (particle.trail.length > 20) {
          particle.trail.pop();
        }
        
        if (particle.position > 1) {
          particle.position = 0;
          particle.rayIndex = Math.floor(Math.random() * config.rayCount);
          particle.trail = [];
        }

        const ray = rays[particle.rayIndex];
        if (!ray) return;

        const currentLength = ray.length * 3;
        const particleX = centerX + Math.cos(ray.angle) * currentLength * particle.position;
        const particleY = centerY + Math.sin(ray.angle) * currentLength * particle.position;

        // Draw trail
        particle.trail.forEach((trailPoint, index) => {
          const trailX = centerX + Math.cos(ray.angle) * currentLength * trailPoint.position;
          const trailY = centerY + Math.sin(ray.angle) * currentLength * trailPoint.position;
          const trailOpacity = trailPoint.opacity * (1 - index / particle.trail.length) * 0.6;
          
          ctx.beginPath();
          ctx.arc(trailX, trailY, particle.size * (1 - index / particle.trail.length), 0, Math.PI * 2);
          ctx.fillStyle = config.colors[particle.colorIndex] + trailOpacity + ')';
          ctx.fill();
        });

        // Draw particle head with sparkle
        ctx.beginPath();
        ctx.arc(particleX, particleY, particle.size, 0, Math.PI * 2);
        ctx.fillStyle = config.colors[particle.colorIndex] + particle.opacity + ')';
        
        ctx.shadowBlur = 20;
        ctx.shadowColor = config.colors[particle.colorIndex] + particle.opacity + ')';
        ctx.fill();
        ctx.shadowBlur = 0;

        if (Math.random() < 0.02) {
          particle.opacity = 0.8 + Math.random() * 0.2;
        } else {
          particle.opacity = 0.6 + Math.random() * 0.2;
        }
      });

      animationFrameId = requestAnimationFrame(animate);
    };

    animationFrameId = requestAnimationFrame(animate);

    const handleResize = () => {
      resizeCanvas();
      config.rayCount = window.innerWidth < 768 ? 40 : 80;
      config.particleCount = window.innerWidth < 768 ? 15 : 30;
      initRays();
      initParticles();
    };
    window.addEventListener('resize', handleResize);

    return () => {
      cancelAnimationFrame(animationFrameId);
      window.removeEventListener('resize', handleResize);
    };
  }, []);

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
        <canvas ref={canvasRef} className="tech-rays-canvas" />
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
