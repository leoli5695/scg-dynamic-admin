<script setup lang="ts">
/**
 * Fluent Bit collection guide — per-deploy-mode tabs with copyable config
 * templates. Port of gateway-ui/src/features/access-log/components/CollectionGuide.tsx;
 * the tab follows the effective deploy mode but stays manually switchable.
 */
import { ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import {
  CheckCircleOutlined, CloudServerOutlined, ContainerOutlined, CopyOutlined,
  HddOutlined, InboxOutlined,
} from '@/components/icons'
import { message } from '@/utils/feedback'
import type { DeployMode } from '@/api/accessLog'
import {
  FLUENT_BIT_CUSTOM_CONFIG, FLUENT_BIT_DOCKER_CONFIG, FLUENT_BIT_K8S_CONFIG,
  FLUENT_BIT_K8S_CONFIGMAP, FLUENT_BIT_LOCAL_CONFIG,
} from '../constants'

const props = defineProps<{ activeDeployMode: DeployMode | string }>()

const { t } = useI18n()
const activeKey = ref<string>(props.activeDeployMode || 'LOCAL')

watch(() => props.activeDeployMode, (mode) => {
  if (mode) activeKey.value = mode
})

function copyConfig(text: string) {
  navigator.clipboard.writeText(text)
  message.success(t('access_log.config_copied'))
}
</script>

<template>
  <a-tabs v-model:activeKey="activeKey" class="al-guide-tabs" size="small">
    <a-tab-pane key="K8S">
      <template #tab>
        <span class="al-guide-tab"><CloudServerOutlined /> {{ t('access_log.collection_k8s_title') }}</span>
      </template>
      <a-alert
        type="success"
        show-icon
        class="al-guide-alert"
        :message="t('access_log.collection_k8s_msg')"
        :description="t('access_log.collection_k8s_desc')"
      >
        <template #icon><CheckCircleOutlined /></template>
      </a-alert>
      <div class="al-code">
        <div class="al-code-head">
          <span class="al-code-label">fluent-bit.conf</span>
          <span class="al-code-actions">
            <a-button type="primary" size="small" @click="copyConfig(FLUENT_BIT_K8S_CONFIG)">
              <template #icon><CopyOutlined /></template>
              {{ t('access_log.collection_copy_config') }}
            </a-button>
            <a-button size="small" @click="copyConfig(FLUENT_BIT_K8S_CONFIGMAP)">
              <template #icon><CopyOutlined /></template>
              {{ t('access_log.collection_copy_configmap') }}
            </a-button>
          </span>
        </div>
        <pre class="al-code-body">{{ FLUENT_BIT_K8S_CONFIG }}</pre>
      </div>
    </a-tab-pane>

    <a-tab-pane key="DOCKER">
      <template #tab>
        <span class="al-guide-tab"><ContainerOutlined /> {{ t('access_log.collection_docker_title') }}</span>
      </template>
      <a-alert
        type="info"
        show-icon
        class="al-guide-alert"
        :message="t('access_log.collection_docker_msg')"
      >
        <template #description>
          <ul class="al-guide-list">
            <li>{{ t('access_log.collection_docker_sidecar') }}</li>
            <li>{{ t('access_log.collection_docker_host') }}</li>
          </ul>
        </template>
      </a-alert>
      <div class="al-code">
        <div class="al-code-head">
          <span class="al-code-label">docker-compose.yml</span>
          <a-button size="small" class="al-code-copy" @click="copyConfig(FLUENT_BIT_DOCKER_CONFIG)">
            <template #icon><CopyOutlined /></template>
            {{ t('access_log.collection_copy') }}
          </a-button>
        </div>
        <pre class="al-code-body">{{ FLUENT_BIT_DOCKER_CONFIG }}</pre>
      </div>
    </a-tab-pane>

    <a-tab-pane key="LOCAL">
      <template #tab>
        <span class="al-guide-tab"><HddOutlined /> {{ t('access_log.collection_local_title') }}</span>
      </template>
      <a-alert
        type="info"
        show-icon
        class="al-guide-alert"
        :message="t('access_log.collection_local_msg')"
        :description="t('access_log.collection_local_desc')"
      />
      <div class="al-code">
        <div class="al-code-head">
          <span class="al-code-label">fluent-bit.conf</span>
          <a-button size="small" class="al-code-copy" @click="copyConfig(FLUENT_BIT_LOCAL_CONFIG)">
            <template #icon><CopyOutlined /></template>
            {{ t('access_log.collection_copy') }}
          </a-button>
        </div>
        <pre class="al-code-body">{{ FLUENT_BIT_LOCAL_CONFIG }}</pre>
      </div>
    </a-tab-pane>

    <a-tab-pane key="CUSTOM">
      <template #tab>
        <span class="al-guide-tab"><InboxOutlined /> {{ t('access_log.collection_custom_title') }}</span>
      </template>
      <a-alert
        type="warning"
        show-icon
        class="al-guide-alert"
        :message="t('access_log.collection_custom_msg')"
        :description="t('access_log.collection_custom_desc')"
      />
      <div class="al-code">
        <div class="al-code-head">
          <span class="al-code-label">fluent-bit.conf</span>
          <a-button size="small" class="al-code-copy" @click="copyConfig(FLUENT_BIT_CUSTOM_CONFIG)">
            <template #icon><CopyOutlined /></template>
            {{ t('access_log.collection_copy') }}
          </a-button>
        </div>
        <pre class="al-code-body">{{ FLUENT_BIT_CUSTOM_CONFIG }}</pre>
      </div>
    </a-tab-pane>
  </a-tabs>
</template>