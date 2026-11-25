<script setup lang="ts">
/**
 * Copyable code block used by the usage-example modal: a caption row with a
 * copy action above a monospaced, horizontally scrollable snippet.
 */
import { useI18n } from 'vue-i18n'
import { CopyOutlined } from '@/components/icons'
import { message } from '@/utils/feedback'
import { copyText } from '@/utils/clipboard'

const props = defineProps<{
  label?: string
  code?: string
}>()

const { t } = useI18n()

async function copy() {
  if (!props.code) return
  const ok = await copyText(props.code)
  if (ok) message.success(t('message.copy_success'))
  else message.error(t('message.copy_failed'))
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