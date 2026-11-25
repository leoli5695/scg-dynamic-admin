<script setup lang="ts">
/**
 * Plugins page — fields aligned with PluginController (/api/plugins) +
 * model/PluginEntity.java. Controllers return bare entities, so httpClient
 * passes them through as-is.
 */
import { computed, ref, type Component } from 'vue';
import { useRoute } from 'vue-router';
import { useI18n } from 'vue-i18n';
import { Empty } from 'ant-design-vue';
import {
  AppstoreAddOutlined,
  CheckCircleOutlined,
  DeleteOutlined,
  EyeOutlined,
  FundOutlined,
  HddOutlined,
  PlayCircleOutlined,
  ReloadOutlined,
  StopOutlined,
  UploadOutlined,
} from '@/components/icons';
import { useRequest } from '@/composables/useRequest';
import { deletePlugin, disablePlugin, enablePlugin, listPlugins } from '@/api/plugins';
import type { Plugin } from '@/types/plugin';
import { formatServerTime } from '@/utils/serverTime';
import { message } from '@/utils/feedback';
import {
  getCategoryIcon,
  getPluginDescription,
  getPluginStatus,
  type PluginStatus,
  type PluginStatusKind,
} from './utils';
import UploadPluginModal from './components/UploadPluginModal.vue';
import PluginDetailDrawer from './components/PluginDetailDrawer.vue';
import DevGuideCard from './components/DevGuideCard.vue';
import './plugins.css';

interface MetricItem {
  key: string;
  title: string;
  value: number | string;
  icon: Component;
  subtitle: string;
  tone?: 'green' | 'purple' | 'amber';
  accent?: number;
}

const STATUS_PILL: Record<PluginStatusKind, string> = {
  active: 'plugin-pill-success',
  loading: 'plugin-pill-info',
  not_loaded: 'plugin-pill-warning',
  disabled: 'plugin-pill-neutral',
};

const route = useRoute();
const { t, locale } = useI18n();

const simpleImage = Empty.PRESENTED_IMAGE_SIMPLE

const instanceId = computed(() => route.params.instanceId as string | undefined);

const uploadOpen = ref(false);
const detailPlugin = ref<Plugin | null>(null);

const {
  data: pluginsData,
  loading: pluginsLoading,
  error: pluginsError,
  run: reload,
} = useRequest(
  (signal) => listPlugins(instanceId.value ? { instanceId: instanceId.value } : undefined, { signal }),
  {
    deps: [instanceId],
    onError: () => message.error(t('plugins.load_failed')),
  },
);

const plugins = computed<Plugin[]>(() => pluginsData.value ?? []);

const stats = computed(() => ({
  total: plugins.value.length,
  enabled: plugins.value.filter((plugin) => plugin.enabled).length,
  disabled: plugins.value.filter((plugin) => !plugin.enabled).length,
  loaded: plugins.value.filter((plugin) => plugin.loaded).length,
  registered: plugins.value.filter((plugin) => plugin.registered).length,
}));

const activeRate = computed(() => (
  stats.value.total ? Math.round((stats.value.registered / stats.value.total) * 100) : 0
));

const metrics = computed<MetricItem[]>(() => [
  {
    key: 'total',
    title: t('plugins.total_plugins'),
    value: stats.value.total,
    icon: AppstoreAddOutlined,
    subtitle: t('plugins.card.total_hint', { count: stats.value.registered }),
  },
  {
    key: 'enabled',
    title: t('plugins.enabled'),
    value: stats.value.enabled,
    icon: CheckCircleOutlined,
    subtitle: t('plugins.card.enabled_hint', { count: stats.value.disabled }),
    tone: 'green',
  },
  {
    key: 'loaded',
    title: t('plugins.loaded'),
    value: stats.value.loaded,
    icon: HddOutlined,
    subtitle: t('plugins.card.loaded_hint'),
    tone: 'purple',
  },
  {
    key: 'active',
    title: t('plugins.active_rate'),
    value: `${activeRate.value}%`,
    icon: FundOutlined,
    subtitle: t('plugins.card.active_hint'),
    tone: 'amber',
    accent: activeRate.value,
  },
]);

