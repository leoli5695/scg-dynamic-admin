<script setup lang="ts">
/**
 * Cluster detail drawer: overview (metadata + nodes), deployments and pods.
 * Namespace is passed explicitly on every reload; failures keep the previous
 * data (connection problems surface on the cluster status instead).
 */
import { computed, onMounted, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import {
  CloseOutlined, ClusterOutlined, CopyOutlined, DeleteOutlined, ExpandOutlined,
  EyeOutlined, PlayCircleOutlined, ReloadOutlined,
} from '@/components/icons'
import {
  deleteDeployment, getClusterDeployments, getClusterNamespaces, getClusterNodes, getClusterPods,
} from '@/api/kubernetes'
import { ApiError } from '@/api/http'
import type {
  KubernetesCluster, KubernetesDeploymentInfo, KubernetesNodeInfo, KubernetesPodInfo,
} from '@/types/kubernetes'
import { formatServerTime } from '@/utils/serverTime'
import { message, modal } from '@/utils/feedback'
import {
  clusterStatusTextKey, clusterStatusTone, nodeStatusTone, phaseTone,
} from '../utils'
import DeployGatewayModal from './DeployGatewayModal.vue'
import ScaleDeploymentModal from './ScaleDeploymentModal.vue'
import PodDetailModal from './PodDetailModal.vue'

const props = defineProps<{ cluster: KubernetesCluster }>()
const emit = defineEmits<{ (e: 'close'): void }>()

const { t } = useI18n()
const DEFAULT_NAMESPACE = 'default'

const nodes = ref<KubernetesNodeInfo[]>([])
const namespaces = ref<string[]>([])
const deployments = ref<KubernetesDeploymentInfo[]>([])
const pods = ref<KubernetesPodInfo[]>([])
const selectedNamespace = ref(DEFAULT_NAMESPACE)
const activeTab = ref('overview')
const resourcesLoading = ref(false)
const deployOpen = ref(false)
const selectedPod = ref<KubernetesPodInfo | null>(null)
const scalingDeployment = ref<KubernetesDeploymentInfo | null>(null)

const tone = computed(() => clusterStatusTone(props.cluster.connectionStatus))
const statusText = computed(() => t(clusterStatusTextKey(props.cluster.connectionStatus)))
const versionText = computed(
  () => (props.cluster.clusterVersion ? `v${props.cluster.clusterVersion}` : t('k8s.version_unknown')),
)
const lastCheckedText = computed(
  () => (props.cluster.lastCheckedAt ? formatServerTime(props.cluster.lastCheckedAt) : '—'),
)

const namespaceOptions = computed(() => [
  { value: '', label: t('k8s.all_namespaces') },
  ...namespaces.value.map((ns) => ({ value: ns, label: ns })),
])

const nodeColumns = computed(() => [
  { title: t('k8s.node_name'), dataIndex: 'name', key: 'name' },
  { title: t('k8s.node_status'), dataIndex: 'status', key: 'status', width: 90 },
  { title: t('k8s.kubelet_version'), dataIndex: 'kubeletVersion', key: 'kubeletVersion', width: 130 },
  { title: t('k8s.os_image'), dataIndex: 'osImage', key: 'osImage', ellipsis: true },
])

const deploymentColumns = computed(() => [
  { title: t('k8s.deployment_name'), dataIndex: 'name', key: 'name' },
  { title: t('k8s.namespace'), dataIndex: 'namespace', key: 'namespace', width: 130 },
  { title: t('k8s.replicas'), dataIndex: 'replicas', key: 'replicas', width: 150 },
  { title: t('common.actions'), key: 'actions', width: 90 },
])

const podColumns = computed(() => [
  { title: t('k8s.pod_name'), dataIndex: 'name', key: 'name', ellipsis: true },
  { title: t('k8s.namespace'), dataIndex: 'namespace', key: 'namespace', width: 120 },
  { title: t('k8s.phase'), dataIndex: 'phase', key: 'phase', width: 96 },
  { title: t('k8s.pod_ip'), dataIndex: 'podIP', key: 'podIP', width: 130 },
  { title: t('k8s.containers'), dataIndex: 'containers', key: 'containers' },
  { title: t('common.actions'), key: 'actions', width: 70 },
])

async function loadResources(namespace: string) {
  resourcesLoading.value = true
  try {
    const [nodeData, namespaceData] = await Promise.all([
      getClusterNodes(props.cluster.id),
      getClusterNamespaces(props.cluster.id),
    ])
    nodes.value = nodeData
    namespaces.value = namespaceData

    const [deploymentData, podData] = await Promise.all([
      getClusterDeployments(props.cluster.id, namespace),
      getClusterPods(props.cluster.id, namespace),
    ])
    deployments.value = deploymentData
    pods.value = podData
  } catch {
    // Keep previous data; connection errors surface via the cluster status.
  } finally {
    resourcesLoading.value = false
  }
}

onMounted(() => {
  loadResources(selectedNamespace.value)
})

function handleNamespaceChange(value: string) {
  selectedNamespace.value = value
  loadResources(value)
}

function handleDeployed(namespace: string) {
  selectedNamespace.value = namespace
  loadResources(namespace)
}

function replicaPercent(deployment: KubernetesDeploymentInfo): number {
  if (!deployment.replicas) return 0
  return Math.round(((deployment.readyReplicas || 0) / deployment.replicas) * 100)
}

function replicaStatus(deployment: KubernetesDeploymentInfo): 'success' | 'active' {
  return (deployment.readyReplicas || 0) >= deployment.replicas ? 'success' : 'active'
}

function handleDeleteDeployment(deployment: KubernetesDeploymentInfo) {
  modal.confirm({
    title: t('k8s.delete_deployment_confirm_title'),
    content: t('k8s.delete_deployment_confirm_content', { name: deployment.name }),
    okText: t('common.delete'),
    okType: 'primary',
    okButtonProps: { danger: true },
    cancelText: t('common.cancel'),
    onOk: async () => {
      try {
        await deleteDeployment(props.cluster.id, deployment.namespace, deployment.name)
        message.success(t('k8s.delete_deployment_success'))
        await loadResources(selectedNamespace.value)
      } catch (error) {
        message.error(
          error instanceof ApiError && error.message ? error.message : t('k8s.delete_deployment_failed'),
        )
      }
    },
  })
}

function copyServerUrl() {
  if (!props.cluster.serverUrl) return
  navigator.clipboard.writeText(props.cluster.serverUrl)
  message.success(t('k8s.copied'))
}
</script>

<template>
  <a-drawer
    :open="true"
    placement="right"
    :width="760"
    :closable="false"
    :mask-closable="true"
    :destroy-on-close="true"
    root-class-name="k8s-drawer"
    @close="emit('close')"
  >
    <div class="k8s-drawer-content">
      <header class="k8s-drawer-head">
        <div class="k8s-drawer-title-wrap">
          <span class="k8s-drawer-glyph" aria-hidden="true"><ClusterOutlined /></span>
          <div class="k8s-drawer-title-main">
            <div class="k8s-drawer-name-row">
              <h4 class="k8s-drawer-name">{{ cluster.clusterName }}</h4>
              <span :class="['k8s-pill', 'status-badge', `status-badge-${tone}`]">
                <i :class="['status-dot', `status-dot-${tone}`]" />
                {{ statusText }}
              </span>
            </div>
            <div class="k8s-drawer-sub">
              <span>{{ versionText }}</span>
              <span class="k8s-dot-sep">·</span>
              <span class="k8s-drawer-server" :title="cluster.serverUrl">{{ cluster.serverUrl || '—' }}</span>
            </div>
          </div>
        </div>
        <div class="k8s-drawer-actions">
          <a-button
            size="small"
            class="k8s-deploy-btn"
            :disabled="cluster.connectionStatus !== 'CONNECTED'"
            @click="deployOpen = true"
          >
            <template #icon><PlayCircleOutlined /></template>
            {{ t('k8s.deploy_gateway') }}
          </a-button>
          <a-tooltip :title="t('common.refresh')">
            <a-button
              type="text"
              class="k8s-icon-btn"
              :loading="resourcesLoading"
              :aria-label="t('common.refresh')"
              @click="loadResources(selectedNamespace)"
            >
              <template #icon><ReloadOutlined /></template>
            </a-button>
          </a-tooltip>
          <a-button
            type="text"
            class="k8s-icon-btn"
            :aria-label="t('common.close')"
            @click="emit('close')"
          >
            <template #icon><CloseOutlined /></template>
          </a-button>
        </div>
      </header>

      <a-tabs v-model:activeKey="activeTab" class="k8s-drawer-tabs">
        <a-tab-pane key="overview" :tab="t('k8s.overview')">
          <section class="k8s-subpanel">
            <div class="k8s-subpanel-head">
              <span class="k8s-section-title">{{ t('k8s.cluster_info') }}</span>
            </div>
            <div class="k8s-kv-panel">
              <div class="k8s-kv-item k8s-kv-wide">
                <span class="k8s-kv-label">{{ t('k8s.server_url') }}</span>
                <span class="k8s-kv-value k8s-mono">
                  {{ cluster.serverUrl || '—' }}
                  <a-tooltip v-if="cluster.serverUrl" :title="t('common.copy')">
                    <a-button type="text" size="small" class="k8s-copy-btn" @click="copyServerUrl">
                      <template #icon><CopyOutlined /></template>
                    </a-button>
                  </a-tooltip>
                </span>
              </div>
              <div class="k8s-kv-item">
                <span class="k8s-kv-label">{{ t('k8s.context') }}</span>
                <span class="k8s-kv-value">{{ cluster.contextName || '—' }}</span>
              </div>
              <div class="k8s-kv-item">
                <span class="k8s-kv-label">{{ t('k8s.cluster_version') }}</span>
                <span class="k8s-kv-value">{{ versionText }}</span>
              </div>
              <div class="k8s-kv-item">
                <span class="k8s-kv-label">{{ t('k8s.last_checked') }}</span>
                <span class="k8s-kv-value">{{ lastCheckedText }}</span>
              </div>
              <div class="k8s-kv-item">
                <span class="k8s-kv-label">{{ t('k8s.status') }}</span>
                <span class="k8s-kv-value">{{ statusText }}</span>
              </div>
            </div>
          </section>

          <section class="k8s-subpanel">
            <div class="k8s-subpanel-head">
              <span class="k8s-section-title">{{ t('k8s.nodes') }}</span>
              <span class="k8s-count-pill">{{ nodes.length }}</span>
            </div>
            <a-table
              :columns="nodeColumns"
              :data-source="nodes"
              :loading="resourcesLoading"
              :pagination="false"
              row-key="name"
              size="small"
              class="k8s-table"
            >
              <template #bodyCell="{ column, record }">
                <template v-if="column.key === 'name'">
                  <span class="k8s-strong">{{ record.name }}</span>
                </template>
                <template v-else-if="column.key === 'status'">
                  <span :class="['k8s-pill', 'status-badge', `status-badge-${nodeStatusTone(record.status)}`]">
                    {{ record.status || '—' }}
                  </span>
                </template>
                <template v-else-if="column.key === 'kubeletVersion'">
                  <code class="k8s-mono k8s-cell-muted">{{ record.kubeletVersion || '—' }}</code>
                </template>
                <template v-else-if="column.key === 'osImage'">
                  <span class="k8s-cell-muted">{{ record.osImage || '—' }}</span>
                </template>
              </template>
            </a-table>
          </section>
        </a-tab-pane>

        <a-tab-pane key="deployments" :tab="t('k8s.deployments')">
          <div class="k8s-pane-toolbar">
            <a-select
              :value="selectedNamespace"
              :options="namespaceOptions"
              class="k8s-namespace-select"
              :aria-label="t('k8s.namespace')"
              @change="handleNamespaceChange"
            />
            <span class="k8s-count-pill">{{ deployments.length }}</span>
          </div>
          <a-table
            :columns="deploymentColumns"
            :data-source="deployments"
            :loading="resourcesLoading"
            :pagination="{ pageSize: 10 }"
            :row-key="(record: KubernetesDeploymentInfo) => `${record.namespace}/${record.name}`"
            size="small"
            class="k8s-table"
          >
            <template #bodyCell="{ column, record }">
              <template v-if="column.key === 'name'">
                <span class="k8s-strong k8s-mono">{{ record.name }}</span>
              </template>
              <template v-else-if="column.key === 'namespace'">
                <span class="k8s-cell-muted k8s-mono">{{ record.namespace }}</span>
              </template>
              <template v-else-if="column.key === 'replicas'">
                <div class="k8s-replica-cell">
                  <a-progress
                    :percent="replicaPercent(record)"
                    :status="replicaStatus(record)"
                    :show-info="false"
                    size="small"
                    class="k8s-replica-bar"
                  />
                  <span class="k8s-mono k8s-cell-muted">{{ record.readyReplicas || 0 }}/{{ record.replicas }}</span>
                </div>
              </template>
              <template v-else-if="column.key === 'actions'">
                <div class="k8s-row-actions">
                  <a-tooltip :title="t('k8s.scale')">
                    <a-button
                      type="text"
                      size="small"
                      class="k8s-icon-btn"
                      :aria-label="t('k8s.scale')"
                      @click="scalingDeployment = record"
                    >
                      <template #icon><ExpandOutlined /></template>
                    </a-button>
                  </a-tooltip>
                  <a-tooltip :title="t('common.delete')">
                    <a-button
                      type="text"
                      size="small"
                      class="k8s-icon-btn is-danger"
                      :aria-label="t('common.delete')"
                      @click="handleDeleteDeployment(record)"
                    >
                      <template #icon><DeleteOutlined /></template>
                    </a-button>
                  </a-tooltip>
                </div>
              </template>
            </template>
          </a-table>
        </a-tab-pane>

        <a-tab-pane key="pods" :tab="t('k8s.pods')">
          <div class="k8s-pane-toolbar">
            <a-select
              :value="selectedNamespace"
              :options="namespaceOptions"
              class="k8s-namespace-select"
              :aria-label="t('k8s.namespace')"
              @change="handleNamespaceChange"
            />
            <span class="k8s-count-pill">{{ pods.length }}</span>
          </div>
          <a-table
            :columns="podColumns"
            :data-source="pods"
            :loading="resourcesLoading"
            :pagination="{ pageSize: 10 }"
            :row-key="(record: KubernetesPodInfo) => `${record.namespace}/${record.name}`"
            size="small"
            class="k8s-table"
          >
            <template #bodyCell="{ column, record }">
              <template v-if="column.key === 'name'">
                <button type="button" class="k8s-pod-link" :title="record.name" @click="selectedPod = record">
                  {{ record.name }}
                </button>
              </template>
              <template v-else-if="column.key === 'namespace'">
                <span class="k8s-cell-muted k8s-mono">{{ record.namespace }}</span>
              </template>
              <template v-else-if="column.key === 'phase'">
                <span :class="['k8s-pill', 'status-badge', `status-badge-${phaseTone(record.phase)}`]">
                  {{ record.phase || '—' }}
                </span>
              </template>
              <template v-else-if="column.key === 'podIP'">
                <span class="k8s-mono k8s-cell-muted">{{ record.podIP || '—' }}</span>
              </template>
              <template v-else-if="column.key === 'containers'">
                <div v-if="record.containers && record.containers.length" class="k8s-container-tags">
                  <a-tag
                    v-for="container in record.containers"
                    :key="container.name"
                    :class="['k8s-container-tag', 'status-badge', container.ready ? 'status-badge-success' : 'status-badge-error']"
                  >
                    {{ container.name }} ({{ container.restartCount }})
                  </a-tag>
                </div>
                <span v-else class="k8s-cell-muted">—</span>
              </template>
              <template v-else-if="column.key === 'actions'">
                <a-tooltip :title="t('common.detail')">
                  <a-button
                    type="text"
                    size="small"
                    class="k8s-icon-btn"
                    :aria-label="t('common.detail')"
                    @click="selectedPod = record"
                  >
                    <template #icon><EyeOutlined /></template>
                  </a-button>
                </a-tooltip>
              </template>
            </template>
          </a-table>
        </a-tab-pane>
      </a-tabs>
    </div>

    <DeployGatewayModal
      v-if="deployOpen"
      :cluster-id="cluster.id"
      :namespaces="namespaces"
      @close="deployOpen = false"
      @deployed="handleDeployed"
    />

    <ScaleDeploymentModal
      v-if="scalingDeployment"
      :cluster-id="cluster.id"
      :deployment="scalingDeployment"
      @close="scalingDeployment = null"
      @scaled="loadResources(selectedNamespace)"
    />

    <PodDetailModal
      v-if="selectedPod"
      :cluster-id="cluster.id"
      :pod="selectedPod"
      @close="selectedPod = null"
    />
  </a-drawer>
</template>