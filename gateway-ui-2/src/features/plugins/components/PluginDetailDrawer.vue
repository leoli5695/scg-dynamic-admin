<script setup lang="ts">
/**
 * Plugin detail drawer — full metadata of a single PluginEntity.
 */
import { useI18n } from 'vue-i18n';
import type { Plugin } from '@/types/plugin';
import {
  getCategoryIcon,
  getPluginDescription,
  getPluginStatus,
  type PluginStatusKind,
} from '../utils';
import { formatServerTime } from '@/utils/serverTime';

const props = defineProps<{ plugin: Plugin }>();
const emit = defineEmits<{ (e: 'close'): void }>();

const { t, locale } = useI18n();
const status = getPluginStatus(props.plugin);

const STATUS_PILL: Record<PluginStatusKind, string> = {
  active: 'plugin-pill-success',
  loading: 'plugin-pill-info',
  not_loaded: 'plugin-pill-warning',
  disabled: 'plugin-pill-neutral',
};

const yesNoPill = (value: boolean): string => (value ? 'plugin-pill-success' : 'plugin-pill-neutral');
</script>

<template>
  <a-drawer
    :open="true"
    :width="560"
    root-class-name="plugin-detail-drawer"
    :destroy-on-close="true"
    @close="emit('close')"
  >
    <template #title>
      <div class="plugin-detail-head">
        <span class="plugin-detail-icon" aria-hidden="true">
          <component :is="getCategoryIcon(plugin.category)" />
        </span>
        <span class="plugin-detail-head-text">
          <span class="plugin-detail-name" :title="plugin.pluginName">{{ plugin.pluginName }}</span>
          <span class="plugin-detail-meta">v{{ plugin.version }} · {{ plugin.category }}</span>
        </span>
      </div>
    </template>

    <div class="plugin-detail-pills">
      <span :class="['plugin-status-pill', STATUS_PILL[status.kind]]">
        <i class="plugin-status-dot" aria-hidden="true" />
        {{ t(status.labelKey) }}
      </span>
      <span :class="['plugin-detail-pill', yesNoPill(plugin.enabled)]">
        {{ t('plugins.enabled_label') }} · {{ plugin.enabled ? t('common.yes') : t('common.no') }}
      </span>
      <span :class="['plugin-detail-pill', yesNoPill(plugin.loaded)]">
        {{ t('plugins.loaded_label') }} · {{ plugin.loaded ? t('common.yes') : t('common.no') }}
      </span>
      <span :class="['plugin-detail-pill', yesNoPill(plugin.registered)]">
        {{ t('plugins.registered_label') }} · {{ plugin.registered ? t('common.yes') : t('common.no') }}
      </span>
    </div>

    <div class="plugin-detail-grid">
      <div class="plugin-detail-item is-wide">
        <span class="plugin-detail-label">{{ t('plugins.plugin_id') }}</span>
        <code class="plugin-detail-code">{{ plugin.pluginId }}</code>
      </div>
      <div class="plugin-detail-item is-wide">
        <span class="plugin-detail-label">{{ t('plugins.factory_class') }}</span>
        <code class="plugin-detail-code">{{ plugin.pluginClass }}</code>
      </div>
      <div class="plugin-detail-item is-wide">
        <span class="plugin-detail-label">{{ t('plugins.config_class') }}</span>
        <code v-if="plugin.configClass" class="plugin-detail-code">{{ plugin.configClass }}</code>
        <span v-else class="plugin-detail-value">{{ t('plugins.no_config_class') }}</span>
      </div>
      <div class="plugin-detail-item">
        <span class="plugin-detail-label">{{ t('plugins.jar_hash') }}</span>
        <a-tooltip v-if="plugin.jarHash" :title="plugin.jarHash">
          <code class="plugin-detail-code">{{ plugin.jarHash.substring(0, 24) }}…</code>
        </a-tooltip>
        <span v-else class="plugin-detail-value">-</span>
      </div>
      <div class="plugin-detail-item">
        <span class="plugin-detail-label">{{ t('plugins.category_label') }}</span>
        <span class="plugin-detail-value">{{ plugin.category }}</span>
      </div>
      <div class="plugin-detail-item">
        <span class="plugin-detail-label">{{ t('plugins.created_at') }}</span>
        <span class="plugin-detail-value">{{ formatServerTime(plugin.createdAt) }}</span>
      </div>
      <div class="plugin-detail-item">
        <span class="plugin-detail-label">{{ t('plugins.updated_at') }}</span>
        <span class="plugin-detail-value">{{ formatServerTime(plugin.updatedAt) }}</span>
      </div>
      <div class="plugin-detail-item is-wide">
        <span class="plugin-detail-label">{{ t('plugins.description_label') }}</span>
        <p class="plugin-detail-description">{{ getPluginDescription(plugin, locale) || '-' }}</p>
      </div>
    </div>
  </a-drawer>
</template>