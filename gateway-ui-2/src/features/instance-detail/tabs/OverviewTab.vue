<script setup lang="ts">
/**
 * Overview tab — stat cards, cluster health score popover, basic info.
 */
import { computed } from 'vue'
import { useI18n } from 'vue-i18n'
import { LinkOutlined } from '@/components/icons'
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
  if (instance.value.manualAccessUrl) return t('instance.access_url_manual')
  if (instance.value.discoveredAccessUrl) return t('instance.access_url_discovered')
  if (instance.value.reportedAccessUrl) return t('instance.access_url_reported')
  return t('instance.access_url_default')
})
</script>

<template>
  <div class="overview-tab">
    <div class="ov-grid">
      <div class="card-base ov-card">
        <span class="ov-label">{{ t('instance.status') }}</span>
        <div :class="['ov-value', isRunning ? 'is-success' : 'is-warning']">
          <i :class="['status-dot', isRunning ? 'status-dot-success' : 'status-dot-warning']" />
          {{ getStatusTextByName(instance.status ?? '', t) }}
        </div>
        <span v-if="instance.statusMessage" class="ov-sub" :title="instance.statusMessage">
          {{ instance.statusMessage }}
        </span>
      </div>

      <div class="card-base ov-card">
        <span class="ov-label">{{ t('instance.replicas') }}</span>
        <div class="ov-value">
          {{ instance.replicas }}
          <small>Pods</small>
        </div>
        <span class="ov-sub">{{ t('instance.deployment') }}: {{ instance.deploymentName || '-' }}</span>
      </div>

      <div class="card-base ov-card">
        <span class="ov-label">{{ t('instance.spec') }}</span>
        <div class="ov-value is-text">{{ getSpecText(instance, t) }}</div>
        <span class="ov-sub">{{ instance.cpuCores || 0 }}C / {{ instance.memoryMB || 0 }}MB</span>
      </div>

      <div class="card-base ov-card ov-card-health">
        <HealthScorePopover />
      </div>
    </div>

    <div class="card-base ov-info">
      <div class="ov-section-head">
        <span class="ov-section-title">{{ t('instance.basic_info') }}</span>
      </div>
      <a-descriptions :column="{ xs: 1, sm: 2, md: 3 }" :colon="false">
        <a-descriptions-item :label="t('instance.instance_id')">
          <a-typography-text copyable class="mono">{{ instance.instanceId }}</a-typography-text>
        </a-descriptions-item>
        <a-descriptions-item :label="t('instance.cluster')">{{ instance.clusterName }}</a-descriptions-item>
        <a-descriptions-item :label="t('instance.namespace')">{{ instance.namespace }}</a-descriptions-item>
        <a-descriptions-item :label="t('instance.nacos_namespace')">
          <a-typography-text copyable>{{ instance.nacosNamespace }}</a-typography-text>
        </a-descriptions-item>
        <a-descriptions-item :label="t('instance.image')">{{ instance.image }}</a-descriptions-item>
        <a-descriptions-item :label="t('instance.deployment')">{{ instance.deploymentName }}</a-descriptions-item>
        <a-descriptions-item :label="t('instance.access_url')">
          <span v-if="!accessUrl" class="access-url-source">-</span>
          <a-space v-else direction="vertical" :size="0">
            <a-tooltip :title="t('instance.click_to_open')">
              <a :href="accessUrl" target="_blank" rel="noopener noreferrer" class="instance-access-url">
                <LinkOutlined style="margin-right: 4px" />{{ accessUrl }}
              </a>
            </a-tooltip>
            <span class="access-url-source">({{ accessUrlSource }})</span>
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
    </div>
  </div>
</template>