<script setup lang="ts">
/**
 * Resources tab — cluster nodes and pods overview.
 * Uses GET /api/instances/{id}/resource-metrics; handles local mode.
 */
import { useI18n } from 'vue-i18n'
import { ReloadOutlined } from '@/components/icons'
import { useRequest } from '@/composables/useRequest'
import { getInstanceResourceMetrics } from '@/api/instances'
import { useInstanceDetail } from '../context'

const { t } = useI18n()
const detail = useInstanceDetail()

const metricsRequest = useRequest(
  (signal) => getInstanceResourceMetrics(detail.instance.id, { signal }),
  { deps: [() => detail.instance.id] },
)
const metrics = metricsRequest.data
</script>

<template>
  <div class="card-base resources-panel">
    <div class="panel-head">
      <div class="panel-head-left">
        <span class="panel-title">{{ t('resources.title') }}</span>
      </div>
      <a-button :loading="metricsRequest.loading.value" @click="metricsRequest.run()">
        <template #icon><ReloadOutlined /></template>
        {{ t('common.refresh') }}
      </a-button>
    </div>

    <div v-if="metricsRequest.loading.value && !metrics" class="panel-state">
      <a-spin />
    </div>
    <div v-else-if="!metrics" class="panel-state">
      {{ t('resources.no_data') }}
    </div>
    <a-alert
      v-else-if="metrics.mode === 'local'"
      type="info"
      show-icon
      :message="t('resources.local_mode')"
      :description="t('resources.local_mode_desc')"
    />
    <template v-else>
      <!-- Nodes -->
      <section class="resource-section">
        <div class="resource-section-head">
          <span class="resource-section-title">{{ t('resources.nodes') }}</span>
          <div class="resource-summary">
            <span class="status-badge status-badge-neutral">
              {{ t('resources.total_nodes') }}: {{ metrics.nodes?.total || 0 }}
            </span>
          </div>
        </div>
        <div class="resource-grid">
          <div v-for="(node, idx) in metrics.nodes?.list" :key="idx" class="resource-card">
            <span class="resource-name" :title="node.name">{{ node.name }}</span>
            <span :class="['status-badge', node.ready ? 'status-badge-success' : 'status-badge-error']">
              <i :class="['status-dot', node.ready ? 'status-dot-success' : 'status-dot-error']" />
              {{ node.ready ? 'Ready' : 'Not Ready' }}
            </span>
            <template v-if="node.allocatable">
              <span class="resource-line">
                <span class="label">CPU</span>{{ node.allocatable.cpu }}
              </span>
              <span class="resource-line">
                <span class="label">MEM</span>{{ node.allocatable.memory }}
              </span>
            </template>
          </div>
        </div>
      </section>

      <!-- Pods -->
      <section class="resource-section">
        <div class="resource-section-head">
          <span class="resource-section-title">{{ t('resources.pods') }}</span>
          <div class="resource-summary">
            <span class="status-badge status-badge-neutral">
              {{ t('resources.total_pods') }}: {{ metrics.pods?.total || 0 }}
            </span>
            <span class="status-badge status-badge-success">
              {{ t('resources.running_pods') }}: {{ metrics.pods?.running || 0 }}
            </span>
          </div>
        </div>
        <div class="resource-grid">
          <div v-for="(pod, idx) in metrics.pods?.list" :key="idx" class="resource-card">
            <span class="resource-name" :title="pod.name">{{ pod.name }}</span>
            <span :class="['status-badge', pod.phase === 'Running' ? 'status-badge-success' : 'status-badge-warning']">
              {{ pod.phase }}
            </span>
            <span v-if="pod.podIP" class="resource-line">
              <span class="label">IP</span>{{ pod.podIP }}
            </span>
            <span v-if="pod.containers" class="resource-line">
              <span class="label">{{ t('resources.containers') }}</span>{{ pod.containers.length }}
            </span>
          </div>
        </div>
      </section>
    </template>
  </div>
</template>