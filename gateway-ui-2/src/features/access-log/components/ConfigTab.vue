<script setup lang="ts">
/**
 * Access log configuration tab — port of
 * gateway-ui/src/features/access-log/components/ConfigTab.tsx.
 * The reactive form object is owned by the page shell (Save/Reset act on it), so
 * this component binds straight into it; deploy-mode / output-target changes are
 * emitted because they also drive the shell's viewer and the collection guide.
 */
import { computed, type Component } from 'vue'
import { useI18n } from 'vue-i18n'
import {
  CheckCircleOutlined, ClusterOutlined, ContainerOutlined, FileTextOutlined,
  HddOutlined, InboxOutlined, InfoCircleOutlined, OrderedListOutlined,
  SettingOutlined, SplitCellsOutlined,
} from '@/components/icons'
import type {
  AccessLogConfig, DeployMode, DeployModeOption, OutputTarget,
} from '@/api/accessLog'
import CollectionGuide from './CollectionGuide.vue'

const props = defineProps<{
  form: AccessLogConfig
  config: AccessLogConfig | null
  deployModes: DeployModeOption[]
  outputTarget: OutputTarget
  showFileConfig: boolean
}>()

const emit = defineEmits<{
  (e: 'deploy-mode-change', mode: DeployMode): void
  (e: 'output-target-change', target: OutputTarget): void
}>()

const { t } = useI18n()

const MODE_ICONS: Record<string, Component> = {
  LOCAL: HddOutlined,
  DOCKER: ContainerOutlined,
  K8S: ClusterOutlined,
  CUSTOM: InboxOutlined,
}

const TARGET_ICONS: Record<string, Component> = {
  stdout: OrderedListOutlined,
  file: FileTextOutlined,
  both: SplitCellsOutlined,
}

/** Hints and the recommended badge follow the SAVED mode, not uncommitted edits. */
const savedDeployMode = computed(() => props.config?.deployMode)

const recommendedTarget = computed<OutputTarget | null>(() => {
  switch (savedDeployMode.value) {
    case 'K8S': return 'stdout'
    case 'LOCAL': return 'file'
    case 'DOCKER': return 'both'
    default: return null
  }
})

const outputOptions = computed(() => [
  { value: 'stdout' },
  { value: 'file', disabled: savedDeployMode.value === 'K8S' },
  { value: 'both', disabled: savedDeployMode.value === 'K8S' },
])

const guideDeployMode = computed(() => props.form.deployMode || savedDeployMode.value || 'LOCAL')

const modeIcon = (mode: string) => MODE_ICONS[mode] ?? InboxOutlined
const targetIcon = (target: string) => TARGET_ICONS[target] ?? FileTextOutlined
</script>

