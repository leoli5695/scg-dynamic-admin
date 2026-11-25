<script setup lang="ts">
/**
 * AI_PROXY strategy fields — multi-provider LLM proxy configuration.
 *
 * Renders the flat schema fields plus two custom sections the generic renderer
 * cannot express: provider credential bindings (created inline via
 * /api/provider-credentials; the key is write-only) and model routing aliases.
 */
import { computed, reactive, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import type { FormInstance } from 'ant-design-vue/es/form'
import {
  ApiOutlined, BranchesOutlined, DeleteOutlined, InfoCircleOutlined,
  KeyOutlined, PlusOutlined,
} from '@/components/icons'
import type { StrategyTypeField, StrategyTypeSchema } from '@/types/strategy'
import type { StrategyFormValues } from '../utils'
import { fieldUnit } from '../schemaLabels'
import { useRequest } from '@/composables/useRequest'
import { message } from '@/utils/feedback'
import {
  createProviderCredential, listProviderCredentials,
} from '@/api/providerCredentials'

const props = defineProps<{
  schema: StrategyTypeSchema | undefined
  values: StrategyFormValues
}>()

const { t } = useI18n()

const credentialsRequest = useRequest(
  (signal) => listProviderCredentials({ signal }),
  { onError: () => {} },
)
const credentials = computed(() => credentialsRequest.data.value ?? [])

const providers = computed(() => [
  { value: 'openai', label: t('strategy.ai.provider_openai') },
  { value: 'deepseek', label: t('strategy.ai.provider_deepseek') },
  { value: 'qwen', label: t('strategy.ai.provider_qwen') },
  { value: 'zhipu', label: t('strategy.ai.provider_zhipu') },
  { value: 'moonshot', label: t('strategy.ai.provider_moonshot') },
  { value: 'anthropic', label: t('strategy.ai.provider_anthropic') },
])

const selection = computed(() => props.values.providerSelection)

const visibleFlatFields = computed<StrategyTypeField[]>(() =>
  (props.schema?.fields ?? []).filter((field) => {
    if (field.name === 'enabled') return false // strategy-level toggle covers activation
    if (field.name === 'fixedProvider') return selection.value === 'FIXED'
    if (field.name === 'providerHeader') return selection.value === 'HEADER'
    return true
  }),
)

const credentialOptions = computed(() => credentials.value.map((credential) => ({
  value: credential.credentialRef,
  label: `${credential.name} · ${credential.provider}${credential.keyHint ? ` · ${credential.keyHint}` : ''}`,
})))

const providerCredentialRows = computed<StrategyFormValues[]>(() => props.values.providerCredentials ?? [])
const keyPoolRows = computed<StrategyFormValues[]>(() => props.values.keyPools ?? [])
const modelRoutingRows = computed<StrategyFormValues[]>(() => props.values.modelRouting ?? [])

const rowKeys = new WeakMap<object, number>()
let keySeq = 0
const keyFor = (row: object): number => {
  if (!rowKeys.has(row)) rowKeys.set(row, ++keySeq)
  return rowKeys.get(row) as number
}

function addCredentialBinding() {
  props.values.providerCredentials = [
    ...providerCredentialRows.value, { provider: undefined, credentialRef: undefined },
  ]
}
function removeCredentialBinding(index: number) {
  props.values.providerCredentials = providerCredentialRows.value.filter((_, i) => i !== index)
}
function addKeyPool() {
  props.values.keyPools = [
    ...keyPoolRows.value, { provider: undefined, strategy: 'ROUND_ROBIN', credentialRefs: [] },
  ]
}
function removeKeyPool(index: number) {
  props.values.keyPools = keyPoolRows.value.filter((_, i) => i !== index)
}
function addModelRoute() {
  props.values.modelRouting = [...modelRoutingRows.value, { model: '', provider: undefined }]
}
function removeModelRoute(index: number) {
  props.values.modelRouting = modelRoutingRows.value.filter((_, i) => i !== index)
}

const colSpan = (field: StrategyTypeField): number =>
  field.colSpan || (field.type === 'textarea' ? 24 : 12)

const strategyOptions = computed(() => [
  { value: 'ROUND_ROBIN', label: t('strategy.ai.lb_rr') },
  { value: 'LEAST_INFLIGHT', label: t('strategy.ai.lb_least') },
])

// ============================================================
// Provider credential creation
// ============================================================
const credOpen = ref(false)
const creating = ref(false)
const credFormRef = ref<FormInstance>()
const credForm = reactive<{ name: string; provider?: string; apiKey: string }>({
  name: '',
  provider: undefined,
  apiKey: '',
})

function openCredentialModal() {
  credForm.name = ''
  credForm.provider = undefined
  credForm.apiKey = ''
  credOpen.value = true
}

function closeCredentialModal() {
  credOpen.value = false
}

async function handleCreateCredential() {
  try {
    await credFormRef.value?.validate()
  } catch {
    return // validation errors are shown inline
  }
  creating.value = true
  try {
    const created = await createProviderCredential({
      name: credForm.name,
      provider: credForm.provider as string,
      apiKey: credForm.apiKey,
    })
    message.success(t('strategy.ai.credential_created'))
    credOpen.value = false
    credFormRef.value?.resetFields()
    credentialsRequest.run()
    const rows = providerCredentialRows.value
    if (!rows.some((row) => row?.provider === created.provider)) {
      props.values.providerCredentials = [
        ...rows,
        { provider: created.provider, credentialRef: created.credentialRef },
      ]
    }
  } catch (error) {
    message.error((error as Error)?.message || t('common.error_request_failed'))
  } finally {
    creating.value = false
  }
}
</script>

<template>
  <div>
    <!-- Basic proxy settings -->
    <a-card size="small" class="strategy-config-card">
      <template #title>
        <span class="strategy-card-title"><ApiOutlined /> <span>{{ t('strategy.ai.basic') }}</span></span>
      </template>
      <a-row :gutter="16">
        <a-col v-for="field in visibleFlatFields" :key="field.name" :span="colSpan(field)">
          <a-form-item :name="field.name" :label="field.label || field.name">
            <a-input-number
              v-if="field.type === 'number'"
              :value="values[field.name]"
              :min="field.min"
              :max="field.max"
              style="width: 100%"
              @update:value="values[field.name] = $event"
            >
              <template v-if="fieldUnit(field)" #addonAfter>{{ fieldUnit(field) }}</template>
            </a-input-number>
            <a-switch
              v-else-if="field.type === 'switch'"
              :checked="values[field.name]"
              @update:checked="values[field.name] = $event"
            />
            <a-select
              v-else-if="field.type === 'select'"
              :value="values[field.name]"
              :options="(field.options ?? []).map((opt) => ({ value: opt.value, label: opt.label || opt.value }))"
              @update:value="values[field.name] = $event"
             popup-class-name="vn-portal"/>
            <a-input
              v-else
              :value="values[field.name]"
              :placeholder="field.placeholder"
              @update:value="values[field.name] = $event"
            />
          </a-form-item>
        </a-col>
      </a-row>
    </a-card>

    <!-- Provider credentials -->
    <a-card size="small" class="strategy-config-card">
      <template #title>
        <span class="strategy-card-title">
          <KeyOutlined />
          <span>{{ t('strategy.ai.credentials') }}</span>
          <a-tooltip :title="t('strategy.ai.credentials_help')">
            <InfoCircleOutlined class="strategy-info-icon" />
          </a-tooltip>
        </span>
      </template>
      <template #extra>
        <a-button size="small" @click="openCredentialModal">
          <template #icon><PlusOutlined /></template>
          {{ t('strategy.ai.new_credential') }}
        </a-button>
      </template>
      <a-row
        v-for="(row, index) in providerCredentialRows"
        :key="keyFor(row)"
        :gutter="12"
        align="middle"
        class="strategy-inline-row"
      >
        <a-col :span="9">
          <a-form-item :name="['providerCredentials', index, 'provider']" class="strategy-inline-item">
            <a-select
              :value="row.provider"
              :placeholder="t('strategy.ai.provider')"
              :options="providers"
              @update:value="row.provider = $event"
             popup-class-name="vn-portal"/>
          </a-form-item>
        </a-col>
        <a-col :span="12">
          <a-form-item :name="['providerCredentials', index, 'credentialRef']" class="strategy-inline-item">
            <a-select
              :value="row.credentialRef"
              :placeholder="t('strategy.ai.credential')"
              show-search
              option-filter-prop="label"
              :options="credentialOptions"
              :not-found-content="t('strategy.ai.no_credentials')"
              @update:value="row.credentialRef = $event"
             popup-class-name="vn-portal"/>
          </a-form-item>
        </a-col>
        <a-col :span="3">
          <a-button type="text" danger @click="removeCredentialBinding(index)">
            <template #icon><DeleteOutlined /></template>
          </a-button>
        </a-col>
      </a-row>
      <a-button type="dashed" block @click="addCredentialBinding">
        <template #icon><PlusOutlined /></template>
        {{ t('strategy.ai.add_credential_binding') }}
      </a-button>
    </a-card>

    <!-- Key pools -->
    <a-card size="small" class="strategy-config-card">
      <template #title>
        <span class="strategy-card-title">
          <ApiOutlined />
          <span>{{ t('strategy.ai.key_pools') }}</span>
          <a-tooltip :title="t('strategy.ai.key_pools_help')">
            <InfoCircleOutlined class="strategy-info-icon" />
          </a-tooltip>
        </span>
      </template>
      <a-row
        v-for="(row, index) in keyPoolRows"
        :key="keyFor(row)"
        :gutter="12"
        align="middle"
        class="strategy-inline-row"
      >
        <a-col :span="6">
          <a-form-item :name="['keyPools', index, 'provider']" class="strategy-inline-item">
            <a-select
              :value="row.provider"
              :placeholder="t('strategy.ai.provider')"
              :options="providers"
              @update:value="row.provider = $event"
             popup-class-name="vn-portal"/>
          </a-form-item>
        </a-col>
        <a-col :span="6">
          <a-form-item :name="['keyPools', index, 'strategy']" class="strategy-inline-item">
            <a-select
              :value="row.strategy"
              :options="strategyOptions"
              @update:value="row.strategy = $event"
             popup-class-name="vn-portal"/>
          </a-form-item>
        </a-col>
        <a-col :span="9">
          <a-form-item :name="['keyPools', index, 'credentialRefs']" class="strategy-inline-item">
            <a-select
              :value="row.credentialRefs"
              mode="multiple"
              :placeholder="t('strategy.ai.pool_refs')"
              show-search
              option-filter-prop="label"
              :options="credentialOptions"
              :not-found-content="t('strategy.ai.no_credentials')"
              @update:value="row.credentialRefs = $event"
             popup-class-name="vn-portal"/>
          </a-form-item>
        </a-col>
        <a-col :span="3">
          <a-button type="text" danger @click="removeKeyPool(index)">
            <template #icon><DeleteOutlined /></template>
          </a-button>
        </a-col>
      </a-row>
      <a-button type="dashed" block @click="addKeyPool">
        <template #icon><PlusOutlined /></template>
        {{ t('strategy.ai.add_key_pool') }}
      </a-button>
    </a-card>

    <!-- Model routing aliases -->
    <a-card size="small" class="strategy-config-card">
      <template #title>
        <span class="strategy-card-title">
          <BranchesOutlined />
          <span>{{ t('strategy.ai.model_routing') }}</span>
          <a-tooltip :title="t('strategy.ai.model_routing_help')">
            <InfoCircleOutlined class="strategy-info-icon" />
          </a-tooltip>
        </span>
      </template>
      <a-row
        v-for="(row, index) in modelRoutingRows"
        :key="keyFor(row)"
        :gutter="12"
        align="middle"
        class="strategy-inline-row"
      >
        <a-col :span="11">
          <a-form-item :name="['modelRouting', index, 'model']" class="strategy-inline-item">
            <a-input
              :value="row.model"
              :placeholder="t('strategy.ai.model_name')"
              @update:value="row.model = $event"
            />
          </a-form-item>
        </a-col>
        <a-col :span="10">
          <a-form-item :name="['modelRouting', index, 'provider']" class="strategy-inline-item">
            <a-select
              :value="row.provider"
              :placeholder="t('strategy.ai.route_to')"
              :options="providers"
              @update:value="row.provider = $event"
             popup-class-name="vn-portal"/>
          </a-form-item>
        </a-col>
        <a-col :span="3">
          <a-button type="text" danger @click="removeModelRoute(index)">
            <template #icon><DeleteOutlined /></template>
          </a-button>
        </a-col>
      </a-row>
      <a-button type="dashed" block @click="addModelRoute">
        <template #icon><PlusOutlined /></template>
        {{ t('strategy.ai.add_model_route') }}
      </a-button>
    </a-card>

    <!-- Credential creation modal -->
    <a-modal
      :open="credOpen"
      :title="t('strategy.ai.new_provider_credential')"
      :confirm-loading="creating"
      :ok-text="t('common.create')"
      :cancel-text="t('common.cancel')"
      :destroy-on-close="true"
      wrap-class-name="vn-portal"
      class="strategy-credential-modal"
      @ok="handleCreateCredential"
      @cancel="closeCredentialModal"
    >
      <a-form ref="credFormRef" layout="vertical" :model="credForm">
        <a-form-item :name="'name'" :label="t('strategy.ai.cred_name')" :rules="[{ required: true }]">
          <a-input v-model:value="credForm.name" :placeholder="t('strategy.ai.cred_name_placeholder')" />
        </a-form-item>
        <a-form-item :name="'provider'" :label="t('strategy.ai.provider')" :rules="[{ required: true }]">
          <a-select v-model:value="credForm.provider" :options="providers" :placeholder="t('strategy.ai.provider')"  popup-class-name="vn-portal"/>
        </a-form-item>
        <a-form-item
          :name="'apiKey'"
          :label="t('strategy.ai.api_key')"
          :rules="[{ required: true }]"
          :extra="t('strategy.ai.api_key_help')"
        >
          <a-input-password v-model:value="credForm.apiKey" placeholder="sk-..." autocomplete="new-password" />
        </a-form-item>
      </a-form>
    </a-modal>
  </div>
</template>