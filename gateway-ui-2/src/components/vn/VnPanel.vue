<script setup lang="ts">
/**
 * Flat surface container. Panels nest: a VnPanel rendered inside another one
 * automatically drops to the sunken surface, so a card can hold an inset
 * sub-panel without the caller passing a variant.
 */
import { computed, inject, provide } from 'vue'
import { VN_PANEL_DEPTH } from './context'

const { sunken = false, flush = false } = defineProps<{
  sunken?: boolean
  flush?: boolean
}>()

const depth = inject(VN_PANEL_DEPTH, 0)
provide(VN_PANEL_DEPTH, depth + 1)

const isSunken = computed(() => sunken || depth > 0)
</script>

<template>
  <section
    :class="['vn-panel', isSunken && 'vn-panel-sunken', flush && 'vn-panel-flush']"
  >
    <header v-if="$slots.head" class="vn-panel-head">
      <slot name="head" />
    </header>
    <div class="vn-panel-body">
      <slot />
    </div>
    <footer v-if="$slots.foot" class="vn-panel-foot">
      <slot name="foot" />
    </footer>
  </section>
</template>