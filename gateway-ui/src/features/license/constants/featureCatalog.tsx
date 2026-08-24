import React from 'react';
import {
  ApiOutlined,
  CheckCircleOutlined,
  CloseCircleOutlined,
  CloudOutlined,
  CrownOutlined,
  DashboardOutlined,
  FileSearchOutlined,
  GlobalOutlined,
  HistoryOutlined,
  LineChartOutlined,
  RocketOutlined,
  RobotOutlined,
  SafetyCertificateOutlined,
  SecurityScanOutlined,
  TeamOutlined,
  ThunderboltOutlined,
} from '@ant-design/icons';
import type { LicenseEdition } from '../../../types/license';

/** Feature catalog entry shown in the authorization matrix. */
export interface LicenseFeatureDef {
  /** Must match backend LicenseInfo.Feature enum exactly. */
  name: string;
  displayName: string;
  description: string;
  requiredEdition: LicenseEdition;
  icon: React.ReactNode;
  /** Default before license status arrives. */
  enabled: boolean;
  detailedDescription?: string;
  benefits?: string[];
  useCases?: string[];
}

/** License status codes returned by GET /api/license/list. */
export type LicenseStatusCode = 'ACTIVE' | 'EXPIRED';

// Feature catalog with edition requirements
// Note: Feature names must match backend LicenseInfo.Feature enum exactly
export const FEATURE_CATALOG: LicenseFeatureDef[] = [
  // ==================== Basic Edition Features (Free) ====================
  {
    name: 'BASIC_ROUTING',
    displayName: '基础路由配置',
    description: '支持路径匹配、重写、转发等基础路由功能',
    requiredEdition: 'BASIC',
    icon: <ApiOutlined />,
    enabled: true,
    detailedDescription: '基础路由配置是网关的核心功能，支持基于路径、请求方法、Header 等条件进行路由匹配，并支持路径重写、请求转发等基础操作。',
    benefits: [
      '灵活的路径匹配规则（精确匹配、前缀匹配、正则匹配）',
      '支持请求方法过滤（GET、POST、PUT、DELETE 等）',
      '路径重写与参数提取',
      '多后端服务转发配置',
    ],
    useCases: [
      'API 路径标准化（如 /api/v1/* 转发到后端服务）',
      '微服务网关入口统一管理',
      '旧版 API 兼容性路由',
    ],
  },
  {
    name: 'LOCAL_RATE_LIMIT',
    displayName: '单机限流',
    description: '基于内存的单实例限流，支持 QPS/并发数限制',
    requiredEdition: 'BASIC',
    icon: <ThunderboltOutlined />,
    enabled: true,
    detailedDescription: '单机限流基于本地内存实现，适用于单实例部署场景。支持 QPS（每秒请求数）和并发数两种限流策略，可有效防止服务过载。',
    benefits: [
      'QPS 限流：控制每秒最大请求数',
      '并发限流：控制同时处理的请求数',
      '基于 IP、用户、路径等多维度限流',
      '限流后自动返回 429 状态码',
    ],
    useCases: [
      '防止突发流量冲击后端服务',
      '保护核心业务接口稳定性',
      '防止恶意请求刷接口',
    ],
  },
  {
    name: 'BASIC_AUTH',
    displayName: '基础认证',
    description: '支持 API Key 基础认证方式',
    requiredEdition: 'BASIC',
    icon: <SafetyCertificateOutlined />,
    enabled: true,
    detailedDescription: '基础认证提供 API Key 认证方式，支持路由级别的认证配置，简单易用适合内部服务调用。',
    benefits: [
      'API Key 认证：简单易用，适合内部服务调用',
      '路由级认证配置：不同路由可使用不同认证方式',
      '认证失败自动返回 401 状态码',
    ],
    useCases: [
      '开放 API 认证授权',
      '微服务间调用认证',
      '移动端应用接口认证',
    ],
  },
  {
    name: 'DOCKER_DEPLOY',
    displayName: 'Docker 部署',
    description: '支持 Docker 容器化部署与管理',
    requiredEdition: 'BASIC',
    icon: <CloudOutlined />,
    enabled: true,
    detailedDescription: 'Docker 部署功能支持网关的容器化部署，提供基础的容器管理能力，便于快速部署和运维。',
    benefits: [
      '容器化部署简化运维',
      '快速环境搭建',
      '版本管理便捷',
      '资源隔离运行',
    ],
    useCases: [
      '开发环境快速部署',
      '测试环境容器化',
      '单机生产部署',
    ],
  },
  {
    name: 'HEALTH_CHECK',
    displayName: '健康检查',
    description: '服务实例健康状态检测与自动剔除',
    requiredEdition: 'BASIC',
    icon: <CheckCircleOutlined />,
    enabled: true,
    detailedDescription: '健康检查功能定期检测后端服务实例的健康状态，自动剔除异常实例，并在恢复后自动加入，确保服务高可用。',
    benefits: [
      '主动健康探测（HTTP、TCP）',
      '被动健康检测（基于请求失败率）',
      '异常实例自动剔除',
      '恢复后自动重新加入',
    ],
    useCases: [
      '微服务实例故障自动感知',
      '负载均衡动态调整',
      '服务发布平滑切换',
    ],
  },
  {
    name: 'SERVICE_DISCOVERY',
    displayName: '服务发现',
    description: '支持静态配置与 DNS 服务发现',
    requiredEdition: 'BASIC',
    icon: <GlobalOutlined />,
    enabled: true,
    detailedDescription: '服务发现支持静态配置和 DNS 两种方式，可灵活对接不同的服务注册中心，实现后端服务地址的动态获取。',
    benefits: [
      '静态配置：手动指定服务地址',
      'DNS 服务发现：自动解析服务域名',
      '服务地址动态更新',
      '多实例负载均衡',
    ],
    useCases: [
      '传统服务静态配置',
      'K8s Service DNS 发现',
      'Nacos/Consul DNS 发现',
    ],
  },
  {
    name: 'TIMEOUT_CONFIG',
    displayName: '超时配置',
    description: '请求超时、连接超时等基础超时控制',
    requiredEdition: 'BASIC',
    icon: <HistoryOutlined />,
    enabled: true,
    detailedDescription: '超时配置支持连接超时、读取超时、写入超时等多种超时策略，可针对不同路由配置不同的超时时间，防止请求长时间阻塞。',
    benefits: [
      '连接超时：控制建立连接的时间',
      '读写超时：控制数据传输时间',
      '路由级超时配置',
      '超时后自动返回 504 状态码',
    ],
    useCases: [
      '慢接口保护',
      '防止资源长时间占用',
      '用户体验优化',
    ],
  },
  {
    name: 'BASIC_LOGGING',
    displayName: '日志记录',
    description: '请求日志、错误日志的记录与查询',
    requiredEdition: 'BASIC',
    icon: <FileSearchOutlined />,
    enabled: true,
    detailedDescription: '日志记录功能支持完整的请求生命周期日志，包括请求入口、路由决策、后端调用、响应返回等关键节点，便于问题排查和审计追溯。',
    benefits: [
      '完整请求链路日志',
      '错误日志自动捕获与分类',
      '日志级别动态配置',
      '支持日志检索与过滤',
    ],
    useCases: [
      '请求异常排查',
      '用户行为分析',
      '安全审计追溯',
    ],
  },
  {
    name: 'BASIC_METRICS',
    displayName: '基础监控指标',
    description: '请求量、成功率、响应时间等基础监控指标',
    requiredEdition: 'BASIC',
    icon: <DashboardOutlined />,
    enabled: true,
    detailedDescription: '基础监控提供核心性能指标的采集与展示，包括请求量、成功率、响应时间等关键数据，支持 Grafana 可视化集成。',
    benefits: [
      '实时请求量统计（QPS、总请求数）',
      '成功率与错误率监控',
      '响应时间分布（P50、P95、P99）',
      '支持 Grafana 可视化集成',
    ],
    useCases: [
      '服务健康状态监控',
      '性能瓶颈定位',
      '异常流量告警',
    ],
  },
  // ==================== Advanced Edition Features ====================
  {
    name: 'WEIGHTED_ROUTING',
    displayName: '多服务加权路由',
    description: '支持多服务实例的权重分配流量路由',
    requiredEdition: 'ADVANCED',
    icon: <ApiOutlined />,
    enabled: false,
    detailedDescription: '多服务加权路由支持将流量按权重分配到多个后端服务，实现流量分流、A/B 测试、金丝雀发布等高级场景。',
    benefits: [
      '多后端服务权重配置',
      '动态权重调整',
      '流量按比例自动分配',
      '支持金丝雀发布场景',
    ],
    useCases: [
      '新版本灰度发布',
      '多机房流量调度',
      'A/B 测试流量分配',
    ],
  },
  {
    name: 'DISTRIBUTED_RATE_LIMIT',
    displayName: '分布式限流',
    description: '基于 Redis 的全局限流，支持多实例协同',
    requiredEdition: 'ADVANCED',
    icon: <ThunderboltOutlined />,
    enabled: false,
    detailedDescription: '分布式限流基于 Redis 实现，支持多网关实例协同限流，确保全局 QPS 控制的精确性和一致性。',
    benefits: [
      '全局 QPS 精确控制',
      '多实例限流协同',
      '滑动窗口算法',
      '限流数据持久化',
    ],
    useCases: [
      '多实例部署场景全局限流',
      '对接第三方 API 流量控制',
      '高峰期流量削峰',
    ],
  },
  {
    name: 'CIRCUIT_BREAKER',
    displayName: '熔断器',
    description: '服务熔断与降级保护',
    requiredEdition: 'ADVANCED',
    icon: <SafetyCertificateOutlined />,
    enabled: false,
    detailedDescription: '熔断器模式自动检测服务异常率，当达到阈值时自动熔断，阻止请求继续调用异常服务，并支持自定义降级响应。',
    benefits: [
      '异常率自动熔断',
      '半开状态自动探测恢复',
      '自定义降级响应',
      '熔断状态可视化监控',
    ],
    useCases: [
      '防止级联故障',
      '服务雪崩效应防护',
      '关键服务降级保护',
    ],
  },
  {
    name: 'K8S_INTEGRATION',
    displayName: 'Kubernetes 集成',
    description: 'K8s 集群管理、Pod 部署、自动伸缩',
    requiredEdition: 'ADVANCED',
    icon: <CloudOutlined />,
    enabled: false,
    detailedDescription: 'Kubernetes 集成提供完整的 K8s 集群管理能力，包括 Deployment 管理、Pod 监控、HPA 自动伸缩等功能。',
    benefits: [
      'Deployment 创建与管理',
      'Pod 实例实时监控',
      'HPA 自动伸缩配置',
      'Service/Ingress 配置',
    ],
    useCases: [
      '网关实例动态伸缩',
      'K8s 环境服务部署',
      '容器化运维管理',
    ],
  },
  {
    name: 'PROMETHEUS_MONITORING',
    displayName: 'Prometheus 监控',
    description: '集成 Prometheus 指标采集与告警',
    requiredEdition: 'ADVANCED',
    icon: <LineChartOutlined />,
    enabled: false,
    detailedDescription: 'Prometheus 监控集成提供完整的指标采集和告警能力，支持自定义指标、多维度聚合分析。',
    benefits: [
      'Prometheus 指标格式输出',
      '自定义业务指标采集',
      '告警规则灵活配置',
      'Grafana 可视化集成',
    ],
    useCases: [
      '服务健康状态监控',
      '性能瓶颈定位',
      '异常流量告警',
    ],
  },
  {
    name: 'JWT_AUTH',
    displayName: 'JWT 认证',
    description: 'JWT 无状态认证，支持自定义 Claims',
    requiredEdition: 'ADVANCED',
    icon: <SafetyCertificateOutlined />,
    enabled: false,
    detailedDescription: 'JWT 认证提供无状态认证方式，支持自定义 Claims、令牌刷新、黑名单等高级功能。',
    benefits: [
      '无状态认证',
      '自定义 Claims 支持',
      '令牌刷新机制',
      'JWT 黑名单',
    ],
    useCases: [
      '移动端应用认证',
      '微服务间调用认证',
      'SSO 单点登录集成',
    ],
  },
  {
    name: 'HMAC_AUTH',
    displayName: 'HMAC 签名认证',
    description: '基于 HMAC 的请求签名认证',
    requiredEdition: 'ADVANCED',
    icon: <SecurityScanOutlined />,
    enabled: false,
    detailedDescription: 'HMAC 签名认证提供高安全性的请求签名验证，防止请求篡改和重放攻击。',
    benefits: [
      '请求签名验证',
      '防篡改保护',
      '防重放攻击',
      '时间戳校验',
    ],
    useCases: [
      '开放 API 安全认证',
      '第三方系统集成',
      '金融级安全要求',
    ],
  },
  {
    name: 'GRAY_RELEASE_BASIC',
    displayName: '基础灰度发布',
    description: '基于百分比的基础灰度发布',
    requiredEdition: 'ADVANCED',
    icon: <RocketOutlined />,
    enabled: false,
    detailedDescription: '基础灰度发布支持按百分比进行流量分流，实现新版本的灰度发布。',
    benefits: [
      '百分比灰度',
      '动态权重调整',
      '灰度状态监控',
      '一键切流',
    ],
    useCases: [
      '新版本灰度发布',
      'A/B 测试',
      '小范围验证',
    ],
  },
  {
    name: 'ADVANCED_AUDIT',
    displayName: '高级审计',
    description: '配置变更时间线、导出分析报告',
    requiredEdition: 'ADVANCED',
    icon: <FileSearchOutlined />,
    enabled: false,
    detailedDescription: '高级审计记录所有配置变更的完整时间线，支持变更追溯、操作审计、合规检查，并可导出分析报告。',
    benefits: [
      '配置变更完整时间线',
      '操作人/时间/内容追溯',
      '变更对比与回滚',
      '审计报告导出',
    ],
    useCases: [
      '合规审计要求',
      '故障追溯分析',
      '配置变更管理',
    ],
  },
  // ==================== Enterprise Edition Features ====================
  {
    name: 'FULL_GRAY_RELEASE',
    displayName: '完整灰度发布',
    description: '支持 Header/Param/Cookie 多维度灰度规则',
    requiredEdition: 'ENTERPRISE',
    icon: <RocketOutlined />,
    enabled: false,
    detailedDescription: '完整灰度发布支持基于 Header、Query 参数、Cookie、用户 ID 等多维度的灰度规则，实现精准的灰度流量控制。',
    benefits: [
      'Header 灰度规则',
      'Query 参数灰度',
      'Cookie 灰度规则',
      '用户 ID/百分比灰度',
    ],
    useCases: [
      '按用户群体灰度',
      '内部员工优先体验',
      'VIP 用户专属版本',
    ],
  },
  {
    name: 'AI_COPilot',
    displayName: 'AI Copilot',
    description: 'AI 智能助手，路由配置生成、错误分析',
    requiredEdition: 'ENTERPRISE',
    icon: <RobotOutlined />,
    enabled: false,
    detailedDescription: 'AI Copilot 集成大语言模型，提供智能路由配置生成、错误日志分析、性能诊断建议等 AI 辅助功能。',
    benefits: [
      '自然语言生成路由配置',
      '错误日志智能分析',
      '性能瓶颈诊断建议',
      '配置优化推荐',
    ],
    useCases: [
      '快速配置复杂路由',
      '错误原因智能定位',
      '运维效率提升',
    ],
  },
  {
    name: 'STRESS_TEST',
    displayName: '一键压测',
    description: '内置压测工具，性能瓶颈分析',
    requiredEdition: 'ENTERPRISE',
    icon: <LineChartOutlined />,
    enabled: false,
    detailedDescription: '一键压测提供内置的性能测试工具，可快速发起压力测试，自动分析性能瓶颈，生成压测报告。',
    benefits: [
      '快速发起压力测试',
      '多场景压测模板',
      '性能瓶颈智能分析',
      '压测报告自动生成',
    ],
    useCases: [
      '上线前性能验证',
      '容量规划参考',
      '性能优化效果验证',
    ],
  },
  {
    name: 'MULTI_TENANT',
    displayName: '多租户隔离',
    description: '租户级别的配置隔离与权限控制',
    requiredEdition: 'ENTERPRISE',
    icon: <TeamOutlined />,
    enabled: false,
    detailedDescription: '多租户隔离支持租户级别的配置、路由、认证等资源隔离，每个租户独立管理，互不影响。',
    benefits: [
      '租户级配置隔离',
      '租户级路由管理',
      '租户级权限控制',
      '租户资源配额限制',
    ],
    useCases: [
      'SaaS 多租户场景',
      '企业多部门独立管理',
      '开发/测试环境隔离',
    ],
  },
  {
    name: 'FULL_CHAIN_TRACE',
    displayName: '全链路追踪',
    description: '请求全链路追踪与性能分析',
    requiredEdition: 'ENTERPRISE',
    icon: <ApiOutlined />,
    enabled: false,
    detailedDescription: '全链路追踪提供完整的请求生命周期追踪，从入口到后端服务调用全程可视化，便于性能瓶颈定位和故障排查。',
    benefits: [
      '请求链路可视化',
      '性能瓶颈定位',
      '调用依赖分析',
      '异常节点标记',
    ],
    useCases: [
      '微服务调用链分析',
      '性能瓶颈定位',
      '故障传播路径追踪',
    ],
  },
  {
    name: 'OAUTH2_AUTH',
    displayName: 'OAuth2 认证',
    description: 'OAuth2 标准协议认证集成',
    requiredEdition: 'ENTERPRISE',
    icon: <SafetyCertificateOutlined />,
    enabled: false,
    detailedDescription: 'OAuth2 认证支持标准 OAuth2 协议，可对接第三方身份提供商，实现企业级统一认证。',
    benefits: [
      'OAuth2 标准协议',
      '第三方身份提供商对接',
      '企业统一认证',
      'SSO 单点登录',
    ],
    useCases: [
      '企业统一身份认证',
      '第三方平台对接',
      'SSO 单点登录集成',
    ],
  },
  {
    name: 'SSL_MANAGEMENT',
    displayName: 'SSL 证书管理',
    description: 'SSL/TLS 证书统一管理',
    requiredEdition: 'ENTERPRISE',
    icon: <SecurityScanOutlined />,
    enabled: false,
    detailedDescription: 'SSL 证书管理提供证书的统一管理、自动更新、过期提醒等功能，确保 HTTPS 服务的安全性。',
    benefits: [
      '证书统一管理',
      '自动更新提醒',
      '过期预警通知',
      '证书版本管理',
    ],
    useCases: [
      'HTTPS 证书管理',
      '多域名证书统一管理',
      '证书过期预警',
    ],
  },
];

// Edition display config - BASIC is free (green), ADVANCED (blue), ENTERPRISE (purple/gold)
export const EDITION_CONFIG: Record<LicenseEdition, { color: string; bgColor: string; icon: React.ReactNode; label: string }> = {
  BASIC: { color: 'success', bgColor: '#22c55e', icon: <CheckCircleOutlined />, label: '基础版' },
  ADVANCED: { color: 'processing', bgColor: '#3b82f6', icon: <CrownOutlined />, label: '高级版' },
  ENTERPRISE: { color: 'purple', bgColor: '#7c3aed', icon: <CrownOutlined style={{ color: '#7c3aed' }} />, label: '企业版' },
};

// Status display config (backend list endpoint only returns ACTIVE/EXPIRED)
export const STATUS_CONFIG: Record<LicenseStatusCode, { color: string; icon: React.ReactNode }> = {
  ACTIVE: { color: 'success', icon: <CheckCircleOutlined /> },
  EXPIRED: { color: 'error', icon: <CloseCircleOutlined /> },
};
