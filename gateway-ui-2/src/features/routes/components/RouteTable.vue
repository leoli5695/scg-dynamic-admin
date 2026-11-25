<script setup lang="ts">
import { useI18n } from 'vue-i18n'
import {
  ApiOutlined, CopyOutlined, DeleteOutlined, EditOutlined, EyeOutlined,
  PauseCircleOutlined, PlayCircleOutlined, RobotOutlined,
} from '@/components/icons'
import type { RouteResponse } from '@/types/route'
import { message } from '@/utils/feedback'
import { copyText } from '@/utils/clipboard'
import VnBadge from '@/components/vn/VnBadge.vue'
import VnIconButton from '@/components/vn/VnIconButton.vue'

defineProps<{ rows: RouteResponse[] }>()
const emit = defineEmits<{
  (e: 'detail', r: RouteResponse): void
  (e: 'edit', r: RouteResponse): void
  (e: 'toggle', r: RouteResponse): void
  (e: 'delete', r: RouteResponse): void
}>()

const { t } = useI18n()

const isMulti = (r: RouteResponse) => r.mode === 'MULTI' && !!r.services && r.services.length > 0

async function copyId(r: RouteResponse) {
  const ok = await copyText(r.id || '')
  if (ok) message.success(t('message.copy_success'))
  else message.error(t('message.copy_failed'))
}
</script>

<template>
  <div class="vn-panel vn-panel-flush vn-tablewrap">
    <table class="vn-table">
      <thead>
        <tr>
          <th>{{ t('routes.name') }}</th>
          <th>{{ t('common.status') }}</th>
          <th>{{ t('routes.order') }}</th>
          <th>{{ t('routes.target_uri') }}</th>
          <th>{{ t('routes.predicates') }}</th>
          <th>{{ t('routes.plugins') }}</th>
          <th class="vn-table-actions-head">{{ t('common.actions') }}</th>
        </tr>
      </thead>
      <tbody>
        <tr v-for="r in rows" :key="r.id" class="vn-row">
          <td>
            <div class="vn-row-identity">
              <span class="vn-row-glyph" aria-hidden="true">
                <RobotOutlined v-if="(r.category || 'NORMAL') === 'AI'" />
                <ApiOutlined v-else />
              </span>
              <div class="vn-row-names">
                <button type="button" class="vn-tile-name" :title="r.routeName || r.id" @click="emit('detail', r)">
                  {{ r.routeName || (r.id && r.id.substring(0, 8)) || 'Unknown' }}
                </button>
                <span class="vn-mono vn-row-id">{{ r.id || 'N/A' }}</span>
              </div>
            </div>
          </td>
          <td>
            <VnBadge :tone="r.enabled ? 'ok' : 'idle'">{{ r.enabled ? t('common.enabled') : t('common.disabled') }}</VnBadge>
          </td>
          <td class="vn-row-dim">{{ r.order ?? 0 }}</td>
          <td>
            <span v-if="isMulti(r)" class="vn-chip">
              <span class="vn-chip-text">{{ r.services![0].serviceName || r.services![0].serviceId }}</span>
              <span v-if="r.services!.length > 1" class="vn-chip-num">+{{ r.services!.length - 1 }}</span>
            </span>
            <span v-else class="vn-route-target" :title="r.uri">{{ r.uri || 'N/A' }}</span>
          </td>
          <td><span class="vn-chip"><span class="vn-chip-num">{{ (r.predicates || []).length }}</span></span></td>
          <td><span class="vn-chip"><span class="vn-chip-num">{{ (r.filters || []).length }}</span></span></td>
          <td>
            <div class="vn-iconbtn-group">
              <VnIconButton :icon="EyeOutlined" :label="t('common.detail')" @click="emit('detail', r)" />
              <VnIconButton :icon="EditOutlined" :label="t('common.edit')" @click="emit('edit', r)" />
              <VnIconButton
                :icon="r.enabled ? PauseCircleOutlined : PlayCircleOutlined"
                :label="r.enabled ? t('common.disable') : t('common.enable')"
                @click="emit('toggle', r)"
              />
              <VnIconButton :icon="CopyOutlined" :label="t('common.copy_id')" inline @click="copyId(r)" />
              <VnIconButton :icon="DeleteOutlined" :label="t('common.delete')" danger @click="emit('delete', r)" />
            </div>
          </td>
        </tr>
      </tbody>
    </table>
  </div>
</template>