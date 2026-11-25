<script setup lang="ts">
/**
 * Copyable code block used by the usage-example modal: a caption row with a
 * copy action above a monospaced, horizontally scrollable snippet.
 */
import { useI18n } from 'vue-i18n'
import { CopyOutlined } from '@/components/icons'
import { message } from '@/utils/feedback'

const props = defineProps<{
  label?: string
  code?: string
}>()

const { t } = useI18n()

function copy() {
  if (!props.code) return
  navigator.clipboard.writeText(props.code)
  message.success(t('message.copy_success'))
}
</script>

<template>
  <div class="auth-code-block">
    <div class="auth-code-head">
      <span class="auth-code-label">{{ label }}</span>
      <a-button type="text" size="small" class="auth-code-copy" @click="copy">
        <template #icon><CopyOutlined /></template>
        {{ t('common.copy') }}
      </a-button>
    </div>
    <pre class="auth-code-body">{{ code }}</pre>
  </div>
</template>