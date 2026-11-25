<script setup lang="ts">
/**
 * Empty / error / filtered-empty state. The region is labelled by its own
 * title and described by its hint, both wired with a stable useId.
 */
import { useId, type Component } from 'vue'

const { tone = 'idle' } = defineProps<{
  icon: Component
  title: string
  hint?: string
  tone?: 'idle' | 'err'
}>()

const titleId = useId()
const hintId = useId()
</script>

<template>
  <div
    class="vn-panel vn-state"
    role="status"
    :aria-labelledby="titleId"
    :aria-describedby="hint ? hintId : undefined"
  >
    <span :class="['vn-state-glyph', tone === 'err' && 'is-err']" aria-hidden="true">
      <component :is="icon" />
    </span>
    <p :id="titleId" class="vn-state-title">{{ title }}</p>
    <p v-if="hint" :id="hintId" class="vn-state-hint">{{ hint }}</p>
    <div v-if="$slots.actions" class="vn-state-actions">
      <slot name="actions" />
    </div>
  </div>
</template>