<script setup lang="ts">
/**
 * Topology graph canvas — vue-echarts/SVG port of the ECharts block in
 * gateway-ui/src/features/topology/index.tsx. The scroll viewport keeps a fixed
 * height while the inner canvas grows with the node count, so dense graphs stay
 * readable instead of being squeezed into the panel.
 */
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import VChart from 'vue-echarts'
import { use } from 'echarts/core'
import { GraphChart } from 'echarts/charts'
import { TooltipComponent } from 'echarts/components'
import { LabelLayout } from 'echarts/features'
import { SVGRenderer } from 'echarts/renderers'
import type { TopologyGraph, TopologyNode } from '@/api/topology'
import { buildChartOption, type ChartFilterOptions } from '../chartOption'
import { CHART_INIT_OPTIONS } from '../palette'

use([SVGRenderer, GraphChart, TooltipComponent, LabelLayout])

/** Stable identity: a new object here would make vue-echarts re-init the chart. */
const UPDATE_OPTIONS = { notMerge: true }

const props = defineProps<{
  data?: TopologyGraph
  filters: ChartFilterOptions
  height: number
}>()

const emit = defineEmits<{
  'node-click': [nodeId: string]
  'blank-click': []
  'node-menu': [payload: { node: TopologyNode; x: number; y: number }]
}>()

const { t } = useI18n()

const viewport = ref<HTMLDivElement>()
const width = ref(1100)
let observer: ResizeObserver | undefined

onMounted(() => {
  if (!viewport.value) return
  observer = new ResizeObserver(() => {
    width.value = Math.max(1000, viewport.value?.clientWidth || 1000)
  })
  observer.observe(viewport.value)
})

onBeforeUnmount(() => {
  observer?.disconnect()
  observer = undefined
})

const option = computed(() => (
  props.data ? buildChartOption(props.data, props.filters, width.value, t) : null
))

function onClick(raw: unknown): void {
  const params = raw as { dataType?: string; data?: { id?: string } }
  if (params.dataType === 'node' && params.data?.id) {
    emit('node-click', params.data.id)
  }
  emit('blank-click')
}

function onContextMenu(raw: unknown): void {
  const params = raw as {
    dataType?: string
    data?: { data?: TopologyNode }
    event?: { event?: MouseEvent }
  }
  const node = params.dataType === 'node' ? params.data?.data : undefined
  if (node && (node.type === 'route' || node.type === 'service')) {
    emit('node-menu', {
      node,
      x: params.event?.event?.clientX ?? 0,
      y: params.event?.event?.clientY ?? 0,
    })
    return
  }
  emit('blank-click')
}
</script>

<template>
  <div ref="viewport" class="topology-chart-viewport" @contextmenu.prevent>
    <v-chart
      v-if="option"
      class="topology-chart"
      :style="{ width: `${width}px`, height: `${height}px` }"
      :option="option"
      :init-options="CHART_INIT_OPTIONS"
      :update-options="UPDATE_OPTIONS"
      autoresize
      @click="onClick"
      @contextmenu="onContextMenu"
    />
  </div>
</template>