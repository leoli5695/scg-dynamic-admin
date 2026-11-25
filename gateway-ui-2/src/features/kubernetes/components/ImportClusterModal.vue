<script setup lang="ts">
/**
 * Import cluster modal: name/description + kubeconfig, with an optional
 * test-connection probe before saving. Kept mounted; driven by `open`.
 */
import { computed, reactive, ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import type { FormInstance } from 'ant-design-vue'
import { ApiOutlined, ClusterOutlined } from '@/components/icons'
import { importCluster, testKubeconfigConnection } from '@/api/kubernetes'
import { ApiError } from '@/api/http'
import type { ConnectionTestResult } from '@/types/kubernetes'
import { message } from '@/utils/feedback'

const props = defineProps<{
  /** Kept mounted by the page and toggled here (unmounting an open antd modal
   *  leaks its dialog tree; see perf audit 2026-11-20). */
  open?: boolean
}>()
const emit = defineEmits<{
  (e: 'close'): void
  (e: 'imported'): void
}>()

const { t } = useI18n()

const formRef = ref<FormInstance>()
const form = reactive({ clusterName: '', description: '', kubeconfig: '' })
const testing = ref(false)
const importing = ref(false)
const testResult = ref<ConnectionTestResult | null>(null)
const getModalContainer = () => document.body
const isOpen = computed(() => props.open !== false)
watch(isOpen, (open) => {
  if (!open) return
  form.clusterName = ''
  form.description = ''
  form.kubeconfig = ''
  testing.value = false
  importing.value = false
  testResult.value = null
})

const rules = computed(() => ({
  clusterName: [{ required: true, message: t('k8s.cluster_name_required') }],
  kubeconfig: [{ required: true, message: t('k8s.kubeconfig_required') }],
}))

const errorMessage = (error: unknown, fallbackKey: string): string =>
  (error instanceof ApiError && error.message ? error.message : t(fallbackKey))

async function handleTestConnection() {
  if (!form.kubeconfig.trim()) {
    message.warning(t('k8s.kubeconfig_required'))
    return
  }
  testing.value = true
  testResult.value = null
  try {
    const result = await testKubeconfigConnection(form.kubeconfig)
    testResult.value = result
    if (result.success) message.success(t('k8s.test_success'))
    else message.error(`${t('k8s.test_failed')}: ${result.error ?? ''}`)
  } catch (error) {
    message.error(errorMessage(error, 'k8s.test_failed'))
  } finally {
    testing.value = false
  }
}

async function handleImport() {
  try {
    await formRef.value?.validate()
  } catch {
    return
  }
  importing.value = true
  try {
    await importCluster({
      clusterName: form.clusterName,
      kubeconfig: form.kubeconfig,
      description: form.description || undefined,
    })
    message.success(t('k8s.import_success'))
    formRef.value?.resetFields()
    testResult.value = null
    emit('imported')
    emit('close')
  } catch (error) {
    message.error(errorMessage(error, 'k8s.import_failed'))
  } finally {
    importing.value = false
  }
}

function handleCancel() {
  testResult.value = null
  emit('close')
}
</script>

<template>
  <a-modal
    :open="isOpen"
    :width="700"
    :mask-closable="true"
    :get-container="getModalContainer"
    wrap-class-name="k8s-import-modal"
    @cancel="handleCancel"
  >
    <template #title>
      <div class="k8s-modal-head">
        <span class="k8s-modal-glyph" aria-hidden="true"><ClusterOutlined /></span>
        <div class="k8s-modal-heading">
          <span class="k8s-modal-title">{{ t('k8s.import_title') }}</span>
          <span class="k8s-modal-subtitle">{{ t('k8s.import_subtitle') }}</span>
        </div>
      </div>
    </template>

    <a-alert type="info" show-icon :message="t('k8s.import_info')" class="k8s-modal-alert" />

    <a-form ref="formRef" :model="form" :rules="rules" layout="vertical" class="k8s-form">
      <section class="k8s-form-section">
        <div class="k8s-section-head">
          <ClusterOutlined class="k8s-section-glyph" />
          <span class="k8s-section-title">{{ t('k8s.basic_info') }}</span>
        </div>
        <a-form-item name="clusterName" :label="t('k8s.cluster_name')">
          <a-input
            v-model:value="form.clusterName"
            :placeholder="t('k8s.cluster_name_placeholder')"
          />
        </a-form-item>
        <a-form-item name="description" :label="t('common.description')" class="k8s-form-item-last">
          <a-input
            v-model:value="form.description"
            :placeholder="t('k8s.description_placeholder')"
          />
        </a-form-item>
      </section>

      <section class="k8s-form-section">
        <div class="k8s-section-head">
          <ApiOutlined class="k8s-section-glyph" />
          <span class="k8s-section-title">{{ t('k8s.kubeconfig') }}</span>
          <a-button
            type="link"
            size="small"
            class="k8s-section-action"
            :loading="testing"
            @click="handleTestConnection"
          >
            <template #icon><ApiOutlined /></template>
            {{ t('k8s.test_connection') }}
          </a-button>
        </div>
        <a-form-item name="kubeconfig" class="k8s-form-item-last">
          <a-textarea
            v-model:value="form.kubeconfig"
            :rows="8"
            class="k8s-kubeconfig-input"
            :placeholder="t('k8s.kubeconfig_placeholder')"
            @input="testResult = null"
          />
        </a-form-item>
      </section>

      <a-alert
        v-if="testResult"
        :type="testResult.success ? 'success' : 'error'"
        show-icon
        :message="testResult.success ? t('k8s.test_success') : t('k8s.test_failed')"
        class="k8s-modal-alert k8s-modal-alert-last"
      >
        <template #description>
          <template v-if="testResult.success">
            <p class="k8s-test-line"><strong>{{ t('k8s.server_url') }}:</strong> {{ testResult.serverUrl }}</p>
            <p class="k8s-test-line"><strong>{{ t('k8s.context') }}:</strong> {{ testResult.contextName }}</p>
            <p v-if="testResult.clusterVersion" class="k8s-test-line">
              <strong>{{ t('k8s.cluster_version') }}:</strong> {{ testResult.clusterVersion }}
            </p>
            <p v-if="testResult.nodeCount !== undefined" class="k8s-test-line">
              <strong>{{ t('k8s.nodes') }}:</strong> {{ testResult.nodeCount }}
            </p>
            <p v-if="testResult.namespaceCount !== undefined" class="k8s-test-line">
              <strong>{{ t('k8s.namespace') }}:</strong> {{ testResult.namespaceCount }}
            </p>
          </template>
          <template v-else>
            <p v-if="testResult.serverUrl" class="k8s-test-line">
              <strong>{{ t('k8s.server_url') }}:</strong> {{ testResult.serverUrl }}
            </p>
            <p class="k8s-test-line k8s-test-error">{{ testResult.error }}</p>
          </template>
        </template>
      </a-alert>
    </a-form>

    <template #footer>
      <div class="k8s-modal-foot-actions">
        <a-button @click="handleCancel">{{ t('common.cancel') }}</a-button>
        <a-button type="primary" :loading="importing" @click="handleImport">
          {{ t('common.confirm') }}
        </a-button>
      </div>
    </template>
  </a-modal>
</template>