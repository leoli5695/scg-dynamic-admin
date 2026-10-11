<script setup lang="ts">
/**
 * Instances list page — Vue port of InstancesListPage.tsx.
 * Data flow via typed API + useRequest composable; actions refresh the list.
 */
import { computed } from 'vue'
import { useRouter } from 'vue-router'
import { useI18n } from 'vue-i18n'
import { Empty } from 'ant-design-vue'
import {
  CloudServerOutlined, ClusterOutlined, DashboardOutlined, DeleteOutlined,
  InboxOutlined, PauseCircleOutlined, PlayCircleOutlined, PlusOutlined,
  ReloadOutlined, SettingOutlined,
} from '@ant-design/icons-vue'
import { useRequest } from '@/composables/useRequest'
import {
  deleteInstance, listInstances, refreshInstanceStatus, startInstance, stopInstance,
} from '@/api/instances'
import { getClusters } from '@/api/kubernetes'
import { INSTANCE_STATUS, type GatewayInstance } from '@/types/instance'
import { getStatusColor, getStatusText, getSpecText } from '@/utils/instance'
import { ApiError } from '@/api/http'
import { message } from '@/utils/feedback'
import './instances.css'

const router = useRouter()
const { t } = useI18n()

const clusters = useRequest((signal) => getClusters({ signal }))
const instances = useRequest((signal) => listInstances({ signal }))

const list = computed<GatewayInstance[]>(() => instances.data.value ?? [])
const clusterCount = computed(() => clusters.data.value?.length ?? 0)
const loading = computed(() => instances.loading.value || clusters.loading.value)

function statusBarClass(statusCode?: number): string {
  switch (statusCode) {
    case INSTANCE_STATUS.RUNNING: return 'status-running'
    case INSTANCE_STATUS.STARTING: return 'status-starting'
    case INSTANCE_STATUS.STOPPED: return 'status-stopped'
    case INSTANCE_STATUS.ERROR: return 'status-error'
    case INSTANCE_STATUS.STOPPING: return 'status-stopping'
    default: return ''
  }
}

/** Run a mutating action then refresh the list. */
async function runAction(action: () => Promise<unknown>, successKey?: string, failKey?: string) {
  try {
    await action()
    if (successKey) message.success(t(successKey))
    instances.run()
  } catch (error) {
    const msg = error instanceof ApiError && error.message
      ? error.message
      : t(failKey || 'common.operation_failed')
    message.error(msg)
  }
}
</script>

