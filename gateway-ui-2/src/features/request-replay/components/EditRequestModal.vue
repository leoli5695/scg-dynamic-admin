<script setup lang="ts">
/**
 * Edit-request modal — Vue port of gateway-ui request-replay/components/EditRequestModal.tsx.
 * Shows the prepared replayable request and lets the user tweak path/query/
 * headers/body before executing. Mounted with a key per trace (v-if in the
 * parent) so local edit state resets for each prepared request.
 */
import { computed, reactive, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import {
  DeleteOutlined, EditOutlined, PlayCircleOutlined, PlusOutlined, ThunderboltOutlined,
} from '@/components/icons'
import type { ReplayableRequest, ReplayOptions } from '@/api/replay'

const props = defineProps<{
  request: ReplayableRequest
  replaying: boolean
}>()
const emit = defineEmits<{
  (e: 'cancel'): void
  (e: 'execute', quick: boolean, options: ReplayOptions): void
}>()

const { t } = useI18n()

const edit = reactive({
  path: props.request.path || '',
  queryString: props.request.queryString || '',
  body: props.request.requestBody || '',
})
const editHeaders = ref<Record<string, string>>({ ...(props.request.headers || {}) })
const newHeaderKey = ref('')
const newHeaderValue = ref('')

const methodOptions = ['GET', 'POST', 'PUT', 'DELETE', 'PATCH'].map((m) => ({ value: m, label: m }))
const hasBody = computed(() => ['POST', 'PUT', 'PATCH'].includes(props.request.method))
const headerEntries = computed(() => Object.entries(editHeaders.value))
const originalStatusClass = computed(() => (props.request.originalStatusCode >= 400 ? 'is-error' : 'is-success'))

function addHeader() {
  const key = newHeaderKey.value.trim()
  if (key) {
    editHeaders.value = { ...editHeaders.value, [key]: newHeaderValue.value }
    newHeaderKey.value = ''
    newHeaderValue.value = ''
  }
}

function removeHeader(key: string) {
  const next = { ...editHeaders.value }
  delete next[key]
  editHeaders.value = next
}

function handleExecute(quick: boolean) {
  const options: ReplayOptions = quick
    ? {}
    : {
        modifiedPath: edit.path !== props.request.path ? edit.path : undefined,
        modifiedQueryString: edit.queryString !== props.request.queryString ? edit.queryString : undefined,
        modifiedHeaders: editHeaders.value,
        // The server merges modifiedHeaders; omissions alone do not remove captured credentials.
        // Compare case-insensitively so deleting and re-adding a header with new casing keeps it.
        removedHeaders: Object.keys(props.request.headers || {}).filter(
          (key) => !Object.keys(editHeaders.value).some((editedKey) => editedKey.toLowerCase() === key.toLowerCase()),
        ),
        modifiedBody: edit.body !== props.request.requestBody ? edit.body : undefined,
        compareWithOriginal: true,
      }
  emit('execute', quick, options)
}
</script>

<template>
  <a-modal
    :open="true"
    :width="720"
    :footer="null"
    wrap-class-name="replay-editor-modal"
    :closable="!replaying"
    :keyboard="!replaying"
    :mask-closable="false"
    @cancel="emit('cancel')"
  >
    <template #title>
      <div class="replay-modal-header">
        <span class="replay-modal-icon" aria-hidden="true"><EditOutlined /></span>
        <span class="replay-modal-title-text">
          <span class="replay-modal-title">{{ t('replay.edit_request') }}</span>
          <span class="replay-modal-subtitle">{{ request.method }} {{ request.path }}</span>
        </span>
      </div>
    </template>

    <a-form layout="vertical" :model="edit" :disabled="replaying" @finish="handleExecute(false)">
      <div class="replay-editor-scroll">
        <section class="replay-form-section">
          <header class="replay-form-section-head">
            <span class="replay-form-section-index">01</span>
            <span class="lg-section-title">{{ t('strategy.basic_info') }}</span>
          </header>

          <div class="replay-original-stats">
            <div class="replay-original-stat">
              <span class="lg-section-title">{{ t('replay.original_status') }}</span>
              <span :class="['replay-original-value', originalStatusClass]">{{ request.originalStatusCode }}</span>
            </div>
            <div class="replay-original-stat">
              <span class="lg-section-title">{{ t('replay.original_latency') }}</span>
              <span class="replay-original-value">{{ request.originalLatencyMs }}<small>ms</small></span>
            </div>
          </div>

          <div class="replay-method-path">
            <a-select
              :value="request.method"
              :options="methodOptions"
              disabled
              class="replay-method-select"
            />
            <a-form-item name="path" :rules="[{ required: true }]" class="replay-path-item">
              <a-input v-model:value="edit.path" :aria-label="t('replay.path')" placeholder="/api/users">
                <template #addonBefore>{{ t('replay.path') }}</template>
              </a-input>
            </a-form-item>
          </div>
          <a-input
            v-model:value="edit.queryString"
            :aria-label="t('replay.query_string')"
            :placeholder="t('replay.query_string')"
            class="replay-query-input"
          >
            <template #addonBefore>{{ t('replay.query_string') }}</template>
          </a-input>
        </section>

        <section class="replay-form-section">
          <header class="replay-form-section-head">
            <span class="replay-form-section-index">02</span>
            <span class="lg-section-title">{{ t('replay.headers') }}</span>
          </header>

          <div class="replay-header-add">
            <a-input
              v-model:value="newHeaderKey"
              :placeholder="t('replay.header_name')"
              class="replay-header-key-input"
            />
            <a-input
              v-model:value="newHeaderValue"
              :placeholder="t('replay.header_value')"
              class="replay-header-value-input"
            />
            <a-button
              type="primary"
              :aria-label="t('replay.add_header')"
              class="replay-header-add-btn"
              @click="addHeader"
            >
              <template #icon><PlusOutlined /></template>
            </a-button>
          </div>
          <div class="replay-header-list">
            <div v-for="[key, value] in headerEntries" :key="key" class="replay-header-row">
              <span class="replay-mono replay-header-key">{{ key }}</span>
              <span class="replay-header-value" :title="value">{{ value }}</span>
              <a-button
                type="text"
                size="small"
                danger
                class="action-btn replay-header-remove"
                :aria-label="t('replay.remove_header')"
                @click="removeHeader(key)"
              >
                <template #icon><DeleteOutlined /></template>
              </a-button>
            </div>
          </div>
        </section>

        <section class="replay-form-section">
          <header class="replay-form-section-head">
            <span class="replay-form-section-index">03</span>
            <span class="lg-section-title">{{ t('replay.request_body') }}</span>
          </header>

          <a-textarea
            v-if="hasBody"
            v-model:value="edit.body"
            :rows="8"
            class="replay-body-input"
          />
          <p v-else class="replay-body-absent">{{ request.method }} · {{ t('replay.request_body') }} —</p>
        </section>
      </div>

      <div class="replay-modal-footer">
        <a-button :disabled="replaying" class="replay-quick-btn" @click="handleExecute(true)">
          <template #icon><ThunderboltOutlined /></template>
          {{ t('replay.quick_replay') }}
        </a-button>
        <a-button :disabled="replaying" @click="emit('cancel')">{{ t('common.cancel') }}</a-button>
        <a-button type="primary" html-type="submit" :loading="replaying">
          <template #icon><PlayCircleOutlined /></template>
          {{ t('replay.execute') }}
        </a-button>
      </div>
    </a-form>
  </a-modal>
</template>