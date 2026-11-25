<script setup lang="ts">
/**
 * GatewayLogo — hexagonal portal with converging traffic and an outbound
 * beam. Aurora gradient ring, legible from 24px up. `class` / `style` fall
 * through to the root <svg> automatically.
 */
</script>

<template>
  <svg
    viewBox="0 0 32 32"
    fill="none"
    xmlns="http://www.w3.org/2000/svg"
  >
    <defs>
      <linearGradient id="logoRingGrad" x1="4.6" y1="4" x2="27.4" y2="29" gradientUnits="userSpaceOnUse">
        <stop offset="0" stop-color="#8ff0c8" />
        <stop offset="0.5" stop-color="#5ed3ea" />
        <stop offset="1" stop-color="#b18cff" />
      </linearGradient>
      <linearGradient id="logoBeamGrad" x1="5" y1="16" x2="26" y2="16" gradientUnits="userSpaceOnUse">
        <stop offset="0" stop-color="#cfe0ff" stop-opacity="0.55" />
        <stop offset="1" stop-color="#ffffff" />
      </linearGradient>
    </defs>
    <path
      d="M16 2.2 28 9.2v13.6L16 29.8 4 22.8V9.2Z"
      fill="#4c7bff10"
      stroke="url(#logoRingGrad)"
      stroke-width="5"
      stroke-linejoin="round"
      opacity="0.16"
    />
    <path
      d="M16 2.2 28 9.2v13.6L16 29.8 4 22.8V9.2Z"
      fill="#4c7bff0d"
      stroke="url(#logoRingGrad)"
      stroke-width="2.1"
      stroke-linejoin="round"
    />
    <path
      d="M5 8.9 13.5 15M5 16h8.5M5 23.1 13.5 17"
      stroke="#a9c6f5"
      stroke-width="1.4"
      stroke-linecap="round"
      opacity="0.7"
    />
    <path
      d="M13.5 16h11M19.9 11.7l4.5 4.3-4.5 4.3"
      stroke="url(#logoBeamGrad)"
      stroke-width="2"
      stroke-linecap="round"
      stroke-linejoin="round"
    />
  </svg>
</template>