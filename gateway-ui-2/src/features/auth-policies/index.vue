<script setup lang="ts">
/**
 * Auth policies page — fields aligned with AuthPolicyController (/api/auth)
 * + model/AuthPolicyDefinition.java + model/RouteAuthBindingEntity.java.
 *
 * Backend quirks handled here (same as the React version):
 * - PUT/DELETE/enable/disable take no instanceId param.
 * - GET /api/auth/bindings returns bindings of ALL instances → filtered
 *   client-side by binding.instanceId.
 * - PUT replaces the whole config → edit submits the merged definition.
 */
import { computed, ref } from 'vue'
import { useRoute } from 'vue-router'
import { useI18n } from 'vue-i18n'
import {
  CheckCircleOutlined, ExclamationCircleOutlined, KeyOutlined, LinkOutlined, LockOutlined,
  PlusOutlined, ReloadOutlined,
} from '@/components/icons'
import { useRequest } from '@/composables/useRequest'
import { ApiError } from '@/api/http'
import {
  createAuthBinding,
  createAuthPolicy,
  deleteAuthBinding,
  deleteAuthPolicy,
  disableAuthBinding,
  disableAuthPolicy,
  enableAuthBinding,
  enableAuthPolicy,
  listAuthBindings,
  listAuthPolicies,
  updateAuthPolicy,
} from '@/api/authPolicies'
import { listRoutes } from '@/api/routes'
import type { AuthPolicyDefinition, RouteAuthBinding } from '@/types/authPolicy'
import { message } from '@/utils/feedback'
import InlineStatChips from '@/components/InlineStatChips.vue'
import AuthPolicyCard from './components/AuthPolicyCard.vue'
import AuthPolicyFormModal from './components/AuthPolicyFormModal.vue'
import BindingModal from './components/BindingModal.vue'
import DeletePolicyModal from './components/DeletePolicyModal.vue'
import UsageExampleModal from './components/UsageExampleModal.vue'
import { AUTH_TYPES, getAuthTypeLabel } from './utils'
import './auth-policies.css'

const route = useRoute()
const { t } = useI18n()

const instanceId = computed(() => route.params.instanceId as string | undefined)

const filterType = ref('all')
const createOpen = ref(false)
const editingPolicy = ref<AuthPolicyDefinition | null>(null)
const bindingPolicy = ref<AuthPolicyDefinition | null>(null)
const usagePolicy = ref<AuthPolicyDefinition | null>(null)
const deletingPolicy = ref<AuthPolicyDefinition | null>(null)

const errorMessage = (error: unknown, fallbackKey: string): string =>
  (error instanceof ApiError && error.message ? error.message : t(fallbackKey))

const policiesRequest = useRequest(
  (signal) => listAuthPolicies(instanceId.value ? { instanceId: instanceId.value } : undefined, { signal }),
  {
    deps: [instanceId],
    onError: () => {
      message.error(t('auth_policy.load_failed'))
    },
  },
)
const policies = computed<AuthPolicyDefinition[]>(() => policiesRequest.data.value ?? [])
const loadFailed = computed(() => !!policiesRequest.error.value && policies.value.length === 0)

// Best-effort context for the binding modal route selects.
const routesRequest = useRequest(
  (signal) => listRoutes(instanceId.value ? { instanceId: instanceId.value } : undefined, { signal }),
  { deps: [instanceId], onError: () => {} },
)
const routes = computed(() => routesRequest.data.value ?? [])

// Backend returns bindings of every instance; filter for this one.
const bindingsRequest = useRequest((signal) => listAuthBindings({ signal }), {
  onError: () => {},
})
const bindings = computed<RouteAuthBinding[]>(() => {
  const all = bindingsRequest.data.value ?? []
  return instanceId.value ? all.filter((binding) => binding.instanceId === instanceId.value) : all
})

const bindingsByPolicy = computed(() => {
  const map = new Map<string, RouteAuthBinding[]>()
  for (const binding of bindings.value) {
    const list = map.get(binding.policyId)
    if (list) list.push(binding)
    else map.set(binding.policyId, [binding])
  }
  return map
})

const filteredPolicies = computed(() =>
  policies.value.filter((policy) => filterType.value === 'all' || policy.authType === filterType.value))

const stats = computed(() => ({
  total: policies.value.length,
  enabled: policies.value.filter((policy) => policy.enabled).length,
  boundRoutes: new Set(bindings.value.filter((binding) => binding.enabled).map((binding) => binding.routeId)).size,
}))

