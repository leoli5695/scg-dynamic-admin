<script setup lang="ts">
/** Compact metric card for the Kubernetes overview row (login panel surface). */
defineProps<{
  label: string
  value: number | string
  caption: string
  tone?: 'default' | 'success' | 'accent'
}>()
</script>

<template>
  <div :class="['k8s-stat-card', `is-${tone || 'default'}`]">
    <div class="k8s-stat-top">
      <span class="k8s-stat-label">{{ label }}</span>
      <span class="k8s-stat-glyph" aria-hidden="true"><slot name="icon" /></span>
    </div>
    <div class="k8s-stat-value">{{ value }}</div>
    <div class="k8s-stat-caption">{{ caption }}</div>
  </div>
</template>