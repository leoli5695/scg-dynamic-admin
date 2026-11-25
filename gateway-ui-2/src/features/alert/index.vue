<script setup lang="ts">
/**
 * Alerts tab (/instances/:instanceId/alerts) on the Vue-native design layer.
 * Email recipients + alert level/language, five threshold editors with live
 * readings, and the server-paginated alert history with clear/refresh.
 * API calls and payloads are unchanged.
 */
import { computed, reactive, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import {
  ExclamationCircleOutlined, ExperimentOutlined, HistoryOutlined,
  InboxOutlined, PlusOutlined, ReloadOutlined, SettingOutlined,
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
import VnIconButton from '@/components/vn/VnIconButton.vue'
import VnStatRail, { type VnStatItem } from '@/components/vn/VnStatRail.vue'
import VnState from '@/components/vn/VnState.vue'
import VnToolbar from '@/components/vn/VnToolbar.vue'
import {
  defaultThresholdState,
  EMAIL_PATTERN,
  evaluateThresholds,
  mergeThresholdConfig,
  type AlertFormState,
} from './utils'
import CurrentMetricsCard from './components/CurrentMetricsCard.vue'
import ThresholdCards from './components/ThresholdCards.vue'
import HistoryTable from './components/HistoryTable.vue'
import '@/styles/vue-native.css'
import '@/styles/vn-pages.css'
import './alert-vue-native.css'

defineOptions({ name: 'AlertsPage' })

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

const thresholdStatus = computed(() => evaluateThresholds(formState, metrics.value))
const enabledCount = computed(() =>
  [formState.cpu, formState.memory, formState.http, formState.instance, formState.thread]
    .filter((g) => g.enabled).length)
const overCount = computed(() => Object.values(thresholdStatus.value).filter(Boolean).length)

const statItems = computed<VnStatItem[]>(() => [
  { key: 'recipients', value: emails.value.length, label: t('alert.email_recipients'), icon: InboxOutlined },
  { key: 'enabled', value: enabledCount.value, label: t('alert.threshold_config'), icon: SettingOutlined, accent: 'var(--vn-ok)' },
  { key: 'over', value: overCount.value, label: t('alert.over_threshold'), icon: ExclamationCircleOutlined, accent: overCount.value > 0 ? 'var(--vn-warn)' : undefined },
  { key: 'history', value: historyTotal.value, label: t('alert.history'), icon: HistoryOutlined, accent: 'var(--vn-info)' },
])

function refreshAll() {
  configRequest.run()
  metricsRequest.run()
  historyRequest.run()
}

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
  <div class="alert-page vn-alert vn-scope vnp-scope">
    <VnToolbar :title="t('alert.title')" :lede="t('alert.subtitle')">
      <template #actions>
        <VnIconButton :icon="ReloadOutlined" :label="t('common.refresh')" @click="refreshAll" />
        <a-popconfirm
          :title="t('alert.test_email_confirm')"
          :ok-text="t('common.confirm')"
          :cancel-text="t('common.cancel')"
          overlay-class-name="vn-portal vnp-portal"
          @confirm="handleTestEmail"
        >
          <a-button :disabled="emails.length === 0">
            <template #icon><ExperimentOutlined /></template>{{ t('alert.test_email') }}
          </a-button>
        </a-popconfirm>
      </template>
    </VnToolbar>

    <div class="vn-metarow">
      <VnStatRail :items="statItems" :label="t('alert.title')" />
      <span class="vn-filterbar-spacer" />
      <span class="vn-result-count" role="status">{{ t('alert.items_total', { count: historyTotal }) }}</span>
    </div>

    <div v-if="configRequest.loading.value && !config" class="vn-alert-loading">
      <a-spin size="large" />
    </div>

    <VnState
      v-else-if="configRequest.error.value && !config"
      :icon="ExclamationCircleOutlined"
      tone="err"
      :title="t('alert.load_failed')"
      :hint="`${configRequest.error.value.message} — ${t('alert.load_failed_hint')}`"
    >
      <template #actions>
        <a-button type="primary" @click="configRequest.run()">
          <template #icon><ReloadOutlined /></template>{{ t('common.refresh') }}
        </a-button>
      </template>
    </VnState>

    <template v-else>
      <div class="vn-alert-settings-grid">
        <section class="vn-panel">
          <header class="vn-panel-head">
            <span class="vnp-panel-head"><InboxOutlined />{{ t('alert.email_settings') }}</span>
          </header>
          <div class="vn-panel-body">
            <div class="vn-alert-field">
              <span class="vn-alert-field-label">{{ t('alert.email_recipients') }}</span>
              <a-space-compact block>
                <a-input
                  v-model:value="emailInput"
                  allow-clear
                  :placeholder="t('alert.email_placeholder')"
                  :aria-label="t('alert.email_placeholder')"
                  @press-enter="handleAddEmail"
                />
                <a-button type="primary" @click="handleAddEmail">
                  <template #icon><PlusOutlined /></template>{{ t('common.add') }}
                </a-button>
              </a-space-compact>
            </div>

            <div class="vn-alert-email-list">
              <a-tag
                v-for="email in emails"
                :key="email"
                closable
                class="vn-alert-email-pill"
                @close="handleRemoveEmail(email)"
              >
                {{ email }}
              </a-tag>
              <span v-if="emails.length === 0" class="vnp-hint">{{ t('alert.no_emails') }}</span>
            </div>

            <div class="vn-alert-field-row">
              <label class="vn-alert-field">
                <span class="vn-alert-field-label">{{ t('alert.email_language') }}</span>
                <a-select
                  v-model:value="formState.emailLanguage"
                  class="vn-alert-language-select"
                  :options="languageOptions"
                  :aria-label="t('alert.email_language')"
                />
              </label>
              <label class="vn-alert-field vn-alert-field-switch">
                <span class="vn-alert-field-label">{{ t('alert.enabled') }}</span>
                <a-switch
                  v-model:checked="formState.enabled"
                  :checked-children="t('common.enabled')"
                  :un-checked-children="t('common.disabled')"
                />
              </label>
            </div>

            <p class="vnp-hint">{{ t('alert.recipients_hint') }}</p>
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

      <div class="vn-alert-save-row">
        <span class="vnp-hint">{{ t('alert.threshold_hint') }}</span>
        <a-button type="primary" :loading="saving" @click="handleSave">{{ t('alert.save_config') }}</a-button>
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