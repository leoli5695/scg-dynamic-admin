<script setup lang="ts">
/**
 * Access log page — Vue port of gateway-ui/src/features/access-log/index.tsx.
 * Configuration form + log viewer views. The reactive form lives here so the
 * footer Save/Reset actions act on it, and the effective deploy mode (uncommitted
 * edit wins) is passed down to the viewer and the collection guide. Rendered on
 * the Vue-native design layer; API calls and payloads are unchanged.
 */
import { computed, reactive, ref, type Component } from 'vue'
import { useI18n } from 'vue-i18n'
import {
  ClusterOutlined, ContainerOutlined, ExclamationCircleOutlined, EyeOutlined,
  HddOutlined, InboxOutlined, ReloadOutlined, SettingOutlined,
} from '@/components/icons'
import { useRequest } from '@/composables/useRequest'
import { ApiError } from '@/api/http'
import {
  getAccessLogConfig, getDeployModes, resetAccessLogConfig, saveAccessLogConfig,
} from '@/api/accessLog'
import type { AccessLogConfig, DeployMode, OutputTarget } from '@/api/accessLog'
import { message } from '@/utils/feedback'
import { useInstanceDetail } from '@/features/instance-detail/context'
import VnIconButton from '@/components/vn/VnIconButton.vue'
import VnSegmented, { type VnSegmentOption } from '@/components/vn/VnSegmented.vue'
import VnState from '@/components/vn/VnState.vue'
import VnToolbar from '@/components/vn/VnToolbar.vue'
import { DEFAULT_SENSITIVE_FIELDS } from './constants'
import ConfigTab from './components/ConfigTab.vue'
import LogViewerTab from './components/LogViewerTab.vue'
import '@/styles/vue-native.css'
import '@/features/services/services-vue-native.css'
import './access-log.css'

defineOptions({ name: 'AccessLogPage' })

const { t } = useI18n()
const instanceContext = useInstanceDetail()
const instanceId = computed(() => instanceContext.instance.instanceId)

/** model/AccessLogGlobalConfig.java defaults — replaced by GET /config on load. */
const form = reactive<AccessLogConfig>({
  enabled: false,
  deployMode: 'LOCAL',
  logDirectory: './logs/access',
  fileNamePattern: 'access-{yyyy-MM-dd}.log',
  logFormat: 'JSON',
  logLevel: 'NORMAL',
  logRequestHeaders: true,
  logResponseHeaders: true,
  logRequestBody: false,
  logResponseBody: false,
  maxBodyLength: 2048,
  samplingRate: 100,
  sensitiveFields: [...DEFAULT_SENSITIVE_FIELDS],
  maxFileSizeMb: 100,
  maxBackupFiles: 30,
  logToConsole: true,
  includeAuthInfo: true,
})

const outputTarget = ref<OutputTarget>('file')
const showFileConfig = ref(true)
const saving = ref(false)
const activeTab = ref<'config' | 'logs'>('config')
/** The viewer mounts lazily on first visit, then stays mounted for instant re-activation. */
const logsVisited = ref(false)
const logTotal = ref(0)

const tabOptions = computed<VnSegmentOption<'config' | 'logs'>[]>(() => [
  { key: 'config', label: t('access_log.config_tab'), icon: SettingOutlined },
  { key: 'logs', label: t('access_log.viewer_tab'), icon: EyeOutlined, count: logTotal.value > 0 ? logTotal.value : undefined },
])

const activeTabModel = computed<'config' | 'logs'>({
  get: () => activeTab.value,
  set: (value) => {
    activeTab.value = value
    if (value === 'logs') logsVisited.value = true
  },
})

/** Infer the frontend-only output target from the persisted config. */
function inferOutputTarget(config: AccessLogConfig): OutputTarget {
  if (config.logToConsole && config.logDirectory) return 'both'
  if (config.logToConsole) return 'stdout'
  return 'file'
}

const configRequest = useRequest(
  (signal) => getAccessLogConfig({ instanceId: instanceId.value }, { signal }),
  {
    deps: [instanceId],
    onError: () => message.error(t('access_log.load_failed')),
    onSuccess: (data) => {
      if (!data) return
      Object.assign(form, data)
      const target = inferOutputTarget(data)
      outputTarget.value = target
      showFileConfig.value = target !== 'stdout'
    },
  },
)

const deployModesRequest = useRequest((signal) => getDeployModes({ signal }))
const deployModes = computed(() => deployModesRequest.data.value ?? [])
const savedConfig = computed<AccessLogConfig | null>(() => configRequest.data.value ?? null)
const configLoading = configRequest.loading
const configError = configRequest.error
const reloadConfig = configRequest.run

