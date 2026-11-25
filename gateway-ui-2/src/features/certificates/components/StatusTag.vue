<script setup lang="ts">
/**
 * Certificate expiry status capsule (VALID / EXPIRING_SOON / EXPIRED)
 * rendered with the shared login-skin status-badge classes.
 */
import { useI18n } from 'vue-i18n'
import {
  CheckCircleOutlined,
  ClockCircleOutlined,
  ExclamationCircleOutlined,
} from '@/components/icons'

defineProps<{
  status?: string
  daysToExpiry?: number
}>()

const { t } = useI18n()
</script>

<template>
  <a-tag
    v-if="status === 'VALID'"
    class="cert-status-tag status-badge status-badge-success"
  >
    <template #icon><CheckCircleOutlined /></template>
    {{ t('cert.status_valid') }}
  </a-tag>
  <a-tag
    v-else-if="status === 'EXPIRING_SOON'"
    class="cert-status-tag status-badge status-badge-warning"
  >
    <template #icon><ClockCircleOutlined /></template>
    {{ t('cert.status_expiring') }} ({{ daysToExpiry }} {{ t('cert.days') }})
  </a-tag>
  <a-tag
    v-else-if="status === 'EXPIRED'"
    class="cert-status-tag status-badge status-badge-error"
  >
    <template #icon><ExclamationCircleOutlined /></template>
    {{ t('cert.status_expired') }}
  </a-tag>
  <a-tag v-else class="cert-status-tag status-badge status-badge-neutral">{{ status }}</a-tag>
</template>