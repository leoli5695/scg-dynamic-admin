<script setup lang="ts">
/**
 * Route detail drawer — read-only route facts: target, gray rules, predicates
 * and plugin bindings rendered as key/value panels and code blocks.
 */
import { computed } from 'vue'
import { useI18n } from 'vue-i18n'
import {
  ApiOutlined, BranchesOutlined, CloseOutlined, CloudOutlined, CopyOutlined,
  DeleteOutlined, EditOutlined, FileTextOutlined, RobotOutlined,
  SplitCellsOutlined, ThunderboltOutlined,
} from '@/components/icons'
import type { RouteResponse, RouteServiceBinding } from '@/types/route'
import { message } from '@/utils/feedback'
import { copyText } from '@/utils/clipboard'

const props = defineProps<{ route: RouteResponse }>()
const emit = defineEmits<{
  (e: 'close'): void
  (e: 'edit', r: RouteResponse): void
  (e: 'delete', r: RouteResponse): void
}>()

const { t } = useI18n()

const isAi = computed(() => (props.route.category || 'NORMAL') === 'AI')

async function handleCopyId() {
  const ok = await copyText(props.route.id || '')
  if (ok) message.success(t('message.copy_success'))
  else message.error(t('message.copy_failed'))
}

function grayRuleTarget(rule: { targetVersion: string }): string {
  const targetService = props.route.services?.find((s: RouteServiceBinding) => {
    const sId = s?.serviceId?.replace('static://', '').replace('lb://', '') || s?.serviceId
    return sId === rule.targetVersion
  })
  return targetService?.serviceName || rule.targetVersion
}

function prettyArgs(args: Record<string, unknown> | undefined): string {
  return JSON.stringify(args ?? {}, null, 2)
}
</script>

