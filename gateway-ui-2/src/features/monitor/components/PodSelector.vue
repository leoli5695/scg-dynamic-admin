<script setup lang="ts">
/**
 * Pod picker with a summary strip for the currently selected pod, on the
 * Vue-native layer. Degrades to nothing when the instance has no K8s pods.
 */
import { computed } from 'vue'
import { useI18n } from 'vue-i18n'
import { ContainerOutlined, ReloadOutlined } from '@/components/icons'
import parseServerTime from '@/utils/serverTime'
import type { InstancePod } from '@/types/instance'
import VnBadge from '@/components/vn/VnBadge.vue'
import VnIconButton from '@/components/vn/VnIconButton.vue'
import { podTone, vnTone } from '../utils'

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
</script>

<template>
  <div class="vn-panel vn-monitor-pod-panel">
    <span class="vn-monitor-pod-label">
      <ContainerOutlined />
      {{ t('monitor.select_pod') }}
    </span>
    <a-select
      class="vn-monitor-pod-select"
      :value="selectedPodName"
      :options="options"
      :loading="loading"
      show-search
      option-filter-prop="label"
      :list-height="200"
      popup-class-name="vn-portal vnp-portal"
      @change="onSelect"
    />
    <VnIconButton :icon="ReloadOutlined" :label="t('common.refresh')" :disabled="loading" @click="emit('refresh')" />

    <template v-if="selectedPod">
      <span class="vn-monitor-pod-divider" aria-hidden="true" />
      <div class="vn-monitor-pod-meta">
        <VnBadge :tone="vnTone(podTone(selectedPod.phase))">{{ selectedPod.phase || 'Unknown' }}</VnBadge>
        <span class="vn-monitor-pod-meta-item">
          IP: <span class="vn-mono">{{ selectedPod.podIP || '—' }}</span>
        </span>
        <span v-if="selectedPod.startTime" class="vn-monitor-pod-meta-item">
          {{ t('monitor.pod_start_time') }}: {{ parseServerTime(selectedPod.startTime).toLocaleString() }}
        </span>
        <span v-if="readyContainers" class="vn-monitor-pod-meta-item">
          {{ t('monitor.container_ready') }}: {{ readyContainers.ready }}/{{ readyContainers.total }}
        </span>
      </div>
    </template>
  </div>
</template>