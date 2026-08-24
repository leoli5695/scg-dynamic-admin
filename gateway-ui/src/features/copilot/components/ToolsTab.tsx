import React, { useEffect, useState } from 'react';
import {
  Alert, Button, Card, Divider, Input, Select, Space, Typography  } from 'antd';
import { message } from '../../../utils/antdStatic';
import {
  BugOutlined,
  BulbOutlined,
  CheckCircleOutlined,
  CopyOutlined,
  DashboardOutlined,
  ThunderboltOutlined,
} from '@ant-design/icons';
import { useTranslation } from 'react-i18next';
import {
  analyzeError,
  applyRouteJson,
  generateRoute,
  getOptimizations,
  validateRouteJson,
} from '../../../api/copilot';
import { getNacosDiscoveryServices, listServices } from '../../../api/services';
import { ApiError } from '../../../api/http';
import AiReportRenderer from '../../../components/ai/AiReportRenderer';
import '../../../styles/ai-report.css';
import { translateValidationError } from '../utils/validationErrors';

const { Paragraph, Text } = Typography;
const { TextArea } = Input;

interface DbServiceOption {
  serviceId: string;
  name: string;
}

interface NacosServiceOption {
  serviceName: string;
  namespace?: string;
  group?: string;
  instanceCount?: number;
  displayName: string;
}

interface RouteValidationState {
  valid: boolean;
  errors?: string[];
}

interface ToolsTabProps {
  instanceId: string;
  provider: string;
  model: string;
}

