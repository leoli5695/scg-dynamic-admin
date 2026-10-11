<script setup lang="ts">
/**
 * Mounts once inside <a-app> to capture the context-aware feedback instances
 * (theme/locale aware) and register them with the global feedback service.
 * Renders nothing.
 */
import { App } from 'ant-design-vue'
import { onMounted } from 'vue'
import { registerFeedbackInstances } from '@/utils/feedback'

const { message, notification, modal } = App.useApp()

onMounted(() => {
  registerFeedbackInstances({ message, notification, modal })
})
</script>

<template>
  <span style="display: none" />
</template>
