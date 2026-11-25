<script setup lang="ts">
/**
 * Route tile in the routes grid: identity head, status meta and fixed
 * target / predicate / plugin bands so every card in a row aligns.
 */
import { computed } from 'vue'
import { useI18n } from 'vue-i18n'
import {
  ApiOutlined, BranchesOutlined, CloudOutlined, EditOutlined,
  PauseCircleOutlined, PlayCircleOutlined, RobotOutlined,
  SplitCellsOutlined, ThunderboltOutlined,
} from '@/components/icons'
import type { RoutePredicate, RouteResponse } from '@/types/route'
import { extractMethods } from '../utils'
import VnBadge from '@/components/vn/VnBadge.vue'
import VnIconButton from '@/components/vn/VnIconButton.vue'
import RouteActionsMenu from './RouteActionsMenu.vue'

const props = defineProps<{ route: RouteResponse }>()
const emit = defineEmits<{
  (e: 'edit', r: RouteResponse): void
  (e: 'detail', r: RouteResponse): void
  (e: 'toggle', r: RouteResponse): void
  (e: 'delete', r: RouteResponse): void
}>()

const { t } = useI18n()

// Bands clip their chips; a tooltip rendered into the band would be clipped
// with them, so chip popups escape to the body (vn-tooltip keeps the matte skin).
const popupContainer = () => document.body

const isAi = computed(() => (props.route.category || 'NORMAL') === 'AI')
const isMultiService = computed(() => props.route.mode === 'MULTI' && !!props.route.services && props.route.services.length > 0)
const displayName = computed(() => props.route.routeName || (props.route.id && props.route.id.substring(0, 8)) || 'Unknown')

const predicates = computed(() => props.route.predicates ?? [])
const plugins = computed(() => props.route.filters ?? [])
const bindings = computed(() => props.route.services ?? [])

const MAX_PREDICATES = 2
const MAX_PLUGINS = 3
const MAX_BINDINGS = 2
const shownPredicates = computed(() => predicates.value.slice(0, MAX_PREDICATES))
const shownPlugins = computed(() => plugins.value.slice(0, MAX_PLUGINS))
const shownBindings = computed(() => bindings.value.slice(0, MAX_BINDINGS))
const hiddenPredicates = computed(() => predicates.value.length - shownPredicates.value.length)
const hiddenPlugins = computed(() => plugins.value.length - shownPlugins.value.length)
const hiddenBindings = computed(() => bindings.value.length - shownBindings.value.length)

/** Display value for a predicate chip. */
function predicateValue(predicate: RoutePredicate): string {
  const { name } = predicate
  const args: Record<string, unknown> = predicate.args && typeof predicate.args === 'object' ? predicate.args : {}
  if (name === 'Path') return String(args.pattern || '/**')
  if (name === 'Host') return String(args.patterns || args.pattern || '')
  if (name === 'Method') return extractMethods(args).join(', ')
  if (name === 'Header') return String(args.header || args.name || '')
  return JSON.stringify(predicate.args)
}
</script>

<template>
  <article :class="['vn-panel', 'vn-tile', 'vn-rail-' + (route.enabled ? 'ok' : 'idle')]">
    <div class="vn-tile-head">
      <div class="vn-tile-identity">
        <span :class="['vn-tile-glyph', route.enabled ? 'vn-ink-ok' : 'vn-ink-idle']" aria-hidden="true">
          <RobotOutlined v-if="isAi" />
          <ApiOutlined v-else />
        </span>
        <button type="button" class="vn-tile-name" :title="route.routeName || route.id" @click="emit('detail', route)">
          {{ displayName }}
        </button>
      </div>
      <div class="vn-iconbtn-group">
        <VnIconButton
          :icon="route.enabled ? PauseCircleOutlined : PlayCircleOutlined"
          :label="route.enabled ? t('common.disable') : t('common.enable')"
          @click="emit('toggle', route)"
        />
        <VnIconButton :icon="EditOutlined" :label="t('common.edit')" @click="emit('edit', route)" />
        <RouteActionsMenu :route-id="route.id" @delete="emit('delete', route)" />
      </div>
    </div>

    <div class="vn-tile-meta">
      <VnBadge :tone="route.enabled ? 'ok' : 'idle'">{{ route.enabled ? t('common.enabled') : t('common.disabled') }}</VnBadge>
      <span class="vn-chip">{{ t('routes.order') }} <span class="vn-chip-num">{{ route.order ?? 0 }}</span></span>
      <span v-if="isMultiService" class="vn-chip">{{ t('routes.multi_service') }}</span>
      <span v-if="isAi" class="vn-chip">{{ t('routes.category_ai') }}</span>
    </div>

    <div class="vn-tile-band">
      <span class="vn-band-label">
        <SplitCellsOutlined v-if="isMultiService" /><CloudOutlined v-else />
        {{ isMultiService ? t('routes.services') : t('routes.target_uri') }}
      </span>
      <span v-if="hiddenBindings > 0" class="vn-band-more">+{{ hiddenBindings }}</span>
      <span v-if="isMultiService" class="vn-chips">
        <span v-for="(s, idx) in shownBindings" :key="idx" class="vn-chip" :title="s.serviceName || s.serviceId">
          <span class="vn-chip-text">{{ s.serviceName || s.serviceId }}</span>
          <span class="vn-chip-weight">{{ s.weight }}%</span>
        </span>
      </span>
      <span v-else class="vn-route-uri" :title="route.uri">{{ route.uri || 'N/A' }}</span>
    </div>

    <div class="vn-tile-band">
      <span class="vn-band-label">
        <BranchesOutlined /> {{ t('routes.predicates') }}
        <span class="vn-band-count">{{ predicates.length }}</span>
      </span>
      <span v-if="hiddenPredicates > 0" class="vn-band-more">+{{ hiddenPredicates }}</span>
      <span v-if="predicates.length" class="vn-chips">
        <a-tooltip
          v-for="p in shownPredicates"
          :key="p.name"
          :title="t(`predicate.${p.name}.detail`, { default: '' })"
          overlay-class-name="vn-tooltip"
          :get-popup-container="popupContainer"
        >
          <span class="vn-chip">
            <span class="vn-chip-key">{{ p.name }}</span>
            <span class="vn-chip-value">{{ predicateValue(p) }}</span>
          </span>
        </a-tooltip>
      </span>
      <span v-else class="vn-tile-empty">{{ t('routes.no_predicates') }}</span>
    </div>

    <div class="vn-tile-band">
      <span class="vn-band-label">
        <ThunderboltOutlined /> {{ t('routes.plugins') }}
        <span class="vn-band-count">{{ plugins.length }}</span>
      </span>
      <span v-if="hiddenPlugins > 0" class="vn-band-more">+{{ hiddenPlugins }}</span>
      <span v-if="plugins.length" class="vn-chips">
        <a-tooltip
          v-for="(f, idx) in shownPlugins"
          :key="idx"
          :title="t(`plugin.${f.name}.detail`, { default: '' })"
          overlay-class-name="vn-tooltip"
          :get-popup-container="popupContainer"
        >
          <span class="vn-chip">{{ t(`plugin.${f.name}.name`, { default: f.name }) }}</span>
        </a-tooltip>
      </span>
      <span v-else class="vn-tile-empty">{{ t('routes.no_plugins') }}</span>
    </div>
  </article>
</template>