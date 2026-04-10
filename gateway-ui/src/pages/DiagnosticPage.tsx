import React, { useState, useEffect } from 'react';
import {
  Card, Row, Col, Progress, Spin, Alert, Tag, Typography, Space,
  Button, Collapse, Descriptions, Badge, Statistic, Divider, Tooltip, message
} from 'antd';
import {
  MedicineBoxOutlined, CheckCircleOutlined, WarningOutlined,
  CloseCircleOutlined, ReloadOutlined, DashboardOutlined,
  DatabaseOutlined, CloudServerOutlined, LockOutlined,
  ApiOutlined, ThunderboltOutlined, RocketOutlined
} from '@ant-design/icons';
import axios from 'axios';
import { useTranslation } from 'react-i18next';

const { Text, Title, Paragraph } = Typography;
const { Panel } = Collapse;

interface ComponentDiagnostic {
  name: string;
  type: string;
  status: string;
  metrics: Record<string, any>;
  warnings?: string[];
  errors?: string[];
}

interface DiagnosticReport {
  startTime: number;
  endTime: number;
  duration: string;
  overallScore: number;
  status: string;
  database?: ComponentDiagnostic;
  redis?: ComponentDiagnostic;
  configCenter?: ComponentDiagnostic;
  routes?: ComponentDiagnostic;
  auth?: ComponentDiagnostic;
  gatewayInstances?: ComponentDiagnostic;
  performance?: ComponentDiagnostic;
  recommendations?: string[];
}

interface DiagnosticPageProps {
  instanceId?: string;
}

