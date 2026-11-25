<script setup lang="ts" generic="T extends string">
/**
 * Native segmented control — replaces antd Tabs for in-page category switching.
 * No ink bar, no tab panel machinery: just a radio group with a tinted thumb.
 * Generic over the key union so callers keep their literal types.
 */
import type { Component } from 'vue'

export interface VnSegmentOption<K extends string = string> {
  key: K
  label: string
  count?: number
  icon?: Component
}

defineProps<{ options: VnSegmentOption<T>[]; label: string }>()
const model = defineModel<T>({ required: true })
</script>

<template>
  <div class="vn-seg" role="radiogroup" :aria-label="label">
    <button
      v-for="opt in options"
      :key="opt.key"
      type="button"
      role="radio"
      :aria-checked="model === opt.key"
      :class="['vn-seg-item', model === opt.key && 'is-active']"
      @click="model = opt.key"
    >
      <component :is="opt.icon" v-if="opt.icon" />
      <span>{{ opt.label }}</span>
      <span v-if="opt.count !== undefined" class="vn-seg-count">{{ opt.count }}</span>
    </button>
  </div>
</template>