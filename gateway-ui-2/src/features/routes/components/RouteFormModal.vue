<script setup lang="ts">
/**
 * Unified route create/edit modal. Mounted only while open, so initial values come
 * straight from props. Payload follows model/RouteDefinition.java; enable/disable is
 * NOT part of the body (backend uses dedicated /enable and /disable endpoints).
 */
import { computed, reactive, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useI18n } from 'vue-i18n'
import { h } from 'vue'
import {
  ApiOutlined, ClusterOutlined, EditOutlined, FileTextOutlined, LinkOutlined,
  PlusOutlined, RobotOutlined, RocketOutlined, ThunderboltOutlined,
} from '@ant-design/icons-vue'
import type { RouteCategory, RouteDefinition, RouteResponse, RoutingMode } from '@/types/route'
import type { NacosDiscoveryServiceInfo, ServiceDefinition } from '@/types/service'
import {
  buildRoutePayload, modelMappingToPairs, stringifyPredicateArgs, sumWeights, toFormBinding,
  type RouteFormValues,
} from '../utils'
import { message } from '@/utils/feedback'
import PredicateItem from './PredicateItem.vue'
import ServiceBindingForm from './ServiceBindingForm.vue'
import AiUpstreamsForm from './AiUpstreamsForm.vue'
import GrayRulesForm from './GrayRulesForm.vue'

const props = defineProps<{
  mode: 'create' | 'edit'
  route?: RouteResponse
  services: ServiceDefinition[]
  nacosServices: NacosDiscoveryServiceInfo[]
  onSubmit: (body: RouteDefinition) => Promise<boolean>
}>()
const emit = defineEmits<{ (e: 'cancel'): void }>()

const { t } = useI18n()
const router = useRouter()
const route_ = useRoute()
const instanceId = computed(() => route_.params.instanceId as string | undefined)

const isEdit = computed(() => props.mode === 'edit')

// Edit-time bindings with protocol-prefixed serviceIds for the Select.
const initialBindings = isEdit.value
  ? (props.route?.services ?? []).map((s) => toFormBinding(s, props.services, props.nacosServices))
  : []

const formState = reactive<RouteFormValues>(
  isEdit.value
    ? {
      routeName: props.route?.routeName ?? '',
      order: props.route?.order,
      description: props.route?.description,
      category: props.route?.category || 'NORMAL',
      routingMode: props.route?.mode || 'SINGLE',
      serviceId: props.route?.uri || undefined,
      serviceNamespace: props.route?.serviceNamespace || '',
      serviceGroup: props.route?.serviceGroup || 'DEFAULT_GROUP',
      uri: props.route?.uri,
      services: initialBindings,
      predicates: (props.route?.predicates ?? []).map((p) => ({
        name: p.name,
        args: stringifyPredicateArgs(p.name, p.args as Record<string, unknown>),
      })),
      grayRules: props.route?.grayRules?.rules ?? [],
      aiUpstreams: (props.route?.aiUpstreams ?? []).map((u) => ({
        provider: u.provider, weight: u.weight ?? 100, modelMappingPairs: modelMappingToPairs(u.modelMapping),
      })),
      modelPredicates: (props.route?.modelPredicates ?? []).map((p) => ({
        matchType: p.matchType || 'PREFIX', value: p.value,
      })),
    }
    : { routeName: '', order: 0, routingMode: 'SINGLE', category: 'NORMAL', predicates: [], services: [], grayRules: [], aiUpstreams: [], modelPredicates: [] },
)

const category = computed<RouteCategory>(() => formState.category ?? (isEdit.value ? props.route?.category ?? 'NORMAL' : 'NORMAL'))
const isAi = computed(() => category.value === 'AI')
const aiServices = computed(() => props.services.filter((s) => s.category === 'AI'))

const routingMode = computed<RoutingMode>(() => formState.routingMode || 'SINGLE')
const grayRulesEnabled = ref<boolean>(isEdit.value ? (props.route?.grayRules?.enabled || false) : false)
const submitting = ref(false)

// Live services list for the gray-rule target dropdown.
const configuredServices = computed(() => (isEdit.value ? initialBindings : (formState.services ?? [])))

const categoryOptions = computed(() => [
  { value: 'NORMAL', label: h('span', {}, [h(ClusterOutlined, { style: 'margin-right:6px' }), t('routes.category_normal')]) },
  { value: 'AI', label: h('span', {}, [h(RobotOutlined, { style: 'margin-right:6px' }), t('routes.category_ai')]) },
])

function addPredicate() {
  if (!formState.predicates) formState.predicates = []
  formState.predicates.push({ name: '', args: '' })
}
function removePredicate(index: number) {
  formState.predicates?.splice(index, 1)
}

async function handleFinish() {
  const predicateList = formState.predicates ?? []
  if (predicateList.length === 0) {
    message.error(t('routes.predicate_required'))
    return
  }
  if (!predicateList.some((p) => p?.name === 'Path')) {
    message.error(t('routes.path_predicate_required'))
    return
  }
  if (isAi.value) {
    const ups = (formState.aiUpstreams ?? []).filter((u) => u && u.provider)
    if (ups.length === 0) {
      message.error(t('routes.ai_at_least_one_upstream'))
      return
    }
  } else {
    if (routingMode.value === 'SINGLE' && !formState.serviceId && !formState.uri) {
      message.error(t('routes.uri_required'))
      return
    }
    if (routingMode.value === 'MULTI') {
      if (!formState.services || formState.services.length === 0) {
        message.error(t('routes.at_least_one_service'))
        return
      }
      const total = sumWeights(formState.services)
      if (total !== 100) {
        message.error(t('routes.weight_must_be_100', { total }))
        return
      }
    }
  }

  const body = buildRoutePayload(formState, routingMode.value, grayRulesEnabled.value, {
    services: props.services,
    nacosServices: props.nacosServices,
    existing: props.route,
  })
  if (!body) {
    message.error(t('routes.create_failed'))
    return
  }

  submitting.value = true
  try {
    const ok = await props.onSubmit(body)
    if (ok) emit('cancel')
  } finally {
    submitting.value = false
  }
}

