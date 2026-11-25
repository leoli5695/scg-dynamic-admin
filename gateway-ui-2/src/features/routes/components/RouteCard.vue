<script setup lang="ts">
/**
 * Route card in the routes grid: identity, status, target, predicate/filter
 * summary chips and the route actions.
 */
import { computed } from 'vue'
import { useI18n } from 'vue-i18n'
import {
  ApiOutlined, BranchesOutlined, CloudOutlined, CopyOutlined, RobotOutlined,
  SplitCellsOutlined, ThunderboltOutlined,
} from '@/components/icons'
import type { RoutePredicate, RouteResponse } from '@/types/route'
import { extractMethods } from '../utils'
import { message } from '@/utils/feedback'
import { copyText } from '@/utils/clipboard'
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
const isAi = computed(() => (props.route.category || 'NORMAL') === 'AI')

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

async function handleCopyId(e: MouseEvent) {
  e.stopPropagation()
  const ok = await copyText(props.route.id || '')
  if (ok) message.success(t('message.copy_success'))
  else message.error(t('message.copy_failed'))
}
</script>

<template>
  <a-card :class="['route-card', !route.enabled ? 'route-card-disabled' : '']">
    <div class="route-card-header">
      <div class="route-info">
        <div class="route-icon icon-tile">
          <RobotOutlined v-if="isAi" />
          <ApiOutlined v-else />
        </div>
        <div class="route-details">
          <div class="route-name-row">
            <a-tooltip :title="route.routeName || route.id">
              <span class="route-name">{{ route.routeName || (route.id && route.id.substring(0, 8)) || 'Unknown' }}</span>
            </a-tooltip>
          </div>
          <div class="route-id-row">
            <a-tooltip :title="route.id || 'N/A'">
              <span class="route-id">{{ route.id || 'N/A' }}</span>
            </a-tooltip>
            <a-tooltip :title="t('common.copy_id')">
              <a-button type="text" size="small" class="copy-btn" @click.stop="handleCopyId">
                <template #icon><CopyOutlined /></template>
              </a-button>
            </a-tooltip>
          </div>
        </div>
      </div>
      <RouteActions :route="route" @edit="emit('edit', $event)" @detail="emit('detail', $event)" @toggle="emit('toggle', $event)" @delete="emit('delete', $event)" />
    </div>

    <div class="route-meta">
      <span class="status-badge" :class="route.enabled ? 'status-badge-success' : 'status-badge-neutral'">
        <span class="status-dot" :class="route.enabled ? 'status-dot-success' : 'status-dot-neutral'" />
        {{ route.enabled ? t('common.enabled') : t('common.disabled') }}
      </span>
      <span class="route-meta-pill">{{ t('routes.order') }} {{ route.order ?? 0 }}</span>
      <span v-if="isMultiService" class="route-meta-pill">{{ t('routes.multi_service') }}</span>
      <span v-if="isAi" class="route-meta-pill route-meta-pill-ai">{{ t('routes.category_ai') }}</span>
    </div>

    <!-- Target service(s) -->
    <div class="route-section">
      <span class="section-label">
        <SplitCellsOutlined v-if="isMultiService" /><CloudOutlined v-else />
        {{ isMultiService ? t('routes.services') : t('routes.target_uri') }}
      </span>
      <div class="tags-container">
        <template v-if="isMultiService">
          <span v-for="(s, idx) in route.services" :key="idx" class="route-chip route-chip-service">
            {{ s.serviceName || s.serviceId }}
            <span class="route-chip-weight">{{ s.weight }}%</span>
          </span>
        </template>
        <code v-else class="route-uri">{{ route.uri || 'N/A' }}</code>
      </div>
    </div>

    <!-- Predicates -->
    <div class="route-section">
      <span class="section-label">
        <BranchesOutlined /> {{ t('routes.predicates') }}
        <span class="count">{{ predicateCount }}</span>
      </span>
      <div class="tags-container">
        <template v-if="predicateCount > 0">
          <a-tooltip
            v-for="p in route.predicates"
            :key="p.name"
            :title="t(`predicate.${p.name}.detail`, { default: '' })"
            placement="top"
          >
            <span class="route-chip route-chip-predicate">
              <span class="route-chip-key">{{ p.name }}</span>
              <code class="route-chip-value">{{ predicateValue(p) }}</code>
            </span>
          </a-tooltip>
        </template>
        <span v-else class="no-data">{{ t('routes.no_predicates') }}</span>
      </div>
    </div>

    <!-- Plugins -->
    <div class="route-section">
      <span class="section-label">
        <ThunderboltOutlined /> {{ t('routes.plugins') }}
        <span class="count">{{ pluginCount }}</span>
      </span>
      <div class="tags-container">
        <template v-if="pluginCount > 0">
          <a-tooltip
            v-for="(f, idx) in route.filters"
            :key="idx"
            :title="t(`plugin.${f.name}.detail`, { default: '' })"
            placement="top"
          >
            <span class="route-chip route-chip-plugin">
              {{ t(`plugin.${f.name}.name`, { default: f.name }) }}
            </span>
          </a-tooltip>
        </template>
        <span v-else class="no-data">{{ t('routes.no_plugins') }}</span>
      </div>
    </div>

    <p v-if="route.description" class="route-description">
      <a-tooltip :title="route.description">
        <span class="route-description-text">{{ route.description }}</span>
      </a-tooltip>
    </p>
  </a-card>
</template>