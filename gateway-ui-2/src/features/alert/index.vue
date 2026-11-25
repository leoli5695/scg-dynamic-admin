<script setup lang="ts">
/**
 * Alerts tab (/instances/:instanceId/alerts) — Vue port of
 * gateway-ui/src/features/alert/index.tsx.
 * Email recipients + alert level/language, five threshold editors with live
 * readings, and the server-paginated alert history with clear/refresh.
 * Visual language follows the login skin (2026-11-20): transparent canvas,
 * login panels, periwinkle→violet accents and capsule badges.
 */
import { computed, reactive, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import {
  BellOutlined, ExclamationCircleOutlined, ExperimentOutlined, InboxOutlined, PlusOutlined, ReloadOutlined,
} from '@/components/icons'
import { useRequest } from '@/composables/useRequest'
import {
  clearAlertHistory,
  getAlertConfig,
  getAlertHistory,
  getAlertThresholdMetrics,
  saveAlertConfig,
  sendTestEmail,
  type AlertConfigData,
  type AlertConfigSaveRequest,
} from '@/api/alerts'
import { message } from '@/utils/feedback'
import { useInstanceDetail } from '@/features/instance-detail/context'
import {
  defaultThresholdState,
  EMAIL_PATTERN,
  mergeThresholdConfig,
  type AlertFormState,
} from './utils'
import CurrentMetricsCard from './components/CurrentMetricsCard.vue'
import ThresholdCards from './components/ThresholdCards.vue'
import HistoryTable from './components/HistoryTable.vue'
import './alert.css'

const { t } = useI18n()
const detail = useInstanceDetail()

const instanceId = computed(() => detail.instance.instanceId)

const formState = reactive<AlertFormState>({
  emailLanguage: 'zh',
  enabled: true,
  ...defaultThresholdState(),
})

const emails = ref<string[]>([])
const emailInput = ref('')
const saving = ref(false)

const languageOptions = computed(() => [
  { value: 'zh', label: t('alert.chinese') },
  { value: 'en', label: t('alert.english') },
])

function applyConfig(data: AlertConfigData): void {
  emails.value = [...(data.emailRecipients ?? [])]
  formState.emailLanguage = data.emailLanguage || 'zh'
  formState.enabled = data.enabled ?? true
  Object.assign(formState, mergeThresholdConfig(data.thresholdConfig))
}

const configRequest = useRequest(
  (signal) => getAlertConfig({ instanceId: instanceId.value }, { signal }),
  { deps: [instanceId], onSuccess: applyConfig },
)
const config = computed(() => configRequest.data.value)

// Alert history is server-paginated: the backend returns one page at a time, so
// page/size must be sent explicitly (client-side slicing would hide older alerts).
const historyPage = ref(0)
const historyPageSize = ref(10)
const historyRequest = useRequest(
  (signal) => getAlertHistory(
    { instanceId: instanceId.value, page: historyPage.value, size: historyPageSize.value },
    { signal },
  ),
  { deps: [instanceId, historyPage, historyPageSize] },
)
const history = computed(() => historyRequest.data.value?.content ?? [])
const historyTotal = computed(() => historyRequest.data.value?.totalElements ?? 0)

const metricsRequest = useRequest((signal) => getAlertThresholdMetrics({ signal }))
const metrics = computed(() => metricsRequest.data.value)

function handleHistoryPageChange(page: number, pageSize: number): void {
  historyPage.value = page
  historyPageSize.value = pageSize
}

function buildSaveBody(emailRecipients: string[]): AlertConfigSaveRequest {
  return {
    id: config.value?.id,
    instanceId: instanceId.value,
    configName: config.value?.configName || 'Default Alert Config',
    emailRecipients,
    emailLanguage: formState.emailLanguage,
    enabled: formState.enabled,
    thresholdConfig: mergeThresholdConfig(config.value?.thresholdConfig),
  }
}

/** Persist just the recipient list (used by add/remove auto-save). */
async function saveEmailRecipients(emailList: string[]): Promise<void> {
  try {
    await saveAlertConfig(buildSaveBody(emailList))
    message.success(t('alert.save_success'))
  } catch (e) {
    message.error(e instanceof Error ? e.message : t('alert.save_failed'))
  }
}

async function handleAddEmail(): Promise<void> {
  const email = emailInput.value.trim()
  if (!email) return
  if (!EMAIL_PATTERN.test(email)) {
    message.error(t('alert.invalid_email'))
    return
  }
  if (emails.value.includes(email)) {
    message.warning(t('alert.email_exists'))
    return
  }
  emails.value = [...emails.value, email]
  emailInput.value = ''
  await saveEmailRecipients(emails.value)
}

async function handleRemoveEmail(email: string): Promise<void> {
  emails.value = emails.value.filter((item) => item !== email)
  await saveEmailRecipients(emails.value)
}

async function handleSave(): Promise<void> {
  saving.value = true
  try {
    await saveAlertConfig({
      ...buildSaveBody(emails.value),
      emailLanguage: formState.emailLanguage,
      enabled: formState.enabled,
      thresholdConfig: mergeThresholdConfig({
        cpu: formState.cpu,
        memory: formState.memory,
        http: formState.http,
        instance: formState.instance,
        thread: formState.thread,
      }),
    })
    message.success(t('alert.save_success'))
    configRequest.run()
  } catch (e) {
    message.error(e instanceof Error ? e.message : t('alert.save_failed'))
  } finally {
    saving.value = false
  }
}

async function handleTestEmail(): Promise<void> {
  if (emails.value.length === 0) {
    message.warning(t('alert.no_recipients'))
    return
  }
  try {
    await sendTestEmail({ recipients: emails.value, language: formState.emailLanguage })
    message.success(t('alert.test_email_sent'))
  } catch (e) {
    message.error(e instanceof Error ? e.message : t('alert.test_email_failed'))
  }
}

async function handleClearHistory(): Promise<void> {
  try {
    await clearAlertHistory({ instanceId: instanceId.value })
    message.success(t('alert.clear_history_success'))
    // Jump back to the first page; if already there, re-run to refresh in place
    // (avoids a double fetch from a no-op state change plus run()).
    if (historyPage.value !== 0) {
      historyPage.value = 0
    } else {
      historyRequest.run()
    }
  } catch (e) {
    message.error(e instanceof Error ? e.message : t('alert.clear_history_failed'))
  }
}
</script>

<template>
  <div class="alert-page">
    <div class="page-header-modern alert-page-header">
      <div class="page-header-left">
        <h3 class="page-title-main has-glyph">
          <span class="page-header-glyph" aria-hidden="true"><BellOutlined /></span>
          {{ t('alert.title') }}
        </h3>
        <span class="page-subtitle">{{ t('alert.subtitle') }}</span>
      </div>
      <div class="page-header-right">
        <a-popconfirm
          :title="t('alert.test_email_confirm')"
          :ok-text="t('common.confirm')"
          :cancel-text="t('common.cancel')"
          @confirm="handleTestEmail"
        >
          <a-button :disabled="emails.length === 0">
            <template #icon><ExperimentOutlined /></template>
            {{ t('alert.test_email') }}
          </a-button>
        </a-popconfirm>
      </div>
    </div>

    <div v-if="configRequest.loading.value && !config" class="alert-loading">
      <a-spin size="large" />
    </div>

    <section v-else-if="configRequest.error.value && !config" class="lg-panel alert-state-panel">
      <span class="alert-state-icon is-error"><ExclamationCircleOutlined /></span>
      <h2 class="alert-state-title">{{ t('alert.load_failed') }}</h2>
      <p class="alert-state-hint" :title="configRequest.error.value.message">
        {{ configRequest.error.value.message }} — {{ t('alert.load_failed_hint') }}
      </p>
      <a-button type="primary" @click="configRequest.run()">
        <template #icon><ReloadOutlined /></template>
        {{ t('common.refresh') }}
      </a-button>
    </section>

    <template v-else>
      <div class="alert-settings-grid">
        <section class="lg-panel alert-panel">
          <header class="alert-panel-head">
            <span class="alert-panel-title"><InboxOutlined /> {{ t('alert.email_settings') }}</span>
          </header>
          <div class="alert-panel-body">
            <div class="alert-field">
              <span class="alert-field-label">{{ t('alert.email_recipients') }}</span>
              <a-space-compact block>
                <a-input
                  v-model:value="emailInput"
                  :placeholder="t('alert.email_placeholder')"
                  :aria-label="t('alert.email_placeholder')"
                  @press-enter="handleAddEmail"
                />
                <a-button type="primary" @click="handleAddEmail">
                  <template #icon><PlusOutlined /></template>
                  {{ t('common.add') }}
                </a-button>
              </a-space-compact>
            </div>

            <div class="alert-email-list">
              <a-tag
                v-for="email in emails"
                :key="email"
                closable
                class="alert-email-pill"
                @close="handleRemoveEmail(email)"
              >
                {{ email }}
              </a-tag>
              <span v-if="emails.length === 0" class="alert-empty-hint">{{ t('alert.no_emails') }}</span>
            </div>

            <div class="alert-field-row">
              <label class="alert-field">
                <span class="alert-field-label">{{ t('alert.email_language') }}</span>
                <a-select
                  v-model:value="formState.emailLanguage"
                  class="alert-language-select"
                  :options="languageOptions"
                  :aria-label="t('alert.email_language')"
                />
              </label>
              <label class="alert-field alert-field-switch">
                <span class="alert-field-label">{{ t('alert.enabled') }}</span>
                <a-switch
                  v-model:checked="formState.enabled"
                  :checked-children="t('common.enabled')"
                  :un-checked-children="t('common.disabled')"
                />
              </label>
            </div>

            <p class="alert-panel-hint">{{ t('alert.recipients_hint') }}</p>
          </div>
        </section>

        <CurrentMetricsCard
          :metrics="metrics"
          :loading="metricsRequest.loading.value"
          :error="metricsRequest.error.value?.message"
          @retry="metricsRequest.run()"
        />
      </div>

      <ThresholdCards :state="formState" :metrics="metrics" />

      <div class="alert-save-row">
        <span class="alert-panel-hint">{{ t('alert.threshold_hint') }}</span>
        <a-button type="primary" :loading="saving" @click="handleSave">
          {{ t('alert.save_config') }}
        </a-button>
      </div>

      <HistoryTable
        :history="history"
        :loading="historyRequest.loading.value"
        :total="historyTotal"
        :page="historyPage"
        :page-size="historyPageSize"
        :error="historyRequest.error.value?.message"
        @page-change="handleHistoryPageChange"
        @clear="handleClearHistory"
        @refresh="historyRequest.run()"
      />
    </template>
  </div>
</template>