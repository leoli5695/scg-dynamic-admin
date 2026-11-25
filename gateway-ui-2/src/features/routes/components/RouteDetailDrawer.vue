<script setup lang="ts">
/**
 * Route detail side panel — identity header, status line, nested fact panels
 * for target / bindings / gray rules / predicates / plugins, sticky actions.
 */
import { computed } from 'vue'
import { useI18n } from 'vue-i18n'
import {
  ApiOutlined, BranchesOutlined, CloseOutlined, CloudOutlined, CopyOutlined,
  DeleteOutlined, EditOutlined, RobotOutlined,
  SplitCellsOutlined, ThunderboltOutlined,
} from '@/components/icons'
import type { RouteResponse, RouteServiceBinding } from '@/types/route'
import { message } from '@/utils/feedback'
import { copyText } from '@/utils/clipboard'
import VnBadge from '@/components/vn/VnBadge.vue'
import VnIconButton from '@/components/vn/VnIconButton.vue'

const props = defineProps<{ route: RouteResponse }>()
const emit = defineEmits<{
  (e: 'close'): void
  (e: 'edit', r: RouteResponse): void
  (e: 'delete', r: RouteResponse): void
}>()

const { t } = useI18n()

const isAi = computed(() => (props.route.category || 'NORMAL') === 'AI')
const displayId = computed(() => props.route.id || 'N/A')

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
    width="min(480px, 100vw)"
    :aria-label="route.routeName || t('routes.detail_title')"
    root-class-name="vn-portal vn-drawer"
    mask
    :mask-closable="true"
    :destroy-on-close="true"
    @close="emit('close')"
  >
    <div class="vn-detail">
      <div class="vn-detail-head">
        <div class="vn-detail-head-left">
          <span class="vn-detail-glyph" aria-hidden="true">
            <RobotOutlined v-if="isAi" />
            <ApiOutlined v-else />
          </span>
          <div style="min-width: 0">
            <h4 class="vn-detail-title">{{ route.routeName || t('routes.detail_title') }}</h4>
            <div class="vn-detail-idrow">
              <a-tooltip :title="displayId" overlay-class-name="vn-tooltip">
                <span class="vn-mono vn-detail-id">{{ displayId }}</span>
              </a-tooltip>
              <VnIconButton :icon="CopyOutlined" :label="t('common.copy_id')" inline @click="handleCopyId" />
            </div>
          </div>
        </div>
        <VnIconButton :icon="CloseOutlined" :label="t('common.close')" @click="emit('close')" />
      </div>

      <div class="vn-detail-scroll">
        <div class="vn-detail-pills">
          <VnBadge :tone="route.enabled ? 'ok' : 'idle'">{{ route.enabled ? t('common.enabled') : t('common.disabled') }}</VnBadge>
          <span class="vn-chip">{{ t('routes.order') }} <span class="vn-chip-num">{{ route.order ?? 0 }}</span></span>
          <span class="vn-chip">{{ route.mode === 'MULTI' ? t('routes.multi_service') : t('routes.single_service') }}</span>
          <span v-if="isAi" class="vn-chip">{{ t('routes.category_ai') }}</span>
        </div>

        <p v-if="route.description" class="vn-detail-desc">{{ route.description }}</p>

        <section v-if="route.mode !== 'MULTI'" class="vn-detail-section">
          <div class="vn-detail-section-head"><CloudOutlined /> {{ t('routes.target_uri') }}</div>
          <div class="vn-kv">
            <div class="vn-kv-item vn-kv-wide">
              <span class="vn-kv-label">URI</span>
              <span class="vn-kv-value vn-mono">{{ route.uri || 'N/A' }}</span>
            </div>
            <div v-if="route.serviceId" class="vn-kv-item vn-kv-wide">
              <span class="vn-kv-label">{{ t('routes.target_service') }}</span>
              <span class="vn-kv-value vn-mono">{{ route.serviceId }}</span>
            </div>
          </div>
        </section>

        <section v-if="route.mode === 'MULTI' && route.services && route.services.length > 0" class="vn-detail-section">
          <div class="vn-detail-section-head">
            <SplitCellsOutlined />
            <span>{{ t('routes.services') }}</span>
            <span class="vn-count">{{ route.services.length }}</span>
          </div>
          <div class="vn-instance-list">
            <div v-for="(s, idx) in route.services" :key="idx" class="vn-instance-card vn-binding-card">
              <div class="vn-binding-main">
                <span class="vn-binding-name" :title="s.serviceName || s.serviceId">{{ s.serviceName || s.serviceId }}</span>
                <span class="vn-binding-weight">{{ s.weight }}%</span>
              </div>
              <div class="vn-binding-meta">
                <VnBadge :tone="s.enabled ? 'ok' : 'idle'">{{ s.enabled ? t('common.enabled') : t('common.disabled') }}</VnBadge>
                <span v-if="s.type" class="vn-chip">{{ s.type }}</span>
              </div>
            </div>
          </div>
        </section>

        <section v-if="isAi && route.aiUpstreams && route.aiUpstreams.length > 0" class="vn-detail-section">
          <div class="vn-detail-section-head">
            <RobotOutlined />
            <span>{{ t('routes.ai_target_configuration') }}</span>
            <span class="vn-count">{{ route.aiUpstreams.length }}</span>
          </div>
          <div class="vn-instance-list">
            <div v-for="(up, idx) in route.aiUpstreams" :key="idx" class="vn-instance-card vn-binding-card">
              <div class="vn-binding-main">
                <span class="vn-binding-name">{{ up.provider }}</span>
                <span class="vn-binding-weight">{{ up.weight ?? 100 }}%</span>
              </div>
              <pre v-if="up.modelMapping && Object.keys(up.modelMapping).length > 0" class="vn-codeblock">{{ prettyArgs(up.modelMapping) }}</pre>
            </div>
          </div>
        </section>

        <section v-if="isAi && route.modelPredicates && route.modelPredicates.length > 0" class="vn-detail-section">
          <div class="vn-detail-section-head">
            <RobotOutlined />
            <span>{{ t('routes.ai_model_predicates') }}</span>
            <span class="vn-count">{{ route.modelPredicates.length }}</span>
          </div>
          <div class="vn-detail-pills">
            <span v-for="(mp, idx) in route.modelPredicates" :key="idx" class="vn-chip">
              <span class="vn-chip-key">{{ mp.matchType || 'PREFIX' }}</span>
              <span class="vn-chip-value">{{ mp.value }}</span>
            </span>
          </div>
        </section>

        <section v-if="route.grayRules?.enabled && route.grayRules?.rules && route.grayRules.rules.length > 0" class="vn-detail-section">
          <div class="vn-detail-section-head">
            <BranchesOutlined />
            <span>{{ t('routes.gray_rules') }}</span>
            <span class="vn-count">{{ route.grayRules.rules.length }}</span>
          </div>
          <div class="vn-instance-list">
            <div v-for="(rule, idx) in route.grayRules.rules" :key="idx" class="vn-instance-card vn-binding-card">
              <div class="vn-binding-main">
                <span class="vn-rule-type">{{ rule.type }}</span>
                <code v-if="rule.type !== 'WEIGHT'" class="vn-rule-value">{{ rule.name }}={{ rule.value }}</code>
                <span v-else class="vn-rule-value">{{ rule.value }}%</span>
                <span class="vn-binding-target">→ {{ grayRuleTarget(rule) }}</span>
              </div>
            </div>
          </div>
        </section>

        <section v-if="route.predicates && route.predicates.length > 0" class="vn-detail-section">
          <div class="vn-detail-section-head">
            <BranchesOutlined />
            <span>{{ t('routes.predicates') }}</span>
            <span class="vn-count">{{ route.predicates.length }}</span>
          </div>
          <div v-for="(p, idx) in route.predicates" :key="idx" class="vn-code-item">
            <span class="vn-code-item-name">{{ p.name }}</span>
            <pre class="vn-codeblock">{{ prettyArgs(p.args) }}</pre>
          </div>
        </section>

        <section v-if="route.filters && route.filters.length > 0" class="vn-detail-section">
          <div class="vn-detail-section-head">
            <ThunderboltOutlined />
            <span>{{ t('routes.plugins') }}</span>
            <span class="vn-count">{{ route.filters.length }}</span>
          </div>
          <div v-for="(f, idx) in route.filters" :key="idx" class="vn-code-item">
            <span class="vn-code-item-name">{{ t(`plugin.${f.name}.name`, { default: f.name }) }}</span>
            <pre class="vn-codeblock">{{ prettyArgs(f.args) }}</pre>
          </div>
        </section>

        <section v-if="route.metadata && Object.keys(route.metadata).length > 0" class="vn-detail-section">
          <div class="vn-detail-section-head">{{ t('routes.metadata') }}</div>
          <pre class="vn-codeblock">{{ prettyArgs(route.metadata) }}</pre>
        </section>
      </div>

      <div class="vn-detail-actions">
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