<script setup lang="ts">
/**
 * Deploy gateway modal: Deployment + Service into the selected namespace.
 * Field semantics mirror POST /clusters/{id}/deploy (DeployGatewayRequest).
 * Kept mounted by the drawer and driven through `open`.
 */
import { computed, reactive, ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import type { FormInstance } from 'ant-design-vue'
import { PlayCircleOutlined } from '@/components/icons'
import { deployGateway } from '@/api/kubernetes'
import { ApiError } from '@/api/http'
import type { DeployGatewayRequest } from '@/types/kubernetes'
import { message } from '@/utils/feedback'

const props = defineProps<{
  clusterId: number
  namespaces: string[]
  open?: boolean
}>()

const emit = defineEmits<{
  (e: 'close'): void
  (e: 'deployed', namespace: string): void
}>()

const { t } = useI18n()

const formRef = ref<FormInstance>()
const initialForm = (): Required<Omit<DeployGatewayRequest, 'envVars'>> => ({
  namespace: 'gateway',
  appName: 'my-gateway',
  replicas: 1,
  image: 'my-gateway:latest',
  containerPort: 8080,
})
const form = reactive<Required<Omit<DeployGatewayRequest, 'envVars'>>>(initialForm())
const deploying = ref(false)
const isOpen = computed(() => props.open !== false)
/** Render on body: this modal is declared inside the drawer, and the global
 *  popup container would otherwise teleport it into the drawer subtree. */
const getModalContainer = () => document.body

watch(isOpen, (open) => {
  if (!open) return
  Object.assign(form, initialForm())
  deploying.value = false
})

const namespaceOptions = computed(() => {
  const set = new Set(['gateway', 'default', ...props.namespaces])
  return Array.from(set).map((ns) => ({ value: ns, label: ns }))
})

const rules = {
  namespace: [{ required: true }],
  appName: [{ required: true }],
  replicas: [{ required: true }],
  image: [{ required: true }],
  containerPort: [{ required: true }],
}

async function handleDeploy() {
  try {
    await formRef.value?.validate()
  } catch {
    return
  }
  deploying.value = true
  const hide = message.loading(t('k8s.deploying'), 0)
  try {
    await deployGateway(props.clusterId, {
      namespace: form.namespace,
      appName: form.appName,
      replicas: form.replicas,
      image: form.image,
      containerPort: form.containerPort,
    })
    hide()
    message.success(t('k8s.deploy_success'))
    const namespace = form.namespace || 'gateway'
    formRef.value?.resetFields()
    emit('deployed', namespace)
    emit('close')
  } catch (error) {
    hide()
    message.error(error instanceof ApiError && error.message ? error.message : t('k8s.deploy_failed'))
  } finally {
    deploying.value = false
  }
}

function handleCancel() {
  formRef.value?.resetFields()
  emit('close')
}
</script>

<template>
  <a-modal
    :open="isOpen"
    :width="600"
    :footer="null"
    :mask-closable="true"
    :get-container="getModalContainer"
    wrap-class-name="vn-portal vnp-portal vnp-modal"
    @cancel="handleCancel"
  >
    <template #title>
      <div class="vnp-modal-head">
        <span class="vnp-modal-head-glyph" aria-hidden="true"><PlayCircleOutlined /></span>
        <div>
          <div class="vnp-modal-title">{{ t('k8s.deploy_title') }}</div>
          <div class="vnp-modal-subtitle">{{ t('k8s.deploy_hint') }}</div>
        </div>
      </div>
    </template>

    <a-form ref="formRef" :model="form" :rules="rules" layout="vertical" class="vnp-form">
      <div class="vnp-modal-scroll">
        <a-form-item name="namespace" :label="t('k8s.namespace')">
          <a-select v-model:value="form.namespace" :options="namespaceOptions" />
        </a-form-item>
        <a-form-item name="appName" :label="t('k8s.app_name')">
          <a-input v-model:value="form.appName" placeholder="my-gateway" />
        </a-form-item>
        <div class="vnp-form-grid">
          <a-form-item name="replicas" :label="t('k8s.replicas')">
            <a-input-number v-model:value="form.replicas" :min="1" :max="10" :style="{ width: '100%' }" />
          </a-form-item>
          <a-form-item name="containerPort" :label="t('k8s.container_port')">
            <a-input-number v-model:value="form.containerPort" :min="1" :max="65535" :style="{ width: '100%' }" />
          </a-form-item>
        </div>
        <a-form-item name="image" :label="t('k8s.image')">
          <a-input v-model:value="form.image" placeholder="my-gateway:latest" />
        </a-form-item>
      </div>

      <div class="vnp-modal-foot">
        <a-button @click="handleCancel">{{ t('common.cancel') }}</a-button>
        <a-button type="primary" :loading="deploying" @click="handleDeploy">{{ t('k8s.deploy_button') }}</a-button>
      </div>
    </a-form>
  </a-modal>
</template>