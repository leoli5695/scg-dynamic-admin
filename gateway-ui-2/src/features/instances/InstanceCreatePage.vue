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
  AppstoreOutlined, ArrowLeftOutlined,
  CloudSyncOutlined, ClusterOutlined, DatabaseOutlined, SettingOutlined,
} from '@/components/icons'
import { useRequest } from '@/composables/useRequest'
import { createInstance, listInstanceSpecs } from '@/api/instances'
import { getClusterImages, getClusters } from '@/api/kubernetes'
import { ApiError } from '@/api/http'
import type { InstanceCreateRequest } from '@/types/instance'
import { message } from '@/utils/feedback'
import VnPanel from '@/components/vn/VnPanel.vue'
import VnToolbar from '@/components/vn/VnToolbar.vue'
import '@/styles/vue-native.css'
import '@/styles/vue-native-shared.css'
import './instances-vue-native.css'

defineOptions({ name: 'InstanceCreatePage' })

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

// antd Select only accepts string/number values; keep the payload boolean.
const createNamespaceValue = computed({
  get: () => (formState.createNamespace ? 'yes' : 'no'),
  set: (value: string) => { formState.createNamespace = value === 'yes' },
})

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
  <div class="instance-create-page vn-instances vn-scope">
    <VnToolbar
      :title="t('instance.create_instance')"
      :lede="t('instance.page_subtitle')"
    >
      <template #actions>
        <a-button @click="handleCancel">
          <template #icon><ArrowLeftOutlined /></template>{{ t('common.back') }}
        </a-button>
      </template>
    </VnToolbar>

    <VnPanel class="vn-create-panel">
      <a-spin :spinning="loading">
        <a-form :model="formState" layout="vertical" @finish="handleSubmit">
            <!-- Basic Info -->
            <section class="ic-section">
              <div class="ic-section-header">
                <div class="ic-section-icon"><AppstoreOutlined /></div>
                <span class="ic-section-title">{{ t('instance.basic_info') }}</span>
              </div>
              <div class="ic-section-body">
                <a-row :gutter="16">
                  <a-col :span="12">
                    <a-form-item name="instanceName" :label="t('instance.instance_name')" :rules="[{ required: true, message: t('common.required') }]">
                      <a-input v-model:value="formState.instanceName" :placeholder="t('instance.instance_name_placeholder')" />
                    </a-form-item>
                  </a-col>
                  <a-col :span="12">
                    <a-form-item name="clusterId" :label="t('instance.cluster')" :rules="[{ required: true, message: t('common.required') }]">
                      <a-select v-model:value="formState.clusterId" :placeholder="t('instance.select_cluster')" popup-class-name="vn-portal">
                        <a-select-option
                          v-for="cluster in connectedClusters"
                          :key="cluster.id"
                          :value="cluster.id"
                        >
                          <ClusterOutlined class="ic-cluster-icon" />
                          {{ cluster.clusterName }}
                          <span class="ic-option-tag">{{ cluster.clusterVersion }}</span>
                        </a-select-option>
                      </a-select>
                    </a-form-item>
                  </a-col>
                </a-row>
                <a-row :gutter="16">
                  <a-col :span="12">
                    <a-form-item name="namespace" :label="t('instance.namespace')" :rules="[{ required: true, message: t('common.required') }]">
                      <a-input v-model:value="formState.namespace" :placeholder="t('instance.namespace_placeholder')" />
                    </a-form-item>
                  </a-col>
                  <a-col :span="12">
                    <a-form-item name="createNamespace" :label="t('instance.create_namespace')">
                      <a-select v-model:value="createNamespaceValue" popup-class-name="vn-portal">
                        <a-select-option value="yes">{{ t('common.yes') }}</a-select-option>
                        <a-select-option value="no">{{ t('common.no') }}</a-select-option>
                      </a-select>
                    </a-form-item>
                  </a-col>
                </a-row>
              </div>
            </section>

            <!-- Service addresses -->
            <section class="ic-section">
              <div class="ic-section-header">
                <div class="ic-section-icon"><DatabaseOutlined /></div>
                <span class="ic-section-title">{{ t('instance.config_section') }}</span>
                <span class="ic-section-hint">{{ t('instance.config_section_hint') }}</span>
              </div>
              <div class="ic-section-body">
                <a-alert class="ic-form-alert" :message="t('instance.config_hint')" type="info" show-icon />
                <a-row :gutter="16">
                  <a-col :span="12">
                    <a-form-item name="nacosServerAddr" :label="t('instance.nacos_server_addr')" :tooltip="t('instance.nacos_server_addr_tooltip')">
                      <a-input v-model:value="formState.nacosServerAddr" :placeholder="t('instance.nacos_server_addr_placeholder')" />
                    </a-form-item>
                  </a-col>
                  <a-col :span="12">
                    <a-form-item name="redisServerAddr" :label="t('instance.redis_server_addr')" :tooltip="t('instance.redis_server_addr_tooltip')">
                      <a-input v-model:value="formState.redisServerAddr" :placeholder="t('instance.redis_server_addr_placeholder')" />
                    </a-form-item>
                  </a-col>
                </a-row>
                <a-row :gutter="16">
                  <a-col :span="12">
                    <a-form-item name="jaegerServerAddr" :label="t('instance.jaeger_server_addr')" :tooltip="t('instance.jaeger_server_addr_tooltip')">
                      <a-input v-model:value="formState.jaegerServerAddr" :placeholder="t('instance.jaeger_server_addr_placeholder')" />
                    </a-form-item>
                  </a-col>
                  <a-col :span="12">
                    <a-form-item name="prometheusServerAddr" :label="t('instance.prometheus_server_addr')" :tooltip="t('instance.prometheus_server_addr_tooltip')">
                      <a-input v-model:value="formState.prometheusServerAddr" :placeholder="t('instance.prometheus_server_addr_placeholder')" />
                    </a-form-item>
                  </a-col>
                </a-row>
              </div>
            </section>

            <!-- Resources -->
            <section class="ic-section">
              <div class="ic-section-header">
                <div class="ic-section-icon"><SettingOutlined /></div>
                <span class="ic-section-title">{{ t('instance.resource_section') }}</span>
              </div>
              <div class="ic-section-body">
                <a-row :gutter="16">
                  <a-col :span="8">
                    <a-form-item name="specType" :label="t('instance.spec_type')" :rules="[{ required: true }]">
                      <a-select v-model:value="formState.specType" popup-class-name="vn-portal">
                        <a-select-option v-for="spec in specOptions" :key="spec.type" :value="spec.type">
                          {{ spec.type === 'custom' ? t('instance.spec_custom') : t(`instance.spec_${spec.type}`) }}
                        </a-select-option>
                      </a-select>
                    </a-form-item>
                  </a-col>
                  <template v-if="selectedSpecType === 'custom'">
                    <a-col :span="8">
                      <a-form-item name="cpuCores" :label="t('instance.cpu_cores')" :rules="[{ required: true, message: t('common.required') }]">
                        <a-input-number v-model:value="formState.cpuCores" :min="0.1" :max="16" :step="0.5" :style="{ width: '100%' }" placeholder="1.0" />
                      </a-form-item>
                    </a-col>
                    <a-col :span="8">
                      <a-form-item name="memoryMB" :label="t('instance.memory_mb')" :rules="[{ required: true, message: t('common.required') }]">
                        <a-input-number v-model:value="formState.memoryMB" :min="128" :max="16384" :step="128" :style="{ width: '100%' }" placeholder="1024" />
                      </a-form-item>
                    </a-col>
                  </template>
                  <a-col :span="8">
                    <a-form-item name="replicas" :label="t('instance.replicas')" :rules="[{ required: true }]">
                      <a-input-number v-model:value="formState.replicas" :min="1" :max="10" :style="{ width: '100%' }" />
                    </a-form-item>
                  </a-col>
                </a-row>
              </div>
            </section>

            <!-- Image -->
            <section class="ic-section">
              <div class="ic-section-header">
                <div class="ic-section-icon"><CloudSyncOutlined /></div>
                <span class="ic-section-title">{{ t('instance.image_section') }}</span>
              </div>
              <div class="ic-section-body">
                <a-row :gutter="16">
                  <a-col :span="12">
                    <a-form-item name="imagePullPolicy" :label="t('instance.image_pull_policy')" :tooltip="t('instance.image_pull_policy_tooltip')">
                      <a-select v-model:value="formState.imagePullPolicy" :style="{ width: '100%' }" popup-class-name="vn-portal">
                        <a-select-option value="IfNotPresent">{{ t('instance.pull_if_not_present') }}</a-select-option>
                        <a-select-option value="Always">{{ t('instance.pull_always') }}</a-select-option>
                        <a-select-option value="Never">{{ t('instance.pull_never') }}</a-select-option>
                      </a-select>
                    </a-form-item>
                  </a-col>
                  <a-col :span="12">
                    <a-form-item
                      :label="t('instance.image')"
                      :tooltip="selectedImagePullPolicy === 'Never' ? 'Select from local images available on cluster nodes' : t('instance.image_tooltip')"
                    >
                      <a-spin v-if="selectedImagePullPolicy === 'Never' && loadingImages" tip="Loading images..." />
                      <div v-else-if="selectedImagePullPolicy === 'Never' && localImages.length > 0" class="ic-field-stack">
                        <a-select v-model:value="selectedImageNamespace" :style="{ width: '100%' }" placeholder="Filter by namespace" popup-class-name="vn-portal">
                          <a-select-option value="all">All Namespaces ({{ localImages.length }})</a-select-option>
                          <a-select-option v-for="ns in namespaces" :key="ns" :value="ns">
                            {{ ns }} ({{ localImages.filter((img) => img.namespace === ns).length }})
                          </a-select-option>
                        </a-select>
                        <a-select
                          v-model:value="formState.image"
                          show-search
                         
                          :placeholder="selectedImageNamespace ? `Select from ${selectedImageNamespace}` : 'Select local image'"
                          option-filter-prop="label"
                          :loading="loadingImages"
                          allow-clear
                          :style="{ width: '100%' }"
                         popup-class-name="vn-portal">
                          <a-select-option v-for="img in filteredImages" :key="img.name" :value="img.name" :label="img.name">
                            <span class="vn-chip">{{ img.namespace }}</span>
                            <span class="ic-option-image">{{ img.image }}</span>
                            <span class="vn-chip">{{ img.tag }}</span>
                          </a-select-option>
                        </a-select>
                      </div>
                      <a-input
                        v-else-if="selectedImagePullPolicy === 'Never'"
                        v-model:value="formState.image"
                       
                        placeholder="No local images found. Enter manually."
                        :style="{ width: '100%' }"
                      />
                      <a-input
                        v-else
                        v-model:value="formState.image"
                       
                        placeholder="e.g., my-gateway:latest"
                        :style="{ width: '100%' }"
                      />
                    </a-form-item>
                  </a-col>
                </a-row>
                <a-form-item name="description" :label="t('instance.description')">
                  <a-textarea v-model:value="formState.description" :rows="3" show-count :maxlength="500" />
                </a-form-item>
              </div>
            </section>

            <!-- Footer -->
            <div class="ic-footer">
              <a-button @click="handleCancel">{{ t('common.cancel') }}</a-button>
              <a-button type="primary" html-type="submit" :loading="submitting">
                {{ t('instance.create_and_deploy') }}
              </a-button>
            </div>
          </a-form>
        </a-spin>
    </VnPanel>
  </div>
</template>