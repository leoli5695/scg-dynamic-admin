<script setup lang="ts">
/** Small label + value block used inside the snapshot metric cards. */
withDefaults(defineProps<{
  label: string
  value: string | number
  unit?: string
  tone?: 'default' | 'primary' | 'success' | 'warning' | 'error'
  size?: 'md' | 'lg'
}>(), {
  tone: 'default',
  size: 'md',
})
</script>

<template>
  <div class="monitor-stat">
    <span class="monitor-stat-label">{{ label }}</span>
    <span :class="['monitor-stat-value', `tone-${tone}`, size === 'lg' ? 'is-lg' : '']">
      {{ value }}<em v-if="unit">{{ unit }}</em>
    </span>
  </div>
</template>