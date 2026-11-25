<script setup lang="ts">
/**
 * Activate license modal: paste a license code or load it from a text file,
 * validate through POST /api/license/import and report the backend message.
 * Vue-native matte portal; kept mounted and driven through `open`.
 */
import { computed, reactive, ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import type { FormInstance } from 'ant-design-vue'
import { FileTextOutlined, KeyOutlined } from '@/components/icons'
import { importLicense } from '@/api/license'
import { ApiError } from '@/api/http'
import { message } from '@/utils/feedback'

const props = defineProps<{ open: boolean }>()
const emit = defineEmits<{ (e: 'close'): void; (e: 'activated'): void }>()

const { t } = useI18n()

const formRef = ref<FormInstance>()
const formState = reactive({ licenseCode: '' })
const submitting = ref(false)

const rules = computed(() => [
  { required: true, message: t('licenseUI.code_required') },
])

watch(
  () => props.open,
  (open) => {
    if (open) {
      formState.licenseCode = ''
      formRef.value?.clearValidate()
    }
  },
)

async function handleActivate(): Promise<void> {
  submitting.value = true
  try {
    await importLicense(formState.licenseCode)
    message.success(t('licenseUI.activate_success'))
    formState.licenseCode = ''
    emit('close')
    emit('activated')
  } catch (error) {
    message.error(
      error instanceof ApiError && error.message ? error.message : t('licenseUI.activate_failed'),
    )
  } finally {
    submitting.value = false
  }
}

function handleFile(file: File): boolean {
  const reader = new FileReader()
  reader.onload = () => {
    formState.licenseCode = String(reader.result || '').trim()
    formRef.value?.clearValidate()
  }
  reader.readAsText(file)
  return false
}
</script>

<template>
  <a-modal
    :open="open"
    :width="600"
    :footer="null"
    wrap-class-name="vn-portal vnp-portal vnp-modal"
    @cancel="emit('close')"
  >
    <template #title>
      <div class="vnp-modal-head">
        <span class="vnp-modal-head-glyph" aria-hidden="true"><KeyOutlined /></span>
        <div>
          <div class="vnp-modal-title">{{ t('licenseUI.activate_modal_title') }}</div>
          <div class="vnp-modal-subtitle">{{ t('licenseUI.code_hint') }}</div>
        </div>
      </div>
    </template>

    <a-form ref="formRef" layout="vertical" :model="formState" class="vnp-form" @finish="handleActivate">
      <div class="vnp-modal-scroll">
        <a-form-item name="licenseCode" :label="t('licenseUI.code_label')" :rules="rules">
          <a-textarea
            v-model:value="formState.licenseCode"
            :rows="6"
            :placeholder="t('licenseUI.code_placeholder')"
            class="vn-mono"
          />
        </a-form-item>
        <div class="vn-license-activate-hint">
          <KeyOutlined />
          <span>{{ t('licenseUI.code_hint') }}</span>
        </div>
      </div>

      <div class="vnp-modal-foot">
        <a-upload
          accept=".txt,.lic,.json"
          :show-upload-list="false"
          :before-upload="handleFile"
        >
          <a-button>
            <template #icon><FileTextOutlined /></template>{{ t('licenseUI.upload_file') }}
          </a-button>
        </a-upload>
        <a-button :disabled="submitting" @click="emit('close')">{{ t('common.cancel') }}</a-button>
        <a-button type="primary" html-type="submit" :loading="submitting">{{ t('licenseUI.activate_submit') }}</a-button>
      </div>
    </a-form>
  </a-modal>
</template>