<template>
  <a-drawer
    :open="true"
    placement="right"
    :closable="false"
    :width="560"
    root-class-name="route-detail-drawer"
    mask
    :mask-closable="true"
    :destroy-on-close="true"
    :z-index="999"
    @close="emit('close')"
  >
    <div class="drawer-content">
      <!-- Header -->
      <div class="drawer-header">
        <div class="drawer-header-left">
          <div class="drawer-icon">
            <RobotOutlined v-if="isAi" />
            <ApiOutlined v-else />
          </div>
          <div class="drawer-title-wrapper">
            <h4 class="drawer-title">{{ route.routeName || t('routes.detail_title') }}</h4>
            <div class="drawer-id-row">
              <a-tooltip :title="route.id || 'N/A'">
                <code class="drawer-id">{{ route.id || 'N/A' }}</code>
              </a-tooltip>
              <a-tooltip :title="t('common.copy_id')">
                <a-button type="text" size="small" class="copy-btn" @click="handleCopyId">
                  <template #icon><CopyOutlined /></template>
                </a-button>
              </a-tooltip>
            </div>
          </div>
        </div>
        <a-tooltip :title="t('common.close')">
          <a-button type="text" class="drawer-close-btn" :aria-label="t('common.close')" @click="emit('close')">
            <template #icon><CloseOutlined /></template>
          </a-button>
        </a-tooltip>
      </div>

      <!-- Status pills -->
      <div class="drawer-pills">
        <span class="status-badge" :class="route.enabled ? 'status-badge-success' : 'status-badge-neutral'">
          <span class="status-dot" :class="route.enabled ? 'status-dot-success' : 'status-dot-neutral'" />
          {{ route.enabled ? t('common.enabled') : t('common.disabled') }}
        </span>
        <span class="drawer-pill">{{ t('routes.order') }} {{ route.order ?? 0 }}</span>
        <span class="drawer-pill">
          {{ route.mode === 'MULTI' ? t('routes.multi_service') : t('routes.single_service') }}
        </span>
        <span v-if="isAi" class="drawer-pill drawer-pill-ai">{{ t('routes.category_ai') }}</span>
      </div>

      <!-- Target -->
      <section v-if="route.mode !== 'MULTI'" class="drawer-section">
        <div class="drawer-section-head"><CloudOutlined /> {{ t('routes.target_uri') }}</div>
        <div class="kv-panel">
          <div class="kv-item kv-item-wide">
            <span class="kv-label">URI</span>
            <span class="kv-value mono">{{ route.uri || 'N/A' }}</span>
          </div>
          <div v-if="route.serviceId" class="kv-item kv-item-wide">
            <span class="kv-label">{{ t('routes.target_service') }}</span>
            <span class="kv-value mono">{{ route.serviceId }}</span>
          </div>
        </div>
      </section>

      <section v-if="route.mode === 'MULTI' && route.services && route.services.length > 0" class="drawer-section">
        <div class="drawer-section-head">
          <SplitCellsOutlined />
          <span>{{ t('routes.services') }}</span>
          <span class="count">{{ route.services.length }}</span>
        </div>
        <div class="binding-list">
          <div v-for="(s, idx) in route.services" :key="idx" class="binding-card">
            <div class="binding-main">
              <span class="binding-name">{{ s.serviceName || s.serviceId }}</span>
              <span class="binding-weight">{{ s.weight }}%</span>
            </div>
            <div class="binding-meta">
              <span class="status-badge" :class="s.enabled ? 'status-badge-success' : 'status-badge-neutral'">
                {{ s.enabled ? t('common.enabled') : t('common.disabled') }}
              </span>
              <span v-if="s.type" class="binding-type">{{ s.type }}</span>
            </div>
          </div>
        </div>
      </section>

      <section v-if="isAi && route.aiUpstreams && route.aiUpstreams.length > 0" class="drawer-section">
        <div class="drawer-section-head">
          <RobotOutlined />
          <span>{{ t('routes.ai_target_configuration') }}</span>
          <span class="count">{{ route.aiUpstreams.length }}</span>
        </div>
        <div class="binding-list">
          <div v-for="(up, idx) in route.aiUpstreams" :key="idx" class="binding-card">
            <div class="binding-main">
              <span class="binding-name">{{ up.provider }}</span>
              <span class="binding-weight">{{ up.weight ?? 100 }}%</span>
            </div>
            <pre v-if="up.modelMapping && Object.keys(up.modelMapping).length > 0" class="code-block">{{ prettyArgs(up.modelMapping) }}</pre>
          </div>
        </div>
      </section>

      <section v-if="isAi && route.modelPredicates && route.modelPredicates.length > 0" class="drawer-section">
        <div class="drawer-section-head">
          <RobotOutlined />
          <span>{{ t('routes.ai_model_predicates') }}</span>
          <span class="count">{{ route.modelPredicates.length }}</span>
        </div>
        <div class="tags-container">
          <span v-for="(mp, idx) in route.modelPredicates" :key="idx" class="route-chip route-chip-predicate">
            <span class="route-chip-key">{{ mp.matchType || 'PREFIX' }}</span>
            <code class="route-chip-value">{{ mp.value }}</code>
          </span>
        </div>
      </section>

      <section v-if="route.grayRules?.enabled && route.grayRules?.rules && route.grayRules.rules.length > 0" class="drawer-section">
        <div class="drawer-section-head">
          <BranchesOutlined />
          <span>{{ t('routes.gray_rules') }}</span>
          <span class="count">{{ route.grayRules.rules.length }}</span>
        </div>
        <div class="binding-list">
          <div v-for="(rule, idx) in route.grayRules.rules" :key="idx" class="binding-card">
            <div class="binding-main">
              <span class="route-rule-type">{{ rule.type }}</span>
              <code v-if="rule.type !== 'WEIGHT'" class="route-rule-value">{{ rule.name }}={{ rule.value }}</code>
              <span v-else class="route-rule-value">{{ rule.value }}%</span>
            </div>
            <div class="binding-meta">
              <span class="binding-target">→ {{ grayRuleTarget(rule) }}</span>
            </div>
          </div>
        </div>
      </section>

      <section v-if="route.predicates && route.predicates.length > 0" class="drawer-section">
        <div class="drawer-section-head">
          <BranchesOutlined />
          <span>{{ t('routes.predicates') }}</span>
          <span class="count">{{ route.predicates.length }}</span>
        </div>
        <div class="code-item-list">
          <div v-for="(p, idx) in route.predicates" :key="idx" class="code-item">
            <div class="code-item-head">
              <span class="code-item-name">{{ p.name }}</span>
            </div>
            <pre class="code-block">{{ prettyArgs(p.args) }}</pre>
          </div>
        </div>
      </section>

      <section v-if="route.filters && route.filters.length > 0" class="drawer-section">
        <div class="drawer-section-head">
          <ThunderboltOutlined />
          <span>{{ t('routes.plugins') }}</span>
          <span class="count">{{ route.filters.length }}</span>
        </div>
        <div class="code-item-list">
          <div v-for="(f, idx) in route.filters" :key="idx" class="code-item">
            <div class="code-item-head">
              <span class="code-item-name">{{ t(`plugin.${f.name}.name`, { default: f.name }) }}</span>
            </div>
            <pre class="code-block">{{ prettyArgs(f.args) }}</pre>
          </div>
        </div>
      </section>

      <section v-if="route.description" class="drawer-section">
        <div class="drawer-section-head"><FileTextOutlined /> {{ t('routes.description') }}</div>
        <p class="drawer-desc">{{ route.description }}</p>
      </section>

      <section v-if="route.metadata && Object.keys(route.metadata).length > 0" class="drawer-section">
        <div class="drawer-section-head"><FileTextOutlined /> {{ t('routes.metadata') }}</div>
        <pre class="code-block">{{ prettyArgs(route.metadata) }}</pre>
      </section>

      <!-- Actions -->
      <div class="drawer-actions">
        <a-button type="primary" @click="emit('edit', route)">
          <template #icon><EditOutlined /></template>{{ t('common.edit') }}
        </a-button>
        <a-button danger @click="emit('delete', route)">
          <template #icon><DeleteOutlined /></template>{{ t('common.delete') }}
        </a-button>
      </div>
    </div>
  </a-drawer>
</template>