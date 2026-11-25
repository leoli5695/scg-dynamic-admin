<script setup lang="ts">
/**
 * Delete confirmation for one auth policy — replaces the popconfirm so the
 * destructive step reads like the rest of the login-aligned modal family:
 * warning glyph, target preview and an explicit consequences note.
 */
import { computed, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import { DeleteOutlined, WarningOutlined } from '@/components/icons'
import type { AuthPolicyDefinition } from '@/types/authPolicy'

const props = defineProps<{
  policy: AuthPolicyDefinition
  bindingCount: number
  /** Resolves true when the policy was deleted (the modal then closes). */
  onConfirm: (policy: AuthPolicyDefinition) => Promise<boolean>
}>()

const emit = defineEmits<{
  (e: 'cancel'): void
}>()

const { t } = useI18n()
const busy = ref(false)

const typeLabel = computed(() => props.policy.authType)

async function confirm() {
  if (busy.value) return
  busy.value = true
  try {
    const ok = await props.onConfirm(props.policy)
    if (ok) emit('cancel')
  } finally {
    busy.value = false
  }
}
</script>

<template>
  <a-modal
    :open="true"
    :width="440"
    :footer="null"
    wrap-class-name="auth-policy-delete-modal"
    :closable="false"
    :keyboard="!busy"
    :mask-closable="!busy"
    destroy-on-close
    @cancel="emit('cancel')"
  >
    <div class="auth-confirm">
      <span class="auth-confirm-glyph" aria-hidden="true"><WarningOutlined /></span>
      <h3 class="auth-confirm-title">{{ t('auth_policy.delete_confirm_title') }}</h3>
      <p class="auth-confirm-desc">{{ t('auth_policy.delete_confirm_content') }}</p>

      <div class="auth-confirm-target">
        <span class="auth-confirm-name">{{ policy.policyName }}</span>
        <span class="auth-confirm-meta">
          <span class="auth-type-pill">{{ typeLabel }}</span>
          <span class="auth-count">{{ bindingCount }}</span>
          <span class="auth-confirm-meta-label">{{ t('auth_policy.bound_routes') }}</span>
        </span>
        <code v-if="policy.policyId" class="auth-confirm-id">{{ policy.policyId }}</code>
      </div>

      <div class="auth-confirm-actions">
        <a-button :disabled="busy" @click="emit('cancel')">{{ t('common.cancel') }}</a-button>
        <a-button type="primary" danger :loading="busy" @click="confirm">
          <template #icon><DeleteOutlined /></template>
          {{ t('common.delete') }}
        </a-button>
      </div>
    </div>
  </a-modal>
</template>