<script setup lang="ts">
/**
 * Service binding configuration form — shared between create and edit.
 * Handles both SINGLE and MULTI routing modes. Mutates the reactive route form
 * model owned by RouteFormModal.
 */
import { computed } from 'vue'
import { useI18n } from 'vue-i18n'
import { CloudOutlined, DeleteOutlined, GlobalOutlined, PlusOutlined } from '@/components/icons'
import type { RoutingMode } from '@/types/route'
import type { NacosDiscoveryServiceInfo, ServiceDefinition } from '@/types/service'
import { sumWeights } from '../utils'
import type { RouteFormValues } from '../utils'
import VnBadge from '@/components/vn/VnBadge.vue'

const props = defineProps<{
  formState: RouteFormValues
  services: ServiceDefinition[]
  nacosServices: NacosDiscoveryServiceInfo[]
}>()

const { t } = useI18n()

const routingMode = computed(() => props.formState.routingMode || 'SINGLE')
const totalWeight = computed(() => sumWeights(props.formState.services))
const weightValid = computed(() => totalWeight.value === 100)

function setRoutingMode(mode: RoutingMode) {
  props.formState.routingMode = mode
  props.formState.serviceId = undefined
  props.formState.services = []
  props.formState.uri = ''
}

function onModeChange(value: string | number) {
  setRoutingMode(value as RoutingMode)
}

function handleServiceIdChange(value: string) {
  props.formState.uri = value || ''
  if (value && value.startsWith('lb://')) {
    const serviceName = value.replace('lb://', '')
    const nacosService = props.nacosServices.find((s) => s.serviceName === serviceName)
    if (nacosService) {
      props.formState.serviceNamespace = nacosService.namespace || ''
      props.formState.serviceGroup = nacosService.group || 'DEFAULT_GROUP'
    }
  } else {
    props.formState.serviceNamespace = undefined
    props.formState.serviceGroup = undefined
  }
}

function handleMultiServiceIdChange(value: string, index: number) {
  let serviceName = ''
  let serviceNamespace: string | undefined
  let serviceGroup: string | undefined
  if (value?.startsWith('static://')) {
    const id = value.replace('static://', '')
    const found = props.services.find((s) => s.serviceId === id)
    serviceName = found?.name || id
  } else if (value?.startsWith('lb://')) {
    serviceName = value.replace('lb://', '')
    const nacosService = props.nacosServices.find((s) => s.serviceName === serviceName)
    if (nacosService) {
      serviceNamespace = nacosService.namespace || ''
      serviceGroup = nacosService.group || 'DEFAULT_GROUP'
    }
  }
  const cur = props.formState.services?.[index]
  if (cur) {
    cur.serviceName = serviceName
    cur.serviceNamespace = serviceNamespace
    cur.serviceGroup = serviceGroup
  }
}

function addService() {
  if (!props.formState.services) props.formState.services = []
  props.formState.services.push({ weight: 100, enabled: true })
}
function removeService(index: number) {
  props.formState.services?.splice(index, 1)
}
</script>

