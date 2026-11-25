<script setup lang="ts">
/**
 * Unified create/edit strategy modal (ported from the React version).
 * Config fields are rendered dynamically from the strategy type schema
 * (strategy_types.config_schema, enriched with AUTH / TOKEN_RATE_LIMITER
 * subSchemas by the backend).
 */
import { computed, h, onMounted, reactive, ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import type { FormInstance } from 'ant-design-vue/es/form'
import {
  ArrowLeftOutlined, CompassOutlined, EditOutlined, GlobalOutlined,
  KeyOutlined, LockOutlined, PlusOutlined, SafetyOutlined, SettingOutlined,
  ThunderboltOutlined,
} from '@/components/icons'
import type { RouteResponse } from '@/types/route'
import type { StrategyDefinition, StrategyTypeInfo, StrategyTypeSubSchema } from '@/types/strategy'
import { buildStrategyConfig, requiresGlobalUniqueness, strategyToFormValues } from '../utils'
import type { StrategyFormValues } from '../utils'
import { getStrategyRoutes, type StrategyAffectedRoute } from '@/api/strategies'
import { listRoutes } from '@/api/routes'
import { useRequest } from '@/composables/useRequest'
import DynamicConfigFields from './DynamicConfigFields.vue'
import AuthSubSchemaFields from './AuthSubSchemaFields.vue'
import MultiDimRateLimiterFields from './MultiDimRateLimiterFields.vue'
import TransformFields from './TransformFields.vue'
import MockResponseFields from './MockResponseFields.vue'
import TokenRateLimiterFields from './TokenRateLimiterFields.vue'
import AiProxyFields from './AiProxyFields.vue'
import AiSecurityFields from './AiSecurityFields.vue'

const props = defineProps<{
  mode: 'create' | 'edit'
  /** Strategy type metadata driving the dynamic config section. */
  typeInfo: StrategyTypeInfo
  /** Present in edit mode. */
  strategy?: StrategyDefinition
  routes: RouteResponse[]
  /** Create mode: existing GLOBAL strategy of this type (uniqueness guard). */
  existingGlobal?: StrategyDefinition
  submitting?: boolean
  lockBinding?: boolean
  initialRouteId?: string
  onSubmit: (body: StrategyDefinition) => Promise<void>
}>()

const emit = defineEmits<{
  (e: 'cancel'): void
  (e: 'backToTypeSelect'): void
}>()

const { t, locale } = useI18n()

/** Locale-aware type name (the picker already shows both names). */
const typeName = computed(() =>
  (locale.value === 'en' ? (props.typeInfo.typeNameEn || props.typeInfo.typeName) : props.typeInfo.typeName))

/** All type-specific AUTH fields, reset when authType switches. */
const AUTH_RESET_FIELDS = [
  'secretKey',
  'jwtAlgorithm',
  'jwtIssuer',
  'jwtAudience',
  'jwtClockSkewSeconds',
  'apiKey',
  'apiKeyHeader',
  'apiKeyPrefix',
  'apiKeyQueryParam',
  'clientId',
  'clientSecret',
  'tokenEndpoint',
  'requiredScopes',
  'basicUsername',
  'basicPassword',
  'realm',
  'passwordHashAlgorithm',
  'basicUsersJson',
  'accessKey',
  'signatureAlgorithm',
  'clockSkewMinutes',
  'requireNonce',
  'validateContentMd5',
  'accessKeySecretsJson',
]

const AI_SECURITY_DEFAULTS: StrategyFormValues = {
  scanRequest: true,
  scanResponse: true,
  inboundAction: 'BLOCK',
  outboundAction: 'REPLACE',
  enablePiiMasking: true,
  piiAction: 'REPLACE',
  piiTypes: ['EMAIL', 'PHONE', 'ID_CARD'],
  blockMessage: '请求或响应包含不被允许的内容，已被网关拦截。',
  streamingPolicy: 'BUFFER',
  maxBodySize: 1048576,
  responseTimeoutMs: 60000,
  externalAdapter: 'HTTP_JSON',
  externalTimeoutMs: 3000,
  externalFailureAction: 'BLOCK',
}

const isEdit = computed(() => props.mode === 'edit')
const strategyTypeCode = props.strategy?.strategyType ?? props.typeInfo.typeCode
const code = strategyTypeCode
const schema = computed(() => props.typeInfo.configSchema)

function applyFieldsDefaults(target: StrategyFormValues, fields?: Array<{ name: string; type: string; default?: unknown }>) {
  ;(fields ?? []).forEach((field) => {
    if (target[field.name] === undefined) {
      if (field.default !== undefined) target[field.name] = field.default
      else if (field.type === 'switch') target[field.name] = false
    }
  })
}

function applyNestedDefaults(target: StrategyFormValues, sub?: StrategyTypeSubSchema, skipEnabled = true) {
  const fields = (sub?.fields ?? []).filter((field) => !skipEnabled || field.name !== 'enabled')
  applyFieldsDefaults(target, fields)
}

function buildInitialValues(): StrategyFormValues {
  const values: StrategyFormValues = isEdit.value && props.strategy
    ? strategyToFormValues(props.strategy)
    : {
      strategyType: props.typeInfo.typeCode,
      scope: props.initialRouteId || props.existingGlobal ? 'ROUTE' : 'GLOBAL',
      routeId: props.initialRouteId,
      priority: 100,
      enabled: true,
    }

  const typeSchema = props.typeInfo.configSchema
  const subs = typeSchema?.subSchemas ?? {}

  switch (code) {
    case 'AUTH':
      if (values.authType === undefined) values.authType = 'JWT'
      applyNestedDefaults(values, subs[String(values.authType)])
      break
    case 'MULTI_DIM_RATE_LIMITER':
      applyFieldsDefaults(values, typeSchema?.fields)
      if (!Array.isArray(values.dimensions)) values.dimensions = []
      break
    case 'TOKEN_RATE_LIMITER':
      Object.values(subs).forEach((sub) => applyNestedDefaults(values, sub))
      if (!Array.isArray(values.tenantQuotas)) values.tenantQuotas = []
      break
    case 'AI_PROXY':
      applyFieldsDefaults(values, (typeSchema?.fields ?? []).filter((field) => field.name !== 'enabled'))
      if (!Array.isArray(values.providerCredentials)) values.providerCredentials = []
      if (!Array.isArray(values.keyPools)) values.keyPools = []
      if (!Array.isArray(values.modelRouting)) values.modelRouting = []
      break
    case 'AI_SECURITY':
      Object.entries(AI_SECURITY_DEFAULTS).forEach(([key, value]) => {
        if (values[key] === undefined) values[key] = value
      })
      if (!Array.isArray(values.externalHeaderRows)) values.externalHeaderRows = []
      if (!Array.isArray(values.externalOptionRows)) values.externalOptionRows = []
      break
    case 'REQUEST_TRANSFORM':
    case 'RESPONSE_TRANSFORM':
      applyFieldsDefaults(values, typeSchema?.fields)
      if (!values.protocolTransform) values.protocolTransform = { enabled: false }
      if (values.protocolTransform.enabled) applyNestedDefaults(values.protocolTransform, subs.protocolTransform)
      if (!Array.isArray(values.fieldMappingRules)) values.fieldMappingRules = []
      if (!Array.isArray(values.dataMaskingRules)) values.dataMaskingRules = []
      break
    case 'MOCK_RESPONSE':
      applyFieldsDefaults(values, typeSchema?.fields)
      if (!values.staticMock) values.staticMock = {}
      applyNestedDefaults(values.staticMock, subs.staticMock)
      if (!values.templateMock) values.templateMock = {}
      applyNestedDefaults(values.templateMock, subs.templateMock)
      if (!Array.isArray(values.dynamicMockConditions)) values.dynamicMockConditions = []
      if (!values.delay) values.delay = { enabled: false }
      if (values.delay.enabled) applyNestedDefaults(values.delay, subs.delay)
      if (!values.errorSimulation) values.errorSimulation = { enabled: false }
      if (values.errorSimulation.enabled) applyNestedDefaults(values.errorSimulation, subs.errorSimulation)
      break
    default:
      applyFieldsDefaults(values, typeSchema?.fields)
      break
  }

  return values
}

const values = reactive<StrategyFormValues>(buildInitialValues())
const formRef = ref<FormInstance>()
const saving = ref(false)
const busy = computed(() => props.submitting || saving.value)

// ============================================================
// Affected-bindings impact (edit mode)
// ============================================================
const {
  data: impactData, loading: impactLoading, error: impactError, run: runImpact,
} = useRequest(
  (signal) => getStrategyRoutes(props.strategy!.strategyId, { signal }),
  { immediate: false },
)
const {
  data: globalImpactData, loading: globalImpactLoading, error: globalImpactError, run: runGlobalImpact,
} = useRequest(
  (signal) => listRoutes(undefined, { signal }),
  { immediate: false },
)

const expandingGlobal = computed(() =>
  isEdit.value && values.scope === 'GLOBAL' && props.strategy?.scope !== 'GLOBAL')

const impactBlocked = computed(() => isEdit.value && (
  !impactData.value || !!impactError.value || impactLoading.value
  || (expandingGlobal.value
    && (!globalImpactData.value || !!globalImpactError.value || globalImpactLoading.value))
))

const affectedRoutes = computed<StrategyAffectedRoute[]>(() => {
  const merged = new Map<string, StrategyAffectedRoute>()
  ;(impactData.value ?? []).forEach((route) => merged.set(route.id, route))
  if (expandingGlobal.value) {
    ;(globalImpactData.value ?? []).forEach((route) => merged.set(route.id, route))
  }
  if (values.scope === 'ROUTE' && values.routeId) {
    const found = props.routes.find((route) => route.id === values.routeId)
    const entry: StrategyAffectedRoute = found
      ? { id: found.id, routeName: found.routeName }
      : { id: String(values.routeId), routeName: '' }
    merged.set(entry.id, entry)
  }
  return [...merged.values()]
})

function retryImpact() {
  runImpact()
  if (expandingGlobal.value) runGlobalImpact()
}

onMounted(() => {
  if (!isEdit.value) return
  runImpact()
  if (expandingGlobal.value) runGlobalImpact()
})

watch(() => values.scope, (scope) => {
  if (!isEdit.value || scope !== 'GLOBAL' || props.strategy?.scope === 'GLOBAL') return
  if (!globalImpactData.value && !globalImpactLoading.value) runGlobalImpact()
})

// ============================================================
// AUTH type switch
// ============================================================
const authTypeOptions = computed(() => [
  { value: 'JWT', label: h('span', {}, [h(KeyOutlined), ` ${t('strategy.auth_type.jwt')}`]) },
  { value: 'API_KEY', label: h('span', {}, [h(KeyOutlined), ` ${t('strategy.auth_type.api_key')}`]) },
  { value: 'OAUTH2', label: h('span', {}, [h(GlobalOutlined), ` ${t('strategy.auth_type.oauth2')}`]) },
  { value: 'BASIC', label: h('span', {}, [h(LockOutlined), ` ${t('strategy.auth_type.basic')}`]) },
  { value: 'HMAC', label: h('span', {}, [h(SafetyOutlined), ` ${t('strategy.auth_type.hmac')}`]) },
])

const authSubSchema = computed(() => schema.value?.subSchemas?.[String(values.authType || 'JWT')])

function handleAuthTypeChange(nextType: string) {
  values.authType = nextType
  AUTH_RESET_FIELDS.forEach((key) => { delete values[key] })
  applyNestedDefaults(values, schema.value?.subSchemas?.[nextType])
}

// ============================================================
// Base fields / submit
// ============================================================
const globalLocked = computed(() =>
  !isEdit.value && !!props.existingGlobal && requiresGlobalUniqueness(props.typeInfo.typeCode))

const isAiStrategy = computed(() => (props.typeInfo.category || '').toLowerCase() === 'ai')
const selectableRoutes = computed(() =>
  isAiStrategy.value ? props.routes.filter((route) => (route.category || 'NORMAL') === 'AI') : props.routes)
const routeSelectDisabled = computed(() =>
  props.lockBinding || (isEdit.value && props.strategy?.scope === 'ROUTE'))
const routeSelectExtra = computed(() => {
  if (isEdit.value && props.strategy?.scope === 'ROUTE') return t('strategy.impact_edit_bindings_hint')
  return isAiStrategy.value ? t('strategy.route_id_ai_helper') : t('strategy.route_id_helper')
})

const timeoutWarning = computed(() =>
  (!!values.connectTimeout && Number(values.connectTimeout) < 100)
  || (!!values.responseTimeout && Number(values.responseTimeout) < 100))

async function handleFinish() {
  if (impactBlocked.value) return
  try {
    await formRef.value?.validate()
  } catch {
    return
  }
  const body: StrategyDefinition = {
    strategyId: props.strategy?.strategyId ?? '',
    strategyName: String(values.strategyName ?? ''),
    strategyType: strategyTypeCode,
    scope: values.scope === 'ROUTE' ? 'ROUTE' : 'GLOBAL',
    routeId: values.scope === 'ROUTE' ? String(values.routeId ?? '') : undefined,
    routeIds: values.scope === 'ROUTE' ? props.strategy?.routeIds ?? [] : [],
    priority: typeof values.priority === 'number' ? values.priority : 100,
    enabled: values.enabled !== false,
    config: buildStrategyConfig(strategyTypeCode === 'AI_PROXY'
      ? { ...(props.strategy?.config ?? {}), ...values }
      : values, strategyTypeCode),
    description: values.description ? String(values.description) : undefined,
  }
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
    :open="true"
    :width="760"
    :footer="null"
    wrap-class-name="strategy-editor-modal"
    :closable="!busy"
    :keyboard="!busy"
    :mask-closable="false"
    :destroy-on-close="true"
    @cancel="emit('cancel')"
  >
    <template #title>
      <div class="strategy-modal-header">
        <div class="strategy-modal-title-wrapper">
          <a-button
            v-if="!isEdit"
            type="text"
            class="strategy-modal-back"
            @click="emit('backToTypeSelect')"
          >
            <template #icon><ArrowLeftOutlined /></template>
            {{ t('common.back') }}
          </a-button>
          <span :class="['strategy-modal-icon', isEdit ? 'edit' : '']" aria-hidden="true">
            <EditOutlined v-if="isEdit" />
            <PlusOutlined v-else />
          </span>
          <span class="strategy-modal-title-text">
            <span class="strategy-modal-title">
              {{ isEdit ? t('strategy.edit') : `${t('strategy.create')} - ${typeName}` }}
            </span>
            <span class="strategy-modal-subtitle">
              {{ isEdit ? t('strategy.edit_description') : (typeInfo.description || t('strategy.create_description')) }}
            </span>
          </span>
        </div>
      </div>
    </template>

    <a-form
      ref="formRef"
      layout="vertical"
      :model="values"
      :disabled="busy || impactBlocked"
      scroll-to-first-error
      @finish="handleFinish"
    >
      <div class="strategy-editor-scroll">
        <a-alert
          v-if="isEdit"
          :type="impactBlocked ? 'warning' : 'info'"
          show-icon
          class="strategy-alert strategy-impact-alert"
        >
          <template #message>{{ t('strategy.impact_title') }}</template>
          <template #description>
            <p class="strategy-impact-hint">{{ t('strategy.impact_hint') }}</p>
            <span v-if="impactLoading || globalImpactLoading">{{ t('common.loading') }}</span>
            <a-button v-else-if="impactError || globalImpactError" size="small" @click="retryImpact">
              {{ t('strategy.retry') }}
            </a-button>
            <div v-else class="strategy-impact-list">
              <template v-if="affectedRoutes.length">
                <div v-for="route in affectedRoutes" :key="route.id">
                  {{ route.routeName || route.id }}
                  <span class="strategy-impact-route-id">{{ route.id }}</span>
                </div>
              </template>
              <span v-else>{{ t('strategy.impact_no_bindings') }}</span>
            </div>
          </template>
        </a-alert>

        <section class="strategy-form-section">
          <header class="strategy-form-section-head">
            <span class="strategy-form-section-index">01</span>
            <ThunderboltOutlined />
            <span class="strategy-form-section-title">{{ t('strategy.basic_info') }}</span>
          </header>
          <a-row :gutter="16">
            <a-col :span="12">
              <a-form-item
                :name="'strategyName'"
                :label="t('strategy.name')"
                :rules="[{ required: true }]"
                :extra="t('strategy.name_helper')"
              >
                <a-input v-model:value="values.strategyName" size="large" :placeholder="t('strategy.name_placeholder')" />
              </a-form-item>
            </a-col>
            <a-col :span="12">
              <a-form-item v-if="isEdit" :name="'scope'" :label="t('strategy.scope')" :extra="t('strategy.scope_helper')">
                <a-radio-group
                  v-model:value="values.scope"
                  size="large"
                  class="strategy-scope-switch"
                  :disabled="lockBinding"
                >
                  <a-radio-button value="GLOBAL">
                    <GlobalOutlined /> {{ t('strategy.scope_global') }}
                  </a-radio-button>
                  <a-radio-button value="ROUTE">
                    <CompassOutlined /> {{ t('strategy.scope_route') }}
                  </a-radio-button>
                </a-radio-group>
              </a-form-item>
              <div v-else class="strategy-type-display">
                <span class="strategy-type-display-label">{{ t('strategy.type') }}</span>
                <a-tag class="strategy-type-display-tag">{{ typeName }}</a-tag>
              </div>
            </a-col>
          </a-row>
          <a-row v-if="!isEdit" :gutter="16">
            <a-col :span="12">
              <a-form-item
                :name="'scope'"
                :label="t('strategy.scope')"
                :extra="globalLocked
                  ? t('strategy.global_strategy_exists_helper', { type: typeName, name: existingGlobal?.strategyName })
                  : t('strategy.scope_helper')"
              >
                <a-radio-group
                  v-model:value="values.scope"
                  size="large"
                  class="strategy-scope-switch"
                  :disabled="lockBinding"
                >
                  <a-radio-button value="GLOBAL" :disabled="globalLocked || lockBinding">
                    <GlobalOutlined /> {{ t('strategy.scope_global') }}
                  </a-radio-button>
                  <a-radio-button value="ROUTE">
                    <CompassOutlined /> {{ t('strategy.scope_route') }}
                  </a-radio-button>
                </a-radio-group>
              </a-form-item>
            </a-col>
            <a-col :span="12">
              <a-form-item
                v-if="values.scope === 'ROUTE'"
                :name="'routeId'"
                :label="t('strategy.route_id')"
                :rules="[{ required: true }]"
                :extra="routeSelectExtra"
              >
                <a-select
                  v-model:value="values.routeId"
                  size="large"
                  show-search
                  option-filter-prop="label"
                  :placeholder="t('strategy.route_id_placeholder')"
                  :disabled="routeSelectDisabled"
                  :options="selectableRoutes.map((route) => ({
                    value: route.id,
                    label: `${route.routeName || route.id}${(route.category || 'NORMAL') === 'AI' ? ' (AI)' : ''}`,
                  }))"
                />
              </a-form-item>
            </a-col>
          </a-row>
          <a-row v-else :gutter="16">
            <a-col :span="12" />
            <a-col :span="12">
              <a-form-item
                v-if="values.scope === 'ROUTE'"
                :name="'routeId'"
                :label="t('strategy.route_id')"
                :extra="routeSelectExtra"
              >
                <a-select
                  v-model:value="values.routeId"
                  size="large"
                  show-search
                  option-filter-prop="label"
                  :placeholder="t('strategy.route_id_placeholder')"
                  :disabled="routeSelectDisabled"
                  :options="selectableRoutes.map((route) => ({
                    value: route.id,
                    label: `${route.routeName || route.id}${(route.category || 'NORMAL') === 'AI' ? ' (AI)' : ''}`,
                  }))"
                />
              </a-form-item>
            </a-col>
          </a-row>
        </section>

        <section class="strategy-form-section">
          <header class="strategy-form-section-head">
            <span class="strategy-form-section-index">02</span>
            <SettingOutlined />
            <span class="strategy-form-section-title">{{ t('strategy.config') }}</span>
            <span class="strategy-form-section-hint">{{ t('strategy.config_helper') }}</span>
          </header>
            <AiSecurityFields v-if="code === 'AI_SECURITY'" :values="values" />

            <template v-else-if="!schema" />

            <template v-else-if="code === 'AUTH' && schema.subSchemas">
              <a-row :gutter="16">
                <a-col :span="12">
                  <a-form-item :name="'authType'" :label="t('strategy.config.auth_type')">
                    <a-select
                      :value="values.authType"
                      :options="authTypeOptions"
                      @update:value="handleAuthTypeChange"
                    />
                  </a-form-item>
                </a-col>
              </a-row>
              <AuthSubSchemaFields
                v-if="authSubSchema?.fields"
                :fields="authSubSchema.fields"
                :auth-type="String(values.authType || 'JWT')"
                :values="values"
              />
            </template>

            <MultiDimRateLimiterFields
              v-else-if="code === 'MULTI_DIM_RATE_LIMITER' && schema.multiDimension"
              :schema="schema"
              :values="values"
            />

            <TransformFields
              v-else-if="code === 'REQUEST_TRANSFORM' && schema.subSchemas"
              :schema="schema"
              :values="values"
            />

            <TransformFields
              v-else-if="code === 'RESPONSE_TRANSFORM' && schema.subSchemas"
              :schema="schema"
              :values="values"
            />

            <MockResponseFields
              v-else-if="code === 'MOCK_RESPONSE' && schema.subSchemas"
              :schema="schema"
              :values="values"
            />

            <TokenRateLimiterFields
              v-else-if="code === 'TOKEN_RATE_LIMITER' && schema.subSchemas"
              :schema="schema"
              :values="values"
            />

            <AiProxyFields v-else-if="code === 'AI_PROXY'" :schema="schema" :values="values" />

            <template v-else-if="code === 'TIMEOUT'">
              <a-alert v-if="timeoutWarning" type="warning" show-icon class="strategy-alert" :message="t('strategy.timeout_low_warning')" />
              <DynamicConfigFields :schema="schema" :values="values" />
            </template>

            <DynamicConfigFields v-else :schema="schema" :values="values" />
        </section>

        <section class="strategy-form-section">
          <header class="strategy-form-section-head">
            <span class="strategy-form-section-index">03</span>
            <SettingOutlined />
            <span class="strategy-form-section-title">{{ t('strategy.advanced_settings') }}</span>
          </header>
          <a-row :gutter="16">
            <a-col :span="8">
              <a-form-item :name="'priority'" :label="t('strategy.priority')" :extra="t('strategy.priority_helper')">
                <a-input-number v-model:value="values.priority" :min="1" :max="1000" size="large" style="width: 100%" />
              </a-form-item>
            </a-col>
            <a-col :span="8">
              <a-form-item :name="'enabled'" :label="t('strategy.enabled')">
                <a-switch
                  v-model:checked="values.enabled"
                  :checked-children="t('common.enabled')"
                  :un-checked-children="t('common.disabled')"
                />
              </a-form-item>
            </a-col>
          </a-row>
          <a-form-item :name="'description'" :label="t('strategy.description_label')" :extra="t('strategy.description_helper')">
            <a-textarea v-model:value="values.description" :rows="2" show-count :maxlength="500" />
          </a-form-item>
        </section>
      </div>

      <div class="strategy-modal-footer">
        <div class="strategy-footer-meta">
          <a-tag class="strategy-type-tag">{{ typeName }}</a-tag>
          <span>{{ values.scope === 'GLOBAL' ? t('strategy.scope_global') : t('strategy.scope_route') }}</span>
        </div>
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