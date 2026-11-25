<script setup lang="ts">
/**
 * Upload SSL certificate modal.
 * PEM: file or text input → POST /api/ssl/pem (JSON body).
 * JKS/P12: base64 keystore text → POST /api/ssl/keystore (query params).
 *
 * Two-step layout (type & basic info → certificate content) keeps the action
 * footer outside the scrollable body, so Cancel/Submit stay visible at 1440x900.
 */
import { computed, reactive, ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import type { FormInstance } from 'ant-design-vue'
import {
  CheckOutlined,
  FileAddOutlined,
  FileTextOutlined,
  KeyOutlined,
  SafetyCertificateOutlined,
} from '@/components/icons'
import type { CertificateType, CertificateUploadPayload } from '@/types/certificate'
import { message } from '@/utils/feedback'

const props = defineProps<{
  /** Kept mounted by the page and toggled here (unmounting an open antd modal
   *  leaks its dialog tree; see perf audit 2026-11-20). */
  open?: boolean
  onSubmit: (payload: CertificateUploadPayload) => Promise<void>
}>()
const emit = defineEmits<{ (e: 'cancel'): void }>()

const { t } = useI18n()

const formRef = ref<FormInstance>()
const step = ref(0)

const formState = reactive<{
  certName: string
  domain: string
  certType: CertificateType
  certContent: string
  keyContent: string
  keystoreContent: string
  password: string
}>({
  certName: '',
  domain: '',
  certType: 'PEM',
  certContent: '',
  keyContent: '',
  keystoreContent: '',
  password: '',
})

const submitting = ref(false)
const uploadMode = ref<'file' | 'text'>('file')
const certFileContent = ref('')
const keyFileContent = ref('')

const isOpen = computed(() => props.open !== false)
watch(isOpen, (open) => {
  if (!open) return
  step.value = 0
  Object.assign(formState, {
    certName: '', domain: '', certType: 'PEM' as CertificateType,
    certContent: '', keyContent: '', keystoreContent: '', password: '',
  })
  uploadMode.value = 'file'
  certFileContent.value = ''
  keyFileContent.value = ''
  submitting.value = false
})

const readFileContent = (file: File): Promise<string> =>
  new Promise((resolve, reject) => {
    const reader = new FileReader()
    reader.onload = () => resolve(reader.result as string)
    reader.onerror = () => reject(new Error('Failed to read file'))
    reader.readAsText(file)
  })

function loadFile(target: typeof certFileContent) {
  return async (file: File): Promise<boolean> => {
    const content = await readFileContent(file)
    target.value = content
    message.success(`${file.name} ${t('cert.file_loaded')}`)
    return false
  }
}

const loadCertFile = loadFile(certFileContent)
const loadKeyFile = loadFile(keyFileContent)

async function nextStep() {
  try {
    await formRef.value?.validateFields(['certName', 'domain'])
    step.value = 1
  } catch {
    /* Field errors stay visible in the current section. */
  }
}

async function submitForm() {
  if (submitting.value) return
  try {
    await formRef.value?.validate()
  } catch {
    return
  }
  await handleFinish()
}

async function handleFinish() {
  let payload: CertificateUploadPayload
  if (formState.certType === 'PEM') {
    const certContent = uploadMode.value === 'file' ? certFileContent.value : formState.certContent
    const keyContent = uploadMode.value === 'file' ? keyFileContent.value : formState.keyContent
    if (!certContent || !keyContent) {
      message.error(t('cert.file_required'))
      return
    }
    payload = {
      kind: 'pem',
      certName: formState.certName,
      domain: formState.domain,
      certContent,
      keyContent,
    }
  } else {
    if (!formState.keystoreContent || !formState.password) return
    payload = {
      kind: 'keystore',
      certName: formState.certName,
      domain: formState.domain,
      certType: formState.certType as 'JKS' | 'P12',
      keystoreContent: formState.keystoreContent,
      password: formState.password,
    }
  }
  submitting.value = true
  try {
    await props.onSubmit(payload)
  } finally {
    submitting.value = false
  }
}
</script>

<template>
  <a-modal
    :open="isOpen"
    :width="680"
    :mask-closable="!submitting"
    :closable="!submitting"
    :keyboard="!submitting"
    class="cert-upload-modal"
    @cancel="emit('cancel')"
  >
    <template #title>
      <div class="cert-upload-title">
        <span class="cert-upload-icon-wrap" aria-hidden="true"><SafetyCertificateOutlined /></span>
        <span class="cert-upload-title-text">
          <span class="cert-upload-title-main">{{ t('cert.upload_title') }}</span>
          <span class="cert-upload-subtitle">PEM · JKS · P12</span>
        </span>
      </div>
    </template>

    <a-form ref="formRef" :model="formState" layout="vertical" @finish="handleFinish">
      <div class="cert-upload-body scrollbar-clean">
        <!-- Two-step indicator -->
        <div class="cert-steps">
          <div class="cert-step" :class="{ 'is-active': step === 0, 'is-done': step > 0 }">
            <span class="cert-step-index">
              <CheckOutlined v-if="step > 0" />
              <template v-else>1</template>
            </span>
            <span>{{ t('cert.step_basic') }}</span>
          </div>
          <span class="cert-step-line" :class="{ 'is-done': step > 0 }" />
          <div class="cert-step" :class="{ 'is-active': step === 1 }">
            <span class="cert-step-index">2</span>
            <span>{{ t('cert.cert_content') }}</span>
          </div>
        </div>

        <!-- Step 1: certificate type + basic info -->
        <section v-show="step === 0" class="cert-form-section">
          <header class="cert-form-section-head">
            <span class="cert-form-section-title">{{ t('cert.step_basic') }}</span>
          </header>
          <div class="cert-type-cards" role="radiogroup" :aria-label="t('cert.type')">
            <button
              type="button"
              :class="['cert-type-card', { 'is-selected': formState.certType === 'PEM' }]"
              :aria-pressed="formState.certType === 'PEM'"
              @click="formState.certType = 'PEM'"
            >
              <span class="cert-type-icon"><FileTextOutlined /></span>
              <span class="cert-type-name">{{ t('cert.type_pem') }}</span>
            </button>
            <button
              type="button"
              :class="['cert-type-card', { 'is-selected': formState.certType === 'JKS' }]"
              :aria-pressed="formState.certType === 'JKS'"
              @click="formState.certType = 'JKS'"
            >
              <span class="cert-type-icon"><KeyOutlined /></span>
              <span class="cert-type-name">{{ t('cert.type_jks') }}</span>
            </button>
            <button
              type="button"
              :class="['cert-type-card', { 'is-selected': formState.certType === 'P12' }]"
              :aria-pressed="formState.certType === 'P12'"
              @click="formState.certType = 'P12'"
            >
              <span class="cert-type-icon"><SafetyCertificateOutlined /></span>
              <span class="cert-type-name">{{ t('cert.type_p12') }}</span>
            </button>
          </div>
          <a-row :gutter="16">
            <a-col :span="12">
              <a-form-item name="certName" :label="t('cert.cert_name')" :rules="[{ required: true }]">
                <a-input v-model:value="formState.certName" size="large" :placeholder="t('cert.cert_name_placeholder')" />
              </a-form-item>
            </a-col>
            <a-col :span="12">
              <a-form-item name="domain" :label="t('cert.domain')" :rules="[{ required: true }]">
                <a-input v-model:value="formState.domain" size="large" placeholder="example.com" />
              </a-form-item>
            </a-col>
          </a-row>
        </section>

        <!-- Step 2: certificate content -->
        <section v-show="step === 1" class="cert-form-section">
          <header class="cert-form-section-head">
            <span class="cert-form-section-title">{{ t('cert.cert_content') }}</span>
            <span class="cert-form-section-hint">{{ formState.certType }}</span>
          </header>
          <template v-if="formState.certType === 'PEM'">
            <div class="cert-mode-toggle" role="group">
              <button
                type="button"
                :class="{ 'is-active': uploadMode === 'file' }"
                :aria-pressed="uploadMode === 'file'"
                @click="uploadMode = 'file'"
              >
                <FileAddOutlined />
                {{ t('cert.file_upload') }}
              </button>
              <button
                type="button"
                :class="{ 'is-active': uploadMode === 'text' }"
                :aria-pressed="uploadMode === 'text'"
                @click="uploadMode = 'text'"
              >
                <FileTextOutlined />
                {{ t('cert.text_input') }}
              </button>
            </div>

            <template v-if="uploadMode === 'file'">
              <a-form-item :label="t('cert.cert_file')" required>
                <a-upload-dragger
                  accept=".crt,.cer,.pem"
                  :max-count="1"
                  :before-upload="loadCertFile"
                  @remove="certFileContent = ''"
                >
                  <p class="cert-upload-drag-icon cert-upload-drag-icon-cert">
                    <SafetyCertificateOutlined />
                  </p>
                  <p class="cert-upload-drag-text">{{ t('cert.click_upload') }}</p>
                  <p class="cert-upload-drag-hint">.crt, .cer, .pem</p>
                </a-upload-dragger>
              </a-form-item>
              <a-form-item :label="t('cert.key_file')" required>
                <a-upload-dragger
                  accept=".key,.pem"
                  :max-count="1"
                  :before-upload="loadKeyFile"
                  @remove="keyFileContent = ''"
                >
                  <p class="cert-upload-drag-icon cert-upload-drag-icon-key">
                    <KeyOutlined />
                  </p>
                  <p class="cert-upload-drag-text">{{ t('cert.click_upload_key') }}</p>
                  <p class="cert-upload-drag-hint">.key, .pem</p>
                </a-upload-dragger>
              </a-form-item>
            </template>
            <template v-else>
              <a-form-item name="certContent" :label="t('cert.cert_content')" :rules="[{ required: true }]">
                <a-textarea
                  v-model:value="formState.certContent"
                  :rows="6"
                  placeholder="-----BEGIN CERTIFICATE-----&#10;...&#10;-----END CERTIFICATE-----"
                />
              </a-form-item>
              <a-form-item name="keyContent" :label="t('cert.key_content')" :rules="[{ required: true }]">
                <a-textarea
                  v-model:value="formState.keyContent"
                  :rows="6"
                  placeholder="-----BEGIN PRIVATE KEY-----&#10;...&#10;-----END PRIVATE KEY-----"
                />
              </a-form-item>
            </template>
          </template>

          <template v-else>
            <a-form-item name="keystoreContent" :label="t('cert.keystore_content')" :rules="[{ required: true }]">
              <a-textarea v-model:value="formState.keystoreContent" :rows="6" :placeholder="t('cert.keystore_placeholder')" />
            </a-form-item>
            <a-form-item name="password" :label="t('cert.keystore_password')" :rules="[{ required: true }]">
              <a-input-password v-model:value="formState.password" :placeholder="t('cert.password_placeholder')" />
            </a-form-item>
          </template>
        </section>
      </div>
    </a-form>

    <template #footer>
      <div class="cert-modal-footer">
        <span class="cert-step-progress">
          {{ step + 1 }} / 2 · {{ step === 0 ? t('cert.step_basic') : t('cert.cert_content') }}
        </span>
        <a-button :disabled="submitting" @click="emit('cancel')">{{ t('common.cancel') }}</a-button>
        <a-button v-if="step > 0" :disabled="submitting" @click="step = 0">
          {{ t('cert.previous_step') }}
        </a-button>
        <a-button v-if="step === 0" type="primary" :disabled="submitting" @click="nextStep">
          {{ t('cert.next_step') }}
        </a-button>
        <a-button v-else type="primary" :loading="submitting" @click="submitForm">
          <template #icon><SafetyCertificateOutlined /></template>
          {{ t('common.submit') }}
        </a-button>
      </div>
    </template>
  </a-modal>
</template>