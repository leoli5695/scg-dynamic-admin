<script setup lang="ts">
import { useI18n } from 'vue-i18n'
import { CompassOutlined, CopyOutlined, DeleteOutlined, EditOutlined, GlobalOutlined, PauseCircleOutlined, PlayCircleOutlined } from '@/components/icons'
import type { StrategyDefinition, StrategyTypeInfo } from '@/types/strategy'
import { message } from '@/utils/feedback'
import { copyText } from '@/utils/clipboard'
import VnBadge from '@/components/vn/VnBadge.vue'
import VnIconButton from '@/components/vn/VnIconButton.vue'
import { toneForLegacyColor } from '../tones'
import StrategyTypeIcon from './StrategyTypeIcon.vue'

defineProps<{
  rows: StrategyDefinition[]
  typeInfoOf: (strategy: StrategyDefinition) => StrategyTypeInfo | undefined
  typeLabelOf: (strategy: StrategyDefinition) => string
  summaryOf: (strategy: StrategyDefinition) => { segments: string[]; hidden: number }
}>()
const emit = defineEmits<{
  (e: 'edit', s: StrategyDefinition): void
  (e: 'toggle', s: StrategyDefinition): void
  (e: 'delete', s: StrategyDefinition): void
}>()

const { t } = useI18n()

async function copyId(s: StrategyDefinition) {
  const ok = await copyText(s.strategyId)
  if (ok) message.success(t('message.copy_success'))
  else message.error(t('message.copy_failed'))
}
</script>

<template>
  <div class="vn-panel vn-panel-flush vn-tablewrap">
    <table class="vn-table">
      <thead>
        <tr>
          <th>{{ t('strategy.name') }}</th>
          <th>{{ t('common.status') }}</th>
          <th>{{ t('strategy.type') }}</th>
          <th>{{ t('strategy.scope') }}</th>
          <th>{{ t('strategy.config') }}</th>
          <th class="vn-table-actions-head">{{ t('common.actions') }}</th>
        </tr>
      </thead>
      <tbody>
        <tr v-for="s in rows" :key="s.strategyId" class="vn-row">
          <td>
            <div class="vn-row-identity">
              <span :class="['vn-row-glyph', `vn-stone-${toneForLegacyColor(typeInfoOf(s)?.color)}`]" aria-hidden="true">
                <StrategyTypeIcon :name="typeInfoOf(s)?.icon" />
              </span>
              <div class="vn-row-names">
                <button type="button" class="vn-tile-name" :title="s.strategyName" @click="emit('edit', s)">{{ s.strategyName }}</button>
                <span class="vn-mono vn-row-id">{{ s.strategyId }}</span>
              </div>
            </div>
          </td>
          <td>
            <VnBadge :tone="s.enabled ? 'ok' : 'idle'">{{ s.enabled ? t('common.enabled') : t('common.disabled') }}</VnBadge>
          </td>
          <td class="vn-row-dim">{{ typeLabelOf(s) }}</td>
          <td>
            <span class="vn-chip">
              <GlobalOutlined v-if="s.scope === 'GLOBAL'" />
              <CompassOutlined v-else />
              {{ s.scope === 'GLOBAL' ? t('strategy.scope_global') : t('strategy.scope_route') }}
            </span>
          </td>
          <td>
            <span class="vn-strategy-summary" :title="summaryOf(s).segments.join(' · ')">
              {{ summaryOf(s).segments.join(' · ') || '—' }}
              <template v-if="summaryOf(s).hidden > 0"> (+{{ summaryOf(s).hidden }})</template>
            </span>
          </td>
          <td>
            <div class="vn-iconbtn-group">
              <VnIconButton
                :icon="s.enabled ? PauseCircleOutlined : PlayCircleOutlined"
                :label="s.enabled ? t('common.disable') : t('common.enable')"
                @click="emit('toggle', s)"
              />
              <VnIconButton :icon="EditOutlined" :label="t('common.edit')" @click="emit('edit', s)" />
              <VnIconButton :icon="CopyOutlined" :label="t('common.copy_id')" inline @click="copyId(s)" />
              <VnIconButton :icon="DeleteOutlined" :label="t('common.delete')" danger @click="emit('delete', s)" />
            </div>
          </td>
        </tr>
      </tbody>
    </table>
  </div>
</template>