<template>
  <div class="instances-page">
    <div class="instances-page-header">
      <div class="instances-page-header-left">
        <div class="instances-page-title">
          <CloudServerOutlined />
          <span class="instances-page-title-text">{{ t('menu.instances') }}</span>
        </div>
        <span class="instances-page-subtitle ant-typography">{{ t('instance.page_subtitle') }}</span>
      </div>
      <div class="instances-page-actions">
        <a-button
          type="primary"
          size="large"
          :disabled="clusterCount === 0"
          @click="router.push('/instances/create')"
        >
          <template #icon><PlusOutlined /></template>
          {{ t('instance.create_instance') }}
        </a-button>
      </div>
    </div>

    <a-spin :spinning="loading">
      <a-row :gutter="[16, 16]" class="instances-grid">
        <a-col v-for="instance in list" :key="instance.id" :xs="24" :sm="12" :lg="8" :xl="8">
          <a-card
            :class="['instance-card', statusBarClass(instance.statusCode)]"
            hoverable
            :body-style="{ padding: '20px' }"
            @click="router.push(`/instances/${instance.instanceId}`)"
          >
            <div class="instance-card-header">
              <div class="instance-card-icon"><CloudServerOutlined /></div>
              <div class="instance-card-info">
                <div class="instance-card-name">
                  <a-typography-text strong>{{ instance.instanceName }}</a-typography-text>
                </div>
                <div class="instance-card-meta">
                  <span class="instance-cluster">
                    <ClusterOutlined :style="{ marginRight: '4px', fontSize: '12px' }" />
                    {{ instance.clusterName }}
                  </span>
                  <span class="instance-divider">·</span>
                  <span class="instance-namespace">
                    <InboxOutlined :style="{ marginRight: '4px', fontSize: '12px' }" />
                    {{ instance.namespace }}
                  </span>
                </div>
              </div>
              <div class="instance-card-status-indicator">
                <div :class="`status-bar status-bar-${instance.statusCode}`" />
                <a-tag class="instance-status-tag" :color="getStatusColor(instance.statusCode ?? -1)">
                  {{ getStatusText(instance.statusCode ?? -1, instance.status ?? '', t) }}
                </a-tag>
              </div>
            </div>

            <div class="instance-card-body">
              <div class="instance-metrics-grid">
                <div class="instance-metric-item">
                  <div class="instance-metric-icon"><DashboardOutlined /></div>
                  <div class="instance-metric-content">
                    <span class="instance-metric-label">{{ t('instance.spec') || '规格' }}</span>
                    <span class="instance-metric-value">{{ getSpecText(instance, t) }}</span>
                  </div>
                </div>
                <div class="instance-metric-item">
                  <div class="instance-metric-icon"><SettingOutlined /></div>
                  <div class="instance-metric-content">
                    <span class="instance-metric-label">{{ t('instance.replicas') || '副本' }}</span>
                    <span class="instance-metric-value">{{ instance.replicas }}</span>
                  </div>
                </div>
              </div>

              <div v-if="instance.statusMessage" class="instance-status-message">
                <a-tooltip :title="instance.statusMessage" placement="topLeft">
                  <span class="instance-status-text ant-typography">{{ instance.statusMessage }}</span>
                </a-tooltip>
              </div>
            </div>

            <div class="instance-card-actions" @click.stop>
              <a-tooltip v-if="instance.statusCode === INSTANCE_STATUS.RUNNING" :title="t('instance.stop')">
                <a-button
                  class="instance-action-btn"
                  type="text"
                  @click="runAction(() => stopInstance(instance.id), 'instance.stop_success', 'instance.stop_failed')"
                >
                  <template #icon><PauseCircleOutlined /></template>
                </a-button>
              </a-tooltip>
              <a-tooltip
                v-if="instance.statusCode === INSTANCE_STATUS.STOPPED || instance.statusCode === INSTANCE_STATUS.ERROR"
                :title="t('instance.start')"
              >
                <a-button
                  class="instance-action-btn instance-action-btn-start"
                  type="text"
                  @click="runAction(() => startInstance(instance.id), 'instance.start_success', 'instance.start_failed')"
                >
                  <template #icon><PlayCircleOutlined /></template>
                </a-button>
              </a-tooltip>
              <a-tooltip :title="t('common.refresh')">
                <a-button
                  class="instance-action-btn"
                  type="text"
                  @click="runAction(() => refreshInstanceStatus(instance.id), undefined, 'common.refresh_failed')"
                >
                  <template #icon><ReloadOutlined /></template>
                </a-button>
              </a-tooltip>
              <a-popconfirm
                :title="t('instance.delete_confirm')"
                :ok-text="t('common.confirm')"
                :cancel-text="t('common.cancel')"
                @confirm="runAction(() => deleteInstance(instance.id), 'instance.delete_success', 'instance.delete_failed')"
              >
                <a-tooltip :title="t('common.delete')">
                  <a-button class="instance-action-btn instance-action-btn-danger" type="text" danger>
                    <template #icon><DeleteOutlined /></template>
                  </a-button>
                </a-tooltip>
              </a-popconfirm>
            </div>
          </a-card>
        </a-col>
      </a-row>

      <div v-if="list.length === 0 && !loading && clusterCount > 0" class="instances-empty">
        <a-empty :image="Empty.PRESENTED_IMAGE_SIMPLE">
          <template #description>
            <span class="ant-typography" style="color: var(--text-secondary)">{{ t('instance.no_instances') }}</span>
          </template>
        </a-empty>
      </div>

      <div v-if="list.length === 0 && !loading && clusterCount === 0" class="instances-empty">
        <a-empty :image="Empty.PRESENTED_IMAGE_SIMPLE">
          <template #description>
            <span class="ant-typography" style="color: var(--text-secondary)">{{ t('instance.no_clusters_available') }}</span>
          </template>
          <a-button type="primary" @click="router.push('/kubernetes')">
            {{ t('instance.go_import_cluster') }}
          </a-button>
        </a-empty>
      </div>
    </a-spin>
  </div>
</template>
