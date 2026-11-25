<script setup lang="ts">
/**
 * Page header that lives outside any panel: eyebrow, title, one-line lede and
 * an actions slot. Replaces the icon-tile + gradient-title header.
 */
defineProps<{
  eyebrow?: string
  title: string
  lede?: string
}>()
</script>

<template>
  <div class="vn-toolbar">
    <div class="vn-toolbar-copy">
      <span v-if="eyebrow" class="vn-eyebrow">{{ eyebrow }}</span>
      <h2 class="vn-title">{{ title }}</h2>
      <p v-if="lede" class="vn-lede">{{ lede }}</p>
    </div>
    <div v-if="$slots.actions" class="vn-toolbar-actions">
      <slot name="actions" />
    </div>
  </div>
</template>