<template>
  <div>
    <!-- Routing mode -->
    <a-form-item :label="t('routes.routing_mode_label')" required>
      <a-segmented
        :value="routingMode"
        :options="[
          { label: t('routes.single_service'), value: 'SINGLE' },
          { label: t('routes.multi_service'), value: 'MULTI' },
        ]"
        class="form-segmented routing-mode-segmented"
        @change="onModeChange"
      />
    </a-form-item>

    <!-- Single service mode -->
    <template v-if="routingMode === 'SINGLE'">
      <a-form-item :label="t('routes.target_service')" required>
        <a-select v-model:value="formState.serviceId" :placeholder="t('routes.select_service')" show-search popup-class-name="vn-portal" @change="handleServiceIdChange">
          <a-select-opt-group>
            <template #label><CloudOutlined class="select-group-icon" /> {{ t('routes.static_services') }}</template>
            <a-select-option v-for="s in services" :key="`static-${s.serviceId}`" :value="`static://${s.serviceId}`">
              <span>{{ s.name || s.serviceId }}</span><span class="service-option-tag static">Static</span>
            </a-select-option>
          </a-select-opt-group>
          <a-select-opt-group>
            <template #label><GlobalOutlined class="select-group-icon" /> {{ t('routes.nacos_services') }}</template>
            <a-select-option
              v-for="s in nacosServices"
              :key="`lb-${s.serviceName}-${s.namespace || 'public'}-${s.group || 'DEFAULT_GROUP'}`"
              :value="`lb://${s.serviceName}`"
            >
              <span>{{ s.displayName || `${s.serviceName} (${s.namespace || 'public'}/${s.group || 'DEFAULT_GROUP'})` }}</span>
              <span class="service-option-tag nacos">Nacos</span>
            </a-select-option>
          </a-select-opt-group>
        </a-select>
      </a-form-item>
    </template>

    <!-- Multi-service mode -->
    <template v-if="routingMode === 'MULTI'">
      <div class="weight-bar">
        <VnBadge :tone="weightValid ? 'ok' : 'warn'">
          {{ t('routes.weight_total') }}: {{ totalWeight }}%
        </VnBadge>
        <span v-if="!weightValid" class="weight-bar-hint">{{ t('routes.weight_must_be_100_hint') }}</span>
      </div>
      <div v-for="(svc, idx) in formState.services" :key="idx" class="binding-row">
        <a-select
          v-model:value="svc.serviceId"
          :placeholder="t('routes.select_service')"
          class="binding-row-service"
          show-search
          popup-class-name="vn-portal"
          @change="handleMultiServiceIdChange($event, idx)"
        >
          <a-select-opt-group>
            <template #label><CloudOutlined class="select-group-icon" /> {{ t('routes.static_services') }}</template>
            <a-select-option v-for="s in services" :key="`static-${s.serviceId}`" :value="`static://${s.serviceId}`">
              <span>{{ s.name || s.serviceId }}</span><span class="service-option-tag static">Static</span>
            </a-select-option>
          </a-select-opt-group>
          <a-select-opt-group>
            <template #label><GlobalOutlined class="select-group-icon" /> {{ t('routes.nacos_services') }}</template>
            <a-select-option
              v-for="s in nacosServices"
              :key="`lb-${s.serviceName}-${s.namespace || 'public'}-${s.group || 'DEFAULT_GROUP'}`"
              :value="`lb://${s.serviceName}`"
            >
              <span>{{ s.displayName || `${s.serviceName} (${s.namespace || 'public'}/${s.group || 'DEFAULT_GROUP'})` }}</span>
              <span class="service-option-tag nacos">Nacos</span>
            </a-select-option>
          </a-select-opt-group>
        </a-select>
        <a-input-number v-model:value="svc.weight" :min="1" :max="100" placeholder="Weight" class="binding-row-weight" addon-after="%" />
        <a-switch v-model:checked="svc.enabled" size="small" checked-children="ON" un-checked-children="OFF" />
        <a-tooltip v-if="(formState.services?.length || 0) > 1" :title="t('common.delete')">
          <a-button type="text" danger class="action-btn vn-iconbtn" :aria-label="t('common.delete')" @click="removeService(idx)">
            <template #icon><DeleteOutlined /></template>
          </a-button>
        </a-tooltip>
      </div>
      <a-button type="dashed" block class="add-predicate-btn-modern" @click="addService">
        <template #icon><PlusOutlined /></template>{{ t('routes.add_service_binding') }}
      </a-button>
    </template>

    <!-- URI display (backward compatibility) -->
    <a-form-item v-if="routingMode === 'SINGLE'" :label="t('routes.target_uri')">
      <a-input v-model:value="formState.uri" :placeholder="t('routes.uri_placeholder')" disabled />
    </a-form-item>
  </div>
</template>
