<script setup lang="ts">
/**
 * Node right-click menu — port of the context menu in
 * gateway-ui/src/features/topology/index.tsx (route/service deep links,
 * open in new tab, focus node). Rendered above a click-away overlay.
 */
import { computed } from 'vue'
import { useI18n } from 'vue-i18n'
import {
  AimOutlined, CompassOutlined, DeploymentUnitOutlined, ExpandOutlined,
} from '@/components/icons'
import type { TopologyNode } from '@/api/topology'

const props = defineProps<{
  node: TopologyNode
  x: number
  y: number
}>()

const emit = defineEmits<{
  navigate: [payload: { kind: 'route' | 'service'; id: string; newTab: boolean }]
  focus: [nodeId: string]
  close: []
}>()

const { t } = useI18n()

const detailKind = computed<'route' | 'service' | null>(() => {
  if (props.node.type === 'route' && props.node.routeId) return 'route'
  if (props.node.type === 'service' && props.node.serviceId) return 'service'
  return null
})

const detailId = computed(() => (
  detailKind.value === 'route' ? props.node.routeId ?? '' : props.node.serviceId ?? ''
))

function go(newTab: boolean): void {
  if (!detailKind.value) return
  emit('navigate', { kind: detailKind.value, id: detailId.value, newTab })
}
</script>

<template>
  <div class="topology-menu-overlay" @click="emit('close')" @contextmenu.prevent="emit('close')" />
  <div
    class="lg-panel topology-menu"
    :style="{ left: `${x}px`, top: `${y}px` }"
    role="menu"
    @click.stop
  >
    <template v-if="detailKind">
      <button type="button" class="topology-menu-item" role="menuitem" @click="go(false)">
        <CompassOutlined v-if="detailKind === 'route'" />
        <DeploymentUnitOutlined v-else />
        {{ detailKind === 'route' ? t('topology.menu_route_detail') : t('topology.menu_service_detail') }}
      </button>
      <button type="button" class="topology-menu-item" role="menuitem" @click="go(true)">
        <ExpandOutlined />
        {{ detailKind === 'route' ? t('topology.menu_route_detail_new_tab') : t('topology.menu_service_detail_new_tab') }}
      </button>
      <span class="topology-menu-divider" />
    </template>
    <button type="button" class="topology-menu-item" role="menuitem" @click="emit('focus', node.id)">
      <AimOutlined />
      {{ t('topology.menu_focus') }}
    </button>
  </div>
</template>