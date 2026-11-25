<script setup lang="ts">
/** Scale a deployment to a target replica count (1–10, same bounds as React). */
import { ref } from 'vue'
import { useI18n } from 'vue-i18n'
import { ExpandOutlined } from '@/components/icons'
import { scaleDeployment } from '@/api/kubernetes'
import { ApiError } from '@/api/http'
import type { KubernetesDeploymentInfo } from '@/types/kubernetes'
import { message } from '@/utils/feedback'

const props = defineProps<{
  clusterId: number
  deployment: KubernetesDeploymentInfo
}>()

const emit = defineEmits<{
  (e: 'close'): void
  (e: 'scaled'): void
}>()

const { t } = useI18n()
const replicas = ref(props.deployment.replicas || 1)
const loading = ref(false)
/** Render on body: this modal is declared inside the drawer, and the global
 *  popup container would otherwise teleport it into the drawer subtree. */
const getModalContainer = () => document.body

async function handleOk() {
  loading.value = true
  try {
    await scaleDeployment(
      props.clusterId,
      props.deployment.namespace,
      props.deployment.name,
      replicas.value,
    )
    message.success(t('k8s.scale_success'))
    emit('scaled')
    emit('close')
  } catch (error) {
    message.error(error instanceof ApiError && error.message ? error.message : t('k8s.scale_failed'))
  } finally {
    loading.value = false
  }
}
</script>

<template>
  <a-modal
    :open="true"
    :width="460"
    :confirm-loading="loading"
    :ok-text="t('common.confirm')"
    :cancel-text="t('common.cancel')"
    :mask-closable="true"
    :destroy-on-close="true"
    :get-container="getModalContainer"
    @ok="handleOk"
    @cancel="emit('close')"
  >
    <template #title>
      <div class="k8s-modal-head">
        <span class="k8s-modal-glyph" aria-hidden="true"><ExpandOutlined /></span>
        <div class="k8s-modal-heading">
          <span class="k8s-modal-title">{{ t('k8s.scale_title') }}</span>
          <span class="k8s-modal-subtitle">{{ deployment.namespace }} / {{ deployment.name }}</span>
        </div>
      </div>
    </template>

    <p class="k8s-scale-hint">{{ t('k8s.scale_hint') }}</p>
    <a-form layout="vertical">
      <a-form-item :label="t('k8s.replicas')" required>
        <a-input-number v-model:value="replicas" :min="1" :max="10" :style="{ width: '100%' }" autofocus />
      </a-form-item>
    </a-form>
  </a-modal>
</template>