const statsItems = computed(() => [
  { key: 'total', value: stats.value.total, label: t('auth_policy.stats_total'), icon: KeyOutlined },
  { key: 'enabled', value: stats.value.enabled, label: t('auth_policy.stats_enabled'), tone: 'green' as const, icon: CheckCircleOutlined },
  { key: 'bound', value: stats.value.boundRoutes, label: t('auth_policy.stats_bound'), tone: 'blue' as const, icon: LinkOutlined },
])

const typeFilterOptions = computed(() => [
  { value: 'all', label: t('auth_policy.all_types'), count: policies.value.length },
  ...AUTH_TYPES.map((type) => ({
    value: type,
    label: getAuthTypeLabel(type, t),
    count: policies.value.filter((policy) => policy.authType === type).length,
  })),
])

const filterEmpty = computed(() =>
  policies.value.length > 0 && filteredPolicies.value.length === 0)

const filterLabel = computed(() =>
  typeFilterOptions.value.find((option) => option.value === filterType.value)?.label ?? '')

const policyCountFor = (policy: AuthPolicyDefinition): RouteAuthBinding[] =>
  bindingsByPolicy.value.get(policy.policyId ?? '') ?? []

function getRouteName(routeId: string): string {
  return routes.value.find((r) => r.id === routeId)?.routeName
    || routeId.substring(0, 12)
    || 'Unknown'
}

// ============================================================
// Policy CRUD handlers
// ============================================================

function refresh() {
  policiesRequest.run()
  bindingsRequest.run()
}

async function handleCreate(body: AuthPolicyDefinition) {
  try {
    await createAuthPolicy(body, instanceId.value)
    message.success(t('message.create_success'))
    createOpen.value = false
    policiesRequest.run()
  } catch (error) {
    message.error(errorMessage(error, 'message.create_failed'))
  }
}

async function handleEdit(body: AuthPolicyDefinition) {
  if (!editingPolicy.value?.policyId) return
  try {
    await updateAuthPolicy(editingPolicy.value.policyId, body)
    message.success(t('message.update_success'))
    editingPolicy.value = null
    policiesRequest.run()
  } catch (error) {
    message.error(errorMessage(error, 'message.update_failed'))
  }
}

async function handleDelete(policy: AuthPolicyDefinition): Promise<boolean> {
  if (!policy.policyId) return false
  try {
    await deleteAuthPolicy(policy.policyId)
    message.success(t('message.delete_success'))
    refresh()
    return true
  } catch (error) {
    message.error(errorMessage(error, 'message.delete_failed'))
    return false
  }
}

async function handleToggle(policy: AuthPolicyDefinition) {
  if (!policy.policyId) return
  try {
    if (policy.enabled) {
      await disableAuthPolicy(policy.policyId)
      message.success(t('auth_policy.disabled'))
    } else {
      await enableAuthPolicy(policy.policyId)
      message.success(t('auth_policy.enabled'))
    }
    policiesRequest.run()
  } catch (error) {
    message.error(errorMessage(error, 'message.update_failed'))
  }
}

// ============================================================
// Binding handlers
// ============================================================

async function handleAddBindings(routeIds: string[], priority: number): Promise<boolean> {
  if (!bindingPolicy.value?.policyId) return false
  const policyId = bindingPolicy.value.policyId
  const results = await Promise.allSettled(
    routeIds.map((routeId) => createAuthBinding({ policyId, routeId, priority })),
  )
  const successCount = results.filter((result) => result.status === 'fulfilled').length
  const failCount = results.length - successCount
  if (successCount > 0) {
    message.success(`${t('auth_policy.binding_created')} (${successCount})`)
    bindingsRequest.run()
  }
  if (failCount > 0) {
    message.error(`${t('auth_policy.binding_failed')} (${failCount})`)
  }
  return failCount === 0
}

async function handleUnbind(binding: RouteAuthBinding) {
  try {
    await deleteAuthBinding(binding.bindingId)
    message.success(t('auth_policy.binding_removed'))
    bindingsRequest.run()
  } catch (error) {
    message.error(errorMessage(error, 'message.delete_failed'))
  }
}

async function handleToggleBinding(binding: RouteAuthBinding) {
  try {
    if (binding.enabled) {
      await disableAuthBinding(binding.bindingId)
    } else {
      await enableAuthBinding(binding.bindingId)
    }
    bindingsRequest.run()
  } catch (error) {
    message.error(errorMessage(error, 'auth_policy.binding_toggle_failed'))
  }
}
</script>