<template>
  <a-form :model="form" layout="vertical" class="al-config-form">
    <div class="al-config-grid">
      <!-- Basic settings -->
      <section class="vn-panel al-panel">
        <header class="al-panel-head">
          <span class="al-panel-title"><SettingOutlined /> {{ t('access_log.basic_settings') }}</span>
        </header>
        <div class="al-panel-body">
          <label class="al-switch-row">
            <span class="al-switch-label">{{ t('access_log.enabled') }}</span>
            <a-switch
              v-model:checked="form.enabled"
              :checked-children="t('common.enabled')"
              :un-checked-children="t('common.disabled')"
            />
          </label>

          <a-form-item :label="t('access_log.deploy_mode')" :tooltip="t('access_log.deploy_mode_tip')">
            <a-select v-model:value="form.deployMode" @change="(value: DeployMode) => emit('deploy-mode-change', value)">
              <a-select-option v-for="mode in deployModes" :key="mode.mode" :value="mode.mode">
                <span class="al-option"><component :is="modeIcon(mode.mode)" /> {{ mode.description }}</span>
              </a-select-option>
            </a-select>
          </a-form-item>

          <a-form-item :label="t('access_log.output_target')" :tooltip="t('access_log.output_target_tip')">
            <a-segmented
              :value="outputTarget"
              :options="outputOptions"
              block
              class="al-target-seg"
              @change="(value: string | number) => emit('output-target-change', value as OutputTarget)"
            >
              <template #label="{ value }">
                <span class="al-seg-label">
                  <component :is="targetIcon(String(value))" />
                  <span>{{ value }}</span>
                  <span v-if="recommendedTarget === value" class="al-seg-recommended">
                    {{ t('access_log.recommended') }}
                  </span>
                </span>
              </template>
            </a-segmented>
          </a-form-item>

          <a-alert
            v-if="savedDeployMode === 'K8S'"
            type="warning"
            show-icon
            class="al-inline-alert"
            :message="t('access_log.file_both_disabled')"
          />

          <a-alert
            v-if="savedDeployMode === 'K8S'"
            type="success"
            show-icon
            class="al-mode-alert"
            :message="t('access_log.k8s_stdout_hint')"
          >
            <template #icon><CheckCircleOutlined /></template>
            <template #description>
              <p class="al-alert-line">
                {{ t('access_log.k8s_stdout_desc_line1') }}<code class="al-inline-code">/var/log/containers/*.log</code>
              </p>
              <p class="al-alert-line is-success">{{ t('access_log.k8s_stdout_desc_line2') }}</p>
            </template>
          </a-alert>

          <a-alert
            v-if="savedDeployMode === 'DOCKER'"
            type="info"
            show-icon
            class="al-mode-alert"
            :message="t('access_log.docker_hint')"
          >
            <template #description>
              <p class="al-alert-line">
                {{ t('access_log.docker_desc_line1') }}<code class="al-inline-code">both</code>{{ t('access_log.docker_desc_line2') }}
              </p>
              <p class="al-alert-line is-primary">{{ t('access_log.docker_desc_line3') }}</p>
            </template>
          </a-alert>

          <a-alert
            v-if="savedDeployMode === 'LOCAL'"
            type="info"
            show-icon
            class="al-mode-alert"
            :message="t('access_log.local_hint_msg')"
            :description="t('access_log.local_hint_desc')"
          />

          <a-alert
            v-if="savedDeployMode === 'CUSTOM'"
            type="warning"
            show-icon
            class="al-mode-alert"
            :message="t('access_log.custom_hint_msg')"
            :description="t('access_log.custom_hint_desc')"
          />

          <template v-if="showFileConfig">
            <a-form-item :label="t('access_log.log_directory')" :extra="t('access_log.directory_hint')">
              <a-input v-model:value="form.logDirectory" :placeholder="t('access_log.directory_placeholder')">
                <template #prefix><InboxOutlined /></template>
              </a-input>
            </a-form-item>

            <a-form-item :label="t('access_log.file_pattern')" :extra="t('access_log.pattern_extra')">
              <a-input v-model:value="form.fileNamePattern" placeholder="access-{yyyy-MM-dd}.log" />
            </a-form-item>
          </template>

          <a-form-item :label="t('access_log.log_format')">
            <a-select v-model:value="form.logFormat">
              <a-select-option value="JSON">
                <span class="al-option"><span class="al-pill is-info">JSON</span>{{ t('access_log.json_desc') }}</span>
              </a-select-option>
              <a-select-option value="TEXT">
                <span class="al-option"><span class="al-pill is-success">TEXT</span>{{ t('access_log.text_desc') }}</span>
              </a-select-option>
            </a-select>
          </a-form-item>

          <a-form-item :label="t('access_log.log_level')">
            <a-select v-model:value="form.logLevel">
              <a-select-option value="MINIMAL">
                <span class="al-option"><span class="al-pill">MINIMAL</span>{{ t('access_log.level_minimal') }}</span>
              </a-select-option>
              <a-select-option value="NORMAL">
                <span class="al-option"><span class="al-pill is-info">NORMAL</span>{{ t('access_log.level_normal') }}</span>
              </a-select-option>
              <a-select-option value="VERBOSE">
                <span class="al-option"><span class="al-pill is-violet">VERBOSE</span>{{ t('access_log.level_verbose') }}</span>
              </a-select-option>
            </a-select>
          </a-form-item>
        </div>
      </section>

      <!-- Log content settings -->
      <section class="vn-panel al-panel">
        <header class="al-panel-head">
          <span class="al-panel-title"><FileTextOutlined /> {{ t('access_log.content_settings') }}</span>
        </header>
        <div class="al-panel-body">
          <label class="al-switch-row">
            <span class="al-switch-label">{{ t('access_log.log_request_headers') }}</span>
            <a-switch v-model:checked="form.logRequestHeaders" size="small" />
          </label>
          <label class="al-switch-row">
            <span class="al-switch-label">{{ t('access_log.log_response_headers') }}</span>
            <a-switch v-model:checked="form.logResponseHeaders" size="small" />
          </label>
          <label class="al-switch-row">
            <span class="al-switch-label">{{ t('access_log.log_request_body') }}</span>
            <a-switch v-model:checked="form.logRequestBody" size="small" />
          </label>
          <label class="al-switch-row">
            <span class="al-switch-label">{{ t('access_log.log_response_body') }}</span>
            <a-switch v-model:checked="form.logResponseBody" size="small" />
          </label>
          <label class="al-switch-row">
            <span class="al-switch-label">
              {{ t('access_log.include_auth_info') }}
              <span class="al-switch-hint">{{ t('access_log.auth_info_extra') }}</span>
            </span>
            <a-switch v-model:checked="form.includeAuthInfo" size="small" />
          </label>

          <div class="al-divider" />

          <a-form-item :label="t('access_log.max_body_length')" :extra="t('access_log.max_body_extra')">
            <a-input-number v-model:value="form.maxBodyLength" :min="0" :max="10000" class="al-number" />
          </a-form-item>

          <a-form-item :label="t('access_log.sampling_rate')" :extra="t('access_log.sampling_extra')">
            <a-input-number v-model:value="form.samplingRate" :min="1" :max="100" class="al-number" />
          </a-form-item>
        </div>
      </section>
    </div>

    <!-- File rotation — only when file output is enabled -->
    <section v-if="showFileConfig" class="vn-panel al-panel">
      <header class="al-panel-head">
        <span class="al-panel-title"><InboxOutlined /> {{ t('access_log.file_settings') }}</span>
      </header>
      <div class="al-panel-body">
        <div class="al-rotation-grid">
          <a-form-item :label="t('access_log.max_file_size')">
            <a-input-number v-model:value="form.maxFileSizeMb" :min="1" :max="1000" class="al-number" />
          </a-form-item>
          <a-form-item :label="t('access_log.max_backup_files')">
            <a-input-number v-model:value="form.maxBackupFiles" :min="0" :max="100" class="al-number" />
          </a-form-item>
        </div>
      </div>
    </section>

    <!-- Fluent Bit collection guide -->
    <section class="vn-panel al-panel">
      <header class="al-panel-head">
        <span class="al-panel-title"><InfoCircleOutlined /> {{ t('access_log.advanced_config') }}</span>
      </header>
      <div class="al-panel-body">
        <a-alert
          type="info"
          show-icon
          class="al-mode-alert"
          :message="t('access_log.storage_hint_msg')"
          :description="t('access_log.storage_hint_desc')"
        />
        <CollectionGuide :active-deploy-mode="guideDeployMode" />
      </div>
    </section>
  </a-form>
</template>