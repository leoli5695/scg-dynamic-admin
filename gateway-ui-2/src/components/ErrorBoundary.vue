<script setup lang="ts">
/**
 * ErrorBoundary — Vue equivalent of the legacy React class ErrorBoundary.
 * Catches errors thrown by slotted children (onErrorCaptured) and shows a
 * recoverable fallback instead of blanking the whole app.
 */
import { ref, onErrorCaptured } from 'vue'
import { Result, Button } from 'ant-design-vue'
import { ReloadOutlined, HomeOutlined } from '@ant-design/icons-vue'

const hasError = ref(false)
const errorMessage = ref('')

onErrorCaptured((err: unknown) => {
  hasError.value = true
  errorMessage.value = err instanceof Error ? err.message : String(err)
  console.error('ErrorBoundary caught an error:', err)
  return false
})

function reload() {
  window.location.reload()
}
function goHome() {
  window.location.href = '/instances'
}
</script>

<template>
  <div
    v-if="hasError"
    :style="{ display: 'flex', justifyContent: 'center', alignItems: 'center', minHeight: '400px', padding: '24px' }"
  >
    <Result
      status="error"
      title="页面加载失败"
      :sub-title="errorMessage || '组件渲染时发生错误，请尝试刷新页面'"
    >
      <template #extra>
        <Button type="primary" @click="reload">
          <template #icon><ReloadOutlined /></template>
          刷新页面
        </Button>
        <Button @click="goHome">
          <template #icon><HomeOutlined /></template>
          返回列表
        </Button>
      </template>
    </Result>
  </div>
  <slot v-else />
</template>
