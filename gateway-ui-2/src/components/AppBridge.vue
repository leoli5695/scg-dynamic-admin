<script setup lang="ts">
/**
 * Mounts once inside <a-app> to capture the context-aware feedback instances
 * (theme/locale aware) and register them with the global feedback service.
 * Renders nothing.
 */
import { App } from 'ant-design-vue'
import { onMounted } from 'vue'
import { registerFeedbackInstances, type FeedbackApi } from '@/utils/feedback'

const { message, notification, modal } = App.useApp()

onMounted(() => {
  registerFeedbackInstances({
    message: message as unknown as FeedbackApi,
    notification: notification as unknown as FeedbackApi,
    modal: modal as unknown as FeedbackApi,
  })
})
</script>

<template>
  <span style="display: none" />
</template>
