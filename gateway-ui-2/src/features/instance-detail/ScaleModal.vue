<script setup lang="ts">
/**
 * Scale modal — replicas and/or spec modification.
 * Request body aligned with dto/ScaleInstanceRequest.java.
 * Mounted only while open (parent v-if), so initial state comes from the
 * instance on each open.
 */
import { ref } from 'vue'
import { useI18n } from 'vue-i18n'
import { scaleInstance } from '@/api/instances'
import { ApiError } from '@/api/http'
import type { GatewayInstance, ScaleInstanceRequest } from '@/types/instance'
import { getSpecText } from '@/utils/instance'
import { message } from '@/utils/feedback'

const props = defineProps<{ instance: GatewayInstance }>()
const emit = defineEmits<{ (e: 'close'): void; (e: 'scaled'): void }>()

const { t } = useI18n()

/** Preset spec values (must match backend InstanceSpec enum). */
const SPEC_VALUES: Record<string, { cpu: number; memory: number }> = {
  small: { cpu: 0.5, memory: 512 },
  medium: { cpu: 1, memory: 1024 },
  large: { cpu: 2, memory: 2048 },
  xlarge: { cpu: 4, memory: 4096 },
}

const replicas = ref(props.instance.replicas || 1)
const specType = ref('')
const cpuCores = ref(props.instance.cpuCores || 1)
const memoryMB = ref(props.instance.memoryMB || 1024)
const loading = ref(false)

const specOptions = [
  { value: '', label: t('instance.spec_no_change') || '不修改规格' },
  { value: 'small', label: t('instance.spec_small') || '小型 (0.5C 512MB)' },
  { value: 'medium', label: t('instance.spec_medium') || '中型 (1C 1024MB)' },
  { value: 'large', label: t('instance.spec_large') || '大型 (2C 2048MB)' },
  { value: 'xlarge', label: t('instance.spec_xlarge') || '超大型 (4C 4096MB)' },
  { value: 'custom', label: t('instance.spec_custom') || '自定义' },
]

function handleSpecChange(value: string) {
  specType.value = value
  if (value === 'custom') {
    cpuCores.value = props.instance.cpuCores || 1
    memoryMB.value = props.instance.memoryMB || 1024
  } else if (value && SPEC_VALUES[value]) {
    cpuCores.value = SPEC_VALUES[value].cpu
    memoryMB.value = SPEC_VALUES[value].memory
  }
}

async function handleOk() {
  loading.value = true
  try {
    const body: ScaleInstanceRequest = { replicas: replicas.value }
    if (specType.value) {
      body.specType = specType.value
      if (specType.value === 'custom') {
        body.cpuCores = cpuCores.value
        body.memoryMB = memoryMB.value
      }
    }
    await scaleInstance(props.instance.id, body)
    const successMsg = specType.value && specType.value !== props.instance.specType
      ? (t('instance.scale_and_spec_success') || '扩缩容和规格修改成功，Pod正在重启')
      : t('instance.scale_success')
    message.success(successMsg)
    emit('close')
    emit('scaled')
  } catch (error) {
    const msg = error instanceof ApiError && error.message ? error.message : t('instance.scale_failed')
    message.error(msg)
  } finally {
    loading.value = false
  }
}
</script>

<template>
  <a-modal
    :open="true"
    :title="t('instance.scale_title') || '扩缩容与规格修改'"
    :confirm-loading="loading"
    :ok-text="t('instance.scale_confirm') || '确认'"
    :cancel-text="t('common.cancel') || '取消'"
    :width="500"
    @ok="handleOk"
    @cancel="emit('close')"
  >
    <a-form layout="vertical">
      <a-form-item :label="t('instance.current_replicas') || '当前副本数'">
        <span class="ant-typography">{{ instance.replicas || 0 }} Pods</span>
      </a-form-item>
      <a-form-item :label="t('instance.target_replicas') || '目标副本数'" required>
        <a-input-number v-model:value="replicas" :min="1" :max="10" :style="{ width: '100%' }" />
        <div class="ant-typography" style="color: var(--text-secondary); font-size: 12px">
          {{ t('instance.replicas_hint') || '支持 1-10 个副本，修改副本数不会重启 Pod' }}
        </div>
      </a-form-item>

      <a-form-item :label="t('instance.current_spec') || '当前规格'">
        <span class="ant-typography">{{ getSpecText(instance, t) }} ({{ instance.cpuCores || 0 }}C {{ instance.memoryMB || 0 }}MB)</span>
      </a-form-item>
      <a-form-item :label="t('instance.target_spec') || '目标规格'">
        <a-select :value="specType" :options="specOptions" :style="{ width: '100%' }" @change="handleSpecChange" />
        <div class="ant-typography" style="color: var(--text-secondary); font-size: 12px">
          {{ t('instance.spec_hint') || '修改规格会触发 Pod 重启' }}
        </div>
      </a-form-item>

      <template v-if="specType === 'custom'">
        <a-form-item :label="t('instance.cpu_cores') || 'CPU 核数'" required>
          <a-input-number v-model:value="cpuCores" :min="0.5" :max="8" :step="0.5" :style="{ width: '100%' }" suffix="核" />
        </a-form-item>
        <a-form-item :label="t('instance.memory_mb') || '内存大小'" required>
          <a-input-number v-model:value="memoryMB" :min="256" :max="8192" :step="256" :style="{ width: '100%' }" suffix="MB" />
        </a-form-item>
      </template>

      <div
        v-if="specType !== '' && specType !== instance.specType"
        :style="{ padding: '8px 12px', background: 'rgba(234,179,8,0.1)', border: '1px solid rgba(234,179,8,0.3)', borderRadius: '4px', marginTop: '8px' }"
      >
        <span class="ant-typography" style="color: var(--warning-color)">
          ⚠️ {{ t('instance.spec_change_warning') || '规格修改将触发 Pod 重启，服务可能会有短暂中断' }}
        </span>
      </div>
    </a-form>
  </a-modal>
</template>
