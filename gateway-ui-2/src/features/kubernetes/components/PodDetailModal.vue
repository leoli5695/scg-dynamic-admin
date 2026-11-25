<script setup lang="ts">
/**
 * Pod detail modal: info / YAML / logs tabs. Logs are snapshot-based (the API
 * returns the tail window, not a stream), so realtime mode re-fetches instead
 * of appending.
 */
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import {
  ClearOutlined, ContainerOutlined, CopyOutlined, PauseCircleOutlined,
  PlayCircleOutlined, ReloadOutlined,
} from '@/components/icons'
import { getPodDetail, getPodLogs, getPodYaml } from '@/api/kubernetes'
import type { KubernetesPodDetail, KubernetesPodInfo } from '@/types/kubernetes'
import { message } from '@/utils/feedback'
import { phaseTone } from '../utils'

const props = defineProps<{
  clusterId: number
  pod: KubernetesPodInfo
}>()

const emit = defineEmits<{ (e: 'close'): void }>()

const { t } = useI18n()
const INITIAL_TAIL_LINES = 200
/** Render on body: this modal is declared inside the drawer, and the global
 *  popup container would otherwise teleport it into the drawer subtree. */
const getModalContainer = () => document.body

const detail = ref<KubernetesPodDetail | null>(null)
const yaml = ref('')
const logs = ref('')
const loading = ref(false)
const activeTab = ref('info')

const logMode = ref<'history' | 'realtime'>('history')
const logTailLines = ref(INITIAL_TAIL_LINES)
const logSinceSeconds = ref(0)
const logAutoRefresh = ref(false)
const logRefreshInterval = ref(3)
const logsLoading = ref(false)

const tailLineOptions = [100, 200, 500, 1000].map((n) => ({
  value: n,
  label: t('k8s.log_lines_option', { n }),
}))
const sinceOptions = computed(() => [
  { value: 0, label: t('k8s.log_all_time') },
  { value: 300, label: t('k8s.log_minutes', { n: 5 }) },
  { value: 600, label: t('k8s.log_minutes', { n: 10 }) },
  { value: 1800, label: t('k8s.log_minutes', { n: 30 }) },
  { value: 3600, label: t('k8s.log_hours', { n: 1 }) },
])
const intervalOptions = [1, 3, 5, 10].map((n) => ({ value: n, label: `${n}s` }))

const conditionColumns = computed(() => [
  { title: t('k8s.type'), dataIndex: 'type', key: 'type', width: 160 },
  { title: t('k8s.status'), dataIndex: 'status', key: 'status', width: 90 },
  { title: t('k8s.reason'), dataIndex: 'reason', key: 'reason', width: 130 },
  { title: t('k8s.message'), dataIndex: 'message', key: 'message', ellipsis: true },
])

const statusByContainer = computed(() => {
  const map = new Map<string, boolean>()
  for (const status of detail.value?.containerStatuses ?? []) {
    map.set(status.name, status.ready)
  }
  return map
})

function containerReady(name: string): boolean {
  return statusByContainer.value.get(name) === true
}

function containerRestarts(name: string): number {
  return detail.value?.containerStatuses?.find((s) => s.name === name)?.restartCount || 0
}

async function loadInitial() {
  loading.value = true
  try {
    const [detailData, yamlData, logsData] = await Promise.all([
      getPodDetail(props.clusterId, props.pod.namespace, props.pod.name),
      getPodYaml(props.clusterId, props.pod.namespace, props.pod.name),
      getPodLogs(props.clusterId, props.pod.namespace, props.pod.name, { tailLines: INITIAL_TAIL_LINES }),
    ])
    detail.value = detailData
    yaml.value = yamlData
    logs.value = logsData || t('k8s.no_logs')
  } catch {
    message.error(t('k8s.load_failed'))
  } finally {
    loading.value = false
  }
}

async function loadLogs() {
  logsLoading.value = true
  try {
    const data = await getPodLogs(
      props.clusterId,
      props.pod.namespace,
      props.pod.name,
      logSinceSeconds.value > 0
        ? { sinceSeconds: logSinceSeconds.value }
        : { tailLines: logTailLines.value },
    )
    logs.value = data || t('k8s.no_logs')
  } catch {
    // Keep the previous logs on transient failures.
  } finally {
    logsLoading.value = false
  }
}

let logTimer: ReturnType<typeof setInterval> | undefined
function stopLogTimer() {
  if (logTimer !== undefined) {
    clearInterval(logTimer)
    logTimer = undefined
  }
}

watch([logAutoRefresh, logRefreshInterval], () => {
  stopLogTimer()
  if (logAutoRefresh.value) {
    logTimer = setInterval(() => { loadLogs() }, logRefreshInterval.value * 1000)
  }
})

onMounted(loadInitial)
onBeforeUnmount(stopLogTimer)

function handleLogModeChange(mode: 'history' | 'realtime') {
  logMode.value = mode
  if (mode === 'history') logAutoRefresh.value = false
}

function startRealtime() {
  logs.value = ''
  logAutoRefresh.value = true
}

