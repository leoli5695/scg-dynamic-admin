<script setup lang="ts">
/**
 * Delete confirmation for an SSL certificate — replaces the inline popconfirm
 * so the destructive action gets a clear, login-skinned dialog.
 */
import { useI18n } from 'vue-i18n'
import { DeleteOutlined } from '@/components/icons'
import type { SslCertificate } from '@/types/certificate'

defineProps<{
  certificate: SslCertificate
  busy?: boolean
}>()
const emit = defineEmits<{
  (e: 'cancel'): void
  (e: 'confirm'): void
}>()

const { t } = useI18n()
</script>

<template>
  <a-modal
    :open="true"
    :width="440"
    :footer="null"
    :closable="!busy"
    :keyboard="!busy"
    :mask-closable="!busy"
    wrap-class-name="cert-delete-modal"
    @cancel="emit('cancel')"
  >
    <div class="cert-delete-body">
      <span class="cert-delete-icon" aria-hidden="true"><DeleteOutlined /></span>
      <p class="cert-delete-title">{{ t('common.delete') }}</p>
      <p class="cert-delete-text">{{ t('cert.delete_confirm') }}</p>
      <span class="cert-delete-target" :title="certificate.certName">{{ certificate.certName }}</span>
    </div>
    <div class="cert-delete-actions">
      <a-button :disabled="busy" @click="emit('cancel')">{{ t('common.cancel') }}</a-button>
      <a-button type="primary" danger :loading="busy" @click="emit('confirm')">
        <template #icon><DeleteOutlined /></template>
        {{ t('common.delete') }}
      </a-button>
    </div>
  </a-modal>
</template>