<script setup lang="ts">
/**
 * Create/edit auth policy modal (unified), ported from the React version.
 * Form field names map 1:1 to the flat fields of model/AuthPolicyDefinition.java.
 * On edit the full stored definition is merged under the form values — the
 * backend PUT replaces the whole config, so fields not exposed in the form
 * (whitelistPaths, apiKeys, ...) must be carried over.
 */
import { computed, onMounted, reactive, ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import type { FormInstance } from 'ant-design-vue/es/form'
import {
  EditOutlined, InfoCircleOutlined, PlusOutlined, SafetyOutlined, SettingOutlined,
  ThunderboltOutlined, WarningOutlined,
} from '@/components/icons'
import { listBindingsForPolicy } from '@/api/authPolicies'
import { useRequest } from '@/composables/useRequest'
import type { AuthPolicyDefinition, AuthType } from '@/types/authPolicy'
import { AUTH_CONFIG_FIELDS, AUTH_TYPE_DEFAULTS, AUTH_TYPES, getAuthTypeLabel } from '../utils'

const props = defineProps<{
  mode: 'create' | 'edit'
  /** Required for edit mode — the full stored definition. */
  policy?: AuthPolicyDefinition
  /** Kept mounted by the page and toggled here (unmounting an open antd modal
   *  leaks its dialog tree; see perf audit 2026-11-20). */
  open?: boolean
  onSubmit: (body: AuthPolicyDefinition) => Promise<void>
}>()

const emit = defineEmits<{
  (e: 'cancel'): void
}>()

const { t } = useI18n()

const isOpen = computed(() => props.open !== false)
const isEdit = computed(() => props.mode === 'edit')

// Dynamic flat fields (config fields vary by auth type) — same shape as the
// strategies feature uses for its schema-driven form.
// eslint-disable-next-line @typescript-eslint/no-explicit-any
const values = reactive<Record<string, any>>(
  isEdit.value && props.policy ? { ...props.policy } : { enabled: true },
)
const formRef = ref<FormInstance>()
const saving = ref(false)
const busy = computed(() => saving.value)

const authTypeOptions = computed(() =>
  AUTH_TYPES.map((type) => ({ value: type, label: getAuthTypeLabel(type, t) })))

// ============================================================
// Bound-routes impact (edit mode): PUT replaces the whole config.
// ============================================================
const {
  data: impactData, loading: impactLoading, error: impactError, run: runImpact,
} = useRequest(
  (signal) => listBindingsForPolicy(props.policy!.policyId!, { signal }),
  { immediate: false, onError: () => {} },
)
const impactBlocked = computed(() =>
  isEdit.value && (impactLoading.value || !!impactError.value || !impactData.value))
const impactRouteIds = computed(() =>
  [...new Set((impactData.value ?? []).map((binding) => binding.routeId))])

onMounted(() => {
  if (isEdit.value && props.policy?.policyId) runImpact()
})

watch(isOpen, (open) => {
  if (!open) return
  for (const key of Object.keys(values)) delete values[key]
  Object.assign(values, isEdit.value && props.policy ? { ...props.policy } : { enabled: true })
  saving.value = false
  if (isEdit.value && props.policy?.policyId) runImpact()
})

function handleTypeChange(nextType: AuthType) {
  values.authType = nextType
  // Drop the previous type's config, then apply backend-aligned defaults.
  AUTH_CONFIG_FIELDS.forEach((key) => { delete values[key] })
  const defaults = AUTH_TYPE_DEFAULTS[nextType]
  if (defaults) Object.assign(values, defaults)
}

async function handleFinish() {
  if (impactBlocked.value) return
  try {
    await formRef.value?.validate()
  } catch {
    return
  }
  const body: AuthPolicyDefinition = isEdit.value && props.policy
    ? { ...props.policy, ...values, policyId: props.policy.policyId }
    : { ...values } as AuthPolicyDefinition
  saving.value = true
  try {
    await props.onSubmit(body)
  } finally {
    saving.value = false
  }
}
</script>

<template>
  <a-modal
    :open="isOpen"
    :width="600"
    :footer="null"
    :style="{ top: '24px' }"
    wrap-class-name="vn-portal vn-modal"
    :closable="!busy"
    :keyboard="!busy"
    :mask-closable="false"
    @cancel="emit('cancel')"
  >
    <template #title>
      <div class="vn-modal-head">
        <span class="vn-modal-head-glyph" aria-hidden="true">
          <EditOutlined v-if="isEdit" />
          <PlusOutlined v-else />
        </span>
        <div style="min-width: 0">
          <div class="vn-modal-title">
            {{ t(isEdit ? 'auth_policy.edit' : 'auth_policy.create') }}
          </div>
          <div class="vn-modal-subtitle">
            {{ isEdit ? policy?.policyName : t('auth_policy.create_description') }}
          </div>
        </div>
      </div>
    </template>

    <a-form
      ref="formRef"
      layout="vertical"
      class="vn-form"
      :model="values"
      :disabled="busy || impactBlocked"
      scroll-to-first-error
      @finish="handleFinish"
    >
      <div class="vn-modal-scroll">
        <div
          v-if="isEdit"
          :class="['auth-impact-block', impactBlocked ? 'is-blocked' : '']"
        >
          <span class="auth-impact-glyph" aria-hidden="true">
            <WarningOutlined v-if="impactBlocked" />
            <InfoCircleOutlined v-else />
          </span>
          <div class="auth-impact-body">
            <span class="auth-impact-title">{{ t('strategy.impact_title') }}</span>
            <p class="auth-impact-hint">{{ t('strategy.impact_hint') }}</p>
            <span v-if="impactLoading" class="auth-impact-status">{{ t('common.loading') }}</span>
            <a-button v-else-if="impactError" size="small" :disabled="false" @click="runImpact">
              {{ t('strategy.retry') }}
            </a-button>
            <div v-else class="auth-impact-routes">
              <template v-if="impactRouteIds.length">
                <code v-for="routeId in impactRouteIds" :key="routeId" class="auth-impact-route">
                  {{ routeId }}
                </code>
              </template>
              <span v-else class="auth-impact-status">{{ t('strategy.impact_no_bindings') }}</span>
            </div>
          </div>
        </div>

        <div class="form-section-modern">
          <div class="section-header">
            <ThunderboltOutlined class="section-icon" />
            <span class="section-title">{{ t('strategy.basic_info') }}</span>
          </div>
          <div class="section-content">
            <a-form-item
              name="policyName"
              :label="t('auth_policy.policy_name')"
              :rules="[{ required: true, whitespace: true }]"
            >
              <a-input
                v-model:value="values.policyName"
               
                :placeholder="t('auth_policy.policy_name_placeholder')"
              />
            </a-form-item>
            <a-form-item
              name="authType"
              :label="t('auth_policy.auth_type')"
              :rules="[{ required: true }]"
            >
              <a-select
                :value="values.authType"
               
                :disabled="isEdit"
                :options="authTypeOptions"
                @update:value="handleTypeChange"
               popup-class-name="vn-portal"/>
            </a-form-item>
          </div>
        </div>

        <div class="form-section-modern">
          <div class="section-header">
            <SafetyOutlined class="section-icon" />
            <span class="section-title">{{ t('auth_policy.config_section') }}</span>
          </div>
          <div class="section-content">
            <template v-if="values.authType === 'JWT'">
              <a-form-item
                name="secretKey"
                :label="t('auth_policy.jwt_secret')"
                :rules="[{ required: true }]"
              >
                <a-input-password v-model:value="values.secretKey" />
              </a-form-item>
              <a-row :gutter="16">
                <a-col :span="12">
                  <a-form-item name="jwtAlgorithm" :label="t('auth_policy.jwt_algorithm')">
                    <a-select
                      v-model:value="values.jwtAlgorithm"
                      :options="[
                        { value: 'HS256', label: 'HS256' },
                        { value: 'HS512', label: 'HS512' },
                        { value: 'RS256', label: 'RS256' },
                      ]"
                     popup-class-name="vn-portal"/>
                  </a-form-item>
                </a-col>
                <a-col :span="12">
                  <a-form-item name="jwtClockSkewSeconds" :label="t('auth_policy.jwt_clock_skew')">
                    <a-input-number v-model:value="values.jwtClockSkewSeconds" :min="0" style="width: 100%" />
                  </a-form-item>
                </a-col>
              </a-row>
              <a-form-item name="jwtIssuer" :label="t('auth_policy.jwt_issuer')">
                <a-input v-model:value="values.jwtIssuer" />
              </a-form-item>
              <a-form-item name="jwtAudience" :label="t('auth_policy.jwt_audience')">
                <a-input v-model:value="values.jwtAudience" />
              </a-form-item>
            </template>

            <template v-else-if="values.authType === 'API_KEY'">
              <a-form-item
                name="apiKey"
                :label="t('auth_policy.api_key')"
                :rules="[{ required: true }]"
              >
                <a-input-password v-model:value="values.apiKey" />
              </a-form-item>
              <a-form-item name="apiKeyHeader" :label="t('auth_policy.api_key_header')">
                <a-input v-model:value="values.apiKeyHeader" placeholder="X-API-Key" />
              </a-form-item>
              <a-form-item name="apiKeyPrefix" :label="t('auth_policy.api_key_prefix')">
                <a-input v-model:value="values.apiKeyPrefix" placeholder="e.g., sk_live_" />
              </a-form-item>
            </template>

            <template v-else-if="values.authType === 'OAUTH2'">
              <a-form-item
                name="clientId"
                :label="t('auth_policy.client_id')"
                :rules="[{ required: true }]"
              >
                <a-input v-model:value="values.clientId" />
              </a-form-item>
              <a-form-item
                name="clientSecret"
                :label="t('auth_policy.client_secret')"
                :rules="[{ required: true }]"
              >
                <a-input-password v-model:value="values.clientSecret" />
              </a-form-item>
              <a-form-item
                name="tokenEndpoint"
                :label="t('auth_policy.token_endpoint')"
                :rules="[{ required: true }]"
              >
                <a-input v-model:value="values.tokenEndpoint" placeholder="https://auth.example.com/introspect" />
              </a-form-item>
              <a-form-item name="requiredScopes" :label="t('auth_policy.required_scopes')">
                <a-input v-model:value="values.requiredScopes" placeholder="read,write" />
              </a-form-item>
            </template>

            <template v-else-if="values.authType === 'BASIC'">
              <a-form-item
                name="basicUsername"
                :label="t('auth_policy.username')"
                :rules="[{ required: true }]"
              >
                <a-input v-model:value="values.basicUsername" />
              </a-form-item>
              <a-form-item
                name="basicPassword"
                :label="t('auth_policy.password')"
                :rules="[{ required: true }]"
              >
                <a-input-password v-model:value="values.basicPassword" />
              </a-form-item>
              <a-form-item name="realm" :label="t('auth_policy.realm')">
                <a-input v-model:value="values.realm" placeholder="API Gateway" />
              </a-form-item>
            </template>

            <template v-else-if="values.authType === 'HMAC'">
              <a-form-item
                name="accessKey"
                :label="t('auth_policy.access_key')"
                :rules="[{ required: true }]"
              >
                <a-input v-model:value="values.accessKey" />
              </a-form-item>
              <a-form-item name="signatureAlgorithm" :label="t('auth_policy.signature_algorithm')">
                <a-select
                  v-model:value="values.signatureAlgorithm"
                  :options="[
                    { value: 'HMAC-SHA256', label: 'HMAC-SHA256' },
                    { value: 'HMAC-SHA512', label: 'HMAC-SHA512' },
                  ]"
                 popup-class-name="vn-portal"/>
              </a-form-item>
            </template>
          </div>
        </div>

        <div class="form-section-modern">
          <div class="section-header">
            <SettingOutlined class="section-icon" />
            <span class="section-title">{{ t('strategy.advanced_settings') }}</span>
          </div>
          <div class="section-content">
            <a-form-item name="description" :label="t('common.description')">
              <a-textarea v-model:value="values.description" :rows="2" />
            </a-form-item>
            <a-form-item v-if="isEdit" name="enabled" :label="t('common.status')">
              <a-switch
                v-model:checked="values.enabled"
                :checked-children="t('common.enabled')"
                :un-checked-children="t('common.disabled')"
              />
            </a-form-item>
          </div>
        </div>
      </div>

      <div class="modal-footer-modern">
        <a-button :disabled="busy" @click="emit('cancel')">{{ t('common.cancel') }}</a-button>
        <a-button type="primary" html-type="submit" :loading="busy" :disabled="impactBlocked">
          <template #icon>
            <EditOutlined v-if="isEdit" />
            <PlusOutlined v-else />
          </template>
          {{ t(isEdit ? 'common.update' : 'common.create') }}
        </a-button>
      </div>
    </a-form>
  </a-modal>
</template>