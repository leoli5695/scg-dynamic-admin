<script setup lang="ts">
/**
 * Page header that lives outside any panel: title with an inline gradient
 * subtitle on the same baseline, plus an actions slot.
 */
defineProps<{
  title: string
  lede?: string
}>()
</script>

<template>
  <div class="vn-toolbar">
    <div class="vn-toolbar-copy">
      <h2 class="vn-title">
        <span class="vn-title-text">{{ title }}</span>
        <span v-if="lede" class="vn-lede-inline">{{ lede }}</span>
      </h2>
    </div>
    <div v-if="$slots.actions" class="vn-toolbar-actions">
      <slot name="actions" />
    </div>
  </div>
</template>