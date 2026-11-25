<script setup lang="ts">
/**
 * Kubernetes overview — cluster catalog on the Vue-native design layer:
 * toolbar + compact metric rail + native segmented connection filter + card
 * grid. API calls and payloads are unchanged from the React page.
 */
import { computed, ref, useTemplateRef } from 'vue'
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
import VnIconButton from '@/components/vn/VnIconButton.vue'
import VnSegmented, { type VnSegmentOption } from '@/components/vn/VnSegmented.vue'
import VnStatRail, { type VnStatItem } from '@/components/vn/VnStatRail.vue'
import VnState from '@/components/vn/VnState.vue'
import VnToolbar from '@/components/vn/VnToolbar.vue'
import ClusterCard from './components/ClusterCard.vue'
import ImportClusterModal from './components/ImportClusterModal.vue'
import ClusterDetailDrawer from './components/ClusterDetailDrawer.vue'
import '@/styles/vue-native.css'
import '@/styles/vn-pages.css'
import './kubernetes-vue-native.css'

defineOptions({ name: 'KubernetesPage' })

const { t } = useI18n()

const clustersRequest = useRequest((signal) => getClusters({ signal }))
const clusters = computed<KubernetesCluster[]>(() => clustersRequest.data.value ?? [])

const search = ref('')
const connectionFilter = ref<'all' | 'connected' | 'other'>('all')
const importOpen = ref(false)
const selectedCluster = ref<KubernetesCluster | null>(null)
const refreshingId = ref<number | null>(null)
const searchInput = useTemplateRef<{ focus: () => void }>('searchInput')

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

const isFiltered = computed(() => search.value.trim() !== '' || connectionFilter.value !== 'all')

const connectionOptions = computed<VnSegmentOption<'all' | 'connected' | 'other'>[]>(() => [
  { key: 'all', label: t('k8s.all_connections'), count: stats.value.total, icon: ClusterOutlined },
  { key: 'connected', label: t('k8s.status_connected'), count: stats.value.connected, icon: CheckCircleOutlined },
  { key: 'other', label: t('k8s.needs_attention'), count: stats.value.total - stats.value.connected, icon: ExclamationCircleOutlined },
])

const statItems = computed<VnStatItem[]>(() => [
  { key: 'all', value: stats.value.total, label: t('k8s.stats_total'), icon: ClusterOutlined, active: connectionFilter.value === 'all' },
  { key: 'connected', value: stats.value.connected, label: t('k8s.stats_connected'), icon: CheckCircleOutlined, accent: 'var(--vn-ok)', active: connectionFilter.value === 'connected' },
  { key: 'nodes', value: stats.value.nodes, label: t('k8s.stats_nodes'), icon: CloudServerOutlined },
  { key: 'pods', value: stats.value.pods, label: t('k8s.stats_pods'), icon: ContainerOutlined, accent: 'var(--vn-info)' },
])

function selectStat(key: string) {
  if (key === 'all' || key === 'connected') connectionFilter.value = key
}

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
    wrapClassName: 'vn-portal',
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
  searchInput.value?.focus()
}
</script>

<template>
  <div class="k8s-page vn-k8s vn-scope vnp-scope">
    <VnToolbar :title="t('k8s.title')" :lede="t('k8s.description')">
      <template #actions>
        <VnIconButton
          :icon="ReloadOutlined"
          :label="t('common.refresh')"
          :disabled="clustersRequest.loading.value"
          @click="clustersRequest.run()"
        />
        <a-button type="primary" class="create-btn" @click="importOpen = true">
          <template #icon><PlusOutlined /></template>{{ t('k8s.import_cluster') }}
        </a-button>
      </template>
    </VnToolbar>

    <div class="vn-metarow">
      <VnSegmented v-model="connectionFilter" :options="connectionOptions" :label="t('k8s.connection_filter')" />
      <VnStatRail :items="statItems" :label="t('k8s.cluster_list')" @select="selectStat" />
      <a-input
        ref="searchInput"
        v-model:value="search"
        allow-clear
        class="vn-search"
        :placeholder="t('k8s.search_placeholder')"
        :aria-label="t('k8s.search_placeholder')"
      >
        <template #prefix><SearchOutlined /></template>
      </a-input>
      <span class="vn-filterbar-spacer" />
      <span class="vn-result-count" role="status">{{ visibleClusters.length }} {{ t('k8s.clusters') }}</span>
      <a-button v-if="isFiltered" type="text" size="small" @click="resetFilters">{{ t('common.reset') }}</a-button>
    </div>

    <a-alert
      v-if="clustersRequest.error.value && clusters.length > 0"
      type="error"
      show-icon
      :message="t('k8s.load_failed')"
      class="vn-k8s-inline-alert"
    >
      <template #action>
        <a-button size="small" @click="clustersRequest.run()">{{ t('common.refresh') }}</a-button>
      </template>
    </a-alert>

    <VnState
      v-if="showErrorState"
      :icon="ExclamationCircleOutlined"
      tone="err"
      :title="t('k8s.load_failed')"
      :hint="clustersRequest.error.value?.message"
    >
      <template #actions>
        <a-button type="primary" :loading="clustersRequest.loading.value" @click="clustersRequest.run()">
          <template #icon><ReloadOutlined /></template>{{ t('common.refresh') }}
        </a-button>
      </template>
    </VnState>

    <div v-else-if="initialLoading" class="vnp-grid">
      <div v-for="n in 4" :key="n" class="vn-panel vnp-skel-tile">
        <div class="vn-skel vn-skel-line" style="width: 58%" />
        <div class="vn-skel vn-skel-line" style="width: 38%" />
        <div class="vn-skel vn-skel-block" />
      </div>
    </div>

    <VnState
      v-else-if="clusters.length === 0"
      :icon="ClusterOutlined"
      :title="t('k8s.empty')"
      :hint="t('k8s.empty_description')"
    >
      <template #actions>
        <a-button type="primary" @click="importOpen = true">
          <template #icon><PlusOutlined /></template>{{ t('k8s.import_first') }}
        </a-button>
      </template>
    </VnState>

    <template v-else>
      <div v-if="visibleClusters.length > 0" class="vnp-grid">
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
      <VnState
        v-else
        :icon="SearchOutlined"
        :title="t('k8s.no_matches')"
        :hint="t('k8s.search_placeholder')"
      >
        <template #actions>
          <a-button @click="resetFilters">{{ t('k8s.reset_filters') }}</a-button>
        </template>
      </VnState>
    </template>

    <ImportClusterModal :open="importOpen" @close="importOpen = false" @imported="clustersRequest.run()" />

    <ClusterDetailDrawer
      v-if="selectedCluster"
      :cluster="selectedCluster"
      @close="selectedCluster = null"
    />
  </div>
</template>