const effectiveDeployMode = computed<DeployMode>(
  () => form.deployMode || savedConfig.value?.deployMode || 'LOCAL',
)

const MODE_ICONS: Record<string, Component> = {
  LOCAL: HddOutlined,
  DOCKER: ContainerOutlined,
  K8S: ClusterOutlined,
  CUSTOM: InboxOutlined,
}

const modeIcon = computed(() => MODE_ICONS[effectiveDeployMode.value] ?? InboxOutlined)
const modeDescription = computed(() => (
  deployModes.value.find((mode) => mode.mode === effectiveDeployMode.value)?.description
    ?? effectiveDeployMode.value
))

function handleDeployModeChange(mode: DeployMode) {
  const selected = deployModes.value.find((item) => item.mode === mode)
  if (selected) form.logDirectory = selected.defaultPath

  let recommended: OutputTarget
  let showFile = true
  switch (mode) {
    case 'K8S':
      // Cloud-native best practice: stdout only, file options disabled.
      recommended = 'stdout'
      showFile = false
      form.logToConsole = true
      message.info(t('access_log.k8s_auto_switch_msg'))
      break
    case 'DOCKER':
      recommended = 'both'
      form.logToConsole = true
      break
    case 'LOCAL':
      recommended = 'file'
      form.logToConsole = false
      break
    default:
      recommended = outputTarget.value
  }
  outputTarget.value = recommended
  showFileConfig.value = showFile
}

function handleOutputTargetChange(target: OutputTarget) {
  outputTarget.value = target
  form.logToConsole = target !== 'file'
  showFileConfig.value = target !== 'stdout'
}

async function handleSave() {
  saving.value = true
  try {
    await saveAccessLogConfig({
      ...form,
      logToConsole: outputTarget.value !== 'file',
      instanceId: instanceId.value,
      sensitiveFields: savedConfig.value?.sensitiveFields ?? DEFAULT_SENSITIVE_FIELDS,
    })
    message.success(t('access_log.save_success'))
    reloadConfig()
  } catch (error) {
    message.error(error instanceof ApiError && error.message ? error.message : t('access_log.save_failed'))
  } finally {
    saving.value = false
  }
}

async function handleReset() {
  try {
    await resetAccessLogConfig({ instanceId: instanceId.value })
    message.success(t('access_log.reset_success'))
    reloadConfig()
  } catch (error) {
    message.error(error instanceof ApiError && error.message ? error.message : t('access_log.reset_failed'))
  }
}
</script>

<template>
  <div class="access-log-page vn-scope">
    <VnToolbar :title="t('access_log.title')" :lede="t('access_log.description_helper')">
      <template #actions>
        <span class="vn-chip al-mode-chip">
          <component :is="modeIcon" />
          {{ modeDescription }}
        </span>
        <VnSegmented v-model="activeTabModel" :options="tabOptions" :label="t('access_log.view_mode')" />
        <VnIconButton :icon="ReloadOutlined" :label="t('common.refresh')" :disabled="configLoading" @click="reloadConfig" />
      </template>
    </VnToolbar>

    <div v-if="configLoading && !savedConfig" class="al-loading">
      <a-spin size="large" />
    </div>

    <VnState
      v-else-if="configError && !savedConfig"
      :icon="ExclamationCircleOutlined"
      tone="err"
      :title="t('access_log.load_failed')"
      :hint="configError.message"
    >
      <template #actions>
        <a-button type="primary" @click="reloadConfig">
          <template #icon><ReloadOutlined /></template>
          {{ t('common.refresh') }}
        </a-button>
      </template>
    </VnState>

    <template v-else>
      <div v-show="activeTab === 'config'" class="al-config-wrap">
        <ConfigTab
          :form="form"
          :config="savedConfig"
          :deploy-modes="deployModes"
          :output-target="outputTarget"
          :show-file-config="showFileConfig"
          @deploy-mode-change="handleDeployModeChange"
          @output-target-change="handleOutputTargetChange"
        />
        <div class="al-settings-footer">
          <a-button @click="handleReset">{{ t('access_log.reset') }}</a-button>
          <a-button type="primary" :loading="saving" @click="handleSave">
            {{ t('common.save') }}
          </a-button>
        </div>
      </div>

      <LogViewerTab
        v-if="logsVisited"
        v-show="activeTab === 'logs'"
        :instance-id="instanceId"
        :deploy-mode="effectiveDeployMode"
        @total-change="(value: number) => (logTotal = value)"
      />
    </template>
  </div>
</template>