function toggleRealtime() {
  if (logAutoRefresh.value) logAutoRefresh.value = false
  else startRealtime()
}

function clearLogs() {
  logs.value = ''
  loadLogs()
}

function copyYaml() {
  navigator.clipboard.writeText(yaml.value)
  message.success(t('k8s.copied'))
}
</script>

<template>
  <a-modal
    :open="true"
    :width="900"
    :footer="null"
    :mask-closable="true"
    :destroy-on-close="true"
    :get-container="getModalContainer"
    wrap-class-name="k8s-pod-modal"
    @cancel="emit('close')"
  >
    <template #title>
      <div class="k8s-modal-head">
        <span class="k8s-modal-glyph" aria-hidden="true"><ContainerOutlined /></span>
        <div class="k8s-modal-heading">
          <span class="k8s-modal-title k8s-mono k8s-pod-title">{{ pod.name }}</span>
          <span class="k8s-modal-subtitle">{{ pod.namespace }}</span>
        </div>
        <span :class="['k8s-pill', 'status-badge', `status-badge-${phaseTone(pod.phase)}`]">
          {{ pod.phase || '—' }}
        </span>
      </div>
    </template>

    <a-spin :spinning="loading">
      <a-tabs v-model:activeKey="activeTab" class="k8s-pod-tabs">
        <a-tab-pane key="info" :tab="t('k8s.pod_info')">
          <div v-if="detail" class="k8s-pod-body">
            <section class="k8s-subpanel">
              <div class="k8s-subpanel-head">
                <span class="k8s-section-title">{{ t('k8s.basic_info') }}</span>
              </div>
              <div class="k8s-pod-info-grid">
                <div class="k8s-pod-info-item">
                  <span class="k8s-kv-label">{{ t('k8s.namespace') }}</span>
                  <span class="k8s-pod-info-value">{{ detail.namespace }}</span>
                </div>
                <div class="k8s-pod-info-item">
                  <span class="k8s-kv-label">Pod IP</span>
                  <span class="k8s-pod-info-value k8s-mono">{{ detail.podIP || '—' }}</span>
                </div>
                <div class="k8s-pod-info-item">
                  <span class="k8s-kv-label">{{ t('k8s.host_ip') }}</span>
                  <span class="k8s-pod-info-value k8s-mono">{{ detail.hostIP || '—' }}</span>
                </div>
                <div class="k8s-pod-info-item">
                  <span class="k8s-kv-label">{{ t('k8s.node') }}</span>
                  <span class="k8s-pod-info-value">{{ detail.nodeName || '—' }}</span>
                </div>
                <div class="k8s-pod-info-item">
                  <span class="k8s-kv-label">{{ t('k8s.start_time') }}</span>
                  <span class="k8s-pod-info-value">{{ detail.startTime || '—' }}</span>
                </div>
                <div class="k8s-pod-info-item">
                  <span class="k8s-kv-label">{{ t('k8s.service_account') }}</span>
                  <span class="k8s-pod-info-value">{{ detail.serviceAccountName || '—' }}</span>
                </div>
                <div class="k8s-pod-info-item k8s-pod-info-wide">
                  <span class="k8s-kv-label">{{ t('k8s.restart_policy') }}</span>
                  <span class="k8s-pod-info-value">{{ detail.restartPolicy || '—' }}</span>
                </div>
              </div>
            </section>

            <section class="k8s-subpanel">
              <div class="k8s-subpanel-head">
                <span class="k8s-section-title">{{ t('k8s.containers') }}</span>
                <span class="k8s-count-pill">{{ detail.containers?.length ?? 0 }}</span>
              </div>
              <div class="k8s-container-list">
                <div v-for="container in detail.containers ?? []" :key="container.name" class="k8s-container-card">
                  <div class="k8s-container-head">
                    <span class="k8s-container-name">{{ container.name }}</span>
                    <a-tooltip :title="container.image">
                      <code class="k8s-container-image">{{ container.image }}</code>
                    </a-tooltip>
                  </div>
                  <div class="k8s-container-details">
                    <div class="k8s-container-detail">
                      <span class="k8s-kv-label">{{ t('k8s.image_pull_policy') }}</span>
                      <span class="k8s-container-detail-value">{{ container.imagePullPolicy || '—' }}</span>
                    </div>
                    <div class="k8s-container-detail">
                      <span class="k8s-kv-label">{{ t('k8s.ready') }}</span>
                      <span
                        :class="['k8s-pill', 'status-badge', containerReady(container.name) ? 'status-badge-success' : 'status-badge-error']"
                      >
                        {{ containerReady(container.name) ? t('k8s.ready_true') : t('k8s.ready_false') }}
                      </span>
                    </div>
                    <div class="k8s-container-detail">
                      <span class="k8s-kv-label">{{ t('k8s.restarts') }}</span>
                      <span class="k8s-container-detail-value">{{ containerRestarts(container.name) }}</span>
                    </div>
                    <div v-if="container.ports && container.ports.length" class="k8s-container-detail">
                      <span class="k8s-kv-label">{{ t('k8s.ports') }}</span>
                      <span class="k8s-container-tags">
                        <a-tag v-for="(port, idx) in container.ports" :key="idx" class="k8s-port-tag">
                          {{ port.containerPort }}/{{ port.protocol }}
                        </a-tag>
                      </span>
                    </div>
                  </div>
                  <div v-if="container.env && container.env.length" class="k8s-env-block">
                    <span class="k8s-kv-label">{{ t('k8s.env_vars') }}</span>
                    <div class="k8s-env-list">
                      <div v-for="env in container.env" :key="env.name" class="k8s-env-row">
                        <code class="k8s-env-name">{{ env.name }}</code>
                        <span class="k8s-env-value">{{ env.value || `(${t('k8s.from_secret')})` }}</span>
                      </div>
                    </div>
                  </div>
                </div>
              </div>
            </section>

            <section v-if="detail.conditions && detail.conditions.length" class="k8s-subpanel">
              <div class="k8s-subpanel-head">
                <span class="k8s-section-title">{{ t('k8s.conditions') }}</span>
              </div>
              <a-table
                :columns="conditionColumns"
                :data-source="detail.conditions"
                :pagination="false"
                row-key="type"
                size="small"
                class="k8s-table"
              >
                <template #bodyCell="{ column, record }">
                  <template v-if="column.key === 'status'">
                    <span :class="['k8s-pill', 'status-badge', record.status === 'True' ? 'status-badge-success' : 'status-badge-error']">
                      {{ record.status }}
                    </span>
                  </template>
                  <template v-else-if="column.key === 'reason'">
                    <span class="k8s-cell-muted">{{ record.reason || '—' }}</span>
                  </template>
                  <template v-else-if="column.key === 'message'">
                    <span class="k8s-cell-muted" :title="record.message">{{ record.message || '—' }}</span>
                  </template>
                </template>
              </a-table>
            </section>

            <section v-if="detail.events && detail.events.length" class="k8s-subpanel">
              <div class="k8s-subpanel-head">
                <span class="k8s-section-title">{{ t('k8s.events') }}</span>
              </div>
              <div class="k8s-event-list">
                <div v-for="(event, idx) in detail.events" :key="idx" class="k8s-event-card">
                  <div class="k8s-event-head">
                    <span :class="['k8s-pill', 'status-badge', event.type === 'Normal' ? 'status-badge-info' : 'status-badge-warning']">
                      {{ event.reason }}
                    </span>
                    <span class="k8s-event-time">{{ event.lastTimestamp || '—' }}</span>
                  </div>
                  <p class="k8s-event-message">{{ event.message }}</p>
                </div>
              </div>
            </section>
          </div>
        </a-tab-pane>

        <a-tab-pane key="yaml" tab="YAML">
          <div class="k8s-code-block">
            <div class="k8s-code-head">
              <span class="k8s-section-title">{{ t('k8s.yaml_definition') }}</span>
              <a-button size="small" @click="copyYaml">
                <template #icon><CopyOutlined /></template>
                {{ t('common.copy') }}
              </a-button>
            </div>
            <pre class="k8s-code-pre">{{ yaml || '—' }}</pre>
          </div>
        </a-tab-pane>

        <a-tab-pane key="logs" :tab="t('k8s.logs')">
          <div class="k8s-code-block">
            <div class="k8s-code-head k8s-logs-head">
              <div class="k8s-logs-group">
                <span class="k8s-section-title">{{ t('k8s.container_logs') }}</span>
                <a-select
                  :value="logMode"
                  :options="[
                    { value: 'history', label: t('k8s.log_history') },
                    { value: 'realtime', label: t('k8s.log_realtime') },
                  ]"
                  class="k8s-log-mode-select"
                  @change="handleLogModeChange"
                />
              </div>
              <div v-if="logMode === 'history'" class="k8s-logs-group">
                <a-select v-model:value="logTailLines" :options="tailLineOptions" class="k8s-log-select" />
                <a-select v-model:value="logSinceSeconds" :options="sinceOptions" class="k8s-log-select-lg" />
                <a-button :loading="logsLoading" @click="loadLogs">
                  <template #icon><ReloadOutlined /></template>
                  {{ t('common.refresh') }}
                </a-button>
              </div>
              <div v-else class="k8s-logs-group">
                <a-select v-model:value="logRefreshInterval" :options="intervalOptions" class="k8s-log-select" />
                <a-button :type="logAutoRefresh ? 'primary' : 'default'" @click="toggleRealtime">
                  <template #icon>
                    <PauseCircleOutlined v-if="logAutoRefresh" />
                    <PlayCircleOutlined v-else />
                  </template>
                  {{ logAutoRefresh ? t('k8s.log_stop') : t('k8s.log_start') }}
                </a-button>
                <a-button @click="clearLogs">
                  <template #icon><ClearOutlined /></template>
                  {{ t('k8s.log_clear') }}
                </a-button>
              </div>
            </div>
            <pre class="k8s-code-pre k8s-logs-pre">{{ logs }}</pre>
          </div>
        </a-tab-pane>
      </a-tabs>
    </a-spin>
  </a-modal>
</template>