<script setup lang="ts">
/**
 * Route binding modal for one policy — lists current bindings (with
 * enable/disable + unbind) and adds new ones.
 * Endpoints: POST /api/auth/bindings, DELETE /api/auth/bindings/{id},
 * POST /api/auth/bindings/{id}/enable|disable.
 */
import { computed, ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import { CompassOutlined, LinkOutlined, PlusOutlined } from '@/components/icons'
import type { AuthPolicyDefinition, RouteAuthBinding } from '@/types/authPolicy'
import type { RouteResponse } from '@/types/route'

const props = defineProps<{
  policy?: AuthPolicyDefinition
  bindings: RouteAuthBinding[]
  routes: RouteResponse[]
  /** Kept mounted by the page and toggled here (unmounting an open antd modal
   *  leaks its dialog tree; see perf audit 2026-11-20). */
  open?: boolean
  /** Returns true when every requested binding succeeded (modal closes). */
  onAddBindings: (routeIds: string[], priority: number) => Promise<boolean>
  onUnbind: (binding: RouteAuthBinding) => void
  onToggleBinding: (binding: RouteAuthBinding) => void
}>()

const emit = defineEmits<{
  (e: 'close'): void
}>()

const { t } = useI18n()

const isOpen = computed(() => props.open !== false)
const selectedRouteIds = ref<string[]>([])
const priority = ref<number>(100)
const submitting = ref(false)

watch(isOpen, (open) => {
  if (!open) return
  selectedRouteIds.value = []
  priority.value = 100
  submitting.value = false
})

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
    :open="isOpen"
    :width="600"
    :footer="null"
    :style="{ top: '24px' }"
    wrap-class-name="vn-portal vn-modal"
    @cancel="emit('close')"
  >
    <template #title>
      <div class="vn-modal-head">
        <span class="vn-modal-head-glyph" aria-hidden="true"><LinkOutlined /></span>
        <div>
          <div class="vn-modal-title">{{ t('auth_policy.bind_routes') }}</div>
          <div class="vn-modal-subtitle">{{ policy?.policyName }}</div>
        </div>
      </div>
    </template>

    <div class="vn-modal-scroll">
    <div class="form-section-modern">
      <div class="section-header">
        <LinkOutlined class="section-icon" />
        <span class="section-title">{{ t('auth_policy.current_bindings') }}</span>
        <span class="auth-section-count">{{ bindings.length }}</span>
      </div>
      <div class="section-content">
        <div v-if="bindings.length > 0" class="auth-binding-list">
          <div v-for="binding in bindings" :key="binding.bindingId" class="auth-binding-row">
            <div class="auth-binding-info">
              <span class="vn-chip">
                <CompassOutlined />
                <span class="vn-route-chip-text">{{ getRouteName(binding.routeId) }}</span>
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

    <div class="form-section-modern">
      <div class="section-header">
        <PlusOutlined class="section-icon" />
        <span class="section-title">{{ t('auth_policy.add_binding') }}</span>
      </div>
      <div class="section-content">
        <div class="auth-binding-form">
          <div class="auth-field">
            <label class="auth-field-label">{{ t('auth_policy.select_routes') }}</label>
            <a-select
              v-model:value="selectedRouteIds"
              mode="multiple"
              show-search
              option-filter-prop="label"
              popup-class-name="vn-portal"
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
        <div class="modal-footer-modern">
          <a-button @click="emit('close')">{{ t('common.cancel') }}</a-button>
          <a-button type="primary" :loading="submitting" @click="handleSubmit">
            <template #icon><PlusOutlined /></template>
            {{ t('common.confirm') }}
          </a-button>
        </div>
      </div>
    </div>
    </div>
  </a-modal>
</template>