<script setup lang="ts">
/**
 * License page on the Vue-native design layer: status overview, quota limits,
 * feature entitlement matrix, activation flow and the imported-license list.
 * API calls and payloads are unchanged.
 */
import { computed, onMounted, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import {
  CloseCircleOutlined, CloudServerOutlined, CompassOutlined, CopyOutlined,
  DeploymentUnitOutlined, KeyOutlined, ReloadOutlined, SafetyCertificateOutlined,
  SettingOutlined, ThunderboltOutlined,
} from '@/components/icons'
import { getLicenseList, getLicenseStatus } from '@/api/license'
import { message } from '@/utils/feedback'
import { copyText } from '@/utils/clipboard'
import logger from '@/utils/logger'
import parseServerTime from '@/utils/serverTime'
import type { LicenseEdition, LicenseListItem, LicenseStatus } from '@/types/license'
import VnBadge from '@/components/vn/VnBadge.vue'
import VnIconButton from '@/components/vn/VnIconButton.vue'
import VnSegmented, { type VnSegmentOption } from '@/components/vn/VnSegmented.vue'
import VnStatRail, { type VnStatItem } from '@/components/vn/VnStatRail.vue'
import VnState from '@/components/vn/VnState.vue'
import VnToolbar from '@/components/vn/VnToolbar.vue'
import {
  EDITION_CONFIG,
  FEATURE_CATALOG,
  type LicenseFeatureDef,
} from './constants/featureCatalog'
import ActivateLicenseModal from './components/ActivateLicenseModal.vue'
import FeatureDetailDrawer from './components/FeatureDetailDrawer.vue'
import FeatureMatrix from './components/FeatureMatrix.vue'
import '@/styles/vue-native.css'
import '@/styles/vn-pages.css'
import './license-vue-native.css'

defineOptions({ name: 'LicensePage' })

const { t } = useI18n()

const loading = ref(true)
const hasLoaded = ref(false)
const loadFailed = ref(false)
const licenseInfo = ref<LicenseStatus | null>(null)
const licenseList = ref<LicenseListItem[]>([])
const features = ref<LicenseFeatureDef[]>(FEATURE_CATALOG.map((feature) => ({ ...feature })))
const activeTab = ref<'status' | 'manage'>('status')
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
  { key: 'instances', label: t('licenseUI.instances'), value: quotaValue(licenseInfo.value?.quotas?.maxInstances), icon: CloudServerOutlined },
  { key: 'routes', label: t('licenseUI.routes'), value: quotaValue(licenseInfo.value?.quotas?.maxRoutes), icon: CompassOutlined },
  { key: 'services', label: t('licenseUI.services'), value: quotaValue(licenseInfo.value?.quotas?.maxServices), icon: DeploymentUnitOutlined },
  { key: 'qps', label: t('licenseUI.qps'), value: quotaValue(licenseInfo.value?.quotas?.maxRateLimitQPS), icon: ThunderboltOutlined },
])

const tabOptions = computed<VnSegmentOption<'status' | 'manage'>[]>(() => [
  { key: 'status', label: t('licenseUI.tab_status'), icon: SafetyCertificateOutlined },
  { key: 'manage', label: t('licenseUI.tab_manage'), icon: SettingOutlined },
])

const statItems = computed<VnStatItem[]>(() => [
  { key: 'enabled', value: enabledCount.value, label: t('licenseUI.feature_matrix'), icon: SafetyCertificateOutlined, accent: 'var(--vn-ok)' },
  { key: 'licenses', value: licenseList.value.length, label: t('licenseUI.list_title'), icon: KeyOutlined, accent: 'var(--vn-info)' },
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
  { title: t('licenseUI.col_feature_count'), dataIndex: 'features', key: 'features', width: 90 },
  { title: t('common.actions'), key: 'actions', width: 70, align: 'center' },
])

const tablePagination = computed(() => ({
  pageSize: 10,
  showSizeChanger: true,
  showTotal: (total: number) => t('licenseUI.total_records', { total }),
}))

function editionOf(edition: string) {
  return EDITION_CONFIG[edition as LicenseEdition] ?? EDITION_CONFIG.BASIC
}

function formatDate(value: string | null | undefined): string {
  if (!value) return t('licenseUI.forever')
  return parseServerTime(value).toLocaleDateString()
}

