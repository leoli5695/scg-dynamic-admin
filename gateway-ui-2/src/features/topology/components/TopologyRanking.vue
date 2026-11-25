<script setup lang="ts">
/**
 * Top routes ranking aside — port of the ranking block in
 * gateway-ui/src/features/topology/index.tsx (top 5 routes by request volume,
 * click to focus, plus the client / route-status summary).
 */
import { computed } from 'vue'
import { useI18n } from 'vue-i18n'
import { ApiOutlined, FundOutlined, GlobalOutlined } from '@/components/icons'
import type { TopologyNode } from '@/api/topology'

const props = defineProps<{
  routes: TopologyNode[]
  selectedNodeId: string | null
  clients: number
  enabledRoutes: number
  configuredRoutes: number
}>()

const emit = defineEmits<{ select: [nodeId: string] }>()

const { t } = useI18n()

const maxRequests = computed(() => props.routes[0]?.metrics?.requestCount || 1)

function barWidth(route: TopologyNode): string {
  const requests = route.metrics?.requestCount || 0
  return `${Math.max(3, (requests / maxRequests.value) * 100)}%`
}

function errorRate(route: TopologyNode): number {
  return route.metrics?.errorRate || 0
}
</script>

<template>
  <aside class="topology-ranking" :aria-label="t('topology.top_routes')">
    <div class="topology-ranking-header">
      <span class="icon-tile icon-tile--sm"><FundOutlined /></span>
      <div>
        <span class="topology-ranking-title">{{ t('topology.top_routes') }}</span>
        <small>{{ t('topology.top_routes_hint') }}</small>
      </div>
    </div>

    <div class="topology-ranking-list">
      <button
        v-for="(route, index) in routes"
        :key="route.id"
        type="button"
        :class="['topology-route-rank', { 'is-selected': selectedNodeId === route.id }]"
        :title="route.name"
        @click="emit('select', route.id)"
      >
        <span class="topology-route-rank-topline">
          <span class="topology-route-position">{{ String(index + 1).padStart(2, '0') }}</span>
          <strong>{{ route.name }}</strong>
          <em :class="{ 'is-error': errorRate(route) > 5 }">{{ errorRate(route).toFixed(1) }}%</em>
        </span>
        <span class="topology-route-rank-meta">
          {{ (route.metrics?.requestCount || 0).toLocaleString() }} {{ t('topology.tooltip_requests') }}
        </span>
        <span class="topology-route-rank-line"><i :style="{ width: barWidth(route) }" /></span>
      </button>
    </div>

    <div class="topology-ranking-summary">
      <div>
        <GlobalOutlined />
        <span>{{ t('topology.clients') }}</span>
        <strong>{{ clients }}</strong>
      </div>
      <div>
        <ApiOutlined />
        <span>{{ t('topology.routes_status') }}</span>
        <strong>{{ enabledRoutes }}/{{ configuredRoutes }}</strong>
      </div>
    </div>
  </aside>
</template>