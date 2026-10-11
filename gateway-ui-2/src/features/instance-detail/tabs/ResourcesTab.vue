<script setup lang="ts">
/**
 * Resources tab — cluster nodes and pods overview.
 * Uses GET /api/instances/{id}/resource-metrics; handles local mode.
 */
import { useI18n } from 'vue-i18n'
import { ReloadOutlined } from '@ant-design/icons-vue'
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
  <a-card :bordered="false">
    <div :style="{ marginBottom: '16px', display: 'flex', justifyContent: 'space-between', alignItems: 'center' }">
      <h5 class="ant-typography" :style="{ margin: 0 }">{{ t('resources.title') || '集群资源监控' }}</h5>
      <a-button :loading="metricsRequest.loading" @click="metricsRequest.run()">
        <template #icon><ReloadOutlined /></template>
        {{ t('common.refresh') || '刷新' }}
      </a-button>
    </div>

    <div v-if="metricsRequest.loading && !metrics" :style="{ display: 'flex', justifyContent: 'center', padding: '40px' }">
      <a-spin />
    </div>
    <div v-else-if="!metrics" :style="{ textAlign: 'center', padding: '40px' }">
      <span class="ant-typography" style="color: var(--text-secondary)">{{ t('resources.no_data') || '暂无资源数据' }}</span>
    </div>
    <div v-else-if="metrics.mode === 'local'" :style="{ textAlign: 'center', padding: '40px' }">
      <a-alert
        type="info"
        show-icon
        :message="t('resources.local_mode') || '本地运行模式'"
        :description="t('resources.local_mode_desc') || '此网关实例为本地启动，未关联 Kubernetes 集群，无法获取资源指标'"
      />
    </div>
    <template v-else>
      <!-- Nodes -->
      <a-card :title="t('resources.nodes') || '节点'" size="small" :style="{ marginBottom: '16px' }">
        <a-row :gutter="[16, 16]">
          <a-col v-for="(node, idx) in metrics.nodes?.list" :key="idx" :xs="24" :sm="12" :md="8" :lg="6">
            <a-card size="small" :bordered="false" :style="{ background: 'rgba(148,163,184,0.06)' }">
              <a-space direction="vertical" size="small">
                <span class="ant-typography" style="font-weight: 600">{{ node.name }}</span>
                <a-badge :status="node.ready ? 'success' : 'error'" :text="node.ready ? 'Ready' : 'Not Ready'" />
                <template v-if="node.allocatable">
                  <span class="ant-typography" style="color: var(--text-secondary); font-size: 12px">CPU: {{ node.allocatable.cpu }}</span>
                  <span class="ant-typography" style="color: var(--text-secondary); font-size: 12px">Memory: {{ node.allocatable.memory }}</span>
                </template>
              </a-space>
            </a-card>
          </a-col>
        </a-row>
        <div :style="{ marginTop: '16px' }">
          <span class="ant-typography" style="color: var(--text-secondary)">
            {{ t('resources.total_nodes') || '节点总数' }}: {{ metrics.nodes?.total || 0 }}
          </span>
        </div>
      </a-card>

      <!-- Pods -->
      <a-card :title="t('resources.pods') || 'Pods'" size="small">
        <a-row :gutter="[16, 16]">
          <a-col v-for="(pod, idx) in metrics.pods?.list" :key="idx" :xs="24" :sm="12" :md="8" :lg="6">
            <a-card
              size="small"
              :bordered="false"
              :style="{ background: pod.phase === 'Running' ? 'rgba(34,197,94,0.08)' : 'rgba(234,179,8,0.08)' }"
            >
              <a-space direction="vertical" size="small">
                <span class="ant-typography" style="font-weight: 600; font-size: 12px">{{ pod.name }}</span>
                <a-tag :color="pod.phase === 'Running' ? 'success' : 'warning'">{{ pod.phase }}</a-tag>
                <span v-if="pod.podIP" class="ant-typography" style="color: var(--text-secondary); font-size: 12px">IP: {{ pod.podIP }}</span>
                <span v-if="pod.containers" class="ant-typography" style="color: var(--text-secondary); font-size: 12px">
                  {{ t('resources.containers') || '容器' }}: {{ pod.containers.length }}
                </span>
              </a-space>
            </a-card>
          </a-col>
        </a-row>
        <div :style="{ marginTop: '16px' }">
          <a-space>
            <span class="ant-typography" style="color: var(--text-secondary)">
              {{ t('resources.total_pods') || 'Pod总数' }}: {{ metrics.pods?.total || 0 }}
            </span>
            <span class="ant-typography" style="color: var(--text-secondary)">
              {{ t('resources.running_pods') || '运行中' }}: {{ metrics.pods?.running || 0 }}
            </span>
          </a-space>
        </div>
      </a-card>
    </template>
  </a-card>
</template>
