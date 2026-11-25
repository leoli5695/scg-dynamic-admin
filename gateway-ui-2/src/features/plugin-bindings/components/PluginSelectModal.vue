<script setup lang="ts">
/**
 * Step 1 of the create flow: pick an enabled plugin, grouped by category.
 * Mirrors the React PluginSelectModal (cards instead of a plain list).
 */
import { computed, type CSSProperties } from 'vue'
import { useI18n } from 'vue-i18n'
import { theme as antdTheme, Empty } from 'ant-design-vue'
import { ArrowRightOutlined, AppstoreAddOutlined, PlusOutlined } from '@/components/icons'
import { BUILTIN_JAR_PATH, type Plugin } from '@/types/plugin'
import {
  getCategoryIcon,
  getCategoryTone,
  getPluginDescription,
  toneForLegacyColor,
  withAlpha,
} from '@/features/plugins/utils'
import { categoryLabelKey, TONE_TOKEN } from '../utils'

const props = defineProps<{
  plugins: Plugin[]
  loading: boolean
}>()
const emit = defineEmits<{
  (e: 'select', plugin: Plugin): void
  (e: 'cancel'): void
}>()

const { t, te, locale } = useI18n()
const { token } = antdTheme.useToken()

const simpleImage = Empty.PRESENTED_IMAGE_SIMPLE

const groups = computed(() => {
  const grouped = new Map<string, Plugin[]>()
  props.plugins.forEach((plugin) => {
    const key = plugin.category || 'other'
    const list = grouped.get(key)
    if (list) list.push(plugin)
    else grouped.set(key, [plugin])
  })
  return [...grouped.entries()].map(([key, plugins]) => ({ key, plugins }))
})

function categoryLabel(category?: string | null): string {
  const key = categoryLabelKey(category)
  return key && te(key) ? t(key) : (category || 'other')
}

function iconStyle(plugin: Plugin): CSSProperties {
  const tone = plugin.color ? toneForLegacyColor(plugin.color) : getCategoryTone(plugin.category)
  const color = String(token.value[TONE_TOKEN[tone]])
  return {
    color,
    background: withAlpha(color, 0.12),
    borderColor: withAlpha(color, 0.28),
  }
}

function descriptionOf(plugin: Plugin): string | undefined {
  return getPluginDescription(plugin, locale.value)
}
</script>

<template>
  <a-modal
    :open="true"
    :footer="null"
    :width="880"
    wrap-class-name="plugin-bindings-picker-modal"
    :destroy-on-close="true"
    @cancel="emit('cancel')"
  >
    <template #title>
      <div class="binding-modal-header">
        <span class="binding-modal-icon"><PlusOutlined /></span>
        <span class="binding-modal-title-text">
          <span class="binding-modal-title">{{ t('plugin_bindings.select_plugin') }}</span>
          <span class="binding-modal-subtitle">{{ t('plugin_bindings.select_plugin_desc') }}</span>
        </span>
      </div>
    </template>

    <a-spin :spinning="loading">
      <a-empty
        v-if="plugins.length === 0 && !loading"
        :image="simpleImage"
        class="binding-picker-empty"
      >
        <template #description>
          <span class="binding-picker-empty-text">
            <AppstoreAddOutlined />
            {{ t('plugin_bindings.plugins_empty') }}
          </span>
        </template>
      </a-empty>

      <div v-else class="binding-picker-groups">
        <section v-for="group in groups" :key="group.key" class="binding-picker-group">
          <div class="binding-picker-group-title">
            {{ categoryLabel(group.key) }}
            <span class="binding-picker-group-count">{{ group.plugins.length }}</span>
          </div>
          <div class="binding-picker-grid">
            <button
              v-for="plugin in group.plugins"
              :key="plugin.pluginId"
              type="button"
              class="binding-plugin-card"
              :disabled="loading"
              :title="plugin.pluginName"
              @click="emit('select', plugin)"
            >
              <span class="binding-plugin-icon" :style="iconStyle(plugin)">
                <component :is="getCategoryIcon(plugin.category)" />
              </span>
              <span class="binding-plugin-body">
                <span class="binding-plugin-heading">
                  <strong class="binding-plugin-name">{{ plugin.pluginName }}</strong>
                  <span
                    v-if="plugin.jarPath !== BUILTIN_JAR_PATH"
                    class="binding-plugin-kind"
                  >
                    {{ t('plugin_bindings.custom') }}
                  </span>
                </span>
                <span class="binding-plugin-desc">{{ descriptionOf(plugin) || plugin.pluginClass }}</span>
              </span>
              <ArrowRightOutlined class="binding-plugin-arrow" />
            </button>
          </div>
        </section>
      </div>
    </a-spin>
  </a-modal>
</template>