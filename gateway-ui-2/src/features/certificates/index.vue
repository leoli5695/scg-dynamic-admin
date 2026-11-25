<script setup lang="ts">
/**
 * SSL certificates page.
 * Fields aligned with SslCertificateController (/api/ssl) +
 * model/SslCertificate.java.
 */
import { computed, ref } from 'vue'
import { useRoute } from 'vue-router'
import { useI18n } from 'vue-i18n'
import {
  CheckCircleOutlined,
  ClockCircleOutlined,
  DeleteOutlined,
  ExclamationCircleOutlined,
  EyeOutlined,
  PlusOutlined,
  ReloadOutlined,
  SafetyCertificateOutlined,
} from '@/components/icons'
import { useRequest } from '@/composables/useRequest'
import { ApiError } from '@/api/http'
import {
  deleteCertificate,
  getCertificateStats,
  listCertificates,
  refreshCertificateStatuses,
  setCertificateEnabled,
  uploadKeystore,
  uploadPemCertificate,
} from '@/api/certificates'
import type { CertificateUploadPayload, SslCertificate } from '@/types/certificate'
import { message } from '@/utils/feedback'
import { parseServerTime } from '@/utils/serverTime'
import InlineStatChips from '@/components/InlineStatChips.vue'
import StatusTag from './components/StatusTag.vue'
import CertDetailModal from './components/CertDetailModal.vue'
import UploadCertModal from './components/UploadCertModal.vue'
import DeleteCertModal from './components/DeleteCertModal.vue'
import './certificates.css'

const route = useRoute()
const { t } = useI18n()

const instanceId = computed(() => route.params.instanceId as string | undefined)

const uploadOpen = ref(false)
const detailCert = ref<SslCertificate | null>(null)
const deleteTarget = ref<SslCertificate | null>(null)
const deleting = ref(false)
const refreshing = ref(false)

const certsRequest = useRequest(
  (signal) => listCertificates(instanceId.value ? { instanceId: instanceId.value } : undefined, { signal }),
  {
    deps: [instanceId],
    onError: (error) => {
      message.error(t('cert.load_error', { error: error.message }))
    },
  },
)
const certificates = computed<SslCertificate[]>(() => certsRequest.data.value ?? [])
const certsLoading = certsRequest.loading
const certsError = certsRequest.error

const statsRequest = useRequest(
  (signal) => getCertificateStats(instanceId.value ? { instanceId: instanceId.value } : undefined, { signal }),
  { deps: [instanceId], onError: () => {} },
)
const stats = statsRequest.data

const statItems = computed(() => [
  {
    key: 'total',
    value: stats.value?.total ?? certificates.value.length,
    label: t('cert.total'),
    icon: SafetyCertificateOutlined,
  },
  {
    key: 'valid',
    value: stats.value?.valid ?? 0,
    label: t('cert.valid'),
    tone: 'green' as const,
    icon: CheckCircleOutlined,
  },
  {
    key: 'expiring',
    value: stats.value?.expiringSoon ?? 0,
    label: t('cert.expiring_soon'),
    tone: 'amber' as const,
    icon: ClockCircleOutlined,
  },
  {
    key: 'expired',
    value: stats.value?.expired ?? 0,
    label: t('cert.expired'),
    tone: 'red' as const,
    icon: ExclamationCircleOutlined,
  },
])

const pagination = { pageSize: 10 }

/** Stable column set; cells are rendered by the bodyCell slot. */
const columns = computed(() => [
  { title: t('cert.cert_name'), dataIndex: 'certName', key: 'certName' },
  { title: t('cert.domain'), dataIndex: 'domain', key: 'domain' },
  { title: t('cert.type'), dataIndex: 'certType', key: 'certType' },
  { title: t('cert.status'), dataIndex: 'status', key: 'status' },
  { title: t('cert.valid_to'), dataIndex: 'validTo', key: 'validTo' },
  { title: t('cert.enabled'), dataIndex: 'enabled', key: 'enabled' },
  { title: t('common.actions'), key: 'actions' },
])

const errorMessage = (error: unknown, fallbackKey: string): string =>
  (error instanceof ApiError && error.message ? error.message : t(fallbackKey))

function formatDate(value?: string): string {
  return value ? parseServerTime(value).toLocaleDateString() : '-'
}

function reload() {
  certsRequest.run()
  statsRequest.run()
}

async function handleUpload(payload: CertificateUploadPayload) {
  try {
    if (payload.kind === 'pem') {
      await uploadPemCertificate(payload, instanceId.value)
    } else {
      await uploadKeystore(payload, instanceId.value)
    }
    message.success(t('cert.upload_success'))
    uploadOpen.value = false
    reload()
  } catch (error) {
    message.error(errorMessage(error, 'cert.upload_error'))
  }
}

async function handleToggleEnabled(cert: SslCertificate) {
  try {
    await setCertificateEnabled(cert.id, !cert.enabled)
    message.success(t('cert.update_success'))
    reload()
  } catch (error) {
    message.error(errorMessage(error, 'cert.update_error'))
  }
}

function requestDelete(cert: SslCertificate) {
  deleteTarget.value = cert
}

async function confirmDelete() {
  if (!deleteTarget.value) return
  deleting.value = true
  try {
    await deleteCertificate(deleteTarget.value.id)
    message.success(t('cert.delete_success'))
    deleteTarget.value = null
    reload()
  } catch (error) {
    message.error(errorMessage(error, 'cert.delete_error'))
  } finally {
    deleting.value = false
  }
}

