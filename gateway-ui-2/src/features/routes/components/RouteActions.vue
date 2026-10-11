<script setup lang="ts">
/**
 * Inline route actions: edit / detail / enable-disable / delete.
 */
import { useI18n } from 'vue-i18n'
import {
  DeleteOutlined, EditOutlined, EyeOutlined, PlayCircleOutlined, StopOutlined,
} from '@ant-design/icons-vue'
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
  <a-space>
    <a-tooltip :title="t('common.edit')">
      <a-button type="text" size="small" class="action-btn" @click="emit('edit', route)">
        <template #icon><EditOutlined /></template>
      </a-button>
    </a-tooltip>
    <a-tooltip :title="t('common.detail')">
      <a-button type="text" size="small" class="action-btn" @click="emit('detail', route)">
        <template #icon><EyeOutlined /></template>
      </a-button>
    </a-tooltip>
    <a-tooltip :title="route.enabled ? t('common.disable') : t('common.enable')">
      <a-button type="text" size="small" class="action-btn" @click="emit('toggle', route)">
        <template #icon><StopOutlined v-if="route.enabled" /><PlayCircleOutlined v-else /></template>
      </a-button>
    </a-tooltip>
    <a-popconfirm
      :title="`${t('common.delete')}: ${route.routeName || route.id}`"
      :ok-text="t('common.confirm')"
      :cancel-text="t('common.cancel')"
      @confirm="emit('delete', route)"
    >
      <a-tooltip :title="t('common.delete')">
        <a-button type="text" size="small" danger class="action-btn">
          <template #icon><DeleteOutlined /></template>
        </a-button>
      </a-tooltip>
    </a-popconfirm>
  </a-space>
</template>
