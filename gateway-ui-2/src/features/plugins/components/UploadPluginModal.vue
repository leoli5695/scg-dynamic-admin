<script setup lang="ts">
/**
 * Jar upload modal — POST /api/plugins/upload (multipart, instanceId query).
 */
import { computed, ref, watch } from 'vue';
import { useI18n } from 'vue-i18n';
import type { UploadProps } from 'ant-design-vue';
import { CloudUploadOutlined } from '@/components/icons';
import { uploadPlugin } from '@/api/plugins';
import { ApiError } from '@/api/http';
import { message } from '@/utils/feedback';

const props = defineProps<{
  instanceId?: string;
  /** Kept mounted by the page and toggled here (unmounting an open antd modal
   *  leaks its dialog tree; see perf audit 2026-11-20). */
  open?: boolean;
}>();
const emit = defineEmits<{
  (e: 'close'): void;
  (e: 'uploaded'): void;
}>();

const { t } = useI18n();
const uploading = ref(false);
const isOpen = computed(() => props.open !== false);
watch(isOpen, (open) => { if (open) uploading.value = false });

const steps = computed(() => [
  t('plugins.upload_requirement_1'),
  t('plugins.upload_requirement_2'),
  t('plugins.upload_requirement_3'),
]);

const customRequest: NonNullable<UploadProps['customRequest']> = async (options) => {
  uploading.value = true;
  try {
    const plugin = await uploadPlugin(options.file as File, props.instanceId);
    message.success(t('plugins.upload_success'));
    options.onSuccess?.(plugin);
    emit('uploaded');
    emit('close');
  } catch (error) {
    const fallback = t('plugins.upload_failed');
    message.error(error instanceof ApiError && error.message ? error.message : fallback);
    options.onError?.(error as Error);
  } finally {
    uploading.value = false;
  }
};
</script>

<template>
  <a-modal
    :open="isOpen"
    wrap-class-name="plugin-upload-modal"
    :mask-closable="!uploading"
    :closable="!uploading"
    :keyboard="!uploading"
    :footer="null"
    @cancel="emit('close')"
  >
    <template #title>
      <div class="upload-modal-head">
        <span class="upload-modal-icon" aria-hidden="true"><CloudUploadOutlined /></span>
        <span class="upload-modal-title-text">
          <span class="upload-modal-title">{{ t('plugins.upload_title') }}</span>
          <span class="upload-modal-subtitle">{{ t('plugins.upload_hint') }}</span>
        </span>
      </div>
    </template>

    <div class="upload-steps">
      <span class="upload-steps-title">{{ t('plugins.upload_requirements') }}</span>
      <ol class="upload-step-list">
        <li v-for="(step, index) in steps" :key="step" class="upload-step">
          <span class="upload-step-index" aria-hidden="true">{{ index + 1 }}</span>
          <span>{{ step }}</span>
        </li>
      </ol>
    </div>

    <a-upload-dragger
      name="file"
      accept=".jar"
      class="upload-dropzone"
      :show-upload-list="false"
      :disabled="uploading"
      :custom-request="customRequest"
    >
      <p class="upload-drop-icon" aria-hidden="true"><CloudUploadOutlined /></p>
      <p class="upload-drop-text">{{ t('plugins.upload_drag') }}</p>
      <p class="upload-drop-hint">{{ t('plugins.upload_hint') }}</p>
    </a-upload-dragger>
  </a-modal>
</template>