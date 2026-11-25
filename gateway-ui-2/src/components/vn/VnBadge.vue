<script setup lang="ts">
/**
 * Status badge: a dot plus a label on a faint tint. Deliberately not a filled
 * capsule, so it never competes with the solid green primary action.
 */
const { tone = 'idle', dot = true } = defineProps<{
  tone?: 'ok' | 'warn' | 'err' | 'info' | 'idle'
  dot?: boolean
}>()
</script>

<template>
  <span :class="['vn-badge', `vn-tone-${tone}`]">
    <i v-if="dot" class="vn-badge-dot" aria-hidden="true" />
    <slot />
  </span>
</template>