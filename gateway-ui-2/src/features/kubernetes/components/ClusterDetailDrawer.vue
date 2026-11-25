<script setup lang="ts">
/**
 * Cluster detail drawer on the Vue-native layer: overview (metadata + nodes),
 * deployments and pods. Namespace is passed explicitly on every reload;
 * failures keep the previous data (connection problems surface on the status).
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
import { copyText } from '@/utils/clipboard'
import VnBadge from '@/components/vn/VnBadge.vue'
import VnIconButton from '@/components/vn/VnIconButton.vue'
import {
  clusterStatusTextKey, clusterStatusTone, nodeStatusTone, phaseTone, vnTone,
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

const tone = computed(() => vnTone(clusterStatusTone(props.cluster.connectionStatus)))
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
  { title: t('common.actions'), key: 'actions', width: 90, align: 'right' },
])

const podColumns = computed(() => [
  { title: t('k8s.pod_name'), dataIndex: 'name', key: 'name', ellipsis: true },
  { title: t('k8s.namespace'), dataIndex: 'namespace', key: 'namespace', width: 120 },
  { title: t('k8s.phase'), dataIndex: 'phase', key: 'phase', width: 96 },
  { title: t('k8s.pod_ip'), dataIndex: 'podIP', key: 'podIP', width: 130 },
  { title: t('k8s.containers'), dataIndex: 'containers', key: 'containers' },
  { title: t('common.actions'), key: 'actions', width: 70, align: 'right' },
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
    wrapClassName: 'vn-portal',
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

async function copyServerUrl() {
  if (!props.cluster.serverUrl) return
  const ok = await copyText(props.cluster.serverUrl)
  if (ok) message.success(t('k8s.copied'))
  else message.error(t('message.copy_failed'))
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
    root-class-name="vn-portal vnp-portal vnp-drawer"
    @close="emit('close')"
  >
    <div class="vnp-detail">
      <header class="vnp-detail-head">
        <div class="vnp-detail-head-left">
          <span class="vnp-detail-glyph" aria-hidden="true"><ClusterOutlined /></span>
          <div style="min-width: 0">
            <h4 class="vnp-detail-title">{{ cluster.clusterName }}</h4>
            <div class="vnp-detail-idrow">
              <VnBadge :tone="tone">{{ statusText }}</VnBadge>
              <span class="vn-mono vnp-detail-id" :title="cluster.serverUrl">{{ versionText }} · {{ cluster.serverUrl || '—' }}</span>
            </div>
          </div>
        </div>
        <div class="vn-iconbtn-group">
          <a-button
            size="small"
            :disabled="cluster.connectionStatus !== 'CONNECTED'"
            @click="deployOpen = true"
          >
            <template #icon><PlayCircleOutlined /></template>{{ t('k8s.deploy_gateway') }}
          </a-button>
          <VnIconButton :icon="ReloadOutlined" :label="t('common.refresh')" :disabled="resourcesLoading" @click="loadResources(selectedNamespace)" />
          <VnIconButton :icon="CloseOutlined" :label="t('common.close')" @click="emit('close')" />
        </div>
      </header>

      <div class="vnp-detail-scroll">
        <a-tabs v-model:activeKey="activeTab">
          <a-tab-pane key="overview" :tab="t('k8s.overview')">
            <section class="vnp-section">
              <div class="vnp-section-head">{{ t('k8s.cluster_info') }}</div>
              <div class="vnp-kv">
                <div class="vnp-kv-item vnp-kv-wide">
                  <span class="vnp-kv-label">{{ t('k8s.server_url') }}</span>
                  <span class="vnp-kv-value vn-mono">
                    {{ cluster.serverUrl || '—' }}
                    <VnIconButton v-if="cluster.serverUrl" :icon="CopyOutlined" :label="t('common.copy')" inline @click="copyServerUrl" />
                  </span>
                </div>
                <div class="vnp-kv-item">
                  <span class="vnp-kv-label">{{ t('k8s.context') }}</span>
                  <span class="vnp-kv-value">{{ cluster.contextName || '—' }}</span>
                </div>
                <div class="vnp-kv-item">
                  <span class="vnp-kv-label">{{ t('k8s.cluster_version') }}</span>
                  <span class="vnp-kv-value">{{ versionText }}</span>
                </div>
                <div class="vnp-kv-item">
                  <span class="vnp-kv-label">{{ t('k8s.last_checked') }}</span>
                  <span class="vnp-kv-value">{{ lastCheckedText }}</span>
                </div>
                <div class="vnp-kv-item">
                  <span class="vnp-kv-label">{{ t('k8s.status') }}</span>
                  <span class="vnp-kv-value"><VnBadge :tone="tone">{{ statusText }}</VnBadge></span>
                </div>
              </div>
            </section>

            <section class="vnp-section" style="margin-top: 12px">
              <div class="vnp-section-head"><span>{{ t('k8s.nodes') }}</span><span class="vnp-count">{{ nodes.length }}</span></div>
              <a-table
                :columns="nodeColumns"
                :data-source="nodes"
                :loading="resourcesLoading"
                :pagination="false"
                row-key="name"
                size="small"
              >
                <template #bodyCell="{ column, record }">
                  <template v-if="column.key === 'name'">
                    <span class="vnp-strong">{{ record.name }}</span>
                  </template>
                  <template v-else-if="column.key === 'status'">
                    <VnBadge :tone="vnTone(nodeStatusTone(record.status))">{{ record.status || '—' }}</VnBadge>
                  </template>
                  <template v-else-if="column.key === 'kubeletVersion'">
                    <span class="vn-mono vnp-cell-muted">{{ record.kubeletVersion || '—' }}</span>
                  </template>
                  <template v-else-if="column.key === 'osImage'">
                    <span class="vnp-cell-muted">{{ record.osImage || '—' }}</span>
                  </template>
                </template>
              </a-table>
            </section>
          </a-tab-pane>

          <a-tab-pane key="deployments" :tab="t('k8s.deployments')">
            <div class="vn-k8s-pane-toolbar">
              <a-select
                :value="selectedNamespace"
                :options="namespaceOptions"
                class="vn-k8s-namespace-select"
                :aria-label="t('k8s.namespace')"
                @change="handleNamespaceChange"
              />
              <span class="vn-result-count">{{ deployments.length }}</span>
            </div>
            <a-table
              :columns="deploymentColumns"
              :data-source="deployments"
              :loading="resourcesLoading"
              :pagination="{ pageSize: 10 }"
              :row-key="(record: KubernetesDeploymentInfo) => `${record.namespace}/${record.name}`"
              size="small"
            >
              <template #bodyCell="{ column, record }">
                <template v-if="column.key === 'name'">
                  <span class="vnp-strong vn-mono">{{ record.name }}</span>
                </template>
                <template v-else-if="column.key === 'namespace'">
                  <span class="vnp-cell-muted vn-mono">{{ record.namespace }}</span>
                </template>
                <template v-else-if="column.key === 'replicas'">
                  <div class="vn-k8s-replica-cell">
                    <a-progress
                      :percent="replicaPercent(record)"
                      :status="replicaStatus(record)"
                      :show-info="false"
                      size="small"
                    />
                    <span class="vn-mono vnp-cell-muted">{{ record.readyReplicas || 0 }}/{{ record.replicas }}</span>
                  </div>
                </template>
                <template v-else-if="column.key === 'actions'">
                  <div class="vn-iconbtn-group">
                    <VnIconButton :icon="ExpandOutlined" :label="t('k8s.scale')" @click="scalingDeployment = record" />
                    <VnIconButton :icon="DeleteOutlined" :label="t('common.delete')" danger @click="handleDeleteDeployment(record)" />
                  </div>
                </template>
              </template>
            </a-table>
          </a-tab-pane>

          <a-tab-pane key="pods" :tab="t('k8s.pods')">
            <div class="vn-k8s-pane-toolbar">
              <a-select
                :value="selectedNamespace"
                :options="namespaceOptions"
                class="vn-k8s-namespace-select"
                :aria-label="t('k8s.namespace')"
                @change="handleNamespaceChange"
              />
              <span class="vn-result-count">{{ pods.length }}</span>
            </div>
            <a-table
              :columns="podColumns"
              :data-source="pods"
              :loading="resourcesLoading"
              :pagination="{ pageSize: 10 }"
              :row-key="(record: KubernetesPodInfo) => `${record.namespace}/${record.name}`"
              size="small"
            >
              <template #bodyCell="{ column, record }">
                <template v-if="column.key === 'name'">
                  <button type="button" class="vnp-name" :title="record.name" @click="selectedPod = record">{{ record.name }}</button>
                </template>
                <template v-else-if="column.key === 'namespace'">
                  <span class="vnp-cell-muted vn-mono">{{ record.namespace }}</span>
                </template>
                <template v-else-if="column.key === 'phase'">
                  <VnBadge :tone="vnTone(phaseTone(record.phase))">{{ record.phase || '—' }}</VnBadge>
                </template>
                <template v-else-if="column.key === 'podIP'">
                  <span class="vn-mono vnp-cell-muted">{{ record.podIP || '—' }}</span>
                </template>
                <template v-else-if="column.key === 'containers'">
                  <div v-if="record.containers && record.containers.length" class="vn-k8s-container-tags">
                    <span
                      v-for="container in record.containers"
                      :key="container.name"
                      :class="['vn-chip', container.ready ? 'vn-ink-ok' : 'vn-ink-err']"
                    >
                      {{ container.name }} ({{ container.restartCount }})
                    </span>
                  </div>
                  <span v-else class="vnp-cell-muted">—</span>
                </template>
                <template v-else-if="column.key === 'actions'">
                  <VnIconButton :icon="EyeOutlined" :label="t('common.detail')" @click="selectedPod = record" />
                </template>
              </template>
            </a-table>
          </a-tab-pane>
        </a-tabs>
      </div>
    </div>

    <DeployGatewayModal
      :open="deployOpen"
      :cluster-id="cluster.id"
      :namespaces="namespaces"
      @close="deployOpen = false"
      @deployed="handleDeployed"
    />

    <ScaleDeploymentModal
      :open="!!scalingDeployment"
      :cluster-id="cluster.id"
      :deployment="scalingDeployment"
      @close="scalingDeployment = null"
      @scaled="loadResources(selectedNamespace)"
    />

    <PodDetailModal
      :open="!!selectedPod"
      :cluster-id="cluster.id"
      :pod="selectedPod"
      @close="selectedPod = null"
    />
  </a-drawer>
</template>