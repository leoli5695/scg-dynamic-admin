<script setup lang="ts">
/**
 * License page — Vue port of gateway-ui (React) features/license/index.tsx.
 * Status overview, quota limits, feature entitlement matrix, activation flow
 * and the imported-license list.
 */
import { computed, onMounted, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import {
  CloudServerOutlined,
  CloseCircleOutlined,
  CompassOutlined,
  DeploymentUnitOutlined,
  KeyOutlined,
  ReloadOutlined,
  SafetyCertificateOutlined,
  SettingOutlined,
  ThunderboltOutlined,
} from '@/components/icons'
import { getLicenseList, getLicenseStatus } from '@/api/license'
import { message } from '@/utils/feedback'
import logger from '@/utils/logger'
import parseServerTime from '@/utils/serverTime'
import type { LicenseEdition, LicenseListItem, LicenseStatus } from '@/types/license'
import {
  EDITION_CONFIG,
  EDITION_TONE,
  FEATURE_CATALOG,
  type LicenseFeatureDef,
} from './constants/featureCatalog'
import ActivateLicenseModal from './components/ActivateLicenseModal.vue'
import FeatureDetailDrawer from './components/FeatureDetailDrawer.vue'
import FeatureMatrix from './components/FeatureMatrix.vue'
import './license.css'

const { t } = useI18n()

const loading = ref(true)
const hasLoaded = ref(false)
const loadFailed = ref(false)
const licenseInfo = ref<LicenseStatus | null>(null)
const licenseList = ref<LicenseListItem[]>([])
const features = ref<LicenseFeatureDef[]>(FEATURE_CATALOG.map((feature) => ({ ...feature })))
const activeTab = ref('status')
const activateOpen = ref(false)
const selectedFeature = ref<LicenseFeatureDef | null>(null)

const editionConfig = computed(() =>
  licenseInfo.value
    ? EDITION_CONFIG[licenseInfo.value.edition as LicenseEdition] ?? EDITION_CONFIG.BASIC
    : EDITION_CONFIG.BASIC,
)

const enabledCount = computed(
  () =>
    licenseInfo.value?.features?.length
    ?? features.value.filter((feature) => feature.enabled).length,
)

const validityText = computed(() => {
  const info = licenseInfo.value
  if (!info) return t('licenseUI.activation_hint')
  if (info.expired) return t('licenseUI.expired')
  if (info.daysRemaining != null && info.daysRemaining <= 365) {
    return t('licenseUI.days_remaining', { count: info.daysRemaining })
  }
  if (info.expiresAt) {
    return t('licenseUI.valid_until', {
      date: parseServerTime(info.expiresAt).toLocaleDateString(),
    })
  }
  return t('licenseUI.permanent')
})

const expiredDate = computed(() =>
  licenseInfo.value?.expiresAt
    ? parseServerTime(licenseInfo.value.expiresAt).toLocaleDateString()
    : '-',
)

function quotaValue(value: number | undefined): string {
  if (value === -1) return t('licenseUI.unlimited')
  if (value == null) return '—'
  return value.toLocaleString()
}

const quotaMetrics = computed(() => [
  {
    key: 'instances',
    label: t('licenseUI.instances'),
    value: quotaValue(licenseInfo.value?.quotas?.maxInstances),
    icon: CloudServerOutlined,
  },
  {
    key: 'routes',
    label: t('licenseUI.routes'),
    value: quotaValue(licenseInfo.value?.quotas?.maxRoutes),
    icon: CompassOutlined,
  },
  {
    key: 'services',
    label: t('licenseUI.services'),
    value: quotaValue(licenseInfo.value?.quotas?.maxServices),
    icon: DeploymentUnitOutlined,
  },
  {
    key: 'qps',
    label: t('licenseUI.qps'),
    value: quotaValue(licenseInfo.value?.quotas?.maxRateLimitQPS),
    icon: ThunderboltOutlined,
  },
])

function updateFeatureStatus(enabledFeatures: string[]): void {
  features.value = FEATURE_CATALOG.map((feature) => ({
    ...feature,
    enabled: enabledFeatures.includes(feature.name),
  }))
}

async function fetchLicenseData(): Promise<void> {
  loading.value = true
  loadFailed.value = false
  try {
    const status = await getLicenseStatus()
    licenseInfo.value = status.loaded ? status : null
    updateFeatureStatus(status.features || [])
    // Single imported license; may be unavailable for non-admin users.
    try {
      licenseList.value = (await getLicenseList()) || []
    } catch {
      logger.debug('License list API not available')
    }
  } catch (error) {
    logger.error('Failed to fetch license data', error)
    loadFailed.value = true
    message.error(t('common.fetch_error'))
  } finally {
    loading.value = false
    hasLoaded.value = true
  }
}

onMounted(fetchLicenseData)

const licenseColumns = computed(() => [
  { title: t('licenseUI.license_id'), dataIndex: 'licenseId', key: 'licenseId', width: 180 },
  { title: t('licenseUI.col_edition'), dataIndex: 'edition', key: 'edition', width: 120 },
  { title: t('licenseUI.customer'), dataIndex: 'customerName', key: 'customerName', width: 160 },
  { title: t('common.status'), dataIndex: 'status', key: 'status', width: 110 },
  { title: t('licenseUI.col_validity'), dataIndex: 'expiresAt', key: 'expiresAt', width: 180 },
  {
    title: t('licenseUI.col_feature_count'),
    dataIndex: 'features',
    key: 'features',
    width: 90,
  },
])

const tablePagination = computed(() => ({
  pageSize: 10,
  showSizeChanger: true,
  showTotal: (total: number) => t('licenseUI.total_records', { total }),
}))

function editionOf(edition: string) {
  return EDITION_CONFIG[edition as LicenseEdition] ?? EDITION_CONFIG.BASIC
}

function editionToneOf(edition: string): string {
  return EDITION_TONE[edition as LicenseEdition] ?? EDITION_TONE.BASIC
}

function formatDate(value: string | null | undefined): string {
  if (!value) return t('licenseUI.forever')
  return parseServerTime(value).toLocaleDateString()
}

function isExpiredDate(value: string | null | undefined): boolean {
  if (!value) return false
  return parseServerTime(value).getTime() < Date.now()
}
</script>

<template>
  <div class="license-page">
    <div class="page-header-modern license-page-header">
      <div class="page-header-left">
        <h3 class="page-title-main license-page-title">
          <span class="license-page-glyph" aria-hidden="true"><SafetyCertificateOutlined /></span>
          <span class="license-page-heading">
            <span class="license-page-name">{{ t('licenseUI.title') }}</span>
            <span class="license-page-subtitle">{{ t('licenseUI.subtitle') }}</span>
          </span>
        </h3>
      </div>
      <div class="page-header-right">
        <a-button :loading="loading" @click="fetchLicenseData">
          <template #icon><ReloadOutlined /></template>
          {{ t('common.refresh') }}
        </a-button>
        <a-button type="primary" @click="activateOpen = true">
          <template #icon><KeyOutlined /></template>
          {{ t('licenseUI.activate') }}
        </a-button>
      </div>
    </div>

    <a-alert
      v-if="!loading && !loadFailed && !licenseInfo"
      type="warning"
      show-icon
      class="license-alert"
      :message="t('licenseUI.no_license_title')"
      :description="t('licenseUI.no_license_desc')"
    />
    <a-alert
      v-if="licenseInfo?.expired"
      type="error"
      show-icon
      class="license-alert"
      :message="t('licenseUI.expired_title')"
      :description="t('licenseUI.expired_desc', { date: expiredDate })"
    />

    <a-card v-if="loadFailed && !hasLoaded && !loading" :bordered="false" class="license-state-card">
      <div class="license-state">
        <span class="license-state-icon is-error"><CloseCircleOutlined /></span>
        <p class="license-state-title">{{ t('common.fetch_error') }}</p>
        <div class="license-state-actions">
          <a-button type="primary" @click="fetchLicenseData">
            <template #icon><ReloadOutlined /></template>
            {{ t('common.refresh') }}
          </a-button>
        </div>
      </div>
    </a-card>

    <div v-else-if="loading && !hasLoaded" class="license-skeleton">
      <a-card :bordered="false">
        <a-skeleton active :title="{ width: '45%' }" :paragraph="{ rows: 3 }" />
      </a-card>
      <a-card :bordered="false">
        <a-skeleton active :title="{ width: '30%' }" :paragraph="{ rows: 4 }" />
      </a-card>
      <a-card :bordered="false" class="license-skeleton-wide">
        <a-skeleton active :title="{ width: '25%' }" :paragraph="{ rows: 5 }" />
      </a-card>
    </div>

    <a-tabs v-else v-model:activeKey="activeTab" class="license-tabs">
      <a-tab-pane key="status">
        <template #tab>
          <span class="license-tab-label"><SafetyCertificateOutlined />{{ t('licenseUI.tab_status') }}</span>
        </template>

        <div class="license-tab-content">
          <div class="license-overview">
            <section class="lg-panel license-status-card">
              <div class="license-edition">
                <span class="lg-icon-tile license-edition-icon">
                  <component :is="licenseInfo ? editionConfig.icon : KeyOutlined" />
                </span>
                <span class="license-edition-copy">
                  <span class="license-edition-name">
                    {{ licenseInfo ? t(editionConfig.labelKey) : t('licenseUI.not_activated') }}
                  </span>
                  <span
                    class="status-badge license-edition-chip"
                    :class="licenseInfo
                      ? (licenseInfo.valid ? 'status-badge-success' : 'status-badge-error')
                      : 'status-badge-neutral'"
                  >
                    {{ licenseInfo
                      ? (licenseInfo.valid ? t('licenseUI.valid') : t('licenseUI.invalid'))
                      : t('licenseUI.basic_access') }}
                  </span>
                </span>
              </div>
              <div class="license-summary-note">
                <span>{{ t('licenseUI.enabled_features', { count: enabledCount }) }}</span>
                <span :class="{ 'is-error': licenseInfo?.expired }">{{ validityText }}</span>
              </div>
            </section>

            <section class="lg-panel license-quota-card">
              <div class="license-quota-head">
                <h4 class="lg-section-title">{{ t('licenseUI.quota_title') }}</h4>
                <span class="license-quota-caption">{{ t('licenseUI.quota_hint') }}</span>
              </div>

              <div class="license-quota-grid">
                <div v-for="metric in quotaMetrics" :key="metric.key" class="license-quota-metric">
                  <span class="license-quota-metric-head">
                    <component :is="metric.icon" />
                    <span>{{ metric.label }}</span>
                  </span>
                  <span class="license-quota-metric-value">{{ metric.value }}</span>
                </div>
              </div>

              <a-divider class="license-quota-divider" />

              <a-descriptions
                v-if="licenseInfo"
                :column="{ xs: 1, sm: 2, xl: 4 }"
                layout="vertical"
                :colon="false"
                size="small"
                class="license-quota-meta"
              >
                <a-descriptions-item :label="t('licenseUI.license_id')">
                  <a-typography-text
                    :copyable="{ text: licenseInfo.licenseId || '' }"
                    class="license-id-text"
                  >
                    {{ licenseInfo.licenseId || '—' }}
                  </a-typography-text>
                </a-descriptions-item>
                <a-descriptions-item :label="t('licenseUI.customer')">
                  {{ licenseInfo.customerName || '-' }}
                </a-descriptions-item>
                <a-descriptions-item :label="t('licenseUI.organization')">
                  {{ licenseInfo.organization || '-' }}
                </a-descriptions-item>
                <a-descriptions-item :label="t('licenseUI.valid_until_label')">
                  {{ licenseInfo.expiresAt ? formatDate(licenseInfo.expiresAt) : t('licenseUI.forever') }}
                </a-descriptions-item>
              </a-descriptions>
            </section>
          </div>

          <FeatureMatrix :features="features" @select="selectedFeature = $event" />
        </div>
      </a-tab-pane>

      <a-tab-pane key="manage">
        <template #tab>
          <span class="license-tab-label"><SettingOutlined />{{ t('licenseUI.tab_manage') }}</span>
        </template>

        <div class="license-tab-content">
          <a-card :bordered="false" :title="t('licenseUI.list_title')" class="license-list-card">
            <a-table
              :data-source="licenseList"
              :columns="licenseColumns"
              row-key="licenseId"
              :pagination="tablePagination"
              :scroll="{ x: 1000 }"
              :loading="loading"
            >
              <template #emptyText>
                <a-empty :description="t('licenseUI.empty_list')" />
              </template>
              <template #bodyCell="{ column, record }">
                <template v-if="column.key === 'licenseId'">
                  <a-tooltip :title="record.licenseId">
                    <a-typography-text
                      :copyable="{ text: record.licenseId }"
                      class="license-id-text"
                    >
                      {{ record.licenseId.substring(0, 8) }}...
                    </a-typography-text>
                  </a-tooltip>
                </template>
                <template v-else-if="column.key === 'edition'">
                  <span class="status-badge license-mini-chip" :class="editionToneOf(record.edition)">
                    <component :is="editionOf(record.edition).icon" />
                    {{ t(editionOf(record.edition).labelKey) }}
                  </span>
                </template>
                <template v-else-if="column.key === 'customerName'">
                  <a-tooltip :title="record.customerEmail">{{ record.customerName || '-' }}</a-tooltip>
                </template>
                <template v-else-if="column.key === 'status'">
                  <a-badge :status="record.expired ? 'error' : 'success'" :text="record.status" />
                </template>
                <template v-else-if="column.key === 'expiresAt'">
                  <span
                    class="license-validity"
                    :class="{ 'is-expired': isExpiredDate(record.expiresAt) }"
                  >
                    {{ formatDate(record.expiresAt) }}
                    <CloseCircleOutlined v-if="isExpiredDate(record.expiresAt)" />
                  </span>
                </template>
                <template v-else-if="column.key === 'features'">
                  <span class="status-badge status-badge-neutral license-mini-chip">
                    {{ record.features?.length || 0 }}
                  </span>
                </template>
              </template>
            </a-table>
          </a-card>
        </div>
      </a-tab-pane>
    </a-tabs>

    <ActivateLicenseModal
      :open="activateOpen"
      @close="activateOpen = false"
      @activated="fetchLicenseData"
    />
    <FeatureDetailDrawer :feature="selectedFeature" @close="selectedFeature = null" />
  </div>
</template>