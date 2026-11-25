<script setup lang="ts">
/** Scale a deployment to a target replica count (1–10, same bounds as React).
 *  Kept mounted by the drawer and driven through `open`. */
import { computed, ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import { ExpandOutlined } from '@/components/icons'
import { scaleDeployment } from '@/api/kubernetes'
import { ApiError } from '@/api/http'
import type { KubernetesDeploymentInfo } from '@/types/kubernetes'
import { message } from '@/utils/feedback'

const props = defineProps<{
  clusterId: number
  deployment?: KubernetesDeploymentInfo | null
  open?: boolean
}>()

const emit = defineEmits<{
  (e: 'close'): void
  (e: 'scaled'): void
}>()

const { t } = useI18n()
const replicas = ref(props.deployment?.replicas || 1)
const loading = ref(false)
const isOpen = computed(() => props.open !== false)
/** Render on body: this modal is declared inside the drawer, and the global
 *  popup container would otherwise teleport it into the drawer subtree. */
const getModalContainer = () => document.body

watch(isOpen, (open) => {
  if (!open) return
  replicas.value = props.deployment?.replicas || 1
  loading.value = false
})

async function handleOk() {
  if (!props.deployment) return
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
    :open="isOpen"
    :width="460"
    :footer="null"
    :mask-closable="true"
    :get-container="getModalContainer"
    wrap-class-name="vn-portal vnp-portal vnp-modal"
    @cancel="emit('close')"
  >
    <template #title>
      <div class="vnp-modal-head">
        <span class="vnp-modal-head-glyph" aria-hidden="true"><ExpandOutlined /></span>
        <div>
          <div class="vnp-modal-title">{{ t('k8s.scale_title') }}</div>
          <div class="vnp-modal-subtitle">{{ deployment?.namespace }} / {{ deployment?.name }}</div>
        </div>
      </div>
    </template>

    <div class="vnp-form">
      <div class="vnp-modal-scroll">
        <p class="vnp-hint">{{ t('k8s.scale_hint') }}</p>
        <a-form layout="vertical">
          <a-form-item :label="t('k8s.replicas')" required>
            <a-input-number v-model:value="replicas" :min="1" :max="10" :style="{ width: '100%' }" autofocus />
          </a-form-item>
        </a-form>
      </div>
      <div class="vnp-modal-foot">
        <a-button @click="emit('close')">{{ t('common.cancel') }}</a-button>
        <a-button type="primary" :loading="loading" @click="handleOk">{{ t('common.confirm') }}</a-button>
      </div>
    </div>
  </a-modal>
</template>