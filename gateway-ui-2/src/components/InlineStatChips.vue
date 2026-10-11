<script setup lang="ts">
/**
 * Compact inline stat chips rendered inside a page header's right side.
 * Chips are optionally clickable (active ring) when they double as filters.
 */
export interface InlineStatChipItem {
  key: string
  value: number
  label: string
  tone?: 'green' | 'red' | 'blue' | 'amber'
  active?: boolean
  onClick?: () => void
}

defineProps<{ items: InlineStatChipItem[] }>()

const TONE_CLASS: Record<NonNullable<InlineStatChipItem['tone']>, string> = {
  green: 'text-green-600',
  red: 'text-red-600',
  blue: 'text-blue-600',
  amber: 'text-amber-600',
}
</script>

<template>
  <div class="stat-chips-inline">
    <div
      v-for="item in items"
      :key="item.key"
      :class="[
        'stat-chip',
        item.active ? 'stat-chip-active' : '',
        item.onClick ? '' : 'stat-chip-static',
      ]"
      @click="item.onClick?.()"
    >
      <span :class="['stat-chip-value', item.tone ? TONE_CLASS[item.tone] : '']">{{ item.value }}</span>
      <span class="stat-chip-label">{{ item.label }}</span>
    </div>
  </div>
</template>
