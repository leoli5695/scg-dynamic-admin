<script setup lang="ts">
/**
 * Compact metric strip: one line per cell (icon, label, value), hairline
 * dividers, and a shared bottom rule that recolours for the active filter.
 * Cells double as filters.
 */
import type { Component } from 'vue'

export interface VnStatItem {
  key: string
  value: number
  label: string
  icon?: Component
  /** CSS colour for the icon and the active bottom rule. */
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
      <component :is="item.icon" v-if="item.icon" class="vn-stat-icon" />
      <span class="vn-stat-label">{{ item.label }}</span>
      <span class="vn-stat-value">{{ item.value }}</span>
    </button>
  </div>
</template>