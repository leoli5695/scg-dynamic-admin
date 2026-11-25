<script setup lang="ts">
/**
 * Pod picker with a summary strip for the currently selected pod.
 * Port of gateway-ui components/PodSelector.tsx; degrades to nothing when the
 * instance has no K8s pods (the page then shows aggregated metrics).
 */
import { computed } from 'vue'
import { useI18n } from 'vue-i18n'
import { ContainerOutlined, ReloadOutlined } from '@/components/icons'
import parseServerTime from '@/utils/serverTime'
import type { InstancePod } from '@/types/instance'
import { podTone } from '../utils'

const props = defineProps<{
  pods: InstancePod[]
  loading: boolean
  selectedPodName?: string
}>()

const emit = defineEmits<{
  select: [podName: string]
  refresh: []
}>()

const { t } = useI18n()

const namedPods = computed(() => props.pods.filter((pod): pod is InstancePod & { name: string } => Boolean(pod.name)))
const selectedPod = computed(() => namedPods.value.find((pod) => pod.name === props.selectedPodName))
const options = computed(() => namedPods.value.map((pod) => ({ value: pod.name, label: pod.name })))

const readyContainers = computed(() => {
  const containers = selectedPod.value?.containers
  if (!containers?.length) return undefined
  return { ready: containers.filter((container) => container.ready).length, total: containers.length }
})

function onSelect(value: unknown): void {
  if (typeof value === 'string') emit('select', value)
}

function getPopupContainer(trigger: HTMLElement): HTMLElement {
  return (trigger.parentElement as HTMLElement) || document.body
}
</script>

<template>
  <div class="lg-panel monitor-pod-panel">
    <span class="monitor-pod-label">
      <span class="icon-tile icon-tile--sm"><ContainerOutlined /></span>
      {{ t('monitor.select_pod') }}
    </span>
    <a-select
      class="monitor-pod-select"
      :value="selectedPodName"
      :options="options"
      :loading="loading"
      show-search
      option-filter-prop="label"
      :list-height="200"
      :get-popup-container="getPopupContainer"
      @change="onSelect"
    >
      <template #suffixIcon>
        <span class="monitor-pod-refresh" :title="t('common.refresh')" @click.stop="emit('refresh')">
          <ReloadOutlined :spin="loading" />
        </span>
      </template>
    </a-select>

    <template v-if="selectedPod">
      <span class="monitor-pod-divider" aria-hidden="true" />
      <div class="monitor-pod-meta">
        <span :class="['status-badge', `status-badge-${podTone(selectedPod.phase)}`]">
          <i :class="['status-dot', `status-dot-${podTone(selectedPod.phase)}`]" />
          {{ selectedPod.phase || 'Unknown' }}
        </span>
        <span class="monitor-pod-meta-item">
          IP: <code class="monitor-code">{{ selectedPod.podIP || '—' }}</code>
        </span>
        <span v-if="selectedPod.startTime" class="monitor-pod-meta-item">
          {{ t('monitor.pod_start_time') }}: {{ parseServerTime(selectedPod.startTime).toLocaleString() }}
        </span>
        <span v-if="readyContainers" class="monitor-pod-meta-item">
          {{ t('monitor.container_ready') }}: {{ readyContainers.ready }}/{{ readyContainers.total }}
        </span>
      </div>
    </template>
  </div>
</template>