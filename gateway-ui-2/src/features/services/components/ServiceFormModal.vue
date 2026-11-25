<script setup lang="ts">
/**
 * Create/edit service modal (shared form). Kept mounted by the page and driven
 * through `open`; form state resets on every open. Body aligns with
 * model/ServiceDefinition.java.
 */
import { computed, h, reactive, ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import {
  ApiOutlined, ClusterOutlined, EditOutlined, ExclamationCircleOutlined,
  PlusOutlined, RobotOutlined,
} from '@/components/icons'
import type { ServiceCategory, ServiceDefinition, ServiceInstanceItem } from '@/types/service'
import { AI_PROVIDER_TYPES } from '@/types/service'
import { isHttpUrl, isValidIP, isValidPort } from '../utils'
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
  /** Kept mounted by the page and toggled here (unmounting an open antd modal
   *  leaks its dialog tree; see perf audit 2026-11-20). */
  open?: boolean
  onSubmit: (values: ServiceFormValues, instances: ServiceInstanceItem[]) => Promise<boolean>
}>()
const emit = defineEmits<{ (e: 'cancel'): void }>()

const { t } = useI18n()
const isOpen = computed(() => props.open !== false)
const isEdit = computed(() => props.mode === 'edit')

function initialForm(): ServiceFormValues {
  return isEdit.value
    ? {
      name: props.service?.name ?? '',
      loadBalancer: props.service?.loadBalancer,
      description: props.service?.description,
      category: props.service?.category || 'NORMAL',
      providerType: props.service?.providerType,
      baseUrl: props.service?.baseUrl,
    }
    : { loadBalancer: 'weighted', category: 'NORMAL', name: '' }
}

const emptyInstance = (): ServiceInstanceItem => ({ ip: '', port: 8080, weight: 1, healthy: true, enabled: true })
const initialInstances = (): ServiceInstanceItem[] =>
  (isEdit.value ? [...(props.service?.instances ?? [])] : [emptyInstance()])

const formState = reactive<ServiceFormValues>(initialForm())
const instances = ref<ServiceInstanceItem[]>(initialInstances())
const submitting = ref(false)
const replacingKey = ref(false)
const keyStatus = computed(() => t(props.service?.credentialRef
  ? 'services.credential_configured' : 'services.credential_none'))

function toggleKeyReplacement() {
  replacingKey.value = !replacingKey.value
  formState.apiKey = undefined
}

watch(isOpen, (open) => {
  if (!open) {
    formState.apiKey = undefined
    return
  }
  for (const key of Object.keys(formState)) delete (formState as Record<string, unknown>)[key]
  Object.assign(formState, initialForm())
  instances.value = initialInstances()
  submitting.value = false
  replacingKey.value = false
})

const category = computed<ServiceCategory>(() => formState.category ?? (isEdit.value ? props.service?.category ?? 'NORMAL' : 'NORMAL'))
const isAi = computed(() => category.value === 'AI')
const modalDescription = computed(() => {
  if (isEdit.value) return t('services.edit_description')
  return t(isAi.value ? 'services.create_ai_description' : 'services.create_description')
})

const providerOptions = computed(() => AI_PROVIDER_TYPES.map((value) => ({
  value,
  label: t('services.provider_' + value),
})))

function matchesProvider(input: string, option?: { label?: string; value?: string }) {
  const query = input.trim().toLowerCase()
  return Boolean(option?.label?.toLowerCase().includes(query) || option?.value?.includes(query))
}

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

async function validateBaseUrl(_rule: unknown, value?: string) {
  if (!isHttpUrl(value)) throw new Error(t('services.base_url_invalid'))
}

async function handleFinish() {
  if (submitting.value) return
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
    const ok = await props.onSubmit({ ...formState, baseUrl: formState.baseUrl?.trim() }, instances.value)
    if (ok) emit('cancel')
  } finally {
    submitting.value = false
  }
}
</script>