<template>
  <div class="auth-policies-page">
    <!-- Header: title + inline stats + create -->
    <div class="page-header-modern auth-page-header">
      <div class="page-header-left">
        <h3 class="page-title-main auth-page-title">
          <span class="auth-page-glyph" aria-hidden="true"><KeyOutlined /></span>
          <span class="auth-page-heading">
            <span class="auth-page-name">{{ t('auth_policy.title') }}</span>
            <span class="auth-page-subtitle">{{ t('auth_policy.description') }}</span>
          </span>
        </h3>
      </div>
      <div class="page-header-right">
        <InlineStatChips :items="statsItems" />
        <a-button type="primary" class="create-btn" @click="createOpen = true">
          <template #icon><PlusOutlined /></template>
          {{ t('auth_policy.create') }}
        </a-button>
      </div>
    </div>

    <!-- Type filter pills with per-type counts -->
    <div class="auth-filter-row" role="tablist" :aria-label="t('auth_policy.auth_type')">
      <button
        v-for="option in typeFilterOptions"
        :key="option.value"
        type="button"
        role="tab"
        :aria-selected="filterType === option.value"
        :class="['auth-filter-pill', filterType === option.value ? 'is-active' : '']"
        @click="filterType = option.value"
      >
        {{ option.label }}
        <span class="auth-filter-count">{{ option.count }}</span>
      </button>
    </div>

    <a-spin :spinning="policiesRequest.loading.value">
      <!-- First-load failure -->
      <a-card v-if="loadFailed" :bordered="false" class="auth-state-card">
        <div class="auth-state">
          <span class="auth-state-glyph is-error" aria-hidden="true"><ExclamationCircleOutlined /></span>
          <h4 class="auth-state-title">{{ t('auth_policy.load_failed') }}</h4>
          <p class="auth-state-desc">{{ policiesRequest.error.value?.message }}</p>
          <a-button @click="refresh">
            <template #icon><ReloadOutlined /></template>
            {{ t('common.refresh') }}
          </a-button>
        </div>
      </a-card>

      <!-- Empty (no policies at all / filter matched nothing) -->
      <a-card v-else-if="filteredPolicies.length === 0" :bordered="false" class="auth-state-card">
        <div class="auth-state">
          <span class="auth-state-glyph" aria-hidden="true"><LockOutlined /></span>
          <h4 class="auth-state-title">
            {{ filterEmpty ? filterLabel : t('auth_policy.empty') }}
          </h4>
          <p v-if="filterEmpty" class="auth-state-desc">{{ t('auth_policy.empty') }}</p>
          <a-button v-if="filterEmpty" @click="filterType = 'all'">
            {{ t('auth_policy.all_types') }}
          </a-button>
          <a-button v-else type="primary" @click="createOpen = true">
            <template #icon><PlusOutlined /></template>
            {{ t('auth_policy.create') }}
          </a-button>
        </div>
      </a-card>

      <div v-else class="auth-policy-grid">
        <AuthPolicyCard
          v-for="policy in filteredPolicies"
          :key="policy.policyId"
          :policy="policy"
          :bindings="policyCountFor(policy)"
          :route-name="getRouteName"
          @edit="editingPolicy = $event"
          @bind="bindingPolicy = $event"
          @usage="usagePolicy = $event"
          @toggle="handleToggle"
          @delete="deletingPolicy = $event"
        />
      </div>
    </a-spin>

    <!-- Modals (conditionally mounted so forms start fresh) -->
    <AuthPolicyFormModal
      :open="createOpen"
      mode="create"
      :on-submit="handleCreate"
      @cancel="createOpen = false"
    />
    <AuthPolicyFormModal
      :open="!!editingPolicy"
      mode="edit"
      :policy="editingPolicy ?? undefined"
      :on-submit="handleEdit"
      @cancel="editingPolicy = null"
    />
    <BindingModal
      v-if="bindingPolicy"
      :policy="bindingPolicy"
      :bindings="policyCountFor(bindingPolicy)"
      :routes="routes"
      :on-add-bindings="handleAddBindings"
      :on-unbind="handleUnbind"
      :on-toggle-binding="handleToggleBinding"
      @close="bindingPolicy = null"
    />
    <UsageExampleModal
      v-if="usagePolicy"
      :policy="usagePolicy"
      @close="usagePolicy = null"
    />
    <DeletePolicyModal
      v-if="deletingPolicy"
      :policy="deletingPolicy"
      :binding-count="policyCountFor(deletingPolicy).length"
      :on-confirm="handleDelete"
      @cancel="deletingPolicy = null"
    />
  </div>
</template>