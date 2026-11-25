<script setup lang="ts">
/**
 * Auth policy card — one credential policy in the grid with its bound routes.
 * Visual contract follows the login-aligned console skin (gradient panel,
 * capsule status/type pills, hairline footer with icon actions).
 */
import { computed } from 'vue'
import { useI18n } from 'vue-i18n'
import {
  ApiOutlined, AuditOutlined, CompassOutlined, CopyOutlined, DeleteOutlined, EditOutlined,
  KeyOutlined, LinkOutlined, PauseCircleOutlined, PlayCircleOutlined, QuestionCircleOutlined,
  SafetyCertificateOutlined, UserOutlined,
} from '@/components/icons'
import type { AuthPolicyDefinition, RouteAuthBinding } from '@/types/authPolicy'
import { message } from '@/utils/feedback'
import { authToneClass, getAuthTypeDescKey, getAuthTypeLabel } from '../utils'

const props = defineProps<{
  policy: AuthPolicyDefinition
  bindings: RouteAuthBinding[]
  routeName: (routeId: string) => string
}>()

const emit = defineEmits<{
  (e: 'edit', policy: AuthPolicyDefinition): void
  (e: 'bind', policy: AuthPolicyDefinition): void
  (e: 'usage', policy: AuthPolicyDefinition): void
  (e: 'toggle', policy: AuthPolicyDefinition): void
  (e: 'delete', policy: AuthPolicyDefinition): void
}>()

const { t } = useI18n()

const TYPE_ICONS = {
  JWT: KeyOutlined,
  API_KEY: ApiOutlined,
  OAUTH2: SafetyCertificateOutlined,
  BASIC: UserOutlined,
  HMAC: AuditOutlined,
} as const

const toneClass = computed(() => authToneClass(props.policy.authType))
const typeIcon = computed(() => TYPE_ICONS[props.policy.authType] ?? KeyOutlined)
const typeLabel = computed(() => getAuthTypeLabel(props.policy.authType, t))
const typeDescKey = computed(() => getAuthTypeDescKey(props.policy.authType))
const statusText = computed(() => (props.policy.enabled ? t('common.enabled') : t('common.disabled')))
const visibleBindings = computed(() => props.bindings.slice(0, 3))
const overflowCount = computed(() => Math.max(props.bindings.length - 3, 0))

function copyId() {
  if (!props.policy.policyId) return
  navigator.clipboard.writeText(props.policy.policyId)
  message.success(t('message.copy_success'))
}
</script>

<template>
  <a-card
    :bordered="false"
    :class="['auth-policy-card', !policy.enabled ? 'is-disabled' : '']"
  >
    <div class="auth-card-top">
      <span :class="['auth-card-glyph', 'icon-tile', toneClass]">
        <component :is="typeIcon" />
      </span>
      <div class="auth-card-headline">
        <a-tooltip :title="policy.policyName">
          <span class="auth-card-name">{{ policy.policyName }}</span>
        </a-tooltip>
        <span class="auth-card-id-line">
          <a-tooltip :title="policy.policyId">
            <code class="auth-card-id">{{ policy.policyId }}</code>
          </a-tooltip>
          <a-tooltip :title="t('common.copy_id')">
            <a-button type="text" size="small" class="auth-mini-btn" @click.stop="copyId">
              <template #icon><CopyOutlined /></template>
            </a-button>
          </a-tooltip>
        </span>
      </div>
      <span
        class="status-badge auth-status"
        :class="policy.enabled ? 'status-badge-success' : 'status-badge-neutral'"
      >
        <span :class="['status-dot', policy.enabled ? 'status-dot-success' : 'status-dot-neutral']" />
        {{ statusText }}
      </span>
    </div>

    <div class="auth-card-tags">
      <span :class="['auth-type-pill', toneClass]">{{ typeLabel }}</span>
      <a-tooltip v-if="typeDescKey" :title="t(typeDescKey)">
        <QuestionCircleOutlined class="auth-type-help" />
      </a-tooltip>
    </div>

    <div class="auth-card-routes">
      <div class="auth-section-label">
        {{ t('auth_policy.bound_routes') }}
        <span class="auth-count">{{ bindings.length }}</span>
      </div>
      <div v-if="bindings.length > 0" class="auth-route-chips">
        <a-tooltip v-for="binding in visibleBindings" :key="binding.bindingId" :title="binding.routeId">
          <span class="auth-route-chip">
            <CompassOutlined />
            <span class="auth-route-name">{{ routeName(binding.routeId) }}</span>
          </span>
        </a-tooltip>
        <span v-if="overflowCount > 0" class="auth-more-chip">+{{ overflowCount }}</span>
      </div>
      <span v-else class="auth-no-bindings">{{ t('auth_policy.no_bindings') }}</span>
    </div>

    <div v-if="policy.description" class="auth-card-description">
      <a-tooltip :title="policy.description">
        <span class="auth-description-text">{{ policy.description }}</span>
      </a-tooltip>
    </div>

    <div class="auth-card-actions">
      <a-tooltip :title="t('common.edit')">
        <a-button type="text" size="small" class="auth-icon-btn" @click="emit('edit', policy)">
          <template #icon><EditOutlined /></template>
        </a-button>
      </a-tooltip>
      <a-tooltip :title="t('auth_policy.usage_example')">
        <a-button type="text" size="small" class="auth-icon-btn" @click="emit('usage', policy)">
          <template #icon><QuestionCircleOutlined /></template>
        </a-button>
      </a-tooltip>
      <a-tooltip :title="t('auth_policy.bind_routes')">
        <a-button type="text" size="small" class="auth-icon-btn" @click="emit('bind', policy)">
          <template #icon><LinkOutlined /></template>
        </a-button>
      </a-tooltip>
      <a-tooltip :title="policy.enabled ? t('common.disable') : t('common.enable')">
        <a-button type="text" size="small" class="auth-icon-btn" @click="emit('toggle', policy)">
          <template #icon><PauseCircleOutlined v-if="policy.enabled" /><PlayCircleOutlined v-else /></template>
        </a-button>
      </a-tooltip>
      <a-tooltip :title="t('common.delete')">
        <a-button type="text" size="small" danger class="auth-icon-btn delete" @click="emit('delete', policy)">
          <template #icon><DeleteOutlined /></template>
        </a-button>
      </a-tooltip>
    </div>
  </a-card>
</template>