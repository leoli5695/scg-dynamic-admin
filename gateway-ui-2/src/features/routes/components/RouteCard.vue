<script setup lang="ts">
/**
 * Route card in the routes grid.
 */
import { computed } from 'vue'
import { useI18n } from 'vue-i18n'
import {
  ApiOutlined, BranchesOutlined, CloudOutlined, CopyOutlined,
  SplitCellsOutlined, ThunderboltOutlined,
} from '@ant-design/icons-vue'
import type { RoutePredicate, RouteResponse } from '@/types/route'
import { extractMethods } from '../utils'
import { message } from '@/utils/feedback'
import RouteActions from './RouteActions.vue'

const props = defineProps<{
  route: RouteResponse
}>()
const emit = defineEmits<{
  (e: 'edit', r: RouteResponse): void
  (e: 'detail', r: RouteResponse): void
  (e: 'toggle', r: RouteResponse): void
  (e: 'delete', r: RouteResponse): void
}>()

const { t } = useI18n()

const predicateCount = computed(() => props.route.predicates?.length || 0)
const pluginCount = computed(() => props.route.filters?.length || 0)
const isMultiService = computed(() => props.route.mode === 'MULTI' && !!props.route.services && props.route.services.length > 0)

/** Display value for a predicate tag. */
function predicateValue(predicate: RoutePredicate): string {
  const { name } = predicate
  const args: Record<string, unknown> = predicate.args && typeof predicate.args === 'object' ? predicate.args : {}
  if (name === 'Path') return String(args.pattern || '/**')
  if (name === 'Host') return String(args.patterns || args.pattern || '')
  if (name === 'Method') return extractMethods(args).join(', ')
  if (name === 'Header') return String(args.header || args.name || '')
  return JSON.stringify(predicate.args)
}

function handleCopyId(e: MouseEvent) {
  e.stopPropagation()
  navigator.clipboard.writeText(props.route.id || '')
  message.success(t('message.copy_success'))
}
</script>

<template>
  <a-card class="route-card" hoverable>
    <div class="route-card-header">
      <div class="route-info">
        <div class="route-icon"><ApiOutlined /></div>
        <div class="route-details">
          <span class="route-name ant-typography" style="font-weight: 600">
            {{ route.routeName || (route.id && route.id.substring(0, 8)) || 'Unknown' }}
          </span>
          <div class="route-id-row">
            <span class="route-id ant-typography" style="color: var(--text-secondary)">{{ route.id ? route.id.substring(0, 16) : 'N/A' }}...</span>
            <a-tooltip :title="t('common.copy')">
              <a-button type="text" size="small" class="copy-btn" @click="handleCopyId">
                <template #icon><CopyOutlined /></template>
              </a-button>
            </a-tooltip>
          </div>
        </div>
      </div>
      <div class="route-status">
        <a-badge :status="route.enabled ? 'success' : 'default'" :text="route.enabled ? t('common.enabled') : t('common.disabled')" />
      </div>
      <RouteActions :route="route" @edit="emit('edit', $event)" @detail="emit('detail', $event)" @toggle="emit('toggle', $event)" @delete="emit('delete', $event)" />
    </div>

    <!-- Target service(s) -->
    <div class="route-section">
      <span class="section-label ant-typography" style="color: var(--text-secondary)">
        <SplitCellsOutlined v-if="isMultiService" /><CloudOutlined v-else />
        {{ isMultiService ? t('routes.services') : t('routes.target_uri') }}
      </span>
      <div class="tags-container">
        <template v-if="isMultiService">
          <a-tag v-for="(s, idx) in route.services" :key="idx" class="service-tag" :color="s.enabled ? 'blue' : 'default'">
            {{ s.serviceName || s.serviceId }} ({{ s.weight }}%)
          </a-tag>
        </template>
        <code v-else class="ant-typography">{{ route.uri || 'N/A' }}</code>
      </div>
    </div>

    <!-- Predicates -->
    <div class="route-section">
      <span class="section-label ant-typography" style="color: var(--text-secondary)">
        <BranchesOutlined /> {{ t('routes.predicates') }} ({{ predicateCount }})
      </span>
      <div class="tags-container">
        <template v-if="predicateCount > 0">
          <a-tooltip v-for="p in route.predicates" :key="p.name" :title="t(`predicate.${p.name}.detail`, { default: '' })" placement="top">
            <a-tag class="predicate-tag">
              <BranchesOutlined /> {{ p.name }}: <code style="font-family: 'JetBrains Mono', monospace">{{ predicateValue(p) }}</code>
            </a-tag>
          </a-tooltip>
        </template>
        <span v-else class="no-data ant-typography" style="color: var(--text-secondary)">{{ t('routes.no_predicates') }}</span>
      </div>
    </div>

    <!-- Plugins -->
    <div class="route-section">
      <span class="section-label ant-typography" style="color: var(--text-secondary)">
        <ThunderboltOutlined /> {{ t('routes.plugins') }} ({{ pluginCount }})
      </span>
      <div class="tags-container">
        <template v-if="pluginCount > 0">
          <a-tooltip v-for="(f, idx) in route.filters" :key="idx" :title="t(`plugin.${f.name}.detail`, { default: '' })" placement="top">
            <a-tag class="plugin-tag">
              <ThunderboltOutlined /> {{ t(`plugin.${f.name}.name`, { default: f.name }) }}
            </a-tag>
          </a-tooltip>
        </template>
        <span v-else class="no-data ant-typography" style="color: var(--text-secondary)">{{ t('routes.no_plugins') }}</span>
      </div>
    </div>

    <!-- Meta -->
    <div class="route-meta">
      <span class="ant-typography" style="color: var(--text-secondary)">Order: {{ route.order ?? 0 }}</span>
      <a-tooltip v-if="route.description" :title="route.description">
        <span class="route-desc ant-typography" style="color: var(--text-secondary)">{{ route.description }}</span>
      </a-tooltip>
    </div>
  </a-card>
</template>

<style scoped>
.predicate-tag {
  border-radius: 9999px;
  padding: 4px 12px;
  font-size: 13px;
  font-weight: 500;
  background-color: rgba(59, 130, 246, 0.15);
  color: #60a5fa;
  border: 1px solid rgba(59, 130, 246, 0.3);
  cursor: help;
}
.plugin-tag {
  border-radius: 9999px;
  padding: 4px 12px;
  font-size: 13px;
  font-weight: 500;
  background-color: rgba(16, 185, 129, 0.15);
  color: #34d399;
  border: 1px solid rgba(16, 185, 129, 0.3);
  cursor: help;
}
</style>
