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
 *
 * Rendered on the Vue-native design layer; API calls and payloads are unchanged.
 */
import { computed, ref, useTemplateRef, watch } from 'vue'
import { useRoute } from 'vue-router'
import { useI18n } from 'vue-i18n'
import {
  AppstoreOutlined, CheckCircleOutlined, ExclamationCircleOutlined, KeyOutlined,
  LockOutlined, OrderedListOutlined, PauseCircleOutlined, PlusOutlined,
  ReloadOutlined, SearchOutlined,
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
import VnIconButton from '@/components/vn/VnIconButton.vue'
import VnSegmented, { type VnSegmentOption } from '@/components/vn/VnSegmented.vue'
import VnStatRail, { type VnStatItem } from '@/components/vn/VnStatRail.vue'
import VnState from '@/components/vn/VnState.vue'
import VnToolbar from '@/components/vn/VnToolbar.vue'
import AuthPolicyCard from './components/AuthPolicyCard.vue'
import AuthPolicyTable from './components/AuthPolicyTable.vue'
import AuthPolicyFormModal from './components/AuthPolicyFormModal.vue'
import BindingModal from './components/BindingModal.vue'
import DeletePolicyModal from './components/DeletePolicyModal.vue'
import UsageExampleModal from './components/UsageExampleModal.vue'
import { AUTH_TYPES, getAuthTypeLabel } from './utils'
import '@/styles/vue-native.css'
import '@/styles/vue-native-shared.css'
import './auth-policies-vue-native.css'

// Named so the shell's KeepAlive can cache it.
defineOptions({ name: 'AuthPoliciesPage' })

const route = useRoute()
const { t } = useI18n()

const instanceId = computed(() => route.params.instanceId as string | undefined)

type StatusFilter = 'all' | 'enabled' | 'disabled'

const viewPreferenceKey = 'gateway.authPolicies.view'
function readViewPreference(): 'grid' | 'table' | undefined {
  try {
    const value = localStorage.getItem(viewPreferenceKey)
    return value === 'grid' || value === 'table' ? value : undefined
  } catch {
    return undefined
  }
}

const viewPreference = ref(readViewPreference())
const filterType = ref('all')
const statusFilter = ref<StatusFilter>('all')
const searchTerm = ref('')
const createOpen = ref(false)
const editingPolicy = ref<AuthPolicyDefinition | null>(null)
const bindingPolicy = ref<AuthPolicyDefinition | null>(null)
const usagePolicy = ref<AuthPolicyDefinition | null>(null)
const deletingPolicy = ref<AuthPolicyDefinition | null>(null)
const searchInput = useTemplateRef<{ focus: () => void }>('searchInput')

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

const policyCountFor = (policy: AuthPolicyDefinition): RouteAuthBinding[] =>
  bindingsByPolicy.value.get(policy.policyId ?? '') ?? []

const statusCounts = computed(() => {
  const enabled = policies.value.filter((policy) => policy.enabled).length
  return { total: policies.value.length, enabled, disabled: policies.value.length - enabled }
})

const filteredPolicies = computed(() => {
  const term = searchTerm.value.trim().toLowerCase()
  return policies.value.filter((policy) => {
    if (filterType.value !== 'all' && policy.authType !== filterType.value) return false
    if (statusFilter.value === 'enabled' && !policy.enabled) return false
    if (statusFilter.value === 'disabled' && policy.enabled) return false
    if (term && !policy.policyName.toLowerCase().includes(term)) return false
    return true
  })
})

const isFiltered = computed(() =>
  filterType.value !== 'all' || statusFilter.value !== 'all' || searchTerm.value.trim() !== '')

const typeOptions = computed<VnSegmentOption<string>[]>(() => [
  { key: 'all', label: t('auth_policy.all_types'), count: policies.value.length },
  ...AUTH_TYPES.map((type) => ({
    key: type as string,
    label: getAuthTypeLabel(type, t),
    count: policies.value.filter((policy) => policy.authType === type).length,
  })),
])

const statItems = computed<VnStatItem[]>(() => {
  const c = statusCounts.value
  return [
    {
      key: 'all',
      value: c.total,
      label: t('auth_policy.stats_total'),
      icon: KeyOutlined,
      active: statusFilter.value === 'all',
    },
    {
      key: 'enabled',
      value: c.enabled,
      label: t('auth_policy.stats_enabled'),
      icon: CheckCircleOutlined,
      accent: 'var(--vn-ok)',
      active: statusFilter.value === 'enabled',
    },
    {
      key: 'disabled',
      value: c.disabled,
      label: t('auth_policy.stats_disabled'),
      icon: PauseCircleOutlined,
      accent: 'var(--vn-idle)',
      active: statusFilter.value === 'disabled',
    },
  ]
})

function selectStat(key: string) {
  statusFilter.value = key as StatusFilter
}

const pageSize = 24
const view = computed({
  get: () => viewPreference.value ?? (statusCounts.value.total > pageSize ? 'table' : 'grid'),
  set: (value: 'grid' | 'table') => {
    viewPreference.value = value
    try { localStorage.setItem(viewPreferenceKey, value) } catch { /* storage is optional */ }
  },
})
const page = ref(1)
const pageCount = computed(() => Math.max(1, Math.ceil(filteredPolicies.value.length / pageSize)))
const visiblePolicies = computed(() => {
  const start = (Math.min(page.value, pageCount.value) - 1) * pageSize
  return filteredPolicies.value.slice(start, start + pageSize)
})

watch([filterType, statusFilter, searchTerm], () => { page.value = 1 })
watch(pageCount, (count) => { page.value = Math.min(page.value, count) })
watch(instanceId, () => {
  editingPolicy.value = null
  bindingPolicy.value = null
  usagePolicy.value = null
  deletingPolicy.value = null
  createOpen.value = false
  page.value = 1
}, { flush: 'sync' })

function resetFilters() {
  filterType.value = 'all'
  statusFilter.value = 'all'
  searchTerm.value = ''
  searchInput.value?.focus()
}

const viewOptions = computed<VnSegmentOption<'grid' | 'table'>[]>(() => [
  { key: 'grid', label: t('auth_policy.view_grid'), icon: AppstoreOutlined },
  { key: 'table', label: t('auth_policy.view_table'), icon: OrderedListOutlined },
])

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
  <div class="auth-policies-page vn-auth vn-scope">
    <VnToolbar
      :title="t('auth_policy.title')"
      :lede="t('auth_policy.description')"
    >
      <template #actions>
        <VnSegmented v-model="view" :options="viewOptions" :label="t('auth_policy.view_mode')" />
        <VnIconButton
          :icon="ReloadOutlined"
          :label="t('common.refresh')"
          :disabled="policiesRequest.loading.value"
          @click="refresh"
        />
        <a-button type="primary" class="create-btn" @click="createOpen = true">
          <template #icon><PlusOutlined /></template>{{ t('auth_policy.create') }}
        </a-button>
      </template>
    </VnToolbar>

    <!-- Type filter, status metrics and search share one tool row. -->
    <div class="vn-metarow">
      <VnSegmented v-model="filterType" :options="typeOptions" :label="t('auth_policy.auth_type')" />
      <VnStatRail
        :items="statItems"
        :label="t('auth_policy.stats_scope')"
        @select="selectStat"
      />
      <a-input
        ref="searchInput"
        v-model:value="searchTerm"
        :aria-label="t('auth_policy.search_placeholder')"
        :placeholder="t('auth_policy.search_placeholder')"
        allow-clear
        class="vn-search"
      >
        <template #prefix><SearchOutlined /></template>
      </a-input>
      <span class="vn-filterbar-spacer" />
      <span class="vn-result-count" role="status">{{ t('auth_policy.results', { count: filteredPolicies.length }) }}</span>
      <a-button v-if="isFiltered" type="text" size="small" @click="resetFilters">
        {{ t('common.reset') }}
      </a-button>
    </div>

    <!-- Error -->
    <VnState
      v-if="loadFailed"
      :icon="ExclamationCircleOutlined"
      tone="err"
      :title="t('auth_policy.load_failed')"
      :hint="policiesRequest.error.value?.message"
    >
      <template #actions>
        <a-button type="primary" @click="refresh">
          <template #icon><ReloadOutlined /></template>{{ t('common.refresh') }}
        </a-button>
      </template>
    </VnState>

    <!-- Initial load -->
    <div v-else-if="policiesRequest.loading.value && policies.length === 0" class="vn-grid">
      <div v-for="n in 8" :key="n" class="vn-panel vn-skel-tile">
        <div class="vn-skel vn-skel-line" style="width: 58%" />
        <div class="vn-skel vn-skel-line" style="width: 38%" />
        <div class="vn-skel vn-skel-block" />
      </div>
    </div>

    <!-- Empty / filtered-empty -->
    <VnState
      v-else-if="filteredPolicies.length === 0"
      :icon="policies.length > 0 ? SearchOutlined : LockOutlined"
      :title="policies.length === 0 ? t('auth_policy.empty') : t('auth_policy.no_match')"
      :hint="policies.length === 0 ? t('auth_policy.create_description') : undefined"
    >
      <template #actions>
        <a-button v-if="policies.length === 0" type="primary" @click="createOpen = true">
          <template #icon><PlusOutlined /></template>{{ t('auth_policy.create') }}
        </a-button>
        <a-button v-else @click="resetFilters">{{ t('common.reset') }}</a-button>
      </template>
    </VnState>

    <!-- Catalog -->
    <template v-else>
      <div v-if="view === 'grid'" class="vn-grid">
        <AuthPolicyCard
          v-for="policy in visiblePolicies"
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
      <AuthPolicyTable
        v-else
        :rows="visiblePolicies"
        :bindings-of="policyCountFor"
        @edit="editingPolicy = $event"
        @bind="bindingPolicy = $event"
        @usage="usagePolicy = $event"
        @toggle="handleToggle"
        @delete="deletingPolicy = $event"
      />
      <a-pagination
        v-if="pageCount > 1"
        v-model:current="page"
        :page-size="pageSize"
        :total="filteredPolicies.length"
        :show-size-changer="false"
        class="vn-pagination"
      />
    </template>

    <!-- Modals kept mounted and driven through :open -->
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
      :open="!!bindingPolicy"
      :policy="bindingPolicy ?? undefined"
      :bindings="bindingPolicy ? policyCountFor(bindingPolicy) : []"
      :routes="routes"
      :on-add-bindings="handleAddBindings"
      :on-unbind="handleUnbind"
      :on-toggle-binding="handleToggleBinding"
      @close="bindingPolicy = null"
    />
    <UsageExampleModal
      :open="!!usagePolicy"
      :policy="usagePolicy ?? undefined"
      @close="usagePolicy = null"
    />
    <DeletePolicyModal
      :open="!!deletingPolicy"
      :policy="deletingPolicy ?? undefined"
      :binding-count="deletingPolicy ? policyCountFor(deletingPolicy).length : 0"
      :on-confirm="handleDelete"
      @cancel="deletingPolicy = null"
    />
  </div>
</template>