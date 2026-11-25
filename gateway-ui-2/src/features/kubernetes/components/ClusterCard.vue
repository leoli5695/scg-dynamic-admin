<script setup lang="ts">
/**
 * Cluster card: identity, connection capsule, version/node/pod facts, resource
 * summary and the detail/refresh/delete actions. Whole card opens the drawer.
 */
import { computed } from 'vue'
import { useI18n } from 'vue-i18n'
import {
  CloudServerOutlined, ClusterOutlined, ContainerOutlined, DeleteOutlined,
  EyeOutlined, ReloadOutlined,
} from '@/components/icons'
import type { KubernetesCluster } from '@/types/kubernetes'
import { clusterStatusTextKey, clusterStatusTone, formatMemoryGb, formatNumber } from '../utils'

const props = defineProps<{
  cluster: KubernetesCluster
  refreshing?: boolean
}>()

const emit = defineEmits<{
  (e: 'detail', cluster: KubernetesCluster): void
  (e: 'refresh', cluster: KubernetesCluster): void
  (e: 'delete', cluster: KubernetesCluster): void
}>()

const { t } = useI18n()

const tone = computed(() => clusterStatusTone(props.cluster.connectionStatus))
const statusText = computed(() => t(clusterStatusTextKey(props.cluster.connectionStatus)))
const versionText = computed(
  () => (props.cluster.clusterVersion ? `v${props.cluster.clusterVersion}` : t('k8s.version_unknown')),
)
const nodeText = computed(() => `${formatNumber(props.cluster.nodeCount)} ${t('k8s.nodes')}`)
const podText = computed(() => `${formatNumber(props.cluster.podCount)} Pods`)
</script>

<template>
  <article
    :class="['k8s-cluster-card', tone === 'error' ? 'is-error' : '']"
    @click="emit('detail', cluster)"
  >
    <header class="k8s-cluster-head">
      <span class="k8s-cluster-glyph" aria-hidden="true"><ClusterOutlined /></span>
      <div class="k8s-cluster-info">
        <div class="k8s-cluster-name-row">
          <span class="k8s-cluster-name" :title="cluster.clusterName">{{ cluster.clusterName }}</span>
          <span :class="['k8s-pill', 'status-badge', `status-badge-${tone}`]">
            <i :class="['status-dot', `status-dot-${tone}`]" />
            {{ statusText }}
          </span>
        </div>
        <span class="k8s-cluster-server" :title="cluster.serverUrl">{{ cluster.serverUrl || '—' }}</span>
      </div>
    </header>

    <div class="k8s-cluster-meta">
      <span class="k8s-meta-pill">{{ versionText }}</span>
      <span class="k8s-meta-pill"><CloudServerOutlined />{{ nodeText }}</span>
      <span class="k8s-meta-pill"><ContainerOutlined />{{ podText }}</span>
    </div>

    <div class="k8s-cluster-resources">
      <div class="k8s-res-cell">
        <span class="k8s-res-label">{{ t('k8s.cpu_cores') }}</span>
        <span class="k8s-res-value">{{ formatNumber(cluster.totalCpuCores) }}</span>
      </div>
      <div class="k8s-res-cell">
        <span class="k8s-res-label">{{ t('k8s.memory') }}</span>
        <span class="k8s-res-value">{{ formatMemoryGb(cluster.totalMemoryGb) }}</span>
      </div>
      <div class="k8s-res-cell">
        <span class="k8s-res-label">{{ t('k8s.namespaces') }}</span>
        <span class="k8s-res-value">{{ formatNumber(cluster.namespaceCount) }}</span>
      </div>
    </div>

    <footer class="k8s-cluster-foot">
      <a-button type="link" class="k8s-open-link" @click.stop="emit('detail', cluster)">
        <template #icon><EyeOutlined /></template>
        {{ t('common.detail') }}
      </a-button>
      <div class="k8s-cluster-actions">
        <a-tooltip :title="t('common.refresh')">
          <a-button
            type="text"
            size="small"
            class="k8s-icon-btn"
            :loading="refreshing"
            :aria-label="`${t('common.refresh')} ${cluster.clusterName}`"
            @click.stop="emit('refresh', cluster)"
          >
            <template #icon><ReloadOutlined /></template>
          </a-button>
        </a-tooltip>
        <a-tooltip :title="t('common.delete')">
          <a-button
            type="text"
            size="small"
            class="k8s-icon-btn is-danger"
            :aria-label="`${t('common.delete')} ${cluster.clusterName}`"
            @click.stop="emit('delete', cluster)"
          >
            <template #icon><DeleteOutlined /></template>
          </a-button>
        </a-tooltip>
      </div>
    </footer>
  </article>
</template>