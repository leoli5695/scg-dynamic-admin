<script setup lang="ts">
/**
 * One request/response body panel of an expanded log row. Port of the
 * BodyContent/BodyPanel pair in gateway-ui LogEntryExpanded.tsx: formatted JSON is
 * tokenised into Vue nodes (never innerHTML), raw view compacts it, and bodies
 * above 128KB wait behind an explicit reveal so highlighting cannot block paint.
 */
import { computed, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import { DownloadOutlined, UploadOutlined } from '@/components/icons'
import { LARGE_BODY_CHARS, renderRawJson, tokenizeJson } from '../utils'

const props = defineProps<{
  title: string
  body?: string
  viewMode: 'raw' | 'formatted'
  tone: 'request' | 'response'
}>()

const { t } = useI18n()
const revealed = ref(false)

const isLarge = computed(() => (props.body?.length ?? 0) > LARGE_BODY_CHARS)
const largeSize = computed(() => `${((props.body?.length ?? 0) / 1024).toFixed(1)} KB`)
const tokens = computed(() => (props.viewMode === 'formatted' && !isLarge.value ? tokenizeJson(props.body) : []))
const raw = computed(() => renderRawJson(props.body))
</script>

<template>
  <div class="al-body-panel">
    <div :class="['al-body-head', `is-${tone}`]">
      <span class="al-body-title">
        <DownloadOutlined v-if="tone === 'response'" />
        <UploadOutlined v-else />
        {{ title }}
      </span>
      <span v-if="body" class="al-body-size">{{ body.length }} bytes</span>
      <slot name="actions" />
    </div>
    <div class="al-body-content">
      <div v-if="!body" class="al-body-empty">{{ t('access_log.not_recorded') }}</div>
      <template v-else-if="isLarge">
        <a-alert type="info" show-icon :message="t('access_log.body_too_large', { size: largeSize })">
          <template #action>
            <a-button v-if="!revealed" size="small" @click="revealed = true">
              {{ t('access_log.show_body') }}
            </a-button>
          </template>
        </a-alert>
        <pre v-if="revealed" class="al-pre is-large">{{ body }}</pre>
      </template>
      <pre v-else-if="viewMode === 'formatted'" class="al-pre al-json"><span
        v-for="(token, index) in tokens"
        :key="index"
        :class="`al-json-${token.kind}`"
      >{{ token.text }}</span></pre>
      <div v-else class="al-pre is-raw">{{ raw }}</div>
    </div>
  </div>
</template>