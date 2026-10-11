<script setup lang="ts">
/**
 * Instance create page — Vue port of InstanceCreatePage.tsx.
 * Form fields aligned with dto/InstanceCreateRequest.java; specs via
 * GET /api/instances/specs, local images via /clusters/{id}/images.
 *
 * Note: the legacy React version left the local-image <Select> unbound (antd
 * Form.Item injected value/onChange into the wrapping <Space>, so picking a local
 * image never populated form.image). Here the select is bound to formState.image.
 */
import { computed, reactive, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import { useI18n } from 'vue-i18n'
import {
  AppstoreOutlined, ArrowLeftOutlined, CloudServerOutlined,
  CloudSyncOutlined, ClusterOutlined, DatabaseOutlined, SettingOutlined,
} from '@ant-design/icons-vue'
import { useRequest } from '@/composables/useRequest'
import { createInstance, listInstanceSpecs } from '@/api/instances'
import { getClusterImages, getClusters } from '@/api/kubernetes'
import { ApiError } from '@/api/http'
import type { InstanceCreateRequest } from '@/types/instance'
import { message } from '@/utils/feedback'

/** Parsed local image: [registry/][namespace/]image[:tag]. */
interface LocalImage {
  name: string
  namespace: string
  image: string
  tag: string
}

function parseImageName(fullName: string): LocalImage {
  const parts = fullName.split('/')
  let namespace = ''
  let imageWithTag = fullName
  if (parts.length >= 2 && !parts[0].includes(':')) {
    namespace = parts[0]
    imageWithTag = parts.slice(1).join('/')
  }
  const [image, tag] = imageWithTag.split(':')
  return { name: fullName, namespace: namespace || 'default', image, tag: tag || 'latest' }
}

const router = useRouter()
const { t } = useI18n()
const submitting = ref(false)

const formState = reactive({
  instanceName: '',
  namespace: '',
  clusterId: undefined as number | undefined,
  createNamespace: true,
  nacosServerAddr: '',
  redisServerAddr: '',
  jaegerServerAddr: '',
  prometheusServerAddr: '',
  specType: 'medium',
  cpuCores: undefined as number | undefined,
  memoryMB: undefined as number | undefined,
  replicas: 1,
  imagePullPolicy: 'Never',
  image: 'my-gateway:latest',
  description: '',
  serverPort: 80,
  managementPort: 9091,
})

const clusters = useRequest((signal) => getClusters({ signal }))
const specs = useRequest((signal) => listInstanceSpecs({ signal }))

// Exposed as computeds: nested refs (clusters.data) are NOT auto-unwrapped in templates.
const connectedClusters = computed(() => (clusters.data.value ?? []).filter((c) => c.connectionStatus === 'CONNECTED'))
const specOptions = computed(() => specs.data.value ?? [])

const selectedSpecType = computed(() => formState.specType)
const selectedClusterId = computed(() => formState.clusterId)
const selectedImagePullPolicy = computed(() => formState.imagePullPolicy)

// Local images are only relevant when pull policy is "Never" and a cluster is set.
const needLocalImages = computed(() => !!selectedClusterId.value && selectedImagePullPolicy.value === 'Never')
const selectedImageNamespace = ref<string | undefined>(undefined)

const imagesRequest = useRequest(
  (signal) => getClusterImages(selectedClusterId.value as number, { signal }),
  {
    immediate: false,
    onSuccess: (raw) => {
      if ((raw ?? []).some((img) => parseImageName(img.name).namespace === 'k8s.io')) {
        selectedImageNamespace.value = 'k8s.io'
      }
    },
    onError: (error) => {
      if (error.status === 0) return
      message.warning('Failed to load local images from cluster')
    },
  },
)

// Replicates the legacy effect: fetch when needLocalImages is true and deps change.
watch(
  [needLocalImages, selectedClusterId, selectedImagePullPolicy],
  () => {
    if (needLocalImages.value) imagesRequest.run()
  },
)

const localImages = computed<LocalImage[]>(() =>
  needLocalImages.value ? (imagesRequest.data.value ?? []).map((img) => parseImageName(img.name)) : [])
const loadingImages = computed(() => needLocalImages.value && imagesRequest.loading.value)

const filteredImages = computed(() =>
  selectedImageNamespace.value && selectedImageNamespace.value !== 'all'
    ? localImages.value.filter((img) => img.namespace === selectedImageNamespace.value)
    : localImages.value)

const namespaces = computed(() => Array.from(new Set(localImages.value.map((img) => img.namespace))))

const loading = computed(() => clusters.loading.value || specs.loading.value)

async function handleSubmit() {
  submitting.value = true
  try {
    await createInstance({ ...formState } as InstanceCreateRequest)
    message.success(t('instance.create_success'))
    router.push('/instances')
  } catch (error) {
    const msg = error instanceof ApiError && error.message ? error.message : t('instance.create_failed')
    message.error(msg)
  } finally {
    submitting.value = false
  }
}

function handleCancel() {
  router.push('/instances')
}
</script>

<template>
  <div class="form-page-container">
    <a-card class="form-card" :bordered="false">
      <div class="form-header">
        <a-button type="text" class="form-header-back" @click="handleCancel">
          <template #icon><ArrowLeftOutlined /></template>
        </a-button>
        <div class="form-header-icon"><CloudServerOutlined /></div>
        <h3 class="form-header-title">{{ t('instance.create_instance') }}</h3>
      </div>

      <a-divider :style="{ margin: '20px 0 0', borderColor: 'rgba(255,255,255,0.08)' }" />

      <a-spin :spinning="loading">
        <a-form :model="formState" layout="vertical" @finish="handleSubmit">
          <!-- Basic Info -->
          <div class="form-section">
            <div class="form-section-header">
              <div class="form-section-icon"><AppstoreOutlined /></div>
              <span class="form-section-title">{{ t('instance.basic_info') }}</span>
            </div>
            <div class="form-section-content">
              <a-row :gutter="16">
                <a-col :span="12">
                  <a-form-item name="instanceName" :label="t('instance.instance_name')" :rules="[{ required: true, message: t('common.required') }]">
                    <a-input v-model:value="formState.instanceName" size="large" :placeholder="t('instance.instance_name_placeholder')" />
                  </a-form-item>
                </a-col>
                <a-col :span="12">
                  <a-form-item name="clusterId" :label="t('instance.cluster')" :rules="[{ required: true, message: t('common.required') }]">
                    <a-select v-model:value="formState.clusterId" size="large" :placeholder="t('instance.select_cluster')">
                      <a-select-option
                        v-for="cluster in connectedClusters"
                        :key="cluster.id"
                        :value="cluster.id"
                      >
                        <ClusterOutlined :style="{ color: '#60a5fa' }" />
                        {{ cluster.clusterName }}
                        <a-tag color="blue" :style="{ marginLeft: '8px' }">{{ cluster.clusterVersion }}</a-tag>
                      </a-select-option>
                    </a-select>
                  </a-form-item>
                </a-col>
              </a-row>
              <a-row :gutter="16">
                <a-col :span="12">
                  <a-form-item name="namespace" :label="t('instance.namespace')" :rules="[{ required: true, message: t('common.required') }]">
                    <a-input v-model:value="formState.namespace" size="large" :placeholder="t('instance.namespace_placeholder')" />
                  </a-form-item>
                </a-col>
                <a-col :span="12">
                  <a-form-item name="createNamespace" :label="t('instance.create_namespace')">
                    <a-select v-model:value="formState.createNamespace" size="large">
                      <a-select-option :value="true">{{ t('common.yes') }}</a-select-option>
                      <a-select-option :value="false">{{ t('common.no') }}</a-select-option>
                    </a-select>
                  </a-form-item>
                </a-col>
              </a-row>
            </div>
          </div>

          <!-- Configuration -->
          <div class="form-section">
            <div class="form-section-header">
              <div class="form-section-icon"><DatabaseOutlined /></div>
              <span class="form-section-title">{{ t('instance.config_section') }}</span>
              <span class="form-section-subtitle">{{ t('instance.config_section_hint') }}</span>
            </div>
            <div class="form-section-content">
              <a-alert :message="t('instance.config_hint')" type="info" show-icon :style="{ marginBottom: '16px' }" />
              <a-row :gutter="16">
                <a-col :span="12">
                  <a-form-item name="nacosServerAddr" :label="t('instance.nacos_server_addr')" :tooltip="t('instance.nacos_server_addr_tooltip')">
                    <a-input v-model:value="formState.nacosServerAddr" size="large" :placeholder="t('instance.nacos_server_addr_placeholder')" />
                  </a-form-item>
                </a-col>
                <a-col :span="12">
                  <a-form-item name="redisServerAddr" :label="t('instance.redis_server_addr')" :tooltip="t('instance.redis_server_addr_tooltip')">
                    <a-input v-model:value="formState.redisServerAddr" size="large" :placeholder="t('instance.redis_server_addr_placeholder')" />
                  </a-form-item>
                </a-col>
              </a-row>
              <a-row :gutter="16">
                <a-col :span="12">
                  <a-form-item name="jaegerServerAddr" :label="t('instance.jaeger_server_addr')" :tooltip="t('instance.jaeger_server_addr_tooltip')">
                    <a-input v-model:value="formState.jaegerServerAddr" size="large" :placeholder="t('instance.jaeger_server_addr_placeholder')" />
                  </a-form-item>
                </a-col>
                <a-col :span="12">
                  <a-form-item name="prometheusServerAddr" :label="t('instance.prometheus_server_addr')" :tooltip="t('instance.prometheus_server_addr_tooltip')">
                    <a-input v-model:value="formState.prometheusServerAddr" size="large" :placeholder="t('instance.prometheus_server_addr_placeholder')" />
                  </a-form-item>
                </a-col>
              </a-row>
            </div>
          </div>

          <!-- Resource -->
          <div class="form-section">
            <div class="form-section-header">
              <div class="form-section-icon"><SettingOutlined /></div>
              <span class="form-section-title">{{ t('instance.resource_section') }}</span>
            </div>
            <div class="form-section-content">
              <a-row :gutter="16">
                <a-col :span="8">
                  <a-form-item name="specType" :label="t('instance.spec_type')" :rules="[{ required: true }]">
                    <a-select v-model:value="formState.specType" size="large">
                      <a-select-option v-for="spec in specOptions" :key="spec.type" :value="spec.type">
                        {{ spec.type === 'custom' ? t('instance.spec_custom') : t(`instance.spec_${spec.type}`) }}
                      </a-select-option>
                    </a-select>
                  </a-form-item>
                </a-col>
                <template v-if="selectedSpecType === 'custom'">
                  <a-col :span="8">
                    <a-form-item name="cpuCores" :label="t('instance.cpu_cores')" :rules="[{ required: true, message: t('common.required') }]">
                      <a-input-number v-model:value="formState.cpuCores" :min="0.1" :max="16" :step="0.5" size="large" :style="{ width: '100%' }" placeholder="1.0" />
                    </a-form-item>
                  </a-col>
                  <a-col :span="8">
                    <a-form-item name="memoryMB" :label="t('instance.memory_mb')" :rules="[{ required: true, message: t('common.required') }]">
                      <a-input-number v-model:value="formState.memoryMB" :min="128" :max="16384" :step="128" size="large" :style="{ width: '100%' }" placeholder="1024" />
                    </a-form-item>
                  </a-col>
                </template>
                <a-col :span="8">
                  <a-form-item name="replicas" :label="t('instance.replicas')" :rules="[{ required: true }]">
                    <a-input-number v-model:value="formState.replicas" :min="1" :max="10" size="large" :style="{ width: '100%' }" />
                  </a-form-item>
                </a-col>
              </a-row>
            </div>
          </div>

          <!-- Image -->
          <div class="form-section">
            <div class="form-section-header">
              <div class="form-section-icon"><CloudSyncOutlined /></div>
              <span class="form-section-title">{{ t('instance.image_section') }}</span>
            </div>
            <div class="form-section-content">
              <a-row :gutter="16">
                <a-col :span="12">
                  <a-form-item name="imagePullPolicy" :label="t('instance.image_pull_policy')" :tooltip="t('instance.image_pull_policy_tooltip')">
                    <a-select v-model:value="formState.imagePullPolicy" size="large" :style="{ width: '100%' }">
                      <a-select-option value="IfNotPresent">{{ t('instance.pull_if_not_present') }}</a-select-option>
                      <a-select-option value="Always">{{ t('instance.pull_always') }}</a-select-option>
                      <a-select-option value="Never">{{ t('instance.pull_never') }}</a-select-option>
                    </a-select>
                  </a-form-item>
                </a-col>
                <a-col :span="12">
                  <a-form-item
                    name="image"
                    :label="t('instance.image')"
                    :tooltip="selectedImagePullPolicy === 'Never' ? 'Select from local images available on cluster nodes' : t('instance.image_tooltip')"
                  >
                    <a-spin v-if="selectedImagePullPolicy === 'Never' && loadingImages" tip="Loading images..." />
                    <template v-else-if="selectedImagePullPolicy === 'Never' && localImages.length > 0">
                      <a-space direction="vertical" :style="{ width: '100%' }" size="small">
                        <a-select v-model:value="selectedImageNamespace" size="large" :style="{ width: '100%' }" placeholder="Filter by namespace">
                          <a-select-option value="all">All Namespaces ({{ localImages.length }})</a-select-option>
                          <a-select-option v-for="ns in namespaces" :key="ns" :value="ns">
                            {{ ns }} ({{ localImages.filter((img) => img.namespace === ns).length }})
                          </a-select-option>
                        </a-select>
                        <a-select
                          v-model:value="formState.image"
                          show-search
                          size="large"
                          :placeholder="selectedImageNamespace ? `Select from ${selectedImageNamespace}` : 'Select local image'"
                          option-filter-prop="label"
                          :loading="loadingImages"
                          allow-clear
                          :style="{ width: '100%' }"
                        >
                          <a-select-option v-for="img in filteredImages" :key="img.name" :value="img.name" :label="img.name">
                            <a-tag color="blue">{{ img.namespace }}</a-tag>
                            <span :style="{ color: '#fafafa' }">{{ img.image }}</span>
                            <a-tag color="green">{{ img.tag }}</a-tag>
                          </a-select-option>
                        </a-select>
                      </a-space>
                    </template>
                    <a-input
                      v-else-if="selectedImagePullPolicy === 'Never'"
                      v-model:value="formState.image"
                      size="large"
                      placeholder="No local images found. Enter manually."
                      :style="{ width: '100%' }"
                    />
                    <a-input
                      v-else
                      v-model:value="formState.image"
                      size="large"
                      placeholder="e.g., my-gateway:latest"
                      :style="{ width: '100%' }"
                    />
                  </a-form-item>
                </a-col>
              </a-row>
              <a-form-item name="description" :label="t('instance.description')">
                <a-textarea v-model:value="formState.description" :rows="3" size="large" show-count :maxlength="500" />
              </a-form-item>
            </div>
          </div>

          <!-- Footer -->
          <div class="form-footer">
            <a-button size="large" @click="handleCancel">{{ t('common.cancel') }}</a-button>
            <a-button type="primary" size="large" html-type="submit" :loading="submitting">
              {{ t('instance.create_and_deploy') }}
            </a-button>
          </div>
        </a-form>
      </a-spin>
    </a-card>
  </div>
</template>