async function handleRefreshStatus() {
  refreshing.value = true
  try {
    await refreshCertificateStatuses()
    message.success(t('cert.refresh_success'))
    reload()
  } catch (error) {
    message.error(errorMessage(error, 'cert.refresh_error'))
  } finally {
    refreshing.value = false
  }
}
</script>

<template>
  <div class="certificates-page">
    <div class="page-header-modern">
      <div class="page-header-left">
        <h3 class="page-title-main has-glyph ant-typography">
          <span class="page-header-glyph" aria-hidden="true"><SafetyCertificateOutlined /></span>
          {{ t('cert.title') }}
        </h3>
        <span class="page-subtitle">{{ t('cert.description') }}</span>
      </div>
      <div class="page-header-right">
        <InlineStatChips :items="statItems" />
        <a-button class="cert-refresh-btn" :loading="refreshing" @click="handleRefreshStatus">
          <template #icon><ReloadOutlined /></template>
          {{ t('cert.refresh_status') }}
        </a-button>
        <a-button type="primary" class="cert-upload-btn" @click="uploadOpen = true">
          <template #icon><PlusOutlined /></template>
          {{ t('cert.upload') }}
        </a-button>
      </div>
    </div>

    <!-- Error state (no cached data to fall back on) -->
    <a-card v-if="certsError && certificates.length === 0" :bordered="false" class="cert-state-card">
      <div class="cert-state">
        <span class="cert-state-icon is-error"><ExclamationCircleOutlined /></span>
        <p class="cert-state-title">{{ t('cert.load_error') }}</p>
        <p class="cert-state-hint">{{ certsError.message }}</p>
        <div class="cert-state-actions">
          <a-button type="primary" @click="reload">
            <template #icon><ReloadOutlined /></template>
            {{ t('common.refresh') }}
          </a-button>
        </div>
      </div>
    </a-card>

    <!-- Empty state -->
    <a-card
      v-else-if="!certsLoading && certificates.length === 0"
      :bordered="false"
      class="cert-state-card"
    >
      <div class="cert-state">
        <span class="cert-state-icon"><SafetyCertificateOutlined /></span>
        <p class="cert-state-title">{{ t('cert.empty') }}</p>
        <p class="cert-state-hint">{{ t('cert.empty_hint') }}</p>
        <div class="cert-state-actions">
          <a-button type="primary" @click="uploadOpen = true">
            <template #icon><PlusOutlined /></template>
            {{ t('cert.upload') }}
          </a-button>
        </div>
      </div>
    </a-card>

    <!-- Certificate table -->
    <a-card v-else :bordered="false" class="cert-table-card">
      <a-table
        :columns="columns"
        :data-source="certificates"
        :loading="certsLoading"
        :pagination="pagination"
        :scroll="{ x: 'max-content' }"
        row-key="id"
      >
        <template #bodyCell="{ column, record }">
          <template v-if="column.key === 'certName'">
            <div class="cert-name-cell">
              <span class="cert-name-icon" aria-hidden="true"><SafetyCertificateOutlined /></span>
              <span class="cert-name-text">
                <span class="cert-name-main" :title="record.certName">{{ record.certName }}</span>
              </span>
            </div>
          </template>
          <template v-else-if="column.key === 'domain'">
            <span class="cert-domain-cell" :title="record.domain">{{ record.domain }}</span>
          </template>
          <template v-else-if="column.key === 'certType'">
            <a-tag class="cert-type-badge">{{ record.certType }}</a-tag>
          </template>
          <template v-else-if="column.key === 'status'">
            <StatusTag :status="record.status" :days-to-expiry="record.daysToExpiry" />
          </template>
          <template v-else-if="column.key === 'validTo'">
            <span class="cert-date-cell">{{ formatDate(record.validTo) }}</span>
          </template>
          <template v-else-if="column.key === 'enabled'">
            <a-popconfirm
              :title="record.enabled ? t('cert.disable_confirm') : t('cert.enable_confirm')"
              @confirm="handleToggleEnabled(record)"
            >
              <a-tag
                class="cert-enabled-pill status-badge"
                :class="record.enabled ? 'status-badge-success' : 'status-badge-neutral'"
              >
                <span
                  class="status-dot"
                  :class="record.enabled ? 'status-dot-success' : 'status-dot-neutral'"
                />
                {{ record.enabled ? t('cert.enabled') : t('cert.disabled') }}
              </a-tag>
            </a-popconfirm>
          </template>
          <template v-else-if="column.key === 'actions'">
            <div class="cert-row-actions">
              <a-tooltip :title="t('common.detail')">
                <a-button
                  type="text"
                  size="small"
                  class="cert-action-btn"
                  :aria-label="t('common.detail')"
                  @click="detailCert = record"
                >
                  <template #icon><EyeOutlined /></template>
                </a-button>
              </a-tooltip>
              <a-tooltip :title="t('common.delete')">
                <a-button
                  type="text"
                  size="small"
                  danger
                  class="cert-action-btn"
                  :aria-label="t('common.delete')"
                  @click="requestDelete(record)"
                >
                  <template #icon><DeleteOutlined /></template>
                </a-button>
              </a-tooltip>
            </div>
          </template>
        </template>
      </a-table>
    </a-card>

    <UploadCertModal :open="uploadOpen" :on-submit="handleUpload" @cancel="uploadOpen = false" />
    <CertDetailModal v-if="detailCert" :certificate="detailCert" @close="detailCert = null" />
    <DeleteCertModal
      v-if="deleteTarget"
      :certificate="deleteTarget"
      :busy="deleting"
      @cancel="deleteTarget = null"
      @confirm="confirmDelete"
    />
  </div>
</template>