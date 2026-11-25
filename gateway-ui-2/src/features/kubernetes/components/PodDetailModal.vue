<script setup lang="ts">
/**
 * Pod detail modal: info / YAML / logs tabs. Logs are snapshot-based (the API
 * returns the tail window, not a stream), so realtime mode re-fetches instead
 * of appending. Kept mounted by the drawer and driven through `open`.
 */
import { computed, onBeforeUnmount, ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import {
  ClearOutlined, ContainerOutlined, CopyOutlined, PauseCircleOutlined,
  PlayCircleOutlined, ReloadOutlined,
} from '@/components/icons'
import { getPodDetail, getPodLogs, getPodYaml } from '@/api/kubernetes'
import type { KubernetesPodDetail, KubernetesPodInfo } from '@/types/kubernetes'
import { message } from '@/utils/feedback'
import { copyText } from '@/utils/clipboard'
import VnBadge from '@/components/vn/VnBadge.vue'
import { phaseTone, vnTone } from '../utils'

const props = defineProps<{
  clusterId: number
  pod?: KubernetesPodInfo | null
  open?: boolean
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
const isOpen = computed(() => props.open !== false)

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
  if (!props.pod) return
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
  if (!props.pod) return
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

watch(isOpen, (open) => {
  if (open) {
    detail.value = null
    yaml.value = ''
    logs.value = ''
    activeTab.value = 'info'
    logMode.value = 'history'
    logTailLines.value = INITIAL_TAIL_LINES
    logSinceSeconds.value = 0
    logAutoRefresh.value = false
    logRefreshInterval.value = 3
    loadInitial()
  } else {
    stopLogTimer()
    logAutoRefresh.value = false
  }
})

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

async function copyYaml() {
  const ok = await copyText(yaml.value)
  if (ok) message.success(t('k8s.copied'))
  else message.error(t('message.copy_failed'))
}
</script>

<template>
  <a-modal
    :open="isOpen"
    :width="900"
    :footer="null"
    :mask-closable="true"
    :get-container="getModalContainer"
    wrap-class-name="vn-portal vnp-portal vnp-modal"
    @cancel="emit('close')"
  >
    <template #title>
      <div class="vnp-modal-head">
        <span class="vnp-modal-head-glyph" aria-hidden="true"><ContainerOutlined /></span>
        <div style="min-width: 0">
          <div class="vnp-modal-title vn-mono">{{ pod?.name }}</div>
          <div class="vnp-modal-subtitle">{{ pod?.namespace }}</div>
        </div>
        <VnBadge :tone="vnTone(phaseTone(pod?.phase))">{{ pod?.phase || '—' }}</VnBadge>
      </div>
    </template>

    <a-spin :spinning="loading">
      <a-tabs v-model:activeKey="activeTab">
        <a-tab-pane key="info" :tab="t('k8s.pod_info')">
          <div v-if="detail" class="vn-k8s-pod-body">
            <section class="vnp-section">
              <div class="vnp-section-head">{{ t('k8s.basic_info') }}</div>
              <div class="vnp-kv">
                <div class="vnp-kv-item">
                  <span class="vnp-kv-label">{{ t('k8s.namespace') }}</span>
                  <span class="vnp-kv-value">{{ detail.namespace }}</span>
                </div>
                <div class="vnp-kv-item">
                  <span class="vnp-kv-label">Pod IP</span>
                  <span class="vnp-kv-value vn-mono">{{ detail.podIP || '—' }}</span>
                </div>
                <div class="vnp-kv-item">
                  <span class="vnp-kv-label">{{ t('k8s.host_ip') }}</span>
                  <span class="vnp-kv-value vn-mono">{{ detail.hostIP || '—' }}</span>
                </div>
                <div class="vnp-kv-item">
                  <span class="vnp-kv-label">{{ t('k8s.node') }}</span>
                  <span class="vnp-kv-value">{{ detail.nodeName || '—' }}</span>
                </div>
                <div class="vnp-kv-item">
                  <span class="vnp-kv-label">{{ t('k8s.start_time') }}</span>
                  <span class="vnp-kv-value">{{ detail.startTime || '—' }}</span>
                </div>
                <div class="vnp-kv-item">
                  <span class="vnp-kv-label">{{ t('k8s.service_account') }}</span>
                  <span class="vnp-kv-value">{{ detail.serviceAccountName || '—' }}</span>
                </div>
                <div class="vnp-kv-item vnp-kv-wide">
                  <span class="vnp-kv-label">{{ t('k8s.restart_policy') }}</span>
                  <span class="vnp-kv-value">{{ detail.restartPolicy || '—' }}</span>
                </div>
              </div>
            </section>

            <section class="vnp-section" style="margin-top: 12px">
              <div class="vnp-section-head"><span>{{ t('k8s.containers') }}</span><span class="vnp-count">{{ detail.containers?.length ?? 0 }}</span></div>
              <div class="vnp-list">
                <div v-for="container in detail.containers ?? []" :key="container.name" class="vn-k8s-container-card">
                  <div class="vn-k8s-container-head">
                    <span class="vnp-strong">{{ container.name }}</span>
                    <a-tooltip :title="container.image" overlay-class-name="vn-tooltip">
                      <code class="vn-mono vnp-cell-muted">{{ container.image }}</code>
                    </a-tooltip>
                  </div>
                  <div class="vnp-kv">
                    <div class="vnp-kv-item">
                      <span class="vnp-kv-label">{{ t('k8s.image_pull_policy') }}</span>
                      <span class="vnp-kv-value">{{ container.imagePullPolicy || '—' }}</span>
                    </div>
                    <div class="vnp-kv-item">
                      <span class="vnp-kv-label">{{ t('k8s.ready') }}</span>
                      <span class="vnp-kv-value">
                        <VnBadge :tone="containerReady(container.name) ? 'ok' : 'err'">
                          {{ containerReady(container.name) ? t('k8s.ready_true') : t('k8s.ready_false') }}
                        </VnBadge>
                      </span>
                    </div>
                    <div class="vnp-kv-item">
                      <span class="vnp-kv-label">{{ t('k8s.restarts') }}</span>
                      <span class="vnp-kv-value">{{ containerRestarts(container.name) }}</span>
                    </div>
                    <div v-if="container.ports && container.ports.length" class="vnp-kv-item">
                      <span class="vnp-kv-label">{{ t('k8s.ports') }}</span>
                      <span class="vnp-kv-value vn-k8s-container-tags">
                        <span v-for="(port, idx) in container.ports" :key="idx" class="vn-chip">{{ port.containerPort }}/{{ port.protocol }}</span>
                      </span>
                    </div>
                  </div>
                  <div v-if="container.env && container.env.length" class="vn-k8s-env-block">
                    <span class="vnp-kv-label">{{ t('k8s.env_vars') }}</span>
                    <div class="vn-k8s-env-list">
                      <div v-for="env in container.env" :key="env.name" class="vn-k8s-env-row">
                        <code class="vn-mono vn-k8s-env-name">{{ env.name }}</code>
                        <span class="vnp-cell-muted">{{ env.value || `(${t('k8s.from_secret')})` }}</span>
                      </div>
                    </div>
                  </div>
                </div>
              </div>
            </section>

            <section v-if="detail.conditions && detail.conditions.length" class="vnp-section" style="margin-top: 12px">
              <div class="vnp-section-head">{{ t('k8s.conditions') }}</div>
              <a-table
                :columns="conditionColumns"
                :data-source="detail.conditions"
                :pagination="false"
                row-key="type"
                size="small"
              >
                <template #bodyCell="{ column, record }">
                  <template v-if="column.key === 'status'">
                    <VnBadge :tone="record.status === 'True' ? 'ok' : 'err'">{{ record.status }}</VnBadge>
                  </template>
                  <template v-else-if="column.key === 'reason'">
                    <span class="vnp-cell-muted">{{ record.reason || '—' }}</span>
                  </template>
                  <template v-else-if="column.key === 'message'">
                    <span class="vnp-cell-muted" :title="record.message">{{ record.message || '—' }}</span>
                  </template>
                </template>
              </a-table>
            </section>

            <section v-if="detail.events && detail.events.length" class="vnp-section" style="margin-top: 12px">
              <div class="vnp-section-head">{{ t('k8s.events') }}</div>
              <div class="vnp-list">
                <div v-for="(event, idx) in detail.events" :key="idx" class="vn-k8s-event-card">
                  <div class="vn-k8s-event-head">
                    <VnBadge :tone="event.type === 'Normal' ? 'info' : 'warn'">{{ event.reason }}</VnBadge>
                    <span class="vnp-cell-muted">{{ event.lastTimestamp || '—' }}</span>
                  </div>
                  <p class="vn-k8s-event-message">{{ event.message }}</p>
                </div>
              </div>
            </section>
          </div>
        </a-tab-pane>

        <a-tab-pane key="yaml" tab="YAML">
          <div class="vnp-code">
            <div class="vnp-code-head">
              <span class="vnp-form-section-title">{{ t('k8s.yaml_definition') }}</span>
              <a-button size="small" @click="copyYaml">
                <template #icon><CopyOutlined /></template>{{ t('common.copy') }}
              </a-button>
            </div>
            <pre class="vnp-code-pre">{{ yaml || '—' }}</pre>
          </div>
        </a-tab-pane>

        <a-tab-pane key="logs" :tab="t('k8s.logs')">
          <div class="vnp-code">
            <div class="vnp-code-head vn-k8s-logs-head">
              <div class="vn-k8s-logs-group">
                <span class="vnp-form-section-title">{{ t('k8s.container_logs') }}</span>
                <a-select
                  :value="logMode"
                  :options="[
                    { value: 'history', label: t('k8s.log_history') },
                    { value: 'realtime', label: t('k8s.log_realtime') },
                  ]"
                  class="vn-k8s-log-select"
                  @change="handleLogModeChange"
                />
              </div>
              <div v-if="logMode === 'history'" class="vn-k8s-logs-group">
                <a-select v-model:value="logTailLines" :options="tailLineOptions" class="vn-k8s-log-select" />
                <a-select v-model:value="logSinceSeconds" :options="sinceOptions" class="vn-k8s-log-select-lg" />
                <a-button :loading="logsLoading" @click="loadLogs">
                  <template #icon><ReloadOutlined /></template>{{ t('common.refresh') }}
                </a-button>
              </div>
              <div v-else class="vn-k8s-logs-group">
                <a-select v-model:value="logRefreshInterval" :options="intervalOptions" class="vn-k8s-log-select" />
                <a-button :type="logAutoRefresh ? 'primary' : 'default'" @click="toggleRealtime">
                  <template #icon>
                    <PauseCircleOutlined v-if="logAutoRefresh" />
                    <PlayCircleOutlined v-else />
                  </template>
                  {{ logAutoRefresh ? t('k8s.log_stop') : t('k8s.log_start') }}
                </a-button>
                <a-button @click="clearLogs">
                  <template #icon><ClearOutlined /></template>{{ t('k8s.log_clear') }}
                </a-button>
              </div>
            </div>
            <pre class="vnp-code-pre">{{ logs }}</pre>
          </div>
        </a-tab-pane>
      </a-tabs>
    </a-spin>
  </a-modal>
</template>