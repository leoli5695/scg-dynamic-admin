<script setup lang="ts">
/**
 * Collapsible JSON pane for the diff comparison. Large payloads start collapsed
 * so a 40KB config snapshot cannot blow up the drawer height.
 */
import { computed, ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import { jsonText } from '../auditUtils'

const props = defineProps<{ value: unknown }>()
const { t } = useI18n()

const text = computed(() => jsonText(props.value))
const activeKey = ref<string[]>([])

watch(text, (value) => {
  activeKey.value = value.length > 500 ? [] : ['1']
}, { immediate: true })
</script>

<template>
  <span v-if="!text" class="audit-muted">{{ t('common.none') }}</span>
  <a-collapse v-else v-model:activeKey="activeKey" ghost class="audit-collapse audit-json-collapse">
    <a-collapse-panel key="1" :header="t('audit.json_chars', { count: text.length })">
      <pre class="audit-pre">{{ text }}</pre>
    </a-collapse-panel>
  </a-collapse>
</template>