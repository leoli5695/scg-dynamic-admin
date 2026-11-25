<script setup lang="ts">
/**
 * Traffic topology tab — Vue port of gateway-ui/src/features/topology/index.tsx.
 * Clients → gateway → routes → services graph with focus mode, search, smart
 * aggregation, node deep links and 30s auto refresh. Rendered on the Vue-native
 * design layer; the graph itself and API calls are unchanged.
 */
import { computed, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import { useRouter } from 'vue-router'
import {
  ApartmentOutlined, ControlOutlined, EyeOutlined, FundOutlined, ReloadOutlined, SearchOutlined,
} from '@/components/icons'
import { usePolling } from '@/composables/useRequest'
import { getTopology } from '@/api/topology'
import type { TopologyGraph, TopologyNode } from '@/api/topology'
import VnIconButton from '@/components/vn/VnIconButton.vue'
import VnSegmented, { type VnSegmentOption } from '@/components/vn/VnSegmented.vue'
import VnStatRail, { type VnStatItem } from '@/components/vn/VnStatRail.vue'
import VnToolbar from '@/components/vn/VnToolbar.vue'
import { useInstanceDetail } from '../instance-detail/context'
import {
  getTopologyChartHeight, getTopologyDisplayGraph, isSameTopologyContent,
  type ChartFilterOptions, type TranslateFn,
} from './chartOption'
import { TOPOLOGY_COLORS } from './palette'
import TopologyChart from './components/TopologyChart.vue'
import TopologyRanking from './components/TopologyRanking.vue'
import NodeContextMenu from './components/NodeContextMenu.vue'
import '@/styles/vue-native.css'
import '@/features/services/services-vue-native.css'
import './topology.css'

defineOptions({ name: 'TopologyPage' })

const { t } = useI18n()
const router = useRouter()
const detail = useInstanceDetail()

const instanceId = computed(() => detail.instance.instanceId)

const timeRange = ref(60)
const autoRefresh = ref(true)
const showDisabledRoutes = ref(true)
const showOnlyActiveRoutes = ref(false)
const smartAggregate = ref(true)
const searchKeyword = ref('')
const selectedNodeId = ref<string | null>(null)
const menu = ref<{ node: TopologyNode; x: number; y: number } | null>(null)

/**
 * Rendered graph. Kept separate from the request payload so an unchanged 30s
 * poll keeps the previous object identity and does not rebuild the chart.
 */
const graph = ref<TopologyGraph>()

const topologyRequest = usePolling(
  (signal) => getTopology(instanceId.value, { minutes: timeRange.value }, { signal }),
  {
    intervalMs: 30_000,
    paused: () => !autoRefresh.value,
    deps: [instanceId, timeRange],
    onSuccess: (data) => {
      graph.value = graph.value && isSameTopologyContent(graph.value, data) ? graph.value : data
    },
  },
)

const translate: TranslateFn = (key, named) => (named ? t(key, named) : t(key))

const filters = computed<ChartFilterOptions>(() => ({
  showDisabledRoutes: showDisabledRoutes.value,
  showOnlyActiveRoutes: showOnlyActiveRoutes.value,
  searchKeyword: searchKeyword.value,
  selectedNodeId: selectedNodeId.value,
  aggregate: smartAggregate.value,
}))

const displayGraph = computed(() => (
  graph.value ? getTopologyDisplayGraph(graph.value, filters.value, translate) : undefined
))
const chartHeight = computed(() => (
  displayGraph.value ? getTopologyChartHeight(displayGraph.value) : 500
))
const aggregateActive = computed(() => (
  displayGraph.value?.nodes.some((node) => node.id.startsWith('aggregate:')) ?? false
))
const topRoutes = computed(() => (graph.value?.nodes ?? [])
  .filter((node) => node.type === 'route')
  .sort((a, b) => (b.metrics?.requestCount || 0) - (a.metrics?.requestCount || 0))
  .slice(0, 5))

const metrics = computed(() => graph.value?.metrics)
const isEmpty = computed(() => Boolean(graph.value) && (graph.value?.nodes?.length || 0) === 0)
const initialLoading = computed(() => topologyRequest.loading.value && !graph.value)

const rangeOptions = computed<VnSegmentOption<string>[]>(() => [
  { key: '15', label: t('topology.last_15m') },
  { key: '30', label: t('topology.last_30m') },
  { key: '60', label: t('topology.last_1h') },
  { key: '180', label: t('topology.last_3h') },
  { key: '360', label: t('topology.last_6h') },
])

const rangeSeg = computed<string>({
  get: () => String(timeRange.value),
  set: (value) => { timeRange.value = Number(value) || 60 },
})

const statItems = computed<VnStatItem[]>(() => {
  const m = metrics.value
  return [
    { key: 'requests', value: m?.totalRequests ?? 0, label: t('topology.total_requests'), icon: FundOutlined },
    { key: 'rps', value: Number((m?.requestsPerSecond ?? 0).toFixed(1)), label: t('topology.rps') },
    { key: 'latency', value: Number((m?.avgLatency ?? 0).toFixed(1)), label: t('topology.avg_latency') },
    { key: 'error', value: Number((m?.errorRate ?? 0).toFixed(1)), label: t('topology.error_rate'), accent: (m?.errorRate ?? 0) > 5 ? 'var(--vn-err)' : 'var(--vn-ok)' },
  ]
})

const legendItems = computed(() => [
  { key: 'gateway', shape: 'circle', color: TOPOLOGY_COLORS.blue, label: t('topology.node_gateway') },
  { key: 'route-enabled', shape: 'diamond', color: TOPOLOGY_COLORS.cyan, label: t('topology.node_route_enabled') },
  { key: 'route-disabled', shape: 'diamond', disabled: true, color: TOPOLOGY_COLORS.muted, label: t('topology.node_route_disabled') },
  { key: 'service-discovery', shape: 'rect', color: TOPOLOGY_COLORS.violet, label: t('topology.node_service_discovery') },
  { key: 'service-static', shape: 'rect', color: TOPOLOGY_COLORS.mint, label: t('topology.node_service_static') },
  { key: 'client', shape: 'circle', color: TOPOLOGY_COLORS.amber, label: t('topology.node_client') },
])

function onNodeClick(nodeId: string): void {
  selectedNodeId.value = selectedNodeId.value === nodeId ? null : nodeId
}

function selectFromRanking(nodeId: string): void {
  selectedNodeId.value = selectedNodeId.value === nodeId ? null : nodeId
}

function onNavigate(payload: { kind: 'route' | 'service'; id: string; newTab: boolean }): void {
  menu.value = null
  const tab = payload.kind === 'route' ? 'routes' : 'services'
  const path = `/instances/${instanceId.value}/${tab}?highlight=${encodeURIComponent(payload.id)}&from=topology`
  if (payload.newTab) {
    window.open(path, '_blank')
    return
  }
  router.push(path)
}
</script>

<template>
  <div class="topology-page vn-scope">
    <VnToolbar :title="t('topology.title')" :lede="t('topology.subtitle')">
      <template #actions>
        <a-button
          :type="autoRefresh ? 'primary' : 'default'"
          @click="autoRefresh = !autoRefresh"
        >
          <template #icon><ReloadOutlined /></template>
          {{ autoRefresh ? t('topology.auto_refresh_on') : t('topology.auto_refresh_off') }}
        </a-button>
        <VnIconButton :icon="ReloadOutlined" :label="t('common.refresh')" :disabled="topologyRequest.loading.value" @click="topologyRequest.run()" />
      </template>
    </VnToolbar>

    <div class="vn-metarow">
      <VnSegmented v-model="rangeSeg" :options="rangeOptions" :label="t('topology.title')" />
      <VnStatRail :items="statItems" :label="t('topology.traffic_flow')" />
      <a-input
        v-model:value="searchKeyword"
        class="vn-search"
        :placeholder="t('topology.search_placeholder')"
        allow-clear
      >
        <template #prefix><SearchOutlined /></template>
      </a-input>
      <span class="vn-filterbar-spacer" />
      <a-button
        v-if="selectedNodeId"
        type="primary"
        size="small"
        @click="selectedNodeId = null"
      >
        <template #icon><EyeOutlined /></template>
        {{ t('topology.exit_focus') }}
      </a-button>
    </div>

    <a-alert
      v-if="topologyRequest.error.value"
      class="topology-error"
      type="error"
      show-icon
      closable
      :message="t('common.error')"
      :description="topologyRequest.error.value.message"
      @close="topologyRequest.error.value = undefined"
    />

    <section class="vn-panel topology-canvas">
      <div class="topology-canvas-header">
        <span class="topology-canvas-title"><ApartmentOutlined /> {{ t('topology.traffic_flow') }}</span>
        <div class="topology-canvas-status">
          <span class="topology-toggle">
            <a-switch v-model:checked="showDisabledRoutes" size="small" />
            <span>{{ t('topology.show_disabled_routes') }}</span>
          </span>
          <span class="topology-toggle">
            <a-switch v-model:checked="showOnlyActiveRoutes" size="small" />
            <span>{{ t('topology.active_only') }}</span>
          </span>
          <span class="topology-toggle is-emphasized">
            <ControlOutlined />
            <a-switch v-model:checked="smartAggregate" size="small" />
            <span>{{ t('topology.smart_aggregate') }}</span>
          </span>
          <span v-if="aggregateActive" class="vn-chip topology-aggregate-badge">
            {{ t('topology.aggregate_badge') }}
          </span>
          <span class="topology-live"><i />{{ t('topology.live') }}</span>
        </div>
      </div>

      <div class="topology-chart-hint">
        <span>{{ t('topology.canvas_hint') }}</span>
        <span class="topology-visible-count">
          {{ t('topology.visible_nodes', { visible: displayGraph?.nodes.length ?? 0, total: graph?.nodes.length ?? 0 }) }}
        </span>
      </div>

      <div class="topology-workspace">
        <div class="topology-chart-region">
          <TopologyChart
            :data="graph"
            :filters="filters"
            :height="chartHeight"
            @node-click="onNodeClick"
            @blank-click="menu = null"
            @node-menu="menu = $event"
          />
          <div v-if="initialLoading" class="topology-state-overlay">
            <a-spin size="large" />
            <span class="topology-state-hint">{{ t('topology.loading') }}</span>
          </div>
          <div v-else-if="isEmpty" class="topology-state-overlay">
            <a-empty :description="t('topology.no_data')" />
          </div>
        </div>

        <TopologyRanking
          v-if="topRoutes.length > 0"
          :routes="topRoutes"
          :selected-node-id="selectedNodeId"
          :clients="metrics?.uniqueClients ?? 0"
          :enabled-routes="metrics?.enabledRoutes ?? 0"
          :configured-routes="metrics?.configuredRoutes ?? 0"
          @select="selectFromRanking"
        />
      </div>

      <div class="topology-legend">
        <span class="topology-legend-title">{{ t('topology.legend') }}</span>
        <span v-for="item in legendItems" :key="item.key" class="topology-legend-item">
          <i :class="[`legend-${item.shape}`, { 'is-disabled': item.disabled }]" :style="{ background: item.color }" />
          {{ item.label }}
        </span>
      </div>
    </section>

    <NodeContextMenu
      v-if="menu"
      :node="menu.node"
      :x="menu.x"
      :y="menu.y"
      @navigate="onNavigate"
      @focus="selectedNodeId = $event; menu = null"
      @close="menu = null"
    />
  </div>
</template>