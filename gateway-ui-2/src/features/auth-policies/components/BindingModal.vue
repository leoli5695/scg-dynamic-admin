<script setup lang="ts">
/**
 * Route binding modal for one policy — lists current bindings (with
 * enable/disable + unbind) and adds new ones.
 * Endpoints: POST /api/auth/bindings, DELETE /api/auth/bindings/{id},
 * POST /api/auth/bindings/{id}/enable|disable.
 */
import { computed, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import { CompassOutlined, LinkOutlined, PlusOutlined } from '@/components/icons'
import type { AuthPolicyDefinition, RouteAuthBinding } from '@/types/authPolicy'
import type { RouteResponse } from '@/types/route'

const props = defineProps<{
  policy: AuthPolicyDefinition
  bindings: RouteAuthBinding[]
  routes: RouteResponse[]
  /** Returns true when every requested binding succeeded (modal closes). */
  onAddBindings: (routeIds: string[], priority: number) => Promise<boolean>
  onUnbind: (binding: RouteAuthBinding) => void
  onToggleBinding: (binding: RouteAuthBinding) => void
}>()

const emit = defineEmits<{
  (e: 'close'): void
}>()

const { t } = useI18n()

const selectedRouteIds = ref<string[]>([])
const priority = ref<number>(100)
const submitting = ref(false)

const boundRouteIds = computed(() => props.bindings.map((binding) => binding.routeId))
const availableRoutes = computed(() =>
  props.routes.filter((route) => !boundRouteIds.value.includes(route.id)))
const routeOptions = computed(() =>
  availableRoutes.value.map((route) => ({ value: route.id, label: route.routeName || route.id })))
const emptyHint = computed(() =>
  props.routes.length > 0 && availableRoutes.value.length === 0
    ? t('auth_policy.all_routes_bound')
    : t('auth_policy.no_routes_available'))

function getRouteName(routeId: string): string {
  return props.routes.find((route) => route.id === routeId)?.routeName
    || routeId.substring(0, 12)
    || 'Unknown'
}

async function handleSubmit() {
  const routeIds = selectedRouteIds.value
  // No new routes: user may have only unbound/toggled — just close.
  if (routeIds.length === 0) {
    emit('close')
    return
  }
  submitting.value = true
  try {
    const allOk = await props.onAddBindings(routeIds, priority.value ?? 100)
    if (allOk) emit('close')
  } finally {
    submitting.value = false
  }
}
</script>

<template>
  <a-modal
    :open="true"
    :width="620"
    :footer="null"
    wrap-class-name="auth-policy-binding-modal"
    destroy-on-close
    @cancel="emit('close')"
  >
    <template #title>
      <div class="auth-modal-head">
        <span class="auth-modal-glyph" aria-hidden="true"><LinkOutlined /></span>
        <span class="auth-modal-heading">
          <span class="auth-modal-title">{{ t('auth_policy.bind_routes') }}</span>
          <span class="auth-modal-subtitle">{{ policy.policyName }}</span>
        </span>
      </div>
    </template>

    <div class="form-section">
      <div class="form-section-header">
        <LinkOutlined class="form-section-icon" />
        <span class="form-section-title">{{ t('auth_policy.current_bindings') }}</span>
        <span class="auth-count auth-section-count">{{ bindings.length }}</span>
      </div>
      <div class="form-section-content">
        <div v-if="bindings.length > 0" class="auth-binding-list">
          <div v-for="binding in bindings" :key="binding.bindingId" class="auth-binding-row">
            <div class="auth-binding-info">
              <span class="auth-route-chip">
                <CompassOutlined />
                <span class="auth-route-name">{{ getRouteName(binding.routeId) }}</span>
              </span>
              <span class="auth-binding-priority">
                {{ t('auth_policy.binding_priority') }}: {{ binding.priority ?? 100 }}
              </span>
            </div>
            <div class="auth-binding-actions">
              <a-switch
                size="small"
                :checked="binding.enabled !== false"
                @change="props.onToggleBinding(binding)"
              />
              <a-button size="small" type="text" danger class="auth-unbind-btn" @click="props.onUnbind(binding)">
                {{ t('auth_policy.unbind') }}
              </a-button>
            </div>
          </div>
        </div>
        <div v-else class="auth-binding-empty">
          <span class="auth-binding-empty-glyph" aria-hidden="true"><LinkOutlined /></span>
          <span>{{ t('auth_policy.no_bindings') }}</span>
        </div>
      </div>
    </div>

    <div class="form-section">
      <div class="form-section-header">
        <PlusOutlined class="form-section-icon" />
        <span class="form-section-title">{{ t('auth_policy.add_binding') }}</span>
      </div>
      <div class="form-section-content">
        <div class="auth-binding-form">
          <div class="auth-field">
            <label class="auth-field-label">{{ t('auth_policy.select_routes') }}</label>
            <a-select
              v-model:value="selectedRouteIds"
              mode="multiple"
              show-search
              option-filter-prop="label"
              :placeholder="t('auth_policy.select_routes_placeholder')"
              :options="routeOptions"
              style="width: 100%"
            >
              <template #notFoundContent>
                <span class="auth-select-empty">{{ emptyHint }}</span>
              </template>
            </a-select>
          </div>
          <div class="auth-field auth-field-priority">
            <label class="auth-field-label">{{ t('auth_policy.binding_priority') }}</label>
            <a-input-number v-model:value="priority" :min="1" :max="1000" style="width: 100%" />
          </div>
        </div>
        <div class="auth-modal-footer">
          <a-button @click="emit('close')">{{ t('common.cancel') }}</a-button>
          <a-button type="primary" :loading="submitting" @click="handleSubmit">
            <template #icon><PlusOutlined /></template>
            {{ t('common.confirm') }}
          </a-button>
        </div>
      </div>
    </div>
  </a-modal>
</template>