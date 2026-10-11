<script setup lang="ts">
/**
 * Overview tab — stat cards, cluster health score popover, basic info.
 */
import { computed } from 'vue'
import { useI18n } from 'vue-i18n'
import { LinkOutlined } from '@ant-design/icons-vue'
import {
  getEffectiveAccessUrl, getEffectiveNacosServerAddr, getSpecText, getStatusTextByName,
} from '@/utils/instance'
import { useInstanceDetail } from '../context'
import HealthScorePopover from './HealthScorePopover.vue'

const { t } = useI18n()
const detail = useInstanceDetail()
const instance = computed(() => detail.instance)

const isRunning = computed(() => instance.value.status === 'Running' || instance.value.status === 'RUNNING')
const accessUrl = computed(() => getEffectiveAccessUrl(instance.value))
const nacosAddr = computed(() =>
  instance.value.nacosServerAddr ? getEffectiveNacosServerAddr(instance.value) : detail.globalNacosAddr)

const accessUrlSource = computed(() => {
  if (instance.value.manualAccessUrl) return t('instance.access_url_manual') || '手动配置'
  if (instance.value.discoveredAccessUrl) return t('instance.access_url_discovered') || 'K8s发现'
  if (instance.value.reportedAccessUrl) return t('instance.access_url_reported') || '心跳上报'
  return t('instance.access_url_default') || '默认'
})
</script>

<template>
  <div class="instance-overview-stats">
    <a-row :gutter="[24, 24]">
      <a-col :xs="24" :md="12" :lg="6">
        <a-card class="instance-stat-card" :bordered="false">
          <a-statistic
            :title="t('instance.status')"
            :value="getStatusTextByName(instance.status ?? '', t)"
            :value-style="{ color: isRunning ? 'var(--success-color)' : 'var(--warning-color)' }"
          >
            <template #prefix><a-badge :status="isRunning ? 'success' : 'warning'" /></template>
          </a-statistic>
        </a-card>
      </a-col>
      <a-col :xs="24" :md="12" :lg="6">
        <a-card class="instance-stat-card" :bordered="false">
          <a-statistic :title="t('instance.replicas')" :value="instance.replicas" suffix="Pods" :value-style="{ color: 'var(--primary-color)' }" />
        </a-card>
      </a-col>
      <a-col :xs="24" :md="12" :lg="6">
        <a-card class="instance-stat-card" :bordered="false">
          <a-statistic :title="t('instance.spec')" :value="getSpecText(instance, t)" :value-style="{ color: 'var(--text-primary)' }" />
        </a-card>
      </a-col>
      <a-col :xs="24" :md="12" :lg="6">
        <a-card class="instance-stat-card" :bordered="false">
          <HealthScorePopover />
        </a-card>
      </a-col>
    </a-row>

    <a-card class="instance-info-section" :title="t('instance.basic_info')" :bordered="false">
      <a-descriptions :column="{ xs: 1, sm: 2, md: 3 }">
        <a-descriptions-item :label="t('instance.instance_id')">
          <a-typography-text copyable>{{ instance.instanceId }}</a-typography-text>
        </a-descriptions-item>
        <a-descriptions-item :label="t('instance.cluster')">{{ instance.clusterName }}</a-descriptions-item>
        <a-descriptions-item :label="t('instance.namespace')">{{ instance.namespace }}</a-descriptions-item>
        <a-descriptions-item :label="t('instance.nacos_namespace')">
          <a-typography-text copyable>{{ instance.nacosNamespace }}</a-typography-text>
        </a-descriptions-item>
        <a-descriptions-item :label="t('instance.image')">{{ instance.image }}</a-descriptions-item>
        <a-descriptions-item :label="t('instance.deployment')">{{ instance.deploymentName }}</a-descriptions-item>
        <a-descriptions-item :label="t('instance.access_url')">
          <span v-if="!accessUrl" class="ant-typography" style="color: var(--text-secondary)">-</span>
          <a-space v-else direction="vertical" :size="0">
            <a-tooltip :title="t('instance.click_to_open')">
              <a :href="accessUrl" target="_blank" rel="noopener noreferrer" class="instance-access-url">
                <LinkOutlined style="margin-right: 4px" />{{ accessUrl }}
              </a>
            </a-tooltip>
            <span class="ant-typography" style="color: var(--text-secondary); font-size: 12px">({{ accessUrlSource }})</span>
          </a-space>
        </a-descriptions-item>
        <a-descriptions-item :label="t('instance.nacos_console')">
          <a-tooltip :title="t('instance.click_to_open_nacos')">
            <a
              :href="`http://${nacosAddr}/nacos/#/configurationManagement?dataId=&group=&namespace=${instance.nacosNamespace ?? ''}`"
              target="_blank"
              rel="noopener noreferrer"
              class="instance-access-url"
            >
              <LinkOutlined style="margin-right: 4px" />http://{{ nacosAddr }}/nacos
            </a>
          </a-tooltip>
        </a-descriptions-item>
        <a-descriptions-item :label="t('instance.created_at')">
          {{ instance.createdAt ? new Date(instance.createdAt).toLocaleString() : '-' }}
        </a-descriptions-item>
        <a-descriptions-item v-if="instance.description" :label="t('instance.description')">
          {{ instance.description }}
        </a-descriptions-item>
      </a-descriptions>
    </a-card>
  </div>
</template>