function gotoPluginBindings() {
  router.push(`/instances/${instanceId.value}/plugin_bindings`)
}
</script>

<template>
  <a-modal
    :open="true"
    :footer="null"
    :width="720"
    :class="['route-modal', isEdit ? 'route-edit-modal' : 'route-create-modal']"
    :destroy-on-close="true"
    :mask-closable="true"
    @cancel="emit('cancel')"
  >
    <template #title>
      <div class="modal-header-modern">
        <div class="modal-title-wrapper">
          <div :class="['modal-icon-wrapper', isEdit ? 'edit' : '']">
            <EditOutlined v-if="isEdit" /><PlusOutlined v-else />
          </div>
          <div class="modal-title-text">
            <div class="modal-title">{{ t(isEdit ? 'routes.edit' : 'routes.create') }}</div>
            <div class="modal-subtitle">{{ t(isEdit ? 'routes.edit_description' : 'routes.create_description') }}</div>
          </div>
        </div>
      </div>
    </template>

    <a-form :model="formState" layout="vertical" @finish="handleFinish">
      <!-- Basic info -->
      <div class="form-section-modern">
        <div class="section-header">
          <FileTextOutlined class="section-icon" />
          <span class="section-title">{{ t('routes.basic_info') }}</span>
        </div>
        <div class="section-content">
          <a-form-item :label="t('routes.category')" :tooltip="isEdit ? t('routes.category_locked') : t('routes.category_helper')">
            <a-segmented v-model:value="formState.category" :options="categoryOptions" :disabled="isEdit" />
          </a-form-item>
          <a-form-item :label="t('routes.route_name')" :rules="isEdit ? undefined : [{ required: true }]">
            <a-input v-model:value="formState.routeName" :placeholder="isEdit ? undefined : t('routes.route_name_placeholder')" :disabled="isEdit" size="large" />
          </a-form-item>
          <div class="form-row">
            <a-form-item :label="t('routes.order')" class="form-item-half">
              <a-input v-model:value="formState.order" type="number" placeholder="0" size="large" />
            </a-form-item>
          </div>
          <a-form-item :label="t('routes.description_label')">
            <a-textarea v-model:value="formState.description" :rows="2" :placeholder="t('routes.description_placeholder')" show-count :maxlength="500" />
          </a-form-item>
        </div>
      </div>

      <!-- Target configuration -->
      <div class="form-section-modern">
        <div class="section-header">
          <RobotOutlined v-if="isAi" class="section-icon" /><ApiOutlined v-else class="section-icon" />
          <span class="section-title">{{ isAi ? t('routes.ai_target_configuration') : t('routes.target_configuration') }}</span>
          <span v-if="isAi" class="section-subtitle">{{ t('routes.ai_target_helper') }}</span>
        </div>
        <div class="section-content">
          <AiUpstreamsForm v-if="isAi" :ai-services="aiServices" :ai-upstreams="formState.aiUpstreams ?? []" :model-predicates="formState.modelPredicates ?? []" />
          <template v-else>
            <ServiceBindingForm :form-state="formState" :services="services" :nacos-services="nacosServices" />
            <GrayRulesForm
              v-model:enabled="grayRulesEnabled"
              :rules="formState.grayRules ?? []"
              :configured-services="configuredServices"
              :services="services"
            />
          </template>
        </div>
      </div>

      <!-- Predicates -->
      <div class="form-section-modern">
        <div class="section-header">
          <ThunderboltOutlined class="section-icon" />
          <span class="section-title">{{ t('routes.predicates_section') }}</span>
        </div>
        <div class="section-content">
          <PredicateItem
            v-for="(p, idx) in formState.predicates"
            :key="idx"
            :predicate="p"
            :index="idx"
            @remove="removePredicate"
          />
          <a-button type="dashed" block size="large" class="add-predicate-btn-modern" @click="addPredicate">
            <template #icon><PlusOutlined /></template>{{ t('routes.add_predicate') }}
          </a-button>
        </div>
      </div>

      <!-- Plugins — managed on the plugin binding page -->
      <div class="form-section-modern">
        <div class="section-header">
          <RocketOutlined class="section-icon" />
          <span class="section-title">{{ t('routes.plugins_section') }}</span>
        </div>
        <div class="section-content">
          <a-alert type="info" show-icon :message="t('routes.plugins_guide_title')" :description="t('routes.plugins_guide_desc')" :style="{ marginBottom: '16px' }" />
          <a-button type="dashed" block size="large" @click="gotoPluginBindings">
            <template #icon><LinkOutlined /></template>{{ t('routes.manage_plugin_bindings') }}
          </a-button>
          <span class="ant-typography" :style="{ display: 'block', marginTop: '8px', fontSize: '12px', color: 'var(--text-secondary)' }">
            {{ t('routes.plugins_hint') }}
          </span>
        </div>
      </div>

      <!-- Footer -->
      <div class="modal-footer-modern">
        <a-button size="large" @click="emit('cancel')">{{ t('common.cancel') }}</a-button>
        <a-button type="primary" size="large" html-type="submit" :loading="submitting">
          <template #icon><EditOutlined v-if="isEdit" /><PlusOutlined v-else /></template>
          {{ t(isEdit ? 'common.update' : 'routes.create') }}
        </a-button>
      </div>
    </a-form>
  </a-modal>
</template>
