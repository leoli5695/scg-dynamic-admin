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
import './token-revocation.css'

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
    return { tone: 'status-badge-error', dot: 'status-dot-error', text: t('common.error') }
  }
  return checkEnabled.value
    ? { tone: 'status-badge-success', dot: 'status-dot-success', text: t('jwt_revocation.switch_on') }
    : { tone: 'status-badge-neutral', dot: 'status-dot-neutral', text: t('jwt_revocation.switch_off') }
})

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

function copyId(id: string) {
  navigator.clipboard.writeText(id)
  message.success(t('message.copy_success'))
}
</script>

<template>
  <div class="token-revocation-page">
    <div class="page-header-modern tr-page-header">
      <div class="page-header-left">
        <h2 class="page-title-main tr-page-title">
          <span class="tr-page-glyph" aria-hidden="true"><StopOutlined /></span>
          <span class="tr-heading">
            <span class="tr-page-name">{{ t('jwt_revocation.title') }}</span>
            <span class="tr-page-desc">{{ t('jwt_revocation.description') }}</span>
          </span>
        </h2>
      </div>
    </div>

    <div class="tr-note">
      <span class="tr-note-glyph" aria-hidden="true"><InfoCircleOutlined /></span>
      <div class="tr-note-body">
        <span class="tr-note-title">{{ t('jwt_revocation.scope_note') }}</span>
        <p class="tr-note-desc">{{ t('jwt_revocation.redis_note') }}</p>
      </div>
    </div>

    <div v-if="!checkEnabled" class="tr-note tr-note-warning">
      <span class="tr-note-glyph" aria-hidden="true"><WarningOutlined /></span>
      <div class="tr-note-body">
        <span class="tr-note-title">{{ t('jwt_revocation.switch_off') }}</span>
        <p class="tr-note-desc">{{ t('jwt_revocation.switch_desc') }}</p>
      </div>
    </div>

    <!-- Runtime switch -->
    <a-card :bordered="false" class="tr-card">
      <div class="tr-card-head">
        <span class="tr-card-glyph" aria-hidden="true"><ControlOutlined /></span>
        <div class="tr-card-heading">
          <div class="tr-card-title-row">
            <h3 class="tr-card-title">{{ t('jwt_revocation.switch_title') }}</h3>
            <span
              class="status-badge tr-switch-badge"
              :class="switchBadge.tone"
            >
              <span class="status-dot" :class="switchBadge.dot" />
              {{ switchBadge.text }}
            </span>
          </div>
          <p class="tr-card-desc">{{ t('jwt_revocation.switch_desc') }}</p>
        </div>
        <div class="tr-switch-control">
          <a-switch
            :checked="checkEnabled"
            :loading="switchLoading || switching"
            @change="handleToggle"
          />
        </div>
      </div>
      <div v-if="switchError" class="tr-inline-error">
        <ExclamationCircleOutlined aria-hidden="true" />
        <span class="tr-inline-error-text">
          {{ t('jwt_revocation.switch_load_failed') }} · {{ switchError.message }}
        </span>
        <a-button type="text" size="small" class="tr-inline-retry" @click="runSwitch">
          {{ t('strategy.retry') }}
        </a-button>
      </div>
    </a-card>

    <!-- Revoke / lift form -->
    <a-card :bordered="false" class="tr-card">
      <div class="tr-card-head">
        <span class="tr-card-glyph" aria-hidden="true"><ThunderboltOutlined /></span>
        <div class="tr-card-heading">
          <h3 class="tr-card-title">{{ t('jwt_revocation.form_title') }}</h3>
        </div>
      </div>

      <div class="tr-mode-block">
        <span class="tr-field-label">{{ t('jwt_revocation.mode_label') }}</span>
        <a-radio-group v-model:value="mode" class="tr-segmented">
          <a-radio-button value="token">
            <SafetyCertificateOutlined class="tr-mode-icon" />
            {{ t('jwt_revocation.mode_token') }}
          </a-radio-button>
          <a-radio-button value="jti">
            <BarcodeOutlined class="tr-mode-icon" />
            {{ t('jwt_revocation.mode_jti') }}
          </a-radio-button>
        </a-radio-group>
      </div>

      <a-form layout="vertical" class="tr-form">
        <div class="tr-form-grid">
          <a-form-item
            class="tr-field-target"
            :label="mode === 'token'
              ? t('jwt_revocation.token_label')
              : t('jwt_revocation.jti_label')"
          >
            <a-textarea
              v-if="mode === 'token'"
              v-model:value="tokenInput"
              class="tr-token-input"
              :rows="3"
              :placeholder="t('jwt_revocation.token_placeholder')"
            />
            <a-input
              v-else
              v-model:value="jtiInput"
              class="tr-jti-input"
              allow-clear
              :placeholder="t('jwt_revocation.jti_placeholder')"
            />
          </a-form-item>

          <a-form-item class="tr-field-ttl" :label="t('jwt_revocation.ttl_label')">
            <a-input-number
              v-model:value="ttlSeconds"
              class="tr-ttl-input"
              :min="60"
              :max="604800"
              :precision="0"
              :disabled="mode === 'jti'"
              :placeholder="mode === 'token' ? t('jwt_revocation.ttl_placeholder') : undefined"
            />
            <div v-if="ttlSeconds !== null" class="tr-field-hint">{{ t('jwt_revocation.ttl_placeholder') }}</div>
          </a-form-item>

          <div class="tr-actions-cell">
            <div class="tr-form-actions">
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
          </div>
        </div>
      </a-form>
    </a-card>

    <!-- Last operation -->
    <a-card :bordered="false" class="tr-card tr-card-result">
      <div class="tr-card-head">
        <span class="tr-card-glyph" aria-hidden="true"><HistoryOutlined /></span>
        <div class="tr-card-heading">
          <h3 class="tr-card-title">{{ t('jwt_revocation.result_title') }}</h3>
        </div>
      </div>

      <div v-if="lastError" class="tr-result-error" role="alert">
        <span class="tr-result-error-glyph" aria-hidden="true"><ExclamationCircleOutlined /></span>
        <div class="tr-result-error-body">
          <span class="tr-result-error-title">{{ t('jwt_revocation.action_failed') }}</span>
          <span class="tr-result-error-text">{{ lastError }}</span>
        </div>
      </div>

      <div v-else-if="lastOperation" class="tr-result">
        <div class="tr-result-top">
          <span
            class="status-badge"
            :class="lastOperation.kind === 'revoke' ? 'status-badge-error' : 'status-badge-success'"
          >
            <span
              class="status-dot"
              :class="lastOperation.kind === 'revoke' ? 'status-dot-error' : 'status-dot-success'"
            />
            {{ lastOperation.kind === 'revoke'
              ? t('jwt_revocation.revoke')
              : t('jwt_revocation.lift') }}
          </span>
          <span
            v-if="lastOperation.kind === 'revoke'"
            class="status-badge status-badge-neutral"
          >
            {{ t('jwt_revocation.result_ttl_value', { ttl: lastOperation.data.ttlSeconds }) }}
          </span>
        </div>

        <div class="tr-result-section">
          <span class="tr-section-label">{{ t('jwt_revocation.result_ids') }}</span>
          <div class="tr-id-list">
            <div v-for="id in lastOperation.data.ids" :key="id" class="tr-id-line">
              <code class="tr-id-code">{{ id }}</code>
              <a-tooltip :title="t('common.copy')">
                <a-button type="text" size="small" class="tr-copy-btn" @click="copyId(id)">
                  <template #icon><CopyOutlined /></template>
                </a-button>
              </a-tooltip>
            </div>
          </div>
        </div>

        <div v-if="lastOperation.kind === 'revoke'" class="tr-result-meta">
          <span class="tr-section-label">{{ t('jwt_revocation.result_ttl') }}</span>
          <span class="tr-result-value">
            {{ t('jwt_revocation.result_ttl_value', { ttl: lastOperation.data.ttlSeconds }) }}
          </span>
        </div>
      </div>

      <div v-else class="tr-result-empty">
        <HistoryOutlined class="tr-empty-icon" aria-hidden="true" />
        <span>{{ t('jwt_revocation.result_empty') }}</span>
      </div>
    </a-card>

    <!-- Confirmation dialog (kept mounted; unmounting an open antd modal leaks it) -->
    <a-modal
      wrap-class-name="tr-confirm-modal"
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
        <div class="tr-confirm-head">
          <span
            class="tr-confirm-glyph"
            :class="confirmKind === 'revoke' ? 'is-danger' : 'is-lift'"
            aria-hidden="true"
          >
            <StopOutlined v-if="confirmKind === 'revoke'" />
            <RollbackOutlined v-else />
          </span>
          <span class="tr-confirm-title">
            {{ confirmKind === 'revoke'
              ? t('jwt_revocation.revoke_confirm_title')
              : t('jwt_revocation.lift_confirm_title') }}
          </span>
        </div>
      </template>
      <p class="tr-confirm-desc">
        {{ confirmKind === 'revoke'
          ? t('jwt_revocation.revoke_confirm_desc')
          : t('jwt_revocation.lift_confirm_desc') }}
      </p>
      <div class="tr-confirm-meta">
        <span class="tr-chip">
          {{ mode === 'token' ? t('jwt_revocation.mode_token') : t('jwt_revocation.mode_jti') }}
        </span>
        <span v-if="confirmKind === 'revoke' && mode === 'token' && ttlSeconds" class="tr-chip">
          {{ t('jwt_revocation.result_ttl_value', { ttl: ttlSeconds }) }}
        </span>
      </div>
      <code class="tr-confirm-target">{{ targetPreview }}</code>
    </a-modal>
  </div>
</template>