<script setup lang="ts">
/**
 * App root — ConfigProvider (theme + antd locale) wrapping antd's <App> context
 * (which powers the theme-aware message/modal/notification bridge) and the router
 * outlet. Mirrors the legacy React App.tsx.
 */
import { computed } from 'vue'
import { RouterView } from 'vue-router'
import { useI18n } from 'vue-i18n'
import { ConfigProvider, App as AntdApp } from 'ant-design-vue'
import zhCN from 'ant-design-vue/es/locale/zh_CN'
import enUS from 'ant-design-vue/es/locale/en_US'
import theme from '@/theme'
import AppBridge from '@/components/AppBridge.vue'

const { locale } = useI18n()
const antdLocale = computed(() => (locale.value?.startsWith('zh') ? zhCN : enUS))

// Keep popups (select/dropdown/datepicker) inside their trigger's parent so they
// scroll/clip with the layout, matching the legacy getPopupContainer behaviour.
function getPopupContainer(triggerNode?: HTMLElement): HTMLElement {
  return triggerNode?.parentElement instanceof HTMLElement ? triggerNode.parentElement : document.body
}
</script>

<template>
  <ConfigProvider :theme="theme" :locale="antdLocale" :get-popup-container="getPopupContainer">
    <AntdApp>
      <AppBridge />
      <RouterView />
    </AntdApp>
  </ConfigProvider>
</template>
