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
import { palette } from '../../../styles/palette';

/**
 * Feature catalog entry shown in the authorization matrix.
 *
 * Text fields hold i18n keys (resolved with t() by the rendering components) so the
 * module-level catalog stays language-neutral and follows language switches.
 */
export interface LicenseFeatureDef {
  /** Must match backend LicenseInfo.Feature enum exactly. */
  name: string;
  displayNameKey: string;
  descriptionKey: string;
  requiredEdition: LicenseEdition;
  icon: React.ReactNode;
  /** Default before license status arrives. */
  enabled: boolean;
  detailedDescriptionKey?: string;
  benefitKeys?: string[];
  useCaseKeys?: string[];
}

/** License status codes returned by GET /api/license/list. */
export type LicenseStatusCode = 'ACTIVE' | 'EXPIRED';

// Feature catalog with edition requirements
// Note: Feature names must match backend LicenseInfo.Feature enum exactly
export const FEATURE_CATALOG: LicenseFeatureDef[] = [
  // ==================== Basic Edition Features (Free) ====================
  {
    name: 'BASIC_ROUTING',
    displayNameKey: 'licenseUI.feature.BASIC_ROUTING.name',
    descriptionKey: 'licenseUI.feature.BASIC_ROUTING.desc',
    requiredEdition: 'BASIC',
    icon: <ApiOutlined />,
    enabled: true,
    detailedDescriptionKey: 'licenseUI.feature.BASIC_ROUTING.detail',
    benefitKeys: [
      'licenseUI.feature.BASIC_ROUTING.benefit1',
      'licenseUI.feature.BASIC_ROUTING.benefit2',
      'licenseUI.feature.BASIC_ROUTING.benefit3',
      'licenseUI.feature.BASIC_ROUTING.benefit4',
    ],
    useCaseKeys: [
      'licenseUI.feature.BASIC_ROUTING.useCase1',
      'licenseUI.feature.BASIC_ROUTING.useCase2',
      'licenseUI.feature.BASIC_ROUTING.useCase3',
    ],
  },
  {
    name: 'LOCAL_RATE_LIMIT',
    displayNameKey: 'licenseUI.feature.LOCAL_RATE_LIMIT.name',
    descriptionKey: 'licenseUI.feature.LOCAL_RATE_LIMIT.desc',
    requiredEdition: 'BASIC',
    icon: <ThunderboltOutlined />,
    enabled: true,
    detailedDescriptionKey: 'licenseUI.feature.LOCAL_RATE_LIMIT.detail',
    benefitKeys: [
      'licenseUI.feature.LOCAL_RATE_LIMIT.benefit1',
      'licenseUI.feature.LOCAL_RATE_LIMIT.benefit2',
      'licenseUI.feature.LOCAL_RATE_LIMIT.benefit3',
      'licenseUI.feature.LOCAL_RATE_LIMIT.benefit4',
    ],
    useCaseKeys: [
      'licenseUI.feature.LOCAL_RATE_LIMIT.useCase1',
      'licenseUI.feature.LOCAL_RATE_LIMIT.useCase2',
      'licenseUI.feature.LOCAL_RATE_LIMIT.useCase3',
    ],
  },
  {
    name: 'BASIC_AUTH',
    displayNameKey: 'licenseUI.feature.BASIC_AUTH.name',
    descriptionKey: 'licenseUI.feature.BASIC_AUTH.desc',
    requiredEdition: 'BASIC',
    icon: <SafetyCertificateOutlined />,
    enabled: true,
    detailedDescriptionKey: 'licenseUI.feature.BASIC_AUTH.detail',
    benefitKeys: [
      'licenseUI.feature.BASIC_AUTH.benefit1',
      'licenseUI.feature.BASIC_AUTH.benefit2',
      'licenseUI.feature.BASIC_AUTH.benefit3',
    ],
    useCaseKeys: [
      'licenseUI.feature.BASIC_AUTH.useCase1',
      'licenseUI.feature.BASIC_AUTH.useCase2',
      'licenseUI.feature.BASIC_AUTH.useCase3',
    ],
  },
  {
    name: 'DOCKER_DEPLOY',
    displayNameKey: 'licenseUI.feature.DOCKER_DEPLOY.name',
    descriptionKey: 'licenseUI.feature.DOCKER_DEPLOY.desc',
    requiredEdition: 'BASIC',
    icon: <CloudOutlined />,
    enabled: true,
    detailedDescriptionKey: 'licenseUI.feature.DOCKER_DEPLOY.detail',
    benefitKeys: [
      'licenseUI.feature.DOCKER_DEPLOY.benefit1',
      'licenseUI.feature.DOCKER_DEPLOY.benefit2',
      'licenseUI.feature.DOCKER_DEPLOY.benefit3',
      'licenseUI.feature.DOCKER_DEPLOY.benefit4',
    ],
    useCaseKeys: [
      'licenseUI.feature.DOCKER_DEPLOY.useCase1',
      'licenseUI.feature.DOCKER_DEPLOY.useCase2',
      'licenseUI.feature.DOCKER_DEPLOY.useCase3',
    ],
  },
  {
    name: 'HEALTH_CHECK',
    displayNameKey: 'licenseUI.feature.HEALTH_CHECK.name',
    descriptionKey: 'licenseUI.feature.HEALTH_CHECK.desc',
    requiredEdition: 'BASIC',
    icon: <CheckCircleOutlined />,
    enabled: true,
    detailedDescriptionKey: 'licenseUI.feature.HEALTH_CHECK.detail',
    benefitKeys: [
      'licenseUI.feature.HEALTH_CHECK.benefit1',
      'licenseUI.feature.HEALTH_CHECK.benefit2',
      'licenseUI.feature.HEALTH_CHECK.benefit3',
      'licenseUI.feature.HEALTH_CHECK.benefit4',
    ],
    useCaseKeys: [
      'licenseUI.feature.HEALTH_CHECK.useCase1',
      'licenseUI.feature.HEALTH_CHECK.useCase2',
      'licenseUI.feature.HEALTH_CHECK.useCase3',
    ],
  },
  {
    name: 'SERVICE_DISCOVERY',
    displayNameKey: 'licenseUI.feature.SERVICE_DISCOVERY.name',
    descriptionKey: 'licenseUI.feature.SERVICE_DISCOVERY.desc',
    requiredEdition: 'BASIC',
    icon: <GlobalOutlined />,
    enabled: true,
    detailedDescriptionKey: 'licenseUI.feature.SERVICE_DISCOVERY.detail',
    benefitKeys: [
      'licenseUI.feature.SERVICE_DISCOVERY.benefit1',
      'licenseUI.feature.SERVICE_DISCOVERY.benefit2',
      'licenseUI.feature.SERVICE_DISCOVERY.benefit3',
      'licenseUI.feature.SERVICE_DISCOVERY.benefit4',
    ],
    useCaseKeys: [
      'licenseUI.feature.SERVICE_DISCOVERY.useCase1',
      'licenseUI.feature.SERVICE_DISCOVERY.useCase2',
      'licenseUI.feature.SERVICE_DISCOVERY.useCase3',
    ],
  },
  {
    name: 'TIMEOUT_CONFIG',
    displayNameKey: 'licenseUI.feature.TIMEOUT_CONFIG.name',
    descriptionKey: 'licenseUI.feature.TIMEOUT_CONFIG.desc',
    requiredEdition: 'BASIC',
    icon: <HistoryOutlined />,
    enabled: true,
    detailedDescriptionKey: 'licenseUI.feature.TIMEOUT_CONFIG.detail',
    benefitKeys: [
      'licenseUI.feature.TIMEOUT_CONFIG.benefit1',
      'licenseUI.feature.TIMEOUT_CONFIG.benefit2',
      'licenseUI.feature.TIMEOUT_CONFIG.benefit3',
      'licenseUI.feature.TIMEOUT_CONFIG.benefit4',
    ],
    useCaseKeys: [
      'licenseUI.feature.TIMEOUT_CONFIG.useCase1',
      'licenseUI.feature.TIMEOUT_CONFIG.useCase2',
      'licenseUI.feature.TIMEOUT_CONFIG.useCase3',
    ],
  },
  {
    name: 'BASIC_LOGGING',
    displayNameKey: 'licenseUI.feature.BASIC_LOGGING.name',
    descriptionKey: 'licenseUI.feature.BASIC_LOGGING.desc',
    requiredEdition: 'BASIC',
    icon: <FileSearchOutlined />,
    enabled: true,
    detailedDescriptionKey: 'licenseUI.feature.BASIC_LOGGING.detail',
    benefitKeys: [
      'licenseUI.feature.BASIC_LOGGING.benefit1',
      'licenseUI.feature.BASIC_LOGGING.benefit2',
      'licenseUI.feature.BASIC_LOGGING.benefit3',
      'licenseUI.feature.BASIC_LOGGING.benefit4',
    ],
    useCaseKeys: [
      'licenseUI.feature.BASIC_LOGGING.useCase1',
      'licenseUI.feature.BASIC_LOGGING.useCase2',
      'licenseUI.feature.BASIC_LOGGING.useCase3',
    ],
  },
  {
    name: 'BASIC_METRICS',
    displayNameKey: 'licenseUI.feature.BASIC_METRICS.name',
    descriptionKey: 'licenseUI.feature.BASIC_METRICS.desc',
    requiredEdition: 'BASIC',
    icon: <DashboardOutlined />,
    enabled: true,
    detailedDescriptionKey: 'licenseUI.feature.BASIC_METRICS.detail',
    benefitKeys: [
      'licenseUI.feature.BASIC_METRICS.benefit1',
      'licenseUI.feature.BASIC_METRICS.benefit2',
      'licenseUI.feature.BASIC_METRICS.benefit3',
      'licenseUI.feature.BASIC_METRICS.benefit4',
    ],
    useCaseKeys: [
      'licenseUI.feature.BASIC_METRICS.useCase1',
      'licenseUI.feature.BASIC_METRICS.useCase2',
      'licenseUI.feature.BASIC_METRICS.useCase3',
    ],
  },
  // ==================== Advanced Edition Features ====================
  {
    name: 'WEIGHTED_ROUTING',
    displayNameKey: 'licenseUI.feature.WEIGHTED_ROUTING.name',
    descriptionKey: 'licenseUI.feature.WEIGHTED_ROUTING.desc',
    requiredEdition: 'ADVANCED',
    icon: <ApiOutlined />,
    enabled: false,
    detailedDescriptionKey: 'licenseUI.feature.WEIGHTED_ROUTING.detail',
    benefitKeys: [
      'licenseUI.feature.WEIGHTED_ROUTING.benefit1',
      'licenseUI.feature.WEIGHTED_ROUTING.benefit2',
      'licenseUI.feature.WEIGHTED_ROUTING.benefit3',
      'licenseUI.feature.WEIGHTED_ROUTING.benefit4',
    ],
    useCaseKeys: [
      'licenseUI.feature.WEIGHTED_ROUTING.useCase1',
      'licenseUI.feature.WEIGHTED_ROUTING.useCase2',
      'licenseUI.feature.WEIGHTED_ROUTING.useCase3',
    ],
  },
  {
    name: 'DISTRIBUTED_RATE_LIMIT',
    displayNameKey: 'licenseUI.feature.DISTRIBUTED_RATE_LIMIT.name',
    descriptionKey: 'licenseUI.feature.DISTRIBUTED_RATE_LIMIT.desc',
    requiredEdition: 'ADVANCED',
    icon: <ThunderboltOutlined />,
    enabled: false,
    detailedDescriptionKey: 'licenseUI.feature.DISTRIBUTED_RATE_LIMIT.detail',
    benefitKeys: [
      'licenseUI.feature.DISTRIBUTED_RATE_LIMIT.benefit1',
      'licenseUI.feature.DISTRIBUTED_RATE_LIMIT.benefit2',
      'licenseUI.feature.DISTRIBUTED_RATE_LIMIT.benefit3',
      'licenseUI.feature.DISTRIBUTED_RATE_LIMIT.benefit4',
    ],
    useCaseKeys: [
      'licenseUI.feature.DISTRIBUTED_RATE_LIMIT.useCase1',
      'licenseUI.feature.DISTRIBUTED_RATE_LIMIT.useCase2',
      'licenseUI.feature.DISTRIBUTED_RATE_LIMIT.useCase3',
    ],
  },
  {
    name: 'CIRCUIT_BREAKER',
    displayNameKey: 'licenseUI.feature.CIRCUIT_BREAKER.name',
    descriptionKey: 'licenseUI.feature.CIRCUIT_BREAKER.desc',
    requiredEdition: 'ADVANCED',
    icon: <SafetyCertificateOutlined />,
    enabled: false,
    detailedDescriptionKey: 'licenseUI.feature.CIRCUIT_BREAKER.detail',
    benefitKeys: [
      'licenseUI.feature.CIRCUIT_BREAKER.benefit1',
      'licenseUI.feature.CIRCUIT_BREAKER.benefit2',
      'licenseUI.feature.CIRCUIT_BREAKER.benefit3',
      'licenseUI.feature.CIRCUIT_BREAKER.benefit4',
    ],
    useCaseKeys: [
      'licenseUI.feature.CIRCUIT_BREAKER.useCase1',
      'licenseUI.feature.CIRCUIT_BREAKER.useCase2',
      'licenseUI.feature.CIRCUIT_BREAKER.useCase3',
    ],
  },
  {
    name: 'K8S_INTEGRATION',
    displayNameKey: 'licenseUI.feature.K8S_INTEGRATION.name',
    descriptionKey: 'licenseUI.feature.K8S_INTEGRATION.desc',
    requiredEdition: 'ADVANCED',
    icon: <CloudOutlined />,
    enabled: false,
    detailedDescriptionKey: 'licenseUI.feature.K8S_INTEGRATION.detail',
    benefitKeys: [
      'licenseUI.feature.K8S_INTEGRATION.benefit1',
      'licenseUI.feature.K8S_INTEGRATION.benefit2',
      'licenseUI.feature.K8S_INTEGRATION.benefit3',
      'licenseUI.feature.K8S_INTEGRATION.benefit4',
    ],
    useCaseKeys: [
      'licenseUI.feature.K8S_INTEGRATION.useCase1',
      'licenseUI.feature.K8S_INTEGRATION.useCase2',
      'licenseUI.feature.K8S_INTEGRATION.useCase3',
    ],
  },
  {
    name: 'PROMETHEUS_MONITORING',
    displayNameKey: 'licenseUI.feature.PROMETHEUS_MONITORING.name',
    descriptionKey: 'licenseUI.feature.PROMETHEUS_MONITORING.desc',
    requiredEdition: 'ADVANCED',
    icon: <LineChartOutlined />,
    enabled: false,
    detailedDescriptionKey: 'licenseUI.feature.PROMETHEUS_MONITORING.detail',
    benefitKeys: [
      'licenseUI.feature.PROMETHEUS_MONITORING.benefit1',
      'licenseUI.feature.PROMETHEUS_MONITORING.benefit2',
      'licenseUI.feature.PROMETHEUS_MONITORING.benefit3',
      'licenseUI.feature.PROMETHEUS_MONITORING.benefit4',
    ],
    useCaseKeys: [
      'licenseUI.feature.PROMETHEUS_MONITORING.useCase1',
      'licenseUI.feature.PROMETHEUS_MONITORING.useCase2',
      'licenseUI.feature.PROMETHEUS_MONITORING.useCase3',
    ],
  },
  {
    name: 'JWT_AUTH',
    displayNameKey: 'licenseUI.feature.JWT_AUTH.name',
    descriptionKey: 'licenseUI.feature.JWT_AUTH.desc',
    requiredEdition: 'ADVANCED',
    icon: <SafetyCertificateOutlined />,
    enabled: false,
    detailedDescriptionKey: 'licenseUI.feature.JWT_AUTH.detail',
    benefitKeys: [
      'licenseUI.feature.JWT_AUTH.benefit1',
      'licenseUI.feature.JWT_AUTH.benefit2',
      'licenseUI.feature.JWT_AUTH.benefit3',
      'licenseUI.feature.JWT_AUTH.benefit4',
    ],
    useCaseKeys: [
      'licenseUI.feature.JWT_AUTH.useCase1',
      'licenseUI.feature.JWT_AUTH.useCase2',
      'licenseUI.feature.JWT_AUTH.useCase3',
    ],
  },
  {
    name: 'HMAC_AUTH',
    displayNameKey: 'licenseUI.feature.HMAC_AUTH.name',
    descriptionKey: 'licenseUI.feature.HMAC_AUTH.desc',
    requiredEdition: 'ADVANCED',
    icon: <SecurityScanOutlined />,
    enabled: false,
    detailedDescriptionKey: 'licenseUI.feature.HMAC_AUTH.detail',
    benefitKeys: [
      'licenseUI.feature.HMAC_AUTH.benefit1',
      'licenseUI.feature.HMAC_AUTH.benefit2',
      'licenseUI.feature.HMAC_AUTH.benefit3',
      'licenseUI.feature.HMAC_AUTH.benefit4',
    ],
    useCaseKeys: [
      'licenseUI.feature.HMAC_AUTH.useCase1',
      'licenseUI.feature.HMAC_AUTH.useCase2',
      'licenseUI.feature.HMAC_AUTH.useCase3',
    ],
  },
  {
    name: 'GRAY_RELEASE_BASIC',
    displayNameKey: 'licenseUI.feature.GRAY_RELEASE_BASIC.name',
    descriptionKey: 'licenseUI.feature.GRAY_RELEASE_BASIC.desc',
    requiredEdition: 'ADVANCED',
    icon: <RocketOutlined />,
    enabled: false,
    detailedDescriptionKey: 'licenseUI.feature.GRAY_RELEASE_BASIC.detail',
    benefitKeys: [
      'licenseUI.feature.GRAY_RELEASE_BASIC.benefit1',
      'licenseUI.feature.GRAY_RELEASE_BASIC.benefit2',
      'licenseUI.feature.GRAY_RELEASE_BASIC.benefit3',
      'licenseUI.feature.GRAY_RELEASE_BASIC.benefit4',
    ],
    useCaseKeys: [
      'licenseUI.feature.GRAY_RELEASE_BASIC.useCase1',
      'licenseUI.feature.GRAY_RELEASE_BASIC.useCase2',
      'licenseUI.feature.GRAY_RELEASE_BASIC.useCase3',
    ],
  },
  {
    name: 'ADVANCED_AUDIT',
    displayNameKey: 'licenseUI.feature.ADVANCED_AUDIT.name',
    descriptionKey: 'licenseUI.feature.ADVANCED_AUDIT.desc',
    requiredEdition: 'ADVANCED',
    icon: <FileSearchOutlined />,
    enabled: false,
    detailedDescriptionKey: 'licenseUI.feature.ADVANCED_AUDIT.detail',
    benefitKeys: [
      'licenseUI.feature.ADVANCED_AUDIT.benefit1',
      'licenseUI.feature.ADVANCED_AUDIT.benefit2',
      'licenseUI.feature.ADVANCED_AUDIT.benefit3',
      'licenseUI.feature.ADVANCED_AUDIT.benefit4',
    ],
    useCaseKeys: [
      'licenseUI.feature.ADVANCED_AUDIT.useCase1',
      'licenseUI.feature.ADVANCED_AUDIT.useCase2',
      'licenseUI.feature.ADVANCED_AUDIT.useCase3',
    ],
  },
  // ==================== Enterprise Edition Features ====================
  {
    name: 'FULL_GRAY_RELEASE',
    displayNameKey: 'licenseUI.feature.FULL_GRAY_RELEASE.name',
    descriptionKey: 'licenseUI.feature.FULL_GRAY_RELEASE.desc',
    requiredEdition: 'ENTERPRISE',
    icon: <RocketOutlined />,
    enabled: false,
    detailedDescriptionKey: 'licenseUI.feature.FULL_GRAY_RELEASE.detail',
    benefitKeys: [
      'licenseUI.feature.FULL_GRAY_RELEASE.benefit1',
      'licenseUI.feature.FULL_GRAY_RELEASE.benefit2',
      'licenseUI.feature.FULL_GRAY_RELEASE.benefit3',
      'licenseUI.feature.FULL_GRAY_RELEASE.benefit4',
    ],
    useCaseKeys: [
      'licenseUI.feature.FULL_GRAY_RELEASE.useCase1',
      'licenseUI.feature.FULL_GRAY_RELEASE.useCase2',
      'licenseUI.feature.FULL_GRAY_RELEASE.useCase3',
    ],
  },
  {
    name: 'AI_COPilot',
    displayNameKey: 'licenseUI.feature.AI_COPilot.name',
    descriptionKey: 'licenseUI.feature.AI_COPilot.desc',
    requiredEdition: 'ENTERPRISE',
    icon: <RobotOutlined />,
    enabled: false,
    detailedDescriptionKey: 'licenseUI.feature.AI_COPilot.detail',
    benefitKeys: [
      'licenseUI.feature.AI_COPilot.benefit1',
      'licenseUI.feature.AI_COPilot.benefit2',
      'licenseUI.feature.AI_COPilot.benefit3',
      'licenseUI.feature.AI_COPilot.benefit4',
    ],
    useCaseKeys: [
      'licenseUI.feature.AI_COPilot.useCase1',
      'licenseUI.feature.AI_COPilot.useCase2',
      'licenseUI.feature.AI_COPilot.useCase3',
    ],
  },
  {
    name: 'STRESS_TEST',
    displayNameKey: 'licenseUI.feature.STRESS_TEST.name',
    descriptionKey: 'licenseUI.feature.STRESS_TEST.desc',
    requiredEdition: 'ENTERPRISE',
    icon: <LineChartOutlined />,
    enabled: false,
    detailedDescriptionKey: 'licenseUI.feature.STRESS_TEST.detail',
    benefitKeys: [
      'licenseUI.feature.STRESS_TEST.benefit1',
      'licenseUI.feature.STRESS_TEST.benefit2',
      'licenseUI.feature.STRESS_TEST.benefit3',
      'licenseUI.feature.STRESS_TEST.benefit4',
    ],
    useCaseKeys: [
      'licenseUI.feature.STRESS_TEST.useCase1',
      'licenseUI.feature.STRESS_TEST.useCase2',
      'licenseUI.feature.STRESS_TEST.useCase3',
    ],
  },
  {
    name: 'MULTI_TENANT',
    displayNameKey: 'licenseUI.feature.MULTI_TENANT.name',
    descriptionKey: 'licenseUI.feature.MULTI_TENANT.desc',
    requiredEdition: 'ENTERPRISE',
    icon: <TeamOutlined />,
    enabled: false,
    detailedDescriptionKey: 'licenseUI.feature.MULTI_TENANT.detail',
    benefitKeys: [
      'licenseUI.feature.MULTI_TENANT.benefit1',
      'licenseUI.feature.MULTI_TENANT.benefit2',
      'licenseUI.feature.MULTI_TENANT.benefit3',
      'licenseUI.feature.MULTI_TENANT.benefit4',
    ],
    useCaseKeys: [
      'licenseUI.feature.MULTI_TENANT.useCase1',
      'licenseUI.feature.MULTI_TENANT.useCase2',
      'licenseUI.feature.MULTI_TENANT.useCase3',
    ],
  },
  {
    name: 'FULL_CHAIN_TRACE',
    displayNameKey: 'licenseUI.feature.FULL_CHAIN_TRACE.name',
    descriptionKey: 'licenseUI.feature.FULL_CHAIN_TRACE.desc',
    requiredEdition: 'ENTERPRISE',
    icon: <ApiOutlined />,
    enabled: false,
    detailedDescriptionKey: 'licenseUI.feature.FULL_CHAIN_TRACE.detail',
    benefitKeys: [
      'licenseUI.feature.FULL_CHAIN_TRACE.benefit1',
      'licenseUI.feature.FULL_CHAIN_TRACE.benefit2',
      'licenseUI.feature.FULL_CHAIN_TRACE.benefit3',
      'licenseUI.feature.FULL_CHAIN_TRACE.benefit4',
    ],
    useCaseKeys: [
      'licenseUI.feature.FULL_CHAIN_TRACE.useCase1',
      'licenseUI.feature.FULL_CHAIN_TRACE.useCase2',
      'licenseUI.feature.FULL_CHAIN_TRACE.useCase3',
    ],
  },
  {
    name: 'OAUTH2_AUTH',
    displayNameKey: 'licenseUI.feature.OAUTH2_AUTH.name',
    descriptionKey: 'licenseUI.feature.OAUTH2_AUTH.desc',
    requiredEdition: 'ENTERPRISE',
    icon: <SafetyCertificateOutlined />,
    enabled: false,
    detailedDescriptionKey: 'licenseUI.feature.OAUTH2_AUTH.detail',
    benefitKeys: [
      'licenseUI.feature.OAUTH2_AUTH.benefit1',
      'licenseUI.feature.OAUTH2_AUTH.benefit2',
      'licenseUI.feature.OAUTH2_AUTH.benefit3',
      'licenseUI.feature.OAUTH2_AUTH.benefit4',
    ],
    useCaseKeys: [
      'licenseUI.feature.OAUTH2_AUTH.useCase1',
      'licenseUI.feature.OAUTH2_AUTH.useCase2',
      'licenseUI.feature.OAUTH2_AUTH.useCase3',
    ],
  },
  {
    name: 'SSL_MANAGEMENT',
    displayNameKey: 'licenseUI.feature.SSL_MANAGEMENT.name',
    descriptionKey: 'licenseUI.feature.SSL_MANAGEMENT.desc',
    requiredEdition: 'ENTERPRISE',
    icon: <SecurityScanOutlined />,
    enabled: false,
    detailedDescriptionKey: 'licenseUI.feature.SSL_MANAGEMENT.detail',
    benefitKeys: [
      'licenseUI.feature.SSL_MANAGEMENT.benefit1',
      'licenseUI.feature.SSL_MANAGEMENT.benefit2',
      'licenseUI.feature.SSL_MANAGEMENT.benefit3',
      'licenseUI.feature.SSL_MANAGEMENT.benefit4',
    ],
    useCaseKeys: [
      'licenseUI.feature.SSL_MANAGEMENT.useCase1',
      'licenseUI.feature.SSL_MANAGEMENT.useCase2',
      'licenseUI.feature.SSL_MANAGEMENT.useCase3',
    ],
  },
];

// Edition display config - BASIC is free (green), ADVANCED (blue), ENTERPRISE (purple/gold)
// labelKey is an i18n key: resolve it with t() where the edition is rendered.
export const EDITION_CONFIG: Record<LicenseEdition, { color: string; bgColor: string; icon: React.ReactNode; labelKey: string }> = {
  BASIC: { color: 'success', bgColor: palette.success, icon: <CheckCircleOutlined />, labelKey: 'licenseUI.edition.BASIC.label' },
  ADVANCED: { color: 'processing', bgColor: palette.primary, icon: <CrownOutlined />, labelKey: 'licenseUI.edition.ADVANCED.label' },
  ENTERPRISE: { color: 'purple', bgColor: palette.accent, icon: <CrownOutlined style={{ color: palette.accent }} />, labelKey: 'licenseUI.edition.ENTERPRISE.label' },
};

// Status display config (backend list endpoint only returns ACTIVE/EXPIRED)
export const STATUS_CONFIG: Record<LicenseStatusCode, { color: string; icon: React.ReactNode }> = {
  ACTIVE: { color: 'success', icon: <CheckCircleOutlined /> },
  EXPIRED: { color: 'error', icon: <CloseCircleOutlined /> },
};
