<script setup lang="ts">
/**
 * Service binding configuration form — shared between create and edit.
 * Handles both SINGLE and MULTI routing modes. Mutates the reactive route form
 * model owned by RouteFormModal.
 */
import { computed } from 'vue'
import { useI18n } from 'vue-i18n'
import { CloudOutlined, DeleteOutlined, GlobalOutlined, PlusOutlined } from '@ant-design/icons-vue'
import type { RoutingMode } from '@/types/route'
import type { NacosDiscoveryServiceInfo, ServiceDefinition } from '@/types/service'
import { sumWeights } from '../utils'
import type { RouteFormValues } from '../utils'

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
      <a-select :value="routingMode" size="large" :style="{ width: '100%' }" @change="setRoutingMode">
        <a-select-option value="SINGLE"><CloudOutlined /> {{ t('routes.single_service') }}</a-select-option>
        <a-select-option value="MULTI"><CloudOutlined /> {{ t('routes.multi_service') }}</a-select-option>
      </a-select>
    </a-form-item>

    <!-- Single service mode -->
    <template v-if="routingMode === 'SINGLE'">
      <a-form-item :label="t('routes.target_service')" required>
        <a-select v-model:value="formState.serviceId" :placeholder="t('routes.select_service')" size="large" show-search @change="handleServiceIdChange">
          <a-select-opt-group>
            <template #label><CloudOutlined style="margin-right: 6px" /> {{ t('routes.static_services') }}</template>
            <a-select-option v-for="s in services" :key="`static-${s.serviceId}`" :value="`static://${s.serviceId}`">
              <span>{{ s.name || s.serviceId }}</span><span class="service-option-tag static">Static</span>
            </a-select-option>
          </a-select-opt-group>
          <a-select-opt-group>
            <template #label><GlobalOutlined style="margin-right: 6px" /> {{ t('routes.nacos_services') }}</template>
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
      <div
        :style="{
          marginBottom: '12px', padding: '8px 12px',
          background: weightValid ? 'rgba(34,197,94,0.1)' : 'rgba(139,90,43,0.15)',
          border: `1px solid ${weightValid ? 'rgba(34,197,94,0.4)' : 'rgba(139,90,43,0.5)'}`,
          borderRadius: '4px', display: 'flex', alignItems: 'center', gap: '8px',
        }"
      >
        <a-tag v-if="weightValid" color="success">{{ t('routes.weight_total') }}: {{ totalWeight }}% ✓</a-tag>
        <a-tag v-else color="warning">{{ t('routes.weight_total') }}: {{ totalWeight }}% ({{ t('routes.weight_must_be_100_hint') }})</a-tag>
      </div>
      <div
        v-for="(svc, idx) in formState.services"
        :key="idx"
        :style="{ padding: '8px 10px', marginBottom: '6px', background: 'rgba(26,35,56,0.6)', borderRadius: '6px', border: '1px solid rgba(148,163,184,0.15)' }"
      >
        <div :style="{ display: 'flex', gap: '8px', alignItems: 'center', flexWrap: 'nowrap' }">
          <a-select
            v-model:value="svc.serviceId"
            :placeholder="t('routes.select_service')"
            :style="{ width: '220px', flexShrink: 0 }"
            show-search
            @change="handleMultiServiceIdChange($event, idx)"
          >
            <a-select-opt-group>
              <template #label><CloudOutlined style="margin-right: 6px" /> {{ t('routes.static_services') }}</template>
              <a-select-option v-for="s in services" :key="`static-${s.serviceId}`" :value="`static://${s.serviceId}`">
                <span>{{ s.name || s.serviceId }}</span><span class="service-option-tag static">Static</span>
              </a-select-option>
            </a-select-opt-group>
            <a-select-opt-group>
              <template #label><GlobalOutlined style="margin-right: 6px" /> {{ t('routes.nacos_services') }}</template>
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
          <a-input-number v-model:value="svc.weight" :min="1" :max="100" placeholder="Weight" :style="{ width: '90px', flexShrink: 0 }" addon-after="%" />
          <a-switch v-model:checked="svc.enabled" size="small" checked-children="ON" un-checked-children="OFF" />
          <a-button v-if="(formState.services?.length || 0) > 1" type="text" danger :style="{ flexShrink: 0, padding: '4px' }" @click="removeService(idx)">
            <template #icon><DeleteOutlined /></template>
          </a-button>
        </div>
      </div>
      <a-button type="dashed" block @click="addService">
        <template #icon><PlusOutlined /></template>{{ t('routes.add_service_binding') }}
      </a-button>
    </template>

    <!-- URI display (backward compatibility) -->
    <a-form-item v-if="routingMode === 'SINGLE'" :label="t('routes.target_uri')">
      <a-input v-model:value="formState.uri" :placeholder="t('routes.uri_placeholder')" disabled size="large" />
    </a-form-item>
  </div>
</template>
