<script setup lang="ts">
/**
 * Route count chips (total, enabled, disabled), rendered inline in the page header.
 */
import { useI18n } from 'vue-i18n'
import InlineStatChips from '@/components/InlineStatChips.vue'

const props = defineProps<{ total: number; enabled: number; disabled: number }>()
const { t } = useI18n()
</script>

<template>
  <InlineStatChips
    :items="[
      { key: 'total', value: props.total, label: t('routes.stats_routes') },
      { key: 'enabled', value: props.enabled, label: t('routes.stats_enabled'), tone: 'green' },
      { key: 'disabled', value: props.disabled, label: t('routes.stats_disabled'), tone: 'red' },
    ]"
  />
</template>
