<script setup lang="ts">
/**
 * Inline route actions: edit / detail / enable-disable / delete.
 */
import { useI18n } from 'vue-i18n'
import {
  DeleteOutlined, EditOutlined, EyeOutlined, PauseCircleOutlined, PlayCircleOutlined,
} from '@/components/icons'
import type { RouteResponse } from '@/types/route'

defineProps<{ route: RouteResponse }>()
const emit = defineEmits<{
  (e: 'edit', r: RouteResponse): void
  (e: 'detail', r: RouteResponse): void
  (e: 'toggle', r: RouteResponse): void
  (e: 'delete', r: RouteResponse): void
}>()

const { t } = useI18n()
</script>

<template>
  <div class="route-actions">
    <a-tooltip :title="t('common.detail')">
      <a-button type="text" size="small" class="action-btn" :aria-label="t('common.detail')" @click="emit('detail', route)">
        <template #icon><EyeOutlined /></template>
      </a-button>
    </a-tooltip>
    <a-tooltip :title="t('common.edit')">
      <a-button type="text" size="small" class="action-btn" :aria-label="t('common.edit')" @click="emit('edit', route)">
        <template #icon><EditOutlined /></template>
      </a-button>
    </a-tooltip>
    <a-tooltip :title="route.enabled ? t('common.disable') : t('common.enable')">
      <a-button
        type="text"
        size="small"
        class="action-btn"
        :aria-label="route.enabled ? t('common.disable') : t('common.enable')"
        @click="emit('toggle', route)"
      >
        <template #icon><PauseCircleOutlined v-if="route.enabled" /><PlayCircleOutlined v-else /></template>
      </a-button>
    </a-tooltip>
    <a-tooltip :title="t('common.delete')">
      <a-button type="text" size="small" danger class="action-btn" :aria-label="t('common.delete')" @click="emit('delete', route)">
        <template #icon><DeleteOutlined /></template>
      </a-button>
    </a-tooltip>
  </div>
</template>