<script setup lang="ts">
/**
 * Search bar with title, search input, status filter, and create button.
 */
import { useI18n } from 'vue-i18n'
import { BranchesOutlined, PlusOutlined, SearchOutlined } from '@/components/icons'

export type RouteStatusFilter = 'all' | 'enabled' | 'disabled'

defineProps<{
  searchTerm: string
  statusFilter: RouteStatusFilter
  onCreateClick: () => void
}>()
const emit = defineEmits<{
  (e: 'update:searchTerm', v: string): void
  (e: 'update:statusFilter', v: RouteStatusFilter): void
}>()

const { t } = useI18n()
</script>

<template>
  <div class="page-header-modern">
    <div class="page-header-left">
      <h3 class="page-title-main has-glyph">
        <span class="page-header-glyph" aria-hidden="true"><BranchesOutlined /></span>
        {{ t('routes.title') }}
      </h3>
      <span class="page-subtitle">{{ t('routes.page_description_helper') }}</span>
    </div>
    <div class="page-header-right">
      <slot name="stats" />
      <a-input
        :value="searchTerm"
        :placeholder="t('routes.search_placeholder')"
        allow-clear
        class="search-input"
        @update:value="emit('update:searchTerm', $event)"
      >
        <template #suffix>
          <SearchOutlined class="search-suffix-icon" />
        </template>
      </a-input>
      <a-select
        :value="statusFilter"
        :placeholder="t('routes.status_filter_placeholder')"
        class="status-select"
        @change="emit('update:statusFilter', $event)"
      >
        <a-select-option value="all">{{ t('common.all') }}</a-select-option>
        <a-select-option value="enabled">{{ t('common.enabled') }}</a-select-option>
        <a-select-option value="disabled">{{ t('common.disabled') }}</a-select-option>
      </a-select>
      <a-button type="primary" class="create-btn" @click="onCreateClick">
        <template #icon><PlusOutlined /></template>{{ t('routes.create') }}
      </a-button>
    </div>
  </div>
</template>
