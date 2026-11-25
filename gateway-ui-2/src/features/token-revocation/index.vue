<script setup lang="ts">
/**
 * Data-plane JWT revocation page.
 * Ported from gateway-ui/src/features/token-revocation/index.tsx; endpoints in
 * controller/GatewayTokenRevocationController.java (/api/gateway/jwt-revocations).
 *
 * The blacklist lives in the Redis shared by the whole gateway deployment, so
 * actions here are not instance-scoped even though the page sits inside the
 * instance console - the copy says so explicitly.
 */
import { computed, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import {
  BarcodeOutlined,
  ControlOutlined,
  CopyOutlined,
  ExclamationCircleOutlined,
  HistoryOutlined,
  InfoCircleOutlined,
  RollbackOutlined,
  SafetyCertificateOutlined,
  StopOutlined,
  ThunderboltOutlined,
  WarningOutlined,
} from '@/components/icons'
import { useRequest } from '@/composables/useRequest'
import { ApiError } from '@/api/http'
import {
  getRevocationSwitch, liftJwtRevocation, revokeJwtToken, setRevocationSwitch,
} from '@/api/jwtRevocation'
import type { RevocationRequest, RevocationResult } from '@/api/jwtRevocation'
import { message } from '@/utils/feedback'
import { copyText } from '@/utils/clipboard'
import VnBadge from '@/components/vn/VnBadge.vue'
import VnIconButton from '@/components/vn/VnIconButton.vue'
import VnPanel from '@/components/vn/VnPanel.vue'
import VnSegmented, { type VnSegmentOption } from '@/components/vn/VnSegmented.vue'
import VnToolbar from '@/components/vn/VnToolbar.vue'
import '@/styles/vue-native.css'
import '@/styles/vue-native-shared.css'
import './token-revocation-vue-native.css'

// Named so the shell's KeepAlive can cache it.
defineOptions({ name: 'TokenRevocationPage' })

type TargetMode = 'token' | 'jti'
type ActionKind = 'revoke' | 'lift'

interface LastOperation {
  kind: ActionKind
  data: RevocationResult
}

const { t } = useI18n()

const mode = ref<TargetMode>('token')
const tokenInput = ref('')
const jtiInput = ref('')
const ttlSeconds = ref<number | null>(null)
const submitting = ref(false)
const switching = ref(false)
const confirmKind = ref<ActionKind | null>(null)
const confirmOpen = ref(false)
const lastOperation = ref<LastOperation | null>(null)
const lastError = ref<string | null>(null)

const errorMessage = (error: unknown, fallbackKey: string): string =>
  (error instanceof ApiError && error.message ? error.message : t(fallbackKey))

const {
  data: switchData,
  loading: switchLoading,
  error: switchError,
  run: runSwitch,
  mutate: mutateSwitch,
} = useRequest((signal) => getRevocationSwitch({ signal }), {
  onError: (error) => {
    message.error(errorMessage(error, 'jwt_revocation.switch_load_failed'))
  },
})

// Absent switch state is shown as enabled, matching the gateway default.
const checkEnabled = computed(() => switchData.value?.enabled ?? true)

// When the authoritative state could not be read, do not present a green badge.
const switchBadge = computed(() => {
  if (switchError.value && !switchData.value) {
    return { tone: 'err' as const, text: t('common.error') }
  }
  return checkEnabled.value
    ? { tone: 'ok' as const, text: t('jwt_revocation.switch_on') }
    : { tone: 'idle' as const, text: t('jwt_revocation.switch_off') }
})

const modeOptions = computed<VnSegmentOption<TargetMode>[]>(() => [
  { key: 'token', label: t('jwt_revocation.mode_token'), icon: SafetyCertificateOutlined },
  { key: 'jti', label: t('jwt_revocation.mode_jti'), icon: BarcodeOutlined },
])

const target = computed(() => (mode.value === 'token' ? tokenInput.value.trim() : jtiInput.value.trim()))

/** Tokens are long; the confirm dialog only needs a recognizable excerpt. */
const targetPreview = computed(() => {
  const value = target.value
  if (mode.value === 'jti' || value.length <= 28) return value
  return `${value.slice(0, 16)}…${value.slice(-8)}`
})

async function handleToggle(next: string | number | boolean) {
  if (switching.value || switchLoading.value) return
  const enabled = Boolean(next)
  switching.value = true
  try {
    await setRevocationSwitch(enabled)
    // Apply the acknowledged value immediately, then re-read the authoritative state.
    mutateSwitch({ enabled })
    message.success(t('jwt_revocation.switch_updated'))
    runSwitch()
  } catch (error) {
    message.error(errorMessage(error, 'jwt_revocation.switch_update_failed'))
  } finally {
    switching.value = false
  }
}

function requestConfirm(kind: ActionKind) {
  if (!target.value) {
    message.warning(t('jwt_revocation.input_required'))
    return
  }
  // Light structural guard: business JWTs are compact serializations (header.payload.signature).
  if (mode.value === 'token' && !/^\S+\.\S+\.\S+/.test(target.value)) {
    message.warning(t('jwt_revocation.token_placeholder'))
    return
  }
  confirmKind.value = kind
  confirmOpen.value = true
}

function closeConfirm() {
  if (!submitting.value) confirmOpen.value = false
}

async function runAction() {
  const kind = confirmKind.value
  if (!kind) return
  const value = target.value
  if (!value) {
    confirmOpen.value = false
    message.warning(t('jwt_revocation.input_required'))
    return
  }
  submitting.value = true
  lastError.value = null
  try {
    const body: RevocationRequest = mode.value === 'token' ? { token: value } : { jti: value }
    // TTL only applies to the token form; the field is disabled in jti mode.
    if (kind === 'revoke' && mode.value === 'token' && ttlSeconds.value) {
      body.ttlSeconds = ttlSeconds.value
    }
    const data = kind === 'revoke'
      ? await revokeJwtToken(body)
      : await liftJwtRevocation(body)
    lastOperation.value = { kind, data }
    confirmOpen.value = false
    message.success(t(kind === 'revoke'
      ? 'jwt_revocation.revoke_success'
      : 'jwt_revocation.lift_success'))
  } catch (error) {
    // Keep the dialog open so the operator can retry or cancel after reading the reason;
    // the result card also keeps the failure visible after closing it.
    lastError.value = errorMessage(error, 'jwt_revocation.action_failed')
    message.error(lastError.value)
  } finally {
    submitting.value = false
  }
}

async function copyId(id: string) {
  const ok = await copyText(id)
  if (ok) message.success(t('message.copy_success'))
  else message.error(t('message.copy_failed'))
}
</script>

<template>
  <div class="token-revocation-page vn-revocation vn-scope">
    <VnToolbar
      :title="t('jwt_revocation.title')"
      :lede="t('jwt_revocation.description')"
    />

    <div class="vn-rev-stack">
      <div class="vn-note">
        <span class="vn-note-glyph" aria-hidden="true"><InfoCircleOutlined /></span>
        <div class="vn-note-body">
          <span class="vn-note-title">{{ t('jwt_revocation.scope_note') }}</span>
          <p class="vn-note-desc">{{ t('jwt_revocation.redis_note') }}</p>
        </div>
      </div>

      <div v-if="!checkEnabled" class="vn-note vn-note-warning">
        <span class="vn-note-glyph" aria-hidden="true"><WarningOutlined /></span>
        <div class="vn-note-body">
          <span class="vn-note-title">{{ t('jwt_revocation.switch_off') }}</span>
          <p class="vn-note-desc">{{ t('jwt_revocation.switch_desc') }}</p>
        </div>
      </div>

      <!-- Runtime switch -->
      <VnPanel>
        <div class="vn-rev-head">
          <span class="vn-rev-glyph" aria-hidden="true"><ControlOutlined /></span>
          <div class="vn-rev-heading">
            <div class="vn-rev-title-row">
              <h3 class="vn-rev-title">{{ t('jwt_revocation.switch_title') }}</h3>
              <VnBadge :tone="switchBadge.tone">{{ switchBadge.text }}</VnBadge>
            </div>
            <p class="vn-rev-desc">{{ t('jwt_revocation.switch_desc') }}</p>
          </div>
          <div class="vn-rev-control">
            <a-switch
              :checked="checkEnabled"
              :loading="switchLoading || switching"
              @change="handleToggle"
            />
          </div>
        </div>
        <div v-if="switchError" class="vn-inline-error">
          <ExclamationCircleOutlined aria-hidden="true" />
          <span class="vn-inline-error-text">
            {{ t('jwt_revocation.switch_load_failed') }} · {{ switchError.message }}
          </span>
          <a-button type="text" size="small" class="vn-inline-retry" @click="runSwitch">
            {{ t('strategy.retry') }}
          </a-button>
        </div>
      </VnPanel>

      <!-- Revoke / lift form -->
      <VnPanel>
        <div class="vn-rev-head">
          <span class="vn-rev-glyph" aria-hidden="true"><ThunderboltOutlined /></span>
          <div class="vn-rev-heading">
            <h3 class="vn-rev-title">{{ t('jwt_revocation.form_title') }}</h3>
          </div>
        </div>

        <div class="vn-mode-block">
          <span class="vn-field-label">{{ t('jwt_revocation.mode_label') }}</span>
          <VnSegmented v-model="mode" :options="modeOptions" :label="t('jwt_revocation.mode_label')" />
        </div>

        <a-form layout="vertical">
          <div class="vn-form-grid">
            <a-form-item
              :label="mode === 'token'
                ? t('jwt_revocation.token_label')
                : t('jwt_revocation.jti_label')"
            >
              <a-textarea
                v-if="mode === 'token'"
                v-model:value="tokenInput"
                :rows="3"
                :placeholder="t('jwt_revocation.token_placeholder')"
              />
              <a-input
                v-else
                v-model:value="jtiInput"
                allow-clear
                :placeholder="t('jwt_revocation.jti_placeholder')"
              />
            </a-form-item>

            <a-form-item :label="t('jwt_revocation.ttl_label')">
              <a-input-number
                v-model:value="ttlSeconds"
                :min="60"
                :max="604800"
                :precision="0"
                :disabled="mode === 'jti'"
                style="width: 100%"
                :placeholder="mode === 'token' ? t('jwt_revocation.ttl_placeholder') : undefined"
              />
              <div v-if="ttlSeconds !== null" class="vn-field-hint">{{ t('jwt_revocation.ttl_placeholder') }}</div>
            </a-form-item>
          </div>
          <div class="vn-form-actions">
            <a-button
              type="primary"
              danger
              :loading="submitting"
              @click="requestConfirm('revoke')"
            >
              <template #icon><StopOutlined /></template>
              {{ t('jwt_revocation.revoke') }}
            </a-button>
            <a-button :loading="submitting" @click="requestConfirm('lift')">
              <template #icon><RollbackOutlined /></template>
              {{ t('jwt_revocation.lift') }}
            </a-button>
          </div>
        </a-form>
      </VnPanel>

      <!-- Last operation -->
      <VnPanel>
        <div class="vn-rev-head">
          <span class="vn-rev-glyph" aria-hidden="true"><HistoryOutlined /></span>
          <div class="vn-rev-heading">
            <h3 class="vn-rev-title">{{ t('jwt_revocation.result_title') }}</h3>
          </div>
        </div>

        <div v-if="lastError" class="vn-result-error" role="alert">
          <span class="vn-result-error-glyph" aria-hidden="true"><ExclamationCircleOutlined /></span>
          <div class="vn-result-error-body">
            <span class="vn-result-error-title">{{ t('jwt_revocation.action_failed') }}</span>
            <span class="vn-result-error-text">{{ lastError }}</span>
          </div>
        </div>

        <div v-else-if="lastOperation">
          <div class="vn-result-top">
            <VnBadge :tone="lastOperation.kind === 'revoke' ? 'err' : 'ok'">
              {{ lastOperation.kind === 'revoke'
                ? t('jwt_revocation.revoke')
                : t('jwt_revocation.lift') }}
            </VnBadge>
            <span v-if="lastOperation.kind === 'revoke'" class="vn-chip">
              {{ t('jwt_revocation.result_ttl_value', { ttl: lastOperation.data.ttlSeconds }) }}
            </span>
          </div>

          <div class="vn-result-section">
            <span class="vn-section-label">{{ t('jwt_revocation.result_ids') }}</span>
            <div class="vn-id-list">
              <div v-for="id in lastOperation.data.ids" :key="id" class="vn-id-line">
                <code class="vn-id-code" :title="id">{{ id }}</code>
                <VnIconButton :icon="CopyOutlined" :label="t('common.copy')" inline @click="copyId(id)" />
              </div>
            </div>
          </div>

          <div v-if="lastOperation.kind === 'revoke'" class="vn-result-section">
            <span class="vn-section-label">{{ t('jwt_revocation.result_ttl') }}</span>
            <span class="vn-result-value">
              {{ t('jwt_revocation.result_ttl_value', { ttl: lastOperation.data.ttlSeconds }) }}
            </span>
          </div>
        </div>

        <div v-else class="vn-result-empty">
          <HistoryOutlined class="vn-empty-icon" aria-hidden="true" />
          <span>{{ t('jwt_revocation.result_empty') }}</span>
        </div>
      </VnPanel>
    </div>

    <!-- Confirmation dialog (kept mounted; unmounting an open antd modal leaks it) -->
    <a-modal
      wrap-class-name="vn-portal tr-confirm-modal"
      :open="confirmOpen"
      :width="480"
      :ok-text="confirmKind === 'revoke'
        ? t('jwt_revocation.revoke')
        : t('jwt_revocation.lift')"
      :cancel-text="t('common.cancel')"
      :ok-button-props="confirmKind === 'revoke' ? { danger: true } : {}"
      :confirm-loading="submitting"
      :mask-closable="!submitting"
      :closable="!submitting"
      :keyboard="!submitting"
      @ok="runAction"
      @cancel="closeConfirm"
    >
      <template #title>
        <div class="vn-modal-head">
          <span class="vn-modal-head-glyph" aria-hidden="true">
            <StopOutlined v-if="confirmKind === 'revoke'" />
            <RollbackOutlined v-else />
          </span>
          <div class="vn-modal-title">
            {{ confirmKind === 'revoke'
              ? t('jwt_revocation.revoke_confirm_title')
              : t('jwt_revocation.lift_confirm_title') }}
          </div>
        </div>
      </template>
      <p class="vn-confirm-desc">
        {{ confirmKind === 'revoke'
          ? t('jwt_revocation.revoke_confirm_desc')
          : t('jwt_revocation.lift_confirm_desc') }}
      </p>
      <div class="vn-confirm-meta">
        <span class="vn-chip">
          {{ mode === 'token' ? t('jwt_revocation.mode_token') : t('jwt_revocation.mode_jti') }}
        </span>
        <span v-if="confirmKind === 'revoke' && mode === 'token' && ttlSeconds" class="vn-chip">
          {{ t('jwt_revocation.result_ttl_value', { ttl: ttlSeconds }) }}
        </span>
      </div>
      <code class="vn-confirm-target">{{ targetPreview }}</code>
    </a-modal>
  </div>
</template>