const columns = computed(() => [
  { title: t('plugins.plugin_name'), dataIndex: 'pluginName', key: 'pluginName', width: 230 },
  { title: t('plugins.version'), dataIndex: 'version', key: 'version', width: 110 },
  { title: t('plugins.category'), dataIndex: 'category', key: 'category', width: 150 },
  { title: t('plugins.status'), key: 'status', width: 130 },
  { title: t('plugins.description_col'), key: 'description', ellipsis: true },
  { title: t('plugins.created'), dataIndex: 'createdAt', key: 'createdAt', className: 'plugin-col-time' },
  { title: t('plugins.actions'), key: 'actions', width: 150 },
]);

const pagination = { defaultPageSize: 10 };

/** Table cells are narrow; keep popups at body level like the React shell. */
const getPopupContainer = () => document.body;

function statusOf(plugin: Plugin): PluginStatus {
  return getPluginStatus(plugin);
}

function statusPillClass(plugin: Plugin): string {
  return STATUS_PILL[statusOf(plugin).kind];
}

function localizedDescription(plugin: Plugin): string | undefined {
  return getPluginDescription(plugin, locale.value);
}

async function handleToggleEnabled(plugin: Plugin) {
  try {
    if (plugin.enabled) {
      await disablePlugin(plugin.pluginId);
      message.success(t('plugins.disable_success'));
    } else {
      await enablePlugin(plugin.pluginId);
      message.success(t('plugins.enable_success'));
    }
    reload();
  } catch {
    message.error(plugin.enabled ? t('plugins.disable_failed') : t('plugins.enable_failed'));
  }
}

async function handleDelete(pluginId: string) {
  try {
    await deletePlugin(pluginId);
    message.success(t('plugins.delete_success'));
    reload();
  } catch {
    message.error(t('plugins.delete_failed'));
  }
}
</script>

