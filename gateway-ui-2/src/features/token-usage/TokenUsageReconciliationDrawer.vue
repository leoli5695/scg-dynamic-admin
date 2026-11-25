<script setup lang="ts">
/**
 * 计量详情与对账抽屉 — Vue port of TokenUsageReconciliationDrawer.tsx.
 * Lists the event snapshot, its related quota, the reconciliation command and
 * the verified-correction form (admin only). All API fields keep the React contract.
 */
import { computed, reactive, ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import type { Rule } from 'ant-design-vue/es/form'
import { useAuthStore } from '@/stores/auth'
import { useRequest } from '@/composables/useRequest'
import { modal } from '@/utils/feedback'
import {
  getTokenQuotaStatus,
  getTokenUsageReconciliation,
  reconcileTokenUsage,
  retryTokenUsageReconciliation,
} from '@/api/tokenUsage'
import type {
  TokenUsageCorrection, TokenUsageEvent, TokenUsageReconciliation,
} from '@/api/tokenUsage'
import { isUsageUnresolved, usageTokenValue } from './usagePresentation'

const props = defineProps<{
  event: TokenUsageEvent
  /** Kept mounted by the page and toggled here (unmounting an open antd drawer
   *  leaks its dialog tree; see perf audit 2026-11-20). */
  open?: boolean
}>()
const emit = defineEmits<{ close: []; applied: [] }>()

const { t, te } = useI18n()
const auth = useAuthStore()
const canWrite = computed(() => auth.user?.role === 'ADMIN')

interface ReconcileForm {
  promptTokens?: number
  completionTokens?: number
  totalTokens?: number
  chargedTokens?: number
  monthlyDelta?: number
  dailyDelta?: number
  reason: string
  verified: boolean
}

const hasPeriod = computed(() => !!props.event.quotaMonth && !!props.event.quotaDay)
const monthly = computed(
  () => hasPeriod.value && ['MONTHLY', 'BOTH'].includes((props.event.quotaPeriod ?? '').toUpperCase()),
)
const daily = computed(
  () => hasPeriod.value && ['DAILY', 'BOTH'].includes((props.event.quotaPeriod ?? '').toUpperCase()),
)

const isOpen = computed(() => props.open !== false)
const form = reactive<ReconcileForm>({
  promptTokens: props.event.usageStatus === 'UNKNOWN' ? undefined : (props.event.promptTokens ?? undefined),
  completionTokens: props.event.usageStatus === 'UNKNOWN' ? undefined : (props.event.completionTokens ?? undefined),
  totalTokens: props.event.usageStatus === 'UNKNOWN' ? undefined : (props.event.totalTokens ?? undefined),
  chargedTokens: props.event.chargedTokens ?? undefined,
  monthlyDelta: monthly.value ? undefined : 0,
  dailyDelta: daily.value ? undefined : 0,
  reason: '',
  verified: false,
})

function resetFormFromEvent() {
  const ev = props.event
  Object.assign(form, {
    promptTokens: ev.usageStatus === 'UNKNOWN' ? undefined : (ev.promptTokens ?? undefined),
    completionTokens: ev.usageStatus === 'UNKNOWN' ? undefined : (ev.completionTokens ?? undefined),
    totalTokens: ev.usageStatus === 'UNKNOWN' ? undefined : (ev.totalTokens ?? undefined),
    chargedTokens: ev.chargedTokens ?? undefined,
    monthlyDelta: monthly.value ? undefined : 0,
    dailyDelta: daily.value ? undefined : 0,
    reason: '',
    verified: false,
  })
}
watch(isOpen, (open) => { if (open) resetFormFromEvent() })

const rules = computed<Record<string, Rule[]>>(() => ({
  promptTokens: [{ required: true }],
  completionTokens: [{ required: true }],
  totalTokens: [{ required: true }],
  chargedTokens: [{ required: true }],
  monthlyDelta: [{ required: true }],
  dailyDelta: [{ required: true }],
  reason: [{ required: true, whitespace: true }],
  verified: [{
    validator: (_rule, value) =>
      (value ? Promise.resolve() : Promise.reject(new Error(t('tokenUsage.reconcile.verifyRequired')))),
  }],
}))

const errorText = ref('')
const saving = ref(false)
const applying = ref(false)
const formRef = ref()

const request = useRequest(
  (signal) => getTokenUsageReconciliation(props.event.id, { signal }),
  { deps: [() => props.event.id], onSuccess: (result) => syncApplied(result) },
)

// Only events that used a quota bucket have a related quota to show.
const hasQuotaContext = computed(() => !!props.event.tenantId
  && props.event.tenantId !== '__unassigned__'
  && !!props.event.quotaPeriod)
const quota = useRequest(
  (signal) => getTokenQuotaStatus(props.event.tenantId, { signal }),
  { immediate: hasQuotaContext.value },
)
watch(isOpen, (open) => {
  if (!open) return
  request.run()
  if (hasQuotaContext.value) quota.run()
})

const command = computed<TokenUsageReconciliation | undefined | null>(() => request.data.value)
// The applied command is the source of truth; the parent list drops reconciled rows,
// so falling back to the stale snapshot would show "unknown" next to "applied".
const event = computed<TokenUsageEvent>(() => {
  const applied = command.value
  if (applied?.status === 'APPLIED') {
    return {
      ...props.event,
      usageStatus: 'RECONCILED',
      settlementStatus: 'APPLIED',
      promptTokens: Number(applied.prompt_tokens),
      completionTokens: Number(applied.completion_tokens),
      totalTokens: Number(applied.total_tokens),
      chargedTokens: Number(applied.charged_tokens),
    }
  }
  return props.event
})

const unresolved = computed(() => isUsageUnresolved(event.value))
const ledgerPending = computed(() => !!event.value.quotaLedgerId && event.value.quotaLedgerState !== 'READY')
const meteringOnly = computed(() => event.value.settlementStatus === 'NOT_REQUIRED')

const drawerTitle = computed(() => t(
  unresolved.value ? 'tokenUsage.reconcile.title' : 'tokenUsage.details.title',
))

let syncedKey = ''
// GET after a lost response is just as authoritative as a successful POST.
function syncApplied(result: TokenUsageReconciliation | null | undefined) {
  if (result?.status !== 'APPLIED') return
  const key = `${props.event.id}:${result.id}`
  if (syncedKey === key) return
  syncedKey = key
  errorText.value = ''
  if (hasQuotaContext.value) quota.run()
  emit('applied')
}

const display = (value: unknown): string =>
  value === null || value === undefined || value === '' ? '—' : String(value)

const statusLabel = (status?: string | null): string => {
  const key = `tokenUsage.status.${status ?? 'LEGACY'}`
  return te(key) ? t(key) : (status ?? 'LEGACY')
}
const reconcileStatusLabel = (status: string): string => {
  const key = `tokenUsage.reconcile.${status}`
  return te(key) ? t(key) : status
}

const originalSnapshot = computed(() => {
  const raw = command.value?.original_usage
  if (!raw?.trim()) return null
  try {
    return JSON.stringify(JSON.parse(raw), null, 2)
  } catch {
    return raw
  }
})

const eventSnapshot = computed(() => JSON.stringify(event.value, null, 2))

async function validateForm(): Promise<TokenUsageCorrection | null> {
  try {
    await formRef.value?.validate()
    return {
      promptTokens: form.promptTokens!,
      completionTokens: form.completionTokens!,
      totalTokens: form.totalTokens!,
      chargedTokens: form.chargedTokens!,
      monthlyDelta: form.monthlyDelta!,
      dailyDelta: form.dailyDelta!,
      reason: form.reason,
    }
  } catch {
    return null
  }
}

function submit() {
  if (!canWrite.value || !unresolved.value || ledgerPending.value || command.value || saving.value) return
  void (async () => {
    const values = await validateForm()
    if (!values) return
    modal.confirm({
      title: t('tokenUsage.reconcile.confirmTitle'),
      content: t('tokenUsage.reconcile.confirmDeltas', {
        monthly: values.monthlyDelta,
        daily: values.dailyDelta,
      }),
      // Confirmation is opened from inside the 620px drawer; lift it above the drawer mask.
      zIndex: 1200,
      onOk: async () => {
        if (applying.value) return
        applying.value = true
        saving.value = true
        errorText.value = ''
        try {
          const result = await reconcileTokenUsage(event.value.id, values)
          request.mutate(result)
          syncApplied(result)
        } catch (failure) {
          errorText.value = failure instanceof Error ? failure.message : String(failure)
          // A response may be lost after intent was committed. Reload before allowing another submission.
          request.run()
        } finally {
          applying.value = false
          saving.value = false
        }
      },
    })
  })()
}

function retry() {
  const current = command.value
  if (!canWrite.value || !current || current.status === 'APPLIED' || saving.value) return
  modal.confirm({
    title: t('tokenUsage.reconcile.retryConfirmTitle'),
    content: t('tokenUsage.reconcile.confirmDeltas', {
      monthly: current.monthly_delta,
      daily: current.daily_delta,
    }),
    zIndex: 1200,
    onOk: async () => {
      if (applying.value) return
      applying.value = true
      saving.value = true
      errorText.value = ''
      try {
        const result = await retryTokenUsageReconciliation(event.value.id)
        request.mutate(result)
        syncApplied(result)
      } catch (failure) {
        errorText.value = failure instanceof Error ? failure.message : String(failure)
        request.run()
      } finally {
        applying.value = false
        saving.value = false
      }
    },
  })
}

</script>

<template>
  <a-drawer
    :open="isOpen"
    :width="620"
    :title="drawerTitle"
    root-class-name="vn-portal vnp-portal token-drawer"
    @close="emit('close')"
  >
    <div class="token-reconcile">
      <a-descriptions size="small" :column="1" class="token-descriptions">
        <a-descriptions-item :label="t('tokenUsage.table.time')">
          {{ display(event.requestTime?.replace('T', ' ')) }}
        </a-descriptions-item>
        <a-descriptions-item :label="t('tokenUsage.table.tenant')">
          {{ event.tenantId === '__unassigned__' ? t('tokenUsage.unassignedTenant') : event.tenantId }}
        </a-descriptions-item>
        <a-descriptions-item :label="t('tokenUsage.table.instance')">
          {{ display(event.instanceId) }}
        </a-descriptions-item>
        <a-descriptions-item :label="t('tokenUsage.table.route')">
          {{ display(event.routeId) }}
        </a-descriptions-item>
        <a-descriptions-item :label="t('tokenUsage.table.trace')">
          {{ display(event.traceId) }}
        </a-descriptions-item>
        <a-descriptions-item :label="t('tokenUsage.table.model')">
          {{ display(event.model) }}
        </a-descriptions-item>
        <a-descriptions-item :label="t('tokenUsage.table.usageStatus')">
          <span :class="['status-badge', unresolved ? 'status-badge-warning' : 'status-badge-neutral']">
            {{ statusLabel(event.usageStatus) }}
          </span>
        </a-descriptions-item>
        <a-descriptions-item :label="t('tokenUsage.table.settlementStatus')">
          {{ statusLabel(event.settlementStatus) }}
        </a-descriptions-item>
        <a-descriptions-item :label="t('tokenUsage.reconcile.measuredUsage')">
          {{ usageTokenValue(event, 'promptTokens') }} + {{ usageTokenValue(event, 'completionTokens') }} = {{ usageTokenValue(event, 'totalTokens') }}
        </a-descriptions-item>
      </a-descriptions>

      <a-spin v-if="quota.loading.value" :spinning="true" :tip="t('tokenUsage.reconcile.quotaLoading')">
        <div class="token-quota-loading" />
      </a-spin>
      <div v-else-if="quota.error.value" class="token-quota-error">
        <a-alert
          type="warning"
          show-icon
          :message="t('tokenUsage.reconcile.quotaError')"
          :description="quota.error.value.message"
        />
        <a-button size="small" @click="quota.run()">
          {{ t('tokenUsage.reconcile.refresh') }}
        </a-button>
      </div>
      <a-descriptions
        v-else-if="quota.data.value && (quota.data.value.monthlyQuota !== undefined || quota.data.value.dailyQuota !== undefined)"
        size="small"
        :column="1"
        :title="t('tokenUsage.reconcile.quotaTitle')"
        class="token-drawer-section token-descriptions"
      >
        <a-descriptions-item
          v-if="quota.data.value.monthlyQuota !== undefined"
          :label="t('tokenUsage.reconcile.quotaMonthly')"
        >
          {{ quota.data.value.monthlyUsed ?? 0 }} / {{ quota.data.value.monthlyQuota }}
        </a-descriptions-item>
        <a-descriptions-item
          v-if="quota.data.value.dailyQuota !== undefined"
          :label="t('tokenUsage.reconcile.quotaDaily')"
        >
          {{ quota.data.value.dailyUsed ?? 0 }} / {{ quota.data.value.dailyQuota }}
        </a-descriptions-item>
      </a-descriptions>

      <details class="token-details token-drawer-section">
        <summary>{{ t('tokenUsage.details.advanced') }}</summary>
        <a-descriptions size="small" :column="1" class="token-descriptions">
          <a-descriptions-item :label="t('tokenUsage.table.format')">
            {{ display(event.responseFormat) }}
          </a-descriptions-item>
          <a-descriptions-item :label="t('tokenUsage.table.service')">
            {{ display(event.serviceId) }}
          </a-descriptions-item>
          <a-descriptions-item :label="t('tokenUsage.table.latency')">
            {{ display(event.latencyMs) }}
          </a-descriptions-item>
          <a-descriptions-item :label="t('tokenUsage.table.reserved')">
            {{ display(event.reservedTokens) }}
          </a-descriptions-item>
          <a-descriptions-item :label="t('tokenUsage.table.charged')">
            {{ display(event.chargedTokens) }}
          </a-descriptions-item>
          <a-descriptions-item :label="t('tokenUsage.reconcile.period')">
            {{ display(event.quotaMonth) }} / {{ display(event.quotaDay) }}
          </a-descriptions-item>
        </a-descriptions>
      </details>

      <a-alert v-if="errorText" class="token-drawer-section" type="error" show-icon :message="errorText" />

      <a-spin v-if="request.loading.value" :spinning="true" class="token-command-spin" />

      <div v-else-if="request.error.value" class="token-drawer-section token-quota-error">
        <a-alert type="error" show-icon :message="request.error.value.message" />
        <a-button size="small" @click="request.run()">
          {{ t('tokenUsage.reconcile.refresh') }}
        </a-button>
      </div>

      <div v-else-if="command" class="token-command token-drawer-section">
        <a-alert
          :type="command.status === 'APPLIED' ? 'success' : 'warning'"
          show-icon
          :message="reconcileStatusLabel(command.status)"
          :description="command.last_error"
        />
        <a-descriptions size="small" :column="1" class="token-descriptions">
          <a-descriptions-item :label="t('tokenUsage.reconcile.reference')">
            {{ command.id }}
          </a-descriptions-item>
          <a-descriptions-item :label="t('tokenUsage.reconcile.operator')">
            {{ command.operator }}
          </a-descriptions-item>
          <a-descriptions-item :label="t('tokenUsage.reconcile.reason')">
            {{ command.reason }}
          </a-descriptions-item>
          <a-descriptions-item :label="t('tokenUsage.reconcile.verifiedUsage')">
            {{ display(command.prompt_tokens) }} + {{ display(command.completion_tokens) }} = {{ display(command.total_tokens) }}
          </a-descriptions-item>
          <a-descriptions-item :label="t('tokenUsage.reconcile.charged')">
            {{ command.charged_tokens }}
          </a-descriptions-item>
          <a-descriptions-item :label="t('tokenUsage.reconcile.monthlyDelta')">
            {{ command.monthly_delta }}
          </a-descriptions-item>
          <a-descriptions-item :label="t('tokenUsage.reconcile.dailyDelta')">
            {{ command.daily_delta }}
          </a-descriptions-item>
        </a-descriptions>
        <a-button v-if="canWrite && command.status !== 'APPLIED'" :loading="saving" @click="retry">
          {{ t('tokenUsage.reconcile.retry') }}
        </a-button>
        <details v-if="originalSnapshot" class="token-details">
          <summary>{{ t('tokenUsage.reconcile.original') }}</summary>
          <pre class="token-pre">{{ originalSnapshot }}</pre>
        </details>
        <a-alert
          v-else
          type="info"
          :message="t('tokenUsage.reconcile.noSnapshot')"
        />
      </div>

      <a-alert
        v-else-if="unresolved && ledgerPending"
        class="token-drawer-section"
        type="info"
        :message="t('tokenUsage.reconcile.ledgerPending')"
      />

      <a-form
        v-else-if="unresolved && canWrite"
        ref="formRef"
        layout="vertical"
        :model="form"
        :rules="rules"
        class="token-reconcile-form token-drawer-section"
      >
        <a-alert
          type="warning"
          show-icon
          :message="t('tokenUsage.reconcile.instructions')"
          class="token-form-alert"
        />
        <a-alert
          v-if="!monthly && !daily"
          type="info"
          :message="t('tokenUsage.reconcile.noPeriod')"
          class="token-form-alert"
        />
        <a-form-item name="promptTokens" :label="t('tokenUsage.reconcile.promptTokens')">
          <a-input-number v-model:value="form.promptTokens" :min="0" :max="2147483647" :precision="0" class="token-number-input" />
        </a-form-item>
        <a-form-item name="completionTokens" :label="t('tokenUsage.reconcile.completionTokens')">
          <a-input-number v-model:value="form.completionTokens" :min="0" :max="2147483647" :precision="0" class="token-number-input" />
        </a-form-item>
        <a-form-item name="totalTokens" :label="t('tokenUsage.reconcile.totalTokens')">
          <a-input-number v-model:value="form.totalTokens" :min="0" :max="2147483647" :precision="0" class="token-number-input" />
        </a-form-item>
        <a-form-item name="chargedTokens" :label="t('tokenUsage.reconcile.chargedTokens')">
          <a-input-number v-model:value="form.chargedTokens" :min="0" :max="2147483647" :precision="0" class="token-number-input" />
        </a-form-item>
        <a-form-item name="monthlyDelta" :label="t('tokenUsage.reconcile.monthlyDelta')">
          <a-input-number
            v-model:value="form.monthlyDelta"
            :disabled="!monthly"
            :min="-2147483647"
            :max="2147483647"
            :precision="0"
            class="token-number-input"
          />
        </a-form-item>
        <a-form-item name="dailyDelta" :label="t('tokenUsage.reconcile.dailyDelta')">
          <a-input-number
            v-model:value="form.dailyDelta"
            :disabled="!daily"
            :min="-2147483647"
            :max="2147483647"
            :precision="0"
            class="token-number-input"
          />
        </a-form-item>
        <a-form-item name="reason" :label="t('tokenUsage.reconcile.reason')">
          <a-textarea v-model:value="form.reason" :maxlength="1000" show-count :rows="3" />
        </a-form-item>
        <a-form-item name="verified">
          <a-checkbox v-model:checked="form.verified">
            {{ t('tokenUsage.reconcile.verified') }}
          </a-checkbox>
        </a-form-item>
        <a-button type="primary" :loading="saving" @click="submit">
          {{ t('tokenUsage.reconcile.submit') }}
        </a-button>
      </a-form>

      <a-alert
        v-else-if="unresolved"
        class="token-drawer-section"
        type="info"
        :message="t('tokenUsage.reconcile.unavailable')"
      />

      <a-alert
        v-else-if="meteringOnly"
        class="token-drawer-section"
        type="info"
        :message="t('tokenUsage.reconcile.meteringOnly')"
      />

      <details class="token-details token-drawer-section">
        <summary>{{ t('tokenUsage.reconcile.currentData') }}</summary>
        <pre class="token-pre">{{ eventSnapshot }}</pre>
      </details>
    </div>
  </a-drawer>
</template>