<script setup lang="ts">
/**
 * Kubernetes overview — cluster catalog with connection stats, filters, import
 * flow and the cluster detail drawer. Data flow mirrors the React page; the
 * presentation follows the login-aligned console skin.
 */
import { computed, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import {
  CheckCircleOutlined, ClusterOutlined, CloudServerOutlined, ContainerOutlined,
  ExclamationCircleOutlined, PlusOutlined, ReloadOutlined, SearchOutlined,
} from '@/components/icons'
import { useRequest } from '@/composables/useRequest'
import { ApiError } from '@/api/http'
import { deleteCluster, getClusters, refreshClusterConnection } from '@/api/kubernetes'
import type { KubernetesCluster } from '@/types/kubernetes'
import { message, modal } from '@/utils/feedback'
import StatsCard from './components/StatsCard.vue'
import ClusterCard from './components/ClusterCard.vue'
import ImportClusterModal from './components/ImportClusterModal.vue'
import ClusterDetailDrawer from './components/ClusterDetailDrawer.vue'
import './kubernetes.css'

const { t } = useI18n()

const clustersRequest = useRequest((signal) => getClusters({ signal }))
const clusters = computed<KubernetesCluster[]>(() => clustersRequest.data.value ?? [])

const search = ref('')
const connectionFilter = ref<'all' | 'connected' | 'other'>('all')
const importOpen = ref(false)
const selectedCluster = ref<KubernetesCluster | null>(null)
const refreshingId = ref<number | null>(null)

const initialLoading = computed(() => clustersRequest.loading.value && clusters.value.length === 0)
const showErrorState = computed(() => !!clustersRequest.error.value && clusters.value.length === 0)

const stats = computed(() => ({
  total: clusters.value.length,
  connected: clusters.value.filter((c) => c.connectionStatus === 'CONNECTED').length,
  nodes: clusters.value.reduce((sum, c) => sum + (c.nodeCount || 0), 0),
  pods: clusters.value.reduce((sum, c) => sum + (c.podCount || 0), 0),
}))

const visibleClusters = computed(() => {
  const query = search.value.trim().toLowerCase()
  return clusters.value.filter((cluster) => {
    const matchesStatus = connectionFilter.value === 'all'
      || (connectionFilter.value === 'connected'
        ? cluster.connectionStatus === 'CONNECTED'
        : cluster.connectionStatus !== 'CONNECTED')
    const matchesSearch = !query
      || [cluster.clusterName, cluster.clusterVersion]
        .some((value) => value?.toLowerCase().includes(query))
    return matchesStatus && matchesSearch
  })
})

const connectionOptions = computed(() => [
  { value: 'all', label: t('k8s.all_connections') },
  { value: 'connected', label: t('k8s.status_connected') },
  { value: 'other', label: t('k8s.needs_attention') },
])

const errorMessage = (error: unknown, fallbackKey: string): string =>
  (error instanceof ApiError && error.message ? error.message : t(fallbackKey))

async function handleRefreshConnection(cluster: KubernetesCluster) {
  refreshingId.value = cluster.id
  const hide = message.loading(t('k8s.refreshing'), 0)
  try {
    const refreshed = await refreshClusterConnection(cluster.id)
    hide()
    message.success(t('k8s.refresh_success'))
    clustersRequest.mutate((prev) => (prev ?? []).map((c) => (c.id === refreshed.id ? refreshed : c)))
    if (selectedCluster.value?.id === refreshed.id) selectedCluster.value = refreshed
  } catch (error) {
    hide()
    message.error(errorMessage(error, 'k8s.refresh_failed'))
  } finally {
    refreshingId.value = null
  }
}

function handleDeleteCluster(cluster: KubernetesCluster) {
  modal.confirm({
    title: t('k8s.delete_confirm_title'),
    content: t('k8s.delete_confirm_content', { name: cluster.clusterName }),
    okText: t('common.delete'),
    okType: 'primary',
    okButtonProps: { danger: true },
    cancelText: t('common.cancel'),
    onOk: async () => {
      try {
        await deleteCluster(cluster.id)
        message.success(t('k8s.delete_success'))
        if (selectedCluster.value?.id === cluster.id) selectedCluster.value = null
        clustersRequest.run()
      } catch (error) {
        message.error(errorMessage(error, 'k8s.delete_failed'))
      }
    },
  })
}

function resetFilters() {
  search.value = ''
  connectionFilter.value = 'all'
}
</script>

<template>
  <div class="k8s-page">
    <header class="page-header-modern k8s-page-header">
      <div class="page-header-left">
        <h3 class="page-title-main k8s-page-title">
          <span class="k8s-page-glyph" aria-hidden="true"><ClusterOutlined /></span>
          <span class="k8s-page-heading">
            <span class="k8s-page-eyebrow">{{ t('k8s.workspace_label') }}</span>
            <span class="k8s-page-name">{{ t('k8s.title') }}</span>
            <span class="k8s-page-subtitle">{{ t('k8s.description') }}</span>
          </span>
        </h3>
      </div>
      <div class="page-header-right">
        <a-button :loading="clustersRequest.loading.value" @click="clustersRequest.run()">
          <template #icon><ReloadOutlined /></template>
          {{ t('common.refresh') }}
        </a-button>
        <a-button type="primary" size="large" class="create-btn" @click="importOpen = true">
          <template #icon><PlusOutlined /></template>
          {{ t('k8s.import_cluster') }}
        </a-button>
      </div>
    </header>

    <div class="k8s-stats-grid">
      <StatsCard
        :label="t('k8s.stats_total')"
        :value="initialLoading ? '—' : stats.total"
        :caption="t('k8s.clusters')"
      >
        <template #icon><ClusterOutlined /></template>
      </StatsCard>
      <StatsCard
        tone="success"
        :label="t('k8s.stats_connected')"
        :value="initialLoading ? '—' : stats.connected"
        :caption="t('k8s.active_clusters')"
      >
        <template #icon><CheckCircleOutlined /></template>
      </StatsCard>
      <StatsCard
        :label="t('k8s.stats_nodes')"
        :value="initialLoading ? '—' : stats.nodes"
        :caption="t('k8s.total_nodes')"
      >
        <template #icon><CloudServerOutlined /></template>
      </StatsCard>
      <StatsCard
        tone="accent"
        :label="t('k8s.stats_pods')"
        :value="initialLoading ? '—' : stats.pods"
        :caption="t('k8s.running_pods')"
      >
        <template #icon><ContainerOutlined /></template>
      </StatsCard>
    </div>

    <div class="k8s-list-head">
      <h4 class="k8s-list-title">
        {{ t('k8s.cluster_list') }}
        <span class="k8s-count-pill">{{ visibleClusters.length }}</span>
      </h4>
      <div class="k8s-toolbar">
        <a-input
          v-model:value="search"
          allow-clear
          class="search-input k8s-search"
          :placeholder="t('k8s.search_placeholder')"
          :aria-label="t('k8s.search_placeholder')"
        >
          <template #prefix><SearchOutlined /></template>
        </a-input>
        <a-select
          v-model:value="connectionFilter"
          :options="connectionOptions"
          class="k8s-filter-select"
          :aria-label="t('k8s.connection_filter')"
        />
      </div>
    </div>

    <a-alert
      v-if="clustersRequest.error.value && clusters.length > 0"
      type="error"
      show-icon
      :message="t('k8s.load_failed')"
      class="k8s-inline-alert"
    >
      <template #action>
        <a-button size="small" @click="clustersRequest.run()">{{ t('common.refresh') }}</a-button>
      </template>
    </a-alert>

    <div v-if="showErrorState" class="k8s-state-panel">
      <span class="k8s-state-glyph is-error" aria-hidden="true"><ExclamationCircleOutlined /></span>
      <h3 class="k8s-state-title">{{ t('k8s.load_failed') }}</h3>
      <p class="k8s-state-hint">{{ clustersRequest.error.value?.message }}</p>
      <div class="k8s-state-actions">
        <a-button type="primary" :loading="clustersRequest.loading.value" @click="clustersRequest.run()">
          <template #icon><ReloadOutlined /></template>
          {{ t('common.refresh') }}
        </a-button>
      </div>
    </div>

    <div v-else-if="initialLoading" class="k8s-cluster-grid">
      <div v-for="n in 4" :key="n" class="k8s-skeleton-card">
        <a-skeleton active :title="{ width: '45%' }" :paragraph="{ rows: 3 }" />
      </div>
    </div>

    <div v-else-if="clusters.length === 0" class="k8s-state-panel">
      <span class="k8s-state-glyph" aria-hidden="true"><ClusterOutlined /></span>
      <h3 class="k8s-state-title">{{ t('k8s.empty') }}</h3>
      <p class="k8s-state-hint">{{ t('k8s.empty_description') }}</p>
      <div class="k8s-state-actions">
        <a-button type="primary" size="large" @click="importOpen = true">
          <template #icon><PlusOutlined /></template>
          {{ t('k8s.import_first') }}
        </a-button>
      </div>
    </div>

    <a-spin v-else :spinning="clustersRequest.loading.value">
      <div v-if="visibleClusters.length > 0" class="k8s-cluster-grid">
        <ClusterCard
          v-for="cluster in visibleClusters"
          :key="cluster.id"
          :cluster="cluster"
          :refreshing="refreshingId === cluster.id"
          @detail="selectedCluster = $event"
          @refresh="handleRefreshConnection"
          @delete="handleDeleteCluster"
        />
      </div>
      <div v-else class="k8s-state-panel">
        <span class="k8s-state-glyph" aria-hidden="true"><SearchOutlined /></span>
        <h3 class="k8s-state-title">{{ t('k8s.no_matches') }}</h3>
        <p class="k8s-state-hint">{{ t('k8s.search_placeholder') }}</p>
        <div class="k8s-state-actions">
          <a-button @click="resetFilters">{{ t('k8s.reset_filters') }}</a-button>
        </div>
      </div>
    </a-spin>

    <ImportClusterModal
      :open="importOpen"
      @close="importOpen = false"
      @imported="clustersRequest.run()"
    />

    <ClusterDetailDrawer
      v-if="selectedCluster"
      :cluster="selectedCluster"
      @close="selectedCluster = null"
    />
  </div>
</template>