<template>
  <div class="plugins-page">
    <div class="page-header-modern">
      <div class="page-header-left">
        <span class="plugins-eyebrow">{{ t('plugins.eyebrow') }}</span>
        <h3 class="page-title-main has-glyph ant-typography">
          <span class="page-header-glyph" aria-hidden="true"><AppstoreAddOutlined /></span>
          {{ t('plugins.title') }}
        </h3>
        <span class="plugins-subtitle">{{ t('plugins.description') }}</span>
      </div>
      <div class="page-header-right">
        <a-button type="primary" @click="uploadOpen = true">
          <template #icon><UploadOutlined /></template>
          {{ t('plugins.upload') }}
        </a-button>
        <a-button :loading="pluginsLoading" @click="reload()">
          <template #icon><ReloadOutlined /></template>
          {{ t('plugins.refresh') }}
        </a-button>
      </div>
    </div>

    <div class="plugins-metrics">
      <div
        v-for="metric in metrics"
        :key="metric.key"
        :class="['plugin-metric', metric.tone ? `plugin-metric-${metric.tone}` : '']"
      >
        <div class="plugin-metric-heading">
          <span class="plugin-metric-title">{{ metric.title }}</span>
          <span class="plugin-metric-icon" aria-hidden="true">
            <component :is="metric.icon" />
          </span>
        </div>
        <div class="plugin-metric-value">{{ metric.value }}</div>
        <div class="plugin-metric-caption">{{ metric.subtitle }}</div>
        <span v-if="metric.accent !== undefined" class="plugin-metric-accent" aria-hidden="true">
          <i :style="{ width: `${metric.accent}%` }" />
        </span>
      </div>
    </div>

    <a-card
      v-if="pluginsError && plugins.length === 0"
      :bordered="false"
      class="plugins-state-card"
    >
      <a-result
        status="error"
        :title="t('plugins.load_failed')"
        :sub-title="pluginsError.message"
      >
        <template #extra>
          <a-button type="primary" :loading="pluginsLoading" @click="reload()">
            <template #icon><ReloadOutlined /></template>
            {{ t('common.refresh') }}
          </a-button>
        </template>
      </a-result>
    </a-card>

    <a-card v-else class="plugins-table-card" :bordered="false">
      <a-table
        :columns="columns"
        :data-source="plugins"
        row-key="pluginId"
        :loading="pluginsLoading"
        :pagination="plugins.length > pagination.defaultPageSize ? pagination : false"
        :scroll="{ x: 900 }"
      >
        <template #bodyCell="{ column, record }">
          <template v-if="column.key === 'pluginName'">
            <span class="plugin-name-chip" :title="record.pluginName">
              <span class="icon-tile icon-tile--sm" aria-hidden="true">
                <component :is="getCategoryIcon(record.category)" />
              </span>
              <span class="plugin-name-text">{{ record.pluginName }}</span>
            </span>
          </template>
          <template v-else-if="column.key === 'version'">
            <span class="plugin-version">v{{ record.version }}</span>
          </template>
          <template v-else-if="column.key === 'category'">
            <span class="plugin-category-pill">{{ (record.category || 'other').toUpperCase() }}</span>
          </template>
          <template v-else-if="column.key === 'status'">
            <span :class="['plugin-status-pill', statusPillClass(record)]">
              <i class="plugin-status-dot" aria-hidden="true" />
              {{ t(statusOf(record).labelKey) }}
            </span>
          </template>
          <template v-else-if="column.key === 'description'">
            <span class="plugin-description" :title="localizedDescription(record)">
              {{ localizedDescription(record) }}
            </span>
          </template>
          <template v-else-if="column.key === 'createdAt'">
            {{ formatServerTime(record.createdAt) }}
          </template>
          <template v-else-if="column.key === 'actions'">
            <div class="plugin-row-actions">
              <a-tooltip :title="t('plugins.view_details')" :get-popup-container="getPopupContainer">
                <a-button type="text" class="plugin-action" @click="detailPlugin = record">
                  <template #icon><EyeOutlined /></template>
                </a-button>
              </a-tooltip>
              <a-tooltip
                :title="record.enabled ? t('plugins.disable') : t('plugins.enable')"
                :get-popup-container="getPopupContainer"
              >
                <a-button type="text" class="plugin-action" @click="handleToggleEnabled(record)">
                  <template #icon>
                    <StopOutlined v-if="record.enabled" />
                    <PlayCircleOutlined v-else />
                  </template>
                </a-button>
              </a-tooltip>
              <a-popconfirm
                :title="t('plugins.delete_confirm')"
                :description="t('plugins.delete_confirm_desc')"
                :ok-text="t('common.yes')"
                :cancel-text="t('common.no')"
                :ok-button-props="{ danger: true }"
                :get-popup-container="getPopupContainer"
                overlay-class-name="plugin-confirm-popover"
                @confirm="handleDelete(record.pluginId)"
              >
                <a-tooltip :title="t('plugins.delete')" :get-popup-container="getPopupContainer">
                  <a-button type="text" class="plugin-action plugin-action-danger">
                    <template #icon><DeleteOutlined /></template>
                  </a-button>
                </a-tooltip>
              </a-popconfirm>
            </div>
          </template>
        </template>

        <template #emptyText>
          <a-empty :image="simpleImage" class="plugins-empty">
            <template #description>
              <span class="plugins-empty-text">
                <span class="plugins-empty-icon" aria-hidden="true"><AppstoreAddOutlined /></span>
                <span>{{ t('plugins.empty_description') }}</span>
              </span>
            </template>
            <a-button type="primary" @click="uploadOpen = true">
              <template #icon><UploadOutlined /></template>
              {{ t('plugins.upload') }}
            </a-button>
          </a-empty>
        </template>
      </a-table>
    </a-card>

    <DevGuideCard />

    <UploadPluginModal
      :open="uploadOpen"
      :instance-id="instanceId"
      @close="uploadOpen = false"
      @uploaded="reload"
    />

    <PluginDetailDrawer
      v-if="detailPlugin"
      :plugin="detailPlugin"
      @close="detailPlugin = null"
    />
  </div>
</template>