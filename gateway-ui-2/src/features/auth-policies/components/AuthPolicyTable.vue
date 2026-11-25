<script setup lang="ts">
import { useI18n } from 'vue-i18n'
import {
  ApiOutlined, AuditOutlined, CopyOutlined, DeleteOutlined, EditOutlined, KeyOutlined,
  LinkOutlined, PauseCircleOutlined, PlayCircleOutlined, QuestionCircleOutlined,
  SafetyCertificateOutlined, UserOutlined,
} from '@/components/icons'
import type { AuthPolicyDefinition, RouteAuthBinding } from '@/types/authPolicy'
import { message } from '@/utils/feedback'
import { copyText } from '@/utils/clipboard'
import VnBadge from '@/components/vn/VnBadge.vue'
import VnIconButton from '@/components/vn/VnIconButton.vue'
import { getAuthTypeLabel, vnAuthToneClass } from '../utils'

defineProps<{
  rows: AuthPolicyDefinition[]
  bindingsOf: (policy: AuthPolicyDefinition) => RouteAuthBinding[]
}>()
const emit = defineEmits<{
  (e: 'edit', p: AuthPolicyDefinition): void
  (e: 'bind', p: AuthPolicyDefinition): void
  (e: 'usage', p: AuthPolicyDefinition): void
  (e: 'toggle', p: AuthPolicyDefinition): void
  (e: 'delete', p: AuthPolicyDefinition): void
}>()

const { t } = useI18n()

const TYPE_ICONS = {
  JWT: KeyOutlined,
  API_KEY: ApiOutlined,
  OAUTH2: SafetyCertificateOutlined,
  BASIC: UserOutlined,
  HMAC: AuditOutlined,
} as const

const typeIcon = (type: string) => TYPE_ICONS[type as keyof typeof TYPE_ICONS] ?? KeyOutlined

async function copyId(p: AuthPolicyDefinition) {
  if (!p.policyId) return
  const ok = await copyText(p.policyId)
  if (ok) message.success(t('message.copy_success'))
  else message.error(t('message.copy_failed'))
}
</script>

<template>
  <div class="vn-panel vn-panel-flush vn-tablewrap">
    <table class="vn-table">
      <thead>
        <tr>
          <th>{{ t('auth_policy.policy_name') }}</th>
          <th>{{ t('auth_policy.auth_type') }}</th>
          <th>{{ t('common.status') }}</th>
          <th>{{ t('auth_policy.bound_routes') }}</th>
          <th class="vn-table-actions-head">{{ t('common.actions') }}</th>
        </tr>
      </thead>
      <tbody>
        <tr v-for="p in rows" :key="p.policyId" class="vn-row">
          <td>
            <div class="vn-row-identity">
              <span :class="['vn-row-glyph', vnAuthToneClass(p.authType)]" aria-hidden="true">
                <component :is="typeIcon(p.authType)" />
              </span>
              <div class="vn-row-names">
                <button type="button" class="vn-tile-name" :title="p.policyName" @click="emit('edit', p)">{{ p.policyName }}</button>
                <span class="vn-mono vn-row-id">{{ p.policyId }}</span>
              </div>
            </div>
          </td>
          <td>
            <span :class="['vn-chip', vnAuthToneClass(p.authType)]">{{ getAuthTypeLabel(p.authType, t) }}</span>
          </td>
          <td>
            <VnBadge :tone="p.enabled ? 'ok' : 'idle'">{{ p.enabled ? t('common.enabled') : t('common.disabled') }}</VnBadge>
          </td>
          <td><span class="vn-chip"><span class="vn-chip-num">{{ bindingsOf(p).length }}</span></span></td>
          <td>
            <div class="vn-iconbtn-group">
              <VnIconButton
                :icon="p.enabled ? PauseCircleOutlined : PlayCircleOutlined"
                :label="p.enabled ? t('common.disable') : t('common.enable')"
                @click="emit('toggle', p)"
              />
              <VnIconButton :icon="EditOutlined" :label="t('common.edit')" @click="emit('edit', p)" />
              <VnIconButton :icon="LinkOutlined" :label="t('auth_policy.bind_routes')" @click="emit('bind', p)" />
              <VnIconButton :icon="QuestionCircleOutlined" :label="t('auth_policy.usage_example')" @click="emit('usage', p)" />
              <VnIconButton :icon="CopyOutlined" :label="t('common.copy_id')" inline @click="copyId(p)" />
              <VnIconButton :icon="DeleteOutlined" :label="t('common.delete')" danger @click="emit('delete', p)" />
            </div>
          </td>
        </tr>
      </tbody>
    </table>
  </div>
</template>