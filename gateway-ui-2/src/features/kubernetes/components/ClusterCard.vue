<script setup lang="ts">
/**
 * Cluster tile on the Vue-native layer: identity + connection badge, version /
 * node / pod chips, a fixed resource fact band and hover-stable icon actions.
 */
import { computed } from 'vue'
import { useI18n } from 'vue-i18n'
import {
  CloudServerOutlined, ClusterOutlined, ContainerOutlined, DeleteOutlined,
  EyeOutlined, ReloadOutlined,
} from '@/components/icons'
import type { KubernetesCluster } from '@/types/kubernetes'
import VnBadge from '@/components/vn/VnBadge.vue'
import VnIconButton from '@/components/vn/VnIconButton.vue'
import { clusterStatusTextKey, clusterStatusTone, formatMemoryGb, formatNumber, vnTone } from '../utils'

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

const tone = computed(() => vnTone(clusterStatusTone(props.cluster.connectionStatus)))
const statusText = computed(() => t(clusterStatusTextKey(props.cluster.connectionStatus)))
const versionText = computed(
  () => (props.cluster.clusterVersion ? `v${props.cluster.clusterVersion}` : t('k8s.version_unknown')),
)
</script>

<template>
  <article :class="['vn-panel', 'vnp-tile', 'k8s-cluster-card', 'vnp-rail-' + tone]">
    <div class="vnp-tile-head">
      <div class="vnp-tile-identity">
        <span :class="['vnp-tile-glyph', 'vn-ink-' + tone]" aria-hidden="true"><ClusterOutlined /></span>
        <button type="button" class="vnp-name" :title="cluster.clusterName" @click="emit('detail', cluster)">
          {{ cluster.clusterName }}
        </button>
      </div>
      <div class="vn-iconbtn-group">
        <VnIconButton :icon="EyeOutlined" :label="t('common.detail')" @click="emit('detail', cluster)" />
        <VnIconButton :icon="ReloadOutlined" :label="t('common.refresh')" :disabled="refreshing" @click="emit('refresh', cluster)" />
        <VnIconButton :icon="DeleteOutlined" :label="t('common.delete')" danger @click="emit('delete', cluster)" />
      </div>
    </div>

    <div class="vnp-tile-meta">
      <VnBadge :tone="tone">{{ statusText }}</VnBadge>
      <span class="vn-chip">{{ versionText }}</span>
      <span class="vn-chip" :title="t('k8s.nodes')"><CloudServerOutlined /><span class="vn-chip-num">{{ formatNumber(cluster.nodeCount) }}</span></span>
      <span class="vn-chip" title="Pods"><ContainerOutlined /><span class="vn-chip-num">{{ formatNumber(cluster.podCount) }}</span></span>
    </div>

    <div class="vnp-facts">
      <div class="vnp-fact">
        <span class="vnp-fact-label">{{ t('k8s.cpu_cores') }}</span>
        <span class="vnp-fact-value">{{ formatNumber(cluster.totalCpuCores) }}</span>
      </div>
      <div class="vnp-fact">
        <span class="vnp-fact-label">{{ t('k8s.memory') }}</span>
        <span class="vnp-fact-value">{{ formatMemoryGb(cluster.totalMemoryGb) }}</span>
      </div>
      <div class="vnp-fact">
        <span class="vnp-fact-label">{{ t('k8s.namespaces') }}</span>
        <span class="vnp-fact-value">{{ formatNumber(cluster.namespaceCount) }}</span>
      </div>
    </div>

    <p class="vnp-tile-sub vn-mono" :title="cluster.serverUrl">{{ cluster.serverUrl || '—' }}</p>
  </article>
</template>