<template>
  <a-modal
    :open="isOpen"
    :footer="null"
    :width="600"
    :style="{ top: '24px' }"
    :class="['service-modal', isEdit ? 'service-edit-modal' : 'service-create-modal']"
    wrap-class-name="vn-portal vn-modal"
    :mask-closable="!submitting"
    :keyboard="!submitting"
    :closable="!submitting"
    @cancel="emit('cancel')"
  >
    <template #title>
      <div class="vn-modal-head">
        <span class="vn-modal-head-glyph" aria-hidden="true">
          <EditOutlined v-if="isEdit" />
          <PlusOutlined v-else />
        </span>
        <div>
          <div class="vn-modal-title">{{ t(isEdit ? 'services.edit' : 'services.create') }}</div>
          <div class="vn-modal-subtitle">{{ modalDescription }}</div>
        </div>
      </div>
    </template>

    <a-form :model="formState" layout="vertical" class="vn-form" @finish="handleFinish">
      <div class="vn-modal-scroll">
        <div class="form-section-modern">
          <div class="section-header">
            <ClusterOutlined class="section-icon" />
            <span class="section-title">{{ t('services.basic_config') }}</span>
          </div>
          <div class="section-content">
            <div class="form-row form-row-identity">
              <a-form-item name="category" :label="t('services.category')" :tooltip="isEdit ? t('services.category_locked') : t('services.category_helper')" class="form-item-category">
                <div v-if="isEdit" class="vn-readonly">{{ t(isAi ? 'services.category_ai' : 'services.category_normal') }}</div>
                <a-segmented v-else v-model:value="formState.category" :options="categoryOptions" class="form-segmented" />
              </a-form-item>
              <a-form-item
                name="name"
                :label="t('services.name')"
                :rules="isEdit ? undefined : [{ required: true, message: t('services.name_required') }]"
                class="form-item-grow"
              >
                <div v-if="isEdit" class="vn-readonly vn-mono">{{ formState.name }}</div>
                <a-input v-else v-model:value="formState.name" :placeholder="t('services.name_placeholder')" />
              </a-form-item>
              <a-form-item v-if="!isAi" name="loadBalancer" :label="t('services.loadBalancer')" class="form-item-lb">
                <LoadBalancerSelect v-model:value="formState.loadBalancer" />
              </a-form-item>
            </div>
            <a-form-item name="description" :label="t('services.description_optional')">
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
              <a-select
                v-model:value="formState.providerType"
                :placeholder="t('services.provider_type_placeholder')"
                :options="providerOptions"
                :filter-option="matchesProvider"
                :list-height="300"
                popup-class-name="vn-portal vn-provider-dropdown"
                show-search
              >
                <template #option="{ label, value }">
                  <div class="vn-provider-option">
                    <span class="vn-provider-label">{{ label }}</span>
                    <span v-if="label.toLowerCase() !== value" class="vn-provider-id vn-mono">{{ value }}</span>
                  </div>
                </template>
              </a-select>
            </a-form-item>
            <a-form-item
              name="apiKey"
              :label="t('services.api_key')"
              :rules="!isEdit || replacingKey ? [{ required: true, whitespace: true, message: t('services.api_key_required') }] : undefined"
            >
              <div v-if="isEdit" class="vn-key-status">
                <span>{{ keyStatus }}</span>
                <a-button html-type="button" size="small" @click="toggleKeyReplacement">
                  {{ t(replacingKey ? 'common.cancel' : 'services.replace_key') }}
                </a-button>
              </div>
              <a-input-password v-if="!isEdit || replacingKey" v-model:value="formState.apiKey" autocomplete="new-password" :placeholder="t('services.api_key_placeholder')" />
            </a-form-item>
            <a-form-item
              name="baseUrl"
              :label="t('services.base_url')"
              :tooltip="t('services.base_url_helper')"
              :rules="[{ validator: validateBaseUrl }]"
            >
              <a-input v-model:value="formState.baseUrl" :placeholder="t('services.base_url_placeholder')" />
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
              <a-button type="dashed" class="add-instance-btn-modern" @click="instances.push(emptyInstance())">
                <template #icon><PlusOutlined /></template>{{ t('services.add_instance') }}
              </a-button>
            </div>
          </div>
        </div>
      </div>

      <div class="modal-footer-modern">
        <a-button :disabled="submitting" @click="emit('cancel')">{{ t('common.cancel') }}</a-button>
        <a-button type="primary" html-type="submit" :loading="submitting">
          <template #icon><EditOutlined v-if="isEdit" /><PlusOutlined v-else /></template>
          {{ t(isEdit ? 'common.update' : 'common.create') }}
        </a-button>
      </div>
    </a-form>
  </a-modal>
</template>
