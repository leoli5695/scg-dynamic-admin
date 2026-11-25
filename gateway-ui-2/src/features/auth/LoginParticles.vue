<script setup lang="ts">
/**
 * Six small compositor layers, without a canvas or a JavaScript frame loop.
 * Pauses while the page is scrolling or hidden (ported from the React version).
 */
import { onMounted, onUnmounted, ref } from 'vue'

const containerRef = ref<HTMLDivElement | null>(null)
let resumeTimer: ReturnType<typeof setTimeout> | undefined
let scrolling = false

function onVisibilityChange() {
  containerRef.value?.toggleAttribute('data-hidden', document.hidden)
}

function onScroll() {
  // Update once when scrolling starts, rather than on every scroll event.
  if (!scrolling) {
    containerRef.value?.setAttribute('data-scrolling', '')
    scrolling = true
  }
  clearTimeout(resumeTimer)
  resumeTimer = setTimeout(() => {
    scrolling = false
    containerRef.value?.removeAttribute('data-scrolling')
  }, 200)
}

onMounted(() => {
  onVisibilityChange()
  document.addEventListener('visibilitychange', onVisibilityChange)
  window.addEventListener('scroll', onScroll, { passive: true })
})

onUnmounted(() => {
  clearTimeout(resumeTimer)
  document.removeEventListener('visibilitychange', onVisibilityChange)
  window.removeEventListener('scroll', onScroll)
})
</script>

<template>
  <div ref="containerRef" class="login-particles" aria-hidden="true">
    <span v-for="index in 6" :key="index" class="login-particle" />
  </div>
</template>