<script setup lang="ts">
/**
 * Create/edit service modal (shared form). Mounted only while open, so initial
 * values come straight from props. Body aligns with model/ServiceDefinition.java.
 */
import { computed, h, reactive, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import {
  ApiOutlined, ClusterOutlined, EditOutlined, ExclamationCircleOutlined,
  PlusOutlined, RobotOutlined,
} from '@ant-design/icons-vue'
import type { ServiceCategory, ServiceDefinition, ServiceInstanceItem } from '@/types/service'
import { AI_PROVIDER_TYPES } from '@/types/service'
import { isValidIP, isValidPort } from '../utils'
import { message } from '@/utils/feedback'
import InstanceRow from './InstanceRow.vue'
import LoadBalancerSelect from './LoadBalancerSelect.vue'

export interface ServiceFormValues {
  name: string
  loadBalancer?: string
  description?: string
  category?: ServiceCategory
  providerType?: string
  baseUrl?: string
  apiKey?: string
}

const props = defineProps<{
  mode: 'create' | 'edit'
  service?: ServiceDefinition
  onSubmit: (values: ServiceFormValues, instances: ServiceInstanceItem[]) => Promise<boolean>
}>()
const emit = defineEmits<{ (e: 'cancel'): void }>()

const { t } = useI18n()
const isEdit = computed(() => props.mode === 'edit')

const formState = reactive<ServiceFormValues>(
  isEdit.value
    ? {
      name: props.service?.name ?? '',
      loadBalancer: props.service?.loadBalancer,
      description: props.service?.description,
      category: props.service?.category || 'NORMAL',
      providerType: props.service?.providerType,
      baseUrl: props.service?.baseUrl,
    }
    : { loadBalancer: 'weighted', category: 'NORMAL', name: '' },
)

const emptyInstance = (): ServiceInstanceItem => ({ ip: '', port: 8080, weight: 1, healthy: true, enabled: true })
const instances = ref<ServiceInstanceItem[]>(
  isEdit.value ? [...(props.service?.instances ?? [])] : [emptyInstance()],
)
const submitting = ref(false)

const category = computed<ServiceCategory>(() => formState.category ?? (isEdit.value ? props.service?.category ?? 'NORMAL' : 'NORMAL'))
const isAi = computed(() => category.value === 'AI')

const categoryOptions = computed(() => [
  { value: 'NORMAL', label: h('span', {}, [h(ClusterOutlined, { style: 'margin-right:6px' }), t('services.category_normal')]) },
  { value: 'AI', label: h('span', {}, [h(RobotOutlined, { style: 'margin-right:6px' }), t('services.category_ai')]) },
])

function handleInstanceChange(index: number, patch: Partial<ServiceInstanceItem>) {
  instances.value = instances.value.map((item, i) => (i === index ? { ...item, ...patch } : item))
}
function handleRemoveInstance(index: number) {
  instances.value = instances.value.filter((_, i) => i !== index)
}

const allDisabled = computed(() => instances.value.length === 0 || instances.value.every((i) => i.enabled === false))

async function handleFinish() {
  if (!isAi.value) {
    for (const inst of instances.value) {
      if (!isValidIP(inst.ip)) {
        message.error(t('message.invalid_ip_format', { index: 1, ip: inst.ip }))
        return
      }
      if (!isValidPort(inst.port)) {
        message.error(t('message.invalid_port', { index: 1, port: inst.port }))
        return
      }
    }
  }
  submitting.value = true
  try {
    const ok = await props.onSubmit({ ...formState }, instances.value)
    if (ok) emit('cancel')
  } finally {
    submitting.value = false
  }
}
</script>

<template>
  <a-modal
    :open="true"
    :footer="null"
    :width="600"
    :class="['service-modal', isEdit ? 'service-edit-modal' : 'service-create-modal']"
    :destroy-on-close="true"
    :mask-closable="true"
    @cancel="emit('cancel')"
  >
    <template #title>
      <div class="modal-header-modern">
        <div class="modal-title-wrapper">
          <div :class="['modal-icon-wrapper', isEdit ? 'edit' : '']">
            <EditOutlined v-if="isEdit" />
            <PlusOutlined v-else />
          </div>
          <div class="modal-title-text">
            <div class="modal-title">{{ t(isEdit ? 'services.edit' : 'services.create') }}</div>
            <div class="modal-subtitle">{{ t(isEdit ? 'services.edit_description' : 'services.create_description') }}</div>
          </div>
        </div>
      </div>
    </template>

    <a-form :model="formState" layout="vertical" @finish="handleFinish">
      <div class="form-section-modern">
        <div class="section-header">
          <ClusterOutlined class="section-icon" />
          <span class="section-title">{{ t('services.basic_config') }}</span>
        </div>
        <div class="section-content">
          <a-form-item name="category" :label="t('services.category')" :tooltip="isEdit ? t('services.category_locked') : t('services.category_helper')">
            <a-segmented v-model:value="formState.category" :options="categoryOptions" :disabled="isEdit" />
          </a-form-item>
          <div class="form-row">
            <a-form-item
              name="name"
              :label="t('services.name')"
              :rules="isEdit ? undefined : [{ required: true, message: t('services.name_required') }]"
              class="form-item-half"
            >
              <a-input v-model:value="formState.name" :placeholder="isEdit ? undefined : t('services.name_placeholder')" :disabled="isEdit" size="large" />
            </a-form-item>
            <a-form-item name="loadBalancer" :label="t('services.loadBalancer')" class="form-item-half">
              <LoadBalancerSelect v-model:value="formState.loadBalancer" />
            </a-form-item>
          </div>
          <a-form-item name="description" :label="t('services.description')">
            <a-textarea v-model:value="formState.description" :rows="2" :placeholder="t('services.description_placeholder')" show-count :maxlength="500" />
          </a-form-item>
        </div>
      </div>

      <div v-if="isAi" class="form-section-modern">
        <div class="section-header">
          <RobotOutlined class="section-icon" />
          <span class="section-title">{{ t('services.ai_config') }}</span>
          <span class="section-subtitle">{{ t('services.ai_config_helper') }}</span>
        </div>
        <div class="section-content">
          <a-form-item name="providerType" :label="t('services.provider_type')" :rules="[{ required: true, message: t('services.provider_type_required') }]">
            <a-select v-model:value="formState.providerType" :placeholder="t('services.provider_type_placeholder')" size="large" show-search :options="AI_PROVIDER_TYPES.map((p) => ({ label: p, value: p }))" />
          </a-form-item>
          <a-form-item
            name="apiKey"
            :label="t('services.api_key')"
            :rules="isEdit ? undefined : [{ required: true, message: t('services.api_key_required') }]"
            :tooltip="isEdit && service?.credentialRef ? t('services.credential_configured') : t('services.credential_none')"
          >
            <a-input-password v-model:value="formState.apiKey" size="large" autocomplete="new-password" :placeholder="isEdit ? t('services.api_key_rotate_placeholder') : t('services.api_key_placeholder')" />
          </a-form-item>
          <a-form-item name="baseUrl" :label="t('services.base_url')" :tooltip="t('services.base_url_helper')">
            <a-input v-model:value="formState.baseUrl" size="large" :placeholder="t('services.base_url_placeholder')" />
          </a-form-item>
        </div>
      </div>

      <div v-if="!isAi" class="form-section-modern">
        <div class="section-header">
          <ApiOutlined class="section-icon" />
          <span class="section-title">{{ t('services.service_instances') }}</span>
          <span class="section-subtitle">{{ t('services.instances_helper') }}</span>
        </div>
        <div class="section-content">
          <a-alert
            v-if="allDisabled"
            type="warning"
            show-icon
            :message="instances.length === 0 ? t('services.modal_no_instances_warning') : t('services.modal_all_disabled_warning')"
            class="modal-instances-warning"
          >
            <template #icon><ExclamationCircleOutlined /></template>
          </a-alert>
          <div class="instances-container">
            <InstanceRow
              v-for="(inst, i) in instances"
              :key="i"
              :instance="inst"
              :index="i"
              :can-remove="instances.length > 1"
              @change="handleInstanceChange"
              @remove="handleRemoveInstance"
            />
            <a-button type="dashed" block size="large" class="add-instance-btn-modern" @click="instances.push(emptyInstance())">
              <template #icon><PlusOutlined /></template>{{ t('services.add_instance') }}
            </a-button>
          </div>
        </div>
      </div>

      <div class="modal-footer-modern">
        <a-button size="large" @click="emit('cancel')">{{ t('common.cancel') }}</a-button>
        <a-button type="primary" size="large" html-type="submit" :loading="submitting">
          <template #icon><EditOutlined v-if="isEdit" /><PlusOutlined v-else /></template>
          {{ t(isEdit ? 'common.update' : 'common.create') }}
        </a-button>
      </div>
    </a-form>
  </a-modal>
</template>