function isExpiredDate(value: string | null | undefined): boolean {
  if (!value) return false
  return parseServerTime(value).getTime() < Date.now()
}

async function copyLicenseId(text: string) {
  if (!text) return
  if (await copyText(text)) message.success(t('message.copy_success'))
  else message.error(t('message.copy_failed'))
}
</script>

<template>
  <div class="license-page vn-license vn-scope vnp-scope">
    <VnToolbar :title="t('licenseUI.title')" :lede="t('licenseUI.subtitle')">
      <template #actions>
        <VnIconButton :icon="ReloadOutlined" :label="t('common.refresh')" :disabled="loading" @click="fetchLicenseData" />
        <a-button type="primary" class="create-btn" @click="activateOpen = true">
          <template #icon><KeyOutlined /></template>{{ t('licenseUI.activate') }}
        </a-button>
      </template>
    </VnToolbar>

    <div class="vn-metarow">
      <VnSegmented v-model="activeTab" :options="tabOptions" :label="t('licenseUI.title')" />
      <VnStatRail :items="statItems" :label="t('licenseUI.title')" />
      <span class="vn-filterbar-spacer" />
      <span class="vn-result-count" role="status">{{ t('licenseUI.feature_count', { enabled: enabledCount, total: features.length }) }}</span>
    </div>

    <a-alert
      v-if="!loading && !loadFailed && !licenseInfo"
      type="warning"
      show-icon
      class="vn-license-alert"
      :message="t('licenseUI.no_license_title')"
      :description="t('licenseUI.no_license_desc')"
    />
    <a-alert
      v-if="licenseInfo?.expired"
      type="error"
      show-icon
      class="vn-license-alert"
      :message="t('licenseUI.expired_title')"
      :description="t('licenseUI.expired_desc', { date: expiredDate })"
    />

    <VnState
      v-if="loadFailed && !hasLoaded && !loading"
      :icon="CloseCircleOutlined"
      tone="err"
      :title="t('common.fetch_error')"
    >
      <template #actions>
        <a-button type="primary" @click="fetchLicenseData">
          <template #icon><ReloadOutlined /></template>{{ t('common.refresh') }}
        </a-button>
      </template>
    </VnState>

    <div v-else-if="loading && !hasLoaded" class="vnp-grid vn-license-skeleton">
      <div v-for="n in 3" :key="n" class="vn-panel vnp-skel-tile">
        <div class="vn-skel vn-skel-line" style="width: 45%" />
        <div class="vn-skel vn-skel-line" style="width: 30%" />
        <div class="vn-skel vn-skel-block" />
      </div>
    </div>

    <template v-else>
      <div v-if="activeTab === 'status'" class="vn-license-tab-content">
        <div class="vn-license-overview">
          <section class="vn-panel">
            <div class="vn-panel-body">
              <div class="vn-license-edition">
                <span class="vn-license-edition-icon" aria-hidden="true">
                  <component :is="licenseInfo ? editionConfig.icon : KeyOutlined" />
                </span>
                <span class="vn-license-edition-copy">
                  <span class="vn-license-edition-name">
                    {{ licenseInfo ? t(editionConfig.labelKey) : t('licenseUI.not_activated') }}
                  </span>
                  <VnBadge :tone="licenseInfo ? (licenseInfo.valid ? 'ok' : 'err') : 'idle'">
                    {{ licenseInfo ? (licenseInfo.valid ? t('licenseUI.valid') : t('licenseUI.invalid')) : t('licenseUI.basic_access') }}
                  </VnBadge>
                </span>
              </div>
              <div class="vn-license-summary-note">
                <span>{{ t('licenseUI.enabled_features', { count: enabledCount }) }}</span>
                <span :class="{ 'vn-ink-err': licenseInfo?.expired }">{{ validityText }}</span>
              </div>
            </div>
          </section>

          <section class="vn-panel">
            <header class="vn-panel-head">
              <span class="vnp-panel-head">{{ t('licenseUI.quota_title') }}</span>
              <span class="vnp-hint">{{ t('licenseUI.quota_hint') }}</span>
            </header>
            <div class="vn-panel-body">
              <div class="vn-license-quota-grid">
                <div v-for="metric in quotaMetrics" :key="metric.key" class="vnp-fact">
                  <span class="vnp-fact-label"><component :is="metric.icon" /> {{ metric.label }}</span>
                  <span class="vnp-fact-value">{{ metric.value }}</span>
                </div>
              </div>

              <div v-if="licenseInfo" class="vnp-kv vn-license-meta">
                <div class="vnp-kv-item">
                  <span class="vnp-kv-label">{{ t('licenseUI.license_id') }}</span>
                  <span class="vnp-kv-value vn-mono">
                    {{ licenseInfo.licenseId || '—' }}
                    <VnIconButton v-if="licenseInfo.licenseId" :icon="CopyOutlined" :label="t('common.copy')" inline @click="copyLicenseId(licenseInfo.licenseId)" />
                  </span>
                </div>
                <div class="vnp-kv-item">
                  <span class="vnp-kv-label">{{ t('licenseUI.customer') }}</span>
                  <span class="vnp-kv-value">{{ licenseInfo.customerName || '-' }}</span>
                </div>
                <div class="vnp-kv-item">
                  <span class="vnp-kv-label">{{ t('licenseUI.organization') }}</span>
                  <span class="vnp-kv-value">{{ licenseInfo.organization || '-' }}</span>
                </div>
                <div class="vnp-kv-item">
                  <span class="vnp-kv-label">{{ t('licenseUI.valid_until_label') }}</span>
                  <span class="vnp-kv-value">{{ licenseInfo.expiresAt ? formatDate(licenseInfo.expiresAt) : t('licenseUI.forever') }}</span>
                </div>
              </div>
            </div>
          </section>
        </div>

        <FeatureMatrix :features="features" @select="selectedFeature = $event" />
      </div>

      <div v-else class="vn-license-tab-content">
        <section class="vn-panel">
          <header class="vn-panel-head">
            <span class="vnp-panel-head">{{ t('licenseUI.list_title') }}</span>
          </header>
          <div class="vn-panel-body vn-license-list-body">
            <a-table
              :data-source="licenseList"
              :columns="licenseColumns"
              row-key="licenseId"
              :pagination="tablePagination"
              :scroll="{ x: 1000 }"
              :loading="loading"
            >
              <template #emptyText>
                <div class="vn-license-table-empty">
                  <span class="vnp-empty">{{ t('licenseUI.empty_list') }}</span>
                </div>
              </template>
              <template #bodyCell="{ column, record }">
                <template v-if="column.key === 'licenseId'">
                  <span class="vn-mono" :title="record.licenseId">{{ record.licenseId.substring(0, 8) }}...</span>
                </template>
                <template v-else-if="column.key === 'edition'">
                  <VnBadge tone="info" :dot="false">
                    <component :is="editionOf(record.edition).icon" />
                    {{ t(editionOf(record.edition).labelKey) }}
                  </VnBadge>
                </template>
                <template v-else-if="column.key === 'customerName'">
                  <a-tooltip :title="record.customerEmail" overlay-class-name="vn-tooltip">{{ record.customerName || '-' }}</a-tooltip>
                </template>
                <template v-else-if="column.key === 'status'">
                  <VnBadge :tone="record.expired ? 'err' : 'ok'">{{ record.status }}</VnBadge>
                </template>
                <template v-else-if="column.key === 'expiresAt'">
                  <span :class="['vn-license-validity', { 'vn-ink-err': isExpiredDate(record.expiresAt) }]">
                    {{ formatDate(record.expiresAt) }}
                    <CloseCircleOutlined v-if="isExpiredDate(record.expiresAt)" />
                  </span>
                </template>
                <template v-else-if="column.key === 'features'">
                  <span class="vn-chip"><span class="vn-chip-num">{{ record.features?.length || 0 }}</span></span>
                </template>
                <template v-else-if="column.key === 'actions'">
                  <VnIconButton :icon="CopyOutlined" :label="t('common.copy')" inline @click="copyLicenseId(record.licenseId)" />
                </template>
              </template>
            </a-table>
          </div>
        </section>
      </div>
    </template>

    <ActivateLicenseModal :open="activateOpen" @close="activateOpen = false" @activated="fetchLicenseData" />
    <FeatureDetailDrawer :feature="selectedFeature" @close="selectedFeature = null" />
  </div>
</template>