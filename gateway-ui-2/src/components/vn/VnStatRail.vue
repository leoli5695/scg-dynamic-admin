<script setup lang="ts">
/**
 * Metric rail: one flat panel divided by hairlines. Cells double as filters —
 * the active cell grows an accent underline rather than becoming a filled pill.
 */
import type { Component } from 'vue'

export interface VnStatItem {
  key: string
  value: number
  label: string
  icon?: Component
  /** CSS colour for the icon row and the active underline. */
  accent?: string
  active?: boolean
}

defineProps<{ items: VnStatItem[]; label: string }>()
const emit = defineEmits<{ (e: 'select', key: string): void }>()
</script>

<template>
  <div class="vn-panel vn-statrail" role="group" :aria-label="label">
    <button
      v-for="item in items"
      :key="item.key"
      type="button"
      :class="['vn-stat', item.active && 'is-active']"
      :style="item.accent ? { '--vn-stat-accent': item.accent } : undefined"
      :aria-pressed="item.active ?? false"
      @click="emit('select', item.key)"
    >
      <span class="vn-stat-top">
        <component :is="item.icon" v-if="item.icon" />
        <span class="vn-stat-label">{{ item.label }}</span>
      </span>
      <span class="vn-stat-value">{{ item.value }}</span>
    </button>
  </div>
</template>