const DiagnosticPage: React.FC<DiagnosticPageProps> = ({ instanceId }) => {
  const [loading, setLoading] = useState(false);
  const [report, setReport] = useState<DiagnosticReport | null>(null);
  const [error, setError] = useState<string | null>(null);
  const { t } = useTranslation();

  const runDiagnostic = async (type: 'full' | 'quick' = 'full') => {
    setLoading(true);
    setError(null);
    try {
      const params = instanceId ? `?instanceId=${instanceId}` : '';
      const response = await axios.get(`/api/diagnostic/${type}${params}`);
      if (response.data) {
        setReport(response.data);
      } else {
        setError('Failed to get diagnostic report');
      }
    } catch (e: any) {
      setError(e.message || 'Failed to run diagnostic');
      message.error(t('diagnostic.run_failed') || '诊断运行失败');
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => {
    runDiagnostic('quick');
  }, [instanceId]);

  const getStatusIcon = (status: string) => {
    switch (status) {
      case 'HEALTHY':
        return <CheckCircleOutlined style={{ color: '#52c41a', fontSize: 20 }} />;
      case 'WARNING':
        return <WarningOutlined style={{ color: '#faad14', fontSize: 20 }} />;
      case 'CRITICAL':
        return <CloseCircleOutlined style={{ color: '#ff4d4f', fontSize: 20 }} />;
      case 'NOT_CONFIGURED':
        return <WarningOutlined style={{ color: '#8c8c8c', fontSize: 20 }} />;
      default:
        return <WarningOutlined style={{ color: '#8c8c8c', fontSize: 20 }} />;
    }
  };

  const getStatusColor = (status: string) => {
    switch (status) {
      case 'HEALTHY':
        return 'success';
      case 'WARNING':
        return 'warning';
      case 'CRITICAL':
        return 'error';
      default:
        return 'default';
    }
  };

  const getScoreColor = (score: number) => {
    if (score >= 80) return '#52c41a';
    if (score >= 50) return '#faad14';
    return '#ff4d4f';
  };

  const getScoreStatus = (score: number): 'success' | 'normal' | 'exception' => {
    if (score >= 80) return 'success';
    if (score >= 50) return 'normal';
    return 'exception';
  };

  const renderComponentCard = (
    title: string,
    icon: React.ReactNode,
    diagnostic?: ComponentDiagnostic
  ) => {
    if (!diagnostic) {
      return (
        <Card className="diagnostic-card">
          <div style={{ textAlign: 'center', padding: 20 }}>
            <Text type="secondary">{t('diagnostic.no_data') || '无数据'}</Text>
          </div>
        </Card>
      );
    }

    const metricsEntries = Object.entries(diagnostic.metrics || {});
    const hasWarnings = diagnostic.warnings && diagnostic.warnings.length > 0;
    const hasErrors = diagnostic.errors && diagnostic.errors.length > 0;

    return (
      <Card 
        className="diagnostic-card"
        title={
          <Space>
            {icon}
            <Text strong>{title}</Text>
          </Space>
        }
        extra={
          <Space>
            {getStatusIcon(diagnostic.status)}
            <Tag color={getStatusColor(diagnostic.status)}>{diagnostic.status}</Tag>
          </Space>
        }
      >
        <Descriptions column={2} size="small">
          {metricsEntries.map(([key, value]) => (
            <Descriptions.Item key={key} label={t(`diagnostic.metric.${key}`) || key}>
              {typeof value === 'boolean' ? (
                <Tag color={value ? 'green' : 'default'}>{value ? t('common.yes') : t('common.no')}</Tag>
              ) : typeof value === 'string' && (value === 'PASS' || value.startsWith('PASS')) ? (
                <Tag color="green">{t('diagnostic.metric.PASS')}</Tag>
              ) : typeof value === 'string' && (value === 'FAIL' || value.startsWith('FAIL')) ? (
                <Tag color="red">{t('diagnostic.metric.FAIL')}</Tag>
              ) : (
                <Text>{value}</Text>
              )}
            </Descriptions.Item>
          ))}
        </Descriptions>

        {hasWarnings && (
          <div style={{ marginTop: 12 }}>
            <Alert
              type="warning"
              showIcon
              message={t('diagnostic.warnings') || '警告'}
              description={
                <ul style={{ margin: 0, paddingLeft: 20 }}>
                  {diagnostic.warnings!.map((w, i) => (
                    <li key={i}>{w}</li>
                  ))}
                </ul>
              }
            />
          </div>
        )}

        {hasErrors && (
          <div style={{ marginTop: 12 }}>
            <Alert
              type="error"
              showIcon
              message={t('diagnostic.errors') || '错误'}
              description={
                <ul style={{ margin: 0, paddingLeft: 20 }}>
                  {diagnostic.errors!.map((e, i) => (
                    <li key={i}>{e}</li>
                  ))}
                </ul>
              }
            />
          </div>
        )}
      </Card>
    );
  };

  if (loading && !report) {
    return (
      <div style={{ textAlign: 'center', padding: 100 }}>
        <Spin size="large" />
        <div style={{ marginTop: 16 }}>
          <Text type="secondary">{t('diagnostic.running') || '正在运行诊断...'}</Text>
        </div>
      </div>
    );
  }

  return (
    <div className="diagnostic-page">
      <div className="page-header" style={{
        display: 'flex',
        justifyContent: 'space-between',
        alignItems: 'center',
        marginBottom: 16
      }}>
        <Title level={4} style={{ margin: 0 }}>
          <MedicineBoxOutlined style={{ marginRight: 8 }} />
          {t('diagnostic.title') || '系统诊断'}
        </Title>
        <Space>
          <Button
            type="primary"
            icon={<RocketOutlined />}
            onClick={() => runDiagnostic('full')}
            loading={loading}
          >
            {t('diagnostic.full_check') || '完整诊断'}
          </Button>
          <Button
            icon={<ThunderboltOutlined />}
            onClick={() => runDiagnostic('quick')}
            loading={loading}
          >
            {t('diagnostic.quick_check') || '快速诊断'}
          </Button>
        </Space>
      </div>

      {error && (
        <Alert
          message={t('common.error') || '错误'}
          description={error}
          type="error"
          showIcon
          closable
          style={{ marginBottom: 16 }}
          onClose={() => setError(null)}
        />
      )}

      {/* Overall Score Card */}
      {report && (
        <Card style={{ marginBottom: 16 }}>
          <Row gutter={24} align="middle">
            <Col xs={24} sm={8} md={6}>
              <div style={{ textAlign: 'center' }}>
                <Progress
                  type="circle"
                  percent={report.overallScore}
                  strokeColor={getScoreColor(report.overallScore)}
                  status={getScoreStatus(report.overallScore)}
                  format={(percent) => (
                    <div>
                      <div style={{ fontSize: 24, fontWeight: 'bold' }}>{percent}</div>
                      <div style={{ fontSize: 12 }}>{t('diagnostic.score') || '健康评分'}</div>
                    </div>
                  )}
                />
              </div>
            </Col>
            <Col xs={24} sm={16} md={18}>
              <Row gutter={16}>
                <Col span={8}>
                  <Statistic
                    title={t('diagnostic.status') || '状态'}
                    value={report.status}
                    valueStyle={{ 
                      color: getScoreColor(report.overallScore),
                      fontSize: 18
                    }}
                    prefix={getStatusIcon(report.status)}
                  />
                </Col>
                <Col span={8}>
                  <Statistic
                    title={t('diagnostic.duration') || '耗时'}
                    value={report.duration}
                    suffix=""
                  />
                </Col>
                <Col span={8}>
                  <Statistic
                    title={t('diagnostic.timestamp') || '时间'}
                    value={new Date(report.endTime).toLocaleTimeString()}
                  />
                </Col>
              </Row>
              
              {/* Recommendations */}
              {report.recommendations && report.recommendations.length > 0 && (
                <div style={{ marginTop: 16 }}>
                  <Text strong>{t('diagnostic.recommendations') || '建议措施'}:</Text>
                  <ul style={{ margin: '8px 0 0 20px' }}>
                    {report.recommendations.map((rec, i) => (
                      <li key={i}>
                        <Text>{rec}</Text>
                      </li>
                    ))}
                  </ul>
                </div>
              )}
            </Col>
          </Row>
        </Card>
      )}

      {/* Component Diagnostics */}
      {report && (
        <Row gutter={[16, 16]}>
          <Col xs={24} lg={12}>
            {renderComponentCard(
              t('diagnostic.database') || '数据库',
              <DatabaseOutlined />,
              report.database
            )}
          </Col>
          <Col xs={24} lg={12}>
            {renderComponentCard(
              t('diagnostic.redis') || 'Redis',
              <CloudServerOutlined />,
              report.redis
            )}
          </Col>
          <Col xs={24} lg={12}>
            {renderComponentCard(
              t('diagnostic.config_center') || '配置中心',
              <ApiOutlined />,
              report.configCenter
            )}
          </Col>
          <Col xs={24} lg={12}>
            {renderComponentCard(
              t('diagnostic.routes') || '路由配置',
              <DashboardOutlined />,
              report.routes
            )}
          </Col>
          <Col xs={24} lg={12}>
            {renderComponentCard(
              t('diagnostic.auth') || '认证配置',
              <LockOutlined />,
              report.auth
            )}
          </Col>
          <Col xs={24} lg={12}>
            {renderComponentCard(
              t('diagnostic.gateway_instances') || '网关实例',
              <CloudServerOutlined />,
              report.gatewayInstances
            )}
          </Col>
          <Col xs={24}>
            {renderComponentCard(
              t('diagnostic.performance') || '性能指标',
              <DashboardOutlined />,
              report.performance
            )}
          </Col>
        </Row>
      )}

      {!loading && !report && (
        <Card>
          <div style={{ textAlign: 'center', padding: 40 }}>
            <MedicineBoxOutlined style={{ fontSize: 48, color: '#8c8c8c' }} />
            <Paragraph style={{ marginTop: 16, color: '#8c8c8c' }}>
              {t('diagnostic.click_to_run') || '点击上方按钮运行诊断'}
            </Paragraph>
          </div>
        </Card>
      )}

      <style>{`
        .diagnostic-page { padding: 0 }
        .diagnostic-card { height: 100% }
        .diagnostic-card .ant-card-head {
          background: rgba(255,255,255,0.02);
        }
      `}</style>
    </div>
  );
};

export default DiagnosticPage;