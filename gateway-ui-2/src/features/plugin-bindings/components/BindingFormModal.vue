<script setup lang="ts">
/**
 * Create / edit binding modal — bind type + route picker + order plus the
 * schema-driven plugin config form. Mirrors the React BindingFormModal.
 */
import { computed, reactive, ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import type { FormInstance } from 'ant-design-vue/es/form'
import { CompassOutlined, EditOutlined, GlobalOutlined, SettingOutlined } from '@/components/icons'
import type { Plugin, PluginBindingView, PluginConfigSchema } from '@/types/plugin'
import { GLOBAL_BINDING_ROUTE_ID } from '@/types/plugin'
import type { RouteResponse } from '@/types/route'
import { getCategoryIcon } from '@/features/plugins/utils'
import type { BindingFormPayload } from '../utils'
import ConfigFieldInput from './ConfigFieldInput.vue'

const props = defineProps<{
  mode: 'create' | 'edit'
  plugin: Plugin | null
  schema: PluginConfigSchema
  initialConfig: Record<string, unknown>
  initialOrder: number
  /** Create mode: routes to pick from. */
  routes?: RouteResponse[]
  /** Edit mode: binding being edited. */
  binding?: PluginBindingView
  onSubmit: (payload: BindingFormPayload) => Promise<boolean>
}>()
const emit = defineEmits<{ (e: 'close'): void }>()

const { t } = useI18n()

const isEdit = computed(() => props.mode === 'edit')
const bindType = ref<'GLOBAL' | 'ROUTE'>('GLOBAL')
const formRef = ref<FormInstance>()
const saving = ref(false)

const model = reactive<{
  order: number
  routeId: string | undefined
  config: Record<string, unknown>
}>({
  order: props.initialOrder ?? 0,
  routeId: undefined,
  config: { ...props.initialConfig },
})

const fields = computed(() => props.schema.fields ?? [])
const scopeName = computed(() => (
  props.binding?.routeId === GLOBAL_BINDING_ROUTE_ID
    ? t('plugin_bindings.global')
    : (props.binding?.routeName || '')
))
const routeRules = computed(() => (
  bindType.value === 'ROUTE'
    ? [{ required: true, message: t('plugin_bindings.select_route_required') }]
    : []
))
const routeOptions = computed(() => (props.routes ?? []).map((route) => ({
  value: route.id,
  label: `${route.routeName || route.id} · ${route.uri || ''}${route.enabled === false ? ` (${t('common.disabled')})` : ''}`,
})))

// The editor scroll container would clip an in-place dropdown.
const getPopupContainer = () => document.body

// Leaving ROUTE mode should not keep a stale "select a route" error visible.
watch(bindType, () => {
  formRef.value?.clearValidate(['routeId'])
})

async function handleSubmit() {
  if (saving.value) return
  try {
    await formRef.value?.validate()
  } catch {
    return
  }
  saving.value = true
  try {
    const ok = await props.onSubmit({
      order: model.order ?? 0,
      config: { ...props.initialConfig, ...model.config },
      ...(isEdit.value
        ? {}
        : { bindType: bindType.value, routeId: model.routeId }),
    })
    if (ok) emit('close')
  } finally {
    saving.value = false
  }
}
</script>

<template>
  <a-modal
    :open="true"
    :width="720"
    :footer="null"
    wrap-class-name="plugin-bindings-editor-modal"
    :closable="!saving"
    :keyboard="!saving"
    :mask-closable="false"
    :destroy-on-close="true"
    @cancel="emit('close')"
  >
    <template #title>
      <div v-if="isEdit" class="binding-modal-header">
        <span class="binding-modal-icon edit"><EditOutlined /></span>
        <span class="binding-modal-title-text">
          <span class="binding-modal-title">{{ t('plugin_bindings.edit_title') }}</span>
          <span class="binding-modal-subtitle">
            {{ binding?.pluginName }} · {{ scopeName }}
          </span>
        </span>
      </div>
      <div v-else class="binding-modal-header">
        <span class="binding-modal-icon">
          <component :is="getCategoryIcon(plugin?.category ?? '')" />
        </span>
        <span class="binding-modal-title-text">
          <span class="binding-modal-title">{{ plugin?.pluginName }}</span>
          <span class="binding-modal-subtitle">{{ t('plugin_bindings.create_binding') }}</span>
        </span>
      </div>
    </template>

    <a-form
      ref="formRef"
      layout="vertical"
      :model="model"
      :disabled="saving"
      scroll-to-first-error
    >
      <div class="binding-editor-scroll">
        <div class="form-section">
          <div class="form-section-header">
            <CompassOutlined class="form-section-icon" />
            <span class="form-section-title">{{ t('plugin_bindings.binding_scope') }}</span>
          </div>
          <div class="form-section-content">
            <a-alert class="binding-modal-alert" type="info" show-icon>
              <template #message>
                {{ isEdit ? scopeName : t('plugin_bindings.bind_notice') }}
              </template>
              <template #description>
                {{ isEdit ? binding?.pluginName : t('plugin_bindings.bind_notice_desc') }}
              </template>
            </a-alert>

            <template v-if="!isEdit">
              <a-form-item :label="t('plugin_bindings.binding_type')" required>
                <a-radio-group v-model:value="bindType" class="binding-scope-radio">
                  <a-radio-button value="GLOBAL">
                    <GlobalOutlined /> {{ t('plugin_bindings.global_binding') }}
                  </a-radio-button>
                  <a-radio-button value="ROUTE">
                    <CompassOutlined /> {{ t('plugin_bindings.route_binding') }}
                  </a-radio-button>
                </a-radio-group>
              </a-form-item>

              <a-form-item
                v-if="bindType === 'ROUTE'"
                name="routeId"
                :label="t('plugin_bindings.select_route')"
                :rules="routeRules"
              >
                <a-select
                  v-model:value="model.routeId"
                  show-search
                  option-filter-prop="label"
                  :placeholder="t('plugin_bindings.select_route_placeholder')"
                  :options="routeOptions"
                  :get-popup-container="getPopupContainer"
                />
              </a-form-item>
            </template>

            <a-form-item
              name="order"
              :label="t('plugin_bindings.execution_order')"
              :extra="isEdit ? undefined : t('plugin_bindings.execution_order_help')"
            >
              <a-input-number v-model:value="model.order" :min="-1000" :max="10000" style="width: 100%" />
            </a-form-item>
          </div>
        </div>

        <div class="form-section">
          <div class="form-section-header">
            <SettingOutlined class="form-section-icon" />
            <span class="form-section-title">{{ t('plugin_bindings.plugin_config') }}</span>
          </div>
          <div class="form-section-content">
            <a-alert
              v-if="fields.length === 0"
              class="binding-modal-alert"
              type="info"
              show-icon
            >
              <template #message>{{ t('plugin_bindings.no_config_info') }}</template>
              <template #description>{{ t('plugin_bindings.no_config_desc') }}</template>
            </a-alert>

            <a-form-item
              v-for="field in fields"
              :key="field.name"
              :name="['config', field.name]"
              :label="field.label || field.name"
              :extra="field.description"
              :rules="field.required ? [{ required: true }] : []"
            >
              <ConfigFieldInput
                v-model="model.config[field.name]"
                :field="field"
              />
            </a-form-item>
          </div>
        </div>
      </div>

      <div class="binding-modal-footer">
        <a-button :disabled="saving" @click="emit('close')">{{ t('common.cancel') }}</a-button>
        <a-button type="primary" :loading="saving" @click="handleSubmit">
          <template #icon>
            <EditOutlined v-if="isEdit" />
            <CompassOutlined v-else />
          </template>
          {{ t(isEdit ? 'common.update' : 'common.create') }}
        </a-button>
      </div>
    </a-form>
  </a-modal>
</template>