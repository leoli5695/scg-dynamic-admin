<script setup lang="ts">
/**
 * Edit-request modal — Vue port of gateway-ui request-replay/components/EditRequestModal.tsx.
 * Shows the prepared replayable request and lets the user tweak path/query/
 * headers/body before executing. Kept mounted by the page and driven through
 * `open`; local edit state re-initialises from the request on every open.
 */
import { computed, reactive, ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import {
  DeleteOutlined, EditOutlined, PlayCircleOutlined, PlusOutlined, ThunderboltOutlined,
} from '@/components/icons'
import type { ReplayableRequest, ReplayOptions } from '@/api/replay'
import VnIconButton from '@/components/vn/VnIconButton.vue'

const props = defineProps<{
  open: boolean
  request: ReplayableRequest | null
  replaying: boolean
}>()
const emit = defineEmits<{
  (e: 'cancel'): void
  (e: 'execute', quick: boolean, options: ReplayOptions): void
}>()

const { t } = useI18n()

const edit = reactive({ path: '', queryString: '', body: '' })
const editHeaders = ref<Record<string, string>>({})
const newHeaderKey = ref('')
const newHeaderValue = ref('')

function initFromRequest() {
  const request = props.request
  edit.path = request?.path || ''
  edit.queryString = request?.queryString || ''
  edit.body = request?.requestBody || ''
  editHeaders.value = { ...(request?.headers || {}) }
  newHeaderKey.value = ''
  newHeaderValue.value = ''
}

watch(() => [props.open, props.request] as const, ([open]) => {
  if (open && props.request) initFromRequest()
}, { immediate: true })

const methodOptions = ['GET', 'POST', 'PUT', 'DELETE', 'PATCH'].map((m) => ({ value: m, label: m }))
const hasBody = computed(() => ['POST', 'PUT', 'PATCH'].includes(props.request?.method ?? ''))
const headerEntries = computed(() => Object.entries(editHeaders.value))
const originalStatusTone = computed(() => ((props.request?.originalStatusCode ?? 0) >= 400 ? 'vn-ink-err' : 'vn-ink-ok'))

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
  const request = props.request
  if (!request) return
  const options: ReplayOptions = quick
    ? {}
    : {
        modifiedPath: edit.path !== request.path ? edit.path : undefined,
        modifiedQueryString: edit.queryString !== request.queryString ? edit.queryString : undefined,
        modifiedHeaders: editHeaders.value,
        // The server merges modifiedHeaders; omissions alone do not remove captured credentials.
        // Compare case-insensitively so deleting and re-adding a header with new casing keeps it.
        removedHeaders: Object.keys(request.headers || {}).filter(
          (key) => !Object.keys(editHeaders.value).some((editedKey) => editedKey.toLowerCase() === key.toLowerCase()),
        ),
        modifiedBody: edit.body !== request.requestBody ? edit.body : undefined,
        compareWithOriginal: true,
      }
  emit('execute', quick, options)
}
</script>

<template>
  <a-modal
    :open="open"
    :width="600"
    :footer="null"
    :style="{ top: '24px' }"
    wrap-class-name="vn-portal vn-modal"
    :closable="!replaying"
    :keyboard="!replaying"
    :mask-closable="false"
    @cancel="emit('cancel')"
  >
    <template #title>
      <div class="vn-modal-head">
        <span class="vn-modal-head-glyph" aria-hidden="true"><EditOutlined /></span>
        <div>
          <div class="vn-modal-title">{{ t('replay.edit_request') }}</div>
          <div class="vn-modal-subtitle vn-mono">{{ request?.method }} {{ request?.path }}</div>
        </div>
      </div>
    </template>

    <a-form v-if="request" layout="vertical" :model="edit" :disabled="replaying" class="vn-form" @finish="handleExecute(false)">
      <div class="vn-modal-scroll">
        <section class="form-section-modern">
          <div class="section-header">
            <EditOutlined class="section-icon" />
            <span class="section-title">{{ t('strategy.basic_info') }}</span>
          </div>
          <div class="section-content">
            <div class="replay-original-stats">
              <div class="replay-original-stat">
                <span class="vn-kv-label">{{ t('replay.original_status') }}</span>
                <span :class="['replay-original-value', originalStatusTone]">{{ request.originalStatusCode }}</span>
              </div>
              <div class="replay-original-stat">
                <span class="vn-kv-label">{{ t('replay.original_latency') }}</span>
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
          </div>
        </section>

        <section class="form-section-modern">
          <div class="section-header">
            <PlusOutlined class="section-icon" />
            <span class="section-title">{{ t('replay.headers') }}</span>
          </div>
          <div class="section-content">
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
                <span class="vn-mono replay-header-key">{{ key }}</span>
                <span class="replay-header-value" :title="value">{{ value }}</span>
                <VnIconButton
                  :icon="DeleteOutlined"
                  :label="t('replay.remove_header')"
                  danger
                  @click="removeHeader(key)"
                />
              </div>
            </div>
          </div>
        </section>

        <section class="form-section-modern">
          <div class="section-header">
            <EditOutlined class="section-icon" />
            <span class="section-title">{{ t('replay.request_body') }}</span>
          </div>
          <div class="section-content">
            <a-textarea
              v-if="hasBody"
              v-model:value="edit.body"
              :rows="8"
              class="replay-body-input"
            />
            <p v-else class="replay-body-absent">{{ request.method }} · {{ t('replay.request_body') }} —</p>
          </div>
        </section>
      </div>

      <div class="modal-footer-modern">
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