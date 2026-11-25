/**
 * Feature catalog entry shown in the authorization matrix.
 *
 * Text fields hold i18n keys (resolved with t() by the rendering components) so the
 * module-level catalog stays language-neutral and follows language switches.
 */
import type { Component } from 'vue'
import {
  BranchesOutlined,
  CheckCircleOutlined,
  ClockCircleOutlined,
  ClusterOutlined,
  CompassOutlined,
  ContainerOutlined,
  CrownOutlined,
  DashboardOutlined,
  DisconnectOutlined,
  ExperimentOutlined,
  FileSearchOutlined,
  GlobalOutlined,
  LineChartOutlined,
  PercentageOutlined,
  RocketOutlined,
  RobotOutlined,
  SafetyCertificateOutlined,
  SecurityScanOutlined,
  TeamOutlined,
  ThunderboltOutlined,
} from '@/components/icons'
import type { LicenseEdition } from '@/types/license'

export interface LicenseFeatureDef {
  /** Must match backend LicenseInfo.Feature enum exactly. */
  name: string
  displayNameKey: string
  descriptionKey: string
  requiredEdition: LicenseEdition
  icon: Component
  /** Default before license status arrives. */
  enabled: boolean
  detailedDescriptionKey?: string
  benefitKeys?: string[]
  useCaseKeys?: string[]
}

/** License status codes returned by GET /api/license/list. */
export type LicenseStatusCode = 'ACTIVE' | 'EXPIRED'

function defineFeature(
  name: string,
  requiredEdition: LicenseEdition,
  icon: Component,
  benefitCount = 4,
): LicenseFeatureDef {
  return {
    name,
    displayNameKey: `licenseUI.feature.${name}.name`,
    descriptionKey: `licenseUI.feature.${name}.desc`,
    detailedDescriptionKey: `licenseUI.feature.${name}.detail`,
    requiredEdition,
    icon,
    enabled: requiredEdition === 'BASIC',
    benefitKeys: Array.from(
      { length: benefitCount },
      (_, index) => `licenseUI.feature.${name}.benefit${index + 1}`,
    ),
    useCaseKeys: [1, 2, 3].map((index) => `licenseUI.feature.${name}.useCase${index}`),
  }
}

// Feature names must match the backend LicenseInfo.Feature enum exactly.
export const FEATURE_CATALOG: LicenseFeatureDef[] = [
  // ==================== Basic Edition Features (Free) ====================
  defineFeature('BASIC_ROUTING', 'BASIC', CompassOutlined),
  defineFeature('LOCAL_RATE_LIMIT', 'BASIC', ThunderboltOutlined),
  defineFeature('BASIC_AUTH', 'BASIC', SafetyCertificateOutlined, 3),
  defineFeature('DOCKER_DEPLOY', 'BASIC', ContainerOutlined),
  defineFeature('HEALTH_CHECK', 'BASIC', CheckCircleOutlined),
  defineFeature('SERVICE_DISCOVERY', 'BASIC', GlobalOutlined),
  defineFeature('TIMEOUT_CONFIG', 'BASIC', ClockCircleOutlined),
  defineFeature('BASIC_LOGGING', 'BASIC', FileSearchOutlined),
  defineFeature('BASIC_METRICS', 'BASIC', DashboardOutlined),
  // ==================== Advanced Edition Features ====================
  defineFeature('WEIGHTED_ROUTING', 'ADVANCED', PercentageOutlined),
  defineFeature('DISTRIBUTED_RATE_LIMIT', 'ADVANCED', ThunderboltOutlined),
  defineFeature('CIRCUIT_BREAKER', 'ADVANCED', DisconnectOutlined),
  defineFeature('K8S_INTEGRATION', 'ADVANCED', ClusterOutlined),
  defineFeature('PROMETHEUS_MONITORING', 'ADVANCED', LineChartOutlined),
  defineFeature('JWT_AUTH', 'ADVANCED', SafetyCertificateOutlined),
  defineFeature('HMAC_AUTH', 'ADVANCED', SecurityScanOutlined),
  defineFeature('GRAY_RELEASE_BASIC', 'ADVANCED', RocketOutlined),
  defineFeature('ADVANCED_AUDIT', 'ADVANCED', FileSearchOutlined),
  // ==================== Enterprise Edition Features ====================
  defineFeature('FULL_GRAY_RELEASE', 'ENTERPRISE', RocketOutlined),
  defineFeature('AI_COPilot', 'ENTERPRISE', RobotOutlined),
  defineFeature('STRESS_TEST', 'ENTERPRISE', ExperimentOutlined),
  defineFeature('MULTI_TENANT', 'ENTERPRISE', TeamOutlined),
  defineFeature('FULL_CHAIN_TRACE', 'ENTERPRISE', BranchesOutlined),
  defineFeature('OAUTH2_AUTH', 'ENTERPRISE', SafetyCertificateOutlined),
  defineFeature('SSL_MANAGEMENT', 'ENTERPRISE', SafetyCertificateOutlined),
]

// Edition display config: BASIC is free (green), ADVANCED (periwinkle), ENTERPRISE (violet).
export const EDITION_CONFIG: Record<LicenseEdition, { icon: Component; labelKey: string }> = {
  BASIC: { icon: CheckCircleOutlined, labelKey: 'licenseUI.edition.BASIC.label' },
  ADVANCED: { icon: CrownOutlined, labelKey: 'licenseUI.edition.ADVANCED.label' },
  ENTERPRISE: { icon: CrownOutlined, labelKey: 'licenseUI.edition.ENTERPRISE.label' },
}

/** Badge tone per edition, mapped onto the shared login-skin badge classes. */
export const EDITION_TONE: Record<LicenseEdition, string> = {
  BASIC: 'status-badge-success',
  ADVANCED: 'status-badge-info',
  ENTERPRISE: 'license-chip-enterprise',
}