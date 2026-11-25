<script setup lang="ts">
/**
 * Certificate detail modal — read-only, login-skinned definition grid.
 */
import { useI18n } from 'vue-i18n'
import { SafetyCertificateOutlined } from '@/components/icons'
import type { SslCertificate } from '@/types/certificate'
import { parseServerTime } from '@/utils/serverTime'
import StatusTag from './StatusTag.vue'

defineProps<{ certificate: SslCertificate }>()
const emit = defineEmits<{ (e: 'close'): void }>()

const { t } = useI18n()

function formatDateTime(value?: string): string {
  return value ? parseServerTime(value).toLocaleString() : '-'
}
</script>

<template>
  <a-modal
    :open="true"
    :width="620"
    :mask-closable="true"
    class="cert-detail-modal"
    @cancel="emit('close')"
  >
    <template #title>
      <div class="cert-detail-head">
        <span class="cert-detail-icon" aria-hidden="true"><SafetyCertificateOutlined /></span>
        <span class="cert-detail-heading">
          <span class="cert-detail-name" :title="certificate.certName">{{ certificate.certName }}</span>
          <span class="cert-detail-sub">
            <span class="cert-domain">{{ certificate.domain }}</span>
            <StatusTag :status="certificate.status" :days-to-expiry="certificate.daysToExpiry" />
          </span>
        </span>
      </div>
    </template>

    <div class="cert-detail-body">
      <section class="cert-detail-section">
        <h4>{{ t('cert.detail_section_cert') }}</h4>
        <dl class="cert-detail-grid">
          <div class="cert-detail-item">
            <dt>{{ t('cert.type') }}</dt>
            <dd><a-tag class="cert-type-badge">{{ certificate.certType || 'PEM' }}</a-tag></dd>
          </div>
          <div class="cert-detail-item">
            <dt>{{ t('cert.status') }}</dt>
            <dd><StatusTag :status="certificate.status" :days-to-expiry="certificate.daysToExpiry" /></dd>
          </div>
          <div class="cert-detail-item is-wide">
            <dt>{{ t('cert.issuer') }}</dt>
            <dd>{{ certificate.issuer || '-' }}</dd>
          </div>
          <div class="cert-detail-item">
            <dt>{{ t('cert.serial') }}</dt>
            <dd class="is-mono">{{ certificate.serialNumber || '-' }}</dd>
          </div>
          <div class="cert-detail-item">
            <dt>{{ t('cert.days_to_expiry') }}</dt>
            <dd>{{ certificate.daysToExpiry }} {{ t('cert.days') }}</dd>
          </div>
        </dl>
      </section>

      <section class="cert-detail-section">
        <h4>{{ t('cert.detail_section_validity') }}</h4>
        <dl class="cert-detail-grid">
          <div class="cert-detail-item">
            <dt>{{ t('cert.valid_from') }}</dt>
            <dd>{{ formatDateTime(certificate.validFrom) }}</dd>
          </div>
          <div class="cert-detail-item">
            <dt>{{ t('cert.valid_to') }}</dt>
            <dd>{{ formatDateTime(certificate.validTo) }}</dd>
          </div>
          <div class="cert-detail-item">
            <dt>{{ t('cert.enabled') }}</dt>
            <dd>
              <a-tag
                class="status-badge"
                :class="certificate.enabled ? 'status-badge-success' : 'status-badge-neutral'"
              >
                <span
                  class="status-dot"
                  :class="certificate.enabled ? 'status-dot-success' : 'status-dot-neutral'"
                />
                {{ certificate.enabled ? t('cert.enabled') : t('cert.disabled') }}
              </a-tag>
            </dd>
          </div>
          <div class="cert-detail-item">
            <dt>{{ t('cert.auto_renew') }}</dt>
            <dd>{{ certificate.autoRenew ? t('cert.yes') : t('cert.no') }}</dd>
          </div>
        </dl>
      </section>
    </div>

    <template #footer>
      <div class="cert-detail-footer">
        <a-button @click="emit('close')">{{ t('common.close') }}</a-button>
      </div>
    </template>
  </a-modal>
</template>