const ToolsTab: React.FC<ToolsTabProps> = ({ instanceId, provider, model }) => {
  const { t, i18n } = useTranslation();

  // Route generation
  const [routeDescription, setRouteDescription] = useState('');
  const [generatedRoute, setGeneratedRoute] = useState('');
  const [routeLoading, setRouteLoading] = useState(false);
  const [dbServices, setDbServices] = useState<DbServiceOption[]>([]);
  const [nacosServices, setNacosServices] = useState<NacosServiceOption[]>([]);
  const [selectedServiceId, setSelectedServiceId] = useState('');
  const [validationResult, setValidationResult] = useState<RouteValidationState | null>(null);
  const [validatingRoute, setValidatingRoute] = useState(false);
  const [applyingRoute, setApplyingRoute] = useState(false);

  // Error analysis
  const [errorMessage, setErrorMessage] = useState('');
  const [errorAnalysis, setErrorAnalysis] = useState('');
  const [errorLoading, setErrorLoading] = useState(false);

  // Optimization
  const [optimizations, setOptimizations] = useState('');
  const [optimizationLoading, setOptimizationLoading] = useState(false);

  // Load services for route generator - both database services and Nacos services
  useEffect(() => {
    (async () => {
      try {
        const dbServiceList = await listServices({ instanceId });
        setDbServices((dbServiceList || []).map(s => ({
          serviceId: s.serviceId || s.name,
          name: s.name,
        })));
      } catch (e) {
        console.error('Failed to load database services', e);
      }
      try {
        const nacosServiceList = await getNacosDiscoveryServices();
        setNacosServices((nacosServiceList || []).map(s => ({
          serviceName: s.serviceName,
          namespace: s.namespace,
          group: s.group,
          instanceCount: s.instanceCount,
          displayName: s.displayName || `${s.serviceName} (${s.namespace || 'public'}/${s.group || 'DEFAULT_GROUP'})`,
        })));
      } catch (e) {
        console.error('Failed to load Nacos services', e);
      }
    })();
  }, [instanceId]);

  const handleGenerateRoute = async () => {
    if (!routeDescription.trim()) {
      message.warning(t('copilot.route_desc_required'));
      return;
    }

    // Parse service selection - format: "db:uuidServiceId:serviceName" or "nacos:serviceName"
    let serviceHint = '';
    if (selectedServiceId) {
      if (selectedServiceId.startsWith('db:')) {
        // Database service - use static://serviceId (UUID, NOT serviceName!)
        // serviceId is the UUID used in Nacos config for static services
        const parts = selectedServiceId.split(':');
        const uuidServiceId = parts[1];  // UUID serviceId for static://
        const serviceName = parts[2];    // Display name only
        serviceHint = ` (目标服务: ${serviceName}, 服务ID: ${uuidServiceId}, 使用 static://${uuidServiceId})`;
      } else if (selectedServiceId.startsWith('nacos:')) {
        // Nacos discovery service - use lb://serviceName with namespace/group
        const serviceName = selectedServiceId.replace('nacos:', '');
        const nacosService = nacosServices.find(s => s.serviceName === serviceName);
        if (nacosService) {
          const namespace = nacosService.namespace || 'public';
          const group = nacosService.group || 'DEFAULT_GROUP';
          serviceHint = ` (目标服务: ${serviceName}, namespace: ${namespace}, group: ${group}, 使用 lb://${serviceName})`;
        } else {
          serviceHint = ` (目标服务: ${serviceName}, 使用 lb://${serviceName})`;
        }
      }
    }

    setRouteLoading(true);
    setValidationResult(null); // Clear previous validation
    try {
      const response = await generateRoute({
        description: serviceHint ? `${routeDescription}${serviceHint}` : routeDescription,
        instanceId,
        provider: provider || undefined,
        model: model || undefined,
      });

      if (response.success) {
        setGeneratedRoute(response.config || '');
      } else {
        message.error(response.error || t('copilot.route_gen_failed'));
      }
    } catch (e) {
      message.error(e instanceof ApiError && e.message ? e.message : t('copilot.route_gen_error'));
    } finally {
      setRouteLoading(false);
    }
  };

  const handleValidateRoute = async () => {
    if (!generatedRoute.trim()) {
      message.warning(t('copilot.route_required'));
      return;
    }

    setValidatingRoute(true);
    try {
      const response = await validateRouteJson(generatedRoute);
      setValidationResult({ valid: response.valid, errors: response.errors });
      if (response.valid) {
        message.success(t('copilot.validation_passed'));
      } else {
        message.warning(t('copilot.validation_failed'));
      }
    } catch (e) {
      message.error(e instanceof ApiError && e.message ? e.message : t('copilot.validation_error'));
    } finally {
      setValidatingRoute(false);
    }
  };

  const handleApplyRoute = async () => {
    if (!generatedRoute.trim()) {
      message.warning(t('copilot.route_required'));
      return;
    }

    setApplyingRoute(true);
    try {
      const response = await applyRouteJson({ routeJson: generatedRoute, instanceId });
      if (response.success) {
        message.success(t('copilot.route_applied'));
        setGeneratedRoute('');
        setValidationResult(null);
        setRouteDescription('');
        setSelectedServiceId('');
      } else {
        message.error(response.message || t('copilot.route_apply_failed'));
      }
    } catch (e) {
      message.error(e instanceof ApiError && e.message ? e.message : t('copilot.route_apply_error'));
    } finally {
      setApplyingRoute(false);
    }
  };

  const handleCopy = (text: string) => {
    navigator.clipboard.writeText(text);
    message.success(t('copilot.copied'));
  };

  const handleAnalyzeError = async () => {
    if (!errorMessage.trim()) {
      message.warning(t('copilot.error_required'));
      return;
    }

    setErrorLoading(true);
    try {
      const response = await analyzeError({
        errorMessage,
        instanceId,
        provider: provider || undefined,
        model: model || undefined,
      });

      if (response.success) {
        setErrorAnalysis(response.analysis || '');
      } else {
        message.error(response.error || t('copilot.error_analysis_failed'));
      }
    } catch (e) {
      message.error(e instanceof ApiError && e.message ? e.message : t('copilot.error_analyze_error'));
    } finally {
      setErrorLoading(false);
    }
  };

  const handleGetOptimizations = async () => {
    setOptimizationLoading(true);
    try {
      const response = await getOptimizations(instanceId, {
        provider: provider || undefined,
        model: model || undefined,
      });

      if (response.success) {
        setOptimizations(response.suggestions || '');
      } else {
        message.error(response.error || t('copilot.optimization_failed'));
      }
    } catch (e) {
      message.error(e instanceof ApiError && e.message ? e.message : t('copilot.optimization_error'));
    } finally {
      setOptimizationLoading(false);
    }
  };

  return (
    <div style={{ padding: '16px' }}>
      <Space direction="vertical" size="large" style={{ width: '100%' }}>
        {/* Route Generator */}
        <Card
          title={
            <span>
              <ThunderboltOutlined style={{ marginRight: '8px', color: '#165DFF' }} />
              {t('copilot.route_generator')}
            </span>
          }
          style={{ background: 'rgba(255,255,255,0.05)' }}
        >
          <Paragraph style={{ color: 'rgba(255,255,255,0.65)' }}>
            {t('copilot.route_generator_desc')}
          </Paragraph>

          {/* Service Selection - supports both DB services and Nacos services */}
          <div style={{ marginBottom: '12px' }}>
            <Text style={{ color: 'rgba(255,255,255,0.85)', marginRight: '8px' }}>
              {t('copilot.target_service')}:
            </Text>
            <Select
              value={selectedServiceId}
              onChange={value => setSelectedServiceId(value)}
              placeholder={t('copilot.select_service')}
              allowClear
              style={{ width: 300 }}
            >
              {/* Database Services Group */}
              <Select.OptGroup label={t('routes.static_services')}>
                {dbServices.map(s => (
                  <Select.Option key={s.serviceId} value={`db:${s.serviceId}:${s.name}`}>
                    {s.name}
                  </Select.Option>
                ))}
              </Select.OptGroup>
              {/* Nacos Discovery Services Group */}
              <Select.OptGroup label={t('routes.nacos_services')}>
                {nacosServices.map(s => (
                  <Select.Option key={`nacos:${s.serviceName}`} value={`nacos:${s.serviceName}`}>
                    {s.displayName} {(s.instanceCount ?? 0) > 0 ? `(${s.instanceCount}实例)` : ''}
                  </Select.Option>
                ))}
              </Select.OptGroup>
            </Select>
          </div>

          {/* Description Input */}
          <TextArea
            value={routeDescription}
            onChange={e => setRouteDescription(e.target.value)}
            placeholder={t('copilot.route_placeholder')}
            rows={3}
            style={{
              marginBottom: '16px',
              background: 'rgba(0,0,0,0.2)',
              color: '#fff',
            }}
          />
          <Button
            type="primary"
            icon={<BulbOutlined />}
            onClick={handleGenerateRoute}
            loading={routeLoading}
          >
            {t('copilot.generate_route')}
          </Button>

          {generatedRoute && (
            <div style={{ marginTop: '16px' }}>
              <Divider />
              <div
                style={{
                  display: 'flex',
                  justifyContent: 'space-between',
                  alignItems: 'center',
                  marginBottom: '8px',
                }}
              >
                <Text strong>{t('copilot.generated_config')}</Text>
                <Space>
                  <Button
                    size="small"
                    icon={<CheckCircleOutlined />}
                    onClick={handleValidateRoute}
                    loading={validatingRoute}
                    style={{
                      borderColor: validationResult?.valid ? '#52c41a' : undefined,
                    }}
                  >
                    {t('copilot.validate')}
                  </Button>
                  <Button
                    size="small"
                    icon={<CopyOutlined />}
                    onClick={() => handleCopy(generatedRoute)}
                  >
                    {t('common.copy')}
                  </Button>
                </Space>
              </div>

              {/* Editable JSON Editor */}
              <TextArea
                value={generatedRoute}
                onChange={e => {
                  setGeneratedRoute(e.target.value);
                  setValidationResult(null); // Clear validation on edit
                }}
                rows={12}
                style={{
                  background: 'rgba(0,0,0,0.3)',
                  color: '#52c41a',
                  fontFamily: 'monospace',
                  fontSize: '13px',
                }}
              />

              {/* Validation Result */}
              {validationResult && (
                <div style={{ marginTop: '12px' }}>
                  {validationResult.valid ? (
                    <Alert
                      type="success"
                      showIcon
                      message={t('copilot.validation_passed')}
                      style={{ marginBottom: '12px' }}
                    />
                  ) : (
                    <Alert
                      type="error"
                      showIcon
                      message={t('copilot.validation_failed')}
                      description={
                        validationResult.errors?.map((err, idx) => (
                          <div key={idx}>• {translateValidationError(err, i18n.language)}</div>
                        ))
                      }
                      style={{ marginBottom: '12px' }}
                    />
                  )}
                </div>
              )}

              {/* Apply Button */}
              <Button
                type="primary"
                icon={<CheckCircleOutlined />}
                onClick={handleApplyRoute}
                loading={applyingRoute}
                disabled={!validationResult?.valid}
                style={{ marginTop: '12px' }}
              >
                {t('copilot.apply_route')}
              </Button>
            </div>
          )}
        </Card>

        {/* Error Analyzer */}
        <Card
          title={
            <span>
              <BugOutlined style={{ marginRight: '8px', color: '#f5222d' }} />
              {t('copilot.error_analyzer')}
            </span>
          }
          style={{ background: 'rgba(255,255,255,0.05)' }}
        >
          <Paragraph style={{ color: 'rgba(255,255,255,0.65)' }}>
            {t('copilot.error_analyzer_desc')}
          </Paragraph>
          <TextArea
            value={errorMessage}
            onChange={e => setErrorMessage(e.target.value)}
            placeholder={t('copilot.error_placeholder')}
            rows={4}
            style={{
              marginBottom: '16px',
              background: 'rgba(0,0,0,0.2)',
              color: '#fff',
            }}
          />
          <Button
            type="primary"
            danger
            icon={<BugOutlined />}
            onClick={handleAnalyzeError}
            loading={errorLoading}
          >
            {t('copilot.analyze_error')}
          </Button>
          {errorAnalysis && (
            <div style={{ marginTop: '16px' }}>
              <Divider />
              <div style={{ color: 'rgba(255,255,255,0.85)' }}>
                <AiReportRenderer content={errorAnalysis} />
              </div>
            </div>
          )}
        </Card>

        {/* Optimization Suggestions */}
        <Card
          title={
            <span>
              <DashboardOutlined style={{ marginRight: '8px', color: '#52c41a' }} />
              {t('copilot.performance_optimizer')}
            </span>
          }
          style={{ background: 'rgba(255,255,255,0.05)' }}
        >
          <Paragraph style={{ color: 'rgba(255,255,255,0.65)' }}>
            {t('copilot.performance_desc')}
          </Paragraph>
          <Button
            type="primary"
            icon={<DashboardOutlined />}
            onClick={handleGetOptimizations}
            loading={optimizationLoading}
          >
            {t('copilot.get_optimizations')}
          </Button>
          {optimizations && (
            <div style={{ marginTop: '16px' }}>
              <Divider />
              <div style={{ color: 'rgba(255,255,255,0.85)' }}>
                <AiReportRenderer content={optimizations} />
              </div>
            </div>
          )}
        </Card>
      </Space>
    </div>
  );
};

export default ToolsTab;
