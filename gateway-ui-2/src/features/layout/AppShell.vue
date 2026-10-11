<script setup lang="ts">
/**
 * AppShell — main authenticated layout: sidebar + header + content + footer.
 * Vue port of the legacy AppShell.tsx. The instance-detail console renders its
 * own header, so the main header is hidden there.
 */
import { computed, ref } from 'vue'
import { RouterView, useRoute, useRouter } from 'vue-router'
import { useI18n } from 'vue-i18n'
import { Tooltip } from 'ant-design-vue'
import {
  CloudServerOutlined, ClusterOutlined, KeyOutlined, FundOutlined,
  MenuFoldOutlined, MenuUnfoldOutlined,
} from '@ant-design/icons-vue'
import GatewayLogo from '@/components/GatewayLogo.vue'
import LanguageSwitcher from '@/components/LanguageSwitcher.vue'
import UserMenu from '@/components/UserMenu.vue'

const collapsed = ref(false)
const router = useRouter()
const route = useRoute()
const { t } = useI18n()

const menuItems = computed(() => [
  { key: 'instances', label: t('menu.instances'), icon: CloudServerOutlined },
  { key: 'token-usage', label: t('menu.token_usage'), icon: FundOutlined },
  { key: 'kubernetes', label: t('menu.kubernetes'), icon: ClusterOutlined },
  { key: 'license', label: 'License', icon: KeyOutlined },
])

const selectedKey = computed(() => {
  const p = route.path
  if (p.startsWith('/kubernetes')) return 'kubernetes'
  if (p.startsWith('/license')) return 'license'
  if (p.startsWith('/token-usage')) return 'token-usage'
  return 'instances'
})

// The instance console owns its own header; hide the main one there.
const isInstanceDetail = computed(
  () => /^\/instances\/[^/]+\/?/.test(route.path) && route.path !== '/instances/create',
)

function handleMenuClick(info: { key: string | number }) {
  router.push(`/${info.key}`)
}
</script>

<template>
  <a-layout :style="{ minHeight: '100vh' }">
    <a-layout-sider
      v-model:collapsed="collapsed"
      :width="220"
      theme="dark"
      class="sidebar-premium"
      collapsible
      :trigger="null"
      :style="{ overflow: 'auto', height: '100vh', position: 'fixed', left: 0, top: 0, bottom: 0 }"
    >
      <div class="sidebar-header">
        <div class="sidebar-logo">
          <GatewayLogo class="logo-icon" :style="{ width: '28px', height: '28px' }" />
          <span v-if="!collapsed" class="logo-text">API Gateway</span>
        </div>
        <div class="sidebar-collapse-btn" @click="collapsed = !collapsed">
          <Tooltip :title="collapsed ? t('common.expand') : t('common.collapse')" placement="right">
            <MenuUnfoldOutlined v-if="collapsed" />
            <MenuFoldOutlined v-else />
          </Tooltip>
        </div>
      </div>
      <a-menu
        theme="dark"
        mode="inline"
        :selected-keys="[selectedKey]"
        @click="handleMenuClick"
      >
        <a-menu-item v-for="item in menuItems" :key="item.key">
          <component :is="item.icon" />
          <span>{{ item.label }}</span>
        </a-menu-item>
      </a-menu>
    </a-layout-sider>

    <a-layout :style="{ marginLeft: collapsed ? '80px' : '220px', transition: 'margin-left 0.2s' }">
      <a-layout-header v-if="!isInstanceDetail" class="main-header">
        <div class="header-left">
          <h1 class="header-title">{{ t('app.console_title') }}</h1>
          <span class="header-subtitle">{{ t('app.subtitle') }}</span>
        </div>
        <div class="header-right">
          <LanguageSwitcher />
          <UserMenu />
        </div>
      </a-layout-header>

      <a-layout-content class="main-content" :style="{ overflow: 'auto' }">
        <RouterView />
      </a-layout-content>

      <footer v-if="!isInstanceDetail" class="page-footer">
        <p class="footer-text">© {{ new Date().getFullYear() }} leoli. All rights reserved.</p>
      </footer>
    </a-layout>
  </a-layout>
</template>
