<script setup lang="ts">
/**
 * Route detail drawer — full route info in a side panel.
 * Mounted only while a route is selected.
 */
import { useI18n } from 'vue-i18n'
import {
  ApiOutlined, BranchesOutlined, CloseOutlined, CloudOutlined,
  DeleteOutlined, EditOutlined, FileTextOutlined, SplitCellsOutlined,
  ThunderboltOutlined,
} from '@ant-design/icons-vue'
import type { RouteResponse, RouteServiceBinding } from '@/types/route'
import { modal } from '@/utils/feedback'

const props = defineProps<{ route: RouteResponse }>()
const emit = defineEmits<{
  (e: 'close'): void
  (e: 'edit', r: RouteResponse): void
  (e: 'delete', r: RouteResponse): void
}>()

const { t } = useI18n()

function handleDeleteClick() {
  modal.confirm({
    title: t('common.confirm'),
    content: t('message.confirm_delete_route', { name: props.route.routeName || props.route.id }),
    okText: t('common.delete'),
    okType: 'danger',
    cancelText: t('common.cancel'),
    onOk: () => emit('delete', props.route),
  })
}

function grayRuleTarget(rule: { targetVersion: string }): string {
  const targetService = props.route.services?.find((s: RouteServiceBinding) => {
    const sId = s?.serviceId?.replace('static://', '').replace('lb://', '') || s?.serviceId
    return sId === rule.targetVersion
  })
  return targetService?.serviceName || rule.targetVersion
}
</script>

<template>
  <a-drawer
    :open="true"
    placement="right"
    :closable="false"
    :width="520"
    class="route-detail-drawer"
    mask
    :mask-closable="true"
    :destroy-on-close="true"
    :z-index="999"
    @close="emit('close')"
  >
    <div class="drawer-content">
      <div class="drawer-header">
        <div class="drawer-header-left">
          <div class="drawer-icon route-icon"><ApiOutlined /></div>
          <div class="drawer-title-wrapper">
            <h4 class="drawer-title ant-typography">{{ route.routeName || 'Route Detail' }}</h4>
            <a-typography-text :copyable="{ text: route.id }" style="color: var(--text-secondary)">
              {{ route.id?.substring(0, 20) }}...
            </a-typography-text>
          </div>
        </div>
        <a-button type="text" class="drawer-close-btn" @click="emit('close')">
          <template #icon><CloseOutlined /></template>
        </a-button>
      </div>

      <div class="drawer-status-bar">
        <a-badge :status="route.enabled ? 'success' : 'default'" :text="route.enabled ? t('common.enabled') : t('common.disabled')" />
        <a-tag color="blue">Order: {{ route.order ?? 0 }}</a-tag>
        <a-tag :color="route.mode === 'MULTI' ? 'purple' : 'cyan'">
          {{ route.mode === 'MULTI' ? t('routes.multi_service') : t('routes.single_service') }}
        </a-tag>
      </div>

      <div v-if="route.mode !== 'MULTI'" class="drawer-section">
        <div class="section-title"><CloudOutlined /> {{ t('routes.target_uri') }}</div>
        <div class="uri-display">
          <a-typography-text code copyable>{{ route.uri || 'N/A' }}</a-typography-text>
        </div>
      </div>

      <div v-if="route.mode !== 'MULTI' && route.serviceId" class="drawer-section">
        <div class="section-title"><CloudOutlined /> {{ t('routes.target_service') }}</div>
        <a-typography-text code>{{ route.serviceId }}</a-typography-text>
      </div>

      <div v-if="route.mode === 'MULTI' && route.services && route.services.length > 0" class="drawer-section">
        <div class="section-title"><SplitCellsOutlined /> {{ t('routes.services') }} <span class="count">{{ route.services.length }}</span></div>
        <div class="services-detail-list">
          <div v-for="(s, idx) in route.services" :key="idx" :class="['service-detail-card', s.enabled ? 'healthy' : 'unhealthy']">
            <div class="service-main-info">
              <span class="ant-typography" style="font-weight: 600">{{ s.serviceName || s.serviceId }}</span>
              <a-tag :color="s.enabled ? 'green' : 'default'">{{ s.enabled ? t('common.enabled') : t('common.disabled') }}</a-tag>
            </div>
            <div class="service-meta">
              <span>{{ t('routes.weight') }}: {{ s.weight }}%</span>
              <a-tag v-if="s.type" :color="s.type === 'STATIC' ? 'orange' : 'blue'" :style="{ marginLeft: '8px' }">{{ s.type }}</a-tag>
            </div>
          </div>
        </div>
      </div>

      <div v-if="route.grayRules?.enabled && route.grayRules?.rules && route.grayRules.rules.length > 0" class="drawer-section">
        <div class="section-title"><BranchesOutlined /> {{ t('routes.gray_rules') }} <span class="count">{{ route.grayRules.rules.length }}</span></div>
        <div class="gray-rules-detail-list">
          <div v-for="(rule, idx) in route.grayRules.rules" :key="idx" class="gray-rule-detail-card">
            <a-tag color="gold">{{ rule.type }}</a-tag>
            <a-typography-text v-if="rule.type !== 'WEIGHT'" code>{{ rule.name }}={{ rule.value }}</a-typography-text>
            <a-typography-text v-else>{{ rule.value }}%</a-typography-text>
            <a-typography-text style="color: var(--text-secondary)">→ {{ grayRuleTarget(rule) }}</a-typography-text>
          </div>
        </div>
      </div>

      <div v-if="route.description" class="drawer-section">
        <div class="section-title"><FileTextOutlined /> {{ t('routes.description') }}</div>
        <a-typography-text style="color: var(--text-secondary)">{{ route.description }}</a-typography-text>
      </div>

      <div v-if="route.predicates && route.predicates.length > 0" class="drawer-section">
        <div class="section-title"><BranchesOutlined /> {{ t('routes.predicates') }} <span class="count">{{ route.predicates.length }}</span></div>
        <div class="predicates-detail-list">
          <div v-for="(p, idx) in route.predicates" :key="idx" class="predicate-detail-card">
            <a-tag color="purple">{{ p.name }}</a-tag>
            <pre class="detail-json">{{ JSON.stringify(p.args, null, 2) }}</pre>
          </div>
        </div>
      </div>

      <div v-if="route.filters && route.filters.length > 0" class="drawer-section">
        <div class="section-title"><ThunderboltOutlined /> {{ t('routes.plugins') }} <span class="count">{{ route.filters.length }}</span></div>
        <div class="plugins-detail-list">
          <div v-for="(f, idx) in route.filters" :key="idx" class="plugin-detail-card">
            <a-tag color="blue">{{ t(`plugin.${f.name}.name`, { default: f.name }) }}</a-tag>
            <pre class="detail-json">{{ JSON.stringify(f.args, null, 2) }}</pre>
          </div>
        </div>
      </div>

      <div class="drawer-actions">
        <a-button type="primary" @click="emit('edit', route)">
          <template #icon><EditOutlined /></template>{{ t('common.edit') }}
        </a-button>
        <a-button danger @click="handleDeleteClick">
          <template #icon><DeleteOutlined /></template>{{ t('common.delete') }}
        </a-button>
      </div>
    </div>
  </a-drawer>
</template>
