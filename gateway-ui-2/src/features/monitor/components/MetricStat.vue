<script setup lang="ts">
/** Small label + value block used inside the snapshot metric cards (vn ink). */
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

const toneClass: Record<string, string> = {
  default: '',
  primary: 'vn-monitor-primary',
  success: 'vn-ink-ok',
  warning: 'vn-ink-warn',
  error: 'vn-ink-err',
}
</script>

<template>
  <div class="vn-monitor-stat">
    <span class="vn-monitor-stat-label">{{ label }}</span>
    <span :class="['vn-monitor-stat-value', toneClass[tone], size === 'lg' ? 'is-lg' : '']">
      {{ value }}<em v-if="unit">{{ unit }}</em>
    </span>
  </div>
</template>