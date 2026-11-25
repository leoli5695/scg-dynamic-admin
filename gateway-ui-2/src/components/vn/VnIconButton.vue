<script setup lang="ts">
/**
 * Square icon action with a tooltip. Row actions keep the `action-btn` hook the
 * console's interaction regression selects on; `inline` affordances (copy id)
 * use `copy-btn` so they do not shift the row-action indices.
 */
import type { Component } from 'vue'

const { danger = false, disabled = false, inline = false } = defineProps<{
  icon: Component
  label: string
  danger?: boolean
  disabled?: boolean
  inline?: boolean
}>()
const emit = defineEmits<{ (e: 'click', ev: MouseEvent): void }>()
</script>

<template>
  <a-tooltip :title="label" overlay-class-name="vn-tooltip">
    <a-button
      type="text"
      size="small"
      :class="[inline ? 'copy-btn' : 'action-btn', 'vn-iconbtn']"
      :danger="danger"
      :disabled="disabled"
      :aria-label="label"
      @click="emit('click', $event)"
    >
      <template #icon><component :is="icon" /></template>
    </a-button>
  </a-tooltip>
</template>