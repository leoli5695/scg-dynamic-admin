<script setup lang="ts">
/**
 * Auth policy tile — one credential policy in the grid with its bound routes.
 * Fixed bands keep every card in a row aligned; the +N overflow marker lives
 * in the band head so it is never clipped away.
 */
import { computed } from 'vue'
import { useI18n } from 'vue-i18n'
import {
  ApiOutlined, AuditOutlined, CompassOutlined, CopyOutlined, EditOutlined,
  KeyOutlined, PauseCircleOutlined, PlayCircleOutlined,
  SafetyCertificateOutlined, UserOutlined,
} from '@/components/icons'
import type { AuthPolicyDefinition, RouteAuthBinding } from '@/types/authPolicy'
import { message } from '@/utils/feedback'
import { copyText } from '@/utils/clipboard'
import VnBadge from '@/components/vn/VnBadge.vue'
import VnIconButton from '@/components/vn/VnIconButton.vue'
import { getAuthTypeLabel, vnAuthToneClass } from '../utils'
import AuthPolicyActionsMenu from './AuthPolicyActionsMenu.vue'

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

const toneClass = computed(() => vnAuthToneClass(props.policy.authType))
const typeIcon = computed(() => TYPE_ICONS[props.policy.authType] ?? KeyOutlined)
const typeLabel = computed(() => getAuthTypeLabel(props.policy.authType, t))

const MAX_BINDINGS = 3
const visibleBindings = computed(() => props.bindings.slice(0, MAX_BINDINGS))
const overflowCount = computed(() => Math.max(props.bindings.length - MAX_BINDINGS, 0))

const popupContainer = () => document.body

async function copyId() {
  if (!props.policy.policyId) return
  const ok = await copyText(props.policy.policyId)
  if (ok) message.success(t('message.copy_success'))
  else message.error(t('message.copy_failed'))
}
</script>

<template>
  <article :class="['vn-panel', 'vn-tile', 'vn-rail-' + (policy.enabled ? 'ok' : 'idle')]">
    <div class="vn-tile-head">
      <div class="vn-tile-identity">
        <span :class="['vn-tile-glyph', toneClass]" aria-hidden="true">
          <component :is="typeIcon" />
        </span>
        <button type="button" class="vn-tile-name" :title="policy.policyName" @click="emit('edit', policy)">
          {{ policy.policyName }}
        </button>
      </div>
      <div class="vn-iconbtn-group">
        <VnIconButton
          :icon="policy.enabled ? PauseCircleOutlined : PlayCircleOutlined"
          :label="policy.enabled ? t('common.disable') : t('common.enable')"
          @click="emit('toggle', policy)"
        />
        <VnIconButton :icon="EditOutlined" :label="t('common.edit')" @click="emit('edit', policy)" />
        <AuthPolicyActionsMenu
          :policy-id="policy.policyId"
          @bind="emit('bind', policy)"
          @usage="emit('usage', policy)"
          @delete="emit('delete', policy)"
        />
      </div>
    </div>

    <div class="vn-tile-meta">
      <VnBadge :tone="policy.enabled ? 'ok' : 'idle'">{{ policy.enabled ? t('common.enabled') : t('common.disabled') }}</VnBadge>
      <span :class="['vn-chip', toneClass]">{{ typeLabel }}</span>
    </div>

    <div class="vn-auth-idrow">
      <a-tooltip :title="policy.policyId" overlay-class-name="vn-tooltip">
        <span class="vn-mono vn-auth-id">{{ policy.policyId }}</span>
      </a-tooltip>
      <VnIconButton :icon="CopyOutlined" :label="t('common.copy_id')" inline @click="copyId" />
    </div>

    <div class="vn-tile-band">
      <span class="vn-band-label">
        <CompassOutlined /> {{ t('auth_policy.bound_routes') }}
        <span class="vn-band-count">{{ bindings.length }}</span>
      </span>
      <span v-if="overflowCount > 0" class="vn-band-more">+{{ overflowCount }}</span>
      <span v-if="bindings.length > 0" class="vn-chips">
        <a-tooltip
          v-for="binding in visibleBindings"
          :key="binding.bindingId"
          :title="binding.routeId"
          overlay-class-name="vn-tooltip"
          :get-popup-container="popupContainer"
        >
          <span class="vn-chip">
            <span class="vn-route-chip-text">{{ routeName(binding.routeId) }}</span>
          </span>
        </a-tooltip>
      </span>
      <span v-else class="vn-tile-empty">{{ t('auth_policy.no_bindings') }}</span>
    </div>
  </article>
</template>