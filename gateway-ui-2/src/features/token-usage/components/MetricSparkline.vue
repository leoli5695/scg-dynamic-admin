<script setup lang="ts">
/** Tiny area sparkline for metric cards — pure SVG, no chart framework, no animation. */
import { computed, useId } from 'vue'

const props = withDefaults(defineProps<{ data: number[]; tone?: string }>(), {
  tone: 'var(--primary-light)',
})

const gradientId = `token-spark-${useId()}`

const geometry = computed(() => {
  const data = props.data
  if (data.length < 2) return null
  const width = 100
  const height = 32
  const max = Math.max(...data)
  const min = Math.min(...data)
  const span = max - min || 1
  const points = data.map((value, index) => ({
    x: (index / (data.length - 1)) * width,
    y: height - 3 - ((value - min) / span) * (height - 6),
  }))
  let line = `M${points[0].x.toFixed(2)},${points[0].y.toFixed(2)}`
  for (let i = 0; i < points.length - 1; i += 1) {
    const p0 = points[i - 1] ?? points[i]
    const p1 = points[i]
    const p2 = points[i + 1]
    const p3 = points[i + 2] ?? p2
    const c1x = p1.x + (p2.x - p0.x) / 6
    const c1y = p1.y + (p2.y - p0.y) / 6
    const c2x = p2.x - (p3.x - p1.x) / 6
    const c2y = p2.y - (p3.y - p1.y) / 6
    line += ` C${c1x.toFixed(2)},${c1y.toFixed(2)} ${c2x.toFixed(2)},${c2y.toFixed(2)} ${p2.x.toFixed(2)},${p2.y.toFixed(2)}`
  }
  return {
    line,
    area: `${line} L${width},${height} L0,${height} Z`,
    last: points[points.length - 1],
  }
})
</script>

<template>
  <svg
    v-if="geometry"
    class="token-metric-spark"
    viewBox="0 0 100 32"
    preserveAspectRatio="none"
    aria-hidden="true"
  >
    <defs>
      <linearGradient :id="gradientId" x1="0" y1="0" x2="0" y2="1">
        <stop offset="0%" :stop-color="tone" stop-opacity="0.26" />
        <stop offset="100%" :stop-color="tone" stop-opacity="0" />
      </linearGradient>
    </defs>
    <path :d="geometry.area" :fill="`url(#${gradientId})`" />
    <path
      :d="geometry.line"
      fill="none"
      :stroke="tone"
      stroke-width="1.6"
      vector-effect="non-scaling-stroke"
      stroke-linecap="round"
      stroke-linejoin="round"
      opacity="0.9"
    />
    <circle :cx="geometry.last.x" :cy="geometry.last.y" r="1.8" :fill="tone" opacity="0.95" />
  </svg>
</template>